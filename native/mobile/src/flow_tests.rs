//! 会话驱动的端到端行为（脚本化连接 + 暂停的 tokio 时间）：握手校验、快照、缓冲重放、
//! 游标重同步、离线宽限与重连、致命错误。

use std::sync::Arc;
use std::time::Duration;

use tokio::time::timeout;

use crate::driver::Timing;
use crate::dto::{HostEventDto, HostSignalDto};
use crate::error::{ErrorCodeDto, FluxError, HostErrorDto};
use crate::link::{CloseInfo, ConnectFailure, LinkEvent};
use crate::projection::fixtures::daemon_frame;
use crate::session::HostSession;
use crate::testkit::{LinkHandle, ScriptedConnector, agent_hello, deleted_frame, mock_link, open};

async fn next(session: &HostSession) -> Option<HostSignalDto> {
    timeout(Duration::from_secs(60), session.next_signal())
        .await
        .expect("a signal within the test window")
}

fn snapshot_task_ids(signal: Option<HostSignalDto>) -> Vec<String> {
    match signal {
        Some(HostSignalDto::Snapshot { snapshot }) => snapshot
            .tasks
            .into_iter()
            .map(|task| task.task_id)
            .collect(),
        other => panic!("expected Snapshot, got {other:?}"),
    }
}

fn progress(task_id: &str, speed: i64) -> fluxdown_protocol::DaemonEvent {
    fluxdown_protocol::DaemonEvent::Engine(fluxdown_protocol::WsServerMsg::TaskProgress {
        task_id: task_id.to_owned(),
        status: 1,
        downloaded_bytes: 10,
        total_bytes: 100,
        speed,
        upload_speed: 0,
        file_name: String::new(),
        save_dir: String::new(),
        url: String::new(),
        error_message: String::new(),
        uploaded_bytes: 0,
        seeding_status: 0,
        seeding_message: String::new(),
        seeding_time_secs: 0,
    })
}

/// 单连接会话：首个快照序号 5，含任务 `a`。
async fn single(connector: Arc<ScriptedConnector>) -> (Arc<HostSession>, LinkHandle) {
    let (link, handle) = mock_link(agent_hello());
    handle.send_snapshot(5, &["a"]);
    connector.push(Ok(link));
    let (session, _driver) = open(connector, Timing::default()).await.expect("open");
    (session, handle)
}

#[tokio::test(start_paused = true)]
async fn first_signal_is_the_snapshot_then_accepted_events_follow() {
    let (session, link) = single(Arc::default()).await;
    assert_eq!(snapshot_task_ids(next(&session).await), ["a"]);

    link.send_frame(daemon_frame(6, progress("a", 4321)));
    match next(&session).await {
        Some(HostSignalDto::Event {
            event: HostEventDto::TaskProgress { task_id, speed, .. },
        }) => assert_eq!((task_id.as_str(), speed), ("a", 4321)),
        other => panic!("expected TaskProgress, got {other:?}"),
    }
}

#[tokio::test(start_paused = true)]
async fn duplicate_and_stale_frames_are_skipped() {
    let (session, link) = single(Arc::default()).await;
    next(&session).await;
    link.send_frame(deleted_frame(5, "a"));
    link.send_frame(deleted_frame(3, "a"));
    link.send_frame(deleted_frame(6, "a"));
    match next(&session).await {
        Some(HostSignalDto::Event {
            event: HostEventDto::TaskDeleted { task_id },
        }) => assert_eq!(task_id, "a"),
        other => panic!("expected the seq-6 delete only, got {other:?}"),
    }
}

#[tokio::test(start_paused = true)]
async fn frames_buffered_during_snapshot_are_replayed_after_it() {
    let connector = Arc::new(ScriptedConnector::default());
    let (link, handle) = mock_link(agent_hello());
    connector.push(Ok(link));
    let opening = tokio::spawn(open(connector, Timing::default()));

    // 快照应答被扣住时到达的帧：旧 epoch、已含于快照、连续的 6 与 7。
    let mut other_epoch = deleted_frame(6, "a");
    other_epoch.epoch = "older-epoch".to_owned();
    handle.send_frame(other_epoch);
    handle.send_frame(deleted_frame(4, "a"));
    handle.send_frame(daemon_frame(6, progress("a", 100)));
    handle.send_frame(daemon_frame(7, progress("a", 200)));
    tokio::time::sleep(Duration::from_millis(5)).await;
    assert_eq!(
        handle.recorder.snapshot_calls(),
        1,
        "snapshot still pending"
    );
    handle.send_snapshot(5, &["a"]);

    let (session, _driver) = opening.await.expect("join").expect("open");
    assert_eq!(snapshot_task_ids(next(&session).await), ["a"]);
    let mut speeds = Vec::new();
    for _ in 0..2 {
        match next(&session).await {
            Some(HostSignalDto::Event {
                event: HostEventDto::TaskProgress { speed, .. },
            }) => speeds.push(speed),
            other => panic!("expected replayed TaskProgress, got {other:?}"),
        }
    }
    assert_eq!(speeds, [100, 200]);
    assert!(
        timeout(Duration::from_millis(50), session.next_signal())
            .await
            .is_err(),
        "nothing else was replayed"
    );
}

