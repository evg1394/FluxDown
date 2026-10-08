//! 账号与安全分组：邮箱展示与双邮箱验证的变更入口、登录密码的修改 / 设置入口。

use fluxdown_protocol::AgentSessionDto;
use fluxdown_ui_components::{ButtonVariant, button, card};
use fluxdown_ui_i18n::Translator;
use fluxdown_ui_theme::SemanticThemeTokens;
use gpui::{Context, FontWeight, IntoElement, ParentElement, Styled, div};
use gpui_component::h_flex;

use crate::view::AccountView;
use crate::{t, ui};

pub(crate) fn render(
    translator: &Translator,
    tokens: &SemanticThemeTokens,
    session: &AgentSessionDto,
    disabled: bool,
    cx: &mut Context<AccountView>,
) -> impl IntoElement {
    let heading = t(translator, "accountSecurityGroup");
    let desc = t(translator, "accountSecurityGroupDesc");
    let email_label = t(translator, "accountEmailPlaceholder");
    let body = &tokens.typography.sm;
    let has_password = session.user.has_password;
    let (password_status, password_action) = if has_password == Some(false) {
        ("accountPasswordStatusNotSet", "accountPasswordSetTitle")
    } else {
        ("accountPasswordStatusSet", "accountPasswordChangeTitle")
    };

    // 一行：左标签，右「状态文案 + 操作按钮」。
    let row = |label: gpui::SharedString, value: gpui::SharedString, action: gpui::AnyElement| {
        h_flex()
            .w_full()
            .justify_between()
            .items_center()
            .text_size(body.size)
            .line_height(body.line_height)
            .child(
                div()
                    .font_weight(FontWeight::MEDIUM)
                    .text_color(tokens.colors.foreground)
                    .child(label),
            )
            .child(
                h_flex()
                    .min_w_0()
                    .gap(tokens.spacing.sm)
                    .child(
                        div()
                            .min_w_0()
                            .truncate()
                            .text_color(tokens.colors.muted_foreground)
                            .child(value),
                    )
                    .child(action),
            )
    };
    let email_action = button(
        "account-email-edit",
        t(translator, "accountEmailChangeTitle"),
        ButtonVariant::Secondary,
        cx,
    )
    .disabled(disabled)
    .on_click(cx.listener(|view, _, window, cx| view.open_email_edit(window, cx)))
    .into_any_element();
    let password_action = button(
        "account-password-edit",
        t(translator, password_action),
        ButtonVariant::Secondary,
        cx,
    )
    .disabled(disabled)
    .on_click(cx.listener(|view, _, window, cx| view.open_password_edit(window, cx)))
    .into_any_element();

    div()
        .flex()
        .flex_col()
        .gap(tokens.spacing.sm)
        .child(ui::group_heading(heading, Some(desc), cx))
        .child(
            card(cx)
                .w_full()
                .p(tokens.spacing.md)
                .flex()
                .flex_col()
                .gap(tokens.spacing.md)
                .child(row(
                    email_label,
                    session.user.email.clone().into(),
                    email_action,
                ))
                .child(row(
                    t(translator, "accountPasswordTitle"),
                    t(translator, password_status),
                    password_action,
                )),
        )
}
