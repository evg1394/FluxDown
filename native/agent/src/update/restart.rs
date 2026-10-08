//! 更新后的重启计划：文件替换完成后登记，agent 运行期**完全结束**（daemon 已退出、
//! `agent.lock` 已释放）再由 `main` 执行，然后进程退出。
//!
//! 计划只描述「怎么把程序重新拉起来」，不做任何文件替换（替换在 [`super::install`] 里完成）：
//! - 桌面各形态：分离启动桌面程序并带 `--after-update`（它等旧实例退出后正常启动）；
//! - Windows 安装版：静默运行安装器，安装器结束时自行带 `--after-update` 拉起桌面；
//! - headless 服务器：Unix `exec` 同一路径与原 argv（PID 不变，systemd / tini / 终端照常工作），
//!   Windows 分离启动同一路径。

use std::ffi::OsString;
use std::path::PathBuf;
use std::sync::{Mutex, PoisonError};

/// 桌面程序在更新后重启时带的参数（只有桌面平台有桌面程序）。
#[cfg(any(windows, target_os = "linux", target_os = "macos"))]
pub(crate) const AFTER_UPDATE_ARG: &str = "--after-update";

static PENDING: Mutex<Option<RestartPlan>> = Mutex::new(None);

/// 一次待执行的重启。
#[derive(Debug)]
pub struct RestartPlan {
    action: Action,
    /// 是否由 headless 服务器自身重启（决定 UI 连接以 `service-restart` 关闭）。
    server: bool,
}

#[derive(Debug)]
enum Action {
    /// 分离启动一个进程（不等待）：桌面平台的桌面重启，及非 Unix 的服务器重启。
    #[cfg(any(not(unix), target_os = "linux", target_os = "macos"))]
    Spawn {
        program: PathBuf,
        args: Vec<OsString>,
    },
    /// 以同一进程原地替换为新程序（Unix 服务器）。
    #[cfg(unix)]
    Exec {
        program: PathBuf,
        args: Vec<OsString>,
    },
    /// 静默运行 Inno Setup 安装器（Windows 安装版）。
    #[cfg(windows)]
    Installer { installer: PathBuf, log: PathBuf },
}

impl RestartPlan {
    /// 桌面形态：分离启动 `program`。
    #[cfg(any(windows, target_os = "linux", target_os = "macos"))]
    pub(crate) fn desktop(program: PathBuf, args: Vec<OsString>) -> Self {
        Self {
            action: Action::Spawn { program, args },
            server: false,
        }
    }

    /// headless 服务器：Unix `exec` 原路径与原参数，Windows 分离启动。
    pub(crate) fn server(program: PathBuf, args: Vec<OsString>) -> Self {
        #[cfg(unix)]
        let action = Action::Exec { program, args };
        #[cfg(not(unix))]
        let action = Action::Spawn { program, args };
        Self {
            action,
            server: true,
        }
    }

    /// Windows 安装版：退出后静默运行 `installer`，日志写到 `log`。
    #[cfg(windows)]
    pub(crate) fn installer(installer: PathBuf, log: PathBuf) -> Self {
        Self {
            action: Action::Installer { installer, log },
            server: false,
        }
    }

    fn run(self) -> std::io::Result<()> {
        match self.action {
            #[cfg(any(not(unix), target_os = "linux", target_os = "macos"))]
            Action::Spawn { program, args } => {
                tracing::info!(program = %program.display(), "starting program after update");
                spawn_detached(&program, &args)
            }
            #[cfg(unix)]
            Action::Exec { program, args } => {
                use std::os::unix::process::CommandExt;

                tracing::info!(program = %program.display(), "re-executing agent after update");
                // `exec` 成功时不返回；只会带着失败原因回来。
                Err(std::process::Command::new(&program).args(&args).exec())
            }
            #[cfg(windows)]
            Action::Installer { installer, log } => run_installer(&installer, &log),
        }
    }
}

/// 登记重启计划；agent 运行期完全结束后由 [`run_pending`] 执行。后登记的覆盖先登记的。
pub(crate) fn schedule(plan: RestartPlan) {
    *PENDING.lock().unwrap_or_else(PoisonError::into_inner) = Some(plan);
}

/// 已登记的计划是否是 headless 服务器自身重启。
pub(crate) fn scheduled_server_restart() -> bool {
    PENDING
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .as_ref()
        .is_some_and(|plan| plan.server)
}

