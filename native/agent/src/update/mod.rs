//! 应用内更新：检查 → 后台下载并校验 → 一键安装并重启。
//!
//! `UpdateService` 独占 `UpdateStatusDto`，经 `AgentEvent::UpdateChanged` 发布（相同状态去重，
//! 下载进度节流）。状态迁移：
//!
//! ```text
//! Idle/UpToDate/Available/Failed --check--> Checking --> UpToDate | Available | Failed(Network)
//! Available/Failed --download--> Downloading --> Ready | Failed(Network/Verify/Storage)
//! Downloading --cancel--> Available
//! Available/Failed --install--> Downloading(installPending) --> Installing
//! Ready --install--> Installing --> (重启) | Failed(Install/..)
//! ```
//!
//! 下载 / 就绪 / 安装中不再重新检查，避免丢失已就绪的包。

mod download;
pub(crate) mod install;
mod release;
pub mod restart;

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use fluxdown_protocol::{
    APP_VERSION, AgentEvent, ReleaseNoteDto, UpdateFailure, UpdateManualReason, UpdatePhase,
    UpdateStatusDto,
};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex as AsyncMutex;
use tokio_util::sync::CancellationToken;

use self::download::{DownloadError, DownloadSpec, download_verified};
pub(crate) use self::install::InstallTarget;
use self::release::{
    ChangelogResponse, ComponentRelease, Endpoint, RELEASE_PAGE_URL, RawRelease,
    checksum_file_names, find_checksum, normalize_channel,
};
pub use self::release::{UpdateError, is_newer};
use crate::event_hub::AgentEventHub;
use crate::http_client::LazyHttpClient;

const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);
const FIRST_CHECK_DELAY: Duration = Duration::from_secs(30);
const CHECK_INTERVAL: Duration = Duration::from_secs(6 * 60 * 60);
const PROGRESS_INTERVAL: Duration = Duration::from_millis(500);
const PENDING_FILE: &str = "pending.json";
const PREF_AUTO_CHECK: &str = "general.auto_check_update";
const PREF_CHANNEL: &str = "general.update_channel";

/// 构造 `UpdateService` 所需的依赖。
pub struct UpdateParts {
    pub events: AgentEventHub,
    /// agent 数据目录；更新包落在其 `updates/` 下。
    pub data_dir: PathBuf,
    pub(crate) target: InstallTarget,
    /// 安装成功后触发完全退出（带重启语义）。
    pub request_restart: Box<dyn Fn() + Send + Sync>,
}

/// 可安装的更新候选。
#[derive(Clone, Debug)]
struct Candidate {
    version: String,
    tag: String,
    asset_name: String,
    size: u64,
    sha256: String,
}

struct Job {
    id: u64,
    cancel: CancellationToken,
}

struct Core {
    status: UpdateStatusDto,
    published: UpdateStatusDto,
    candidate: Option<Candidate>,
    job: Option<Job>,
    next_job: u64,
    last_progress: Option<Instant>,
    /// 用户取消过下载的版本：周期任务不再为它自动下载。
    declined_auto: Option<String>,
}

/// 上次安装尝试的落盘记录（跨重启）。
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PendingRecord {
    target_version: String,
    from_version: String,
    asset_name: String,
    created_at_ms: u64,
}

/// 一次检查的结果。
struct CheckOutcome {
    release: Option<ComponentRelease>,
    has_update: bool,
    notes: Vec<ReleaseNoteDto>,
    manual_reason: Option<UpdateManualReason>,
    manual_url: String,
    candidate: Option<Candidate>,
}

pub struct UpdateService {
    events: AgentEventHub,
    current_version: String,
    updates_dir: PathBuf,
    target: InstallTarget,
    endpoint: Endpoint,
    api_http: LazyHttpClient,
    download_http: LazyHttpClient,
    request_restart: Box<dyn Fn() + Send + Sync>,
    core: Mutex<Core>,
    /// 检查单飞。
    check_gate: AsyncMutex<()>,
    /// 同一时刻只有一个下载任务写 `.part`（取消后的旧任务先退出再轮到新任务）。
    download_gate: AsyncMutex<()>,
}

/// 放进初始 `AgentSnapshot.update` 的状态。
#[must_use]
pub(crate) fn initial_status(target: &InstallTarget) -> UpdateStatusDto {
    initial_status_for(APP_VERSION, target)
}

