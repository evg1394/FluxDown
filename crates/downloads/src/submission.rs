//! 「新建下载」表单确认后的提交：本机建任务 / 外部捕获确认 / 种子上传，或把链接下发到其他设备。
//!
//! 纯规则（命令拆分、结果汇总、提示文案）集中在这里，主窗口下载页与无主窗口时的宿主直提交
//! 共用同一条路径，成功 / 失败提示不会因入口不同而分叉。

use std::path::PathBuf;

use fluxdown_protocol::{
    CaptureResolveParams, CreateTaskRequest, LinkDispatchParams, RemoteDispatchParams,
};
use fluxdown_ui_i18n::Translator;

use crate::{
    controller::{
        DownloadsCommand, DownloadsResult, LAST_DOWNLOAD_TARGET_PREF, LAST_SAVE_DIR_PREF,
        PortFuture,
    },
    model::{devices::DispatchTarget, dispatch::DispatchSummary},
    strings::{NewDownloadStrings, error_text},
};

/// 外部捕获条目的确认：事务 id + 表单产出的建任务参数。
#[derive(Clone, Debug)]
pub struct CapturedTask {
    pub transaction_id: String,
    pub request: CreateTaskRequest,
}

/// 下发到其他设备的一条链接。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RemoteItem {
    pub url: String,
    /// 空 / `None` = 由目标设备按 URL 推断。
    pub file_name: Option<String>,
}

/// 下发到其他设备的提交内容：目标只接收链接 / 文件名 / 保存目录，Cookie、请求头、代理、
/// 分段、队列等只对本机有意义的选项不随下发。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RemoteSubmission {
    /// 必须是云设备或已配对设备（`Local` 走本机提交）。
    pub target: DispatchTarget,
    /// 提示文案用的目标显示名。
    pub device_label: String,
    /// 云设备当前离线（下发被云端排队，上线后才执行）。
    pub target_offline: bool,
    pub items: Vec<RemoteItem>,
    /// 目标设备上的保存目录；`None` = 目标设备默认目录。
    pub save_dir: Option<String>,
    /// 已随本次下发处理的外部捕获事务：链接经下发送达，agent 里对应的待确认捕获据此忽略清除。
    pub captures: Vec<String>,
}

/// 表单里与种子文件相关的提交选项（种子文件本身不带这些）。空串 = 由 agent 用默认值。
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TorrentFileOptions {
    pub save_dir: String,
    pub queue_id: String,
    pub start_paused: bool,
}

/// 表单确认后的提交内容。
#[derive(Clone, Debug)]
pub enum NewDownloadSubmission {
    /// 每条链接一个请求，共享表单选项：普通链接逐条 `daemon.task.create`，
    /// 外部捕获条目逐条 `agent.capture.resolve` 确认。
    Tasks {
        tasks: Vec<CreateTaskRequest>,
        captures: Vec<CapturedTask>,
    },
    /// 预解析清单确认后只建一个组；捕获事务由 agent 合并并消费。
    Group {
        request: Box<fluxdown_protocol::CreateGroupRequest>,
        context: Box<CreateTaskRequest>,
        transaction_id: Option<String>,
    },
    /// 本机 `.torrent` 文件，交给 agent 读取上传；用户主动打开，走 BT 文件选择，
    /// 并带上表单当前的保存目录 / 队列 / 开始状态。
    TorrentFiles {
        paths: Vec<PathBuf>,
        options: TorrentFileOptions,
    },
    /// 逐条下发到云账号其他设备 / 已配对设备。
    Remote(RemoteSubmission),
}

/// 一次提交拆出的命令：主命令逐条执行并计入结果，尽力而为的命令失败不影响结果。
struct SubmissionPlan {
    commands: Vec<DownloadsCommand>,
    best_effort: Vec<DownloadsCommand>,
    remote: Option<RemoteInfo>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct RemoteInfo {
    device: String,
    offline: bool,
}

impl NewDownloadSubmission {
    /// 提交后任务立即开始（非「稍后下载」）；本机种子文件由 agent 直接开始，下发的任务
    /// 由目标设备接单开始。
    #[must_use]
    pub fn starts_immediately(&self) -> bool {
        match self {
            Self::Tasks { tasks, captures } => tasks
                .iter()
                .chain(captures.iter().map(|capture| &capture.request))
                .all(|request| !request.start_paused),
            Self::Group { request, .. } => !request.start_paused,
            Self::TorrentFiles { options, .. } => !options.start_paused,
            Self::Remote(_) => true,
        }
    }

