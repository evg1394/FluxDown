//! daemon 物化投影、事件游标与广播的单一同步边界。

use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};

use fluxdown_protocol::{
    DaemonEvent, DaemonSnapshot, EventFrame, ServiceEvent, Snapshot, SnapshotBody, WsServerMsg,
    apply_daemon_event,
};
use tokio::sync::broadcast;
use uuid::Uuid;

#[derive(Debug)]
struct EventState {
    epoch: String,
    sequence: u64,
    snapshot: DaemonSnapshot,
    download_speeds: HashMap<String, i64>,
    upload_speeds: HashMap<String, i64>,
}

/// 线性化 daemon 快照与增量事件。
#[derive(Clone)]
pub struct DaemonEventHub {
    state: Arc<Mutex<EventState>>,
    events: broadcast::Sender<EventFrame>,
}

impl DaemonEventHub {
    /// 创建新的事件 epoch。
    #[must_use]
    pub fn new(snapshot: DaemonSnapshot, capacity: usize) -> Self {
        let (events, _) = broadcast::channel(capacity);
        Self {
            state: Arc::new(Mutex::new(EventState {
                epoch: Uuid::new_v4().to_string(),
                sequence: 0,
                snapshot,
                download_speeds: HashMap::new(),
                upload_speeds: HashMap::new(),
            })),
            events,
        }
    }

    /// 只订阅增量广播（不克隆快照）；滞后时接收者自行按 [`Self::snapshot`] 复核。
    #[must_use]
    pub fn subscribe(&self) -> broadcast::Receiver<EventFrame> {
        self.events.subscribe()
    }

    /// 先订阅广播，再原子克隆快照；调用方丢弃不大于快照 sequence 的帧。
    #[must_use]
    pub fn subscribe_and_snapshot(&self) -> (broadcast::Receiver<EventFrame>, Snapshot) {
        let receiver = self.events.subscribe();
        let state = lock_or_recover(&self.state);
        let snapshot = Snapshot {
            epoch: state.epoch.clone(),
            sequence: state.sequence,
            body: SnapshotBody::Daemon(Box::new(state.snapshot.clone())),
        };
        (receiver, snapshot)
    }

    /// 原子读取当前快照与游标。
    #[must_use]
    pub fn snapshot(&self) -> Snapshot {
        let state = lock_or_recover(&self.state);
        Snapshot {
            epoch: state.epoch.clone(),
            sequence: state.sequence,
            body: SnapshotBody::Daemon(Box::new(state.snapshot.clone())),
        }
    }

    /// 先更新物化投影，再递增 sequence 并广播对应帧。
    pub fn publish(&self, event: DaemonEvent) -> EventFrame {
        let mut state = lock_or_recover(&self.state);
        self.publish_locked(&mut state, event)
    }

    /// 发布事件；仅当它改变了运行时统计时，才在同一临界区内紧随发布
    /// `RuntimeStatsChanged`。进度帧每个活动任务约每 0.5s 一次，这条路径
    /// 不能为读取统计去克隆整份快照，也不该广播与上一帧相同的统计。
    pub fn publish_with_runtime_stats(&self, event: DaemonEvent) {
        let mut state = lock_or_recover(&self.state);
        let before = state.snapshot.runtime_stats.clone();
        self.publish_locked(&mut state, event);
        if state.snapshot.runtime_stats != before {
            let stats = state.snapshot.runtime_stats.clone();
            self.publish_locked(&mut state, DaemonEvent::RuntimeStatsChanged(stats));
        }
    }

    /// 更新「等待自动重试」任务数；数值不变不发布。
    pub fn set_retry_pending_tasks(&self, count: u32) {
        let mut state = lock_or_recover(&self.state);
        if state.snapshot.runtime_stats.retry_pending_tasks == count {
            return;
        }
        let mut stats = state.snapshot.runtime_stats.clone();
        stats.retry_pending_tasks = count;
        self.publish_locked(&mut state, DaemonEvent::RuntimeStatsChanged(stats));
    }

    fn publish_locked(&self, state: &mut EventState, event: DaemonEvent) -> EventFrame {
        apply_daemon_event(&mut state.snapshot, &event);
        apply_runtime_stats(state, &event);
        state.sequence = state.sequence.saturating_add(1);
        let frame = EventFrame {
            epoch: state.epoch.clone(),
            sequence: state.sequence,
            event: ServiceEvent::Daemon(event),
        };
        // 与序号递增处于同一临界区，接收者不会收到乱序帧。
        // 无订阅者是正常空闲状态，不克隆帧或逐条记录进度日志。
        if self.events.receiver_count() != 0 && self.events.send(frame.clone()).is_err() {
            tracing::trace!("daemon event subscribers disconnected before publish");
        }
        frame
    }