fn initial_status_for(current_version: &str, target: &InstallTarget) -> UpdateStatusDto {
    UpdateStatusDto {
        current_version: current_version.to_owned(),
        install_kind: target.kind,
        manual_reason: target.manual_reason,
        release_page_url: RELEASE_PAGE_URL.to_owned(),
        ..UpdateStatusDto::default()
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| {
            u64::try_from(elapsed.as_millis()).unwrap_or(u64::MAX)
        })
}

/// 不支持应用内安装的宿主（移动端内嵌 agent、测试）使用的安装目标。
#[must_use]
pub(crate) fn unsupported_target() -> InstallTarget {
    InstallTarget {
        kind: fluxdown_protocol::UpdateInstallKind::Unknown,
        component: install::ReleaseComponent::Desktop,
        asset_keys: Vec::new(),
        manual_reason: Some(UpdateManualReason::Unsupported),
    }
}

impl UpdateService {
    #[must_use]
    pub fn new(parts: UpdateParts) -> Self {
        Self::with_version(parts, APP_VERSION, Endpoint::resolve())
    }

    /// 不支持应用内安装的宿主（移动端内嵌 agent、测试）：只检查版本，永不下载安装。
    #[must_use]
    pub fn unsupported(events: AgentEventHub, data_dir: PathBuf) -> Self {
        Self::new(UpdateParts {
            events,
            data_dir,
            target: unsupported_target(),
            request_restart: Box::new(|| {}),
        })
    }

    fn with_version(parts: UpdateParts, current_version: &str, endpoint: Endpoint) -> Self {
        let user_agent = format!("fluxdown-agent/{current_version}");
        let api_agent = user_agent.clone();
        let api_http = LazyHttpClient::new(move || {
            reqwest::Client::builder()
                .connect_timeout(Duration::from_secs(10))
                .timeout(REQUEST_TIMEOUT)
                .user_agent(api_agent.clone())
        });
        // 更新包体积大：不设整体超时，只限制连接与读停顿。
        let download_http = LazyHttpClient::new(move || {
            reqwest::Client::builder()
                .connect_timeout(Duration::from_secs(10))
                .read_timeout(Duration::from_secs(30))
                .user_agent(user_agent.clone())
        });
        let status = initial_status_for(current_version, &parts.target);
        Self {
            events: parts.events,
            current_version: current_version.to_owned(),
            updates_dir: parts.data_dir.join("updates"),
            target: parts.target,
            endpoint,
            api_http,
            download_http,
            request_restart: parts.request_restart,
            core: Mutex::new(Core {
                published: status.clone(),
                status,
                candidate: None,
                job: None,
                next_job: 0,
                last_progress: None,
                declined_auto: None,
            }),
            check_gate: AsyncMutex::new(()),
            download_gate: AsyncMutex::new(()),
        }
    }

    /// 启动后台任务：清理上次替换残留、对账 `pending.json`，`auto_check` 时进入周期检查。
    pub fn start(self: &Arc<Self>, cancel: CancellationToken, auto_check: bool) {
        let service = Arc::clone(self);
        tokio::spawn(async move {
            if let Err(error) = tokio::task::spawn_blocking(install::cleanup_leftovers).await {
                tracing::debug!(error = %error, "update leftover cleanup task failed");
            }
            service.reconcile_pending().await;
            if auto_check {
                service.periodic(cancel).await;
            }
        });
    }

    #[must_use]
    pub fn status(&self) -> UpdateStatusDto {
        self.lock().status.clone()
    }

    fn lock(&self) -> MutexGuard<'_, Core> {
        self.core
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// 发布与上次已发布不同的状态。
    fn commit(&self, core: &mut Core) {
        if core.status != core.published {
            core.published = core.status.clone();
            self.events
                .publish(AgentEvent::UpdateChanged(core.status.clone()));
        }
    }

    fn mutate<R>(&self, change: impl FnOnce(&mut Core) -> R) -> R {
        let mut core = self.lock();
        let result = change(&mut core);
        self.commit(&mut core);
        result
    }

