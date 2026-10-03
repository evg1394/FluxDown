//! 侧栏设备区与「下载到」目标共用的设备投影：过滤本机、去重、同名消歧。
//!
//! 「其他设备」= 云账号设备（去掉本机）+ 局域网已配对设备。选择 id 与远程任务的
//! `to_device` 同一空间：云设备用 `CloudDevice::device_id`，已配对设备用指纹。

use std::collections::{HashMap, HashSet};

use fluxdown_protocol::{CloudDevice, LinkDeviceInfo, PathStyle};

/// 其他设备的来源。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeviceKind {
    /// 同账号的云设备（经 FluxCloud 中转）。
    Cloud,
    /// 局域网直连配对设备。
    Paired,
}

/// 「下载到」目标：本机、云设备或已配对设备。
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub enum DispatchTarget {
    #[default]
    Local,
    /// `CloudDevice::device_id`。
    Cloud(String),
    /// 已配对设备指纹。
    Paired(String),
}

impl DispatchTarget {
    /// 设备本地偏好里的持久化编码：`local` / `cloud:<id>` / `link:<fingerprint>`。
    #[must_use]
    pub fn to_pref(&self) -> String {
        match self {
            Self::Local => "local".to_owned(),
            Self::Cloud(id) => format!("cloud:{id}"),
            Self::Paired(fingerprint) => format!("link:{fingerprint}"),
        }
    }

    /// [`Self::to_pref`] 的逆运算；空 id 或未知前缀视为无记录。
    #[must_use]
    pub fn from_pref(value: &str) -> Option<Self> {
        match value.split_once(':') {
            None if value == "local" => Some(Self::Local),
            Some(("cloud", id)) if !id.is_empty() => Some(Self::Cloud(id.to_owned())),
            Some(("link", id)) if !id.is_empty() => Some(Self::Paired(id.to_owned())),
            _ => None,
        }
    }
}

/// 一台其他设备（已去重、名称已消歧）。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeviceEntry {
    /// 云设备 `device_id` / 已配对设备指纹（同时是侧栏选择 id 与 `to_device`）。
    pub id: String,
    pub kind: DeviceKind,
    /// 显示名；与其他设备同名时追加短码。
    pub label: String,
    pub online: Option<bool>,
    /// 目标自报的默认下载目录（远程下发不填目录时使用）。
    pub default_save_dir: Option<String>,
    /// 目标的路径风格；未上报时已按平台推断，仍未知则为 `None`。
    pub path_style: Option<PathStyle>,
}

impl DeviceEntry {
    #[must_use]
    pub fn target(&self) -> DispatchTarget {
        match self.kind {
            DeviceKind::Cloud => DispatchTarget::Cloud(self.id.clone()),
            DeviceKind::Paired => DispatchTarget::Paired(self.id.clone()),
        }
    }
}

/// 设备 id 的短码（末 4 位字母数字），用于同名设备消歧。
fn short_code(id: &str) -> String {
    let mut tail: Vec<char> = id
        .chars()
        .rev()
        .filter(char::is_ascii_alphanumeric)
        .take(4)
        .collect();
    tail.reverse();
    tail.into_iter().collect()
}

fn paired_style(device: &LinkDeviceInfo) -> Option<PathStyle> {
    device
        .path_style
        .filter(|style| *style != PathStyle::Unknown)
        .or_else(|| {
            device
                .platform
                .as_deref()
                .and_then(PathStyle::from_platform)
        })
}

