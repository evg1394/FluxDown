//! 安装形态检测：把「环境事实」（[`Facts`]）纯函数式地映射为 [`InstallTarget`]。
//!
//! 事实采集（[`gather`]）只读环境变量 / 路径 / 轻量命令（`dpkg -S`、`sysctl`），不写盘；
//! 分类（[`classify`]）不碰系统，各平台的映射都能在任一宿主上单测。
//!
//! 架构取**硬件**而非构建：Windows x64 构建跑在 ARM64 仿真下、macOS x64 构建跑在 Rosetta 下，
//! 都应拿 arm64 资产——否则更新后会永久停留在仿真层。

use std::path::{Path, PathBuf};

use fluxdown_protocol::{UpdateInstallKind, UpdateManualReason};

use super::{InstallTarget, ReleaseComponent};

/// 容器 / NAS 包在启动脚本里导出的安装来源。
const INSTALL_SOURCE_ENV: &str = "FLUXDOWN_INSTALL_SOURCE";
/// deb / Arch 包的安装根。
#[cfg(target_os = "linux")]
const PACKAGE_ROOT: &str = "/opt/fluxdown";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum HostOs {
    Windows,
    Linux,
    Macos,
    Other,
}

impl HostOs {
    const CURRENT: Self = if cfg!(windows) {
        Self::Windows
    } else if cfg!(target_os = "linux") {
        Self::Linux
    } else if cfg!(target_os = "macos") {
        Self::Macos
    } else {
        Self::Other
    };
}

/// 机器（硬件）架构。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Arch {
    X64,
    Arm64,
    Other,
}

impl Arch {
    fn from_rust(arch: &str) -> Self {
        match arch {
            "x86_64" => Self::X64,
            "aarch64" => Self::Arm64,
            _ => Self::Other,
        }
    }
}

/// 拥有当前程序文件的系统包管理器。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub(super) enum PackageOwner {
    Dpkg,
    Pacman,
}

/// 分类所需的全部环境事实。
#[derive(Clone, Debug)]
pub(super) struct Facts {
    pub os: HostOs,
    pub server_mode: bool,
    /// 发布流水线构建（注入了 `FLUXDOWN_APP_VERSION`）。
    pub official_build: bool,
    pub arch: Arch,
    /// `FLUXDOWN_INSTALL_SOURCE` 的原始值。
    pub install_source: Option<String>,
    /// 存在 `/.dockerenv`。
    pub in_docker: bool,
    /// 当前程序路径（读取失败为 `None`）。
    pub exe: Option<PathBuf>,
    /// Windows：程序目录里有 `portable` 标记。
    pub portable_marker: bool,
    /// Linux：以 AppImage 运行（`$APPIMAGE` 指向存在的文件）。
    pub appimage: bool,
    /// Linux：`/opt/fluxdown` 下的程序归哪个包管理器所有。
    pub package_owner: Option<PackageOwner>,
    /// macOS：从只读位置运行（App Translocation / 磁盘映像卷）。
    pub read_only_location: bool,
}

/// 启动时调用一次：采集事实并分类。
pub(super) fn detect(server_mode: bool) -> InstallTarget {
    classify(&gather(server_mode))
}

fn official_build() -> bool {
    option_env!("FLUXDOWN_APP_VERSION").is_some_and(|version| !version.trim().is_empty())
}

pub(super) fn gather(server_mode: bool) -> Facts {
    let os = HostOs::CURRENT;
    let exe = current_exe_path();
    let portable_marker = os == HostOs::Windows
        && exe
            .as_deref()
            .and_then(Path::parent)
            .is_some_and(|dir| dir.join("portable").exists());
    let appimage = os == HostOs::Linux && !server_mode && appimage_path().is_some();
    let package_owner = (os == HostOs::Linux && !server_mode && !appimage)
        .then(|| exe.as_deref().and_then(linux_package_owner))
        .flatten();
    let read_only_location = os == HostOs::Macos
        && !server_mode
        && exe
            .as_deref()
            .and_then(outer_app_bundle)
            .is_some_and(|bundle| is_read_only_location(&bundle));
    Facts {
        os,
        server_mode,
        official_build: official_build(),
        arch: hardware_arch(),
        install_source: std::env::var(INSTALL_SOURCE_ENV).ok(),
        in_docker: server_mode && Path::new("/.dockerenv").exists(),
        exe,
        portable_marker,
        appimage,
        package_owner,
        read_only_location,
    }
}