    fn progress(&self, job_id: u64, bytes: u64) {
        let mut core = self.lock();
        if core.job.as_ref().map(|job| job.id) != Some(job_id) {
            return;
        }
        core.status.downloaded_bytes = bytes;
        let due = core
            .last_progress
            .is_none_or(|at| at.elapsed() >= PROGRESS_INTERVAL);
        let finished = core.status.asset_size > 0 && bytes >= core.status.asset_size;
        if due || finished {
            core.last_progress = Some(Instant::now());
            self.commit(&mut core);
        }
    }

    // ------------------------------------------------------------------ 检查

    /// 查询渠道最新版本；返回检查后的状态。
    pub async fn check(self: &Arc<Self>, channel: &str) -> Result<UpdateStatusDto, UpdateError> {
        self.check_inner(channel, false).await
    }

    /// `silent`：周期检查失败时静默恢复检查前的状态，不把界面翻成 Failed。
    async fn check_inner(
        self: &Arc<Self>,
        channel: &str,
        silent: bool,
    ) -> Result<UpdateStatusDto, UpdateError> {
        let channel = normalize_channel(channel)?;
        let _gate = match self.check_gate.try_lock() {
            Ok(gate) => gate,
            Err(_) => {
                // 单飞：等在途检查结束，直接用它的结果。
                drop(self.check_gate.lock().await);
                return Ok(self.status());
            }
        };
        let previous = {
            let mut core = self.lock();
            if matches!(
                core.status.phase,
                UpdatePhase::Downloading | UpdatePhase::Ready | UpdatePhase::Installing
            ) {
                return Ok(core.status.clone());
            }
            let previous = core.status.clone();
            core.status.phase = UpdatePhase::Checking;
            core.status.channel = channel.to_owned();
            core.status.failure = None;
            core.status.error_detail.clear();
            self.commit(&mut core);
            previous
        };
        match self.fetch_outcome(channel).await {
            Ok(outcome) => Ok(self.mutate(|core| {
                apply_outcome(core, outcome, &self.target);
                core.status.clone()
            })),
            Err(error) => {
                self.mutate(|core| {
                    if silent {
                        core.status = previous;
                    } else {
                        core.status.phase = UpdatePhase::Failed;
                        core.status.failure = Some(match error {
                            UpdateError::Http(_)
                            | UpdateError::Status(_)
                            | UpdateError::Client(_) => UpdateFailure::Network,
                            _ => UpdateFailure::Unknown,
                        });
                        core.status.error_detail = format!("{error:#}");
                    }
                });
                Err(error)
            }
        }
    }

    async fn fetch_outcome(&self, channel: &str) -> Result<CheckOutcome, UpdateError> {
        let http = self.api_http.get().await?;
        let raw: RawRelease = get_json(http, &self.endpoint.release_url(channel)).await?;
        let Some(release) = raw.component(&self.target, &self.endpoint) else {
            return Ok(CheckOutcome {
                release: None,
                has_update: false,
                notes: Vec::new(),
                manual_reason: self.target.manual_reason,
                manual_url: String::new(),
                candidate: None,
            });
        };
        let has_update = is_newer(&release.version, &self.current_version).unwrap_or(false);
        if !has_update {
            return Ok(CheckOutcome {
                release: Some(release),
                has_update,
                notes: Vec::new(),
                manual_reason: self.target.manual_reason,
                manual_url: String::new(),
                candidate: None,
            });
        }
        let notes = self.fetch_notes(http, channel).await;
        let manual_url = release
            .asset
            .as_ref()
            .map(|asset| asset.download_url.clone())
            .unwrap_or_default();
        let mut manual_reason = self.target.manual_reason;
        let mut candidate = None;
        if manual_reason.is_none() {
            match &release.asset {
                None => manual_reason = Some(UpdateManualReason::NoAsset),
                Some(asset) => match self.fetch_checksum(http, &release, &asset.name).await? {
                    None => manual_reason = Some(UpdateManualReason::NoAsset),
                    Some(sha256) => {
                        manual_reason = install::preflight(&self.target).await;
                        if manual_reason.is_none() {
                            candidate = Some(Candidate {
                                version: release.version.clone(),
                                tag: release.tag.clone(),
                                asset_name: asset.name.clone(),
                                size: asset.size,
                                sha256,
                            });
                        }
                    }
                },
            }
        }
        Ok(CheckOutcome {
            release: Some(release),
            has_update,
            notes,
            manual_reason,
            manual_url,
            candidate,
        })
    }

