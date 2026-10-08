//! 「添加设备」对话框：「账号（推荐）」说明页 + 「直连配对」页。
//!
//! 直连配对页：本机配对码（供对端输入）、局域网发现列表 / 手动地址、输入对端配对码 →
//! `pairBegin` → 核对 SAS → `pairFinish`。对话框存活期间开启发现与配对广播，关闭时收回。

use fluxdown_protocol::{
    LinkDiscoveryParams, LinkPairBeginParams, LinkPairBeginResponse, LinkPairFinishParams,
    LinkPairFinishResponse, LinkPairingCodeDto, method,
};
use fluxdown_ui_components::{
    BusyExt as _, ControlExt as _, dialog_scroll_body, field_error, field_hint, field_label,
    form_field, input_with_action, segmented_tabs, tabular_numbers,
};
use fluxdown_ui_theme::active_theme;
use gpui::{
    App, AppContext as _, ClickEvent, ClipboardItem, Context, Entity, FontWeight,
    InteractiveElement as _, IntoElement, ParentElement, Render, SharedString,
    StatefulInteractiveElement as _, Styled, Window, div, prelude::FluentBuilder as _, px,
};
use gpui_component::{
    Disableable as _, WindowExt as _,
    button::{Button, ButtonVariants as _},
    h_flex,
    input::{Input, InputState},
    notification::Notification,
    v_flex,
};

use crate::errors::{ErrorContext, error_text};
use crate::host::AccountHost;
use crate::link::{
    DiscoveredEntry, PairFlow, discovered_entries, group_sas, needs_lan_hint, now_unix_ms,
    seconds_until, validate_pair_input,
};
use crate::verification::spawn_ticker;
use crate::{AccountCommand, PortFuture, t, t_with};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Tab {
    Account,
    Direct,
}

struct AddDeviceDialog {
    host: Entity<AccountHost>,
    tab: Tab,
    address_input: Entity<InputState>,
    code_input: Entity<InputState>,
    /// 直连页已开启发现 / 配对广播（关闭时需要收回）。
    direct_started: bool,
    own_code: Option<LinkPairingCodeDto>,
    own_code_busy: bool,
    own_code_error: Option<SharedString>,
    flow: PairFlow,
    error: Option<SharedString>,
    selected_address: Option<String>,
    closed: bool,
    seen_incoming: usize,
}

/// 打开「添加设备」对话框。已登录时默认停在「账号」页，否则直接进「直连配对」。
pub fn open(host: &Entity<AccountHost>, window: &mut Window, cx: &mut App) {
    let title = t(host.read(cx).translator().read(cx), "addDeviceEntry");
    let view = cx.new(|cx| AddDeviceDialog::new(host.clone(), window, cx));
    let closing = view.clone();
    window.open_dialog(cx, move |dialog, _, cx| {
        let view = view.clone();
        let closing = closing.clone();
        dialog
            .title(fluxdown_ui_components::dialog_title(title.clone(), cx))
            .w(active_theme(cx).text_extent(560.))
            .content(move |content, _, _| content.min_h_0().child(view.clone()))
            .on_close(move |_, _, cx| {
                closing.update(cx, |this, cx| this.shutdown(cx));
            })
    });
}

