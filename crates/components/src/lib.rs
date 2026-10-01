//! FluxDown 应用自有的 gpui-base 视觉封装。
//!
//! gpui-base 提供交互、键盘与无障碍语义；本 crate 只负责从完整主题 token
//! 组装稳定的 shadcn 风格。业务组件依赖这里，不直接散落颜色和尺寸字面量。

mod icons;
mod kit;
mod sidebar;

pub use icons::{ComponentAssets, FluxIcon, category_icon};
pub use kit::{
    ControlExt, DIALOG_PRIMARY_KEY_CONTEXT, DialogIntent, IconControlExt, caption_number,
    check_row, dialog_footer, dialog_scroll_body, dialog_title, field_error, field_hint,
    field_label, form, form_field, form_gap, form_row, input_with_action, option_group, option_row,
    segmented_tabs,
};
pub use sidebar::{SidebarChange, SidebarPanel, SidebarState};

use fluxdown_ui_theme::active_theme;
use gpui::prelude::FluentBuilder as _;
use gpui::{
    App, Div, ElementId, FontFeatures, FontWeight, Hsla, InteractiveElement, IntoElement,
    ParentElement, Pixels, SharedString, StatefulInteractiveElement as _, Styled, div, px,
    relative,
};
pub use gpui_base::Button;
use gpui_component::Sizable as _;

/// 等宽数字（OpenType `tnum`）：速度、大小、百分比、计数等会刷新的数字统一使用，
/// 避免比例数字在刷新时左右跳动。
pub fn tabular_numbers() -> FontFeatures {
    FontFeatures(std::sync::Arc::new(vec![("tnum".into(), 1)]))
}

/// 基础按钮的视觉语义。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ButtonVariant {
    Primary,
    Secondary,
    Ghost,
    /// 外链 / 跳转类文字操作：幽灵按钮外观 + `colors.accentText` 文字，悬停同样只做中性加深。
    Link,
    Destructive,
}

/// 创建具备键盘、焦点、无障碍与完整交互态的主题按钮。
pub fn button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    variant: ButtonVariant,
    cx: &App,
) -> Button {
    let label = label.into();
    text_button_frame(id, variant, cx)
        .accessibility_label(label.clone())
        .child(label)
}

/// 触发异步动作（刷新、检测、测试等）的文字按钮：`loading` 时标签前显示旋转图标并置为不可点击，
/// 视觉只轻微淡出（0.8）而不是禁用态的 0.5，表达「进行中」而非「不可用」。
///
/// 本函数已按 `loading` 设置禁用；调用方若再调用 `.disabled(..)`，必须把 `loading` 并入条件。
pub fn loading_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    variant: ButtonVariant,
    loading: bool,
    cx: &App,
) -> Button {
    let theme = active_theme(cx);
    let tokens = theme.tokens();
    let spinner_size = theme.extended().icon.sm;
    let label = label.into();
    with_loading_state(text_button_frame(id, variant, cx), loading)
        .gap(tokens.spacing.xs + tokens.spacing.xxs)
        .accessibility_label(label.clone())
        .when(loading, |this| this.child(spinner(spinner_size)))
        .child(label)
}

/// 文字按钮的公共外观（不含内容），供 [`button`] / [`loading_button`] 共用。
fn text_button_frame(id: impl Into<ElementId>, variant: ButtonVariant, cx: &App) -> Button {
    let theme = active_theme(cx);
    let tokens = theme.tokens();
    let palette = ButtonPalette::for_variant(variant, tokens.colors, theme.extended().colors);

    Button::new(id)
        .h(theme.density().control)
        .px(tokens.spacing.sm + tokens.spacing.xxs)
        .line_height(relative(1.))
        .flex()
        .items_center()
        .justify_center()
        .cursor_pointer()
        .rounded(theme.components().button_radius)
        .border(theme.extended().stroke.thin)
        .border_color(palette.border)
        .bg(palette.background)
        .text_color(palette.foreground)
        .text_size(tokens.typography.sm.size)
        .font_weight(tokens.typography.sm.weight)
        .hover(move |style| style.bg(palette.hover))
        .active(move |style| style.bg(palette.active))
        .focus_visible(move |style| style.border_color(tokens.colors.ring))
        .styles(|styles| styles.disabled(|style| style.opacity(0.5)))
}

