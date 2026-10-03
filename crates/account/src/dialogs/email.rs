use fluxdown_protocol::{AuthVerificationDto, method};
use fluxdown_ui_components::{
    ControlExt as _, dialog_title, field_error, field_hint, form, form_field,
};
use fluxdown_ui_theme::active_theme;
use gpui::{
    App, AppContext as _, ClickEvent, Context, Entity, IntoElement, ParentElement, Render,
    SharedString, Styled, Window, prelude::FluentBuilder as _, px,
};
use gpui_component::{
    Disableable as _, WindowExt as _,
    button::{Button, ButtonVariants as _},
    h_flex,
    input::{Input, InputEvent, InputState},
    notification::Notification,
    v_flex,
};

use super::code_step;
use crate::errors::{ErrorContext, error_text};
use crate::host::AccountHost;
use crate::verification::{CodeChallenge, spawn_ticker};
use crate::{AccountCommand, t, t_with};

#[derive(Clone, Copy, PartialEq, Eq)]
enum Operation {
    SendOld,
    SendNew,
    Confirm,
}

struct EmailDialog {
    host: Entity<AccountHost>,
    user_id: String,
    current_email: String,
    old_code: Entity<InputState>,
    new_email: Entity<InputState>,
    new_code: Entity<InputState>,
    sent_email: Option<String>,
    old_challenge: Option<CodeChallenge>,
    new_challenge: Option<CodeChallenge>,
    ticking: bool,
    busy: bool,
    closed: bool,
    invalidated: bool,
    error: Option<SharedString>,
}

pub(crate) fn open(host: &Entity<AccountHost>, window: &mut Window, cx: &mut App) {
    let controller = &host.read(cx).controller;
    if controller.is_stale() {
        return;
    }
    let Some(session) = controller.session() else {
        return;
    };
    let user_id = session.user.id.clone();
    let current_email = session.user.email.clone();
    let view = cx.new(|cx| {
        let old_code = cx.new(|cx| InputState::new(window, cx));
        let new_email = cx.new(|cx| InputState::new(window, cx));
        let new_code = cx.new(|cx| InputState::new(window, cx));
        for input in [&old_code, &new_email, &new_code] {
            cx.subscribe_in(
                input,
                window,
                |this: &mut EmailDialog, _, event, window, cx| {
                    if matches!(event, InputEvent::PressEnter { .. }) {
                        this.submit(window, cx);
                    }
                    cx.notify();
                },
            )
            .detach();
        }
        cx.observe(host, |this: &mut EmailDialog, _, cx| {
            if !this.same_session(cx) {
                this.invalidated = true;
            }
            cx.notify();
        })
        .detach();
        EmailDialog {
            host: host.clone(),
            user_id,
            current_email,
            old_code,
            new_email,
            new_code,
            sent_email: None,
            old_challenge: None,
            new_challenge: None,
            ticking: false,
            busy: false,
            closed: false,
            invalidated: false,
            error: None,
        }
    });
    let input = view.read(cx).old_code.clone();
    window.open_dialog(cx, {
        let view = view.clone();
        move |dialog, _, cx| {
            let busy = view.read(cx).busy;
            let title = view.read(cx).text("accountEmailChangeTitle", cx);
            let on_close = view.clone();
            let content = view.clone();
            dialog
                .title(dialog_title(title, cx))
                .w(px(480.))
                .close_button(!busy)
                .keyboard(!busy)
                .overlay_closable(!busy)
                .on_close(move |_, _, cx| on_close.update(cx, |this, _| this.closed = true))
                .content(move |body, _, _| body.child(content.clone()))
        }
    });
    input.update(cx, |input, cx| input.focus(window, cx));
    view.update(cx, |this, cx| this.send_old(window, cx));
}

fn email_error(email: &str, current: &str) -> Option<&'static str> {
    let email = email.trim();
    let valid = email.split_once('@').is_some_and(|(name, domain)| {
        !name.is_empty()
            && !domain.contains('@')
            && domain
                .split_once('.')
                .is_some_and(|(a, b)| !a.is_empty() && !b.is_empty())
    }) && !email.chars().any(char::is_whitespace);
    if !valid {
        Some("accountEmailChangeInvalid")
    } else if email.eq_ignore_ascii_case(current.trim()) {
        Some("accountEmailChangeSame")
    } else {
        None
    }
}

impl EmailDialog {
    fn text(&self, key: &str, cx: &App) -> SharedString {
        t(self.host.read(cx).translator().read(cx), key)
    }

    fn same_session(&self, cx: &App) -> bool {
        let controller = &self.host.read(cx).controller;
        !controller.is_stale()
            && controller
                .session()
                .is_some_and(|session| session.user.id == self.user_id)
    }

    fn enabled(&self, cx: &App) -> bool {
        !self.closed && !self.invalidated && self.same_session(cx)
    }

