//! 交互动效原语：按钮状态层（悬停 / 按下 / 选中的底色过渡）、随选中项滑动的高亮底块与
//! 颜色过渡。
//!
//! gpui-fast 的 Retained Mode 下，动画帧只重建请求帧的那个视图。状态层是挂在元素状态里的
//! 小视图，且**不**被外层视图观察（`Window::use_keyed_state` 会让外层视图观察状态实体）：
//! 悬停、按下与动画帧只重建这个小视图，按钮所在的页面继续复用上一帧。滑动底块与颜色过渡
//! 随所在视图重建（选中切换本就要重建它）。时长与缓动取 gpui-component 的 `MotionTokens`，
//! 与其对话框、下拉、开关等内置动效同一节奏；系统「减弱动态效果」时直接落到终态。
//!
//! 刻意不做「内容从底色淡入」：切换瞬间新内容被纯色盖住，看起来是一次白闪，且页面含多种
//! 底色（侧栏 / 内容区）时单色遮罩必然串色；真正的新旧交叉淡入要同时绘制两页并逐帧改
//! 透明度（gpui 透明度按图元相乘，还会层叠透视），代价与观感都不划算。

use std::time::Duration;

use gpui::{
    App, AppContext as _, Bounds, BoxShadow, Context, ElementId, Entity, Hsla,
    InteractiveElement as _, IntoElement, MouseButton, ParentElement as _, Pixels, Render,
    RenderOnce, StatefulInteractiveElement as _, Styled as _, Window, div,
    prelude::FluentBuilder as _, px,
};
use gpui_base::{Easing, Transition, TransitionId, spring, transition};
use gpui_component::ActiveTheme as _;

/// 颜色过渡（`duration_normal` + `easing_move`）：选中 / 激活态切换时文字、图标色随之淡变。
///
/// 状态按 `id` 挂在当前元素路径下；首次调用直接取目标色。须在渲染期调用。
pub fn color_transition(
    id: impl Into<TransitionId>,
    target: Hsla,
    window: &mut Window,
    cx: &mut App,
) -> Hsla {
    let motion = cx.theme().motion_tokens();
    let policy = Transition::new(motion.duration_normal).easing(motion.easing_move.clone());
    transition(id, target, policy, window, cx)
}

/// 随选中项滑动的高亮底块（绝对定位）：外框由调用方按选中项算出（相对定位容器），位置与
/// 尺寸经弹簧（`spring_move`）过渡，再次切换会带着当前速度转向。首次出现直接落位。
///
/// 须放在被高亮的各项**之前**（先画底块、再画内容），各项自身不再画选中底色。
#[derive(IntoElement)]
pub struct SlidingHighlight {
    id: ElementId,
    bounds: Bounds<Pixels>,
    radius: Pixels,
    color: Hsla,
    shadow: Vec<BoxShadow>,
}

impl SlidingHighlight {
    #[must_use]
    pub fn new(id: impl Into<ElementId>, bounds: Bounds<Pixels>, color: Hsla) -> Self {
        Self {
            id: id.into(),
            bounds,
            radius: px(0.),
            color,
            shadow: Vec::new(),
        }
    }

    #[must_use]
    pub fn radius(mut self, radius: Pixels) -> Self {
        self.radius = radius;
        self
    }

    #[must_use]
    pub fn shadow(mut self, shadow: Vec<BoxShadow>) -> Self {
        self.shadow = shadow;
        self
    }
}

impl RenderOnce for SlidingHighlight {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let policy = cx.theme().motion_tokens().spring_move;
        let target = self.bounds;
        let (left, top, width, height) = window.with_id(self.id, |window| {
            (
                spring("left", target.origin.x, policy, window, cx),
                spring("top", target.origin.y, policy, window, cx),
                spring("width", target.size.width, policy, window, cx),
                spring("height", target.size.height, policy, window, cx),
            )
        });
        div()
            .absolute()
            .left(left)
            .top(top)
            .w(width)
            .h(height)
            .rounded(self.radius)
            .bg(self.color)
            .when(!self.shadow.is_empty(), |this| this.shadow(self.shadow))
    }
}

/// 按下态的淡入时长：按下即可见，又不至于硬切。
const PRESS_IN: Duration = Duration::from_millis(50);

