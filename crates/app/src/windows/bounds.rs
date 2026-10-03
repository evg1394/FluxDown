//! 窗口边界记忆。
//!
//! - 主窗口 / 设置窗口 / 进度窗口记「位置 + 尺寸 + 最大化 + 所在显示器」；其余可调尺寸的辅助窗口只记
//!   尺寸，位置仍按各自规则居中（新建下载 / BT 选择跟随主窗口所在显示器）。同类窗口共用一条
//!   记录：所有任务详情窗口共享一份尺寸。
//! - gpui 的窗口坐标以「打开时 `display_id` 指定的显示器（缺省主显示器）」为基准解释：macOS
//!   是相对该显示器的局部坐标，Windows / X11 是全局坐标。位置因此必须连同显示器 UUID（跨重启
//!   稳定；`DisplayId` 不稳定）一起记录，恢复时传回 `display_id`，并只对那台显示器校验可见性。
//! - 只在边界真实变化时写入：gpui 每次窗口激活 / 失焦都会触发边界观察。
//! - 写设备本地偏好（`sync:false`），同键 500ms 防抖；退出时强制落盘在途值。

use std::{collections::HashMap, rc::Rc, sync::Arc, time::Duration};

use gpui::{
    App, Bounds, Context, DisplayId, Entity, Pixels, PlatformDisplay, Size, Window, WindowBounds,
    WindowOptions, point, px, size,
};
use serde_json::{Value, json};

use super::{WindowRegistry, patch_local_preference};
use crate::{agent_client::AgentClient, app::Desktop};

const PERSIST_DEBOUNCE: Duration = Duration::from_millis(500);
/// 记录的窗口边长、以及恢复时在显示器上可见部分的边长下限（逻辑像素）。
const MIN_EDGE: f32 = 100.;
const MAX_EDGE: f32 = 20_000.;

/// 需要记忆边界的窗口类别。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RememberedWindow {
    Main,
    Settings,
    Progress,
    NewDownload,
    QueueManager,
    TaskDetail,
    GroupDetail,
    BtSelection,
}

impl RememberedWindow {
    const fn pref_key(self) -> &'static str {
        match self {
            Self::Main => "desktop.window.main",
            Self::Settings => "desktop.window.settings",
            Self::Progress => "desktop.window.progress",
            Self::NewDownload => "desktop.window.new_download",
            Self::QueueManager => "desktop.window.queue_manager",
            Self::TaskDetail => "desktop.window.task_detail",
            Self::GroupDetail => "desktop.window.group_detail",
            Self::BtSelection => "desktop.window.bt_selection",
        }
    }

    const fn remembers_position(self) -> bool {
        matches!(self, Self::Main | Self::Settings | Self::Progress)
    }
}

/// 注册表持有的边界状态（按偏好键）。
#[derive(Default)]
pub(super) struct BoundsMemory {
    /// 本进程观察到的最新边界：重开窗口优先于偏好快照，防抖或回执在途时也不回退旧值。
    latest: HashMap<&'static str, Value>,
    /// 尚未落盘的边界（退出时强制写）。
    pending: HashMap<&'static str, Value>,
}

impl BoundsMemory {
    pub(super) fn drain_pending(&mut self) -> Vec<(&'static str, Value)> {
        self.pending.drain().collect()
    }
}

impl WindowRegistry {
    /// 按记忆填写 `options.window_bounds`；无记录或记录失效时以 `default_size` 居中。
    ///
    /// 只记尺寸的窗口在 `options.display_id`（缺省主显示器）上居中；记位置的窗口把
    /// `options.display_id` 改写为记录所在的显示器。尺寸不超出目标显示器可用区域、不小于
    /// `options.window_min_size`。
    pub fn restore_bounds(
        which: RememberedWindow,
        options: &mut WindowOptions,
        default_size: Size<Pixels>,
        cx: &App,
    ) {
        let key = which.pref_key();
        let stored = cx
            .global::<Self>()
            .bounds
            .borrow()
            .latest
            .get(key)
            .cloned()
            .or_else(|| Desktop::pref(cx, key));
        let min_size = options.window_min_size;
        let bounds = if which.remembers_position() {
            match stored.as_ref().and_then(parse_frame) {
                Some(frame) => restore_frame(frame, options, cx),
                None => WindowBounds::Windowed(centered(None, default_size, min_size, cx)),
            }
        } else {
            let wanted = stored.as_ref().and_then(parse_size).unwrap_or(default_size);
            WindowBounds::Windowed(centered(options.display_id, wanted, min_size, cx))
        };
        options.window_bounds = Some(bounds);
    }