pub(super) fn classify(facts: &Facts) -> InstallTarget {
    let (kind, component, asset_keys) = if facts.server_mode {
        classify_server(facts)
    } else {
        classify_desktop(facts)
    };
    let manual_reason = if !facts.official_build {
        Some(UpdateManualReason::UnofficialBuild)
    } else if matches!(
        kind,
        UpdateInstallKind::Docker
            | UpdateInstallKind::Synology
            | UpdateInstallKind::Qnap
            | UpdateInstallKind::Openwrt
    ) {
        Some(UpdateManualReason::ManagedPackage)
    } else if kind == UpdateInstallKind::Unknown {
        Some(UpdateManualReason::Unsupported)
    } else if kind == UpdateInstallKind::MacosApp && facts.read_only_location {
        Some(UpdateManualReason::ReadOnlyLocation)
    } else if asset_keys.is_empty() {
        Some(UpdateManualReason::NoAsset)
    } else {
        None
    };
    InstallTarget {
        kind,
        component,
        asset_keys,
        manual_reason,
    }
}

fn classify_server(facts: &Facts) -> (UpdateInstallKind, ReleaseComponent, Vec<&'static str>) {
    let managed = match facts
        .install_source
        .as_deref()
        .map(|source| source.trim().to_ascii_lowercase())
        .as_deref()
    {
        Some("docker") => Some(UpdateInstallKind::Docker),
        Some("synology") => Some(UpdateInstallKind::Synology),
        Some("qnap") => Some(UpdateInstallKind::Qnap),
        Some("openwrt") => Some(UpdateInstallKind::Openwrt),
        _ => facts.in_docker.then_some(UpdateInstallKind::Docker),
    };
    if let Some(kind) = managed {
        return (kind, ReleaseComponent::Server, Vec::new());
    }
    let key = match (facts.os, facts.arch) {
        (HostOs::Windows, Arch::X64) => Some("windows_x64"),
        (HostOs::Windows, Arch::Arm64) => Some("windows_arm64"),
        (HostOs::Linux, Arch::X64) => Some("linux_x64"),
        (HostOs::Linux, Arch::Arm64) => Some("linux_arm64"),
        (HostOs::Macos, Arch::X64) => Some("macos_x64"),
        (HostOs::Macos, Arch::Arm64) => Some("macos_arm64"),
        _ => None,
    };
    let kind = if facts.os == HostOs::Other || facts.exe.is_none() {
        UpdateInstallKind::Unknown
    } else {
        UpdateInstallKind::ServerBinary
    };
    (kind, ReleaseComponent::Server, key.into_iter().collect())
}

fn classify_desktop(facts: &Facts) -> (UpdateInstallKind, ReleaseComponent, Vec<&'static str>) {
    let component = ReleaseComponent::Desktop;
    if facts.exe.is_none() {
        return (UpdateInstallKind::Unknown, component, Vec::new());
    }
    match facts.os {
        HostOs::Windows => {
            let (kind, key) = match (facts.portable_marker, facts.arch) {
                (true, Arch::X64) => (UpdateInstallKind::WindowsPortable, Some("portable")),
                (true, Arch::Arm64) => (UpdateInstallKind::WindowsPortable, Some("portable_arm64")),
                (false, Arch::X64) => (UpdateInstallKind::WindowsSetup, Some("setup")),
                (false, Arch::Arm64) => (UpdateInstallKind::WindowsSetup, Some("setup_arm64")),
                (portable, Arch::Other) => (
                    if portable {
                        UpdateInstallKind::WindowsPortable
                    } else {
                        UpdateInstallKind::WindowsSetup
                    },
                    None,
                ),
            };
            (kind, component, key.into_iter().collect())
        }
        HostOs::Linux => {
            let (kind, key) = if facts.appimage {
                (UpdateInstallKind::LinuxAppImage, "linux_appimage")
            } else {
                match facts.package_owner {
                    Some(PackageOwner::Dpkg) => (UpdateInstallKind::LinuxDeb, "linux_deb"),
                    Some(PackageOwner::Pacman) => (UpdateInstallKind::LinuxArch, "linux_arch"),
                    None => (UpdateInstallKind::LinuxPortable, "linux_tarball"),
                }
            };
            // Linux 桌面发行物只有 x64。
            let keys = if facts.arch == Arch::X64 {
                vec![key]
            } else {
                Vec::new()
            };
            (kind, component, keys)
        }
        HostOs::Macos => {
            let keys = match facts.arch {
                Arch::Arm64 => vec!["macos_dmg_arm64", "macos_tarball_arm64"],
                Arch::X64 => vec!["macos_dmg_x64", "macos_tarball_x64"],
                Arch::Other => Vec::new(),
            };
            (UpdateInstallKind::MacosApp, component, keys)
        }
        HostOs::Other => (UpdateInstallKind::Unknown, component, Vec::new()),
    }
}