    fn can_submit(&self, cx: &App) -> bool {
        if self.busy || !self.enabled(cx) || !self.old_challenge.is_some_and(|c| !c.is_expired()) {
            return false;
        }
        if self.sent_email.is_some() {
            self.new_challenge.is_some_and(|c| !c.is_expired())
                && !self.new_code.read(cx).value().trim().is_empty()
        } else {
            !self.old_code.read(cx).value().trim().is_empty()
                && email_error(&self.new_email.read(cx).value(), &self.current_email).is_none()
        }
    }

    fn send_old(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.busy || !self.enabled(cx) || self.old_challenge.is_some_and(|c| !c.can_resend()) {
            return;
        }
        self.run(Operation::SendOld, serde_json::json!({}), window, cx);
    }

    fn submit(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.can_submit(cx) {
            return;
        }
        if let Some(email) = &self.sent_email {
            self.run(
                Operation::Confirm,
                serde_json::json!({
                    "email": email, "oldCode": self.old_code.read(cx).value().trim(),
                    "newCode": self.new_code.read(cx).value().trim(),
                }),
                window,
                cx,
            );
        } else {
            self.send_new(window, cx);
        }
    }

    fn send_new(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.busy
            || !self.enabled(cx)
            || !self.old_challenge.is_some_and(|c| !c.is_expired())
            || self.new_challenge.is_some_and(|c| !c.can_resend())
            || self.old_code.read(cx).value().trim().is_empty()
            || email_error(&self.new_email.read(cx).value(), &self.current_email).is_some()
        {
            return;
        }
        self.run(
            Operation::SendNew,
            serde_json::json!({
                "email": self.new_email.read(cx).value().trim(),
                "code": self.old_code.read(cx).value().trim(),
            }),
            window,
            cx,
        );
    }

    fn start_ticker(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.ticking {
            return;
        }
        self.ticking = true;
        spawn_ticker(window, cx, |this: &mut Self, _, cx| {
            let mut active = false;
            for challenge in [&mut this.old_challenge, &mut this.new_challenge]
                .into_iter()
                .flatten()
            {
                challenge.tick();
                active |= !challenge.is_idle();
            }
            this.ticking = active && !this.closed;
            cx.notify();
            this.ticking
        });
    }

    fn run(
        &mut self,
        operation: Operation,
        params: serde_json::Value,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.busy = true;
        self.error = None;
        cx.notify();
        let method = match operation {
            Operation::SendOld => method::AGENT_PROFILE_SEND_EMAIL_CODE,
            Operation::SendNew => method::AGENT_PROFILE_SEND_NEW_EMAIL_CODE,
            Operation::Confirm => method::AGENT_PROFILE_CHANGE_EMAIL,
        };
        let future = self
            .host
            .read(cx)
            .port()
            .execute(AccountCommand::Profile { method, params });
        cx.spawn_in(window, async move |this, cx| {
            let result = future.await;
            let Ok(()) = this.update_in(cx, |this, window, cx| {
                this.busy = false;
                if !this.enabled(cx) {
                    cx.notify();
                    return;
                }
                match result {
                    Ok(_) if operation == Operation::Confirm => {
                        window.push_notification(
                            Notification::success(this.text("accountEmailChangeSuccess", cx)),
                            cx,
                        );
                        this.closed = true;
                        window.close_dialog(cx);
                    }
                    Ok(value) => match serde_json::from_value::<AuthVerificationDto>(value) {
                        Ok(result) => {
                            let challenge = Some(CodeChallenge::new(result.ttl_seconds, false));
                            if operation == Operation::SendOld {
                                this.old_challenge = challenge;
                                this.old_code
                                    .update(cx, |input, cx| input.set_value("", window, cx));
                            } else {
                                this.sent_email =
                                    Some(this.new_email.read(cx).value().trim().to_owned());
                                this.new_challenge = challenge;
                                this.new_code.update(cx, |input, cx| {
                                    input.set_value("", window, cx);
                                    input.focus(window, cx);
                                });
                            }
                            this.start_ticker(window, cx);
                        }
                        Err(_) => this.error = Some(this.text("accountErrorUnknown", cx)),
                    },
                    Err(error) => {
                        this.error = Some(error_text(
                            this.host.read(cx).translator().read(cx),
                            &error,
                            ErrorContext::Code,
                        ))
                    }
                }
                cx.notify();
            }) else {
                // 对话框或窗口已释放，停止回写。
                return;
            };
        })
        .detach();
    }

    fn back(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        self.sent_email = None;
        self.new_challenge = None;
        self.error = None;
        self.old_code
            .update(cx, |input, cx| input.focus(window, cx));
        cx.notify();
    }
}

