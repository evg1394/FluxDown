//! 已导入主题库。唯一事实源是 agent 偏好 `appearance.custom_themes.<id>`（值为主题文件原文，
//! 见 `fluxdown_protocol::custom_theme_key`），随配置同步在设备间逐主题同步。
//!
//! app 观察设置存储的偏好视图（含未回执的本地编辑）时调用 [`sync_theme_library`]，把这些偏好
//! 解析并注册到主题 crate；外观分区的导入 / 删除只写偏好，注册与注销都由这条投影完成，
//! 云端拉到的增删改与本机操作走同一路径。

use std::{
    collections::BTreeMap,
    io,
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use fluxdown_protocol::{
    MAX_CUSTOM_THEME_ID_LEN, custom_theme_fits_sync, custom_theme_id, custom_theme_key,
};
use fluxdown_ui_theme::{
    ACCENT_TOKEN_PATHS, BuiltinBase, BuiltinThemeId, ColorTokens, Diagnostic, DiagnosticKind,
    ResolveOptions, ThemeDocument, ThemeMode, ThemeParseError, ThemeSelection, TokenLayer,
    TokenValue, active_theme, color_hex, custom_theme, register_custom_theme, resolve,
    resolve_with, unregister_custom_theme,
};
use gpui::{App, Context, Global, Hsla, SharedString};
use serde_json::Value;

use crate::store::SettingsStore;

/// 一个已导入并注册的主题。
#[derive(Clone)]
pub(crate) struct ImportedTheme {
    pub id: SharedString,
    /// `meta.name`，缺省为 id。
    pub name: SharedString,
    pub document: Arc<ThemeDocument>,
    preview_dark: ColorTokens,
    preview_light: ColorTokens,
}

impl ImportedTheme {
    fn new(id: String, document: ThemeDocument) -> Self {
        let name = document
            .meta
            .as_ref()
            .and_then(|meta| meta.name.as_deref())
            .map(str::trim)
            .filter(|name| !name.is_empty())
            .map_or_else(|| id.clone(), str::to_owned);
        let preview = |mode| resolve(&document, mode).0.to_theme(1.).base.colors;
        Self {
            preview_dark: preview(ThemeMode::Dark),
            preview_light: preview(ThemeMode::Light),
            id: SharedString::from(id),
            name: SharedString::from(name),
            document: Arc::new(document),
        }
    }

    /// 卡片预览色（纯文件解析，不含用户强调色，与内置卡片一致）。
    pub fn preview(&self, mode: ThemeMode) -> ColorTokens {
        if mode.is_dark() {
            self.preview_dark
        } else {
            self.preview_light
        }
    }

    pub fn available_in(&self, mode: ThemeMode) -> bool {
        theme_available_in(&self.document, mode)
    }
}

/// 主题能否放进 `mode` 槽位，满足任一即可：
/// 1. 文件含该模式层（`dark` / `light` 非空）；
/// 2. `extends` 为该模式外观的单模式预设（如 `builtin:nord` → 暗色）；
/// 3. 文件没有任何模式倾向：两个模式层都为空，且 `extends` 不是单模式预设
///    （缺省、`builtin:default` 或未知值）——只改共享 `tokens` 的主题两个槽位都可用。
pub(crate) fn theme_available_in(document: &ThemeDocument, mode: ThemeMode) -> bool {
    let has_layer = |mode| !document.layer(TokenLayer::for_mode(mode)).is_empty();
    let preset_mode = document
        .extends
        .as_deref()
        .and_then(BuiltinBase::parse)
        .and_then(BuiltinBase::preset)
        .map(BuiltinThemeId::appearance);
    if has_layer(mode) || preset_mode == Some(mode) {
        return true;
    }
    let other = if mode.is_dark() {
        ThemeMode::Light
    } else {
        ThemeMode::Dark
    };
    preset_mode.is_none() && !has_layer(other)
}

/// 一条主题偏好的投影：原文与解析结果（`None` = 无法解析；原文不变时不再重试）。
struct LibraryEntry {
    text: String,
    theme: Option<ImportedTheme>,
}

#[derive(Default)]
struct ThemeLibraryState {
    /// 库内 id → 投影，按 id 升序。
    entries: BTreeMap<String, LibraryEntry>,
}

impl Global for ThemeLibraryState {}

/// 一次投影需要对主题 crate 做的注册变化。
#[derive(Default)]
struct LibraryChanges {
    unregister: Vec<String>,
    register: Vec<ImportedTheme>,
    failures: Vec<String>,
}

/// 偏好视图 → 已注册主题：只解析原文变化的条目，删掉偏好里已不存在（或被写成墓碑）的条目。
/// 须在应用外观偏好之前调用，`custom:<id>` 选择才能直接命中；找不到的 id 由主题 crate
/// 回退到该槽位的内置默认主题。返回无法解析的条目说明，由调用方记录。
pub fn sync_theme_library(preferences: &BTreeMap<String, Value>, cx: &mut App) -> Vec<String> {
    let changes = reconcile(
        &mut cx.default_global::<ThemeLibraryState>().entries,
        preferences,
    );
    for id in &changes.unregister {
        unregister_custom_theme(id, cx);
    }
    for theme in changes.register {
        register_custom_theme(theme.id, theme.document, cx);
    }
    changes.failures
}

fn reconcile(
    entries: &mut BTreeMap<String, LibraryEntry>,
    preferences: &BTreeMap<String, Value>,
) -> LibraryChanges {
    let desired = preferences
        .iter()
        .filter_map(|(key, value)| Some((custom_theme_id(key)?, value.as_str()?)))
        .collect::<BTreeMap<_, _>>();
    let mut changes = LibraryChanges::default();
    entries.retain(|id, _| {
        let keep = desired.contains_key(id);
        if !keep {
            changes.unregister.push(id.clone());
        }
        keep
    });
    for (id, text) in desired {
        if entries.get(&id).is_some_and(|entry| entry.text == text) {
            continue;
        }
        let theme = match ThemeDocument::parse(text) {
            Ok((document, _)) => {
                let theme = ImportedTheme::new(id.clone(), document);
                changes.register.push(theme.clone());
                Some(theme)
            }
            Err(error) => {
                changes.failures.push(format!("{id}: {error}"));
                // 原文变成了无法解析的内容：旧版本也不再代表库内状态。
                changes.unregister.push(id.clone());
                None
            }
        };
        entries.insert(
            id,
            LibraryEntry {
                text: text.to_owned(),
                theme,
            },
        );
    }
    changes
}

/// 已导入的主题（库内 id 升序）。
pub(crate) fn imported_themes(cx: &App) -> Vec<ImportedTheme> {
    cx.try_global::<ThemeLibraryState>()
        .map(|state| {
            state
                .entries
                .values()
                .filter_map(|entry| entry.theme.clone())
                .collect()
        })
        .unwrap_or_default()
}

/// 单个文件导入失败的原因。
#[derive(Debug)]
pub(crate) enum ImportError {
    Read(io::Error),
    Parse(ThemeParseError),
    /// 原文超过同步值上限（`fluxdown_protocol::MAX_SYNC_VALUE_BYTES`）。
    TooLarge,
    /// 本机服务未连接，偏好只读。
    Unavailable,
}

impl ImportError {
    /// 对应的 i18n 文案键。
    pub(crate) fn i18n_key(&self) -> &'static str {
        match self {
            Self::Read(_) => "themeImportReadFailed",
            Self::Parse(ThemeParseError::InvalidJson(_)) => "themeImportInvalidJson",
            Self::Parse(ThemeParseError::NotAnObject) => "themeImportNotObject",
            Self::Parse(ThemeParseError::UnsupportedFormat(_)) => "themeImportUnsupportedFormat",
            Self::TooLarge => "themeImportTooLarge",
            Self::Unavailable => "themeImportSaveFailed",
        }
    }

    /// 系统错误详情（读文件失败时）；其余原因已由文案键表达。
    pub(crate) fn io_detail(&self) -> Option<&io::Error> {
        match self {
            Self::Read(error) => Some(error),
            Self::Parse(_) | Self::TooLarge | Self::Unavailable => None,
        }
    }
}