/// 当前可执行文件路径。Linux 上程序被换掉后 `/proc/self/exe` 会带 ` (deleted)` 后缀，去掉它。
pub(super) fn current_exe_path() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    #[cfg(target_os = "linux")]
    if let Some(stripped) = exe.to_str().and_then(|s| s.strip_suffix(" (deleted)")) {
        return Some(PathBuf::from(stripped));
    }
    Some(exe)
}

/// `$APPIMAGE`：指向存在的文件才算。
pub(super) fn appimage_path() -> Option<PathBuf> {
    let path = PathBuf::from(std::env::var_os("APPIMAGE").filter(|value| !value.is_empty())?);
    path.is_file().then_some(path)
}

/// 最外层的 `.app` 目录（辅助 bundle 嵌在外层 bundle 里，取最外层）。
pub(super) fn outer_app_bundle(exe: &Path) -> Option<PathBuf> {
    exe.ancestors()
        .filter(|ancestor| ancestor.extension().is_some_and(|ext| ext == "app"))
        .last()
        .map(Path::to_path_buf)
}

/// App Translocation（Gatekeeper 的随机只读挂载）或磁盘映像卷里的 bundle 无法被替换。
pub(super) fn is_read_only_location(bundle: &Path) -> bool {
    let text = bundle.to_string_lossy();
    text.contains("/AppTranslocation/") || text.starts_with("/Volumes/")
}

#[cfg(target_os = "linux")]
fn linux_package_owner(exe: &Path) -> Option<PackageOwner> {
    let exe = exe.to_str()?;
    if !exe.starts_with(PACKAGE_ROOT) {
        return None;
    }
    if package_owns(&["dpkg", "-S"], exe) {
        return Some(PackageOwner::Dpkg);
    }
    if package_owns(&["pacman", "-Qo"], exe) {
        return Some(PackageOwner::Pacman);
    }
    None
}

#[cfg(not(target_os = "linux"))]
fn linux_package_owner(_exe: &Path) -> Option<PackageOwner> {
    None
}

#[cfg(target_os = "linux")]
fn package_owns(command: &[&str], exe: &str) -> bool {
    std::process::Command::new(command[0])
        .args(&command[1..])
        .arg(exe)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .is_ok_and(|status| status.success())
}

#[cfg(windows)]
fn hardware_arch() -> Arch {
    use winreg::RegKey;
    use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ};

    // 注册表里的值是机器的原生架构；进程环境变量 / `cfg!` 在仿真下只反映仿真层。
    let native = RegKey::predef(HKEY_LOCAL_MACHINE)
        .open_subkey_with_flags(
            r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment",
            KEY_READ,
        )
        .and_then(|key| key.get_value::<String, _>("PROCESSOR_ARCHITECTURE"));
    match native {
        Ok(value) => match value.trim().to_ascii_uppercase().as_str() {
            "AMD64" => Arch::X64,
            "ARM64" => Arch::Arm64,
            _ => Arch::Other,
        },
        Err(error) => {
            tracing::debug!(%error, "native processor architecture unavailable; using build arch");
            Arch::from_rust(std::env::consts::ARCH)
        }
    }
}