    /// 组件哨兵优先，回退合并清单；都没有该资产的条目 → `None`。
    async fn fetch_checksum(
        &self,
        http: &reqwest::Client,
        release: &ComponentRelease,
        asset_name: &str,
    ) -> Result<Option<String>, UpdateError> {
        for file in checksum_file_names(self.target.component) {
            let url = self.endpoint.package_url(file, &release.tag)?;
            let Some(text) = get_text_optional(http, &url).await? else {
                continue;
            };
            if let Some(sha256) = find_checksum(&text, asset_name) {
                return Ok(Some(sha256));
            }
        }
        Ok(None)
    }

    /// 更新说明是附属信息：拉取失败只降级为空列表，不影响版本判定。
    async fn fetch_notes(&self, http: &reqwest::Client, channel: &str) -> Vec<ReleaseNoteDto> {
        let url = self.endpoint.changelog_url(channel, &self.current_version);
        match get_json::<ChangelogResponse>(http, &url).await {
            Ok(changelog) => changelog.into_notes(&self.current_version),
            Err(error) => {
                tracing::debug!(error = %error, "changelog fetch failed");
                Vec::new()
            }
        }
    }

    // ------------------------------------------------------------------ 下载 / 安装

    /// 后台下载更新包；幂等。
    pub async fn download(self: &Arc<Self>) -> Result<UpdateStatusDto, UpdateError> {
        self.refresh_candidate().await?;
        self.begin(false)
    }

    /// 一键更新：包未就绪时先下载（`installPending`），就绪后安装并重启。
    pub async fn install(self: &Arc<Self>) -> Result<UpdateStatusDto, UpdateError> {
        self.refresh_candidate().await?;
        self.begin(true)
    }

    /// 失败态可能没有候选包（上次安装未完成的对账结果只知道目标版本）：先重新检查拿到
    /// 资产与校验和，再进入下载 / 安装。
    async fn refresh_candidate(self: &Arc<Self>) -> Result<(), UpdateError> {
        let (stale, channel) = {
            let core = self.lock();
            (
                core.candidate.is_none()
                    && core.status.has_update
                    && core.status.phase == UpdatePhase::Failed,
                core.status.channel.clone(),
            )
        };
        if !stale {
            return Ok(());
        }
        let channel = if channel.is_empty() {
            self.pref(PREF_CHANNEL)
                .and_then(|value| value.as_str().map(str::to_owned))
                .unwrap_or_else(|| "stable".to_owned())
        } else {
            channel
        };
        self.check_inner(&channel, false).await.map(drop)
    }

    fn begin(self: &Arc<Self>, install_now: bool) -> Result<UpdateStatusDto, UpdateError> {
        let mut core = self.lock();
        if !core.status.has_update {
            return Err(UpdateError::NoUpdate);
        }
        if let Some(reason) = core.status.manual_reason {
            return Err(UpdateError::Manual(reason));
        }
        match core.status.phase {
            UpdatePhase::Installing => return Ok(core.status.clone()),
            UpdatePhase::Downloading => {
                if install_now {
                    core.status.install_pending = true;
                    self.commit(&mut core);
                }
                return Ok(core.status.clone());
            }
            UpdatePhase::Ready => {
                if install_now && let Some(candidate) = core.candidate.clone() {
                    core.status.phase = UpdatePhase::Installing;
                    self.commit(&mut core);
                    self.spawn_install(candidate);
                }
                return Ok(core.status.clone());
            }
            UpdatePhase::Available | UpdatePhase::Failed => {}
            UpdatePhase::Idle | UpdatePhase::Checking | UpdatePhase::UpToDate => {
                return Err(UpdateError::NoUpdate);
            }
        }
        let Some(candidate) = core.candidate.clone() else {
            return Err(UpdateError::NoUpdate);
        };
        core.next_job += 1;
        let id = core.next_job;
        let cancel = CancellationToken::new();
        core.job = Some(Job {
            id,
            cancel: cancel.clone(),
        });
        core.last_progress = None;
        core.status.phase = UpdatePhase::Downloading;
        core.status.downloaded_bytes = 0;
        core.status.install_pending = install_now;
        core.status.failure = None;
        core.status.error_detail.clear();
        self.commit(&mut core);
        let status = core.status.clone();
        drop(core);
        tokio::spawn(Arc::clone(self).download_task(id, candidate, cancel));
        Ok(status)
    }