/// 云设备 + 已配对设备 → 「其他设备」列表。
///
/// - 云设备去掉 `is_current`（名册包含请求设备自身）；
/// - 按 id 去重（同一 id 保留首条）；
/// - 名称为空用短码代替；多台同名（忽略大小写与首尾空白）时全部追加 ` · 短码`。
#[must_use]
pub(crate) fn other_devices(
    cloud: &[CloudDevice],
    linked: &[LinkDeviceInfo],
    cloud_current: bool,
    service_current: bool,
) -> Vec<DeviceEntry> {
    let mut seen = HashSet::new();
    let mut entries = Vec::with_capacity(cloud.len() + linked.len());
    for device in cloud
        .iter()
        .filter(|device| !device.is_current && !device.device_id.is_empty())
    {
        if !seen.insert(("cloud", device.device_id.as_str())) {
            continue;
        }
        entries.push(DeviceEntry {
            id: device.device_id.clone(),
            kind: DeviceKind::Cloud,
            label: device.name.trim().to_owned(),
            online: (service_current && cloud_current).then_some(device.is_online),
            default_save_dir: device
                .default_save_dir
                .as_deref()
                .map(str::trim)
                .filter(|dir| !dir.is_empty())
                .map(str::to_owned),
            path_style: device.effective_path_style(),
        });
    }
    for device in linked
        .iter()
        .filter(|device| !device.fingerprint.is_empty())
    {
        if !seen.insert(("link", device.fingerprint.as_str())) {
            continue;
        }
        entries.push(DeviceEntry {
            id: device.fingerprint.clone(),
            kind: DeviceKind::Paired,
            label: device.name.trim().to_owned(),
            online: service_current.then_some(device.online),
            default_save_dir: device
                .default_save_dir
                .as_deref()
                .map(str::trim)
                .filter(|dir| !dir.is_empty())
                .map(str::to_owned),
            path_style: paired_style(device),
        });
    }
    disambiguate(&mut entries);
    entries
}

fn disambiguate(entries: &mut [DeviceEntry]) {
    let mut counts: HashMap<String, usize> = HashMap::new();
    for entry in entries.iter() {
        *counts.entry(entry.label.to_lowercase()).or_default() += 1;
    }
    for entry in entries.iter_mut() {
        let duplicated = counts
            .get(&entry.label.to_lowercase())
            .is_some_and(|n| *n > 1);
        if entry.label.is_empty() {
            entry.label = short_code(&entry.id);
        } else if duplicated {
            entry.label = format!("{} · {}", entry.label, short_code(&entry.id));
        }
    }
}

/// 本机的云设备 id：会话里的本机设备优先，否则名册中的 `is_current`。
#[must_use]
pub(crate) fn local_device_id(
    session_device: Option<&str>,
    cloud: &[CloudDevice],
) -> Option<String> {
    session_device
        .filter(|id| !id.is_empty())
        .map(str::to_owned)
        .or_else(|| {
            cloud
                .iter()
                .find(|device| device.is_current && !device.device_id.is_empty())
                .map(|device| device.device_id.clone())
        })
}