#[cfg(target_os = "macos")]
fn hardware_arch() -> Arch {
    let build = Arch::from_rust(std::env::consts::ARCH);
    if build != Arch::X64 {
        return build;
    }
    // x64 构建可能在 Rosetta 下运行在 Apple Silicon 上：`hw.optional.arm64` 为 1 即 arm64 硬件
    // （Intel 机器上该键不存在，命令失败）。
    match std::process::Command::new("/usr/sbin/sysctl")
        .args(["-n", "hw.optional.arm64"])
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .output()
    {
        Ok(output) if output.status.success() && output.stdout.trim_ascii() == b"1" => Arch::Arm64,
        Ok(_) => Arch::X64,
        Err(error) => {
            tracing::debug!(%error, "sysctl unavailable; using build arch");
            Arch::X64
        }
    }
}

#[cfg(not(any(windows, target_os = "macos")))]
fn hardware_arch() -> Arch {
    Arch::from_rust(std::env::consts::ARCH)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn facts(os: HostOs, arch: Arch) -> Facts {
        Facts {
            os,
            server_mode: false,
            official_build: true,
            arch,
            install_source: None,
            in_docker: false,
            exe: Some(PathBuf::from("/app/fluxdown-agent")),
            portable_marker: false,
            appimage: false,
            package_owner: None,
            read_only_location: false,
        }
    }

    fn keys(target: &InstallTarget) -> Vec<&'static str> {
        target.asset_keys.clone()
    }

    #[test]
    fn windows_maps_marker_and_hardware_arch_to_assets() {
        let cases = [
            (false, Arch::X64, UpdateInstallKind::WindowsSetup, "setup"),
            (
                false,
                Arch::Arm64,
                UpdateInstallKind::WindowsSetup,
                "setup_arm64",
            ),
            (
                true,
                Arch::X64,
                UpdateInstallKind::WindowsPortable,
                "portable",
            ),
            (
                true,
                Arch::Arm64,
                UpdateInstallKind::WindowsPortable,
                "portable_arm64",
            ),
        ];
        for (portable, arch, kind, key) in cases {
            let mut f = facts(HostOs::Windows, arch);
            f.portable_marker = portable;
            let target = classify(&f);
            assert_eq!(target.kind, kind);
            assert_eq!(keys(&target), vec![key]);
            assert_eq!(target.component, ReleaseComponent::Desktop);
            assert_eq!(target.manual_reason, None);
        }
        let target = classify(&facts(HostOs::Windows, Arch::Other));
        assert!(target.asset_keys.is_empty());
        assert_eq!(target.manual_reason, Some(UpdateManualReason::NoAsset));
    }

    #[test]
    fn linux_prefers_appimage_then_package_then_tarball_and_is_x64_only() {
        let mut f = facts(HostOs::Linux, Arch::X64);
        assert_eq!(classify(&f).kind, UpdateInstallKind::LinuxPortable);
        assert_eq!(keys(&classify(&f)), vec!["linux_tarball"]);
        f.package_owner = Some(PackageOwner::Dpkg);
        assert_eq!(keys(&classify(&f)), vec!["linux_deb"]);
        f.package_owner = Some(PackageOwner::Pacman);
        assert_eq!(classify(&f).kind, UpdateInstallKind::LinuxArch);
        assert_eq!(keys(&classify(&f)), vec!["linux_arch"]);
        f.appimage = true;
        assert_eq!(classify(&f).kind, UpdateInstallKind::LinuxAppImage);
        assert_eq!(keys(&classify(&f)), vec!["linux_appimage"]);

        let arm = classify(&facts(HostOs::Linux, Arch::Arm64));
        assert_eq!(arm.kind, UpdateInstallKind::LinuxPortable);
        assert!(arm.asset_keys.is_empty());
        assert_eq!(arm.manual_reason, Some(UpdateManualReason::NoAsset));
    }

    #[test]
    fn macos_prefers_dmg_then_tarball_for_the_hardware_arch() {
        let arm = classify(&facts(HostOs::Macos, Arch::Arm64));
        assert_eq!(arm.kind, UpdateInstallKind::MacosApp);
        assert_eq!(keys(&arm), vec!["macos_dmg_arm64", "macos_tarball_arm64"]);
        let intel = classify(&facts(HostOs::Macos, Arch::X64));
        assert_eq!(keys(&intel), vec!["macos_dmg_x64", "macos_tarball_x64"]);

        let mut f = facts(HostOs::Macos, Arch::Arm64);
        f.read_only_location = true;
        assert_eq!(
            classify(&f).manual_reason,
            Some(UpdateManualReason::ReadOnlyLocation)
        );
    }

    #[test]
    fn server_maps_install_source_and_platform() {
        for (source, kind) in [
            ("docker", UpdateInstallKind::Docker),
            (" Synology ", UpdateInstallKind::Synology),
            ("QNAP", UpdateInstallKind::Qnap),
            ("openwrt", UpdateInstallKind::Openwrt),
        ] {
            let mut f = facts(HostOs::Linux, Arch::X64);
            f.server_mode = true;
            f.install_source = Some(source.to_owned());
            let target = classify(&f);
            assert_eq!(target.kind, kind);
            assert_eq!(target.component, ReleaseComponent::Server);
            assert!(target.asset_keys.is_empty());
            assert_eq!(
                target.manual_reason,
                Some(UpdateManualReason::ManagedPackage)
            );
        }

        let mut dockerenv = facts(HostOs::Linux, Arch::X64);
        dockerenv.server_mode = true;
        dockerenv.in_docker = true;
        assert_eq!(classify(&dockerenv).kind, UpdateInstallKind::Docker);

        // 未识别的来源按普通二进制处理。
        let mut unknown_source = facts(HostOs::Linux, Arch::Arm64);
        unknown_source.server_mode = true;
        unknown_source.install_source = Some("tarball".to_owned());
        let target = classify(&unknown_source);
        assert_eq!(target.kind, UpdateInstallKind::ServerBinary);
        assert_eq!(keys(&target), vec!["linux_arm64"]);
        assert_eq!(target.manual_reason, None);

        for (os, arch, key) in [
            (HostOs::Windows, Arch::X64, "windows_x64"),
            (HostOs::Windows, Arch::Arm64, "windows_arm64"),
            (HostOs::Linux, Arch::X64, "linux_x64"),
            (HostOs::Macos, Arch::X64, "macos_x64"),
            (HostOs::Macos, Arch::Arm64, "macos_arm64"),
        ] {
            let mut f = facts(os, arch);
            f.server_mode = true;
            assert_eq!(keys(&classify(&f)), vec![key]);
        }
    }

    #[test]
    fn unofficial_builds_and_unknown_platforms_are_never_self_updated() {
        let mut f = facts(HostOs::Windows, Arch::X64);
        f.official_build = false;
        assert_eq!(
            classify(&f).manual_reason,
            Some(UpdateManualReason::UnofficialBuild)
        );

        let other = classify(&facts(HostOs::Other, Arch::X64));
        assert_eq!(other.kind, UpdateInstallKind::Unknown);
        assert_eq!(other.manual_reason, Some(UpdateManualReason::Unsupported));

        let mut no_exe = facts(HostOs::Linux, Arch::X64);
        no_exe.exe = None;
        assert_eq!(
            classify(&no_exe).manual_reason,
            Some(UpdateManualReason::Unsupported)
        );
    }

    #[test]
    fn macos_bundle_helpers_pick_the_outer_bundle_and_flag_read_only_volumes() {
        let exe = Path::new(
            "/Applications/FluxDown.app/Contents/Helpers/FluxDownAgent.app/Contents/MacOS/fluxdown-agent",
        );
        assert_eq!(
            outer_app_bundle(exe),
            Some(PathBuf::from("/Applications/FluxDown.app"))
        );
        assert_eq!(
            outer_app_bundle(Path::new("/usr/local/bin/fluxdown-agent")),
            None
        );
        assert!(!is_read_only_location(Path::new(
            "/Applications/FluxDown.app"
        )));
        assert!(is_read_only_location(Path::new(
            "/Volumes/FluxDown/FluxDown.app"
        )));
        assert!(is_read_only_location(Path::new(
            "/private/var/folders/xx/T/AppTranslocation/ABC/d/FluxDown.app"
        )));
    }
}