    fn target(&self) -> DispatchTarget {
        match self {
            Self::Remote(remote) => remote.target.clone(),
            Self::Tasks { .. } | Self::Group { .. } | Self::TorrentFiles { .. } => {
                DispatchTarget::Local
            }
        }
    }

    /// 「上次保存目录」偏好写入：只记本机目录（远端目录属于目标设备，不能污染本机默认）。
    fn remember_save_dir_command(&self) -> Option<DownloadsCommand> {
        let save_dir = match self {
            Self::Tasks { tasks, captures } => tasks
                .first()
                .or_else(|| captures.first().map(|capture| &capture.request))?
                .save_dir
                .clone(),
            Self::Group { request, .. } => request.save_dir.clone(),
            _ => return None,
        };
        Some(DownloadsCommand::SetLocalPreference {
            key: LAST_SAVE_DIR_PREF,
            value: serde_json::Value::String(save_dir),
        })
    }

    fn plan(self) -> SubmissionPlan {
        let mut best_effort = Vec::new();
        best_effort.extend(self.remember_save_dir_command());
        best_effort.push(DownloadsCommand::SetLocalPreference {
            key: LAST_DOWNLOAD_TARGET_PREF,
            value: serde_json::Value::String(self.target().to_pref()),
        });
        match self {
            Self::Tasks { tasks, captures } => SubmissionPlan {
                commands: tasks
                    .into_iter()
                    .map(|request| {
                        DownloadsCommand::Create(Box::new(
                            fluxdown_protocol::DaemonCreateTaskParams {
                                request,
                                torrent_blob_id: None,
                                unattended: false,
                                hint_file_size: None,
                            },
                        ))
                    })
                    .chain(captures.into_iter().map(|capture| {
                        DownloadsCommand::CaptureResolve(Box::new(CaptureResolveParams {
                            transaction_id: capture.transaction_id,
                            accepted: true,
                            request: Some(capture.request),
                        }))
                    }))
                    .collect(),
                best_effort,
                remote: None,
            },
            Self::Group {
                request,
                context,
                transaction_id,
            } => SubmissionPlan {
                commands: vec![DownloadsCommand::CreateGroup {
                    request,
                    context,
                    transaction_id,
                }],
                best_effort,
                remote: None,
            },
            Self::TorrentFiles { paths, options } => SubmissionPlan {
                commands: paths
                    .iter()
                    .map(|path| DownloadsCommand::SubmitTorrentFile {
                        path: path.display().to_string(),
                        silent: false,
                        save_dir: Some(options.save_dir.clone()).filter(|dir| !dir.is_empty()),
                        queue_id: Some(options.queue_id.clone()).filter(|id| !id.is_empty()),
                        start_paused: Some(options.start_paused),
                    })
                    .collect(),
                best_effort,
                remote: None,
            },
            Self::Remote(remote) => {
                let RemoteSubmission {
                    target,
                    device_label,
                    target_offline,
                    items,
                    save_dir,
                    captures,
                } = remote;
                let commands = items
                    .into_iter()
                    .filter_map(|item| match &target {
                        DispatchTarget::Cloud(device_id) => {
                            Some(DownloadsCommand::RemoteDispatch(RemoteDispatchParams {
                                to_device: device_id.clone(),
                                url: item.url,
                                file_name: item.file_name,
                                save_dir: save_dir.clone(),
                            }))
                        }
                        DispatchTarget::Paired(fingerprint) => {
                            Some(DownloadsCommand::LinkDispatch(LinkDispatchParams {
                                fingerprint: fingerprint.clone(),
                                url: item.url,
                                file_name: item.file_name,
                                save_dir: save_dir.clone(),
                            }))
                        }
                        DispatchTarget::Local => None,
                    })
                    .collect();
                // 捕获链接已经下发：忽略 agent 里对应的待确认事务，避免悬挂到超时。
                best_effort.extend(captures.into_iter().map(|transaction_id| {
                    DownloadsCommand::CaptureResolve(Box::new(CaptureResolveParams {
                        transaction_id,
                        accepted: false,
                        request: None,
                    }))
                }));
                SubmissionPlan {
                    commands,
                    best_effort,
                    remote: Some(RemoteInfo {
                        device: device_label,
                        offline: target_offline,
                    }),
                }
            }
        }
    }
}

/// 建任务类命令返回的 `{taskId}` / `{taskIds}` 才是本机新任务 ID；下发命令返回的是目标
/// 设备上的任务，不能当作本机任务（会误弹进度窗口）。
fn creates_local_task(command: &DownloadsCommand) -> bool {
    matches!(
        command,
        DownloadsCommand::Create(_)
            | DownloadsCommand::CaptureResolve(_)
            | DownloadsCommand::SubmitTorrentFile { .. }
    )
}

/// 提交结果汇总。
#[derive(Debug)]
pub struct SubmissionReport {
    created_task_ids: Vec<String>,
    summary: DispatchSummary,
    remote: Option<RemoteInfo>,
}

/// 提交完成后给用户的提示。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SubmitNotice {
    /// `false` = 至少一条失败（用错误样式展示）。
    pub ok: bool,
    pub message: String,
}