#[tokio::test(start_paused = true)]
async fn sequence_gap_refetches_the_snapshot_on_the_same_connection() {
    let (session, link) = single(Arc::default()).await;
    next(&session).await;
    link.send_snapshot(8, &["a", "b"]);
    link.send_frame(deleted_frame(7 + 1, "a"));
    assert_eq!(snapshot_task_ids(next(&session).await), ["a", "b"]);
    assert_eq!(link.recorder.snapshot_calls(), 2);

    // 重同步后的游标从新快照继续。
    link.send_frame(deleted_frame(9, "b"));
    assert!(matches!(
        next(&session).await,
        Some(HostSignalDto::Event {
            event: HostEventDto::TaskDeleted { .. }
        })
    ));
}

#[tokio::test(start_paused = true)]
async fn epoch_change_refetches_the_snapshot() {
    let (session, link) = single(Arc::default()).await;
    next(&session).await;
    link.send_snapshot(1, &["fresh"]);
    let mut other = deleted_frame(6, "a");
    other.epoch = "epoch-b".to_owned();
    link.send_frame(other);
    assert_eq!(snapshot_task_ids(next(&session).await), ["fresh"]);
    assert_eq!(link.recorder.snapshot_calls(), 2);
}

#[tokio::test(start_paused = true)]
async fn buffer_overflow_during_snapshot_resyncs() {
    let connector = Arc::new(ScriptedConnector::default());
    let (link, handle) = mock_link(agent_hello());
    connector.push(Ok(link));
    let timing = Timing {
        buffer_cap: 2,
        ..Timing::default()
    };
    let opening = tokio::spawn(open(connector, timing));
    for sequence in 6..=9 {
        handle.send_frame(deleted_frame(sequence, "a"));
    }
    tokio::time::sleep(Duration::from_millis(5)).await;
    handle.send_snapshot(5, &["old"]); // 与溢出的缓冲不一致 → 丢弃并重取
    handle.send_snapshot(9, &["new"]);
    let (session, _driver) = opening.await.expect("join").expect("open");
    assert_eq!(snapshot_task_ids(next(&session).await), ["new"]);
    assert_eq!(handle.recorder.snapshot_calls(), 2);
}

#[tokio::test(start_paused = true)]
async fn stale_is_emitted_only_after_the_grace_and_snapshot_follows_reconnect() {
    let connector = Arc::new(ScriptedConnector::default());
    let (first, first_handle) = mock_link(agent_hello());
    first_handle.send_snapshot(5, &["a"]);
    connector.push(Ok(first));
    // 第一次重连失败 → 退避 > 800ms，宽限先到期。
    connector.push(Err(ConnectFailure::Retry("network down".to_owned())));
    let (second, second_handle) = mock_link(agent_hello());
    second_handle.send_snapshot(50, &["a", "b"]);
    connector.push(Ok(second));

    let (session, _driver) = open(connector, Timing::default()).await.expect("open");
    assert_eq!(snapshot_task_ids(next(&session).await), ["a"]);

    let before = tokio::time::Instant::now();
    first_handle.close(CloseInfo::Lost("reset".to_owned()));
    assert_eq!(next(&session).await, Some(HostSignalDto::Stale));
    assert_eq!(before.elapsed(), Duration::from_millis(800));
    assert_eq!(snapshot_task_ids(next(&session).await), ["a", "b"]);
}

#[tokio::test(start_paused = true)]
async fn quick_reconnect_inside_the_grace_never_goes_stale() {
    let connector = Arc::new(ScriptedConnector::default());
    let (first, first_handle) = mock_link(agent_hello());
    first_handle.send_snapshot(5, &["a"]);
    connector.push(Ok(first));
    let (second, second_handle) = mock_link(agent_hello());
    second_handle.send_snapshot(9, &["a"]);
    connector.push(Ok(second));

    let (session, _driver) = open(connector, Timing::default()).await.expect("open");
    next(&session).await;
    first_handle.close(CloseInfo::Lost("reset".to_owned()));
    // 首次退避 500ms ± 20% < 800ms：下一个信号直接是新快照。
    assert_eq!(snapshot_task_ids(next(&session).await), ["a"]);
}