    /// 在根视图上挂窗口边界观察：边界真实变化后防抖写入设备本地偏好。
    pub fn persist_bounds<V: 'static>(
        which: RememberedWindow,
        client: Arc<AgentClient>,
        root: &Entity<V>,
        window: &mut Window,
        cx: &mut App,
    ) {
        let key = which.pref_key();
        let memory = Rc::clone(&cx.global::<Self>().bounds);
        let mut last = capture(which, window, cx);
        root.update(cx, |_, cx: &mut Context<V>| {
            cx.observe_window_bounds(window, move |_, window, cx| {
                let value = capture(which, window, cx);
                if value == last {
                    return;
                }
                last = value.clone();
                {
                    let mut memory = memory.borrow_mut();
                    memory.latest.insert(key, value.clone());
                    memory.pending.insert(key, value.clone());
                }
                let memory = Rc::clone(&memory);
                let client = Arc::clone(&client);
                cx.spawn(async move |_, cx| {
                    cx.background_executor().timer(PERSIST_DEBOUNCE).await;
                    // 期间同键出现了更新的值：交给那次变化自己的定时器写。
                    let pending = {
                        let mut memory = memory.borrow_mut();
                        if memory.pending.get(key) != Some(&value) {
                            return;
                        }
                        let Some(pending) = memory.pending.remove(key) else {
                            return;
                        };
                        pending
                    };
                    if let Err(error) = patch_local_preference(&client, key, pending).await {
                        log::warn!("failed to persist window bounds {key}: {:?}", error.code);
                        let mut memory = memory.borrow_mut();
                        if memory.latest.get(key) == Some(&value) {
                            // 保存失败的当前值仍交给既有退出落盘流程，不能覆盖更新的边界。
                            memory.pending.entry(key).or_insert(value);
                        }
                    }
                })
                .detach();
            })
            .detach();
        });
    }
}

/// 记录的窗口位置。
#[derive(Debug, PartialEq)]
struct StoredFrame {
    bounds: Bounds<Pixels>,
    maximized: bool,
    /// 所在显示器 UUID；旧记录没有，按主显示器解释。
    display: Option<String>,
}

fn restore_frame(frame: StoredFrame, options: &mut WindowOptions, cx: &App) -> WindowBounds {
    let min_size = options.window_min_size;
    let display = match frame.display.as_deref() {
        Some(uuid) => cx
            .displays()
            .into_iter()
            .find(|display| display_uuid(display.as_ref()).as_deref() == Some(uuid)),
        None => cx.primary_display(),
    };
    let bounds = match display {
        Some(display) => {
            options.display_id = Some(display.id());
            if frame_visible(&frame.bounds, &display.bounds()) {
                frame.bounds
            } else {
                // 分辨率 / 排布变化后原位置不可见：同一台显示器上保留尺寸居中。
                centered(Some(display.id()), frame.bounds.size, min_size, cx)
            }
        }
        // 记录的显示器已断开：回主显示器，保留尺寸居中。
        None => centered(None, frame.bounds.size, min_size, cx),
    };
    if frame.maximized {
        WindowBounds::Maximized(bounds)
    } else {
        WindowBounds::Windowed(bounds)
    }
}