/// 侧栏设备区可见性：偏好显式设置则按值；未设置时只要存在「其他设备」就显示（账号里只有
/// 本机一台、没有已配对设备时不显示，和只看名册人数不同）。
#[must_use]
pub(crate) fn devices_section_visible(pref: Option<bool>, other_devices: usize) -> bool {
    pref.unwrap_or(other_devices > 0)
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn devices_section_defaults_to_visible_only_with_other_devices() {
        assert!(!devices_section_visible(None, 0));
        assert!(devices_section_visible(None, 1));
        // 显式偏好优先于自动判定。
        assert!(!devices_section_visible(Some(false), 3));
        assert!(devices_section_visible(Some(true), 0));
    }

    fn cloud(value: serde_json::Value) -> CloudDevice {
        serde_json::from_value(value).expect("cloud device")
    }

    fn linked(value: serde_json::Value) -> LinkDeviceInfo {
        serde_json::from_value(value).expect("link device")
    }

    #[test]
    fn cloud_disconnect_does_not_hide_targets_or_invalidate_lan_presence() {
        let cloud_devices = [cloud(json!({"id":"1","deviceId":"cloud","isOnline":true}))];
        let paired = [linked(
            json!({"fingerprint":"lan","name":"LAN","online":true,"pairedAt":0,"lastSeenAt":0}),
        )];
        let entries = other_devices(&cloud_devices, &paired, false, true);
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].online, None);
        assert_eq!(
            entries[0].target(),
            DispatchTarget::Cloud("cloud".to_owned())
        );
        assert_eq!(entries[1].online, Some(true));
        let stale = other_devices(&cloud_devices, &paired, false, false);
        assert!(stale.iter().all(|entry| entry.online.is_none()));
        assert_eq!(
            other_devices(&cloud_devices, &paired, true, true)[0].online,
            Some(true)
        );
    }

    #[test]
    fn other_devices_drop_current_and_duplicate_ids() {
        let devices = [
            cloud(json!({"id":"1","deviceId":"dev-me","name":"Me","isCurrent":true})),
            cloud(
                json!({"id":"2","deviceId":"dev-a","name":"Mac","isOnline":true,"platform":"macos"}),
            ),
            cloud(json!({"id":"3","deviceId":"dev-a","name":"Mac dup"})),
            cloud(json!({"id":"4","deviceId":"","name":"No id"})),
        ];
        let entries = other_devices(&devices, &[], true, true);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].id, "dev-a");
        assert_eq!(entries[0].label, "Mac");
        assert_eq!(entries[0].online, Some(true));
        assert_eq!(entries[0].path_style, Some(PathStyle::Posix));
    }

    #[test]
    fn same_named_devices_get_short_codes_and_unnamed_fall_back_to_code() {
        let devices = [
            cloud(json!({"id":"1","deviceId":"aaaa-1111","name":"PC"})),
            cloud(json!({"id":"2","deviceId":"bbbb-2222","name":" pc "})),
            cloud(json!({"id":"3","deviceId":"cccc-3333","name":"Laptop"})),
            cloud(json!({"id":"4","deviceId":"dddd-4444","name":""})),
        ];
        let entries = other_devices(&devices, &[], true, true);
        let labels: Vec<_> = entries.iter().map(|entry| entry.label.as_str()).collect();
        assert_eq!(labels, ["PC · 1111", "pc · 2222", "Laptop", "4444"]);
    }

    #[test]
    fn paired_devices_keep_fingerprint_ids_and_dedupe_across_kinds_independently() {
        let cloud_devices = [cloud(json!({"id":"1","deviceId":"same","name":"Box"}))];
        let paired = [
            linked(
                json!({"fingerprint":"same","name":"Box","online":true,"pairedAt":0,"lastSeenAt":0,
                "pathStyle":"windows"}),
            ),
            linked(
                json!({"fingerprint":"same","name":"Box again","online":false,"pairedAt":0,"lastSeenAt":0}),
            ),
        ];
        let entries = other_devices(&cloud_devices, &paired, true, true);
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].kind, DeviceKind::Cloud);
        assert_eq!(entries[1].kind, DeviceKind::Paired);
        assert_eq!(entries[1].path_style, Some(PathStyle::Windows));
        // 云设备与已配对设备同名 → 各自追加短码消歧。
        assert_eq!(entries[0].label, "Box · same");
        assert!(entries[1].label.starts_with("Box · "));
    }

    #[test]
    fn local_device_id_prefers_session_then_current_flag() {
        let devices = [
            cloud(json!({"id":"1","deviceId":"dev-a"})),
            cloud(json!({"id":"2","deviceId":"dev-me","isCurrent":true})),
        ];
        assert_eq!(
            local_device_id(Some("session-dev"), &devices).as_deref(),
            Some("session-dev")
        );
        assert_eq!(local_device_id(None, &devices).as_deref(), Some("dev-me"));
        assert_eq!(local_device_id(Some(""), &[]), None);
    }

    #[test]
    fn dispatch_target_pref_round_trips_and_rejects_garbage() {
        for target in [
            DispatchTarget::Local,
            DispatchTarget::Cloud("a:b".to_owned()),
            DispatchTarget::Paired("f0f0".to_owned()),
        ] {
            assert_eq!(DispatchTarget::from_pref(&target.to_pref()), Some(target));
        }
        assert_eq!(DispatchTarget::from_pref("cloud:"), None);
        assert_eq!(DispatchTarget::from_pref("other:1"), None);
        assert_eq!(DispatchTarget::from_pref(""), None);
    }
}