impl SubmissionReport {
    /// 本机新建出的任务 ID（下发不产生本机任务）。
    #[must_use]
    pub fn created_task_ids(&self) -> &[String] {
        &self.created_task_ids
    }

    #[must_use]
    pub fn failed(&self) -> bool {
        self.summary.has_failure()
    }

    /// 汇总提示：本机成功 = 「任务已添加」；下发成功 / 部分成功 / 全部失败各自带目标设备名；
    /// 失败附首个错误的原因文案（按 `reason`，缺失再按码）。
    #[must_use]
    pub fn notice(&self, translator: &Translator) -> SubmitNotice {
        let strings = NewDownloadStrings::from_translator(translator);
        let summary = &self.summary;
        let reason = summary
            .first_error
            .as_ref()
            .map(|error| error_text(translator, error));
        match (&self.remote, reason) {
            (Some(remote), None) => SubmitNotice {
                ok: true,
                message: strings.format_dispatched(
                    summary.succeeded,
                    &remote.device,
                    remote.offline,
                ),
            },
            (Some(remote), Some(reason)) => SubmitNotice {
                ok: false,
                message: if summary.succeeded > 0 {
                    format!(
                        "{}: {reason}",
                        strings.format_partial(summary.succeeded, summary.failed, &remote.device)
                    )
                } else {
                    format!("{}: {reason}", strings.dispatch_failed)
                },
            },
            (None, None) => SubmitNotice {
                ok: true,
                message: strings.created.to_string(),
            },
            (None, Some(reason)) => SubmitNotice {
                ok: false,
                message: reason,
            },
        }
    }
}

/// 逐条执行提交并汇总。主命令顺序执行；偏好写入 / 捕获清理尽力而为，放在主命令之后，
/// 失败不影响结果也不计入汇总。
pub async fn run_submission(
    submission: NewDownloadSubmission,
    execute: impl Fn(DownloadsCommand) -> PortFuture<DownloadsResult>,
) -> SubmissionReport {
    let SubmissionPlan {
        commands,
        best_effort,
        remote,
    } = submission.plan();
    let mut summary = DispatchSummary::default();
    let mut created_task_ids = Vec::new();
    for command in commands {
        let creates = creates_local_task(&command);
        let result = execute(command).await;
        summary.record(&result);
        if creates && let Ok(result) = &result {
            created_task_ids.extend(result.created_task_ids());
        }
    }
    for command in best_effort {
        if let Err(error) = execute(command).await {
            // 偏好和捕获清理不影响已创建任务，但失败需要诊断记录。
            eprintln!(
                "download submission best-effort command failed: {:?} ({:?})",
                error.code, error.reason
            );
        }
    }
    SubmissionReport {
        created_task_ids,
        summary,
        remote,
    }
}

#[cfg(test)]
mod tests {
    use std::{
        cell::RefCell,
        future::Future,
        task::{Context, Poll, Waker},
    };

