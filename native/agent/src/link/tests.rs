//! 局域网直连的端到端测试：两个进程内 `LinkService`（各自独立的状态文件）经**真实 HTTP**
//! （`fluxdown_api::server::api_router` + 回环端口）完成 probe → hello → reveal → SAS → confirm →
//! 信息交换 → 下发；另用裸 TCP 服务模拟反代返回的非 FluxDown 响应、旧版对端与冒充设备的代理。

#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;

use async_trait::async_trait;
use fluxdown_api::server::ApiServerConfig;
use fluxdown_api::service::{ApiError, ApiHost};
use fluxdown_link::{LinkError, PeerAddress};
use fluxdown_protocol::{
    AgentSnapshot, CreateTaskRequest, DaemonConfigSnapshot, DaemonSnapshot, DownloadRequest,
    ErrorReason, LinkAuth, LinkPairConfirmOutcome, LinkPairConfirmRequest, LinkPairHelloRequest,
    LinkPairHelloResponse, LinkPairRevealRequest, LinkPairRevealResponse, LinkPingInfo, PathStyle,
    QueueDto, TaskDto,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::Mutex;

use super::{
    ErrorContext, LinkOpError, LinkService, LinkServiceParts, LinkTaskCreator, lan_base_urls,
    resolve_receive_dir, rpc_error,
};
use crate::event_hub::AgentEventHub;
use crate::state::{AgentState, StateStore};

// ── 测试夹具 ───────────────────────────────────────────────────────────────

/// 记录每次建任务尝试；`reject_with_dir` 时带目录的尝试一律失败（模拟目录不可写）。
#[derive(Default)]
struct RecordingTasks {
    attempts: StdMutex<Vec<CreateTaskRequest>>,
    reject_with_dir: std::sync::atomic::AtomicBool,
}

#[async_trait]
impl LinkTaskCreator for RecordingTasks {
    async fn create_task(&self, request: CreateTaskRequest) -> Result<String, ApiError> {
        let mut attempts = self.attempts.lock().unwrap();
        let reject = self
            .reject_with_dir
            .load(std::sync::atomic::Ordering::Relaxed)
            && !request.save_dir.is_empty();
        attempts.push(request);
        if reject {
            return Err(ApiError::Internal("directory not writable".to_owned()));
        }
        Ok(format!("task-{}", attempts.len()))
    }
}

/// 只实现互联相关路由的宿主（其余下载能力不在本测试范围）。
struct LinkOnlyHost {
    link: Arc<LinkService>,
}

#[async_trait]
impl ApiHost for LinkOnlyHost {
    async fn list_tasks(&self) -> Result<Vec<TaskDto>, ApiError> {
        Ok(Vec::new())
    }
    async fn get_task(&self, _task_id: &str) -> Result<Option<TaskDto>, ApiError> {
        Ok(None)
    }
    async fn create_task(&self, _req: CreateTaskRequest) -> Result<String, ApiError> {
        Err(ApiError::Unavailable)
    }
    async fn delete_task(&self, _task_id: &str, _delete_files: bool) -> Result<(), ApiError> {
        Err(ApiError::Unavailable)
    }
    async fn pause_task(&self, _task_id: &str) -> Result<(), ApiError> {
        Err(ApiError::Unavailable)
    }
    async fn continue_task(&self, _task_id: &str) -> Result<(), ApiError> {
        Err(ApiError::Unavailable)
    }
    async fn pause_all(&self) -> Result<(), ApiError> {
        Err(ApiError::Unavailable)
    }
    async fn continue_all(&self) -> Result<(), ApiError> {
        Err(ApiError::Unavailable)
    }
    async fn list_queues(&self) -> Result<Vec<QueueDto>, ApiError> {
        Ok(Vec::new())
    }
    async fn submit_external(&self, _req: DownloadRequest) -> Result<(), ApiError> {
        Err(ApiError::Unavailable)
    }

    async fn link_ping_info(&self) -> Option<LinkPingInfo> {
        self.link.api_ping_info()
    }
    async fn link_pair_hello(
        &self,
        req: LinkPairHelloRequest,
        source: Option<IpAddr>,
    ) -> Result<LinkPairHelloResponse, ApiError> {
        self.link.api_pair_hello(req, source).await
    }
    async fn link_pair_reveal(
        &self,
        req: LinkPairRevealRequest,
    ) -> Result<LinkPairRevealResponse, ApiError> {
        self.link.api_pair_reveal(req).await
    }
    async fn link_pair_confirm(
        &self,
        req: LinkPairConfirmRequest,
    ) -> Result<LinkPairConfirmOutcome, ApiError> {
        self.link.api_pair_confirm(req).await
    }
    async fn link_create_task(&self, auth: LinkAuth, body: Vec<u8>) -> Result<String, ApiError> {
        self.link.api_create_task(auth, body).await
    }
    async fn link_peer_info(&self, auth: LinkAuth, body: Vec<u8>) -> Result<Vec<u8>, ApiError> {
        self.link.api_peer_info(auth, body).await
    }
}

/// 一个完整的互联节点：独立状态目录 + 独立身份 + 回环 HTTP 服务。
struct Node {
    service: Arc<LinkService>,
    events: AgentEventHub,
    state: Arc<Mutex<AgentState>>,
    store: Arc<StateStore>,
    tasks: Arc<RecordingTasks>,
    addr: SocketAddr,
    dir: PathBuf,
}

impl Drop for Node {
    fn drop(&mut self) {
        if let Err(error) = std::fs::remove_dir_all(&self.dir) {
            tracing::warn!(path = %self.dir.display(), %error, "link test node cleanup failed");
        }
    }
}

fn local_abs_dir(name: &str) -> String {
    if cfg!(windows) {
        format!(r"D:\{name}")
    } else {
        format!("/srv/{name}")
    }
}

fn foreign_abs_dir(name: &str) -> String {
    if cfg!(windows) {
        format!("/srv/{name}")
    } else {
        format!(r"D:\{name}")
    }
}

impl Node {
    async fn start(label: &str) -> Node {
        let dir = std::env::temp_dir().join(format!(
            "fluxdown-link-test-{label}-{}",
            uuid::Uuid::new_v4().simple()
        ));
        let store = Arc::new(StateStore::open(dir.clone()).await.unwrap());
        let state = Arc::new(Mutex::new(AgentState {
            device_name: label.to_owned(),
            ..AgentState::default()
        }));
        let events = AgentEventHub::new(AgentSnapshot::default());
        events.replace_daemon_snapshot(DaemonSnapshot {
            config: DaemonConfigSnapshot {
                revision: 1,
                values: [("default_save_dir".to_owned(), local_abs_dir(label))].into(),
            },
            ..DaemonSnapshot::default()
        });
        let tasks = Arc::new(RecordingTasks::default());
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let service = LinkService::new(LinkServiceParts {
            events: events.clone(),
            state: state.clone(),
            store: store.clone(),
            tasks: tasks.clone(),
            bound: addr,
            server_mode: false,
        });
        service.start().await.unwrap();
        let router = fluxdown_api::server::api_router(
            Arc::new(LinkOnlyHost {
                link: service.clone(),
            }),
            ApiServerConfig::from_config_map(&HashMap::new(), "test"),
        );
        tokio::spawn(async move {
            axum::serve(
                listener,
                router.into_make_service_with_connect_info::<SocketAddr>(),
            )
            .await
            .expect("serve link test node");
        });
        Node {
            service,
            events,
            state,
            store,
            tasks,
            addr,
            dir,
        }
    }

    fn address(&self) -> String {
        self.addr.to_string()
    }

    fn fingerprint(&self) -> String {
        self.service.api_ping_info().unwrap().fingerprint
    }

    fn attempts(&self) -> Vec<CreateTaskRequest> {
        self.tasks.attempts.lock().unwrap().clone()
    }
}

async fn wait_for<T>(what: &str, mut probe: impl FnMut() -> Option<T>) -> T {
    for _ in 0..250 {
        if let Some(value) = probe() {
            return value;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    panic!("timed out waiting for {what}");
}

/// 完整配对：`initiator` 用 `responder` 展示的码配对，响应端批准。
async fn pair(responder: &Node, initiator: &Node) {
    let code = responder.service.pairing_code().await.unwrap();
    let begin = initiator
        .service
        .pair_begin(&responder.address(), &code.code)
        .await
        .unwrap();
    let request = wait_for("incoming pairing request", || {
        responder
            .events
            .inspect(|snapshot| snapshot.link_pairing_requests.first().cloned())
    })
    .await;
    let finisher = {
        let service = initiator.service.clone();
        let token = begin.token.clone();
        tokio::spawn(async move { service.pair_finish(&token, true).await })
    };
    responder
        .service
        .approve(&request.session_id, true)
        .unwrap();
    let device = finisher.await.unwrap().unwrap();
    assert!(device.is_some(), "initiator must end up paired");
    let initiator_fp = initiator.fingerprint();
    wait_for("responder roster entry", || {
        responder
            .events
            .inspect(|snapshot| {
                snapshot
                    .linked_devices
                    .iter()
                    .any(|device| device.fingerprint == initiator_fp)
            })
            .then_some(())
    })
    .await;
}

#[tokio::test]
async fn bound_update_during_start_does_not_wait_for_readiness_or_expand_reachability() {
    let node = Node::start("port-rebind-start").await;
    let service = LinkService::new(LinkServiceParts {
        events: node.events.clone(),
        state: node.state.clone(),
        store: node.store.clone(),
        tasks: node.tasks.clone(),
        bound: node.addr,
        server_mode: false,
    });
    let locked_state = node.state.lock().await;
    let startup = service.start();
    tokio::pin!(startup);
    tokio::select! {
        result = &mut startup => panic!("startup completed with its state locked: {result:?}"),
        () = tokio::task::yield_now() => {}
    }
    let mut rebound = node.addr;
    rebound.set_port(17801);
    tokio::time::timeout(Duration::from_secs(1), service.update_bound(rebound))
        .await
        .expect("a bound update must not wait for link startup")
        .unwrap();
    drop(locked_state);
    startup.await.unwrap();
    let code = service.pairing_code().await.unwrap();
    assert_eq!(code.fingerprint, node.fingerprint());
    assert!(
        code.addresses.is_empty(),
        "loopback must stay private after rebinding"
    );
    assert!(matches!(
        service.update_bound("0.0.0.0:17801".parse().unwrap()).await,
        Err(LinkOpError::Invalid("bound"))
    ));
    service.update_bound(node.addr).await.unwrap();
    assert_eq!(
        service.api_ping_info().unwrap().fingerprint,
        node.fingerprint()
    );
}

/// 裸 TCP「反代」：对任何请求回固定的 HTTP 响应，并记录首行请求行。
async fn raw_server(response: impl Into<String>) -> (SocketAddr, Arc<StdMutex<Vec<String>>>) {
    let response: Arc<str> = Arc::from(response.into());
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let request_lines = Arc::new(StdMutex::new(Vec::new()));
    let recorded = request_lines.clone();
    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                break;
            };
            let recorded = recorded.clone();
            let response = response.clone();
            tokio::spawn(async move {
                let mut buffer = vec![0u8; 8192];
                let read = match socket.read(&mut buffer).await {
                    Ok(read) => read,
                    Err(error) => {
                        tracing::debug!(%error, "raw link test client closed before its request");
                        return;
                    }
                };
                let text = String::from_utf8_lossy(&buffer[..read]).into_owned();
                if let Some(line) = text.lines().next() {
                    recorded.lock().unwrap().push(line.to_owned());
                }
                // TLS/plaintext mismatch tests can close the connection as soon as they reject the reply.
                if let Err(error) = socket.write_all(response.as_bytes()).await {
                    tracing::debug!(%error, "raw link test client closed before its response");
                    return;
                }
                if let Err(error) = socket.shutdown().await {
                    tracing::debug!(%error, "raw link test client closed before writer shutdown");
                }
            });
        }
    });
    (addr, request_lines)
}