impl AddDeviceDialog {
    fn new(host: Entity<AccountHost>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let translator = host.read(cx).translator().clone();
        let address_placeholder = SharedString::from("192.168.1.20:17800");
        let code_placeholder = t(translator.read(cx), "localPairingCodePlaceholder");
        cx.observe(&host, |this, host, cx| {
            // 对端输入本机码后码即被 agent 消耗；出现新的入站请求时换新码，避免界面仍展示已失效的旧码。
            let incoming = host.read(cx).pending_pairing_requests().len();
            if incoming > this.seen_incoming && this.direct_started && !this.closed {
                this.refresh_own_code(cx);
            }
            this.seen_incoming = incoming;
            cx.notify();
        })
        .detach();
        cx.observe(&translator, |_, _, cx| cx.notify()).detach();
        let logged_in = host.read(cx).controller.session().is_some();
        let seen_incoming = host.read(cx).pending_pairing_requests().len();
        let mut this = Self {
            host,
            tab: if logged_in { Tab::Account } else { Tab::Direct },
            address_input: cx
                .new(|cx| InputState::new(window, cx).placeholder(address_placeholder)),
            code_input: cx.new(|cx| InputState::new(window, cx).placeholder(code_placeholder)),
            direct_started: false,
            own_code: None,
            own_code_busy: false,
            own_code_error: None,
            flow: PairFlow::Form,
            error: None,
            selected_address: None,
            closed: false,
            seen_incoming,
        };
        spawn_ticker(window, cx, |this: &mut Self, _, cx| {
            if this.closed {
                return false;
            }
            // 本机配对码到期自动换新（失败后不再自动重试，交给「重新生成」）。
            if this.direct_started
                && !this.own_code_busy
                && this.own_code_error.is_none()
                && this
                    .own_code
                    .as_ref()
                    .is_some_and(|code| seconds_until(code.expires_at_unix_ms, now_unix_ms()) == 0)
            {
                this.refresh_own_code(cx);
            }
            if this.direct_started {
                cx.notify();
            }
            true
        });
        if this.tab == Tab::Direct {
            this.start_direct(cx);
        }
        this
    }

    fn t(&self, key: &str, cx: &App) -> SharedString {
        t(self.host.read(cx).translator().read(cx), key)
    }

    fn link_call(
        &self,
        method: &'static str,
        params: serde_json::Value,
        cx: &App,
    ) -> PortFuture<serde_json::Value> {
        self.host
            .read(cx)
            .port()
            .execute(AccountCommand::Link { method, params })
    }

    fn select_tab(&mut self, tab: Tab, cx: &mut Context<Self>) {
        if self.tab == tab {
            return;
        }
        self.tab = tab;
        if tab == Tab::Direct {
            self.start_direct(cx);
        }
        cx.notify();
    }

    /// 进入直连页：开启局域网发现并生成本机配对码。
    fn start_direct(&mut self, cx: &mut Context<Self>) {
        if self.direct_started {
            return;
        }
        self.direct_started = true;
        self.set_discovery(true, cx);
        self.refresh_own_code(cx);
    }

    fn set_discovery(&mut self, enabled: bool, cx: &mut Context<Self>) {
        let params = serde_json::to_value(LinkDiscoveryParams { enabled }).unwrap_or_default();
        let future = self.link_call(method::AGENT_LINK_DISCOVERY_SET, params, cx);
        cx.spawn(async move |this, cx| {
            if let Err(error) = future.await {
                if !enabled {
                    // 停止发现不回写已关闭的对话框，但仍记录真实失败。
                    eprintln!(
                        "disabling local device discovery failed: {:?} ({:?})",
                        error.code, error.reason
                    );
                    return;
                }
                let Ok(()) = this.update(cx, |this, cx| {
                    this.error = Some(error_text(
                        this.host.read(cx).translator().read(cx),
                        &error,
                        ErrorContext::Pairing,
                    ));
                    cx.notify();
                }) else {
                    // 账户视图或窗口已释放，结束回调，不再更新状态。
                    return;
                };
            }
        })
        .detach();
    }

    fn refresh_own_code(&mut self, cx: &mut Context<Self>) {
        if self.own_code_busy {
            return;
        }
        self.own_code_busy = true;
        self.own_code_error = None;
        cx.notify();
        let future = self.link_call(method::AGENT_LINK_PAIRING_CODE, serde_json::json!({}), cx);
        cx.spawn(async move |this, cx| {
            let result = future.await.and_then(|value| {
                serde_json::from_value::<LinkPairingCodeDto>(value).map_err(|_| {
                    fluxdown_protocol::RpcErrorData::new(
                        fluxdown_protocol::ApplicationErrorCode::Internal,
                        false,
                    )
                })
            });
            let Ok(()) = this.update(cx, |this, cx| {
                this.own_code_busy = false;
                match result {
                    Ok(code) => this.own_code = Some(code),
                    Err(error) => {
                        this.own_code_error = Some(error_text(
                            this.host.read(cx).translator().read(cx),
                            &error,
                            ErrorContext::Pairing,
                        ));
                    }
                }
                cx.notify();
            }) else {
                // 账户视图或窗口已释放，结束回调，不再更新状态。
                return;
            };
        })
        .detach();
    }

