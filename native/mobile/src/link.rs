//! 传输无关的连接抽象：session 核心只依赖这里的 trait，本地（进程内 agent）与远端
//! （WebSocket `/rpc`）各自实现；单元测试用脚本化的内存实现。

use std::sync::Arc;

use async_trait::async_trait;
use fluxdown_protocol::{EventFrame, RpcRequest, ServiceHello};
use serde_json::Value;

use crate::error::{FluxError, HostErrorDto};

/// 一条已握手连接上的 RPC 调用端（可并发共享）。
#[async_trait]
pub(crate) trait Caller: Send + Sync {
    async fn call(&self, method: &str, params: Option<Value>) -> Result<Value, FluxError>;
}

/// 连接上的事件流：独占读取。
#[async_trait]
pub(crate) trait EventSource: Send {
    async fn next(&mut self) -> LinkEvent;
}

/// 事件流的下一项。
#[derive(Debug)]
pub(crate) enum LinkEvent {
    Frame(Box<EventFrame>),
    /// 事件丢失（本地广播落后）：必须在新连接上重同步。
    Lagged,
    Closed(CloseInfo),
}

/// 连接结束的原因。
#[derive(Debug, Eq, PartialEq)]
pub(crate) enum CloseInfo {
    /// 服务端广播队列溢出（close code 4009）：立即重连并重拉快照，不退避。
    EventGap,
    /// 服务按 `system.shutdown` 退出（close reason `service-quit`）：停止重连。
    ServiceQuit,
    /// 其余断线：指数退避后重连。
    Lost(String),
}

/// 握手成功的连接。
pub(crate) struct Established {
    pub hello: ServiceHello,
    pub caller: Arc<dyn Caller>,
    pub events: Box<dyn EventSource>,
}

/// 建连失败。
#[derive(Debug)]
pub(crate) enum ConnectFailure {
    /// 不可恢复（鉴权失败、协议不兼容、主机已退出）。
    Fatal(HostErrorDto),
    /// 可恢复（网络不可达、握手超时……），带说明。
    Retry(String),
}

/// 建立连接并完成 `system.hello`。`hello` 是已构造好的首帧请求。
#[async_trait]
pub(crate) trait Connector: Send + Sync {
    async fn connect(&self, hello: RpcRequest) -> Result<Established, ConnectFailure>;
}