    /// 原子替换投影并发布替换事件。
    pub fn replace_snapshot(&self, snapshot: DaemonSnapshot) -> EventFrame {
        self.publish(DaemonEvent::SnapshotReplaced(snapshot))
    }
}

fn apply_runtime_stats(state: &mut EventState, event: &DaemonEvent) {
    match event {
        DaemonEvent::SnapshotReplaced(_) => {
            state.download_speeds.clear();
            state.upload_speeds.clear();
        }
        DaemonEvent::TaskDeleted { task_id } => {
            state.download_speeds.remove(task_id);
            state.upload_speeds.remove(task_id);
        }
        DaemonEvent::TaskChanged(task) if !matches!(task.status, 1 | 5) => {
            state.download_speeds.remove(&task.task_id);
            state.upload_speeds.remove(&task.task_id);
        }
        DaemonEvent::Engine(WsServerMsg::TasksSnapshot { tasks }) => {
            state.download_speeds.retain(|task_id, _| {
                tasks
                    .iter()
                    .any(|task| task.task_id == *task_id && matches!(task.status, 1 | 5))
            });
            state.upload_speeds.retain(|task_id, _| {
                tasks
                    .iter()
                    .any(|task| task.task_id == *task_id && matches!(task.status, 1 | 5))
            });
        }
        DaemonEvent::Engine(WsServerMsg::TaskProgress {
            task_id,
            status,
            speed,
            upload_speed,
            ..
        }) => {
            if matches!(status, 1 | 5) {
                state
                    .download_speeds
                    .insert(task_id.clone(), (*speed).max(0));
                state
                    .upload_speeds
                    .insert(task_id.clone(), (*upload_speed).max(0));
            } else {
                state.download_speeds.remove(task_id);
                state.upload_speeds.remove(task_id);
            }
        }
        _ => {}
    }
    state.snapshot.runtime_stats.active_tasks = u32::try_from(
        state
            .snapshot
            .tasks
            .iter()
            .filter(|task| matches!(task.status, 1 | 5))
            .count(),
    )
    .unwrap_or(u32::MAX);
    state.snapshot.runtime_stats.pending_tasks = u32::try_from(
        state
            .snapshot
            .tasks
            .iter()
            .filter(|task| task.status == 0)
            .count(),
    )
    .unwrap_or(u32::MAX);
    state.snapshot.runtime_stats.total_download_bps = state
        .download_speeds
        .values()
        .fold(0_i64, |total, speed| total.saturating_add(*speed));
    state.snapshot.runtime_stats.total_upload_bps = state
        .upload_speeds
        .values()
        .fold(0_i64, |total, speed| total.saturating_add(*speed));
    if let Some(save_dir) = state
        .snapshot
        .config
        .values
        .get("default_save_dir")
        .filter(|path| !path.trim().is_empty())
    {
        state.snapshot.runtime_stats.save_dir.clone_from(save_dir);
    }
}

/// 将引擎事件无阻塞转换并发布到 daemon 事件中心。
pub struct DaemonEngineEventSink(pub DaemonEventHub);

