//! 旧版主题库迁移。导入主题曾以 `<数据根>/themes/<id>.json` 只存在本机；现以 agent 偏好
//! `appearance.custom_themes.<id>` 为唯一事实源并随配置同步（见 `fluxdown_ui_settings::sync_theme_library`）。
//!
//! 首个 agent 快照后执行一次：偏好里没有的旧主题写入偏好，agent 确认后删除文件；偏好已有同 id
//! 且原文相同的文件直接删除。原文冲突或无法作为同步值的文件保留并记录，绝不丢用户数据。

use std::{
    collections::BTreeMap,
    io,
    path::{Path, PathBuf},
    sync::Arc,
};

use fluxdown_protocol::{
    CUSTOM_THEMES_KEY, RpcErrorData, custom_theme_id, custom_theme_key, method, validate_value,
};
use gpui::App;
use serde_json::Value;

use crate::agent_client::AgentClient;
use crate::app::Desktop;

const EXTENSION: &str = "json";

#[derive(Debug, thiserror::Error)]
enum MigrationError {
    #[error("legacy theme library I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("agent rejected the migrated themes ({:?})", .0.code)]
    Agent(RpcErrorData),
}

/// 一个可迁移的旧主题文件。
struct LegacyTheme {
    key: String,
    path: PathBuf,
    text: String,
}

/// 首个快照后调用：以快照里的主题偏好为准，在后台迁移 `themes_dir` 下的旧主题文件。
pub(crate) fn migrate(cx: &mut App) {
    let desktop = Desktop::global(cx);
    let Some(existing) = desktop
        .session
        .read(cx)
        .latest()
        .and_then(|snapshot| crate::session::agent_body(snapshot))
        .map(|body| {
            body.preferences
                .values
                .iter()
                .filter(|(key, _)| custom_theme_id(key).is_some())
                .map(|(key, value)| (key.clone(), value.clone()))
                .collect::<BTreeMap<_, _>>()
        })
    else {
        return;
    };
    let dir = crate::app::app_data_dir().join("themes");
    let client = Arc::clone(&desktop.client);
    let task_client = Arc::clone(&client);
    client.spawn_background(async move {
        if let Err(error) = run(&task_client, &dir, &existing).await {
            log::warn!("legacy theme migration did not finish: {error}");
        }
    });
}

async fn run(
    client: &AgentClient,
    dir: &Path,
    existing: &BTreeMap<String, Value>,
) -> Result<(), MigrationError> {
    let themes = match legacy_themes(dir).await {
        Ok(themes) => themes,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    let mut values = serde_json::Map::new();
    let mut uploaded = Vec::new();
    let mut removable = Vec::new();
    for LegacyTheme { key, path, text } in themes {
        match existing.get(&key) {
            Some(Value::String(current)) if *current == text => removable.push(path),
            Some(_) => log::warn!(
                "legacy theme {} differs from the synced theme with the same id; kept",
                path.display()
            ),
            None => {
                let value = Value::String(text);
                match validate_value(CUSTOM_THEMES_KEY, &value) {
                    Ok(()) => {
                        values.insert(key, value);
                        uploaded.push(path);
                    }
                    Err(reason) => log::warn!(
                        "legacy theme {} cannot be synced ({reason}); kept",
                        path.display()
                    ),
                }
            }
        }
    }
    if !values.is_empty() {
        client
            .call::<Value, Value>(
                method::AGENT_PREFERENCES_PATCH,
                Some(serde_json::json!({ "values": values })),
            )
            .await
            .map_err(MigrationError::Agent)?;
        removable.extend(uploaded);
    }
    for path in removable {
        match tokio::fs::remove_file(&path).await {
            Err(error) if error.kind() != io::ErrorKind::NotFound => return Err(error.into()),
            _ => {}
        }
    }
    match tokio::fs::remove_dir(dir).await {
        Err(error)
            if !matches!(
                error.kind(),
                io::ErrorKind::NotFound | io::ErrorKind::DirectoryNotEmpty
            ) =>
        {
            Err(error.into())
        }
        _ => Ok(()),
    }
}

/// 目录下文件名为规范 id 的 `<id>.json`；手工放入的非规范文件名从未被引用过，忽略。
/// 单个文件读取失败只记录，不阻断其余文件。
async fn legacy_themes(dir: &Path) -> io::Result<Vec<LegacyTheme>> {
    let mut entries = tokio::fs::read_dir(dir).await?;
    let mut themes = Vec::new();
    while let Some(entry) = entries.next_entry().await? {
        let path = entry.path();
        if path.extension().and_then(|ext| ext.to_str()) != Some(EXTENSION) {
            continue;
        }
        let Some(key) = path
            .file_stem()
            .and_then(|stem| stem.to_str())
            .and_then(custom_theme_key)
        else {
            continue;
        };
        if !entry.file_type().await?.is_file() {
            continue;
        }
        match tokio::fs::read_to_string(&path).await {
            Ok(text) => themes.push(LegacyTheme { key, path, text }),
            Err(error) => log::warn!("could not read legacy theme {}: {error}", path.display()),
        }
    }
    Ok(themes)
}
