use gpui::App;

/// 设备本地偏好，不进入主题文档或云同步目录。
pub const FONT_FAMILY_KEY: &str = "desktop.font_family";

/// GPUI 当前平台字体后端可用的字体族；不推测或硬编码系统字体。
#[must_use]
pub fn available_font_families(cx: &App) -> Vec<String> {
    normalize_names(cx.text_system().all_font_names())
}

fn normalize_names(mut names: Vec<String>) -> Vec<String> {
    names.retain(|name| !name.trim().is_empty());
    names.sort_unstable();
    names.dedup();
    names
}

pub(crate) fn apply_font_family(
    theme: &mut crate::ResolvedTheme,
    selected: Option<&str>,
    available: &[String],
) {
    if let Some(name) = selected.filter(|name| available.iter().any(|item| item == name)) {
        theme.base.typography.sans = name.to_owned().into();
    }
}

#[cfg(test)]
mod tests {
    use super::{FONT_FAMILY_KEY, apply_font_family, normalize_names};
    use crate::{AppearancePreferences, BuiltinThemeId, ThemeDocument, ThemeMode, resolve};
    use serde_json::json;

    #[test]
    fn font_names_are_real_sorted_unique_and_nonempty() {
        assert_eq!(
            normalize_names(vec![
                "中文字体".into(),
                "".into(),
                "  ".into(),
                "A".into(),
                "A".into()
            ]),
            vec!["A", "中文字体"]
        );
    }

    #[test]
    fn font_preference_defaults_and_invalid_values_follow_theme() {
        for value in [json!(null), json!(42), json!(""), json!("  ")] {
            let prefs =
                AppearancePreferences::from_values(&[(FONT_FAMILY_KEY.into(), value)].into());
            assert_eq!(prefs.font_family, None);
        }
        let prefs = AppearancePreferences::from_values(
            &[(FONT_FAMILY_KEY.into(), json!("中文字体"))].into(),
        );
        assert_eq!(prefs.font_family.as_deref(), Some("中文字体"));
        assert!(prefs.same_palette(&AppearancePreferences::default()));
        assert_ne!(prefs, AppearancePreferences::default());
    }

    #[test]
    fn font_override_survives_theme_modes_without_changing_mono_or_documents() {
        let document = ThemeDocument::builtin(BuiltinThemeId::DefaultDark);
        for mode in [ThemeMode::Dark, ThemeMode::Light] {
            let (values, _) = resolve(&document, mode);
            let original = values.to_theme(1.);
            for selected in [None, Some("missing")] {
                let mut theme = original.clone();
                apply_font_family(&mut theme, selected, &["Installed".into()]);
                assert_eq!(theme, original);
            }
            let mut theme = original.clone();
            apply_font_family(&mut theme, Some("Installed"), &["Installed".into()]);
            assert_eq!(theme.base.typography.sans.as_ref(), "Installed");
            assert_eq!(theme.base.typography.mono, original.base.typography.mono);
            assert_eq!(values.to_theme(1.), original);
        }
    }
}
