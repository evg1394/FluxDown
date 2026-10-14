//! 剪贴板监听：`general.clipboard_watch` 打开时，识别复制内容里的下载链接并提交给捕获队列
//! （非静默 —— 由官方 UI 在新建下载窗口确认；没有 UI 时 agent 按需拉起）。
//!
//! 识别分两层：L0（[`classify`]，本地规则，微秒级）按扩展名 / 特殊协议立即接受；带下载信号
//! 但没有扩展名的链接进入 L1，经 daemon 只发一次 HEAD 判定是否为文件
//! （`general.clipboard_watch_probe`，默认开）。
//!
//! 归 agent 而不是界面：托盘驻留、界面全部关闭时仍要继续监听。

mod classify;
mod extract;

use std::collections::{HashSet, VecDeque};
use std::sync::Arc;
use std::time::{Duration, Instant};

use fluxdown_protocol::capture_link::normalize_capture_url;
use fluxdown_protocol::method;
use fluxdown_protocol::{LinkProbeParams, LinkProbeResult, LinkProbeVerdict};
use reqwest::Url;
use serde_json::Value;
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;

use crate::event_hub::AgentEventHub;
use crate::gateway::GatewayService;
use crate::platform::clipboard_change_count;

use classify::{Verdict, WatchedExtensions, classify, final_url_has_watched_extension};
use extract::extract_links;

const POLL_INTERVAL: Duration = Duration::from_millis(250);
const PREFERENCE_KEY: &str = "general.clipboard_watch";
const PROBE_PREFERENCE_KEY: &str = "general.clipboard_watch_probe";
const EXTENSIONS_PREFERENCE_KEY: &str = "general.clipboard_watch_extensions";
/// 同时进行的 L1 探测上限。
const MAX_CONCURRENT_PROBES: usize = 2;
/// 单次探测的整体等待上限（daemon 自身 4s 超时，这里兜住 daemon 无响应）。
const PROBE_TIMEOUT: Duration = Duration::from_secs(6);

/// 在专用线程上轮询剪贴板（各平台剪贴板 API 都是同步阻塞调用）。
pub fn spawn(events: AgentEventHub, gateway: Arc<GatewayService>, cancel: CancellationToken) {
    let runtime = tokio::runtime::Handle::current();
    let spawned = std::thread::Builder::new()
        .name("fluxdown-clipboard-watch".to_owned())
        .spawn(move || watch(&events, &gateway, &cancel, &runtime));
    if let Err(error) = spawned {
        tracing::warn!(error = %error, "clipboard watcher thread unavailable");
    }
}

/// 每轮读取的偏好。
struct Settings {
    enabled: bool,
    probe: bool,
}

/// 用户追加扩展名的解析缓存：偏好文本不变就不重新解析。
struct ExtensionsCache {
    raw: String,
    parsed: Arc<WatchedExtensions>,
}