    /// 取消进行中的下载与待安装请求。
    pub fn cancel(&self) -> UpdateStatusDto {
        self.mutate(|core| {
            if core.status.phase == UpdatePhase::Downloading
                && let Some(job) = core.job.take()
            {
                job.cancel.cancel();
                core.declined_auto = core.candidate.as_ref().map(|c| c.version.clone());
                core.status.phase = UpdatePhase::Available;
                core.status.downloaded_bytes = 0;
                core.status.install_pending = false;
            }
            core.status.clone()
        })
    }

    async fn download_task(
        self: Arc<Self>,
        id: u64,
        candidate: Candidate,
        cancel: CancellationToken,
    ) {
        let _gate = self.download_gate.lock().await;
        if cancel.is_cancelled() {
            return;
        }
        self.prune_other_versions(&candidate.version).await;
        let result = self.run_download(id, &candidate, &cancel).await;
        let mut core = self.lock();
        if core.job.as_ref().map(|job| job.id) != Some(id) {
            return;
        }
        core.job = None;
        match result {
            Ok(_) => {
                core.status.downloaded_bytes = candidate.size.max(core.status.downloaded_bytes);
                if core.status.install_pending {
                    core.status.phase = UpdatePhase::Installing;
                    core.status.install_pending = false;
                    self.commit(&mut core);
                    self.spawn_install(candidate);
                } else {
                    core.status.phase = UpdatePhase::Ready;
                    self.commit(&mut core);
                }
            }
            Err(DownloadError::Cancelled) => {
                core.status.phase = UpdatePhase::Available;
                core.status.install_pending = false;
                self.commit(&mut core);
            }
            Err(error) => {
                tracing::warn!(error = %error, "update download failed");
                core.status.phase = UpdatePhase::Failed;
                core.status.install_pending = false;
                core.status.failure = Some(error.failure());
                core.status.error_detail = format!("{error:#}");
                self.commit(&mut core);
            }
        }
    }

    async fn run_download(
        &self,
        id: u64,
        candidate: &Candidate,
        cancel: &CancellationToken,
    ) -> Result<PathBuf, DownloadError> {
        let http = self.download_http.get().await?;
        let url = self
            .endpoint
            .package_url(&candidate.asset_name, &candidate.tag)
            .map_err(|error| DownloadError::InvalidUrl(format!("{error:#}")))?;
        let dir = self.version_dir(&candidate.version);
        let spec = DownloadSpec {
            url: &url,
            dir: &dir,
            name: &candidate.asset_name,
            size: candidate.size,
            sha256: &candidate.sha256,
        };
        download_verified(http, &spec, cancel, |bytes| self.progress(id, bytes)).await
    }

    fn version_dir(&self, version: &str) -> PathBuf {
        self.updates_dir.join(version)
    }

    /// 只保留目标版本的目录与 `pending.json`。
    async fn prune_other_versions(&self, keep: &str) {
        let mut entries = match tokio::fs::read_dir(&self.updates_dir).await {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return,
            Err(error) => {
                tracing::debug!(error = %error, "cannot list updates dir");
                return;
            }
        };
        loop {
            let entry = match entries.next_entry().await {
                Ok(Some(entry)) => entry,
                Ok(None) => return,
                Err(error) => {
                    tracing::debug!(error = %error, "cannot read updates dir entry");
                    return;
                }
            };
            let is_dir = entry.file_type().await.is_ok_and(|kind| kind.is_dir());
            if is_dir
                && entry.file_name().to_string_lossy() != keep
                && let Err(error) = tokio::fs::remove_dir_all(entry.path()).await
            {
                tracing::debug!(error = %error, path = %entry.path().display(), "cannot remove stale update dir");
            }
        }
    }

    fn spawn_install(self: &Arc<Self>, candidate: Candidate) {
        tokio::spawn(Arc::clone(self).install_task(candidate));
    }

    async fn install_task(self: Arc<Self>, candidate: Candidate) {
        let dir = self.version_dir(&candidate.version);
        let package = dir.join(&candidate.asset_name);
        let work_dir = dir.join("staging");
        if let Err(error) = self.prepare_install(&candidate, &work_dir).await {
            self.fail(UpdateFailure::Storage, format!("{error:#}"));
            return;
        }
        match install::apply(&self.target, &package, &work_dir).await {
            Ok(plan) => {
                restart::schedule(plan);
                (self.request_restart)();
            }
            Err(error) => {
                tracing::warn!(error = %error, "update install failed");
                self.remove_pending().await;
                self.fail(error.failure(), format!("{error:#}"));
            }
        }
    }