/// 进行中：不可点击，但只淡出到 0.8，与普通禁用区分。
fn with_loading_state(button: Button, loading: bool) -> Button {
    if loading {
        button
            .disabled(true)
            .styles(|styles| styles.disabled(|style| style.opacity(0.8)))
    } else {
        button
    }
}

/// 按钮内的旋转加载图标；颜色继承按钮文字色（危险按钮等自定义前景色同样适用）。
fn spinner(size: Pixels) -> gpui_component::spinner::Spinner {
    gpui_component::spinner::Spinner::new().with_size(gpui_component::Size::Size(size))
}

/// 单选「选项片」：一组互斥预设中的一项（如 Webhook 模板预设）。未选中为次要按钮外观，
/// 选中为浅强调底 + 强调色描边与文字；悬停色随选中态区分。
///
/// gpui 的 `hover` 每个元素只能设置一次（debug 断言），因此选中态的悬停样式必须在
/// 这里一次性决定，调用方不得再对返回值调用 `.hover()`。
pub fn choice_chip(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    selected: bool,
    cx: &App,
) -> Button {
    let theme = active_theme(cx);
    let tokens = theme.tokens();
    let colors = tokens.colors;
    let label = label.into();
    let (background, foreground, border, hover) = if selected {
        (
            colors.accent,
            colors.accent_foreground,
            colors.primary,
            colors.accent,
        )
    } else {
        (
            colors.surface,
            colors.foreground,
            colors.border,
            colors.muted,
        )
    };

    Button::new(id)
        .h(theme.density().control)
        .px(tokens.spacing.sm + tokens.spacing.xxs)
        .line_height(relative(1.))
        .flex()
        .items_center()
        .justify_center()
        .cursor_pointer()
        .rounded(theme.components().button_radius)
        .border(theme.extended().stroke.thin)
        .border_color(border)
        .bg(background)
        .text_color(foreground)
        .text_size(tokens.typography.sm.size)
        .font_weight(if selected {
            FontWeight::MEDIUM
        } else {
            tokens.typography.sm.weight
        })
        .hover(move |style| style.bg(hover))
        .focus_visible(move |style| style.border_color(colors.ring))
        .selected(selected)
        .accessibility_label(label.clone())
        .child(label)
}

/// 创建仅图标的方形按钮（`density.control` 尺寸，与同行输入框等高），用于设置行内联的紧凑操作
/// （复制 / 生成 / 清空等）。`label` 仅用作无障碍标签，不渲染文字；
/// 视觉悬浮提示由调用方通过 `Button::tooltip` 叠加。
pub fn icon_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    icon: impl IntoElement,
    variant: ButtonVariant,
    cx: &App,
) -> Button {
    icon_button_frame(id, label, variant, cx).child(icon)
}

/// [`icon_button`] 的 loading 版本：`loading` 时以旋转图标替换原图标并置为不可点击
/// （0.8 淡出）。调用方若再调用 `.disabled(..)`，必须把 `loading` 并入条件。
pub fn loading_icon_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    icon: impl IntoElement,
    variant: ButtonVariant,
    loading: bool,
    cx: &App,
) -> Button {
    let spinner_size = active_theme(cx).extended().icon.md;
    let button = with_loading_state(icon_button_frame(id, label, variant, cx), loading);
    if loading {
        button.child(spinner(spinner_size))
    } else {
        button.child(icon)
    }
}