impl fluxdown_engine::events::EventSink for DaemonEngineEventSink {
    fn emit(&self, event: fluxdown_engine::events::EngineEvent) {
        use fluxdown_engine::events::EngineEvent;
        match event {
            EngineEvent::TaskRuntimeChanged(runtime) => {
                self.0.publish(DaemonEvent::TaskRuntimeChanged(
                    fluxdown_engine_protocol::task_runtime_to_dto(runtime),
                ));
                return;
            }
            EngineEvent::TaskActivityAdded(activity) => {
                self.0.publish(DaemonEvent::TaskActivityAdded(
                    fluxdown_engine_protocol::task_activity_to_dto(activity),
                ));
                return;
            }
            EngineEvent::RetryPendingChanged { count } => {
                self.0.set_retry_pending_tasks(count);
                return;
            }
            EngineEvent::TaskNotice(notice) => {
                self.0.publish(DaemonEvent::TaskNotice(
                    fluxdown_engine_protocol::task_notice_to_dto(notice),
                ));
                return;
            }
            _ => {}
        }

        let message = match event {
            EngineEvent::TaskProgress {
                task_id,
                status,
                downloaded_bytes,
                total_bytes,
                speed,
                file_name,
                save_dir,
                url,
                error_message,
                upload_speed_bps,
                uploaded_bytes,
                seeding_status,
                seeding_message,
                seeding_time_secs,
                ..
            } => WsServerMsg::TaskProgress {
                task_id,
                status,
                downloaded_bytes,
                total_bytes,
                speed,
                upload_speed: upload_speed_bps,
                file_name,
                save_dir,
                url,
                error_message,
                uploaded_bytes,
                seeding_status,
                seeding_message,
                seeding_time_secs,
            },
            EngineEvent::TasksSnapshot(tasks) => WsServerMsg::TasksSnapshot {
                tasks: tasks
                    .into_iter()
                    .map(fluxdown_engine_protocol::task_info_to_dto)
                    .collect(),
            },
            EngineEvent::SegmentProgress {
                task_id,
                total_bytes,
                segment_count,
                segments,
            } => WsServerMsg::SegmentProgress {
                task_id,
                total_bytes,
                segment_count,
                segments: segments
                    .into_iter()
                    .map(fluxdown_engine_protocol::segment_detail_to_dto)
                    .collect(),
            },
            EngineEvent::TaskMetaProbed {
                task_id,
                file_name,
                total_bytes,
            } => WsServerMsg::TaskMetaProbed {
                task_id,
                file_name,
                total_bytes,
            },
            EngineEvent::QueuePositionsChanged(positions) => WsServerMsg::QueuePositionsChanged {
                positions: positions
                    .into_iter()
                    .map(fluxdown_engine_protocol::queue_position_to_dto)
                    .collect(),
            },
            EngineEvent::QueuesChanged(queues) => WsServerMsg::QueuesChanged {
                queues: queues
                    .into_iter()
                    .map(fluxdown_engine_protocol::queue_info_to_dto)
                    .collect(),
            },
            EngineEvent::TaskQueueChanged { task_id, queue_id } => {
                WsServerMsg::TaskQueueChanged { task_id, queue_id }
            }
            EngineEvent::TaskRouteChanged { task_id, route } => {
                WsServerMsg::TaskRouteChanged { task_id, route }
            }
            EngineEvent::PriorityTaskChanged {
                priority_task_id,
                auto_paused_count,
            } => WsServerMsg::PriorityTaskChanged {
                priority_task_id,
                auto_paused_count,
            },
            EngineEvent::SegmentSplit {
                task_id,
                parent_index,
                parent_new_end,
                child_index,
                child_start,
                child_end,
                is_proactive,
                total_segments,
            } => WsServerMsg::SegmentSplit {
                task_id,
                parent_index,
                parent_new_end,
                child_index,
                child_start,
                child_end,
                is_proactive,
                total_segments,
            },
            EngineEvent::TaskCdnEvent {
                task_id,
                kind,
                host,
                nodes,
                ip,
                reason,
                candidates,
                alive,
                cap,
                auto_cap,
            } => WsServerMsg::TaskCdnEvent {
                task_id,
                kind,
                host,
                nodes: nodes
                    .into_iter()
                    .map(fluxdown_engine_protocol::cdn_node_info_to_dto)
                    .collect(),
                ip,
                reason,
                candidates,
                alive,
                cap,
                auto_cap,
            },
            EngineEvent::BtDataFinished { .. } => return,
            EngineEvent::PluginAutoDisabled { identity, reason } => {
                WsServerMsg::PluginAutoDisabled { identity, reason }
            }
            EngineEvent::DuplicateTorrentDetected {
                task_id,
                existing_task_id,
                existing_name,
            } => WsServerMsg::DuplicateTorrent {
                task_id,
                existing_task_id,
                existing_name,
            },
            EngineEvent::PluginHookActivity {
                task_id,
                plugin_id,
                running,
            } => WsServerMsg::PluginHookActivity {
                task_id,
                plugin_id,
                running,
            },
            EngineEvent::GroupsChanged(groups) => WsServerMsg::GroupsChanged {
                groups: groups
                    .into_iter()
                    .map(fluxdown_engine_protocol::group_info_to_dto)
                    .collect(),
            },
            EngineEvent::RssSourcesChanged(sources) => WsServerMsg::RssSourcesChanged {
                sources: sources
                    .into_iter()
                    .map(fluxdown_engine_protocol::rss_source_info_to_dto)
                    .collect(),
            },
            EngineEvent::RssItemsChanged {
                source_id,
                items,
                notify_titles,
            } => WsServerMsg::RssItemsChanged {
                source_id,
                items: items
                    .into_iter()
                    .map(fluxdown_engine_protocol::rss_item_info_to_dto)
                    .collect(),
                notify_titles,
            },
            EngineEvent::RssFeedValidated {
                request_id,
                url,
                feed_title,
                items,
                error,
            } => WsServerMsg::RssFeedValidated {
                request_id,
                url,
                feed_title,
                items: items
                    .into_iter()
                    .map(fluxdown_engine_protocol::rss_item_info_to_dto)
                    .collect(),
                error,
            },
            EngineEvent::WebhookDeliveriesChanged(deliveries) => {
                WsServerMsg::WebhookDeliveriesChanged {
                    deliveries: deliveries
                        .into_iter()
                        .map(fluxdown_engine_protocol::webhook_delivery_to_dto)
                        .collect(),
                }
            }
            EngineEvent::FileMissingChanged(updates) => WsServerMsg::FileMissingChanged {
                updates: updates
                    .into_iter()
                    .map(
                        |(task_id, missing)| fluxdown_protocol::FileMissingUpdateDto {
                            task_id,
                            missing,
                        },
                    )
                    .collect(),
            },
            other => {
                tracing::debug!(?other, "daemon ignored unknown engine event");
                return;
            }
        };
        let updates_runtime = matches!(
            message,
            WsServerMsg::TaskProgress { .. } | WsServerMsg::TasksSnapshot { .. }
        );
        let event = match message {
            WsServerMsg::QueuesChanged { queues } => DaemonEvent::QueuesChanged(queues),
            WsServerMsg::GroupsChanged { groups } => DaemonEvent::GroupsChanged(groups),
            WsServerMsg::WebhookDeliveriesChanged { deliveries } => {
                DaemonEvent::WebhooksChanged(deliveries)
            }
            message => DaemonEvent::Engine(message),
        };
        if updates_runtime {
            self.0.publish_with_runtime_stats(event);
        } else {
            self.0.publish(event);
        }
    }
}