    /// 写 `pending.json` 并重建空的暂存目录。
    async fn prepare_install(&self, candidate: &Candidate, work_dir: &Path) -> std::io::Result<()> {
        let record = PendingRecord {
            target_version: candidate.version.clone(),
            from_version: self.current_version.clone(),
            asset_name: candidate.asset_name.clone(),
            created_at_ms: now_ms(),
        };
        let bytes = serde_json::to_vec_pretty(&record).map_err(std::io::Error::other)?;
        let pending = self.updates_dir.join(PENDING_FILE);
        let temp = self.updates_dir.join(format!("{PENDING_FILE}.tmp"));
        tokio::fs::write(&temp, bytes).await?;
        tokio::fs::rename(&temp, &pending).await?;
        match tokio::fs::remove_dir_all(work_dir).await {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
        tokio::fs::create_dir_all(work_dir).await
    }

    async fn remove_pending(&self) {
        let pending = self.updates_dir.join(PENDING_FILE);
        match tokio::fs::remove_file(&pending).await {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => tracing::debug!(error = %error, "cannot remove pending update record"),
        }
    }

    fn fail(&self, failure: UpdateFailure, detail: String) {
        self.mutate(|core| {
            core.job = None;
            core.status.phase = UpdatePhase::Failed;
            core.status.install_pending = false;
            core.status.failure = Some(failure);
            core.status.error_detail = detail;
        });
    }

    // ------------------------------------------------------------------ 启动对账 / 周期

    /// 上次安装留下 `pending.json`：版本已是目标 → 成功并清理；否则报告未完成。
    async fn reconcile_pending(&self) {
        let pending = self.updates_dir.join(PENDING_FILE);
        let bytes = match tokio::fs::read(&pending).await {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return,
            Err(error) => {
                tracing::debug!(error = %error, "cannot read pending update record");
                return;
            }
        };
        let record = match serde_json::from_slice::<PendingRecord>(&bytes) {
            Ok(record) => record,
            Err(error) => {
                tracing::debug!(error = %error, "invalid pending update record dropped");
                self.remove_pending().await;
                return;
            }
        };
        let installed = record.target_version.trim_start_matches('v')
            == self.current_version.trim_start_matches('v');
        if installed {
            tracing::info!(version = %record.target_version, "update installed; cleaning update files");
            if let Err(error) = tokio::fs::remove_dir_all(&self.updates_dir).await {
                tracing::debug!(error = %error, "cannot clean updates dir");
            }
            return;
        }
        tracing::warn!(
            target = %record.target_version,
            running = %self.current_version,
            "previous update did not complete"
        );
        self.remove_pending().await;
        self.mutate(|core| {
            if core.status.phase != UpdatePhase::Idle {
                return;
            }
            core.status.phase = UpdatePhase::Failed;
            core.status.has_update =
                is_newer(&record.target_version, &self.current_version).unwrap_or(false);
            core.status.latest_version = record.target_version.clone();
            core.status.failure = Some(UpdateFailure::InstallIncomplete);
            core.status.error_detail = format!(
                "running version {} after installing {}",
                self.current_version, record.target_version
            );
        });
    }

    fn pref(&self, key: &str) -> Option<serde_json::Value> {
        self.events
            .inspect(|snapshot| snapshot.preferences.values.get(key).cloned())
    }

    async fn periodic(self: &Arc<Self>, cancel: CancellationToken) {
        let mut delay = FIRST_CHECK_DELAY;
        loop {
            tokio::select! {
                () = cancel.cancelled() => return,
                () = tokio::time::sleep(delay) => {}
            }
            delay = CHECK_INTERVAL;
            if self.pref(PREF_AUTO_CHECK).and_then(|value| value.as_bool()) == Some(false) {
                continue;
            }
            let channel = self
                .pref(PREF_CHANNEL)
                .and_then(|value| value.as_str().map(str::to_owned))
                .unwrap_or_else(|| "stable".to_owned());
            let status = match self.check_inner(&channel, true).await {
                Ok(status) => status,
                Err(error) => {
                    tracing::debug!(error = %error, "periodic update check failed");
                    continue;
                }
            };
            let declined =
                self.lock().declined_auto.as_deref() == Some(status.latest_version.as_str());
            if status.phase == UpdatePhase::Available
                && status.has_update
                && status.manual_reason.is_none()
                && !declined
                && let Err(error) = self.download().await
            {
                tracing::debug!(error = %error, "automatic update download not started");
            }
        }
    }
}

fn apply_outcome(core: &mut Core, outcome: CheckOutcome, target: &InstallTarget) {
    let status = &mut core.status;
    status.phase = if outcome.has_update {
        UpdatePhase::Available
    } else {
        UpdatePhase::UpToDate
    };
    status.install_kind = target.kind;
    status.has_update = outcome.has_update;
    status.latest_version = outcome
        .release
        .as_ref()
        .map(|release| release.version.clone())
        .unwrap_or_default();
    status.manual_reason = outcome.manual_reason;
    let asset = outcome
        .release
        .as_ref()
        .and_then(|release| release.asset.as_ref())
        .filter(|_| outcome.has_update);
    status.asset_name = asset.map(|asset| asset.name.clone()).unwrap_or_default();
    status.asset_size = asset.map_or(0, |asset| asset.size);
    status.downloaded_bytes = 0;
    status.install_pending = false;
    status.download_url = outcome.manual_url;
    status.release_page_url = RELEASE_PAGE_URL.to_owned();
    status.notes = outcome.notes;
    status.failure = None;
    status.error_detail.clear();
    status.checked_at_ms = now_ms();
    core.candidate = outcome.candidate;
}

async fn get_json<T: DeserializeOwned>(
    http: &reqwest::Client,
    url: &str,
) -> Result<T, UpdateError> {
    let response = http.get(url).send().await?;
    if !response.status().is_success() {
        return Err(UpdateError::Status(response.status().as_u16()));
    }
    response
        .json::<T>()
        .await
        .map_err(|error| UpdateError::Decode(error.to_string()))
}

/// 404 → `None`（清单文件不存在）；其他非成功状态按错误处理。
async fn get_text_optional(
    http: &reqwest::Client,
    url: &str,
) -> Result<Option<String>, UpdateError> {
    let response = http.get(url).send().await?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(None);
    }
    if !response.status().is_success() {
        return Err(UpdateError::Status(response.status().as_u16()));
    }
    Ok(Some(response.text().await?))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use axum::Router;
    use axum::routing::get;
    use fluxdown_protocol::{AgentSnapshot, UpdateInstallKind};