/// 解析通过、尚未入库的导入。
pub(crate) struct ParsedImport {
    text: String,
    preferred_id: Option<String>,
    pub diagnostics: Vec<Diagnostic>,
}

/// 解析（含 v1 / Flutter 格式）并确认原文可作为同步值保存；可在后台线程执行。
pub(crate) fn parse_import(text: String) -> Result<ParsedImport, ImportError> {
    let (document, diagnostics) = ThemeDocument::parse(&text).map_err(ImportError::Parse)?;
    if !custom_theme_fits_sync(&text) {
        return Err(ImportError::TooLarge);
    }
    let preferred_id = document.meta.and_then(|meta| meta.id);
    Ok(ParsedImport {
        text,
        preferred_id,
        diagnostics,
    })
}

/// 分配库内 id（`meta.id` 清洗后可用则取之，否则按时间戳生成；与已有主题冲突时加数字后缀，
/// 从不覆盖），把原文原样写入该主题的偏好键；注册由偏好投影完成。返回分配的 id。
pub(crate) fn store_import(
    import: ParsedImport,
    store: &mut SettingsStore,
    cx: &mut Context<SettingsStore>,
) -> Result<String, ImportError> {
    if store.is_read_only() {
        return Err(ImportError::Unavailable);
    }
    let (id, key) = allocate_theme_key(import.preferred_id.as_deref(), |key| {
        store.pref(key).is_some_and(|value| !value.is_null())
    })
    .ok_or(ImportError::Unavailable)?;
    store.set_pref(&key, Value::String(import.text), cx);
    Ok(id)
}

