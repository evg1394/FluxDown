//! 测试用临时目录：Drop 时递归删除。

use std::io;
use std::path::{Path, PathBuf};

pub(super) struct TempDir(PathBuf);

impl TempDir {
    pub(super) fn new(label: &str) -> io::Result<Self> {
        let path = std::env::temp_dir().join(format!(
            "fluxdown_update_{label}_{}",
            uuid::Uuid::new_v4().simple()
        ));
        std::fs::create_dir_all(&path)?;
        Ok(Self(path))
    }

    pub(super) fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        if let Err(error) = std::fs::remove_dir_all(&self.0) {
            eprintln!(
                "could not remove test directory {}: {error}",
                self.0.display()
            );
        }
    }
}