    use super::install::ReleaseComponent;
    use super::*;

    fn temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("fluxdown-upd-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn service(
        dir: &Path,
        current: &str,
        base: &str,
        manual_reason: Option<UpdateManualReason>,
    ) -> Arc<UpdateService> {
        let parts = UpdateParts {
            events: AgentEventHub::new(AgentSnapshot::default()),
            data_dir: dir.to_path_buf(),
            target: InstallTarget {
                kind: UpdateInstallKind::Docker,
                component: ReleaseComponent::Desktop,
                asset_keys: vec!["setup"],
                manual_reason,
            },
            request_restart: Box::new(|| {}),
        };
        Arc::new(UpdateService::with_version(
            parts,
            current,
            Endpoint::with_base(base, true),
        ))
    }

    async fn site(version: &'static str) -> String {
        let app = Router::new()
            .route(
                "/api/release",
                get(move || async move {
                    axum::Json(serde_json::json!({
                        "version": version,
                        "tag": format!("v{version}"),
                        "assets": {
                            "setup": { "name": "FluxDown-setup.exe", "size": 5, "download_url": "/api/download/FluxDown-setup.exe" }
                        }
                    }))
                }),
            )
            .route(
                "/api/changelog",
                get(|| async {
                    axum::Json(serde_json::json!({ "releases": [
                        { "version": "2.0.0", "published_at": "", "body": "notes" },
                        { "version": "1.0.0", "published_at": "", "body": "old" }
                    ]}))
                }),
            );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        base
    }

