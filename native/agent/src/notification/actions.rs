use std::path::{Path, PathBuf};

#[derive(Clone)]
#[cfg_attr(
    not(any(windows, all(target_os = "macos", feature = "desktop"))),
    allow(dead_code)
)]
pub(super) struct CompletionActions {
    pub(super) path: PathBuf,
    pub(super) open_file_label: String,
    pub(super) open_folder_label: String,
}

impl CompletionActions {
    #[cfg(all(target_os = "macos", feature = "desktop"))]
    pub(super) fn activate(&self, action: &str) {
        if let Err(error) = self.try_activate(action) {
            tracing::warn!(%error, action, "notification file action failed");
        }
    }

    #[cfg(any(windows, all(target_os = "macos", feature = "desktop")))]
    pub(super) fn try_activate(&self, action: &str) -> Result<(), String> {
        let (path, reveal) = action_target(&self.path, action)?;
        crate::platform::open_path(&path, reveal).map_err(|error| error.to_string())
    }
}

// Do not execute a URL or fall back to an unrelated working directory. A removed file
// can still reveal its existing parent, but the explicit open-file action must fail.
#[cfg(any(windows, all(target_os = "macos", feature = "desktop"), test))]
fn action_target(path: &Path, action: &str) -> Result<(PathBuf, bool), String> {
    if !path.is_absolute() {
        return Err("notification file path must be absolute".to_owned());
    }
    match action {
        "open-file" if path.exists() => Ok((path.to_path_buf(), false)),
        "default" | "open-folder" if path.exists() => Ok((path.to_path_buf(), true)),
        "default" | "open-folder" => path
            .parent()
            .filter(|parent| parent.is_dir())
            .map(|parent| (parent.to_path_buf(), false))
            .ok_or_else(|| "notification file and containing folder no longer exist".to_owned()),
        "open-file" => Err("notification file no longer exists".to_owned()),
        _ => Err("unknown notification action".to_owned()),
    }
}

pub(crate) fn completion_path(save_dir: &str, file_name: &str) -> Option<PathBuf> {
    // File names originate from the engine, but malformed/legacy tasks should still
    // produce an informational notification rather than an unsafe action target.
    let directory = Path::new(save_dir);
    let mut name = Path::new(file_name).components();
    if !directory.is_absolute()
        || !matches!(name.next(), Some(std::path::Component::Normal(_)))
        || name.next().is_some()
    {
        return None;
    }
    Some(directory.join(file_name))
}

#[cfg(test)]
mod tests {
    use super::{action_target, completion_path};

    #[test]
    fn completion_paths_require_an_absolute_directory_and_one_filename() {
        let dir = std::env::temp_dir();
        let save_dir = dir.to_str().unwrap();
        assert_eq!(
            completion_path(save_dir, "报告 'v2'.zip"),
            Some(dir.join("报告 'v2'.zip"))
        );
        for name in ["", ".", "..", "../file", "nested/file", "/file"] {
            assert!(completion_path(save_dir, name).is_none(), "{name}");
        }
        assert!(completion_path("", "file.bin").is_none());
        assert!(completion_path("relative", "file.bin").is_none());
    }

    #[test]
    fn body_reveals_file_and_missing_file_falls_back_only_to_its_parent()
    -> Result<(), Box<dyn std::error::Error>> {
        let root =
            std::env::temp_dir().join(format!("fluxdown-notice-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root)?;
        let file = root.join("file & '中文'.bin");
        std::fs::write(&file, b"test")?;
        assert_eq!(action_target(&file, "default")?, (file.clone(), true));
        assert_eq!(action_target(&file, "open-folder")?, (file.clone(), true));
        assert_eq!(action_target(&file, "open-file")?, (file.clone(), false));
        assert!(action_target(&file, "unexpected").is_err());
        std::fs::remove_file(&file)?;
        assert!(action_target(&file, "open-file").is_err());
        assert_eq!(action_target(&file, "default")?, (root.clone(), false));
        std::fs::remove_dir(&root)?;
        assert!(action_target(&file, "open-folder").is_err());
        Ok(())
    }
}