    /// 重新扫描：先关再开，让 agent 重新发起一轮发现。
    fn rescan(&mut self, cx: &mut Context<Self>) {
        self.error = None;
        self.set_discovery(false, cx);
        self.set_discovery(true, cx);
        cx.notify();
    }

    fn select_peer(
        &mut self,
        entry: &DiscoveredEntry,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if entry.paired || !self.flow.can_begin() {
            return;
        }
        self.selected_address = Some(entry.address.clone());
        let address = SharedString::from(entry.address.clone());
        self.address_input
            .update(cx, |input, cx| input.set_value(address, window, cx));
        self.code_input
            .update(cx, |input, cx| input.focus(window, cx));
        cx.notify();
    }

    fn begin(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.flow.can_begin() {
            return;
        }
        let address = self.address_input.read(cx).value().to_string();
        let code = self.code_input.read(cx).value().to_string();
        let (address, code) = match validate_pair_input(&address, &code) {
            Ok(input) => input,
            Err(error) => {
                self.error = Some(self.t(error.key(), cx));
                cx.notify();
                return;
            }
        };
        self.error = None;
        self.flow = PairFlow::Beginning;
        cx.notify();
        let params =
            serde_json::to_value(LinkPairBeginParams { address, code }).unwrap_or_default();
        let future = self.link_call(method::AGENT_LINK_PAIR_BEGIN, params, cx);
        cx.spawn_in(window, async move |this, cx| {
            let result = future.await.and_then(|value| {
                serde_json::from_value::<LinkPairBeginResponse>(value).map_err(|_| {
                    fluxdown_protocol::RpcErrorData::new(
                        fluxdown_protocol::ApplicationErrorCode::Internal,
                        false,
                    )
                })
            });
            let Ok(()) = this.update_in(cx, |this, _, cx| {
                match result {
                    Ok(response) => {
                        let flow = std::mem::replace(&mut this.flow, PairFlow::Form);
                        this.flow = flow.began(response.token, response.sas, response.peer_name);
                    }
                    Err(error) => {
                        this.error = Some(error_text(
                            this.host.read(cx).translator().read(cx),
                            &error,
                            ErrorContext::Pairing,
                        ));
                        this.flow = std::mem::replace(&mut this.flow, PairFlow::Form).failed();
                    }
                }
                cx.notify();
            }) else {
                // 对话框或窗口已释放，停止回写异步结果。
                return;
            };
        })
        .detach();
    }

    /// 核对 SAS 后确认（`accept=true`）或放弃（回到表单）。
    fn finish(&mut self, accept: bool, window: &mut Window, cx: &mut Context<Self>) {
        let PairFlow::Verify { token, .. } = self.flow.clone() else {
            return;
        };
        self.error = None;
        let flow = std::mem::replace(&mut self.flow, PairFlow::Form);
        self.flow = if accept {
            flow.confirming()
        } else {
            PairFlow::Form
        };
        cx.notify();
        let params =
            serde_json::to_value(LinkPairFinishParams { token, accept }).unwrap_or_default();
        let future = self.link_call(method::AGENT_LINK_PAIR_FINISH, params, cx);
        if !accept {
            // 放弃：只需通知 agent 释放会话，结果不影响界面。
            cx.background_spawn(async move {
                if let Err(error) = future.await {
                    // 配对令牌已过期或会话已结束时，放弃操作本身已达成。
                    if error.reason == Some(fluxdown_protocol::ErrorReason::PairingSessionExpired) {
                        return;
                    }
                    eprintln!(
                        "abandoning local pairing failed: {:?} ({:?})",
                        error.code, error.reason
                    );
                }
            })
            .detach();
            return;
        }
        cx.spawn_in(window, async move |this, cx| {
            let result = future.await.and_then(|value| {
                serde_json::from_value::<LinkPairFinishResponse>(value).map_err(|_| {
                    fluxdown_protocol::RpcErrorData::new(
                        fluxdown_protocol::ApplicationErrorCode::Internal,
                        false,
                    )
                })
            });
            let Ok(()) = this.update_in(cx, |this, window, cx| {
                match result {
                    Ok(response) => {
                        let paired = response.paired;
                        let name = response.device.map(|device| device.name);
                        let flow = std::mem::replace(&mut this.flow, PairFlow::Form);
                        this.flow = flow.finished(paired, name);
                        if let PairFlow::Done { device_name } = &this.flow {
                            let message = t_with(
                                this.host.read(cx).translator().read(cx),
                                "localPairingPaired",
                                &[("device", device_name)],
                            );
                            window.push_notification(Notification::success(message), cx);
                        } else {
                            this.error = Some(this.t("errReasonPairingRejected", cx));
                        }
                    }
                    Err(error) => {
                        this.error = Some(error_text(
                            this.host.read(cx).translator().read(cx),
                            &error,
                            ErrorContext::Pairing,
                        ));
                        this.flow = std::mem::replace(&mut this.flow, PairFlow::Form).failed();
                    }
                }
                cx.notify();
            }) else {
                // 对话框或窗口已释放，停止回写异步结果。
                return;
            };
        })
        .detach();
    }