fn icon_button_frame(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    variant: ButtonVariant,
    cx: &App,
) -> Button {
    let theme = active_theme(cx);
    let tokens = theme.tokens();
    let palette = ButtonPalette::for_variant(variant, tokens.colors, theme.extended().colors);

    Button::new(id)
        .size(theme.density().control)
        .flex()
        .items_center()
        .justify_center()
        .cursor_pointer()
        .rounded(theme.components().button_radius)
        .border(theme.extended().stroke.thin)
        .border_color(palette.border)
        .bg(palette.background)
        .text_color(palette.foreground)
        .hover(move |style| style.bg(palette.hover))
        .active(move |style| style.bg(palette.active))
        .focus_visible(move |style| style.border_color(tokens.colors.ring))
        .styles(|styles| styles.disabled(|style| style.opacity(0.5)))
        .accessibility_label(label)
}
/// 创建带前置图标的主要操作按钮。
pub fn primary_icon_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    icon: impl IntoElement,
    cx: &App,
) -> Button {
    let theme = active_theme(cx);
    let tokens = theme.tokens();
    let label = label.into();
    let palette = ButtonPalette::for_variant(
        ButtonVariant::Primary,
        tokens.colors,
        theme.extended().colors,
    );

    Button::new(id)
        .h(theme.density().control)
        .px(tokens.spacing.sm + tokens.spacing.xxs)
        .line_height(relative(1.))
        .flex()
        .items_center()
        .justify_center()
        .cursor_pointer()
        .rounded(theme.components().button_radius)
        .border(theme.extended().stroke.thin)
        .border_color(palette.border)
        .bg(palette.background)
        .text_color(palette.foreground)
        .text_size(tokens.typography.sm.size)
        .font_weight(tokens.typography.sm.weight)
        .hover(move |style| style.bg(palette.hover))
        .active(move |style| style.bg(palette.active))
        .focus_visible(move |style| style.border_color(tokens.colors.ring))
        .accessibility_label(label.clone())
        .child(
            div()
                .flex()
                .items_center()
                .gap(tokens.spacing.xs + tokens.spacing.xxs)
                .child(icon)
                .child(label),
        )
}

/// 创建顶栏 / 状态栏等 chrome 区域的紧凑图标按钮；`disabled` 时不响应点击且无悬停反馈。
///
/// 默认图标为二级文字色，悬停时底色取 `nav_hover`、图标变为正文色；
/// `destructive` 只改变图标颜色，不做大面积红色填充。
pub fn toolbar_action_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    icon: impl IntoElement,
    destructive: bool,
    disabled: bool,
    cx: &App,
) -> Button {
    let theme = active_theme(cx);
    let tokens = theme.tokens();
    let extended = theme.extended().colors;
    let foreground = if destructive {
        tokens.colors.destructive
    } else {
        tokens.colors.muted_foreground
    };
    let hover_foreground = if destructive {
        tokens.colors.destructive
    } else {
        tokens.colors.foreground
    };

    let button = Button::new(id)
        .size(theme.density().toolbar_button)
        .flex()
        .flex_none()
        .items_center()
        .justify_center()
        .rounded(theme.components().button_radius)
        .bg(transparent(extended.nav_hover))
        .text_color(foreground)
        .disabled(disabled)
        .accessibility_label(label)
        .child(icon);
    if disabled {
        return button.opacity(0.35).cursor_default();
    }
    button
        .cursor_pointer()
        .hover(move |style| style.bg(extended.nav_hover).text_color(hover_foreground))
        .active(move |style| style.bg(extended.nav_selected))
        .focus_visible(move |style| style.bg(extended.nav_hover))
}

/// 创建侧栏导航按钮；选中态由调用方控制。
pub fn navigation_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    selected: bool,
    cx: &App,
) -> Button {
    let tokens = active_theme(cx).tokens();
    button(id, label, ButtonVariant::Ghost, cx)
        .w_full()
        .justify_start()
        .selected(selected)
        .styles(|styles| {
            styles.selected(|style| {
                style
                    .bg(tokens.colors.accent)
                    .text_color(tokens.colors.accent_foreground)
            })
        })
}