/// 从库中删除：写墓碑（JSON null），经同步链路传到其他设备；注销由偏好投影完成。
/// 调用方负责把引用该 id 的槽位改回内置主题。本机服务未连接时返回 `false`。
pub(crate) fn delete_theme(
    id: &str,
    store: &mut SettingsStore,
    cx: &mut Context<SettingsStore>,
) -> bool {
    let Some(key) = custom_theme_key(id) else {
        return false;
    };
    if store.is_read_only() {
        return false;
    }
    store.set_pref(&key, Value::Null, cx);
    true
}

/// 第一个未被占用的 `(id, 偏好键)`；`taken` 按偏好键判定。
fn allocate_theme_key(
    preferred: Option<&str>,
    taken: impl Fn(&str) -> bool,
) -> Option<(String, String)> {
    let base = preferred.and_then(sanitize_id).unwrap_or_else(timestamp_id);
    (1..=u32::MAX).find_map(|suffix| {
        let id = if suffix == 1 {
            base.clone()
        } else {
            suffixed_id(&base, suffix)
        };
        let key = custom_theme_key(&id)?;
        (!taken(&key)).then_some((id, key))
    })
}

/// `meta.id` → 库 id：小写，`[a-z0-9_]` 之外的字符折叠为单个 `-`，去掉首尾 `-`，
/// 截断到 [`MAX_CUSTOM_THEME_ID_LEN`]；清洗后为空则 `None`。结果总是规范 id
/// （`fluxdown_protocol::is_custom_theme_id`）。
fn sanitize_id(raw: &str) -> Option<String> {
    let mut id = String::with_capacity(raw.len().min(MAX_CUSTOM_THEME_ID_LEN));
    for ch in raw.chars().flat_map(char::to_lowercase) {
        if id.len() >= MAX_CUSTOM_THEME_ID_LEN {
            break;
        }
        if ch.is_ascii_alphanumeric() || ch == '_' {
            id.push(ch);
        } else if !id.is_empty() && !id.ends_with('-') {
            id.push('-');
        }
    }
    let id = id.trim_end_matches('-');
    (!id.is_empty()).then(|| id.to_owned())
}