    fn write_pending(dir: &Path, target: &str) {
        let updates = dir.join("updates");
        std::fs::create_dir_all(updates.join(target)).unwrap();
        let record = PendingRecord {
            target_version: target.to_owned(),
            from_version: "1.0.0".to_owned(),
            asset_name: "a".to_owned(),
            created_at_ms: 1,
        };
        std::fs::write(
            updates.join(PENDING_FILE),
            serde_json::to_vec(&record).unwrap(),
        )
        .unwrap();
    }

    #[tokio::test]
    async fn pending_with_matching_version_is_success_and_cleans_updates() {
        let dir = temp_dir();
        write_pending(&dir, "2.0.0");
        let service = service(&dir, "2.0.0", "http://127.0.0.1:9", None);
        service.reconcile_pending().await;
        assert!(!dir.join("updates").exists());
        assert_eq!(service.status().phase, UpdatePhase::Idle);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[tokio::test]
    async fn pending_with_old_version_reports_install_incomplete() {
        let dir = temp_dir();
        write_pending(&dir, "2.0.0");
        let service = service(&dir, "1.0.0", "http://127.0.0.1:9", None);
        service.reconcile_pending().await;
        let status = service.status();
        assert_eq!(status.phase, UpdatePhase::Failed);
        assert_eq!(status.failure, Some(UpdateFailure::InstallIncomplete));
        assert_eq!(status.latest_version, "2.0.0");
        assert!(status.has_update);
        assert!(!dir.join("updates").join(PENDING_FILE).exists());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[tokio::test]
    async fn check_reports_up_to_date_and_publishes_once() {
        let dir = temp_dir();
        let base = site("1.0.0").await;
        let service = service(&dir, "1.0.0", &base, None);
        let status = service.check("stable").await.unwrap();
        assert_eq!(status.phase, UpdatePhase::UpToDate);
        assert_eq!(status.latest_version, "1.0.0");
        assert!(!status.has_update);
        assert!(matches!(
            service.download().await,
            Err(UpdateError::NoUpdate)
        ));
        assert!(matches!(
            service.install().await,
            Err(UpdateError::NoUpdate)
        ));
        let sequence = service.events.snapshot().sequence;
        service.check("stable").await.unwrap();
        // Checking → UpToDate 两次迁移都有变化；状态相同的重复提交不会额外发布。
        assert_eq!(service.events.snapshot().sequence, sequence + 2);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[tokio::test]
    async fn managed_installs_get_manual_reason_and_reject_download() {
        let dir = temp_dir();
        let base = site("2.0.0").await;
        let service = service(
            &dir,
            "1.0.0",
            &base,
            Some(UpdateManualReason::ManagedPackage),
        );
        let status = service.check("frontier").await.unwrap();
        assert_eq!(status.phase, UpdatePhase::Available);
        assert_eq!(status.channel, "frontier");
        assert!(status.has_update);
        assert_eq!(
            status.manual_reason,
            Some(UpdateManualReason::ManagedPackage)
        );
        assert_eq!(status.asset_name, "FluxDown-setup.exe");
        assert_eq!(
            status.download_url,
            format!("{base}/api/download/FluxDown-setup.exe")
        );
        assert_eq!(status.notes.len(), 1);
        assert!(matches!(
            service.download().await,
            Err(UpdateError::Manual(UpdateManualReason::ManagedPackage))
        ));
        assert!(matches!(
            service.install().await,
            Err(UpdateError::Manual(_))
        ));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[tokio::test]
    async fn missing_checksum_makes_update_manual_only() {
        let dir = temp_dir();
        let base = site("2.0.0").await;
        // 站点没有 SHA256SUMS 文件（404）→ NoAsset。
        let service = service(&dir, "1.0.0", &base, None);
        let status = service.check("stable").await.unwrap();
        assert_eq!(status.phase, UpdatePhase::Available);
        assert_eq!(status.manual_reason, Some(UpdateManualReason::NoAsset));
        assert!(matches!(
            service.download().await,
            Err(UpdateError::Manual(UpdateManualReason::NoAsset))
        ));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[tokio::test]
    async fn invalid_channel_fails_before_any_state_change() {
        let dir = temp_dir();
        let service = service(&dir, "1.0.0", "http://127.0.0.1:9", None);
        assert!(matches!(
            service.check("nightly").await,
            Err(UpdateError::InvalidChannel(_))
        ));
        assert_eq!(service.status().phase, UpdatePhase::Idle);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