fn watch(
    events: &AgentEventHub,
    gateway: &Arc<GatewayService>,
    cancel: &CancellationToken,
    runtime: &tokio::runtime::Handle,
) {
    let mut clipboard = match arboard::Clipboard::new() {
        Ok(clipboard) => clipboard,
        Err(error) => {
            tracing::info!(error = %error, "clipboard unavailable; clipboard watch disabled");
            return;
        }
    };
    let probes = Arc::new(Semaphore::new(MAX_CONCURRENT_PROBES));
    let mut extensions = ExtensionsCache {
        raw: String::new(),
        parsed: Arc::new(WatchedExtensions::default()),
    };
    let mut seen = SeenUrls::new();
    let mut previously_enabled = false;
    let mut last_count: Option<u64> = None;
    let mut last_text: Option<String> = None;
    while !cancel.is_cancelled() {
        std::thread::sleep(POLL_INTERVAL);
        let settings = events.inspect(|snapshot| {
            let values = &snapshot.preferences.values;
            let raw = values
                .get(EXTENSIONS_PREFERENCE_KEY)
                .and_then(Value::as_str)
                .unwrap_or_default();
            if extensions.raw != raw {
                raw.clone_into(&mut extensions.raw);
                extensions.parsed = Arc::new(WatchedExtensions::parse(raw));
            }
            Settings {
                enabled: values
                    .get(PREFERENCE_KEY)
                    .and_then(Value::as_bool)
                    .unwrap_or(false),
                probe: values
                    .get(PROBE_PREFERENCE_KEY)
                    .and_then(Value::as_bool)
                    .unwrap_or(true),
            }
        });
        let baseline = needs_baseline(previously_enabled, settings.enabled);
        previously_enabled = settings.enabled;
        if !settings.enabled {
            continue;
        }
        let count = clipboard_change_count();
        if !baseline && count.is_some() && count == last_count {
            continue;
        }
        last_count = count;
        let text = match clipboard.get_text() {
            Ok(text) => text,
            Err(error) => {
                // 复制的不是文本（图片、文件）很常见，不是故障。
                if !matches!(error, arboard::Error::ContentNotAvailable) {
                    tracing::debug!(error = %error, "clipboard text unavailable");
                }
                last_text = None;
                continue;
            }
        };
        if baseline {
            // 刚打开监听：当前剪贴板内容是旧数据，只作基线，不提交。
            last_text = Some(text);
            continue;
        }
        if last_text.as_deref() == Some(text.as_str()) {
            continue;
        }
        let links = extract_links(&text);
        last_text = Some(text);
        if links.is_empty() {
            continue;
        }
        let known = events.inspect(task_urls);
        let plan = plan_links(
            &links,
            &extensions.parsed,
            settings.probe,
            &known,
            &mut seen,
            Instant::now(),
        );
        submit(gateway, runtime, plan.accepted);
        for url in plan.probes {
            spawn_probe(
                runtime,
                Arc::clone(gateway),
                Arc::clone(&probes),
                Arc::clone(&extensions.parsed),
                url,
            );
        }
    }
}

/// 监听从关到开的那一轮：把当前剪贴板内容设为基线而不是当作新复制。
fn needs_baseline(previously_enabled: bool, enabled: bool) -> bool {
    enabled && !previously_enabled
}

/// 一次剪贴板变化的识别结果。
#[derive(Debug, Default, PartialEq, Eq)]
struct Plan {
    /// L0 立即接受的链接（合并为一次提交）。
    accepted: Vec<String>,
    /// 需要 L1 联网探测的链接。
    probes: Vec<String>,
}

/// 对候选链接做 L0 判定、去自产链接与去重。`known` 为现有任务的规范化 `url` / `origin_url`。
fn plan_links(
    links: &[String],
    extensions: &WatchedExtensions,
    probe_enabled: bool,
    known: &HashSet<String>,
    seen: &mut SeenUrls,
    now: Instant,
) -> Plan {
    let mut plan = Plan::default();
    for link in links {
        let verdict = classify(link, extensions);
        if verdict == Verdict::Reject || (verdict == Verdict::NeedsProbe && !probe_enabled) {
            continue;
        }
        let url = normalize_capture_url(link);
        if known.contains(&canonical(&url)) || !seen.insert(&url, now) {
            continue;
        }
        match verdict {
            Verdict::Accept => plan.accepted.push(url),
            Verdict::NeedsProbe => plan.probes.push(url),
            Verdict::Reject => {}
        }
    }
    plan
}

/// 现有任务的规范化 `url` 与 `origin_url`：FluxDown 自己「复制链接」复制出的就是它们。
fn task_urls(snapshot: &fluxdown_protocol::AgentSnapshot) -> HashSet<String> {
    snapshot
        .daemon
        .tasks
        .iter()
        .flat_map(|task| [task.url.as_str(), task.origin_url.as_str()])
        .filter(|url| !url.is_empty())
        .map(canonical)
        .collect()
}

/// 比较用的规范形式：能解析的 URL 用解析后的序列化（scheme / 主机小写、默认端口省略），
/// 并去掉末尾 `/`；其余（magnet、ed2k…）只去首尾空白。
fn canonical(url: &str) -> String {
    let url = url.trim();
    match Url::parse(url) {
        Ok(parsed) => parsed.as_str().trim_end_matches('/').to_owned(),
        Err(_) => url.to_owned(),
    }
}