fn lock_or_recover<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
mod tests {
    use fluxdown_engine::{
        events::{EngineEvent, EventSink},
        model::QueueInfo,
    };
    use fluxdown_protocol::{
        DaemonEvent, DaemonSnapshot, ServiceEvent, SnapshotBody, TaskDto, WsServerMsg,
    };

    use super::{DaemonEngineEventSink, DaemonEventHub};

    #[test]
    fn queue_engine_changes_reach_subscribers_as_queue_domain_events() {
        let hub = DaemonEventHub::new(DaemonSnapshot::default(), 8);
        let (mut subscriber, _) = hub.subscribe_and_snapshot();
        let sink = DaemonEngineEventSink(hub.clone());
        let queue = QueueInfo {
            queue_id: "work".into(),
            name: "Work".into(),
            speed_limit_kbps: 0,
            upload_limit_kbps: 0,
            max_concurrent: 0,
            default_save_dir: String::new(),
            position: 1,
            default_segments: 0,
            default_user_agent: String::new(),
            is_running: true,
            schedule_enabled: false,
            schedule_start: String::new(),
            schedule_stop: String::new(),
            schedule_days: 127,
        };
        for queues in [
            vec![queue.clone()],
            vec![QueueInfo {
                name: "Renamed".into(),
                is_running: false,
                ..queue
            }],
            vec![],
        ] {
            sink.emit(EngineEvent::QueuesChanged(queues));
            let frame = subscriber
                .try_recv()
                .expect("subscriber receives queue change");
            let fluxdown_protocol::ServiceEvent::Daemon(DaemonEvent::QueuesChanged(changed)) =
                frame.event
            else {
                panic!("queue changes must use the domain event consumed by live windows");
            };
            let SnapshotBody::Daemon(snapshot) = hub.snapshot().body else {
                panic!("daemon snapshot expected");
            };
            assert_eq!(
                serde_json::to_value(snapshot.queues).unwrap(),
                serde_json::to_value(changed).unwrap()
            );
        }
    }

