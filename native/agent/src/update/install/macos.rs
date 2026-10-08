//! macOS：整体替换外层 `FluxDown.app`。
//!
//! 1. 从 DMG（`hdiutil attach` 只读挂载 + `ditto`）或 tarball（系统 `tar`，正确处理
//!    AppleDouble / xattr）取出新 bundle，落在旧 bundle 旁的暂存目录（同一卷，保证后面的改名原子）；
//! 2. `codesign --verify --deep --strict` 且 TeamIdentifier 与当前 bundle 一致，否则拒绝；
//! 3. 旧 bundle 改名为 `.<Name>.old-<pid>`、新 bundle 改名到原位；第二步失败立即改回。
//!
//! 旧 bundle 不立刻删除（仍有进程在其中运行），下次启动由 [`super::cleanup_leftovers`] 清理。
//! 重启用 `open -n <bundle> --args --after-update`：由 LaunchServices 拉起，签名与权限归属正确。

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::Duration;

use fluxdown_protocol::UpdateManualReason;

use super::{InstallError, detect, ensure_success, run_tool};
use crate::update::restart::{AFTER_UPDATE_ARG, RestartPlan};

const HDIUTIL: &str = "/usr/bin/hdiutil";
const DITTO: &str = "/usr/bin/ditto";
const TAR: &str = "/usr/bin/tar";
const CODESIGN: &str = "/usr/bin/codesign";
const OPEN: &str = "/usr/bin/open";
/// 发布包里 bundle 的目录名。
const BUNDLE_NAME: &str = "FluxDown.app";

const ATTACH_TIMEOUT: Duration = Duration::from_secs(180);
const DETACH_TIMEOUT: Duration = Duration::from_secs(60);
const COPY_TIMEOUT: Duration = Duration::from_secs(600);
const VERIFY_TIMEOUT: Duration = Duration::from_secs(180);

fn current_bundle() -> Result<PathBuf, InstallError> {
    detect::current_exe_path()
        .as_deref()
        .and_then(detect::outer_app_bundle)
        .ok_or_else(|| InstallError::Storage {
            context: "locate the running FluxDown.app".to_owned(),
            source: io::Error::new(io::ErrorKind::NotFound, "not running from an .app bundle"),
        })
}

/// `codesign -dv` 输出（stderr）里的 `TeamIdentifier=`；未签名 / ad-hoc（`not set`）为 `None`。
fn parse_team_identifier(output: &str) -> Option<String> {
    output
        .lines()
        .find_map(|line| line.trim().strip_prefix("TeamIdentifier="))
        .map(str::trim)
        .filter(|team| !team.is_empty() && *team != "not set")
        .map(str::to_owned)
}

async fn team_identifier(bundle: &Path) -> Result<Option<String>, InstallError> {
    let output = run_tool(
        Path::new(CODESIGN),
        [
            std::ffi::OsStr::new("-dv"),
            std::ffi::OsStr::new("--verbose=2"),
            bundle.as_os_str(),
        ],
        VERIFY_TIMEOUT,
    )
    .await?;
    // 未签名的 bundle 会以非零码退出且没有 TeamIdentifier，等同于「没有团队」。
    Ok(parse_team_identifier(&String::from_utf8_lossy(
        &output.stderr,
    )))
}

/// 当前 bundle 必须有 Developer ID 团队（官方签名构建）：否则无从判断新包是否同源。
pub(super) async fn preflight() -> Option<UpdateManualReason> {
    let Ok(bundle) = current_bundle() else {
        return Some(UpdateManualReason::Unsupported);
    };
    match team_identifier(&bundle).await {
        Ok(Some(_)) => None,
        Ok(None) => Some(UpdateManualReason::UnofficialBuild),
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "could not read the app signature");
            Some(UpdateManualReason::Unsupported)
        }
    }
}