/// 在显示器上居中，尺寸限制在其可用区域内且不小于最小尺寸。
fn centered(
    display_id: Option<DisplayId>,
    wanted: Size<Pixels>,
    min_size: Option<Size<Pixels>>,
    cx: &App,
) -> Bounds<Pixels> {
    let area = display_id
        .and_then(|id| cx.find_display(id))
        .or_else(|| cx.primary_display())
        .map(|display| display.visible_bounds().size);
    let size = area.map_or(wanted, |area| fit_size(wanted, area, min_size));
    Bounds::centered(display_id, size, cx)
}

fn fit_size(
    wanted: Size<Pixels>,
    area: Size<Pixels>,
    min_size: Option<Size<Pixels>>,
) -> Size<Pixels> {
    let fitted = wanted.min(&area);
    min_size.map_or(fitted, |min| fitted.max(&min))
}

/// 恢复位置的条件：标题栏上沿不高于显示器顶边，且与显示器相交的可见区域 ≥ 100×100。
/// `display` 与窗口边界处于同一坐标系（gpui 按平台约定，见模块文档）。
fn frame_visible(bounds: &Bounds<Pixels>, display: &Bounds<Pixels>) -> bool {
    if bounds.origin.y < display.origin.y || !display.intersects(bounds) {
        return false;
    }
    let visible = display.intersect(bounds);
    visible.size.width >= px(MIN_EDGE) && visible.size.height >= px(MIN_EDGE)
}

fn display_uuid(display: &dyn PlatformDisplay) -> Option<String> {
    display.uuid().ok().map(|uuid| uuid.to_string())
}

/// Windows 上最小化时 `window_bounds()` 按还原矩形报告 `Windowed`，
/// 而平台的最大化标志在最小化后仍保持，两者任一为真都应记为最大化。
fn resolve_maximized(bounds_maximized: bool, platform_maximized: bool) -> bool {
    bounds_maximized || platform_maximized
}

/// 当前窗口边界的记录值：记位置的窗口含最大化状态（全屏按最大化记）与所在显示器，
/// 只记尺寸的窗口取还原尺寸。
fn capture(which: RememberedWindow, window: &Window, cx: &App) -> Value {
    let (rect, bounds_maximized) = match window.window_bounds() {
        WindowBounds::Windowed(rect) => (rect, false),
        WindowBounds::Maximized(rect) | WindowBounds::Fullscreen(rect) => (rect, true),
    };
    let maximized = resolve_maximized(bounds_maximized, window.is_maximized());
    if !which.remembers_position() {
        return size_value(rect.size);
    }
    let display = window
        .display(cx)
        .and_then(|display| display_uuid(display.as_ref()));
    frame_value(rect, maximized, display.as_deref())
}

fn size_value(size: Size<Pixels>) -> Value {
    json!({ "w": f64::from(size.width), "h": f64::from(size.height) })
}

fn frame_value(rect: Bounds<Pixels>, maximized: bool, display: Option<&str>) -> Value {
    let mut value = json!({
        "x": f64::from(rect.origin.x),
        "y": f64::from(rect.origin.y),
        "w": f64::from(rect.size.width),
        "h": f64::from(rect.size.height),
        "maximized": maximized,
    });
    if let (Some(display), Some(object)) = (display, value.as_object_mut()) {
        object.insert("display".to_owned(), Value::from(display));
    }
    value
}

fn number(value: &Value, key: &str) -> Option<f32> {
    value
        .get(key)
        .and_then(Value::as_f64)
        .filter(|number| number.is_finite())
        .map(|number| number as f32)
}

fn parse_size(value: &Value) -> Option<Size<Pixels>> {
    let (w, h) = (number(value, "w")?, number(value, "h")?);
    let edge = MIN_EDGE..MAX_EDGE;
    (edge.contains(&w) && edge.contains(&h)).then(|| size(px(w), px(h)))
}

