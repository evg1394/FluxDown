//! Linux 安装形态：AppImage（原地换文件）与 deb / Arch 包（pkexec 提权安装，运行中即可执行）。
//! Linux 便携 tarball 走 [`super::apply_portable`]。

use std::ffi::OsString;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::time::Duration;

use fluxdown_protocol::{UpdateInstallKind, UpdateManualReason};

use super::{InstallError, current_exe_and_dir, desktop_relaunch, detect, run_tool};
use crate::permission::{self, Invocation, Os, PermissionError, Tools};
use crate::update::restart::RestartPlan;

/// 包管理器安装（含用户在授权对话框里的停留时间）的上限。
const PACKAGE_INSTALL_TIMEOUT: Duration = Duration::from_secs(15 * 60);

/// deb / Arch 安装需要 pkexec 与图形会话（polkit 认证对话框）。
pub(super) fn elevation_unavailable() -> Option<UpdateManualReason> {
    let has_pkexec = Tools::locate(Os::Linux).pkexec.is_some();
    let graphical = ["DISPLAY", "WAYLAND_DISPLAY"]
        .iter()
        .any(|name| std::env::var_os(name).is_some_and(|value| !value.is_empty()));
    (!(has_pkexec && graphical)).then_some(UpdateManualReason::ElevationUnavailable)
}

fn not_found(context: &str, what: &str) -> InstallError {
    InstallError::Storage {
        context: context.to_owned(),
        source: io::Error::new(io::ErrorKind::NotFound, what.to_owned()),
    }
}

/// AppImage：把新包复制到旧文件旁的隐藏临时文件（同一文件系统），`chmod 755` 后改名覆盖。
/// 运行中的旧进程继续使用被覆盖前的 inode，不受影响。
pub(super) fn apply_appimage(package: &Path) -> Result<RestartPlan, InstallError> {
    use std::os::unix::fs::PermissionsExt;

    let target = detect::appimage_path()
        .ok_or_else(|| not_found("locate the running AppImage", "$APPIMAGE is not set"))?;
    let mut magic = [0_u8; 4];
    fs::File::open(package)
        .and_then(|mut file| file.read_exact(&mut magic))
        .map_err(InstallError::storage(format!("read {}", package.display())))?;
    if &magic != b"\x7fELF" {
        return Err(InstallError::InvalidPackage(format!(
            "{} is not an AppImage (ELF) file",
            package.display()
        )));
    }
    let (Some(dir), Some(name)) = (target.parent(), target.file_name()) else {
        return Err(not_found(
            "locate the AppImage directory",
            "no parent directory",
        ));
    };
    let mut temp_name = OsString::from(".");
    temp_name.push(name);
    temp_name.push(format!(".fluxdown-new-{}", std::process::id()));
    let temp = dir.join(temp_name);

    let staged = fs::copy(package, &temp)
        .and_then(|_| fs::set_permissions(&temp, fs::Permissions::from_mode(0o755)))
        .map_err(InstallError::storage(format!("stage {}", temp.display())));
    let installed = staged.and_then(|()| {
        fs::rename(&temp, &target).map_err(InstallError::replace(format!(
            "replace {}",
            target.display()
        )))
    });
    if let Err(error) = installed {
        if let Err(cleanup) = fs::remove_file(&temp)
            && cleanup.kind() != io::ErrorKind::NotFound
        {
            tracing::debug!(path = %temp.display(), error = %cleanup, "could not remove staged AppImage");
        }
        return Err(error);
    }
    Ok(RestartPlan::desktop(
        target,
        vec![crate::update::restart::AFTER_UPDATE_ARG.into()],
    ))
}

/// deb / Arch：经 pkexec 在程序运行时安装，包管理器自行原子替换文件。
pub(super) async fn apply_package(
    kind: UpdateInstallKind,
    package: &Path,
) -> Result<RestartPlan, InstallError> {
    // 安装前取程序路径：包管理器替换文件后 `/proc/self/exe` 会带 ` (deleted)`。
    let (exe, _) = current_exe_and_dir()?;
    let (program, args) = install_command(kind, package)?;
    let tools = Tools::locate(Os::Linux);
    let invocation = permission::elevated_invocation(
        Invocation {
            program,
            args,
            env: Vec::new(),
        },
        Os::Linux,
        &tools,
    )
    .map_err(map_permission)?;
    tracing::info!(program = %invocation.program.display(), "requesting administrator authorization to install the update");
    let output = run_tool(
        &invocation.program,
        &invocation.args,
        PACKAGE_INSTALL_TIMEOUT,
    )
    .await?;
    // pkexec：126 = 用户关闭了认证对话框；127 = 未获授权 / 没有认证代理。
    permission::classify_elevation_exit(
        Os::Linux,
        output.status.code(),
        &String::from_utf8_lossy(&output.stderr),
    )
    .map_err(map_permission)?;
    Ok(desktop_relaunch(&exe))
}