    #[test]
    fn groups_and_webhook_deliveries_reach_subscribers_as_domain_events() {
        let hub = DaemonEventHub::new(DaemonSnapshot::default(), 8);
        let (mut subscriber, _) = hub.subscribe_and_snapshot();
        let sink = DaemonEngineEventSink(hub.clone());
        sink.emit(EngineEvent::GroupsChanged(vec![
            fluxdown_engine::model::GroupInfo {
                group_id: "group-1".into(),
                name: "Collection".into(),
                source_url: String::new(),
                save_dir: String::new(),
                created_at: String::new(),
            },
        ]));
        let frame = subscriber
            .try_recv()
            .expect("subscriber receives group change");
        let fluxdown_protocol::ServiceEvent::Daemon(DaemonEvent::GroupsChanged(groups)) =
            frame.event
        else {
            panic!("groups must use the domain event consumed by live windows");
        };
        assert_eq!(groups[0].name, "Collection");

        sink.emit(EngineEvent::WebhookDeliveriesChanged(vec![
            fluxdown_engine::webhook::WebhookDelivery {
                delivery_id: "delivery-1".into(),
                timestamp_ms: 1,
                event: "task.completed".into(),
                endpoint_id: String::new(),
                endpoint_name: String::new(),
                url: String::new(),
                request_headers: String::new(),
                request_body: String::new(),
                status_code: 200,
                response_body: String::new(),
                latency_ms: 1,
                attempts: 1,
                success: true,
                error: String::new(),
            },
        ]));
        let frame = subscriber
            .try_recv()
            .expect("subscriber receives delivery change");
        let fluxdown_protocol::ServiceEvent::Daemon(DaemonEvent::WebhooksChanged(deliveries)) =
            frame.event
        else {
            panic!("deliveries must use the domain event consumed by live settings");
        };
        assert_eq!(deliveries[0].delivery_id, "delivery-1");
    }

    #[test]
    fn task_notice_maps_to_the_dedicated_daemon_event() {
        let hub = DaemonEventHub::new(DaemonSnapshot::default(), 8);
        let (mut subscriber, _) = hub.subscribe_and_snapshot();
        let sink = DaemonEngineEventSink(hub);
        sink.emit(EngineEvent::TaskNotice(
            fluxdown_engine::webhook::TaskNotice {
                delivery_id: "d-1".into(),
                event: "task.completed".into(),
                timestamp_ms: 42,
                queue_id: "default".into(),
                queue_name: "Default".into(),
                task: Some(fluxdown_engine::webhook::WebhookTask {
                    id: "t-1".into(),
                    file_name: "a.bin".into(),
                    url: "https://example.com/a.bin".into(),
                    save_dir: "/tmp".into(),
                    total_bytes: 9,
                    status: 3,
                    error_message: String::new(),
                }),
            },
        ));
        let frame = subscriber.try_recv().expect("notice is published");
        let fluxdown_protocol::ServiceEvent::Daemon(DaemonEvent::TaskNotice(notice)) = frame.event
        else {
            panic!("task notices use the dedicated event, not an engine message");
        };
        assert_eq!(notice.delivery_id, "d-1");
        assert_eq!(notice.event, "task.completed");
        assert_eq!(notice.timestamp_ms, 42);
        let task = notice.task.expect("task snapshot");
        assert_eq!(
            (task.id.as_str(), task.total_bytes, task.status),
            ("t-1", 9, 3)
        );
    }

    #[test]
    fn retry_pending_count_reaches_runtime_stats_once_per_change() {
        let hub = DaemonEventHub::new(DaemonSnapshot::default(), 8);
        let (mut subscriber, _) = hub.subscribe_and_snapshot();
        let sink = DaemonEngineEventSink(hub.clone());
        sink.emit(EngineEvent::RetryPendingChanged { count: 2 });
        sink.emit(EngineEvent::RetryPendingChanged { count: 2 });
        let frame = subscriber.try_recv().expect("a changed count is published");
        let fluxdown_protocol::ServiceEvent::Daemon(DaemonEvent::RuntimeStatsChanged(stats)) =
            frame.event
        else {
            panic!("retry-pending count travels in the runtime stats projection");
        };
        assert_eq!(stats.retry_pending_tasks, 2);
        assert!(
            subscriber.try_recv().is_err(),
            "an unchanged count must not be republished"
        );
        let SnapshotBody::Daemon(snapshot) = hub.snapshot().body else {
            panic!("daemon snapshot expected");
        };
        assert_eq!(snapshot.runtime_stats.retry_pending_tasks, 2);

        sink.emit(EngineEvent::RetryPendingChanged { count: 0 });
        let SnapshotBody::Daemon(snapshot) = hub.snapshot().body else {
            panic!("daemon snapshot expected");
        };
        assert_eq!(snapshot.runtime_stats.retry_pending_tasks, 0);
    }

