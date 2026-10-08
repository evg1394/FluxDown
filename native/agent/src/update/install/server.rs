//! headless 服务器二进制（`fluxdown-agent --server`）：自更新只替换 `fluxdown-agent` 与
//! `fluxdownd` 两个程序文件，SPA 已内嵌在新二进制里；重启沿用原路径与原 argv。

use std::path::Path;

use super::replace::{self, Selection};
use super::{AGENT_BIN, DAEMON_BIN, InstallError, current_exe_and_dir};
use crate::update::restart::RestartPlan;

pub(super) fn apply(package: &Path, work_dir: &Path) -> Result<RestartPlan, InstallError> {
    // 必须在替换前取路径与参数：此后 Linux 的 `/proc/self/exe` 会指向让位的旧文件。
    let (exe, dir) = current_exe_and_dir()?;
    let args = std::env::args_os().skip(1).collect();
    replace::apply_archive(
        package,
        work_dir,
        &dir,
        Selection::Only(&[AGENT_BIN, DAEMON_BIN]),
    )?;
    Ok(RestartPlan::server(exe, args))
}