pub(super) async fn apply(package: &Path, work_dir: &Path) -> Result<RestartPlan, InstallError> {
    let bundle = current_bundle()?;
    let (Some(parent), Some(name)) = (bundle.parent(), bundle.file_name()) else {
        return Err(InstallError::Storage {
            context: format!("locate the parent of {}", bundle.display()),
            source: io::Error::new(io::ErrorKind::NotFound, "no parent directory"),
        });
    };
    let name = name.to_string_lossy().into_owned();
    let current_team = team_identifier(&bundle).await?.ok_or_else(|| {
        InstallError::Command(format!(
            "{} has no Developer ID signature; refusing to replace it",
            bundle.display()
        ))
    })?;

    let staging = parent.join(format!(".fluxdown-staging-{}", std::process::id()));
    remove_dir_if_exists(&staging).map_err(InstallError::storage(format!(
        "clear {}",
        staging.display()
    )))?;
    fs::create_dir(&staging).map_err(InstallError::storage(format!(
        "create {}",
        staging.display()
    )))?;
    let result =
        stage_verify_swap(package, work_dir, &staging, &bundle, &name, &current_team).await;
    if let Err(error) = remove_dir_if_exists(&staging) {
        tracing::debug!(path = %staging.display(), %error, "could not remove update staging directory");
    }
    result?;
    Ok(RestartPlan::desktop(
        PathBuf::from(OPEN),
        vec![
            "-n".into(),
            bundle.into_os_string(),
            "--args".into(),
            AFTER_UPDATE_ARG.into(),
        ],
    ))
}

async fn stage_verify_swap(
    package: &Path,
    work_dir: &Path,
    staging: &Path,
    bundle: &Path,
    name: &str,
    current_team: &str,
) -> Result<(), InstallError> {
    let new_bundle = stage(package, work_dir, staging).await?;
    verify(&new_bundle, current_team).await?;
    swap(bundle, &new_bundle, name)
}

fn remove_dir_if_exists(path: &Path) -> io::Result<()> {
    match fs::remove_dir_all(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        other => other,
    }
}

async fn stage(package: &Path, work_dir: &Path, staging: &Path) -> Result<PathBuf, InstallError> {
    let file_name = package
        .file_name()
        .and_then(std::ffi::OsStr::to_str)
        .unwrap_or_default()
        .to_ascii_lowercase();
    if file_name.ends_with(".dmg") {
        stage_dmg(package, work_dir, staging).await
    } else if file_name.ends_with(".tar.gz") || file_name.ends_with(".tgz") {
        stage_tarball(package, staging).await
    } else {
        Err(InstallError::InvalidPackage(format!(
            "unsupported macOS package: {file_name}"
        )))
    }
}

async fn stage_dmg(
    package: &Path,
    work_dir: &Path,
    staging: &Path,
) -> Result<PathBuf, InstallError> {
    let mount = work_dir.join("mnt");
    fs::create_dir_all(&mount)
        .map_err(InstallError::storage(format!("create {}", mount.display())))?;
    let hdiutil = Path::new(HDIUTIL);
    let attached = run_tool(
        hdiutil,
        [
            std::ffi::OsStr::new("attach"),
            std::ffi::OsStr::new("-nobrowse"),
            std::ffi::OsStr::new("-noautoopen"),
            std::ffi::OsStr::new("-readonly"),
            std::ffi::OsStr::new("-mountpoint"),
            mount.as_os_str(),
            package.as_os_str(),
        ],
        ATTACH_TIMEOUT,
    )
    .await?;
    ensure_success(hdiutil, &attached)?;

    let copied = copy_app_from_volume(&mount, staging).await;
    detach(&mount).await;
    if let Err(error) = fs::remove_dir(&mount) {
        tracing::debug!(path = %mount.display(), %error, "could not remove DMG mount point");
    }
    copied
}