/// `<base>-<suffix>`：先截断 `base` 给后缀留出空间（去掉截断后尾部的 `-`），
/// 结果仍满足 `sanitize_id(id) == id`。
fn suffixed_id(base: &str, suffix: u32) -> String {
    let suffix = format!("-{suffix}");
    // base 已清洗，全为 ASCII，按字节截断不会切到字符中间。
    let keep = base.len().min(MAX_CUSTOM_THEME_ID_LEN - suffix.len());
    let head = base[..keep].trim_end_matches('-');
    format!("{head}{suffix}")
}

fn timestamp_id() -> String {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_millis());
    format!("theme-{millis}")
}

/// 诊断汇总的展示顺序与文案键。
pub(crate) const DIAGNOSTIC_LABELS: [(DiagnosticKind, &str); 7] = [
    (DiagnosticKind::Migrated, "themeDiagMigrated"),
    (DiagnosticKind::UnknownKey, "themeDiagUnknownKey"),
    (DiagnosticKind::InvalidValue, "themeDiagInvalidValue"),
    (DiagnosticKind::OutOfRange, "themeDiagOutOfRange"),
    (DiagnosticKind::NewerVersion, "themeDiagNewerVersion"),
    (DiagnosticKind::UnknownExtends, "themeDiagUnknownExtends"),
    (DiagnosticKind::RefCycle, "themeDiagRefCycle"),
];

/// 按 [`DIAGNOSTIC_LABELS`] 顺序统计每类诊断的条数（只含非零项）。
pub(crate) fn diagnostic_counts(diagnostics: &[Diagnostic]) -> Vec<(&'static str, usize)> {
    DIAGNOSTIC_LABELS
        .iter()
        .filter_map(|(kind, key)| {
            let count = diagnostics
                .iter()
                .filter(|diagnostic| diagnostic.kind == *kind)
                .count();
            (count > 0).then_some((*key, count))
        })
        .collect()
}

/// 当前明暗模式正在生效的主题文件（导出用）。
///
/// 自定义主题原样返回（未知键保留）；内置主题（含自定义主题缺失时的回退）把用户强调色
/// 写入两个模式层，使导出文件在任何客户端里与当前所见一致。
pub(crate) fn export_document(cx: &App) -> ThemeDocument {
    let state = active_theme(cx);
    let mode = state.mode();
    let appearance = state.appearance();
    if let ThemeSelection::Custom(id) = appearance.theme(mode)
        && let Some(document) = custom_theme(id, cx)
    {
        return (*document).clone();
    }
    builtin_with_accent(appearance.builtin_theme(mode), appearance.accent())
}

pub(crate) fn builtin_with_accent(id: BuiltinThemeId, accent: Hsla) -> ThemeDocument {
    let mut document = ThemeDocument::builtin(id);
    let options = ResolveOptions {
        accent: Some(accent),
        ensure_primary_contrast: false,
    };
    for mode in [ThemeMode::Dark, ThemeMode::Light] {
        let (values, _) = resolve_with(&document, mode, &options);
        for path in ACCENT_TOKEN_PATHS {
            if let Some(TokenValue::Color(color)) = values.get(path) {
                let hex = color_hex(*color);
                document.set_token(TokenLayer::for_mode(mode), path, hex.into());
            }
        }
    }
    document
}

/// 导出文件的建议文件名：`meta.name`（缺省 `meta.id`）清洗为小写 `a-z0-9-`。
pub(crate) fn export_file_name(document: &ThemeDocument) -> String {
    let source = document
        .meta
        .as_ref()
        .and_then(|meta| meta.name.as_deref().or(meta.id.as_deref()))
        .unwrap_or_default();
    let mut stem = String::with_capacity(source.len());
    for ch in source.chars().flat_map(char::to_lowercase) {
        if ch.is_ascii_alphanumeric() {
            stem.push(ch);
        } else if !stem.is_empty() && !stem.ends_with('-') {
            stem.push('-');
        }
    }
    let stem = stem.trim_end_matches('-');
    if stem.is_empty() {
        "fluxdown-theme.json".to_owned()
    } else {
        format!("{stem}.json")
    }
}