/// L1 探测结果是否接受：确认是资源，或无法判定但最终地址扩展名命中监视扩展名表。
fn probe_accepts(result: &LinkProbeResult, extensions: &WatchedExtensions) -> bool {
    match result.verdict {
        LinkProbeVerdict::Resource => true,
        LinkProbeVerdict::Unknown => final_url_has_watched_extension(&result.final_url, extensions),
        LinkProbeVerdict::NotResource => false,
    }
}

/// 把一批链接作为**一次**捕获提交：换行拼接的单个请求会被 `capture` 拆成多个事务，
/// 仍在同一个新建下载对话框里呈现。
fn submit(gateway: &Arc<GatewayService>, runtime: &tokio::runtime::Handle, urls: Vec<String>) {
    if urls.is_empty() {
        return;
    }
    let gateway = Arc::clone(gateway);
    runtime.spawn(async move { submit_urls(&gateway, &urls).await });
}

async fn submit_urls(gateway: &GatewayService, urls: &[String]) {
    let submitted = gateway
        .dispatch_local(
            method::AGENT_CAPTURE_SUBMIT,
            serde_json::json!({ "request": { "url": urls.join("\n") }, "silent": false }),
        )
        .await;
    if let Err(error) = submitted {
        tracing::warn!(code = ?error.code, "clipboard capture rejected");
    }
}

fn spawn_probe(
    runtime: &tokio::runtime::Handle,
    gateway: Arc<GatewayService>,
    permits: Arc<Semaphore>,
    extensions: Arc<WatchedExtensions>,
    url: String,
) {
    runtime.spawn(async move {
        let result = {
            let permit = match permits.acquire_owned().await {
                Ok(permit) => permit,
                Err(error) => {
                    tracing::warn!(error = %error, "clipboard probe limiter closed");
                    return;
                }
            };
            let result = probe(&gateway, &url).await;
            drop(permit);
            result
        };
        if result.is_some_and(|result| probe_accepts(&result, &extensions)) {
            submit_urls(&gateway, &[url]).await;
        }
    });
}

/// 经 daemon 发一次只读 HEAD 探测；失败（含超时）记录后按「无结论」处理。
async fn probe(gateway: &GatewayService, url: &str) -> Option<LinkProbeResult> {
    let params = match serde_json::to_value(LinkProbeParams {
        url: url.to_owned(),
    }) {
        Ok(params) => params,
        Err(error) => {
            tracing::warn!(error = %error, "clipboard probe params unserializable");
            return None;
        }
    };
    let response = tokio::time::timeout(
        PROBE_TIMEOUT,
        gateway.dispatch_local(method::DAEMON_LINK_PROBE, params),
    )
    .await;
    let value = match response {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            tracing::debug!(code = ?error.code, "clipboard link probe failed");
            return None;
        }
        Err(_) => {
            tracing::debug!("clipboard link probe timed out");
            return None;
        }
    };
    match serde_json::from_value::<LinkProbeResult>(value) {
        Ok(result) => Some(result),
        Err(error) => {
            tracing::warn!(error = %error, "clipboard link probe result malformed");
            None
        }
    }
}

/// 最近处理过的 URL 集合：30 分钟内去重，最多保留 50 条（超出淘汰最旧的）。
/// 探测中的 URL 也已在其中，因此同一 URL 不会被重复探测。
struct SeenUrls {
    entries: VecDeque<(String, Instant)>,
}

impl SeenUrls {
    const CAPACITY: usize = 50;
    const TTL: Duration = Duration::from_secs(30 * 60);

    fn new() -> Self {
        Self {
            entries: VecDeque::new(),
        }
    }