async fn copy_app_from_volume(mount: &Path, staging: &Path) -> Result<PathBuf, InstallError> {
    let source = mount.join(BUNDLE_NAME);
    if !source.is_dir() {
        return Err(InstallError::InvalidPackage(format!(
            "the disk image does not contain {BUNDLE_NAME}"
        )));
    }
    let target = staging.join(BUNDLE_NAME);
    let ditto = Path::new(DITTO);
    let output = run_tool(
        ditto,
        [source.as_os_str(), target.as_os_str()],
        COPY_TIMEOUT,
    )
    .await?;
    ensure_success(ditto, &output)?;
    Ok(target)
}

/// 卸载 DMG：先正常卸载，仍被占用再强制；失败只告警（卷是 `-nobrowse` 的只读挂载，不影响更新结果）。
async fn detach(mount: &Path) {
    let hdiutil = Path::new(HDIUTIL);
    for force in [false, true] {
        let mut args = vec![std::ffi::OsStr::new("detach")];
        if force {
            args.push(std::ffi::OsStr::new("-force"));
        }
        args.push(mount.as_os_str());
        match run_tool(hdiutil, args, DETACH_TIMEOUT).await {
            Ok(output) if output.status.success() => return,
            Ok(output) => tracing::warn!(
                force,
                status = %output.status,
                "hdiutil detach did not succeed"
            ),
            Err(error) => {
                tracing::warn!(force, error = %format!("{error:#}"), "hdiutil detach failed");
            }
        }
    }
}

async fn stage_tarball(package: &Path, staging: &Path) -> Result<PathBuf, InstallError> {
    let tar = Path::new(TAR);
    let output = run_tool(
        tar,
        [
            std::ffi::OsStr::new("-xzf"),
            package.as_os_str(),
            std::ffi::OsStr::new("-C"),
            staging.as_os_str(),
        ],
        COPY_TIMEOUT,
    )
    .await?;
    ensure_success(tar, &output)?;
    find_app(staging).ok_or_else(|| {
        InstallError::InvalidPackage(format!("the archive does not contain {BUNDLE_NAME}"))
    })
}

/// 在 `dir` 或其一级子目录里找 `FluxDown.app`（tarball 为 `<prefix>/FluxDown.app`）。
fn find_app(dir: &Path) -> Option<PathBuf> {
    let direct = dir.join(BUNDLE_NAME);
    if direct.is_dir() {
        return Some(direct);
    }
    fs::read_dir(dir)
        .ok()?
        .flatten()
        .map(|entry| entry.path().join(BUNDLE_NAME))
        .find(|candidate| candidate.is_dir())
}

async fn verify(new_bundle: &Path, current_team: &str) -> Result<(), InstallError> {
    let codesign = Path::new(CODESIGN);
    let output = run_tool(
        codesign,
        [
            std::ffi::OsStr::new("--verify"),
            std::ffi::OsStr::new("--deep"),
            std::ffi::OsStr::new("--strict"),
            new_bundle.as_os_str(),
        ],
        VERIFY_TIMEOUT,
    )
    .await?;
    if !output.status.success() {
        return Err(InstallError::InvalidPackage(format!(
            "the new app failed code signature verification: {}",
            super::stderr_tail(&output.stderr)
        )));
    }
    match team_identifier(new_bundle).await? {
        Some(team) if team == current_team => Ok(()),
        Some(team) => Err(InstallError::InvalidPackage(format!(
            "the new app is signed by team {team}, expected {current_team}"
        ))),
        None => Err(InstallError::InvalidPackage(
            "the new app has no Developer ID team".to_owned(),
        )),
    }
}