    /// 对话框关闭：收回发现与配对广播；SAS 核对中途关闭视为放弃。幂等。
    fn shutdown(&mut self, cx: &mut Context<Self>) {
        if std::mem::replace(&mut self.closed, true) {
            return;
        }
        if let PairFlow::Verify { token, .. } = &self.flow {
            let params = serde_json::to_value(LinkPairFinishParams {
                token: token.clone(),
                accept: false,
            })
            .unwrap_or_default();
            let future = self.link_call(method::AGENT_LINK_PAIR_FINISH, params, cx);
            cx.background_spawn(async move {
                if let Err(error) = future.await {
                    // 配对令牌已过期或会话已结束时，关闭清理无需再处理。
                    if error.reason == Some(fluxdown_protocol::ErrorReason::PairingSessionExpired) {
                        return;
                    }
                    eprintln!(
                        "closing local pairing session failed: {:?} ({:?})",
                        error.code, error.reason
                    );
                }
            })
            .detach();
        }
        if self.direct_started {
            let params =
                serde_json::to_value(LinkDiscoveryParams { enabled: false }).unwrap_or_default();
            let discovery = self.link_call(method::AGENT_LINK_DISCOVERY_SET, params, cx);
            let stop = self.link_call(method::AGENT_LINK_STOP_PAIRING, serde_json::json!({}), cx);
            cx.background_spawn(async move {
                if let Err(error) = discovery.await {
                    // 发现停止失败也必须继续撤回配对广播。
                    eprintln!(
                        "stopping local discovery failed: {:?} ({:?})",
                        error.code, error.reason
                    );
                }
                if let Err(error) = stop.await {
                    eprintln!(
                        "stopping local pairing broadcast failed: {:?} ({:?})",
                        error.code, error.reason
                    );
                }
            })
            .detach();
        }
    }

    fn copy(&self, text: String, message_key: &str, window: &mut Window, cx: &mut Context<Self>) {
        cx.write_to_clipboard(ClipboardItem::new_string(text));
        let message = self.t(message_key, cx);
        window.push_notification(Notification::success(message), cx);
    }

    // ───────────────────────── 渲染 ─────────────────────────

