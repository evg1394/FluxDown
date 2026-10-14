//! 带进出场动效的内联提示条（表单 / 设置行下方的风险提醒）。
//!
//! 动效全部取 gpui-component 的 `MotionTokens`，系统「减弱动态效果」时直接落到终态：
//! - 出现 / 消失：[`Presence`] 驱动高度展开（[`MotionReveal`] 裁剪）+ 淡入 + 内容轻微下滑；
//!   中途反向会从当前进度折返，不跳变；
//! - 语气切换（提醒 → 危险）：底色、描边、强调色随 [`crate::color_transition`] 同节奏淡变；
//! - 每次出现或语气变化，警示图标做一次「放大回弹」，把视线拉到新内容上（一次性，不常驻动画）。

use std::time::Duration;

use fluxdown_ui_theme::active_theme;
use gpui::{
    AnyElement, App, ElementId, FontWeight, Hsla, IntoElement, ParentElement as _, RenderOnce,
    SharedString, Styled as _, Window, div, px,
};
use gpui_base::{
    Easing, Keyframe, Keyframes, MotionReveal, Presence, Timing, Transition, animate_keyframes,
};
use gpui_component::{ActiveTheme as _, Icon};

use crate::{FluxIcon, color_transition};

/// 提示语气。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CalloutTone {
    /// 需要注意（警示色）。
    Warning,
    /// 高风险（危险色）。
    Danger,
}

impl CalloutTone {
    fn color(self, cx: &App) -> Hsla {
        let theme = active_theme(cx);
        match self {
            Self::Warning => theme.extended().colors.warning,
            Self::Danger => theme.tokens().colors.destructive,
        }
    }

    const fn key(self) -> &'static str {
        match self {
            Self::Warning => "warning",
            Self::Danger => "danger",
        }
    }
}

/// 图标回弹时长：比 `duration_normal` 略长，回弹尾巴才看得出来。
const ICON_POP: Duration = Duration::from_millis(480);
/// 内容随展开下滑的距离。
const SLIDE_DISTANCE: f32 = 6.;

/// 带进出场动效的提示条。
///
/// **必须每帧渲染**（`visible == false` 时也要放进元素树）：退场动画靠同一 `id` 的状态续播，
/// 条件性地不渲染会让它直接消失。完全隐藏后只占 0 高度。
#[derive(IntoElement)]
pub struct RevealCallout {
    id: ElementId,
    visible: bool,
    tone: CalloutTone,
    title: SharedString,
    body: SharedString,
}

impl RevealCallout {
    #[must_use]
    pub fn new(
        id: impl Into<ElementId>,
        visible: bool,
        tone: CalloutTone,
        title: impl Into<SharedString>,
        body: impl Into<SharedString>,
    ) -> Self {
        Self {
            id: id.into(),
            visible,
            tone,
            title: title.into(),
            body: body.into(),
        }
    }
}

impl RenderOnce for RevealCallout {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let id = self.id.clone();
        window.with_id(id.clone(), |window| self.render_scoped(id, window, cx))
    }
}

impl RevealCallout {
    fn render_scoped(self, id: ElementId, window: &mut Window, cx: &mut App) -> AnyElement {
        let motion = cx.theme().motion_tokens().clone();
        let presence = Presence::new("presence", self.visible)
            .transition(Transition::new(motion.duration_slow).easing(motion.easing_move.clone()))
            .sample(window, cx);
        if !presence.should_render() {
            return div().into_any_element();
        }
        let progress = presence.progress;

        // 退场期间保留最后一次可见时的语气与文案，避免淡出时文字跳成已不成立的内容。
        let current = Shown {
            tone: self.tone,
            title: self.title,
            body: self.body,
        };
        let memory = window.use_keyed_state("shown", cx, |_, _| current.clone());
        let shown = if self.visible {
            if *memory.read(cx) != current {
                memory.update(cx, |memory, _| *memory = current.clone());
            }
            current
        } else {
            memory.read(cx).clone()
        };

        let accent = color_transition("accent", shown.tone.color(cx), window, cx);
        // 关键帧为常量；构造失败（不应发生）时图标保持静止。
        let icon_scale = icon_pop_keyframes().map_or(1., |keyframes| {
            animate_keyframes(
                ("icon-pop", shown.tone.key()),
                &keyframes,
                Timing::new(ICON_POP),
                window,
                cx,
            )
            .value
        });

        let theme = active_theme(cx);
        let tokens = theme.tokens().clone();
        let extended = theme.extended().clone();
        let icon_box = theme.text_extent(18.);
        let icon_size = extended.icon.sm * icon_scale;

        let content = div().w_full().pt(tokens.spacing.sm).child(
            div()
                .relative()
                .top(px(-SLIDE_DISTANCE * (1. - progress)))
                .opacity(progress)
                .w_full()
                .flex()
                .items_start()
                .gap(tokens.spacing.sm)
                .px(tokens.spacing.md)
                .py(tokens.spacing.sm)
                .rounded(tokens.radius.md)
                .bg(accent.opacity(0.08))
                .border_1()
                .border_color(accent.opacity(0.32))
                .border_l_3()
                .child(
                    div()
                        .flex_none()
                        .flex()
                        .items_center()
                        .justify_center()
                        .size(icon_box)
                        .child(
                            Icon::new(FluxIcon::TriangleAlert)
                                .size(icon_size)
                                .text_color(accent),
                        ),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .flex()
                        .flex_col()
                        .gap(tokens.spacing.xxs)
                        .child(
                            div()
                                .text_size(tokens.typography.sm.size)
                                .line_height(tokens.typography.sm.line_height)
                                .font_weight(FontWeight::SEMIBOLD)
                                .text_color(accent)
                                .child(shown.title),
                        )
                        .child(
                            div()
                                .text_size(tokens.typography.xs.size)
                                .line_height(tokens.typography.xs.line_height)
                                .text_color(tokens.colors.muted_foreground)
                                .child(shown.body),
                        ),
                ),
        );
        MotionReveal::new(id, progress, content.into_any_element()).into_any_element()
    }
}

/// 提示条当前展示的内容。
#[derive(Clone, PartialEq)]
struct Shown {
    tone: CalloutTone,
    title: SharedString,
    body: SharedString,
}

/// 1 → 1.28 → 0.94 → 1：放大后带一次轻微回弹。
fn icon_pop_keyframes() -> Option<Keyframes<f32>> {
    Keyframes::try_new([
        Keyframe::new(0., 1.),
        Keyframe::new(0.35, 1.28).ease(Easing::EaseOut),
        Keyframe::new(0.7, 0.94).ease(Easing::EaseInOut),
        Keyframe::new(1., 1.).ease(Easing::EaseOut),
    ])
    .ok()
}