/// 旧 → `.old`、新 → 原位；第二步失败则把旧的改回去。
fn swap(bundle: &Path, new_bundle: &Path, name: &str) -> Result<(), InstallError> {
    let parent = bundle.parent().unwrap_or_else(|| Path::new("/"));
    let old = parent.join(format!(".{name}.old-{}", std::process::id()));
    remove_dir_if_exists(&old)
        .map_err(InstallError::replace(format!("clear {}", old.display())))?;
    fs::rename(bundle, &old).map_err(InstallError::replace(format!(
        "move {} aside",
        bundle.display()
    )))?;
    if let Err(error) = fs::rename(new_bundle, bundle) {
        let context = match fs::rename(&old, bundle) {
            Ok(()) => format!("move the new app to {}", bundle.display()),
            Err(restore) => format!(
                "move the new app to {}; restoring the old app from {} also failed: {restore}",
                bundle.display(),
                old.display()
            ),
        };
        return Err(InstallError::Replace {
            context,
            source: error,
        });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::update::install::test_support::TempDir;

    #[test]
    fn team_identifier_is_parsed_from_codesign_output() {
        let signed = "Executable=/A/FluxDown.app/Contents/MacOS/fluxdown-desktop\n\
                      Identifier=dev.zerx.fluxdown\n\
                      TeamIdentifier=KD4N89AAF5\n\
                      Sealed Resources version=2\n";
        assert_eq!(parse_team_identifier(signed).as_deref(), Some("KD4N89AAF5"));
        assert_eq!(parse_team_identifier("TeamIdentifier=not set\n"), None);
        assert_eq!(parse_team_identifier("TeamIdentifier=\n"), None);
        assert_eq!(
            parse_team_identifier("/A/FluxDown.app: code object is not signed at all\n"),
            None
        );
    }

    #[test]
    fn swap_replaces_the_bundle_and_keeps_the_old_one_aside() -> io::Result<()> {
        let dir = TempDir::new("macos-swap")?;
        let bundle = dir.path().join("FluxDown.app");
        let staging = dir.path().join(".staging").join("FluxDown.app");
        fs::create_dir_all(bundle.join("Contents"))?;
        fs::write(bundle.join("Contents").join("version"), "old")?;
        fs::create_dir_all(staging.join("Contents"))?;
        fs::write(staging.join("Contents").join("version"), "new")?;

        swap(&bundle, &staging, "FluxDown.app").map_err(io::Error::other)?;
        assert_eq!(
            fs::read_to_string(bundle.join("Contents").join("version"))?,
            "new"
        );
        let old = dir
            .path()
            .join(format!(".FluxDown.app.old-{}", std::process::id()));
        assert_eq!(
            fs::read_to_string(old.join("Contents").join("version"))?,
            "old"
        );
        Ok(())
    }

    #[test]
    fn swap_restores_the_old_bundle_when_the_new_one_cannot_move_in() -> io::Result<()> {
        let dir = TempDir::new("macos-swap-rollback")?;
        let bundle = dir.path().join("FluxDown.app");
        fs::create_dir_all(bundle.join("Contents"))?;
        fs::write(bundle.join("Contents").join("version"), "old")?;
        let missing_new = dir.path().join(".staging").join("FluxDown.app");

        let error = swap(&bundle, &missing_new, "FluxDown.app")
            .err()
            .ok_or_else(|| io::Error::other("swap must fail"))?;
        assert!(matches!(error, InstallError::Replace { .. }));
        assert_eq!(
            fs::read_to_string(bundle.join("Contents").join("version"))?,
            "old"
        );
        Ok(())
    }

    #[test]
    fn find_app_looks_one_level_deep() -> io::Result<()> {
        let dir = TempDir::new("macos-find-app")?;
        assert_eq!(find_app(dir.path()), None);
        let nested = dir
            .path()
            .join("FluxDown-1.0.0-macos-arm64")
            .join(BUNDLE_NAME);
        fs::create_dir_all(&nested)?;
        assert_eq!(find_app(dir.path()), Some(nested));
        let direct = dir.path().join(BUNDLE_NAME);
        fs::create_dir_all(&direct)?;
        assert_eq!(find_app(dir.path()), Some(direct));
        Ok(())
    }
}