    use fluxdown_protocol::{ApplicationErrorCode, ErrorReason, RpcErrorData};
    use fluxdown_ui_i18n::{I18nCatalog, I18nError};

    use super::*;

    fn block_on<F: Future>(future: F) -> F::Output {
        let mut future = std::pin::pin!(future);
        let mut cx = Context::from_waker(Waker::noop());
        loop {
            if let Poll::Ready(value) = future.as_mut().poll(&mut cx) {
                return value;
            }
        }
    }

    fn remote(target: DispatchTarget, urls: &[&str], save_dir: Option<&str>) -> RemoteSubmission {
        RemoteSubmission {
            target,
            device_label: "Mac".to_owned(),
            target_offline: false,
            items: urls
                .iter()
                .map(|url| RemoteItem {
                    url: (*url).to_owned(),
                    file_name: None,
                })
                .collect(),
            save_dir: save_dir.map(str::to_owned),
            captures: vec!["cap-1".to_owned()],
        }
    }

    #[test]
    fn remote_submission_dispatches_each_link_with_target_default_dir_omitted() {
        let plan = NewDownloadSubmission::Remote(remote(
            DispatchTarget::Cloud("dev-b".to_owned()),
            &["https://a/1", "https://a/2"],
            None,
        ))
        .plan();
        assert_eq!(plan.commands.len(), 2);
        for command in &plan.commands {
            let DownloadsCommand::RemoteDispatch(params) = command else {
                panic!("cloud target must use remote.dispatch");
            };
            assert_eq!(params.to_device, "dev-b");
            assert_eq!(params.save_dir, None);
        }
        // 偏好写入 + 捕获清理都是尽力而为，且没有污染本机保存目录偏好。
        assert!(plan.best_effort.iter().all(|command| !matches!(
            command,
            DownloadsCommand::SetLocalPreference { key, .. } if *key == LAST_SAVE_DIR_PREF
        )));
        assert!(plan.best_effort.iter().any(|command| matches!(
            command,
            DownloadsCommand::CaptureResolve(params) if !params.accepted && params.transaction_id == "cap-1"
        )));
        assert!(plan.best_effort.iter().any(|command| matches!(
            command,
            DownloadsCommand::SetLocalPreference { key, value }
                if *key == LAST_DOWNLOAD_TARGET_PREF && value == "cloud:dev-b"
        )));
    }

    #[test]
    fn paired_target_uses_link_dispatch_with_explicit_dir() {
        let plan = NewDownloadSubmission::Remote(remote(
            DispatchTarget::Paired("fp1".to_owned()),
            &["magnet:?xt=urn:btih:abc"],
            Some("/mnt/dl"),
        ))
        .plan();
        let [DownloadsCommand::LinkDispatch(params)] = plan.commands.as_slice() else {
            panic!("paired target must use link.dispatch");
        };
        assert_eq!(params.fingerprint, "fp1");
        assert_eq!(params.save_dir.as_deref(), Some("/mnt/dl"));
    }

    #[test]
    fn dispatch_summary_reports_partial_failure_and_never_yields_local_task_ids()
    -> Result<(), I18nError> {
        let catalog = std::sync::Arc::new(I18nCatalog::load_embedded()?);
        let translator = catalog.translator("en");
        let calls = RefCell::new(Vec::new());
        let report = block_on(run_submission(
            NewDownloadSubmission::Remote(remote(
                DispatchTarget::Cloud("dev-b".to_owned()),
                &["https://a/1", "https://a/2", "https://a/3"],
                None,
            )),
            |command| {
                let index = calls.borrow().len();
                calls
                    .borrow_mut()
                    .push(matches!(command, DownloadsCommand::RemoteDispatch(_)));
                Box::pin(async move {
                    if index == 1 {
                        Err(RpcErrorData::new(ApplicationErrorCode::Unavailable, true)
                            .with_reason(ErrorReason::TargetDeviceOffline))
                    } else {
                        // 下发结果里的 `taskId` 属于目标设备，不能当本机任务。
                        Ok(DownloadsResult::Value(
                            serde_json::json!({"taskId": "remote-1"}),
                        ))
                    }
                })
            },
        ));
        assert!(report.created_task_ids().is_empty());
        assert!(report.failed());
        let notice = report.notice(&translator);
        assert!(!notice.ok);
        assert!(notice.message.contains("Mac"), "{}", notice.message);
        assert!(
            notice
                .message
                .contains("The target device is offline, so the command can't be delivered."),
            "{}",
            notice.message
        );
        // 3 条下发 + 目标偏好 + 捕获清理均已执行。
        assert_eq!(calls.borrow().len(), 5);
        Ok(())
    }