/// 带 JSON 体的固定 HTTP 响应（供 [`raw_server`] 使用；`Content-Length` 自动计算）。
fn http_json(status_line: &str, body: &str) -> String {
    format!(
        "HTTP/1.1 {status_line}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    )
}

/// 裸 TCP 冒充代理：`GET /ping` 由它自己应答并声称设备指纹是 `spoofed_fingerprint`，
/// 其余连接原样转发给 `upstream`——等价于网络上的中间人在发现阶段之后换了一台设备。
async fn spoofing_proxy(upstream: SocketAddr, spoofed_fingerprint: String) -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        loop {
            let Ok((mut client, _)) = listener.accept().await else {
                break;
            };
            let fingerprint = spoofed_fingerprint.clone();
            tokio::spawn(async move {
                let mut first = [0u8; 16];
                let Ok(read) = client.peek(&mut first).await else {
                    return;
                };
                if first[..read].starts_with(b"GET /ping") {
                    let mut request = vec![0u8; 4096];
                    let read = client
                        .read(&mut request)
                        .await
                        .expect("read spoofed ping request");
                    assert!(read > 0, "spoofed ping request must be present");
                    let body = serde_json::json!({
                        "success": true,
                        "app": "FluxDown",
                        "version": "9.9.9",
                        "message": "pong",
                        "linkFingerprint": fingerprint,
                        "linkName": "spoofed",
                    })
                    .to_string();
                    let response = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    );
                    client
                        .write_all(response.as_bytes())
                        .await
                        .expect("write spoofed ping response");
                    if let Err(error) = client.shutdown().await {
                        tracing::debug!(%error, "spoofing proxy client closed before writer shutdown");
                    }
                    return;
                }
                let Ok(mut server) = tokio::net::TcpStream::connect(upstream).await else {
                    return;
                };
                // 身份校验失败会让调用方提前关闭代理连接。
                if let Err(error) = tokio::io::copy_bidirectional(&mut client, &mut server).await {
                    tracing::debug!(%error, "spoofing proxy connection closed during relay");
                }
            });
        }
    });
    addr
}

