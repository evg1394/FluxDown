//! 验证码步骤的共用展示：设备替换提示、有效期倒计时与「重新发送」按钮。

use fluxdown_ui_components::{BusyExt as _, ControlExt as _, field_hint};
use fluxdown_ui_i18n::Translator;
use fluxdown_ui_theme::active_theme;
use gpui::{
    ClickEvent, Context, IntoElement, ParentElement, Styled, Window, div,
    prelude::FluentBuilder as _,
};
use gpui_component::{
    Disableable as _,
    button::{Button, ButtonVariants as _},
    h_flex, v_flex,
};

use crate::verification::CodeChallenge;
use crate::{t, t_with};

/// 倒计时文案键与参数：未过期显示剩余秒数，过期提示重新发送。
pub(crate) fn countdown_text(
    translator: &Translator,
    challenge: &CodeChallenge,
) -> gpui::SharedString {
    if challenge.is_expired() {
        t(translator, "accountCodeExpired")
    } else {
        t_with(
            translator,
            "accountCodeExpireIn",
            &[("seconds", &challenge.ttl_remaining.to_string())],
        )
    }
}

/// 重发按钮标签：冷却中显示剩余秒数。
pub(crate) fn resend_label(
    translator: &Translator,
    challenge: &CodeChallenge,
) -> gpui::SharedString {
    if challenge.can_resend() {
        t(translator, "accountResendCode")
    } else {
        t_with(
            translator,
            "accountResendCodeIn",
            &[("seconds", &challenge.resend_remaining.to_string())],
        )
    }
}

/// 渲染验证码状态：（可选）替换设备提示 + 倒计时 + 重发按钮。
pub(crate) fn render<V: 'static>(
    id: &'static str,
    translator: &Translator,
    challenge: &CodeChallenge,
    resending: bool,
    on_resend: impl Fn(&mut V, &mut Window, &mut Context<V>) + 'static,
    cx: &mut Context<V>,
) -> impl IntoElement {
    let theme = active_theme(cx);
    let tokens = theme.tokens().clone();
    let warning = theme.extended().colors.warning;
    let can_resend = challenge.can_resend() && !resending;
    v_flex()
        .w_full()
        .gap(tokens.spacing.sm)
        .when(challenge.will_replace_devices, |column| {
            column.child(
                div()
                    .text_size(tokens.typography.xs.size)
                    .line_height(tokens.typography.xs.line_height)
                    .text_color(warning)
                    .child(t(translator, "accountDeviceVerifyReplacementNotice")),
            )
        })
        .child(
            h_flex()
                .w_full()
                .items_center()
                .justify_between()
                .gap(tokens.spacing.md)
                .child(field_hint(countdown_text(translator, challenge), cx))
                .child(
                    Button::new(id)
                        .ghost()
                        .label(resend_label(translator, challenge))
                        .control(cx)
                        .busy(resending)
                        .disabled(!can_resend)
                        .on_click(cx.listener(move |this, _: &ClickEvent, window, cx| {
                            on_resend(this, window, cx);
                        })),
                ),
        )
}
