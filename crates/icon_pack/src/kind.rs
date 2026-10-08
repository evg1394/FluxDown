//! 文件大类：任务表的类别文案、分组与图标包 `kinds` 映射共用。
//!
//! 扩展名 → 大类的唯一事实源是 `assets/icon-packs/kinds.json`（Web 经同一文件导入）。

use std::{borrow::Cow, collections::HashMap, sync::LazyLock};

use serde_json::Value;

const KINDS_JSON: &str = include_str!("../../../assets/icon-packs/kinds.json");

/// 文件大类。wire 名（[`FileKind::wire_name`]）是图标包 `kinds` 的键。
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum FileKind {
    Application,
    DiskImage,
    Mobile,
    Video,
    Audio,
    Document,
    Image,
    Archive,
    Other,
}

impl FileKind {
    pub const ALL: [Self; 9] = [
        Self::Application,
        Self::DiskImage,
        Self::Mobile,
        Self::Video,
        Self::Audio,
        Self::Document,
        Self::Image,
        Self::Archive,
        Self::Other,
    ];

    #[must_use]
    pub fn wire_name(self) -> &'static str {
        match self {
            Self::Application => "application",
            Self::DiskImage => "diskImage",
            Self::Mobile => "mobile",
            Self::Video => "video",
            Self::Audio => "audio",
            Self::Document => "document",
            Self::Image => "image",
            Self::Archive => "archive",
            Self::Other => "other",
        }
    }

    #[must_use]
    pub fn from_wire(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|kind| kind.wire_name() == name)
    }

    /// 文件名 → 大类：取最后一个 `.` 之后的扩展名（大小写不敏感）；无扩展名、扩展名为空或
    /// 含 `/` 时为 [`FileKind::Other`]。
    #[must_use]
    pub fn of_name(name: &str) -> Self {
        let Some((_, extension)) = name.rsplit_once('.') else {
            return Self::Other;
        };
        if extension.is_empty() || extension.contains('/') {
            return Self::Other;
        }
        let extension = if extension.bytes().any(|byte| byte.is_ascii_uppercase()) {
            Cow::Owned(extension.to_ascii_lowercase())
        } else {
            Cow::Borrowed(extension)
        };
        BY_EXTENSION
            .get(extension.as_ref())
            .copied()
            .unwrap_or(Self::Other)
    }
}

/// 内嵌表由 `kind_table_parses` 测试守护；即便损坏也只会让全部文件归入「其他」，不会崩溃。
static BY_EXTENSION: LazyLock<HashMap<String, FileKind>> =
    LazyLock::new(|| parse_kind_table(KINDS_JSON).unwrap_or_default());

fn parse_kind_table(text: &str) -> Result<HashMap<String, FileKind>, String> {
    let value: Value = serde_json::from_str(text).map_err(|error| error.to_string())?;
    let object = value.as_object().ok_or("kinds.json must be an object")?;
    let mut table = HashMap::new();
    for (name, extensions) in object {
        let kind = FileKind::from_wire(name).ok_or_else(|| format!("unknown kind {name}"))?;
        let extensions = extensions
            .as_array()
            .ok_or_else(|| format!("{name} must be an array"))?;
        for extension in extensions {
            let extension = extension
                .as_str()
                .ok_or_else(|| format!("{name} entries must be strings"))?;
            if table.insert(extension.to_owned(), kind).is_some() {
                return Err(format!("duplicate extension {extension}"));
            }
        }
    }
    Ok(table)
}

#[cfg(test)]
mod tests {
    use super::{FileKind, KINDS_JSON, parse_kind_table};

    #[test]
    fn kind_table_parses() {
        let table = parse_kind_table(KINDS_JSON).expect("embedded kinds.json");
        assert!(table.values().all(|kind| *kind != FileKind::Other));
    }

    #[test]
    fn classifies_by_last_extension_case_insensitively() {
        assert_eq!(FileKind::of_name("Movie.MKV"), FileKind::Video);
        assert_eq!(FileKind::of_name("backup.tar.gz"), FileKind::Archive);
        assert_eq!(FileKind::of_name("setup.AppImage"), FileKind::Application);
        assert_eq!(FileKind::of_name("README"), FileKind::Other);
        assert_eq!(FileKind::of_name("trailing."), FileKind::Other);
        assert_eq!(FileKind::of_name("dir.d/file"), FileKind::Other);
    }
}