    /// `url` 在 TTL 内已出现过则返回 `false`（去重命中）；否则记录并返回 `true`。
    fn insert(&mut self, url: &str, now: Instant) -> bool {
        self.entries
            .retain(|(_, seen_at)| now.saturating_duration_since(*seen_at) < Self::TTL);
        if self.entries.iter().any(|(seen, _)| seen == url) {
            return false;
        }
        while self.entries.len() >= Self::CAPACITY {
            self.entries.pop_front();
        }
        self.entries.push_back((url.to_owned(), now));
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn links(items: &[&str]) -> Vec<String> {
        items.iter().map(|item| (*item).to_owned()).collect()
    }

    fn plan(items: &[&str], probe: bool, known: &HashSet<String>, seen: &mut SeenUrls) -> Plan {
        plan_links(
            &links(items),
            &WatchedExtensions::default(),
            probe,
            known,
            seen,
            Instant::now(),
        )
    }

    #[test]
    fn seen_urls_dedup_within_ttl_and_expire_after() {
        let start = Instant::now();
        let mut seen = SeenUrls::new();
        assert!(seen.insert("https://a/b.zip", start));
        assert!(!seen.insert("https://a/b.zip", start + Duration::from_secs(60)));
        assert!(seen.insert(
            "https://a/b.zip",
            start + SeenUrls::TTL + Duration::from_secs(1)
        ));
    }

    #[test]
    fn seen_urls_evict_oldest_beyond_capacity() {
        let start = Instant::now();
        let mut seen = SeenUrls::new();
        for index in 0..=SeenUrls::CAPACITY {
            assert!(seen.insert(&format!("https://a/{index}.zip"), start));
        }
        assert!(seen.insert("https://a/0.zip", start));
        assert!(!seen.insert(&format!("https://a/{}.zip", SeenUrls::CAPACITY), start));
    }

    #[test]
    fn baseline_only_on_off_to_on() {
        assert!(needs_baseline(false, true));
        assert!(!needs_baseline(true, true));
        assert!(!needs_baseline(false, false));
        assert!(!needs_baseline(true, false));
    }

    #[test]
    fn plan_splits_accepted_probe_and_rejected() {
        let mut seen = SeenUrls::new();
        let result = plan(
            &[
                "https://a.com/b.zip",
                "https://sourceforge.net/projects/x/files/latest/download",
                "https://github.com/zerx-lab/FluxDown",
            ],
            true,
            &HashSet::new(),
            &mut seen,
        );
        assert_eq!(result.accepted, vec!["https://a.com/b.zip"]);
        assert_eq!(
            result.probes,
            vec!["https://sourceforge.net/projects/x/files/latest/download"]
        );
    }

    #[test]
    fn plan_drops_probe_candidates_when_probe_disabled() {
        let mut seen = SeenUrls::new();
        let result = plan(
            &["https://example.com/dl?id=1"],
            false,
            &HashSet::new(),
            &mut seen,
        );
        assert_eq!(result, Plan::default());
    }

    #[test]
    fn plan_skips_links_of_existing_tasks_and_repeats() {
        let known: HashSet<String> = [canonical("HTTPS://A.com/b.zip/")].into_iter().collect();
        let mut seen = SeenUrls::new();
        let first = plan(
            &["https://a.com/b.zip", "https://a.com/c.zip"],
            true,
            &known,
            &mut seen,
        );
        assert_eq!(first.accepted, vec!["https://a.com/c.zip"]);
        let second = plan(&["https://a.com/c.zip"], true, &known, &mut seen);
        assert_eq!(second, Plan::default());
    }

    #[test]
    fn plan_normalizes_deep_links_before_dedup() {
        let known: HashSet<String> = [canonical("https://a.com/b.zip")].into_iter().collect();
        let mut seen = SeenUrls::new();
        let result = plan(
            &["fluxdown://download?url=https%3A%2F%2Fa.com%2Fb.zip"],
            true,
            &known,
            &mut seen,
        );
        assert_eq!(result, Plan::default());
    }

    #[test]
    fn probe_verdicts_map_to_acceptance() {
        let extensions = WatchedExtensions::default();
        let result = |verdict, final_url: &str| LinkProbeResult {
            verdict,
            final_url: final_url.to_owned(),
            file_name: String::new(),
            mime: String::new(),
            total_bytes: 0,
        };
        assert!(probe_accepts(
            &result(LinkProbeVerdict::Resource, "https://a.com/x"),
            &extensions
        ));
        assert!(!probe_accepts(
            &result(LinkProbeVerdict::NotResource, "https://a.com/x.zip"),
            &extensions
        ));
        assert!(probe_accepts(
            &result(LinkProbeVerdict::Unknown, "https://cdn.a.com/x.zip"),
            &extensions
        ));
        assert!(!probe_accepts(
            &result(LinkProbeVerdict::Unknown, "https://cdn.a.com/x"),
            &extensions
        ));
    }
}
