//! 云端推送的两个小对话框：隐私设置（上报哪些可选字段）与开启上报的首次同意。

use fluxdown_ui_components::{
    ControlExt as _, DialogIntent, dialog_footer, dialog_title, option_group, option_row,
};
use fluxdown_ui_i18n::Translator;
use fluxdown_ui_theme::active_theme;
use gpui::{
    App, AppContext as _, Context, Entity, IntoElement, ParentElement, Render, SharedString,
    Styled, Window,
};
use gpui_component::{
    Disableable as _, WindowExt as _,
    button::{Button, ButtonVariants as _},
    h_flex,
    switch::Switch,
    v_flex,
};

use crate::store::SettingsStore;
use crate::ui::meta_text;

/// 隐私对话框宽度档位（100% 字号基准，经 `text_extent` 缩放）。
const PRIVACY_DIALOG_WIDTH: f32 = 460.;

/// 隐私设置：两个即时生效的开关（下载地址 / 保存位置，设备本地、默认关）。
struct PrivacyDialog {
    store: Entity<SettingsStore>,
    translator: Translator,
}

impl PrivacyDialog {
    fn new(store: Entity<SettingsStore>, translator: Translator, cx: &mut Context<Self>) -> Self {
        cx.observe(&store, |_, _, cx| cx.notify()).detach();
        Self { store, translator }
    }

    fn t(&self, key: &str) -> SharedString {
        SharedString::from(self.translator.text(key).to_owned())
    }
}

impl Render for PrivacyDialog {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let tokens = active_theme(cx).tokens().clone();
        let state = self.store.read(cx).cloud_notify().clone();
        let read_only = self.store.read(cx).is_read_only();
        let busy = self.store.read(cx).is_busy("cloudPrivacy");
        let disabled = read_only || busy;

        let url_switch = Switch::new("cloud-privacy-url")
            .checked(state.include_url)
            .disabled(disabled)
            .on_click({
                let store = self.store.clone();
                move |checked: &bool, _, cx| {
                    let checked = *checked;
                    store.update(cx, |store, cx| {
                        let include_save_dir = store.cloud_notify().include_save_dir;
                        store.set_cloud_privacy(checked, include_save_dir, cx);
                    });
                }
            });
        let dir_switch = Switch::new("cloud-privacy-save-dir")
            .checked(state.include_save_dir)
            .disabled(disabled)
            .on_click({
                let store = self.store.clone();
                move |checked: &bool, _, cx| {
                    let checked = *checked;
                    store.update(cx, |store, cx| {
                        let include_url = store.cloud_notify().include_url;
                        store.set_cloud_privacy(include_url, checked, cx);
                    });
                }
            });

        v_flex()
            .w_full()
            .gap(tokens.spacing.md)
            .child(meta_text(cx).child(self.t("cloudNotifyPrivacyDesc")))
            .child(option_group(
                [
                    option_row(self.t("cloudNotifyIncludeUrl"), None, url_switch, cx)
                        .into_any_element(),
                    option_row(self.t("cloudNotifyIncludeSaveDir"), None, dir_switch, cx)
                        .into_any_element(),
                ],
                cx,
            ))
            .child(
                h_flex().w_full().justify_end().child(
                    Button::new("cloud-privacy-close")
                        .primary()
                        .label(self.t("close"))
                        .control(cx)
                        .on_click(|_, window, cx| window.close_dialog(cx)),
                ),
            )
    }
}

/// 打开隐私设置对话框。
pub(crate) fn open_privacy(
    store: Entity<SettingsStore>,
    translator: Translator,
    window: &mut Window,
    cx: &mut App,
) {
    let title = SharedString::from(translator.text("cloudNotifyPrivacyTitle").to_owned());
    let view = cx.new(|cx| PrivacyDialog::new(store, translator, cx));
    window.open_dialog(cx, move |dialog, _, cx| {
        let view = view.clone();
        dialog
            .title(dialog_title(title.clone(), cx))
            .w(active_theme(cx).text_extent(PRIVACY_DIALOG_WIDTH))
            .content(move |content, _, _| content.min_h_0().child(view.clone()))
    });
}

/// 开启本设备上报前的显式同意：确认才真正打开，取消 / 关闭不改任何状态。
pub(crate) fn open_consent(
    store: Entity<SettingsStore>,
    translator: Translator,
    window: &mut Window,
    cx: &mut App,
) {
    let title = SharedString::from(translator.text("cloudNotifyConsentTitle").to_owned());
    let description = SharedString::from(translator.text("cloudNotifyConsentDesc").to_owned());
    let ok = SharedString::from(translator.text("cloudNotifyConsentConfirm").to_owned());
    let cancel = SharedString::from(translator.text("cancel").to_owned());
    window.open_alert_dialog(cx, move |alert, _, cx| {
        let store = store.clone();
        alert
            .title(dialog_title(title.clone(), cx))
            .description(description.clone())
            .footer(dialog_footer(
                Some(cancel.clone()),
                ok.clone(),
                DialogIntent::Confirm,
                cx,
            ))
            .on_ok(move |_, _, cx| {
                store.update(cx, |store, cx| store.set_cloud_reporting(true, cx));
                true
            })
    });
}
