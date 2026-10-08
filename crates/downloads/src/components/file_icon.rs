//! 任务文件图标：按偏好 `appearance.file_icon_pack` 选定的图标包绘制（任务表选择列、进度窗口、
//! 拖拽预览共用）。
//!
//! 所选链含系统图标环节（`builtin:system`，桌面默认）时，经 `agent.platform.fileIcon` 取系统文件
//! 管理器（资源管理器 / Finder / GTK 图标主题）的图标：
//! - 走 GPUI 资产缓存（[`Window::use_asset`]）去重：同一「扩展名 + 物理像素」只取一次；图标
//!   内嵌在文件里的类型（[`FILE_ICON_PER_FILE_EXTENSIONS`]）在本机产物存在时按文件取，键里带
//!   完成时间，同名重新下载后换新图标。
//! - 请求中留空，避免先闪一下回退图标；取不到（agent 不支持 / 失败）的结果同样缓存，本会话
//!   内不再重试，一直显示链上下一个图标包的图标。

use std::{future::Future, sync::Arc};

use fluxdown_protocol::{FILE_ICON_PER_FILE_EXTENSIONS, PlatformFileIconParams};
use fluxdown_ui_icon_pack::{FileIconChoice, FileKind, active_icon_packs, pack_icon};
use fluxdown_ui_theme::active_theme;
use gpui::{
    AnyElement, App, Asset, Global, Image, ImageFormat, IntoElement, ParentElement as _, Pixels,
    SharedString, Styled as _, Window, div, img, prelude::FluentBuilder as _,
};

use crate::{
    controller::{DownloadsCommand, DownloadsPort, DownloadsResult},
    model::DownloadTaskView,
};

/// 已完成但文件已不在下载目录的行：图标与次要色文件名一起退淡。
const MISSING_FILE_OPACITY: f32 = 0.5;

/// 取图标用的端口（下载页创建时注入，所有下载窗口共用同一个 agent 连接）。
struct FileIconPort(Arc<dyn DownloadsPort>);

impl Global for FileIconPort {}

/// 注入取图标用的端口；已注入时不覆盖。
pub(crate) fn install_port(port: &Arc<dyn DownloadsPort>, cx: &mut App) {
    if !cx.has_global::<FileIconPort>() {
        cx.set_global(FileIconPort(Arc::clone(port)));
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
struct SystemIconKey {
    /// 小写、不带点；空 = 无扩展名。
    extension: SharedString,
    /// 按文件取时的本机路径；`None` = 按扩展名取。
    path: Option<SharedString>,
    /// 按文件取时的完成时间：同路径重新下载后换键。
    revision: i64,
    /// 物理像素边长。
    size: u32,
}

impl SystemIconKey {
    fn for_task(task: &DownloadTaskView, size: u32) -> Self {
        let extension = task.file_extension.clone();
        let path = (task.has_local_file()
            && FILE_ICON_PER_FILE_EXTENSIONS.contains(&extension.as_ref()))
        .then(|| task.local_file_path())
        .flatten()
        .map(|path| SharedString::from(path.to_string_lossy().into_owned()));
        let revision = if path.is_some() {
            task.completed_at_secs
        } else {
            0
        };
        Self {
            extension,
            path,
            revision,
            size,
        }
    }
}

enum SystemIconAsset {}

impl Asset for SystemIconAsset {
    type Source = SystemIconKey;
    type Output = Option<Arc<Image>>;

    fn load(
        source: Self::Source,
        cx: &mut App,
    ) -> impl Future<Output = Self::Output> + Send + 'static {
        let port = cx
            .try_global::<FileIconPort>()
            .map(|port| Arc::clone(&port.0));
        async move {
            let params = PlatformFileIconParams {
                extension: source.extension.to_string(),
                path: source.path.map(|path| path.to_string()),
                size: source.size,
            };
            match port?.execute(DownloadsCommand::FileIcon(params)).await {
                Ok(DownloadsResult::FileIcon(png)) if !png.is_empty() => {
                    Some(Arc::new(Image::from_bytes(ImageFormat::Png, png)))
                }
                _ => None,
            }
        }
    }
}

/// 任务的文件图标，`size` 为逻辑边长（系统图标与彩色图标按窗口缩放换算物理像素，高分屏不糊）。
pub(crate) fn task_file_icon(
    task: &DownloadTaskView,
    size: Pixels,
    window: &mut Window,
    cx: &mut App,
) -> AnyElement {
    resolved_file_icon(
        &task.name_fold,
        task.kind,
        |physical| SystemIconKey::for_task(task, physical),
        task.is_file_missing(),
        size,
        window,
        cx,
    )
}

/// 只有文件名、没有任务行时的图标（「文件已存在」窗口：目标文件尚无本机任务产物）：
/// 与任务表同一图标链，系统图标按扩展名取。
pub(crate) fn name_file_icon(
    lower_name: &str,
    kind: FileKind,
    extension: &SharedString,
    size: Pixels,
    window: &mut Window,
    cx: &mut App,
) -> AnyElement {
    resolved_file_icon(
        lower_name,
        kind,
        |physical| SystemIconKey {
            extension: extension.clone(),
            path: None,
            revision: 0,
            size: physical,
        },
        false,
        size,
        window,
        cx,
    )
}

fn resolved_file_icon(
    lower_name: &str,
    kind: FileKind,
    system_key: impl FnOnce(u32) -> SystemIconKey,
    dimmed: bool,
    size: Pixels,
    window: &mut Window,
    cx: &mut App,
) -> AnyElement {
    let Some(choice) = active_icon_packs(cx).resolve(lower_name, kind) else {
        return div().flex_none().size(size).into_any_element();
    };
    let fallback = match choice {
        FileIconChoice::Pack(icon) => icon,
        FileIconChoice::System { fallback } => {
            let physical = (f32::from(size) * window.scale_factor()).ceil() as u32;
            let key = system_key(physical);
            match window.use_asset::<SystemIconAsset>(&key, cx) {
                None => return div().flex_none().size(size).into_any_element(),
                Some(Some(image)) => {
                    return img(image)
                        .flex_none()
                        .size(size)
                        .when(dimmed, |this| this.opacity(MISSING_FILE_OPACITY))
                        .into_any_element();
                }
                Some(None) => fallback,
            }
        }
    };
    let theme = active_theme(cx);
    let dark = theme.mode().is_dark();
    let color = theme.tokens().colors.muted_foreground;
    div()
        .flex_none()
        .when(dimmed, |this| this.opacity(MISSING_FILE_OPACITY))
        .child(pack_icon(&fallback, dark, size, color, window, cx))
        .into_any_element()
}

/// 只按文件名取图标包图标（拖拽预览等不取系统图标的场合）：系统环节直接用链上的下一个包。
pub(crate) fn named_file_icon(
    lower_name: &str,
    kind: FileKind,
    size: Pixels,
    window: &mut Window,
    cx: &mut App,
) -> AnyElement {
    let icon = match active_icon_packs(cx).resolve(lower_name, kind) {
        Some(FileIconChoice::Pack(icon) | FileIconChoice::System { fallback: icon }) => icon,
        None => return div().flex_none().size(size).into_any_element(),
    };
    let theme = active_theme(cx);
    let dark = theme.mode().is_dark();
    let color = theme.tokens().colors.muted_foreground;
    pack_icon(&icon, dark, size, color, window, cx)
}