    #[test]
    fn subscribe_snapshot_cursor_discards_prior_frames() {
        let hub = DaemonEventHub::new(DaemonSnapshot::default(), 8);
        let first = hub.publish(DaemonEvent::TaskDeleted {
            task_id: "old".to_owned(),
        });
        let (_receiver, snapshot) = hub.subscribe_and_snapshot();
        assert_eq!(snapshot.sequence, first.sequence);
        assert!(matches!(snapshot.body, SnapshotBody::Daemon(_)));
        let second = hub.publish(DaemonEvent::TaskDeleted {
            task_id: "new".to_owned(),
        });
        assert_eq!(second.sequence, snapshot.sequence + 1);
        assert_eq!(second.epoch, snapshot.epoch);
    }

    #[test]
    fn delete_sentinel_removes_task_from_atomic_projection() -> Result<(), serde_json::Error> {
        let task = serde_json::from_value::<TaskDto>(serde_json::json!({
            "taskId": "task-1",
            "url": "https://example.com/file",
            "fileName": "file",
            "saveDir": "/tmp",
            "status": 1,
            "downloadedBytes": 10,
            "totalBytes": 100,
            "errorMessage": "",
            "createdAt": "1",
            "proxyUrl": "",
            "queueId": "main",
            "checksum": ""
        }))?;
        let hub = DaemonEventHub::new(
            DaemonSnapshot {
                tasks: vec![task],
                ..DaemonSnapshot::default()
            },
            8,
        );
        let frame = hub.publish(DaemonEvent::Engine(WsServerMsg::TaskProgress {
            task_id: "task-1".to_owned(),
            status: 4,
            downloaded_bytes: 10,
            total_bytes: 100,
            speed: 0,
            file_name: "file".to_owned(),
            save_dir: "/tmp".to_owned(),
            upload_speed: 0,
            url: "https://example.com/file".to_owned(),
            error_message: "deleted".to_owned(),
            uploaded_bytes: 0,
            seeding_status: 0,
            seeding_message: String::new(),
            seeding_time_secs: 0,
        }));
        assert_eq!(frame.sequence, 1);
        let snapshot = hub.snapshot();
        let SnapshotBody::Daemon(snapshot) = snapshot.body else {
            panic!("daemon hub returned agent snapshot");
        };
        assert!(snapshot.tasks.is_empty());
        Ok(())
    }

    #[test]
    fn runtime_stats_follow_live_task_speed_and_terminal_transitions()
    -> Result<(), serde_json::Error> {
        let task = serde_json::from_value::<TaskDto>(serde_json::json!({
            "taskId": "task-live",
            "url": "https://example.com/live",
            "fileName": "live",
            "saveDir": "/tmp",
            "status": 1,
            "downloadedBytes": 10,
            "totalBytes": 100,
            "errorMessage": "",
            "createdAt": "1",
            "proxyUrl": "",
            "queueId": "main",
            "checksum": ""
        }))?;
        let hub = DaemonEventHub::new(
            DaemonSnapshot {
                tasks: vec![task],
                ..DaemonSnapshot::default()
            },
            8,
        );
        let progress = |status, speed, upload_speed| {
            DaemonEvent::Engine(WsServerMsg::TaskProgress {
                task_id: "task-live".to_owned(),
                status,
                downloaded_bytes: 10,
                total_bytes: 100,
                speed,
                upload_speed,
                file_name: "live".to_owned(),
                save_dir: "/tmp".to_owned(),
                url: "https://example.com/live".to_owned(),
                error_message: String::new(),
                uploaded_bytes: 0,
                seeding_status: 0,
                seeding_message: String::new(),
                seeding_time_secs: 0,
            })
        };
        hub.publish(progress(1, 100, 20));
        let SnapshotBody::Daemon(snapshot) = hub.snapshot().body else {
            panic!("daemon hub returned agent snapshot");
        };
        assert_eq!(snapshot.runtime_stats.active_tasks, 1);
        assert_eq!(snapshot.runtime_stats.total_download_bps, 100);
        assert_eq!(snapshot.runtime_stats.total_upload_bps, 20);

        hub.publish(progress(2, 0, 0));
        let SnapshotBody::Daemon(snapshot) = hub.snapshot().body else {
            panic!("daemon hub returned agent snapshot");
        };
        assert_eq!(snapshot.runtime_stats.active_tasks, 0);
        assert_eq!(snapshot.runtime_stats.total_download_bps, 0);
        assert_eq!(snapshot.runtime_stats.total_upload_bps, 0);
        Ok(())
    }

