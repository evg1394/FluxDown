//! 独立下载进度 / 完成窗口的装配：把会话里的任务状态喂给
//! [`ProgressWindowTracker`]，按其决策开关 `WindowKey::Progress(task_id)` 窗口。
//!
//! 开窗意图只来自下载能力的 `on_user_started` 回调（单任务新建 / 继续 / 重新下载成功），
//! 以及无主窗口时新建下载表单的直接提交（见 `windows::new_download`）。
//!
//! 意图登记时任务往往还在排队（命令响应先于活跃态事件到达）。若此刻界面没有任何窗口
//! （由外部捕获拉起、表单提交后即关闭的界面进程），意图未兑现前经 `lifecycle::keep_alive`
//! 推迟界面退出，否则进程会在进度窗口打开前退出；最长等待 [`ARMED_KEEP_ALIVE`]。

use std::{cell::RefCell, collections::HashMap, rc::Rc, time::Duration};

use fluxdown_protocol::{AgentEvent, AgentSnapshot, DaemonEvent, ServiceEvent, WsServerMsg};
use fluxdown_ui_downloads::{ProgressWindowEffect, ProgressWindowPrefs, ProgressWindowTracker};
use futures_util::future::select;
use gpui::{App, AppContext as _, Global};
use tokio::sync::oneshot;

use crate::{
    app::Desktop,
    session::{SessionSignal, agent_body},
    windows::{WindowKey, WindowRegistry},
};

/// 无窗口时为等待开窗而保持界面进程的上限（任务长时间排队则放弃开窗）。
const ARMED_KEEP_ALIVE: Duration = Duration::from_secs(60);

#[derive(Default)]
struct ProgressWindows {
    tracker: ProgressWindowTracker,
    /// 仍在等待兑现的开始意图：兑现或丢弃时发送，结束对应的界面保活。
    waiters: HashMap<String, oneshot::Sender<()>>,
}

impl Global for ProgressWindows {}

/// 订阅会话；应用启动时装一次，不依赖主窗口存在。
pub fn install(cx: &mut App) {
    cx.set_global(ProgressWindows::default());
    let session = Desktop::global(cx).session.clone();
    cx.subscribe(&session, |_, signal, cx| match signal {
        SessionSignal::Snapshot(snapshot) => {
            if let Some(body) = agent_body(snapshot) {
                observe_all(body, cx);
            }
        }
        SessionSignal::Event(frame) => on_event(&frame.event, cx),
        SessionSignal::Stale | SessionSignal::Fatal(_) | SessionSignal::ServiceStopped => {}
    })
    .detach();
}

/// 用户在场亲手开始了一个任务。状态事件可能早于命令响应到达，登记后按会话当前状态补判一次。
pub fn user_started(task_id: String, cx: &mut App) {
    cx.global_mut::<ProgressWindows>().tracker.arm(&task_id);
    let status = Desktop::global(cx)
        .session
        .read(cx)
        .agent_snapshot()
        .and_then(|body| {
            body.daemon
                .tasks
                .iter()
                .find(|task| task.task_id == task_id)
                .map(|task| task.status)
        });
    if let Some(status) = status.filter(|status| is_fresh_status(*status)) {
        observe(&task_id, status, cx);
    }
    hold_ui_until_resolved(task_id, cx);
}

/// 登记前的会话快照只有「正在运行」类状态可信：命令刚成功，暂停 / 失败 / 完成多半是
/// 开始之前的旧状态，按它补判会把刚登记的意图立刻撤销或误弹完成窗口。
fn is_fresh_status(status: i32) -> bool {
    // 与下载能力的状态码一致：1 下载中、5 准备中。
    matches!(status, 1 | 5)
}

/// agent 为静默建成的任务拉起界面（`--progress-task`）：首个快照到达后按用户开始处理。
pub fn user_started_on_launch(task_id: String, cx: &mut App) {
    let session = Desktop::global(cx).session.clone();
    if session.read(cx).latest().is_some() {
        user_started(task_id, cx);
        return;
    }
    let subscription = Rc::new(RefCell::new(None));
    let holder = Rc::clone(&subscription);
    let mut pending = Some(task_id);
    *subscription.borrow_mut() = Some(cx.subscribe(&session, move |_, signal, cx| {
        if matches!(signal, SessionSignal::Snapshot(_))
            && let Some(task_id) = pending.take()
        {
            user_started(task_id, cx);
            holder.borrow_mut().take();
        }
    }));
}