impl EmailDialog {
    fn render_fields(&self, disabled: bool, cx: &mut Context<Self>) -> gpui::Div {
        let translator = self.host.read(cx).translator().read(cx).clone();
        let verifying_new = self.sent_email.is_some();
        let subtitle = if let Some(email) = &self.sent_email {
            t_with(
                &translator,
                "accountEmailChangeCodeSubtitle",
                &[("email", email)],
            )
        } else if self.old_challenge.is_some() {
            t_with(
                &translator,
                "accountEmailChangeOldSubtitle",
                &[("email", &self.current_email)],
            )
        } else {
            self.text("accountEmailChangeOldCodeHint", cx)
        };
        let mut body = form(cx).child(field_hint(subtitle, cx));
        if verifying_new {
            body = body.child(form_field(
                self.text("accountFieldCode", cx),
                Input::new(&self.new_code)
                    .control(cx)
                    .disabled(disabled)
                    .w_full(),
                None,
                cx,
            ));
        } else {
            let email = self.new_email.read(cx).value();
            let validation = (!email.is_empty())
                .then(|| email_error(&email, &self.current_email))
                .flatten();
            body = body
                .child(form_field(
                    self.text("accountEmailChangeOldCodePlaceholder", cx),
                    Input::new(&self.old_code)
                        .control(cx)
                        .disabled(disabled)
                        .w_full(),
                    None,
                    cx,
                ))
                .child(form_field(
                    self.text("accountEmailChangeNewPlaceholder", cx),
                    Input::new(&self.new_email)
                        .control(cx)
                        .disabled(disabled)
                        .w_full(),
                    validation.map(|key| self.text(key, cx)),
                    cx,
                ));
        }
        body
    }

    fn render_footer(&self, disabled: bool, cx: &mut Context<Self>) -> impl IntoElement {
        let verifying_new = self.sent_email.is_some();
        h_flex()
            .w_full()
            .justify_end()
            .gap(active_theme(cx).tokens().spacing.sm)
            .when(verifying_new, |row| {
                row.child(
                    Button::new("account-email-back")
                        .ghost()
                        .label(self.text("back", cx))
                        .control(cx)
                        .disabled(disabled)
                        .on_click(
                            cx.listener(|this, _: &ClickEvent, window, cx| this.back(window, cx)),
                        ),
                )
            })
            .child(
                Button::new("account-email-cancel")
                    .outline()
                    .label(self.text("cancel", cx))
                    .control(cx)
                    .disabled(self.busy)
                    .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                        this.closed = true;
                        window.close_dialog(cx);
                    })),
            )
            .child(
                Button::new("account-email-confirm")
                    .primary()
                    .label(self.text(
                        if verifying_new {
                            "confirm"
                        } else {
                            "accountEmailChangeSendNewCode"
                        },
                        cx,
                    ))
                    .control(cx)
                    .loading(self.busy)
                    .disabled(!self.can_submit(cx))
                    .on_click(
                        cx.listener(|this, _: &ClickEvent, window, cx| this.submit(window, cx)),
                    ),
            )
    }
}

impl Render for EmailDialog {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let tokens = active_theme(cx).tokens().clone();
        let translator = self.host.read(cx).translator().read(cx).clone();
        let disabled = self.busy || !self.enabled(cx);
        let verifying_new = self.sent_email.is_some();
        let challenge = if verifying_new {
            self.new_challenge
        } else {
            self.old_challenge
        };
        let can_resend = !disabled
            && challenge.is_none_or(|c| c.can_resend())
            && (!verifying_new || self.old_challenge.is_some_and(|c| !c.is_expired()));
        let resend_label = challenge
            .map(|c| code_step::resend_label(&translator, &c))
            .unwrap_or_else(|| self.text("accountSendCode", cx));
        v_flex()
            .gap(tokens.spacing.lg)
            .child(self.render_fields(disabled, cx))
            .when_some(challenge, |column, challenge| {
                column.child(field_hint(
                    code_step::countdown_text(&translator, &challenge),
                    cx,
                ))
            })
            .when(
                verifying_new && self.old_challenge.is_some_and(|c| c.is_expired()),
                |column| column.child(field_error(self.text("accountCodeExpired", cx), cx)),
            )
            .child(
                Button::new("account-email-resend")
                    .ghost()
                    .label(resend_label)
                    .control(cx)
                    .disabled(!can_resend)
                    .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                        if this.sent_email.is_some() {
                            this.send_new(window, cx);
                        } else {
                            this.send_old(window, cx);
                        }
                    })),
            )
            .when_some(self.error.clone(), |column, error| {
                column.child(field_error(error, cx))
            })
            .when(!self.enabled(cx), |column| {
                column.child(field_error(self.text("localServiceDisconnected", cx), cx))
            })
            .child(self.render_footer(disabled, cx))
    }
}

#[cfg(test)]
mod tests {
    use super::email_error;

    #[test]
    fn email_change_rejects_same_address_and_malformed_input() {
        assert_eq!(
            email_error("  USER@example.com  ", "user@example.com"),
            Some("accountEmailChangeSame")
        );
        for invalid in [
            "",
            "user",
            "@example.com",
            "user@",
            "user@domain",
            "user@@example.com",
            "user name@example.com",
        ] {
            assert_eq!(
                email_error(invalid, "old@example.com"),
                Some("accountEmailChangeInvalid"),
                "{invalid}"
            );
        }
        assert_eq!(
            email_error("  new+tag@example.com  ", "old@example.com"),
            None
        );
    }
}