#[cfg(test)]
mod tests {
    use fluxdown_protocol::{MAX_SYNC_VALUE_BYTES, is_custom_theme_id};
    use fluxdown_ui_theme::{ExportMode, argb_color, resolve};

    use super::*;

    fn document(json: &str) -> Result<ThemeDocument, ThemeParseError> {
        ThemeDocument::parse(json).map(|(doc, _)| doc)
    }

    fn prefs(entries: &[(&str, Value)]) -> BTreeMap<String, Value> {
        entries
            .iter()
            .map(|(id, value)| {
                let key = custom_theme_key(id).unwrap_or_else(|| panic!("canonical id {id}"));
                (key, value.clone())
            })
            .collect()
    }

    fn registered(changes: &LibraryChanges) -> Vec<&str> {
        changes
            .register
            .iter()
            .map(|theme| theme.id.as_ref())
            .collect()
    }

    #[test]
    fn slot_availability_follows_mode_layers_and_extends() -> Result<(), Box<dyn std::error::Error>>
    {
        let dark_layer = document(r##"{"dark":{"colors":{"primary":"#ff0000"}}}"##)?;
        assert!(theme_available_in(&dark_layer, ThemeMode::Dark));
        assert!(!theme_available_in(&dark_layer, ThemeMode::Light));

        let nord = document(r#"{"extends":"builtin:nord"}"#)?;
        assert!(theme_available_in(&nord, ThemeMode::Dark));
        assert!(!theme_available_in(&nord, ThemeMode::Light));

        // 暗色预设 + 亮色层：亮色层让它也能进亮色槽位。
        let nord_with_light =
            document(r##"{"extends":"builtin:nord","light":{"colors":{"primary":"#ff0000"}}}"##)?;
        assert!(theme_available_in(&nord_with_light, ThemeMode::Dark));
        assert!(theme_available_in(&nord_with_light, ThemeMode::Light));

        // 只有共享 tokens、无模式倾向：两个槽位都可用。
        let shared = document(r#"{"tokens":{"radius":{"md":8}}}"#)?;
        assert!(theme_available_in(&shared, ThemeMode::Dark));
        assert!(theme_available_in(&shared, ThemeMode::Light));
        Ok(())
    }

    /// 投影只解析变化的原文；删除、墓碑与非主题键都不留下注册。
    #[test]
    fn reconcile_registers_changes_and_drops_removed_or_tombstoned_themes() {
        let ocean = Value::String(r#"{"meta":{"name":"Ocean"}}"#.to_owned());
        let nord = Value::String(r#"{"extends":"builtin:nord"}"#.to_owned());
        let mut entries = BTreeMap::new();

        let mut values = prefs(&[("ocean", ocean.clone()), ("nord-square", nord.clone())]);
        values.insert("appearance.theme_mode".to_owned(), Value::from("dark"));
        let changes = reconcile(&mut entries, &values);
        assert_eq!(registered(&changes), ["nord-square", "ocean"]);
        assert!(changes.unregister.is_empty() && changes.failures.is_empty());
        assert_eq!(changes.register[1].name.as_ref(), "Ocean");

        // 原文不变：不重新解析、不重复注册。
        let changes = reconcile(&mut entries, &values);
        assert!(changes.register.is_empty() && changes.unregister.is_empty());

        // 云端改了 ocean、删了 nord-square（本机写墓碑时偏好视图里是 null）。
        let ocean_v2 = Value::String(r#"{"meta":{"name":"Ocean 2"}}"#.to_owned());
        let changes = reconcile(
            &mut entries,
            &prefs(&[("ocean", ocean_v2), ("nord-square", Value::Null)]),
        );
        assert_eq!(registered(&changes), ["ocean"]);
        assert_eq!(changes.register[0].name.as_ref(), "Ocean 2");
        assert_eq!(changes.unregister, ["nord-square"]);
        assert_eq!(entries.keys().collect::<Vec<_>>(), ["ocean"]);
    }

    #[test]
    fn unparseable_synced_theme_is_reported_once_and_not_registered() {
        let mut entries = BTreeMap::new();
        let broken = prefs(&[("ocean", Value::String("[1]".to_owned()))]);
        let changes = reconcile(&mut entries, &broken);
        assert!(changes.register.is_empty());
        assert_eq!(changes.unregister, ["ocean"]);
        assert_eq!(changes.failures.len(), 1);
        assert!(entries["ocean"].theme.is_none());
        let again = reconcile(&mut entries, &broken);
        assert!(again.failures.is_empty(), "same text is not re-parsed");
    }

    #[test]
    fn flutter_theme_parses_into_its_appearance_slot() -> Result<(), Box<dyn std::error::Error>> {
        let flutter =
            r#"{"name":"Ocean","appearance":"light","colors":{"accent":{"color":"FF0EA5E9"}}}"#;
        let parsed = parse_import(flutter.to_owned()).map_err(|error| format!("{error:?}"))?;
        assert_eq!(
            diagnostic_counts(&parsed.diagnostics),
            vec![("themeDiagMigrated", 1)]
        );
        // 原文原样入库（不是转换后的文件），投影后按原文解析出亮色主题。
        assert_eq!(parsed.text, flutter);
        let mut entries = BTreeMap::new();
        let changes = reconcile(&mut entries, &prefs(&[("ocean", Value::from(parsed.text))]));
        let theme = &changes.register[0];
        assert!(theme.available_in(ThemeMode::Light));
        assert!(!theme.available_in(ThemeMode::Dark));
        assert_eq!(theme.name.as_ref(), "Ocean");
        Ok(())
    }

    #[test]
    fn stored_text_round_trips_unknown_keys() -> Result<(), Box<dyn std::error::Error>> {
        let text = r##"{
          "format": "fluxdown.gpui-theme",
          "schemaVersion": 2,
          "meta": { "id": "Ocean", "name": "Ocean", "x-origin": "gallery" },
          "x-top": { "keep": true },
          "dark": { "colors": { "primary": "#0ea5e9", "x-glow": "#ffffff" } }
        }"##;
        let parsed = parse_import(text.to_owned()).map_err(|error| format!("{error:?}"))?;
        assert_eq!(parsed.preferred_id.as_deref(), Some("Ocean"));
        let kinds = diagnostic_counts(&parsed.diagnostics)
            .into_iter()
            .map(|(key, _)| key)
            .collect::<Vec<_>>();
        assert_eq!(kinds, ["themeDiagUnknownKey"]);
        let (document, _) = ThemeDocument::parse(&parsed.text)?;
        let exported: Value = serde_json::from_str(&document.to_json_pretty(ExportMode::Diff))?;
        assert_eq!(exported["x-top"]["keep"], true);
        assert_eq!(exported["meta"]["x-origin"], "gallery");
        assert_eq!(exported["dark"]["colors"]["x-glow"], "#ffffff");
        Ok(())
    }

    #[test]
    fn unusable_imports_are_rejected_before_reaching_preferences() {
        let reason = |text: &str| {
            parse_import(text.to_owned())
                .err()
                .map(|error| error.i18n_key())
        };
        assert_eq!(reason("[1, 2]"), Some("themeImportNotObject"));
        assert_eq!(reason("{"), Some("themeImportInvalidJson"));
        let huge = format!(r#"{{"x-pad":"{}"}}"#, "x".repeat(MAX_SYNC_VALUE_BYTES));
        assert_eq!(reason(&huge), Some("themeImportTooLarge"));
    }

    #[test]
    fn allocated_ids_suffix_conflicts_and_stay_canonical() {
        let taken = |ids: &[&str]| {
            let keys = ids
                .iter()
                .filter_map(|id| custom_theme_key(id))
                .collect::<Vec<_>>();
            move |key: &str| keys.iter().any(|taken| taken == key)
        };
        let allocate =
            |preferred, ids: &[&str]| allocate_theme_key(preferred, taken(ids)).map(|(id, _)| id);
        assert_eq!(allocate(Some("Ocean"), &[]).as_deref(), Some("ocean"));
        assert_eq!(
            allocate(Some("Ocean"), &["ocean"]).as_deref(),
            Some("ocean-2")
        );
        assert_eq!(
            allocate(Some("ocean"), &["ocean", "ocean-2"]).as_deref(),
            Some("ocean-3")
        );
        // 第 62 位是 `-`：给 `-2` 截断到 62 字符后必须去掉尾部 `-`，避免 `--2`。
        let preferred = format!("{}-bb", "a".repeat(61));
        assert_eq!(preferred.len(), MAX_CUSTOM_THEME_ID_LEN);
        let suffixed = allocate(Some(&preferred), &[&preferred]).unwrap_or_default();
        assert_eq!(suffixed, format!("{}-2", "a".repeat(61)));
        for preferred in [None, Some("  "), Some("../../")] {
            let id = allocate(preferred, &[]).unwrap_or_default();
            assert!(id.starts_with("theme-"), "{preferred:?} → {id}");
        }
        for raw in [
            "../../etc/passwd",
            "Nord  Square!",
            "Ünïcode Theme",
            &"x".repeat(100),
        ] {
            let id = sanitize_id(raw).unwrap_or_default();
            assert!(is_custom_theme_id(&id), "{raw} → {id}");
        }
        assert_eq!(sanitize_id("Nord  Square!").as_deref(), Some("nord-square"));
    }

    #[test]
    fn builtin_export_carries_accent_in_both_modes() -> Result<(), Box<dyn std::error::Error>> {
        let rose = argb_color(0xFFF4_3F5E);
        let document = builtin_with_accent(BuiltinThemeId::Nord, rose);
        let reparsed = ThemeDocument::parse(&document.to_json_pretty(ExportMode::Diff))?.0;
        for mode in [ThemeMode::Dark, ThemeMode::Light] {
            let (values, _) = resolve(&reparsed, mode);
            let Some(TokenValue::Color(primary)) = values.get("colors.primary") else {
                panic!("colors.primary missing in {mode:?}");
            };
            assert_eq!(color_hex(*primary), "#f43f5eff", "{mode:?}");
        }
        // Nord 预设固定的 primaryForeground 不随强调色改变。
        let (plain, _) = resolve(
            &ThemeDocument::builtin(BuiltinThemeId::Nord),
            ThemeMode::Dark,
        );
        let (values, _) = resolve(&reparsed, ThemeMode::Dark);
        assert_eq!(
            values.get("colors.primaryForeground"),
            plain.get("colors.primaryForeground")
        );
        Ok(())
    }

    #[test]
    fn builtin_export_pins_accent_even_when_equal_to_base() -> Result<(), Box<dyn std::error::Error>>
    {
        // 强调色等于基底默认值时 diff 导出曾把强调色路径全部精简掉，导入方的强调色随即覆盖。
        let blue = argb_color(0xFF3B_82F6);
        let rose = argb_color(0xFFF4_3F5E);
        let document = builtin_with_accent(BuiltinThemeId::DefaultDark, blue);
        let reparsed = ThemeDocument::parse(&document.to_json_pretty(ExportMode::Diff))?.0;
        let importer = ResolveOptions {
            accent: Some(rose),
            ensure_primary_contrast: false,
        };
        for mode in [ThemeMode::Dark, ThemeMode::Light] {
            let (exported, _) = resolve(&document, mode);
            let (imported, _) = resolve_with(&reparsed, mode, &importer);
            for path in ACCENT_TOKEN_PATHS {
                assert_eq!(imported.get(path), exported.get(path), "{mode:?} {path}");
            }
        }
        Ok(())
    }

    #[test]
    fn export_file_name_is_sanitized() -> Result<(), Box<dyn std::error::Error>> {
        let mut document = ThemeDocument::builtin(BuiltinThemeId::MidnightBlue);
        assert_eq!(export_file_name(&document), "midnight-blue.json");
        document.meta = None;
        assert_eq!(export_file_name(&document), "fluxdown-theme.json");
        Ok(())
    }
}
