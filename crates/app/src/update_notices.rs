//! 应用内更新提示：菜单「检查更新…」的结果通知，以及 agent 推送的更新状态变化提示。
//!
//! 状态唯一来源是 agent（`AgentSnapshot.update` / `UpdateChanged`）；这里只投影成一次性通知：
//! 同一版本同一类提示每次运行只弹一次，承载窗口尚不存在时暂存、主窗口就绪后补弹。

use std::collections::HashSet;

use fluxdown_protocol::{
    AgentEvent, ApplicationErrorCode, ServiceEvent, UpdatePhase, UpdateStatusDto, method,
};
use fluxdown_ui_settings::update_view;
use gpui::{App, Global, SharedString};
use gpui_component::{
    WindowExt as _,
    button::{Button, ButtonVariants as _},
    notification::Notification,
};

use crate::{
    app::Desktop,
    session::{SessionSignal, agent_body},
    windows::WindowRegistry,
};

/// 通知上的操作按钮。
#[derive(Clone, Debug, PartialEq, Eq)]
enum NoticeAction {
    /// 「更新并重启」→ `agent.update.install`。
    Install,
    /// 「前往网站下载」→ 打开地址。
    OpenUrl(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Notice {
    message: String,
    error: bool,
    action: Option<NoticeAction>,
}

/// 观察者对一次状态变化给出的提示类别。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Trigger {
    /// 更新包已就绪，等待重启。
    Ready,
    /// 后台发现了只能手动升级的新版本。
    ManualAvailable,
    /// 用户已点「更新并重启」后失败。
    InstallFailed,
}

#[derive(Default)]
struct UpdateNotices {
    /// 本次运行已提示过的 `类别:版本`。
    notified: HashSet<String>,
    /// 上一份状态里「用户已要求安装」（正在安装，或下载完成后立即安装）。
    install_in_flight: bool,
    /// 菜单发起的检查进行中：其结果由调用方直接提示，观察者不重复弹手动升级提示。
    explicit_check: bool,
    /// 承载窗口尚不存在时暂存的提示。
    pending: Vec<Notice>,
}

impl Global for UpdateNotices {}

/// 用户是否已要求安装且尚未完成。
fn install_in_flight(status: &UpdateStatusDto) -> bool {
    match status.phase {
        UpdatePhase::Installing => true,
        UpdatePhase::Downloading => status.install_pending,
        _ => false,
    }
}

/// 状态 → 是否需要提示；`previous_in_flight` 是上一份状态的安装进行标记。
fn classify(
    previous_in_flight: bool,
    status: &UpdateStatusDto,
    explicit_check: bool,
) -> Option<Trigger> {
    match status.phase {
        UpdatePhase::Ready if status.has_update => Some(Trigger::Ready),
        UpdatePhase::Available
            if status.has_update && status.manual_reason.is_some() && !explicit_check =>
        {
            Some(Trigger::ManualAvailable)
        }
        UpdatePhase::Failed if previous_in_flight => Some(Trigger::InstallFailed),
        _ => None,
    }
}

/// 订阅会话：快照与 `UpdateChanged` 都走同一判定，并补弹启动前就已就绪的更新。
pub(crate) fn install(cx: &mut App) {
    cx.set_global(UpdateNotices::default());
    let session = Desktop::global(cx).session.clone();
    if let Some(status) = session
        .read(cx)
        .agent_snapshot()
        .map(|body| body.update.clone())
    {
        observe(&status, cx);
    }
    cx.subscribe(&session, |_, signal, cx| match signal {
        SessionSignal::Snapshot(snapshot) => {
            if let Some(body) = agent_body(snapshot) {
                observe(&body.update, cx);
            }
        }
        SessionSignal::Event(frame) => {
            if let ServiceEvent::Agent(AgentEvent::UpdateChanged(status)) = &frame.event {
                observe(status, cx);
            }
        }
        SessionSignal::Stale | SessionSignal::Fatal(_) | SessionSignal::ServiceStopped => {}
    })
    .detach();
}

fn observe(status: &UpdateStatusDto, cx: &mut App) {
    let state = cx.global_mut::<UpdateNotices>();
    let previous = std::mem::replace(&mut state.install_in_flight, install_in_flight(status));
    let Some(trigger) = classify(previous, status, state.explicit_check) else {
        return;
    };
    // 失败提示对应用户的一次具体操作，每次都弹；其余按版本去重。
    if trigger != Trigger::InstallFailed
        && !state
            .notified
            .insert(format!("{trigger:?}:{}", status.latest_version))
    {
        return;
    }
    let translator = Desktop::global(cx).translator.read(cx).clone();
    let notice = match trigger {
        Trigger::Ready => Notice {
            message: translator.text_with("updateReadyToast", &[("v", &status.latest_version)]),
            error: false,
            action: Some(NoticeAction::Install),
        },
        Trigger::ManualAvailable => Notice {
            message: translator.text_with("newVersionFound", &[("v", &status.latest_version)]),
            error: false,
            action: update_view::manual_url(status)
                .map(|url| NoticeAction::OpenUrl(url.to_owned())),
        },
        Trigger::InstallFailed => Notice {
            message: translator
                .text(update_view::failure_key(status.failure))
                .to_owned(),
            error: true,
            action: None,
        },
    };
    show(notice, cx);
}

/// 菜单「检查更新…」：检查后按结果提示（可一键更新 / 手动升级 / 已是最新 / 失败）。
pub(crate) fn check_now(cx: &mut App) {
    cx.global_mut::<UpdateNotices>().explicit_check = true;
    let future = Desktop::global(cx)
        .client
        .call::<serde_json::Value, UpdateStatusDto>(
            method::AGENT_UPDATE_CHECK,
            Some(serde_json::json!({})),
        );
    cx.spawn(async move |cx| {
        let result = future.await;
        cx.update(|cx| {
            cx.global_mut::<UpdateNotices>().explicit_check = false;
            let translator = Desktop::global(cx).translator.read(cx).clone();
            let notice = match result {
                Ok(status) if status.has_update && status.manual_reason.is_none() => Notice {
                    message: translator
                        .text_with("newVersionFound", &[("v", &status.latest_version)]),
                    error: false,
                    action: Some(NoticeAction::Install),
                },
                Ok(status) if status.has_update => Notice {
                    message: update_view::manual_line(&status)
                        .map(|line| line.text(&translator))
                        .unwrap_or_else(|| {
                            translator
                                .text_with("newVersionFound", &[("v", &status.latest_version)])
                        }),
                    error: false,
                    action: update_view::manual_url(&status)
                        .map(|url| NoticeAction::OpenUrl(url.to_owned())),
                },
                Ok(status) if status.phase == UpdatePhase::Failed => Notice {
                    message: translator
                        .text(update_view::failure_key(status.failure))
                        .to_owned(),
                    error: true,
                    action: None,
                },
                Ok(_) => Notice {
                    message: translator.text("upToDate").to_owned(),
                    error: false,
                    action: None,
                },
                Err(_) => Notice {
                    message: translator.text("localServiceActionFailed").to_owned(),
                    error: true,
                    action: None,
                },
            };
            show(notice, cx);
        });
    })
    .detach();
}

/// `--after-update` 启动完成：提示已更新到当前版本。
pub(crate) fn show_installed(cx: &mut App) {
    let message = Desktop::global(cx).translator.read(cx).text_with(
        "updateInstalledToast",
        &[("v", fluxdown_protocol::APP_VERSION)],
    );
    show(
        Notice {
            message,
            error: false,
            action: None,
        },
        cx,
    );
}

/// 主窗口就绪后补弹窗口尚不存在时暂存的提示。
pub(crate) fn replay_pending(cx: &mut App) {
    let pending = std::mem::take(&mut cx.global_mut::<UpdateNotices>().pending);
    for notice in pending {
        show(notice, cx);
    }
}

/// 「更新并重启」：包未就绪时 agent 先下载。成功后 agent 整体重启，连接随 `service-quit`
/// 关闭（`Unavailable` 属预期）；其他拒绝（如不支持）才提示失败。后续失败由观察者经
/// `UpdateChanged` 提示。
fn request_install(cx: &mut App) {
    let future = Desktop::global(cx)
        .client
        .call::<serde_json::Value, UpdateStatusDto>(
            method::AGENT_UPDATE_INSTALL,
            Some(serde_json::json!({})),
        );
    cx.spawn(async move |cx| match future.await {
        Ok(_) => {}
        Err(error) if error.code == ApplicationErrorCode::Unavailable => {
            log::debug!("agent connection closed while installing the update (expected)");
        }
        Err(_) => cx.update(|cx| {
            let message = Desktop::global(cx)
                .translator
                .read(cx)
                .text("localServiceActionFailed")
                .to_owned();
            show(
                Notice {
                    message,
                    error: true,
                    action: None,
                },
                cx,
            );
        }),
    })
    .detach();
}

fn show(notice: Notice, cx: &mut App) {
    let Some(handle) = WindowRegistry::overlay_window(cx) else {
        cx.global_mut::<UpdateNotices>().pending.push(notice);
        return;
    };
    let translator = Desktop::global(cx).translator.read(cx).clone();
    let built = notice.clone();
    let shown = handle
        .update(cx, |_, window, cx| {
            let mut note = if built.error {
                Notification::error(built.message)
            } else {
                Notification::info(built.message)
            };
            if let Some(action) = built.action {
                let label = SharedString::from(match &action {
                    NoticeAction::Install => translator.text("updateRestartNow").to_owned(),
                    NoticeAction::OpenUrl(_) => translator.text("updateFailedOpenSite").to_owned(),
                });
                note = note.action(move |_, _, _| {
                    let action = action.clone();
                    Button::new("update-notice-action")
                        .primary()
                        .label(label.clone())
                        .on_click(move |_, _, cx| match &action {
                            NoticeAction::Install => request_install(cx),
                            NoticeAction::OpenUrl(url) => cx.open_url(url),
                        })
                });
            }
            window.push_notification(note, cx);
        })
        .is_ok();
    if !shown {
        cx.global_mut::<UpdateNotices>().pending.push(notice);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn status(phase: UpdatePhase) -> UpdateStatusDto {
        UpdateStatusDto {
            phase,
            latest_version: "2.0.0".to_owned(),
            has_update: true,
            ..UpdateStatusDto::default()
        }
    }

    #[test]
    fn ready_always_notifies() {
        assert_eq!(
            classify(false, &status(UpdatePhase::Ready), false),
            Some(Trigger::Ready)
        );
    }

    #[test]
    fn manual_available_is_suppressed_during_explicit_check() {
        let mut manual = status(UpdatePhase::Available);
        manual.manual_reason = Some(fluxdown_protocol::UpdateManualReason::NoAsset);
        assert_eq!(
            classify(false, &manual, false),
            Some(Trigger::ManualAvailable)
        );
        assert_eq!(classify(false, &manual, true), None);
        assert_eq!(
            classify(false, &status(UpdatePhase::Available), false),
            None
        );
    }

    #[test]
    fn failure_only_notifies_after_an_install_request() {
        let failed = status(UpdatePhase::Failed);
        assert_eq!(classify(false, &failed, false), None);
        assert_eq!(classify(true, &failed, false), Some(Trigger::InstallFailed));
        let mut pending = status(UpdatePhase::Downloading);
        assert!(!install_in_flight(&pending));
        pending.install_pending = true;
        assert!(install_in_flight(&pending));
        assert!(install_in_flight(&status(UpdatePhase::Installing)));
    }
}