/// 创建带图标与尾部信息的侧栏导航按钮。
///
/// 选中态是中性底色 + `navSelectedForeground` 文字 + 中等字重，未选中为二级文字色；
/// 图标颜色由调用方决定，常规导航项用 [`nav_icon_color`]（选中时强调色落在图标上）。
pub fn sidebar_navigation_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    icon: impl IntoElement,
    trailing: impl IntoElement,
    selected: bool,
    cx: &App,
) -> Button {
    let theme = active_theme(cx);
    let tokens = theme.tokens();
    let extended = theme.extended().colors;
    let label = label.into();
    let (background, foreground, weight) = if selected {
        (
            extended.nav_selected,
            extended.nav_selected_foreground,
            FontWeight::MEDIUM,
        )
    } else {
        (
            transparent(extended.nav_hover),
            tokens.colors.muted_foreground,
            FontWeight::NORMAL,
        )
    };
    let (hover_background, hover_foreground) = if selected {
        (extended.nav_selected, extended.nav_selected_foreground)
    } else {
        (extended.nav_hover, tokens.colors.foreground)
    };

    Button::new(id)
        .h(theme.density().nav_row)
        .w_full()
        .px(tokens.spacing.sm)
        .line_height(relative(1.))
        .flex()
        .items_center()
        .justify_between()
        .gap(tokens.spacing.xs)
        .cursor_pointer()
        .rounded(theme.components().nav_item_radius)
        .bg(background)
        .text_color(foreground)
        .text_size(tokens.typography.sm.size)
        .font_weight(weight)
        .hover(move |style| style.bg(hover_background).text_color(hover_foreground))
        .active(move |style| style.bg(extended.nav_selected))
        .focus_visible(move |style| style.bg(hover_background))
        .selected(selected)
        .accessibility_label(label.clone())
        .child(
            div()
                .min_w_0()
                .flex()
                .items_center()
                .gap(tokens.spacing.sm)
                .child(icon)
                .child(div().truncate().child(label)),
        )
        .child(trailing)
}

/// 导航项（侧栏、设置分类、活动栏、订阅源列表）的图标色：选中取 `navSelectedIcon`
/// （强调色，保证与选中底色 ≥ 3:1），未选中取二级文字色。
pub fn nav_icon_color(selected: bool, cx: &App) -> Hsla {
    let theme = active_theme(cx);
    if selected {
        theme.extended().colors.nav_selected_icon
    } else {
        theme.tokens().colors.muted_foreground
    }
}

/// 创建活动栏按钮。
///
/// 选中态为中性底色 + 强调色图标（图标色由调用方经 [`nav_icon_color`] 决定）；未选中为
/// 二级文字色图标，悬停时出现浅底。活动栏与侧栏同为 `chrome` 底色，不铺强调色块。
pub fn activity_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    icon: impl IntoElement,
    selected: bool,
    size: Pixels,
    cx: &App,
) -> Button {
    let theme = active_theme(cx);
    let tokens = theme.tokens();
    let colors = tokens.colors;
    let extended = theme.extended().colors;
    let (background, foreground) = if selected {
        (extended.nav_selected, extended.nav_selected_icon)
    } else {
        (transparent(extended.nav_hover), colors.muted_foreground)
    };
    let (hover_background, hover_foreground) = if selected {
        (extended.nav_selected, extended.nav_selected_icon)
    } else {
        (extended.nav_hover, colors.foreground)
    };

    // 颜色直接落在基础样式上：`styles.selected` 的 text_color 不会传给 svg 图标。
    Button::new(id)
        .size(size)
        .flex()
        .items_center()
        .justify_center()
        .cursor_pointer()
        .rounded(theme.components().nav_item_radius)
        .bg(background)
        .text_color(foreground)
        .hover(move |style| style.bg(hover_background).text_color(hover_foreground))
        .active(move |style| style.bg(extended.nav_selected))
        .focus_visible(move |style| style.bg(hover_background))
        .accessibility_label(label)
        .child(icon)
}