fn parse_frame(value: &Value) -> Option<StoredFrame> {
    let size = parse_size(value)?;
    let (x, y) = (number(value, "x")?, number(value, "y")?);
    let coordinate = -MAX_EDGE..MAX_EDGE;
    if !coordinate.contains(&x) || !coordinate.contains(&y) {
        return None;
    }
    Some(StoredFrame {
        bounds: Bounds {
            origin: point(px(x), px(y)),
            size,
        },
        maximized: value
            .get("maximized")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        display: value
            .get("display")
            .and_then(Value::as_str)
            .map(str::to_owned),
    })
}

#[cfg(test)]
mod tests {
    use gpui::{Bounds, point, px, size};
    use serde_json::json;

    use super::{
        StoredFrame, fit_size, frame_value, frame_visible, parse_frame, parse_size,
        resolve_maximized,
    };

    #[test]
    fn minimized_maximized_window_keeps_maximized() {
        // Windows 上最小化的窗口边界报告为 Windowed，但窗口仍带最大化样式。
        assert!(resolve_maximized(false, true));
        assert!(resolve_maximized(true, false));
        assert!(!resolve_maximized(false, false));
    }

    fn rect(x: f32, y: f32, w: f32, h: f32) -> Bounds<gpui::Pixels> {
        Bounds {
            origin: point(px(x), px(y)),
            size: size(px(w), px(h)),
        }
    }

    #[test]
    fn frame_must_keep_title_bar_and_enough_area_on_its_display() {
        let primary = rect(0., 0., 1920., 1080.);
        assert!(frame_visible(&rect(100., 100., 800., 600.), &primary));
        assert!(!frame_visible(&rect(1900., 1000., 800., 600.), &primary));
        assert!(!frame_visible(&rect(3000., 100., 800., 600.), &primary));
        assert!(!frame_visible(&rect(100., -40., 800., 600.), &primary));
        // Windows 全局坐标：主显示器左侧的副屏是负坐标，仍须可恢复。
        let left = rect(-2560., 0., 2560., 1440.);
        assert!(frame_visible(&rect(-2400., 200., 1200., 800.), &left));
        assert!(!frame_visible(&rect(-2400., 200., 1200., 800.), &primary));
    }

    #[test]
    fn frame_round_trips_with_display_and_maximized_flag() {
        let value = frame_value(rect(-1800., 20., 900., 600.), true, Some("display-a"));
        assert_eq!(
            parse_frame(&value),
            Some(StoredFrame {
                bounds: rect(-1800., 20., 900., 600.),
                maximized: true,
                display: Some("display-a".to_owned()),
            })
        );
        // 旧记录没有显示器字段。
        let legacy = json!({"x": 10.0, "y": 20.0, "w": 800.0, "h": 600.0});
        assert_eq!(
            parse_frame(&legacy),
            Some(StoredFrame {
                bounds: rect(10., 20., 800., 600.),
                maximized: false,
                display: None,
            })
        );
    }

    #[test]
    fn malformed_or_degenerate_records_are_ignored() {
        assert!(parse_frame(&json!({"x": 1})).is_none());
        assert!(parse_frame(&json!({"x": 0.0, "y": 0.0, "w": 50.0, "h": 600.0})).is_none());
        assert!(parse_frame(&json!({"x": 1e9, "y": 0.0, "w": 800.0, "h": 600.0})).is_none());
        assert_eq!(
            parse_size(&json!({"w": 700.0, "h": 500.0})),
            Some(size(px(700.), px(500.)))
        );
        assert!(parse_size(&json!({"w": 700.0})).is_none());
    }

    #[test]
    fn restored_size_fits_display_but_respects_min_size() {
        let area = size(px(1440.), px(900.));
        let min = Some(size(px(640.), px(480.)));
        assert_eq!(
            fit_size(size(px(2000.), px(700.)), area, min),
            size(px(1440.), px(700.))
        );
        assert_eq!(
            fit_size(size(px(300.), px(200.)), area, min),
            size(px(640.), px(480.))
        );
        assert_eq!(
            fit_size(size(px(800.), px(600.)), area, None),
            size(px(800.), px(600.))
        );
    }
}