    #[test]
    fn all_dispatched_offline_target_mentions_it_will_run_later() -> Result<(), I18nError> {
        let catalog = std::sync::Arc::new(I18nCatalog::load_embedded()?);
        let translator = catalog.translator("en");
        let mut submission = remote(
            DispatchTarget::Cloud("dev-b".to_owned()),
            &["https://a/1"],
            None,
        );
        submission.target_offline = true;
        let report = block_on(run_submission(
            NewDownloadSubmission::Remote(submission),
            |_| Box::pin(async { Ok(DownloadsResult::Unit) }),
        ));
        let notice = report.notice(&translator);
        assert!(notice.ok);
        assert!(notice.message.contains("offline"), "{}", notice.message);
        Ok(())
    }

    #[test]
    fn local_failure_shows_reason_text_and_success_collects_created_ids() -> Result<(), I18nError> {
        let catalog = std::sync::Arc::new(I18nCatalog::load_embedded()?);
        let translator = catalog.translator("en");
        let requests = crate::model::new_download::build_requests(
            &[crate::model::new_download::UrlEntry {
                url: "https://a/1".to_owned(),
                ..Default::default()
            }],
            &crate::model::new_download::DraftOptions::default(),
        );
        let submission = NewDownloadSubmission::Tasks {
            tasks: requests,
            captures: Vec::new(),
        };
        let report = block_on(run_submission(submission.clone(), |_| {
            Box::pin(async { Ok(DownloadsResult::Value(serde_json::json!({"taskId": "t1"}))) })
        }));
        assert_eq!(report.created_task_ids(), ["t1"]);
        assert!(report.notice(&translator).ok);

        let report = block_on(run_submission(submission, |_| {
            Box::pin(async { Err(RpcErrorData::new(ApplicationErrorCode::Unavailable, true)) })
        }));
        let notice = report.notice(&translator);
        assert!(!notice.ok);
        assert_eq!(notice.message, translator.text("localServiceDisconnected"));
        Ok(())
    }

    #[test]
    fn torrent_files_are_user_initiated_and_carry_form_options() {
        let plan = NewDownloadSubmission::TorrentFiles {
            paths: vec![
                PathBuf::from("/tmp/a.torrent"),
                PathBuf::from("/tmp/b.torrent"),
            ],
            options: TorrentFileOptions {
                save_dir: "/data/bt".to_owned(),
                queue_id: "q1".to_owned(),
                start_paused: true,
            },
        }
        .plan();
        assert_eq!(plan.commands.len(), 2);
        for command in &plan.commands {
            let DownloadsCommand::SubmitTorrentFile {
                silent,
                save_dir,
                queue_id,
                start_paused,
                ..
            } = command
            else {
                panic!("torrent files must use submitTorrentFile");
            };
            assert!(
                !silent,
                "user-initiated torrents must go through file selection"
            );
            assert_eq!(save_dir.as_deref(), Some("/data/bt"));
            assert_eq!(queue_id.as_deref(), Some("q1"));
            assert_eq!(*start_paused, Some(true));
        }
    }

    #[test]
    fn torrent_files_with_blank_form_options_defer_to_agent_defaults() {
        let plan = NewDownloadSubmission::TorrentFiles {
            paths: vec![PathBuf::from("/tmp/a.torrent")],
            options: TorrentFileOptions::default(),
        }
        .plan();
        let [
            DownloadsCommand::SubmitTorrentFile {
                save_dir, queue_id, ..
            },
        ] = plan.commands.as_slice()
        else {
            panic!("one torrent, one command");
        };
        assert_eq!(save_dir, &None);
        assert_eq!(queue_id, &None);
    }
}