/// 直接对 `addr` 发一条 JSON POST，返回 `(状态码, JSON 响应体)`。
async fn raw_post(addr: SocketAddr, path: &str, body: &str) -> (u16, serde_json::Value) {
    let mut stream = tokio::net::TcpStream::connect(addr).await.unwrap();
    let request = format!(
        "POST {path} HTTP/1.1\r\nHost: {addr}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut response = Vec::new();
    stream.read_to_end(&mut response).await.unwrap();
    let text = String::from_utf8(response).unwrap();
    let status: u16 = text.split_whitespace().nth(1).unwrap().parse().unwrap();
    let body = text.split("\r\n\r\n").nth(1).unwrap_or("");
    (status, serde_json::from_str(body).unwrap())
}

const NGINX_400: &str = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html\r\nContent-Length: 61\r\nConnection: close\r\n\r\n<html><body>The plain HTTP request was sent to HTTPS</body></html>";
const SPA_200: &str = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 28\r\nConnection: close\r\n\r\n<!doctype html><html></html>";

// ── 端到端 ─────────────────────────────────────────────────────────────────

#[tokio::test]
async fn pairs_over_real_http_exchanges_info_and_dispatches() {
    let responder = Node::start("nas").await;
    let initiator = Node::start("laptop").await;

    // 只监听回环：局域网设备连不上本机，配对码结果不带地址。
    let code = responder.service.pairing_code().await.unwrap();
    assert!(code.addresses.is_empty());
    assert_eq!(code.fingerprint, responder.fingerprint());
    assert_eq!(code.code.len(), 6);

    // 手动探测：`/ping` 带 `linkFingerprint`（Flutter 旧版对端靠它做 TOFU）。
    let peer = initiator.service.probe(&responder.address()).await.unwrap();
    assert_eq!(peer.fingerprint.as_deref(), Some(code.fingerprint.as_str()));
    assert_eq!(peer.source, "manual");

    let begin = initiator
        .service
        .pair_begin(&responder.address(), &code.code)
        .await
        .unwrap();
    assert_eq!(begin.peer_fingerprint, responder.fingerprint());
    // 入站请求进快照，两端 SAS 一致，由响应端用户决定。
    let request = wait_for("incoming pairing request", || {
        responder
            .events
            .inspect(|snapshot| snapshot.link_pairing_requests.first().cloned())
    })
    .await;
    assert_eq!(request.sas, begin.sas);
    assert_eq!(request.peer_fingerprint, initiator.fingerprint());
    assert_eq!(request.peer_name, "laptop");

    let finisher = {
        let service = initiator.service.clone();
        let token = begin.token.clone();
        tokio::spawn(async move { service.pair_finish(&token, true).await })
    };
    responder
        .service
        .approve(&request.session_id, true)
        .unwrap();
    let paired = finisher.await.unwrap().unwrap().unwrap();
    assert_eq!(paired.fingerprint, responder.fingerprint());
    assert_eq!(paired.name, "nas");
    // 处理过的请求不再留在快照里。
    assert!(
        responder
            .events
            .inspect(|snapshot| snapshot.link_pairing_requests.is_empty())
    );

    // 探测在线并交换默认目录 / 路径风格。
    let devices = initiator.service.refresh().await.unwrap();
    assert_eq!(devices.len(), 1);
    assert!(devices[0].online);
    assert_eq!(
        devices[0].default_save_dir.as_deref(),
        Some(local_abs_dir("nas").as_str())
    );
    assert_eq!(devices[0].path_style, Some(PathStyle::current()));
    let published = initiator
        .events
        .inspect(|snapshot| snapshot.linked_devices.clone());
    assert!(published[0].online);
    assert_eq!(published[0].default_save_dir, devices[0].default_save_dir);

    // 响应端也从这次已认证请求里学到了发起端的目录（写进它自己的名册）。
    let initiator_fp = initiator.fingerprint();
    let learned = wait_for("responder to learn initiator info", || {
        responder.events.inspect(|snapshot| {
            snapshot
                .linked_devices
                .iter()
                .find(|device| device.fingerprint == initiator_fp)
                .and_then(|device| device.default_save_dir.clone())
        })
    })
    .await;
    assert_eq!(learned, local_abs_dir("laptop"));
    // 名册（含链路密钥）持久化在私有状态里，快照 / 事件里没有密钥。
    let roster = responder.state.lock().await.linked_devices.clone();
    assert_eq!(roster.len(), 1);
    assert!(roster[0].get("linkSecretB64").is_some());
    let snapshot_json = serde_json::to_string(&responder.events.snapshot()).unwrap();
    assert!(!snapshot_json.contains("linkSecret"));

    // 下发：带本机风格绝对目录 → 原样到达接收端；不带目录 → 空串（用接收端默认目录）。
    let target = responder.fingerprint();
    let dir = local_abs_dir("incoming");
    let task_id = initiator
        .service
        .dispatch(
            &target,
            "https://example.com/big.iso",
            Some("big.iso"),
            Some(&dir),
        )
        .await
        .unwrap();
    assert_eq!(task_id, "task-1");
    let default_dir_id = initiator
        .service
        .dispatch(&target, "https://example.com/other.bin", None, None)
        .await
        .unwrap();
    assert_eq!(default_dir_id, "task-2");
    let attempts = responder.attempts();
    assert_eq!(attempts[0].url, "https://example.com/big.iso");
    assert_eq!(attempts[0].file_name, "big.iso");
    assert_eq!(attempts[0].save_dir, dir);
    assert_eq!(attempts[1].save_dir, "");

    // 发起端按对端上报的路径风格校验目录：外来风格路径在发送前就被拒绝。
    let error = initiator
        .service
        .dispatch(
            &target,
            "https://example.com/x",
            None,
            Some(&foreign_abs_dir("x")),
        )
        .await
        .unwrap_err();
    assert!(matches!(error, LinkOpError::SaveDirUnavailable));
    let data = rpc_error(&error, ErrorContext::Peer);
    assert_eq!(data.reason, Some(ErrorReason::SaveDirUnavailable));
    assert_eq!(responder.attempts().len(), 2, "rejected before any request");

    // 解除配对。
    initiator.service.remove(&target).await.unwrap();
    assert!(
        initiator
            .events
            .inspect(|snapshot| snapshot.linked_devices.is_empty())
    );
    let error = initiator.service.remove(&target).await.unwrap_err();
    assert_eq!(
        rpc_error(&error, ErrorContext::Peer).reason,
        Some(ErrorReason::PeerNotPaired)
    );
}

#[tokio::test]
async fn receiver_falls_back_to_default_dir_and_retries_without_dir() {
    let responder = Node::start("nas").await;
    let initiator = Node::start("laptop").await;
    pair(&responder, &initiator).await;
    let target = responder.fingerprint();

    // 绕过发送端校验（模拟旧版发起端）：外来风格绝对路径 → 接收端用默认目录。
    let manager = initiator.service.manager().unwrap();
    manager
        .dispatch(
            &target,
            "https://example.com/a.bin",
            Some(&foreign_abs_dir("nope")),
            None,
        )
        .await
        .unwrap();
    assert_eq!(responder.attempts()[0].save_dir, "");
    assert_eq!(responder.attempts().len(), 1);

    // 带目录建任务失败 → 去掉目录重试一次，任务仍然建成。
    responder
        .tasks
        .reject_with_dir
        .store(true, std::sync::atomic::Ordering::Relaxed);
    let dir = local_abs_dir("readonly");
    let task_id = initiator
        .service
        .dispatch(&target, "https://example.com/b.bin", None, Some(&dir))
        .await
        .unwrap();
    assert_eq!(task_id, "task-3");
    let attempts = responder.attempts();
    assert_eq!(attempts.len(), 3);
    assert_eq!(attempts[1].save_dir, dir);
    assert_eq!(attempts[2].save_dir, "");
}

#[tokio::test]
async fn wrong_code_is_reported_as_invalid_code_not_as_a_network_problem() {
    let responder = Node::start("nas").await;
    let initiator = Node::start("laptop").await;
    let code = responder.service.pairing_code().await.unwrap();
    let wrong = if code.code == "000000" {
        "000001"
    } else {
        "000000"
    };
    let error = initiator
        .service
        .pair_begin(&responder.address(), wrong)
        .await
        .unwrap_err();
    assert!(matches!(error, LinkOpError::Link(LinkError::InvalidCode)));
    assert_eq!(
        rpc_error(&error, ErrorContext::Pairing).reason,
        Some(ErrorReason::PairingCodeInvalid)
    );
}

#[tokio::test]
async fn pairing_aborts_before_reveal_when_peer_identity_differs_from_the_probed_one() {
    // 发现阶段（/ping）看到该地址是设备 F；握手时地址上出示的身份却是另一台设备：
    // 发起方必须在揭示临时公钥之前就终止，响应方不会弹出任何待核对的入站配对。
    let responder = Node::start("nas").await;
    let initiator = Node::start("laptop").await;
    let spoofed = "f".repeat(64);
    let proxy = spoofing_proxy(responder.addr, spoofed.clone())
        .await
        .to_string();

    let probed = initiator.service.probe(&proxy).await.unwrap();
    assert_eq!(probed.fingerprint.as_deref(), Some(spoofed.as_str()));

    let code = responder.service.pairing_code().await.unwrap();
    let error = initiator
        .service
        .pair_begin(&proxy, &code.code)
        .await
        .unwrap_err();
    assert!(
        matches!(error, LinkOpError::Link(LinkError::IdentityMismatch(_))),
        "{error:?}"
    );
    assert_eq!(
        rpc_error(&error, ErrorContext::Pairing).reason,
        Some(ErrorReason::PairingSignatureInvalid)
    );
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert!(
        responder
            .events
            .inspect(|snapshot| snapshot.link_pairing_requests.is_empty()),
        "no SAS may be shown on the responder before the initiator reveals"
    );
    assert!(
        initiator
            .events
            .inspect(|snapshot| snapshot.linked_devices.is_empty())
    );
}

#[tokio::test]
async fn old_protocol_peers_are_rejected_with_an_explicit_version_error() {
    let initiator = Node::start("laptop").await;

    // 旧版响应方：看不懂新 hello，以「invalid link hello payload」拒绝。
    let rejection = http_json(
        "400 Bad Request",
        r#"{"success":false,"message":"invalid link hello payload: missing field `initiatorEphPub` at line 1 column 120"}"#,
    );
    let (addr, _) = raw_server(rejection).await;
    let error = initiator
        .service
        .pair_begin(&addr.to_string(), "123456")
        .await
        .unwrap_err();
    assert!(
        matches!(error, LinkOpError::Link(LinkError::UnsupportedVersion)),
        "{error:?}"
    );
    let data = rpc_error(&error, ErrorContext::Pairing);
    assert_eq!(data.code, fluxdown_protocol::ApplicationErrorCode::Conflict);
    assert!(!data.retryable);
    assert_eq!(
        data.reason,
        Some(fluxdown_protocol::ErrorReason::PairingVersionMismatch)
    );

    // 旧版响应方的 hello 回复：带明文 SAS 与自己的签名、没有 protocolVersion。
    let legacy_reply = http_json(
        "200 OK",
        r#"{"sessionId":"s","responderEphPub":"AA","responderIdPub":"AA","responderSig":"AA","name":"old-nas","sas":"123456"}"#,
    );
    let (addr, _) = raw_server(legacy_reply).await;
    let error = initiator
        .service
        .pair_begin(&addr.to_string(), "123456")
        .await
        .unwrap_err();
    assert!(
        matches!(error, LinkOpError::Link(LinkError::UnsupportedVersion)),
        "{error:?}"
    );

    // 旧版发起方的 hello（带临时公钥明文、没有 protocolVersion / 承诺）：响应方以版本
    // 不兼容拒绝，且不消耗配对码。
    let responder = Node::start("nas").await;
    let code = responder.service.pairing_code().await.unwrap();
    let legacy_hello = serde_json::json!({
        "code": code.code,
        "initiatorEphPub": "AAAA",
        "initiatorIdPub": "AAAA",
        "initiatorSig": "AAAA",
        "name": "old",
        "platform": "",
        "appVersion": "",
        "initiatorAddrs": [],
    })
    .to_string();
    let (status, body) = raw_post(responder.addr, "/api/v1/link/pair/hello", &legacy_hello).await;
    assert_eq!(status, 400);
    assert_eq!(body["success"], false);
    let message = body["message"].as_str().unwrap();
    assert!(matches!(
        LinkError::from_wire_message(message),
        Some(LinkError::UnsupportedVersion)
    ));
    initiator
        .service
        .pair_begin(&responder.address(), &code.code)
        .await
        .expect("the pairing code must survive a version-mismatched hello");
}

#[tokio::test]
async fn proxy_400_html_maps_to_not_fluxdown_and_uses_the_base_path() {
    let initiator = Node::start("laptop").await;
    let (addr, request_lines) = raw_server(NGINX_400).await;

    // https 反代通常挂在子路径下：请求必须落在 `<base>/api/v1/link/...`。
    let error = initiator
        .service
        .pair_begin(&format!("http://{addr}/fluxdown/"), "123456")
        .await
        .unwrap_err();
    assert!(
        matches!(error, LinkOpError::Link(LinkError::NotFluxDown(_))),
        "an HTML 400 must not be reported as a wrong pairing code: {error:?}"
    );
    let data = rpc_error(&error, ErrorContext::Pairing);
    assert_eq!(data.reason, Some(ErrorReason::PairingNotFluxDown));
    assert!(!data.retryable);
    let lines = request_lines.lock().unwrap().clone();
    assert_eq!(lines.len(), 1);
    assert!(
        lines[0].starts_with("POST /fluxdown/api/v1/link/pair/hello "),
        "unexpected request line: {}",
        lines[0]
    );

    // 探测也一样：SPA 兜底页 200 不是 FluxDown。
    let (spa, _) = raw_server(SPA_200).await;
    let error = initiator
        .service
        .probe(&format!("http://{spa}"))
        .await
        .unwrap_err();
    assert!(matches!(
        error,
        LinkOpError::Link(LinkError::NotFluxDown(_))
    ));
}

#[tokio::test]
async fn unreachable_and_bad_addresses_are_distinguished() {
    let initiator = Node::start("laptop").await;

    // 端口上没有监听者：网络失败 → 对端不可达（可重试）。
    let closed = {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        listener.local_addr().unwrap()
    };
    let error = initiator
        .service
        .pair_begin(&closed.to_string(), "123456")
        .await
        .unwrap_err();
    let data = rpc_error(&error, ErrorContext::Pairing);
    assert_eq!(data.reason, Some(ErrorReason::PairingPeerUnreachable));
    assert!(data.retryable);

    // https 地址打到明文端口：TLS 握手失败也是网络失败，且根因进错误信息。
    let (plain, _) = raw_server(NGINX_400).await;
    let error = initiator
        .service
        .pair_begin(&format!("https://{plain}"), "123456")
        .await
        .unwrap_err();
    assert!(
        matches!(&error, LinkOpError::Link(LinkError::Io(cause)) if !cause.is_empty()),
        "{error:?}"
    );

    // 地址本身非法 → 参数错误并指明字段。
    let error = initiator
        .service
        .pair_begin("ftp://nas.local", "123456")
        .await
        .unwrap_err();
    let data = rpc_error(&error, ErrorContext::Pairing);
    assert_eq!(data.field.as_deref(), Some("address"));
}

#[tokio::test]
async fn rpc_dispatch_maps_methods_and_errors() {
    let node = Node::start("nas").await;
    let code = node
        .service
        .rpc(fluxdown_protocol::method::AGENT_LINK_PAIRING_CODE, None)
        .await
        .unwrap();
    assert_eq!(code["addresses"], serde_json::json!([]));
    assert_eq!(code["code"].as_str().unwrap().len(), 6);

    let error = node
        .service
        .rpc(
            fluxdown_protocol::method::AGENT_LINK_REMOVE,
            Some(serde_json::json!({ "fingerprint": "missing" })),
        )
        .await
        .unwrap_err();
    assert_eq!(error.reason, Some(ErrorReason::PeerNotPaired));

    // 参数缺字段 → InvalidArgument，而不是 panic 或假成功。
    let error = node
        .service
        .rpc(fluxdown_protocol::method::AGENT_LINK_PROBE, None)
        .await
        .unwrap_err();
    assert_eq!(
        error.code,
        fluxdown_protocol::ApplicationErrorCode::InvalidArgument
    );

    let stopped = node
        .service
        .rpc(fluxdown_protocol::method::AGENT_LINK_STOP_PAIRING, None)
        .await
        .unwrap();
    assert_eq!(stopped["ok"], true);
    // 已停止的配对码不能再被使用。
    let peer = Node::start("laptop").await;
    let error = peer
        .service
        .pair_begin(&node.address(), code["code"].as_str().unwrap())
        .await
        .unwrap_err();
    assert!(matches!(error, LinkOpError::Link(LinkError::InvalidCode)));
}

// ── 纯函数 ─────────────────────────────────────────────────────────────────

#[test]
fn receive_dir_only_accepts_local_style_absolute_paths() {
    assert_eq!(resolve_receive_dir(""), None);
    assert_eq!(resolve_receive_dir("   "), None);
    assert_eq!(resolve_receive_dir("relative/dir"), None);
    assert_eq!(resolve_receive_dir(&foreign_abs_dir("x")), None);
    let local = local_abs_dir("x");
    assert_eq!(resolve_receive_dir(&format!("  {local} ")), Some(local));
}

#[test]
fn receive_dir_rejects_parent_segments_and_unc_or_device_paths() {
    let local = local_abs_dir("x");
    assert_eq!(resolve_receive_dir(&format!("{local}/../y")), None);
    assert_eq!(resolve_receive_dir(r"\\host\share"), None);
    assert_eq!(resolve_receive_dir(r"\\?\C:\x"), None);
    assert_eq!(resolve_receive_dir("//host/share"), None);
}

#[test]
fn remote_file_names_must_be_plain_and_not_reserved_device_names() {
    assert!(super::valid_file_name("movie.mkv"));
    assert!(!super::valid_file_name("a/b"));
    assert!(!super::valid_file_name(".."));
    assert!(!super::valid_file_name("CON"));
    assert!(!super::valid_file_name("nul.txt"));
    assert!(!super::valid_file_name("com1.log"));
    assert!(super::valid_file_name("com10.log"));
}

#[test]
fn advertised_addresses_follow_the_bound_interface() {
    // 桌面默认只监听回环：没有任何局域网可达地址。
    assert!(lan_base_urls("127.0.0.1:17800".parse().unwrap()).is_empty());
    assert!(lan_base_urls("[::1]:17800".parse().unwrap()).is_empty());
    // 绑定了具体网卡地址：就是它。
    assert_eq!(
        lan_base_urls("192.168.1.20:17800".parse().unwrap()),
        vec!["http://192.168.1.20:17800".to_owned()]
    );
    // 通配地址：只会给出可路由的本机地址，绝不含回环 / 通配。
    for url in lan_base_urls("0.0.0.0:17800".parse().unwrap()) {
        let addr: SocketAddr = url.trim_start_matches("http://").parse().unwrap();
        assert!(
            !addr.ip().is_loopback() && !addr.ip().is_unspecified(),
            "{url}"
        );
        assert_eq!(addr.port(), 17800);
    }
}

#[test]
fn link_address_parser_is_shared_with_the_service() {
    // agent.link.* 的 `address` 与兼容 API 的 host+port 落到同一个候选形态。
    assert_eq!(
        PeerAddress::parse("nas.local").unwrap().to_candidate(),
        "nas.local:17800"
    );
    assert_eq!(
        PeerAddress::from_host_port("nas.local", 17800)
            .unwrap()
            .to_candidate(),
        "nas.local:17800"
    );
}