/// 意图尚未兑现时保持界面进程，直到开窗 / 意图丢弃或超时。
fn hold_ui_until_resolved(task_id: String, cx: &mut App) {
    let windows = cx.global_mut::<ProgressWindows>();
    if !windows.tracker.is_armed(&task_id) {
        return;
    }
    let (resolved, wait) = oneshot::channel();
    if let Some(previous) = windows.waiters.insert(task_id, resolved)
        && previous.send(()).is_err()
    {
        log::trace!("previous progress-window keepalive has already ended");
    }
    let timeout = Box::pin(cx.background_executor().timer(ARMED_KEEP_ALIVE));
    let task = cx.background_spawn(async move {
        match select(wait, timeout).await {
            futures_util::future::Either::Left((result, _timeout)) => {
                if result.is_err() {
                    log::debug!("progress-window keepalive owner released");
                }
            }
            futures_util::future::Either::Right(((), _wait)) => {
                log::trace!("progress-window keepalive deadline reached");
            }
        }
    });
    crate::lifecycle::keep_alive(cx, task).detach();
}

/// 意图已兑现或被丢弃：结束对应的界面保活。
fn release_if_resolved(task_id: &str, cx: &mut App) {
    let windows = cx.global_mut::<ProgressWindows>();
    if windows.tracker.is_armed(task_id) {
        return;
    }
    if let Some(resolved) = windows.waiters.remove(task_id)
        && resolved.send(()).is_err()
    {
        log::trace!("progress-window keepalive has already ended");
    }
}

fn prefs(cx: &App) -> ProgressWindowPrefs {
    ProgressWindowPrefs::from_preferences(Desktop::preferences(cx))
}

fn on_event(event: &ServiceEvent, cx: &mut App) {
    let ServiceEvent::Agent(event) = event else {
        return;
    };
    match event {
        AgentEvent::Daemon(DaemonEvent::TaskChanged(task)) => {
            observe(&task.task_id, task.status, cx);
        }
        AgentEvent::Daemon(DaemonEvent::Engine(WsServerMsg::TaskProgress {
            task_id,
            status,
            ..
        })) => observe(task_id, *status, cx),
        // 静默捕获直接建成的任务：与表单新建同一规则，只有单个任务才弹窗。
        AgentEvent::CaptureTasksStarted(task_ids) => {
            if let [task_id] = task_ids.as_slice() {
                user_started(task_id.clone(), cx);
            }
        }
        AgentEvent::Daemon(DaemonEvent::TaskDeleted { task_id }) => {
            cx.global_mut::<ProgressWindows>().tracker.forget(task_id);
            release_if_resolved(task_id, cx);
        }
        AgentEvent::DaemonSnapshotReplaced(_)
        | AgentEvent::Daemon(
            DaemonEvent::SnapshotReplaced(_)
            | DaemonEvent::Engine(WsServerMsg::TasksSnapshot { .. }),
        ) => {
            // 事件已并入会话快照：按折叠后的完整任务表补判。
            let session = Desktop::global(cx).session.clone();
            let statuses: Vec<(String, i32)> = session
                .read(cx)
                .agent_snapshot()
                .map(|body| {
                    body.daemon
                        .tasks
                        .iter()
                        .map(|task| (task.task_id.clone(), task.status))
                        .collect()
                })
                .unwrap_or_default();
            for (task_id, status) in statuses {
                observe(&task_id, status, cx);
            }
        }
        _ => {}
    }
}

fn observe_all(body: &AgentSnapshot, cx: &mut App) {
    for task in &body.daemon.tasks {
        observe(&task.task_id, task.status, cx);
    }
}

fn observe(task_id: &str, status: i32, cx: &mut App) {
    let key = WindowKey::Progress(task_id.to_owned());
    let window_open = WindowRegistry::is_open(cx, &key);
    let prefs = prefs(cx);
    let effect =
        cx.global_mut::<ProgressWindows>()
            .tracker
            .observe(task_id, status, window_open, prefs);
    release_if_resolved(task_id, cx);
    match effect {
        Some(ProgressWindowEffect::OpenProgress) => {
            crate::windows::progress::open(cx, task_id.to_owned(), true);
        }
        Some(ProgressWindowEffect::OpenCompletion) => {
            crate::windows::progress::open(cx, task_id.to_owned(), false);
        }
        Some(ProgressWindowEffect::Close) => WindowRegistry::close(cx, &key),
        None => {}
    }
}

#[cfg(test)]
mod tests {
    use super::is_fresh_status;

    #[test]
    fn stale_inactive_statuses_do_not_resolve_a_fresh_intent() {
        assert!(is_fresh_status(1) && is_fresh_status(5));
        assert!(!is_fresh_status(2) && !is_fresh_status(4) && !is_fresh_status(3));
    }
}