/// 复选框的三态。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CheckState {
    Unchecked,
    Checked,
    /// 部分选中（表头全选在只选中部分行时）。
    Indeterminate,
}

/// 列表用复选框外观（纯视觉，点击由调用方挂在外层）：`density.checkMark` 见方的圆角方块，
/// 未选中为细描边空框，选中 / 部分选中为强调色实心 + 粗线勾 / 横线。
pub fn check_mark(state: CheckState, cx: &App) -> Div {
    let theme = active_theme(cx);
    let tokens = theme.tokens();
    let colors = tokens.colors;
    let glyph = match state {
        CheckState::Unchecked => None,
        CheckState::Checked => Some(FluxIcon::CheckboxCheck),
        CheckState::Indeterminate => Some(FluxIcon::CheckboxMinus),
    };
    let base = div()
        .size(theme.density().check_mark)
        .flex()
        .flex_none()
        .items_center()
        .justify_center()
        .rounded(theme.components().checkbox_radius)
        .border(theme.extended().stroke.thin);
    match glyph {
        None => base
            .border_color(colors.muted_foreground.opacity(0.55))
            .bg(colors.surface),
        Some(glyph) => base.border_color(colors.primary).bg(colors.primary).child(
            gpui_component::Icon::new(glyph)
                .size(px(11.))
                .text_color(colors.primary_foreground),
        ),
    }
}

/// 基础卡片：内容底色 + hairline 描边 + `components.card.radius`，不加阴影（阴影只留给浮层）。
pub fn card(cx: &App) -> Div {
    let theme = active_theme(cx);
    let tokens = theme.tokens();
    div()
        .bg(tokens.colors.surface)
        .text_color(tokens.colors.surface_foreground)
        .border(theme.extended().stroke.thin)
        .border_color(theme.extended().colors.hairline)
        .rounded(theme.components().card_radius)
}

#[derive(Clone, Copy)]
struct ButtonPalette {
    background: Hsla,
    foreground: Hsla,
    border: Hsla,
    hover: Hsla,
    active: Hsla,
}

impl ButtonPalette {
    fn for_variant(
        variant: ButtonVariant,
        colors: fluxdown_ui_theme::ColorTokens,
        extended: fluxdown_ui_theme::ExtendedColors,
    ) -> Self {
        let ghost = Self {
            background: transparent(colors.background),
            foreground: colors.foreground,
            border: transparent(colors.border),
            hover: colors.muted,
            active: shift_toward_contrast(colors.muted, 0.04),
        };
        match variant {
            ButtonVariant::Primary => Self::filled(colors.primary, colors.primary_foreground),
            // 次要按钮：内容底色 + 描边（outline），悬停只做中性加深，不泛强调色。
            ButtonVariant::Secondary => Self {
                background: colors.surface,
                foreground: colors.foreground,
                border: colors.border,
                hover: colors.muted,
                active: shift_toward_contrast(colors.muted, 0.04),
            },
            ButtonVariant::Ghost => ghost,
            ButtonVariant::Link => Self {
                foreground: extended.accent_text,
                ..ghost
            },
            ButtonVariant::Destructive => {
                Self::filled(colors.destructive, colors.destructive_foreground)
            }
        }
    }

    fn filled(background: Hsla, foreground: Hsla) -> Self {
        Self {
            background,
            foreground,
            border: background,
            hover: shift_toward_contrast(background, 0.08),
            active: shift_toward_contrast(background, 0.13),
        }
    }
}

fn transparent(color: Hsla) -> Hsla {
    Hsla { a: 0., ..color }
}

fn shift_toward_contrast(color: Hsla, amount: f32) -> Hsla {
    let delta = if color.l >= 0.5 { -amount } else { amount };
    shift_lightness(color, delta)
}

fn shift_lightness(color: Hsla, delta: f32) -> Hsla {
    Hsla {
        l: (color.l + delta).clamp(0., 1.),
        ..color
    }
}
