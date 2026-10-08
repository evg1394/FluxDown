//! Windows 安装版（Inno Setup，每用户）：不在运行期替换文件，而是校验安装器，
//! 由重启计划在 agent 完全退出后静默运行它（`CloseApplications=force` 关残余进程，
//! `[Run]` 在静默安装结束时带 `--after-update` 重新拉起桌面）。
//!
//! 便携版走 [`super::apply_portable`]。

use std::fs::File;
use std::io::Read;
use std::path::Path;

use super::InstallError;
use crate::update::restart::RestartPlan;

/// 安装器日志文件名（位于该版本的暂存目录）。
const SETUP_LOG: &str = "setup.log";

pub(super) fn apply_setup(package: &Path, work_dir: &Path) -> Result<RestartPlan, InstallError> {
    let is_exe = package
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("exe"));
    if !is_exe {
        return Err(InstallError::InvalidPackage(format!(
            "installer must be an .exe: {}",
            package.display()
        )));
    }
    let mut magic = [0_u8; 2];
    File::open(package)
        .and_then(|mut file| file.read_exact(&mut magic))
        .map_err(InstallError::storage(format!(
            "read installer {}",
            package.display()
        )))?;
    if &magic != b"MZ" {
        return Err(InstallError::InvalidPackage(format!(
            "{} is not a Windows executable",
            package.display()
        )));
    }
    std::fs::create_dir_all(work_dir).map_err(InstallError::storage(format!(
        "create {}",
        work_dir.display()
    )))?;
    Ok(RestartPlan::installer(
        package.to_path_buf(),
        work_dir.join(SETUP_LOG),
    ))
}