/// `main` / 宿主在 agent 运行期结束、进程退出前调用；没有计划时 `Ok(())`。
///
/// Unix 服务器计划成功时不会返回（进程已被新程序替换）。
///
/// # Errors
/// 启动 / `exec` 失败的 I/O 错误；调用方记录日志即可，进程随后照常退出。
pub fn run_pending() -> std::io::Result<()> {
    let plan = PENDING
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .take();
    match plan {
        Some(plan) => plan.run(),
        None => Ok(()),
    }
}

/// 分离启动：标准流置空，Unix 进入独立进程组，Windows 不带控制台窗口、独立进程组。
#[cfg(any(not(unix), target_os = "linux", target_os = "macos"))]
fn spawn_detached(program: &std::path::Path, args: &[OsString]) -> std::io::Result<()> {
    let mut command = std::process::Command::new(program);
    command
        .args(args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null());
    detach(&mut command);
    // 子进程由系统接管：本进程随后退出，不等待。
    command.spawn().map(drop)
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn detach(command: &mut std::process::Command) {
    use std::os::unix::process::CommandExt;

    command.process_group(0);
}

#[cfg(windows)]
fn detach(command: &mut std::process::Command) {
    use std::os::windows::process::CommandExt;

    command.creation_flags(CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP);
}

#[cfg(not(any(unix, windows)))]
fn detach(_command: &mut std::process::Command) {}

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
#[cfg(windows)]
const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
/// `ERROR_ELEVATION_REQUIRED`：安装器清单要求提权，而当前进程未提权。
#[cfg(windows)]
const ERROR_ELEVATION_REQUIRED: i32 = 740;

/// 静默运行安装器。安装器清单要求提权（os error 740）时用 `__COMPAT_LAYER=RunAsInvoker`
/// 重试：安装器是每用户安装，不需要管理员权限。
#[cfg(windows)]
fn run_installer(installer: &std::path::Path, log: &std::path::Path) -> std::io::Result<()> {
    use std::os::windows::process::CommandExt;

    let launch = |run_as_invoker: bool| {
        let mut command = std::process::Command::new(installer);
        command
            .args(["/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-"])
            // 路径含空格时 Inno Setup 要求 `/LOG="<path>"` 的形式。
            .raw_arg(format!("/LOG=\"{}\"", log.display()))
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .creation_flags(CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP);
        if run_as_invoker {
            command.env("__COMPAT_LAYER", "RunAsInvoker");
        }
        command.spawn().map(drop)
    };
    tracing::info!(installer = %installer.display(), "running installer after update");
    match launch(false) {
        Err(error) if error.raw_os_error() == Some(ERROR_ELEVATION_REQUIRED) => {
            tracing::info!("installer requested elevation; retrying with RunAsInvoker");
            launch(true)
        }
        other => other,
    }
}

#[cfg(all(test, any(windows, target_os = "linux", target_os = "macos")))]
mod tests {
    use std::ffi::OsString;
    use std::path::PathBuf;

    use super::{RestartPlan, run_pending, schedule, scheduled_server_restart};

    /// 全局槽位被多个断言共享，合并成一个测试避免并行互相覆盖。
    #[test]
    fn pending_plan_is_taken_once_and_reports_its_scope() {
        assert!(run_pending().is_ok(), "no plan is a no-op");
        assert!(!scheduled_server_restart());

        let missing = std::env::temp_dir().join(format!(
            "fluxdown-restart-missing-{}",
            uuid::Uuid::new_v4().simple()
        ));
        schedule(RestartPlan::desktop(missing.clone(), Vec::new()));
        assert!(!scheduled_server_restart());
        schedule(RestartPlan::server(
            PathBuf::from("/definitely/not/here"),
            vec![OsString::from("--server")],
        ));
        assert!(
            scheduled_server_restart(),
            "later plans replace earlier ones"
        );

        // 启动失败如实返回，且计划已被取走。
        schedule(RestartPlan::desktop(missing, Vec::new()));
        let error = run_pending().expect_err("missing program must fail to start");
        assert_eq!(error.kind(), std::io::ErrorKind::NotFound);
        assert!(run_pending().is_ok());
        assert!(!scheduled_server_restart());
    }
}