    fn render_account_tab(&self, cx: &mut Context<Self>) -> gpui::AnyElement {
        let tokens = active_theme(cx).tokens().clone();
        let translator = self.host.read(cx).translator().read(cx).clone();
        let session = self.host.read(cx).controller.session().cloned();
        let logged_in = session.is_some();
        let body = match session {
            Some(session) => t_with(
                &translator,
                "addDeviceAccountSynced",
                &[("account", &session.user.email)],
            ),
            None => t(&translator, "addDeviceLoginRequired"),
        };
        v_flex()
            .w_full()
            .gap(tokens.spacing.md)
            .child(
                div()
                    .text_size(tokens.typography.sm.size)
                    .line_height(tokens.typography.sm.line_height)
                    .font_weight(FontWeight::MEDIUM)
                    .text_color(tokens.colors.foreground)
                    .child(body),
            )
            .child(field_hint(t(&translator, "addDeviceHint"), cx))
            .child(field_hint(t(&translator, "addDeviceAccountFooter"), cx))
            .when(!logged_in, |column| {
                column.child(
                    h_flex().w_full().justify_start().child(
                        Button::new("add-device-login")
                            .primary()
                            .label(t(&translator, "accountLogin"))
                            .control(cx)
                            .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                                let translator = this.host.read(cx).translator().clone();
                                let port = this.host.read(cx).port();
                                this.shutdown(cx);
                                window.close_dialog(cx);
                                crate::dialogs::login::open(translator, port, window, cx);
                            })),
                    ),
                )
            })
            .into_any_element()
    }

    fn render_own_code(&self, cx: &mut Context<Self>) -> gpui::AnyElement {
        let tokens = active_theme(cx).tokens().clone();
        let extended = active_theme(cx).extended().clone();
        let translator = self.host.read(cx).translator().read(cx).clone();
        let lan_enabled = self.host.read(cx).controller.lan_enabled();
        let mut section = v_flex()
            .w_full()
            .gap(tokens.spacing.sm)
            .child(field_label(t(&translator, "localPairingMyCodeTitle"), cx))
            .child(field_hint(t(&translator, "localPairingMyCodeHint"), cx));
        if let Some(error) = self.own_code_error.clone() {
            section = section.child(field_error(error, cx));
        }
        if let Some(code) = &self.own_code {
            let remaining = seconds_until(code.expires_at_unix_ms, now_unix_ms());
            let status = if remaining == 0 {
                t(&translator, "localPairingMyCodeExpired")
            } else {
                t_with(
                    &translator,
                    "localDeviceCodeRemaining",
                    &[("seconds", &remaining.to_string())],
                )
            };
            let code_text = code.code.clone();
            section = section.child(
                h_flex()
                    .w_full()
                    .items_center()
                    .justify_between()
                    .gap(tokens.spacing.md)
                    .child(
                        v_flex()
                            .gap(tokens.spacing.xxs)
                            .child(
                                div()
                                    .text_size(extended.title.size * 1.6)
                                    .line_height(extended.title.line_height * 1.6)
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .font_features(tabular_numbers())
                                    .text_color(tokens.colors.foreground)
                                    .child(code.code.clone()),
                            )
                            .child(field_hint(status, cx)),
                    )
                    .child(
                        h_flex()
                            .gap(tokens.spacing.sm)
                            .child(
                                Button::new("add-device-copy-code")
                                    .outline()
                                    .label(self.t("localDeviceCodeCopy", cx))
                                    .control(cx)
                                    .on_click(cx.listener(
                                        move |this, _: &ClickEvent, window, cx| {
                                            this.copy(
                                                code_text.clone(),
                                                "localDeviceCodeCopied",
                                                window,
                                                cx,
                                            );
                                        },
                                    )),
                            )
                            .child(
                                Button::new("add-device-regenerate-code")
                                    .outline()
                                    .label(self.t("localPairingMyCodeRefresh", cx))
                                    .control(cx)
                                    .busy(self.own_code_busy)
                                    .disabled(self.own_code_busy)
                                    .on_click(cx.listener(|this, _: &ClickEvent, _, cx| {
                                        this.refresh_own_code(cx);
                                    })),
                            ),
                    ),
            );
            if needs_lan_hint(code, lan_enabled) {
                section = section.child(field_error(
                    t(&translator, "localPairingLanDisabledHint"),
                    cx,
                ));
            } else if !code.addresses.is_empty() {
                section = section
                    .child(field_label(t(&translator, "localPairingMyAddresses"), cx))
                    .children(code.addresses.iter().map(|address| {
                        div()
                            .text_size(tokens.typography.sm.size)
                            .line_height(tokens.typography.sm.line_height)
                            .font_features(tabular_numbers())
                            .text_color(tokens.colors.foreground)
                            .child(address.clone())
                    }))
                    .child(field_hint(t(&translator, "localDeviceAddressHint"), cx));
            } else {
                section = section.child(field_hint(t(&translator, "localPairingNoAddress"), cx));
            }
        } else if self.own_code_busy {
            section = section.child(field_hint(t(&translator, "localPairingDiscovering"), cx));
        }
        section.into_any_element()
    }

    fn render_discovery(&self, cx: &mut Context<Self>) -> gpui::AnyElement {
        let tokens = active_theme(cx).tokens().clone();
        let extended = active_theme(cx).extended().clone();
        let translator = self.host.read(cx).translator().read(cx).clone();
        let entries = {
            let host = self.host.read(cx);
            discovered_entries(
                host.controller.discovered(),
                host.controller.linked_devices(),
            )
        };
        let selectable = self.flow.can_begin();
        let mut list = v_flex().w_full().gap(tokens.spacing.xxs);
        if entries.is_empty() {
            list = list.child(field_hint(t(&translator, "localPairingNoDevices"), cx));
        }
        for (index, entry) in entries.into_iter().enumerate() {
            let selected = self.selected_address.as_deref() == Some(entry.address.as_str());
            let hover = extended.colors.row_hover;
            let subtitle = match &entry.platform {
                Some(platform) if !platform.is_empty() => format!("{platform} · {}", entry.address),
                _ => entry.address.clone(),
            };
            let picked = entry.clone();
            list = list.child(
                h_flex()
                    .id(("add-device-peer", index))
                    .w_full()
                    .items_center()
                    .justify_between()
                    .gap(tokens.spacing.md)
                    .px(tokens.spacing.sm)
                    .py(tokens.spacing.xs)
                    .rounded(tokens.radius.md)
                    .when(selected, |row| row.bg(tokens.colors.muted))
                    .when(selectable && !entry.paired, |row| {
                        row.cursor_pointer().hover(move |style| style.bg(hover))
                    })
                    .on_click(cx.listener(move |this, _: &ClickEvent, window, cx| {
                        this.select_peer(&picked, window, cx);
                    }))
                    .child(
                        v_flex()
                            .min_w_0()
                            .child(
                                div()
                                    .text_size(tokens.typography.sm.size)
                                    .line_height(tokens.typography.sm.line_height)
                                    .font_weight(FontWeight::MEDIUM)
                                    .text_color(tokens.colors.foreground)
                                    .child(entry.name.clone()),
                            )
                            .child(field_hint(subtitle, cx)),
                    )
                    .when(entry.paired, |row| {
                        row.child(field_hint(t(&translator, "localDevicePairedTag"), cx))
                    }),
            );
        }
        v_flex()
            .w_full()
            .gap(tokens.spacing.sm)
            .child(
                h_flex()
                    .w_full()
                    .items_center()
                    .justify_between()
                    .child(field_hint(t(&translator, "localPairingDiscovering"), cx))
                    .child(
                        Button::new("add-device-rescan")
                            .ghost()
                            .label(self.t("localPairingRetryScan", cx))
                            .control(cx)
                            .on_click(cx.listener(|this, _: &ClickEvent, _, cx| {
                                this.rescan(cx);
                            })),
                    ),
            )
            .child(list)
            .into_any_element()
    }

    fn render_pair_form(&self, cx: &mut Context<Self>) -> gpui::AnyElement {
        let tokens = active_theme(cx).tokens().clone();
        let busy = self.flow.is_busy();
        v_flex()
            .w_full()
            .gap(tokens.spacing.md)
            .child(self.render_discovery(cx))
            .child(form_field(
                self.t("localPairingManualAddress", cx),
                Input::new(&self.address_input).control(cx).w_full(),
                Some(self.t("localPairingAddressHint", cx)),
                cx,
            ))
            .child(form_field(
                self.t("localPairingCodeLabel", cx),
                input_with_action(
                    Input::new(&self.code_input).control(cx).w_full(),
                    Button::new("add-device-connect")
                        .primary()
                        .label(self.t("localPairingConnect", cx))
                        .control(cx)
                        .busy(busy)
                        .disabled(busy)
                        .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                            this.begin(window, cx);
                        })),
                    cx,
                ),
                Some(self.t("localPairingCodeHint", cx)),
                cx,
            ))
            .into_any_element()
    }

    fn render_verify(
        &self,
        sas: &str,
        peer_name: &str,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let tokens = active_theme(cx).tokens().clone();
        let extended = active_theme(cx).extended().clone();
        v_flex()
            .w_full()
            .gap(tokens.spacing.md)
            .child(field_label(self.t("localPairingSasTitle", cx), cx))
            .child(field_hint(self.t("localPairingSasHint", cx), cx))
            .child(
                div()
                    .w_full()
                    .flex()
                    .justify_center()
                    .py(tokens.spacing.md)
                    .rounded(tokens.radius.md)
                    .bg(tokens.colors.muted)
                    .text_size(extended.title.size * 2.)
                    .line_height(extended.title.line_height * 2.)
                    .font_weight(FontWeight::SEMIBOLD)
                    .font_features(tabular_numbers())
                    .text_color(tokens.colors.foreground)
                    .child(group_sas(sas)),
            )
            .child(field_hint(peer_name.to_owned(), cx))
            .child(
                h_flex()
                    .w_full()
                    .justify_end()
                    .gap(tokens.spacing.sm)
                    .child(
                        Button::new("add-device-sas-reject")
                            .outline()
                            .label(self.t("localPairingReject", cx))
                            .control(cx)
                            .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                                this.finish(false, window, cx);
                            })),
                    )
                    .child(
                        Button::new("add-device-sas-confirm")
                            .primary()
                            .label(self.t("localPairingConfirm", cx))
                            .control(cx)
                            .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                                this.finish(true, window, cx);
                            })),
                    ),
            )
            .into_any_element()
    }

    fn render_direct_tab(&self, cx: &mut Context<Self>) -> gpui::AnyElement {
        let tokens = active_theme(cx).tokens().clone();
        let translator = self.host.read(cx).translator().read(cx).clone();
        let pair_section = match &self.flow {
            PairFlow::Form | PairFlow::Beginning => self.render_pair_form(cx),
            PairFlow::Verify { sas, peer_name, .. } => self.render_verify(sas, peer_name, cx),
            PairFlow::Finishing { .. } => {
                field_hint(t(&translator, "localPairingWaitingPeer"), cx).into_any_element()
            }
            PairFlow::Done { device_name } => v_flex()
                .w_full()
                .gap(tokens.spacing.md)
                .child(
                    div()
                        .text_size(tokens.typography.sm.size)
                        .line_height(tokens.typography.sm.line_height)
                        .font_weight(FontWeight::MEDIUM)
                        .text_color(tokens.colors.foreground)
                        .child(t_with(
                            &translator,
                            "localPairingPaired",
                            &[("device", device_name)],
                        )),
                )
                .child(
                    h_flex().w_full().justify_end().child(
                        Button::new("add-device-done")
                            .primary()
                            .label(self.t("close", cx))
                            .control(cx)
                            .on_click(cx.listener(|this, _: &ClickEvent, window, cx| {
                                this.shutdown(cx);
                                window.close_dialog(cx);
                            })),
                    ),
                )
                .into_any_element(),
        };
        v_flex()
            .w_full()
            .gap(tokens.spacing.lg)
            .child(field_hint(t(&translator, "localPairingHint"), cx))
            .child(self.render_own_code(cx))
            .child(
                div()
                    .w_full()
                    .h(active_theme(cx).extended().stroke.thin)
                    .bg(active_theme(cx).extended().colors.hairline),
            )
            .child(pair_section)
            .when_some(self.error.clone(), |column, error| {
                column.child(field_error(error, cx))
            })
            .into_any_element()
    }
}

impl Render for AddDeviceDialog {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let tokens = active_theme(cx).tokens().clone();
        let labels = [
            self.t("addDeviceTabAccount", cx),
            self.t("addDeviceTabLocal", cx),
        ];
        let selected = usize::from(self.tab == Tab::Direct);
        let view = cx.entity();
        let body = match self.tab {
            Tab::Account => self.render_account_tab(cx),
            Tab::Direct => self.render_direct_tab(cx),
        };
        v_flex()
            .w_full()
            .min_h_0()
            .gap(tokens.spacing.lg)
            .child(segmented_tabs(
                "add-device-tabs",
                labels,
                selected,
                move |index, _, cx| {
                    let tab = if index == 0 {
                        Tab::Account
                    } else {
                        Tab::Direct
                    };
                    view.update(cx, |this, cx| this.select_tab(tab, cx));
                },
                cx,
            ))
            .child(dialog_scroll_body(
                "add-device-body",
                Some(px(460.)),
                body,
                cx,
            ))
    }
}