fn first_existing(candidates: &[&str]) -> Option<PathBuf> {
    candidates
        .iter()
        .map(PathBuf::from)
        .find(|candidate| candidate.is_file())
}

/// 提权前的安装命令：deb 优先 `apt-get install -y <绝对路径>`（自动处理依赖），
/// 无 apt-get 时 `dpkg -i`；Arch 用 `pacman -U --noconfirm`。
fn install_command(
    kind: UpdateInstallKind,
    package: &Path,
) -> Result<(PathBuf, Vec<OsString>), InstallError> {
    let name = package
        .file_name()
        .and_then(std::ffi::OsStr::to_str)
        .unwrap_or_default();
    let package = package.as_os_str().to_owned();
    match kind {
        UpdateInstallKind::LinuxDeb => {
            if !name.ends_with(".deb") {
                return Err(InstallError::InvalidPackage(format!("not a .deb: {name}")));
            }
            if let Some(apt) = first_existing(&["/usr/bin/apt-get"]) {
                Ok((
                    PathBuf::from("/usr/bin/env"),
                    vec![
                        "DEBIAN_FRONTEND=noninteractive".into(),
                        apt.into_os_string(),
                        "install".into(),
                        "-y".into(),
                        package,
                    ],
                ))
            } else if let Some(dpkg) = first_existing(&["/usr/bin/dpkg"]) {
                Ok((dpkg, vec!["-i".into(), package]))
            } else {
                Err(InstallError::Command(
                    "neither apt-get nor dpkg is installed".to_owned(),
                ))
            }
        }
        UpdateInstallKind::LinuxArch => {
            if !name.contains(".pkg.tar") {
                return Err(InstallError::InvalidPackage(format!(
                    "not a pacman package: {name}"
                )));
            }
            let pacman = first_existing(&["/usr/bin/pacman"])
                .ok_or_else(|| InstallError::Command("pacman is not installed".to_owned()))?;
            Ok((pacman, vec!["-U".into(), "--noconfirm".into(), package]))
        }
        other => Err(InstallError::Unsupported(other)),
    }
}

fn map_permission(error: PermissionError) -> InstallError {
    match error {
        PermissionError::Cancelled => InstallError::ElevationCancelled,
        PermissionError::ElevationUnavailable(message) => {
            InstallError::ElevationUnavailable(message)
        }
        PermissionError::Failed(message) => InstallError::Command(message),
        other => InstallError::Command(other.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deb_and_arch_commands_use_absolute_package_paths() -> Result<(), InstallError> {
        let deb = Path::new("/home/u/.local/share/updates/fluxdown_1.2.0_amd64.deb");
        // 命令的具体程序取决于宿主是否装了 apt-get / dpkg；只校验参数形态。
        if let Ok((program, args)) = install_command(UpdateInstallKind::LinuxDeb, deb) {
            assert_eq!(args.last().map(OsString::as_os_str), Some(deb.as_os_str()));
            assert!(
                program.ends_with("env") && args.iter().any(|a| a == "install")
                    || program.ends_with("dpkg") && args.first().is_some_and(|a| a == "-i")
            );
        }
        assert!(matches!(
            install_command(UpdateInstallKind::LinuxDeb, Path::new("/x/a.rpm")),
            Err(InstallError::InvalidPackage(_))
        ));
        assert!(matches!(
            install_command(UpdateInstallKind::LinuxArch, Path::new("/x/a.deb")),
            Err(InstallError::InvalidPackage(_))
        ));
        assert!(matches!(
            install_command(UpdateInstallKind::MacosApp, deb),
            Err(InstallError::Unsupported(_))
        ));
        Ok(())
    }

    #[test]
    fn pkexec_exit_codes_map_to_cancel_and_unavailable() {
        let cancelled =
            permission::classify_elevation_exit(Os::Linux, Some(126), "").map_err(map_permission);
        assert!(matches!(cancelled, Err(InstallError::ElevationCancelled)));
        let unavailable = permission::classify_elevation_exit(Os::Linux, Some(127), "no agent")
            .map_err(map_permission);
        assert!(matches!(
            unavailable,
            Err(InstallError::ElevationUnavailable(message)) if message == "no agent"
        ));
        let failed = permission::classify_elevation_exit(Os::Linux, Some(100), "E: broken")
            .map_err(map_permission);
        assert!(
            matches!(failed, Err(InstallError::Command(message)) if message.contains("E: broken"))
        );
        assert!(permission::classify_elevation_exit(Os::Linux, Some(0), "").is_ok());
    }
}