/// 取（或首次创建）挂在当前元素路径 `key` 下的子视图，并在 `props` 变化时写入。
///
/// 外层视图只持有句柄、不读取实体，因此子视图自身的通知不会连带重建外层视图；
/// `props` 的上次值与句柄一起存在元素状态里，比较时也不读实体。
fn detached_view<V, P>(
    key: ElementId,
    props: P,
    window: &mut Window,
    cx: &mut App,
    init: impl FnOnce(P, &mut Context<V>) -> V,
    apply: impl FnOnce(&mut V, P),
) -> Entity<V>
where
    V: Render,
    P: Clone + PartialEq + 'static,
{
    window.with_global_id(key, |global_id, window| {
        window.with_element_state(global_id, |state: Option<(Entity<V>, P)>, _| match state {
            Some((view, applied)) => {
                if applied != props {
                    let next = props.clone();
                    view.update(cx, |view, cx| {
                        apply(view, next);
                        cx.notify();
                    });
                }
                (view.clone(), (view, props))
            }
            None => {
                let initial = props.clone();
                let view = cx.new(|cx| init(initial, cx));
                (view.clone(), (view, props))
            }
        })
    })
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct LayerStyle {
    radius: Pixels,
    inset: Pixels,
    hover: Option<Hsla>,
    press: Option<Hsla>,
    selected: Option<Hsla>,
    is_selected: bool,
}

/// 按钮 / 导航项的状态层：作为宿主的**第一个**子元素铺满宿主（绝对定位），在宿主底色之上、
/// 内容之下绘制选中、悬停、按下三层底色，各自按动效令牌淡入淡出。
///
/// 宿主不得再用 `.hover()` / `.active()` / 选中样式改底色（会与状态层叠加）；文字色等其它
/// 状态样式仍由宿主自己设。宿主须带 id：状态层的元素状态挂在宿主的元素路径下。
#[derive(IntoElement)]
pub struct StateLayer {
    style: LayerStyle,
}

impl StateLayer {
    /// 与宿主同圆角的空状态层；按需叠加 [`Self::hover`] / [`Self::press`] / [`Self::selected`]。
    #[must_use]
    pub fn new(radius: Pixels) -> Self {
        Self {
            style: LayerStyle {
                radius,
                inset: px(0.),
                hover: None,
                press: None,
                selected: None,
                is_selected: false,
            },
        }
    }

    /// 悬停底色。
    #[must_use]
    pub fn hover(mut self, color: Hsla) -> Self {
        self.style.hover = Some(color);
        self
    }

    /// 按下底色：叠在悬停层之上，指针移出宿主即随之淡出。
    #[must_use]
    pub fn press(mut self, color: Hsla) -> Self {
        self.style.press = Some(color);
        self
    }

    /// 选中底色：选中切换时新旧两项交叉淡入淡出。
    #[must_use]
    pub fn selected(mut self, color: Hsla, selected: bool) -> Self {
        self.style.selected = Some(color);
        self.style.is_selected = selected;
        self
    }

    /// 相对宿主内边缘（描边以内）的内缩；负值外扩，用于盖住宿主的透明描边。
    #[must_use]
    pub fn inset(mut self, inset: Pixels) -> Self {
        self.style.inset = inset;
        self
    }
}

impl RenderOnce for StateLayer {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        detached_view(
            ElementId::from("flux-state-layer"),
            self.style,
            window,
            cx,
            |style, _| StateLayerView {
                style,
                hovered: false,
                pressed: false,
            },
            |view, style| view.style = style,
        )
    }
}

struct StateLayerView {
    style: LayerStyle,
    hovered: bool,
    pressed: bool,
}

impl StateLayerView {
    fn set_hovered(&mut self, hovered: bool, cx: &mut Context<Self>) {
        if self.hovered != hovered {
            self.hovered = hovered;
            cx.notify();
        }
    }

    fn set_pressed(&mut self, pressed: bool, cx: &mut Context<Self>) {
        if self.pressed != pressed {
            self.pressed = pressed;
            cx.notify();
        }
    }
}

/// 0 / 1 目标值。
fn level(on: bool) -> f32 {
    if on { 1. } else { 0. }
}

impl Render for StateLayerView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let motion = cx.theme().motion_tokens().clone();
        let style = self.style;
        let hovered = self.hovered && style.hover.is_some();
        // 拖出宿主后不再显示按下态（与系统按钮一致），回到宿主内重新出现。
        let pressed = self.pressed && self.hovered && style.press.is_some();
        let selected = style.is_selected && style.selected.is_some();

        // 进入快、离开略慢：悬停跟手，移开时柔和收尾。
        let enter = Transition::new(motion.duration_fast).easing(motion.easing_enter.clone());
        let leave = Transition::new(motion.duration_normal).easing(motion.easing_move.clone());
        let hover = transition(
            "hover",
            level(hovered),
            if hovered { enter } else { leave.clone() },
            window,
            cx,
        );
        let press = transition(
            "press",
            level(pressed),
            if pressed {
                Transition::new(PRESS_IN).easing(Easing::Linear)
            } else {
                leave
            },
            window,
            cx,
        );
        let selection = transition(
            "selected",
            level(selected),
            Transition::new(motion.duration_normal).easing(motion.easing_move),
            window,
            cx,
        );

        let radius = style.radius;
        let layer = move |color: Hsla, amount: f32| {
            div()
                .absolute()
                .inset_0()
                .rounded(radius)
                .bg(color.opacity(amount))
        };
        div()
            .id("flux-state-layer")
            .absolute()
            .top(style.inset)
            .left(style.inset)
            .right(style.inset)
            .bottom(style.inset)
            .rounded(radius)
            .on_hover(cx.listener(|this, hovered: &bool, _, cx| this.set_hovered(*hovered, cx)))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| this.set_pressed(true, cx)),
            )
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| this.set_pressed(false, cx)),
            )
            .on_mouse_up_out(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| this.set_pressed(false, cx)),
            )
            .when_some(style.selected.filter(|_| selection > 0.), |this, color| {
                this.child(layer(color, selection))
            })
            .when_some(style.hover.filter(|_| hover > 0.), |this, color| {
                this.child(layer(color, hover))
            })
            .when_some(style.press.filter(|_| press > 0.), |this, color| {
                this.child(layer(color, press))
            })
    }
}