#[tokio::test(start_paused = true)]
async fn event_gap_close_and_lag_reconnect_immediately() {
    for info in [Some(CloseInfo::EventGap), None] {
        let connector = Arc::new(ScriptedConnector::default());
        let (first, first_handle) = mock_link(agent_hello());
        first_handle.send_snapshot(5, &["a"]);
        connector.push(Ok(first));
        let (second, second_handle) = mock_link(agent_hello());
        second_handle.send_snapshot(1, &["z"]);
        connector.push(Ok(second));

        let (session, _driver) = open(Arc::clone(&connector), Timing::default())
            .await
            .expect("open");
        next(&session).await;
        let before = tokio::time::Instant::now();
        match info {
            Some(info) => first_handle.close(info),
            None => first_handle
                .events
                .send(LinkEvent::Lagged)
                .expect("driver alive"),
        }
        assert_eq!(snapshot_task_ids(next(&session).await), ["z"]);
        assert_eq!(before.elapsed(), Duration::ZERO, "no backoff on gap / lag");
        assert_eq!(connector.connects(), 2);
    }
}

#[tokio::test(start_paused = true)]
async fn service_quit_is_fatal_and_ends_the_stream() {
    let (session, link) = single(Arc::default()).await;
    next(&session).await;
    link.close(CloseInfo::ServiceQuit);
    match next(&session).await {
        Some(HostSignalDto::Fatal { error }) => {
            assert_eq!(error.code, ErrorCodeDto::Unavailable);
            assert!(!error.retryable);
        }
        other => panic!("expected Fatal, got {other:?}"),
    }
    assert_eq!(next(&session).await, None);
    assert!(matches!(
        session.pause("a".to_owned()).await,
        Err(FluxError::Closed)
    ));
}

#[tokio::test(start_paused = true)]
async fn first_connect_failure_is_returned_not_retried() {
    let connector = Arc::new(ScriptedConnector::default());
    connector.push(Err(ConnectFailure::Retry("refused".to_owned())));
    let error = open(Arc::clone(&connector), Timing::default())
        .await
        .err()
        .expect("open fails");
    assert!(matches!(&error, FluxError::Transport { detail } if detail == "refused"));
    assert_eq!(connector.connects(), 1);
}

#[tokio::test(start_paused = true)]
async fn unauthorized_connect_surfaces_as_rpc_unauthorized() {
    let connector = Arc::new(ScriptedConnector::default());
    connector.push(Err(ConnectFailure::Fatal(HostErrorDto::new(
        ErrorCodeDto::Unauthorized,
        "access key rejected",
    ))));
    let error = open(connector, Timing::default())
        .await
        .err()
        .expect("open fails");
    assert!(matches!(
        error,
        FluxError::Rpc {
            code: ErrorCodeDto::Unauthorized,
            ..
        }
    ));
}

#[tokio::test(start_paused = true)]
async fn incompatible_hello_is_fatal_protocol_incompatible() {
    let mut hello = agent_hello();
    hello.protocol_version = 6;
    let connector = Arc::new(ScriptedConnector::default());
    let (link, _handle) = mock_link(hello);
    connector.push(Ok(link));
    let error = open(connector, Timing::default())
        .await
        .err()
        .expect("open fails");
    assert!(matches!(
        error,
        FluxError::Rpc {
            code: ErrorCodeDto::ProtocolIncompatible,
            ..
        }
    ));

    let mut daemon = agent_hello();
    daemon.role = fluxdown_protocol::ServiceRole::Daemon;
    let connector = Arc::new(ScriptedConnector::default());
    let (link, _handle) = mock_link(daemon);
    connector.push(Ok(link));
    assert!(open(connector, Timing::default()).await.is_err());
}

#[tokio::test(start_paused = true)]
async fn non_service_event_frame_resyncs() {
    let (session, link) = single(Arc::default()).await;
    next(&session).await;
    link.send_snapshot(1, &["after"]);
    link.send_frame(fluxdown_protocol::EventFrame {
        epoch: crate::projection::fixtures::EPOCH.to_owned(),
        sequence: 6,
        event: fluxdown_protocol::ServiceEvent::Daemon(
            fluxdown_protocol::DaemonEvent::SelectionResolved {
                request_id: "r".to_owned(),
            },
        ),
    });
    assert_eq!(snapshot_task_ids(next(&session).await), ["after"]);
}
