//! 单测用的脚本化连接：不碰网络，驱动与命令层都能在 `start_paused` 的 tokio 时间下验证。

use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use async_trait::async_trait;
use fluxdown_protocol::method::SYSTEM_SNAPSHOT;
use fluxdown_protocol::{RpcRequest, ServiceHello, ServiceRole};
use serde_json::{Value, json};
use tokio::runtime::Handle;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

use crate::driver::Timing;
use crate::error::FluxError;
use crate::link::{
    Caller, CloseInfo, ConnectFailure, Connector, Established, EventSource, LinkEvent,
};
use crate::projection::fixtures::{daemon_frame, snapshot, task};
use crate::session::{HostSession, open_session};

/// 记录命令并按方法名返回预置应答的调用端。
#[derive(Default)]
pub(crate) struct Recorder {
    calls: Mutex<Vec<(String, Value)>>,
    replies: Mutex<HashMap<String, Result<Value, FluxError>>>,
    snapshot_calls: AtomicUsize,
    snapshot_replies: tokio::sync::Mutex<Option<mpsc::UnboundedReceiver<Result<Value, FluxError>>>>,
}

impl Recorder {
    pub(crate) fn reply(&self, method: &str, reply: Result<Value, FluxError>) {
        self.replies
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .insert(method.to_owned(), reply);
    }

    /// 除 `system.snapshot` 外按序记录的 `(method, params)`。
    pub(crate) fn calls(&self) -> Vec<(String, Value)> {
        self.calls
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    pub(crate) fn snapshot_calls(&self) -> usize {
        self.snapshot_calls.load(Ordering::SeqCst)
    }
}

struct MockCaller(Arc<Recorder>);

#[async_trait]
impl Caller for MockCaller {
    async fn call(&self, method: &str, params: Option<Value>) -> Result<Value, FluxError> {
        if method == SYSTEM_SNAPSHOT {
            self.0.snapshot_calls.fetch_add(1, Ordering::SeqCst);
            let mut replies = self.0.snapshot_replies.lock().await;
            return match replies.as_mut() {
                Some(receiver) => receiver
                    .recv()
                    .await
                    .unwrap_or_else(|| Err(FluxError::transport("script ended"))),
                None => Err(FluxError::transport("no snapshot script")),
            };
        }
        self.0
            .calls
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .push((method.to_owned(), params.unwrap_or(Value::Null)));
        self.0
            .replies
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .get(method)
            .cloned()
            .unwrap_or_else(|| Ok(json!({ "ok": true })))
    }
}

struct MockEvents(mpsc::UnboundedReceiver<LinkEvent>);

#[async_trait]
impl EventSource for MockEvents {
    async fn next(&mut self) -> LinkEvent {
        self.0
            .recv()
            .await
            .unwrap_or_else(|| LinkEvent::Closed(CloseInfo::Lost("script ended".to_owned())))
    }
}

/// 测试侧句柄：推送事件 / 快照应答，读取命令记录。
pub(crate) struct LinkHandle {
    pub events: mpsc::UnboundedSender<LinkEvent>,
    pub snapshots: mpsc::UnboundedSender<Result<Value, FluxError>>,
    pub recorder: Arc<Recorder>,
}

impl LinkHandle {
    pub(crate) fn send_frame(&self, frame: fluxdown_protocol::EventFrame) {
        self.events
            .send(LinkEvent::Frame(Box::new(frame)))
            .expect("driver alive");
    }

    pub(crate) fn send_snapshot(&self, sequence: u64, task_ids: &[&str]) {
        let tasks = task_ids.iter().map(|id| task(id, 1)).collect();
        let value = serde_json::to_value(snapshot(sequence, tasks)).expect("snapshot json");
        self.snapshots.send(Ok(value)).expect("driver alive");
    }

    pub(crate) fn close(&self, info: CloseInfo) {
        self.events
            .send(LinkEvent::Closed(info))
            .expect("driver alive");
    }
}

pub(crate) struct MockLink {
    hello: ServiceHello,
    recorder: Arc<Recorder>,
    events: mpsc::UnboundedReceiver<LinkEvent>,
}

pub(crate) fn agent_hello() -> ServiceHello {
    ServiceHello::new(
        ServiceRole::Agent,
        "fluxdown-agent",
        "1.0.0",
        "instance",
        vec!["agent.gateway".to_owned()],
    )
}

pub(crate) fn mock_link(hello: ServiceHello) -> (MockLink, LinkHandle) {
    let recorder = Arc::new(Recorder::default());
    let (events_tx, events_rx) = mpsc::unbounded_channel();
    let (snapshots_tx, snapshots_rx) = mpsc::unbounded_channel();
    recorder
        .snapshot_replies
        .try_lock()
        .expect("fresh recorder")
        .replace(snapshots_rx);
    (
        MockLink {
            hello,
            recorder: Arc::clone(&recorder),
            events: events_rx,
        },
        LinkHandle {
            events: events_tx,
            snapshots: snapshots_tx,
            recorder,
        },
    )
}

/// 按顺序吐出预置的连接 / 失败。
#[derive(Default)]
pub(crate) struct ScriptedConnector {
    queue: Mutex<VecDeque<Result<MockLink, ConnectFailure>>>,
    connects: AtomicUsize,
}

impl ScriptedConnector {
    pub(crate) fn push(&self, item: Result<MockLink, ConnectFailure>) {
        self.queue
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .push_back(item);
    }

    pub(crate) fn connects(&self) -> usize {
        self.connects.load(Ordering::SeqCst)
    }
}

#[async_trait]
impl Connector for ScriptedConnector {
    async fn connect(&self, _hello: RpcRequest) -> Result<Established, ConnectFailure> {
        self.connects.fetch_add(1, Ordering::SeqCst);
        let next = self
            .queue
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .pop_front();
        match next {
            Some(Ok(link)) => Ok(Established {
                hello: link.hello,
                caller: Arc::new(MockCaller(link.recorder)),
                events: Box::new(MockEvents(link.events)),
            }),
            Some(Err(failure)) => Err(failure),
            None => Err(ConnectFailure::Retry("script exhausted".to_owned())),
        }
    }
}

pub(crate) async fn open(
    connector: Arc<ScriptedConnector>,
    timing: Timing,
) -> Result<(Arc<HostSession>, JoinHandle<()>), FluxError> {
    open_session(
        &Handle::current(),
        connector,
        CancellationToken::new(),
        timing,
    )
    .await
}

/// 已握手并完成首次同步的会话；返回的句柄必须在测试期间保持存活（丢弃即断线）。
pub(crate) async fn session_with_recorder() -> (Arc<HostSession>, LinkHandle) {
    let connector = Arc::new(ScriptedConnector::default());
    let (link, handle) = mock_link(agent_hello());
    handle.send_snapshot(5, &[]);
    connector.push(Ok(link));
    let (session, _driver) = open(connector, Timing::default()).await.expect("open");
    (session, handle)
}

/// 构造一帧 `TaskDeleted`，用于推进游标。
pub(crate) fn deleted_frame(sequence: u64, task_id: &str) -> fluxdown_protocol::EventFrame {
    daemon_frame(
        sequence,
        fluxdown_protocol::DaemonEvent::TaskDeleted {
            task_id: task_id.to_owned(),
        },
    )
}