    #[test]
    fn runtime_stats_frame_follows_progress_only_when_stats_change() -> Result<(), serde_json::Error>
    {
        let task: TaskDto = serde_json::from_value(serde_json::json!({
            "taskId":"task-live", "url":"https://example.com/live", "fileName":"live",
            "saveDir":"/tmp", "status":1, "downloadedBytes":10, "totalBytes":100,
            "errorMessage":"", "createdAt":"1", "proxyUrl":"", "queueId":"main", "checksum":""
        }))?;
        let hub = DaemonEventHub::new(
            DaemonSnapshot {
                tasks: vec![task],
                ..DaemonSnapshot::default()
            },
            16,
        );
        let (mut subscriber, _) = hub.subscribe_and_snapshot();
        let progress = |downloaded_bytes, speed| {
            DaemonEvent::Engine(WsServerMsg::TaskProgress {
                task_id: "task-live".to_owned(),
                status: 1,
                downloaded_bytes,
                total_bytes: 100,
                speed,
                upload_speed: 0,
                file_name: "live".to_owned(),
                save_dir: "/tmp".to_owned(),
                url: "https://example.com/live".to_owned(),
                error_message: String::new(),
                uploaded_bytes: 0,
                seeding_status: 0,
                seeding_message: String::new(),
                seeding_time_secs: 0,
            })
        };
        let mut drain = || {
            std::iter::from_fn(|| subscriber.try_recv().ok())
                .map(|frame| match frame.event {
                    ServiceEvent::Daemon(DaemonEvent::RuntimeStatsChanged(stats)) => {
                        Some(stats.total_download_bps)
                    }
                    _ => None,
                })
                .collect::<Vec<_>>()
        };

        hub.publish_with_runtime_stats(progress(20, 100));
        assert_eq!(drain(), [None, Some(100)]);
        // 字节推进但聚合统计不变：只有进度帧。
        hub.publish_with_runtime_stats(progress(30, 100));
        assert_eq!(drain(), [None]);
        hub.publish_with_runtime_stats(progress(40, 250));
        assert_eq!(drain(), [None, Some(250)]);
        Ok(())
    }
    #[test]
    fn parallel_publish_preserves_broadcast_sequence_and_snapshot_cursor() {
        let task: TaskDto = serde_json::from_value(serde_json::json!({
            "taskId":"shared", "url":"https://example.com/x", "fileName":"x", "saveDir":"/tmp",
            "status":1, "downloadedBytes":0, "totalBytes":100, "errorMessage":"",
            "createdAt":"1", "proxyUrl":"", "queueId":"main", "checksum":""
        }))
        .expect("task fixture");
        let hub = DaemonEventHub::new(
            DaemonSnapshot {
                tasks: vec![task],
                ..Default::default()
            },
            1024,
        );
        let (mut subscriber, before) = hub.subscribe_and_snapshot();
        std::thread::scope(|scope| {
            for _ in 0..4 {
                let hub = hub.clone();
                scope.spawn(move || {
                    for _ in 0..100 {
                        hub.publish(DaemonEvent::TaskRuntimeChanged(
                            fluxdown_protocol::TaskRuntimeDto {
                                task_id: "shared".into(),
                                active_transfers: Some(1),
                                ..Default::default()
                            },
                        ));
                    }
                });
            }
        });
        for expected in 1..=400 {
            let frame = subscriber
                .try_recv()
                .expect("all publications arrive without a gap");
            assert_eq!(frame.epoch, before.epoch);
            assert_eq!(frame.sequence, expected);
        }
        assert_eq!(hub.snapshot().sequence, 400);
        let (_, after) = hub.subscribe_and_snapshot();
        assert_eq!(after.sequence, 400);
        let SnapshotBody::Daemon(body) = after.body else {
            panic!("daemon snapshot")
        };
        assert_eq!(body.task_runtime["shared"].active_transfers, Some(1));
    }
}
