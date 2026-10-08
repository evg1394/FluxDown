use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;
use std::sync::Arc;

use fluxdown_engine::bt_downloader::{BtConfig, BtMseMode};
use fluxdown_engine::db::{Db, DbError};
use fluxdown_engine::download_manager::{self, NewTaskSpec, TaskDone};
use fluxdown_engine::events::EventSink;
use fluxdown_engine::proxy_config::ProxyConfig;
use fluxdown_engine::selection::HostSelection;
use fluxdown_engine::{Engine, EngineConfig, EngineError};
use rinf::{DartSignal, RustSignal};
use tokio::sync::mpsc;

use crate::logger::log_info;
use crate::rinf_selection::RinfHostSelection;
use crate::rinf_sink::RinfEventSink;
use crate::signals::{
    BatchControlTask, CheckForUpdate, ConfigEntry, ConfigLoaded, ConfirmExternalDownload,
    ControlTask, CreateTask, DownloadUpdate, MoveTaskToQueue, RequestAllQueues, RequestAllTasks,
    RequestConfig, RescanFiles, SaveConfig, SelectBtFiles, SelectHlsQuality, SelectResolveVariant,
    SetPriorityTask, UpdateCheckResult,
};

#[derive(Debug, thiserror::Error)]
pub(crate) enum ActorError {
    #[error("failed to open download database")]
    OpenDatabase(#[source] DbError),
    #[error("failed to initialize download engine")]
    InitializeEngine(#[source] EngineError),
    #[error("failed to invalidate obsolete ED2K server cache")]
    InvalidateEd2kCache(#[source] DbError),
}

use crate::updater;

/// Compute default save directory (platform-dependent).
///
/// Android 用应用专属外部目录，其它平台由系统 API 给出（`fluxdown_engine::user_dirs`）。
pub(crate) fn default_save_dir() -> String {
    // Android：应用专属外部目录（免权限可写）；公共 Download 目录需
    // SAF / All-files 权限，由 Dart 侧引导用户选择后经配置下发。
    #[cfg(target_os = "android")]
    {
        if let Some(pkg) = fluxdown_engine::data_dir::android_package_name() {
            return format!("/storage/emulated/0/Android/data/{pkg}/files/Download");
        }
    }
    fluxdown_engine::user_dirs::download_dir_or_cwd()
}

/// Build a [`BtConfig`] from the raw config key-value map.
///
/// When the tracker subscription feature is disabled, the cached
/// subscription trackers are excluded so only the user's own list is used.
fn bt_config_from_map(cfg: &HashMap<String, String>) -> BtConfig {
    let sub_enabled = cfg
        .get("bt_tracker_sub_enabled")
        .map(|v| v == "true")
        .unwrap_or(true);
    BtConfig {
        enabled: cfg.get("bt_enabled").map(|v| v == "true").unwrap_or(true),
        enable_dht: cfg
            .get("bt_enable_dht")
            .map(|v| v == "true")
            .unwrap_or(true),
        enable_upnp: cfg
            .get("bt_enable_upnp")
            .map(|v| v == "true")
            .unwrap_or(true),
        port_start: cfg
            .get("bt_port_start")
            .and_then(|v| v.parse::<u16>().ok())
            .unwrap_or(6881),
        port_end: cfg
            .get("bt_port_end")
            .and_then(|v| v.parse::<u16>().ok())
            .unwrap_or(6891),
        custom_trackers: cfg.get("bt_custom_trackers").cloned().unwrap_or_default(),
        subscription_trackers: if sub_enabled {
            cfg.get("bt_tracker_sub_cache").cloned().unwrap_or_default()
        } else {
            String::new()
        },
        seed_ratio_limit: cfg
            .get("bt_seed_ratio_limit")
            .and_then(|v| v.parse::<f64>().ok())
            .unwrap_or(0.0),
        seed_post_ratio_limit: cfg
            .get("bt_seed_post_ratio_limit")
            .and_then(|v| v.parse::<f64>().ok())
            .unwrap_or(0.0),
        seed_time_limit_minutes: cfg
            .get("bt_seed_time_limit_minutes")
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(0),
        seed_inactive_time_limit_minutes: cfg
            .get("bt_seed_inactive_time_limit_minutes")
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(0),
        seed_limit_operator: cfg
            .get("bt_seed_limit_operator")
            .map(|v| {
                if v.eq_ignore_ascii_case("and") {
                    fluxdown_engine::bt_seeding::SeedingLimitOperator::And
                } else {
                    fluxdown_engine::bt_seeding::SeedingLimitOperator::Or
                }
            })
            .unwrap_or(fluxdown_engine::bt_seeding::SeedingLimitOperator::Or),
        seed_then_action: cfg
            .get("bt_seed_then_action")
            .cloned()
            .unwrap_or_else(|| "stop".to_string()),
        seed_max_active: cfg
            .get("bt_seed_max_active")
            .and_then(|v| v.parse::<usize>().ok())
            .unwrap_or(0),
        mse_mode: cfg
            .get("bt_mse_mode")
            .map(String::as_str)
            .map(BtMseMode::from)
            .unwrap_or_default(),
    }
}

/// Spawn a background task that fetches all tracker subscription sources,
/// persists the deduped result to the config table, then reports the outcome
/// back to the actor loop (which updates the BtConfig and notifies Dart).
fn spawn_tracker_sub_refresh(
    db: Db,
    tx: mpsc::Sender<fluxdown_engine::tracker_subscription::FetchOutcome>,
) {
    tokio::spawn(async move {
        let cfg = match db.get_all_config().await {
            Ok(cfg) => cfg,
            Err(error) => {
                crate::logger::report_error("actor", "read tracker subscription config", &error);
                return;
            }
        };
        let urls = cfg
            .get("bt_tracker_sub_urls")
            .cloned()
            .unwrap_or_else(fluxdown_engine::tracker_subscription::default_subscription_urls);
        let outcome = fluxdown_engine::tracker_subscription::fetch_subscriptions(&urls).await;
        if outcome.is_success() {
            let values = BTreeMap::from([
                ("bt_tracker_sub_cache".into(), outcome.trackers.join("\n")),
                (
                    "bt_tracker_sub_updated_at".into(),
                    chrono::Utc::now().timestamp().to_string(),
                ),
            ]);
            if let Err(error) = db.set_config_batch_atomic(&values).await {
                crate::logger::report_error("actor", "save tracker subscription cache", &error);
                return;
            }
        }
        if tx.send(outcome).await.is_err() {
            tracing::debug!("hub tracker refresh receiver closed");
        }
    });
}

/// Spawn a background task that fetches all ED2K server.met subscription
/// sources and persists the deduped `ip:port` list to the config table.
///
/// Unlike BT trackers, the ED2K server list is read fresh at each download's
/// find-sources step, so no shared session needs invalidating here.
fn spawn_ed2k_server_sub_refresh(db: Db) {
    tokio::spawn(async move {
        let cfg = match db.get_all_config().await {
            Ok(cfg) => cfg,
            Err(error) => {
                crate::logger::report_error(
                    "actor",
                    "read ED2K server subscription config",
                    &error,
                );
                return;
            }
        };
        let urls = cfg
            .get("ed2k_server_sub_urls")
            .cloned()
            .unwrap_or_else(fluxdown_engine::ed2k::server_subscription::default_server_met_urls);
        let outcome =
            fluxdown_engine::ed2k::server_subscription::fetch_server_subscriptions(&urls).await;
        if outcome.is_success() {
            let values = BTreeMap::from([
                ("ed2k_server_sub_cache".into(), outcome.servers.join(",")),
                (
                    "ed2k_server_sub_updated_at".into(),
                    chrono::Utc::now().timestamp().to_string(),
                ),
                (
                    "ed2k_server_sub_cache_version".into(),
                    fluxdown_engine::ed2k::server_subscription::CACHE_FORMAT_VERSION.to_string(),
                ),
            ]);
            if let Err(error) = db.set_config_batch_atomic(&values).await {
                crate::logger::report_error("actor", "save ED2K server subscription cache", &error);
            }
        }
    });
}

/// Kad nodes.dat 刷新间隔（秒）：24 小时。
const ED2K_NODES_DAT_REFRESH_SECS: i64 = 24 * 60 * 60;

/// Spawn a fire-and-forget task that downloads `nodes.dat` from the configured
/// URL and caches it (base64) in the config table for Kad bootstrap.
///
/// Binary blob with no Dart-visible state, so no result channel — failures are
/// logged and tolerated (Kad simply stays inactive until a later refresh).
fn spawn_ed2k_nodes_dat_refresh(db: Db) {
    tokio::spawn(async move {
        use base64::Engine as _;
        let cfg = db.get_all_config().await.unwrap_or_default();
        let url = cfg.get("ed2k_nodes_dat_url").cloned().unwrap_or_default();
        if url.is_empty() {
            return;
        }
        match fluxdown_engine::ed2k::kad::fetch_nodes_dat(&url).await {
            Ok(bytes) => {
                let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
                let now = chrono::Utc::now().timestamp();
                if let Err(e) = db.set_config("ed2k_nodes_dat_cache", &encoded).await {
                    log_info!("[actor] failed to save ed2k nodes.dat cache: {}", e);
                }
                if let Err(e) = db
                    .set_config("ed2k_nodes_dat_updated_at", &now.to_string())
                    .await
                {
                    log_info!("[actor] failed to save ed2k nodes.dat timestamp: {}", e);
                }
                log_info!("[actor] ed2k nodes.dat refreshed ({} bytes)", bytes.len());
            }
            Err(e) => log_info!("[actor] ed2k nodes.dat refresh failed: {}", e),
        }
    });
}

/// Read initial config values from DB to pass to DownloadManager.
async fn load_initial_config(
    db: &Db,
) -> (
    usize,
    u64,
    u64,
    String,
    BtConfig,
    ProxyConfig,
    String,
    i32,
    i32,
    bool,
    i32,
    bool,
) {
    let config = db.get_all_config().await.unwrap_or_default();
    let max_concurrent = config
        .get("max_concurrent_tasks")
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(5);
    let speed_limit_bytes = config
        .get("speed_limit_bytes")
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(0);
    // 全局 BT 上传限速（B/s，0 = 不限）。与 speed_limit_bytes 解耦：
    // 后者只管下载，本键管 BT 上传（下载期上传 + 做种）。
    let upload_limit_bytes = config
        .get("upload_limit_bytes")
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(0);
    let save_dir = config
        .get("default_save_dir")
        .cloned()
        .unwrap_or_else(default_save_dir);
    let bt_config = bt_config_from_map(&config);

    let proxy_config = ProxyConfig::from_config_map(&config);
    let user_agent = config.get("global_user_agent").cloned().unwrap_or_default();
    let default_segments = config
        .get("default_segments")
        .and_then(|v| v.parse::<i32>().ok())
        .unwrap_or(0);
    // Auto 模式最大连接数上限。老库无此 key → 默认 16。
    let auto_max_connections = config
        .get("auto_max_connections")
        .and_then(|v| v.parse::<i32>().ok())
        .unwrap_or(16);
    // Multi-CDN 并发下载开关（实验性，P0）。老库无此 key → 默认关闭。
    let cdn_multi_enabled = config
        .get("cdn_multi_enabled")
        .is_some_and(|v| v == "1" || v == "true");
    // 单任务最多钉定的 CDN 节点数，0..=8；0 = 自动档（按文件大小/并发推导）。
    // 老库无此 key → 默认 0。
    let cdn_max_nodes = config
        .get("cdn_max_nodes")
        .and_then(|v| v.parse::<i32>().ok())
        .unwrap_or(0)
        .clamp(0, 8);
    // 多网卡聚合下载开关。老库无此 key → 默认关闭。
    let multi_nic_enabled = config
        .get("multi_nic_enabled")
        .is_some_and(|v| v == "1" || v == "true");

    (
        max_concurrent,
        speed_limit_bytes,
        upload_limit_bytes,
        save_dir,
        bt_config,
        proxy_config,
        user_agent,
        default_segments,
        auto_max_connections,
        cdn_multi_enabled,
        cdn_max_nodes,
        multi_nic_enabled,
    )
}

/// 写租约重试窗口：同进程二次 isolate（Android Activity 重建 / rinf 热重启）时，
/// 旧 runtime 还在 drop 途中仍握着 `engine.lock`；短暂等待即可接手，
/// 超时则说明确有另一个引擎进程（fluxdownd / headless server / CLI --local）在写同一目录。
const LEASE_RETRY_INTERVAL: std::time::Duration = std::time::Duration::from_millis(250);
const LEASE_RETRY_ATTEMPTS: u32 = 20;

async fn open_db_with_lease_retry(
    db_dir: &std::path::Path,
) -> Result<(Db, fluxdown_engine::db::EngineWriteGuard), ActorError> {
    let mut attempt = 0;
    loop {
        match Db::open_exclusive(db_dir).await {
            Err(DbError::WriterLeaseHeld(lock_path)) if attempt < LEASE_RETRY_ATTEMPTS => {
                attempt += 1;
                if attempt == 1 {
                    log_info!(
                        "[actor] engine write lease held ({lock_path}); waiting for previous holder"
                    );
                }
                tokio::time::sleep(LEASE_RETRY_INTERVAL).await;
            }
            Err(DbError::WriterLeaseHeld(lock_path)) => {
                log_info!(
                    "[actor] engine write lease still held after {}ms ({lock_path}); another FluxDown engine (fluxdownd / server / cli --local) is using this data dir",
                    LEASE_RETRY_INTERVAL.as_millis() * u128::from(LEASE_RETRY_ATTEMPTS)
                );
                return Err(ActorError::OpenDatabase(DbError::WriterLeaseHeld(
                    lock_path,
                )));
            }
            other => return other.map_err(ActorError::OpenDatabase),
        }
    }
}

pub async fn run(
    db_dir: PathBuf,
    shutdown: tokio_util::sync::CancellationToken,
) -> Result<(), ActorError> {
    let (db, write_guard) = open_db_with_lease_retry(&db_dir).await?;

    // Initialize default config values in DB (no-op if already set)
    if let Err(e) = db.init_default_config(&default_save_dir()).await {
        log_info!("Failed to init default config: {}", e);
    }

    // Load persisted config to initialize the manager with correct limits.
    let (
        max_concurrent,
        speed_limit_bps,
        upload_limit_bps,
        save_dir,
        mut bt_config,
        proxy_config,
        user_agent,
        default_segments,
        auto_max_connections,
        cdn_multi_enabled,
        cdn_max_nodes,
        multi_nic_enabled,
    ) = load_initial_config(&db).await;
    log_info!(
        "[actor] proxy config: mode={}, type={}, host={}, port={}",
        proxy_config.mode.as_str(),
        proxy_config.proxy_type.as_str(),
        proxy_config.host,
        proxy_config.port,
    );

    // Populate default tracker list on first launch (when DB value is empty).
    if bt_config.custom_trackers.trim().is_empty() {
        let defaults = fluxdown_engine::bt_downloader::default_tracker_list();
        if let Err(e) = db.set_config("bt_custom_trackers", &defaults).await {
            log_info!("[actor] failed to save default trackers: {}", e);
        }
        bt_config.custom_trackers = defaults;
    }
    log_info!(
        "[actor] init config: max_concurrent={}, speed_limit_bps={}, save_dir={}, \
         bt: dht={}, upnp={}, ports={}-{}, custom_trackers={} lines, subscription_trackers={} lines",
        max_concurrent,
        speed_limit_bps,
        save_dir,
        bt_config.enable_dht,
        bt_config.enable_upnp,
        bt_config.port_start,
        bt_config.port_end,
        bt_config.custom_trackers.lines().count(),
        bt_config.subscription_trackers.lines().count(),
    );

    let app_data_dir = db_dir.to_string_lossy().into_owned();

    // 引擎事件接收端与选择接口:分别桥接到 hub 的 RustSignal 发送与
    // oneshot 等待表。
    let sink: Arc<dyn EventSink> = Arc::new(RinfEventSink::new());
    let selector: Arc<dyn HostSelection> = Arc::new(RinfHostSelection::new());

    let mut engine = Engine::from_db(
        EngineConfig {
            max_concurrent,
            speed_limit_bps,
            upload_limit_bps,
            default_save_dir: save_dir,
            app_data_dir,
            bt_config,
            proxy_config,
            user_agent,
            // db_dir 已由 `actors::create_actors` 通过
            // `fluxdown_engine::data_dir::resolve_data_dir(None)` 解析。
            data_dir_override: Some(db_dir.clone()),
            database_url: None,
        },
        db,
        write_guard,
        sink.clone(),
        selector.clone(),
    )
    .await
    .map_err(ActorError::InitializeEngine)?;

    engine.manager.set_default_segments(default_segments);
    engine
        .manager
        .set_auto_max_connections(auto_max_connections);
    engine.manager.set_cdn_multi_enabled(cdn_multi_enabled);
    engine.manager.set_cdn_max_nodes(cdn_max_nodes);
    engine.manager.set_multi_nic_enabled(multi_nic_enabled);

    // Apply persisted log size cap (MB) to the global logger.
    if let Ok(Some(v)) = engine.db.get_config("log_max_size_mb").await
        && let Ok(mb) = v.parse::<u64>()
    {
        crate::logger::set_max_total_bytes(mb * 1024 * 1024);
    }

    // Apply persisted auto-retry config (key-value `config` table). Absent or
    // unparsable values fall back to the manager's built-in defaults.
    {
        let cfg = engine.db.get_all_config().await.unwrap_or_default();
        if let Some(v) = cfg
            .get("max_auto_retries")
            .and_then(|s| s.parse::<i32>().ok())
        {
            engine.manager.set_max_auto_retries(v);
        }
        if let Some(v) = cfg
            .get("auto_retry_delay_secs")
            .and_then(|s| s.parse::<u64>().ok())
        {
            engine.manager.set_auto_retry_delay_secs(v);
        }
        // 下载完成后是否采用服务器 Last-Modified 作为文件修改时间（默认关闭）。
        if let Some(v) = cfg.get("use_server_time") {
            engine.manager.set_use_server_time(v == "true");
        }
        // 文件已存在时的处理方式（"rename"=自动重命名，默认；"overwrite"=
        // 覆盖旧文件；"skip"=跳过下载）。
        if let Some(v) = cfg.get("file_exists_behavior") {
            engine
                .manager
                .set_file_exists_behavior(download_manager::FileExistsBehavior::from_config_str(v));
        }
        // 任务的文件被删除/移动时的动作（"keep"=保留任务记录，默认；
        // "delete"=文件消失后自动删除任务记录）。
        if let Some(v) = cfg.get("file_missing_action") {
            engine.manager.set_missing_file_auto_delete(v == "delete");
        }
    }

    let progress_task = engine.manager.take_progress_rx().map(|rx| {
        tokio::spawn(download_manager::progress_reporter(
            rx,
            engine.db.clone(),
            engine.activity_sink.clone(),
        ))
    });

    // Load named queue settings into the in-memory cache so that
    // per-queue speed limits and concurrency limits take effect immediately.
    engine.manager.load_queues().await;

    // Channel for spawned tasks to notify completion (for active_tokens cleanup)
    let mut done_rx: mpsc::Receiver<TaskDone> = match engine.manager.take_done_rx() {
        Some(rx) => rx,
        None => {
            // Should never happen — take_done_rx returns Some on first call
            let (_tx, rx) = mpsc::channel(1);
            rx
        }
    };

    // Channel for delayed auto-retry of failed tasks (stall / network errors).
    let mut retry_rx: mpsc::Receiver<String> = match engine.manager.take_retry_rx() {
        Some(rx) => rx,
        None => {
            let (_tx, rx) = mpsc::channel(1);
            rx
        }
    };

    // 文件跟踪扫描判定「文件已消失」后的自动清理回流通道（config
    // `file_missing_action == "delete"`）。主 `select!` 已占满 64 分支上限，
    // 这条通道由下方后台泵合流进 `aux_tx`，不新增主循环分支。
    let missing_cleanup_rx: Option<mpsc::Receiver<Vec<String>>> =
        engine.manager.take_missing_cleanup_rx();

    let create_recv = CreateTask::get_dart_signal_receiver();
    let control_recv = ControlTask::get_dart_signal_receiver();
    let batch_control_recv = BatchControlTask::get_dart_signal_receiver();
    let all_recv = RequestAllTasks::get_dart_signal_receiver();
    let move_task_queue_recv = MoveTaskToQueue::get_dart_signal_receiver();
    let all_queues_recv = RequestAllQueues::get_dart_signal_receiver();
    let config_save_recv = SaveConfig::get_dart_signal_receiver();
    let config_req_recv = RequestConfig::get_dart_signal_receiver();
    let confirm_ext_recv = ConfirmExternalDownload::get_dart_signal_receiver();
    let check_update_recv = CheckForUpdate::get_dart_signal_receiver();
    let download_update_recv = DownloadUpdate::get_dart_signal_receiver();
    let select_hls_quality_recv = SelectHlsQuality::get_dart_signal_receiver();
    let select_resolve_variant_recv = SelectResolveVariant::get_dart_signal_receiver();
    let set_priority_recv = SetPriorityTask::get_dart_signal_receiver();
    let select_bt_files_recv = SelectBtFiles::get_dart_signal_receiver();
    let rescan_recv = RescanFiles::get_dart_signal_receiver();

    // Tracker 订阅刷新通道：后台 fetch 任务完成后把结果送回 actor 循环，
    // 由循环更新 BtConfig、失效 BT 会话并通知 Dart。
    let (tracker_sub_tx, mut tracker_sub_rx) =
        mpsc::channel::<fluxdown_engine::tracker_subscription::FetchOutcome>(4);

    // 启动时自动刷新：订阅启用且缓存超过 24 小时未更新。
    {
        let cfg = engine.db.get_all_config().await.unwrap_or_default();
        let bt_enabled = cfg.get("bt_enabled").map(|v| v == "true").unwrap_or(true);
        let sub_enabled = cfg
            .get("bt_tracker_sub_enabled")
            .map(|v| v == "true")
            .unwrap_or(true);
        let updated_at = cfg
            .get("bt_tracker_sub_updated_at")
            .and_then(|v| v.parse::<i64>().ok())
            .unwrap_or(0);
        let now = chrono::Utc::now().timestamp();
        if bt_enabled
            && sub_enabled
            && now.saturating_sub(updated_at)
                > fluxdown_engine::tracker_subscription::REFRESH_INTERVAL_SECS
        {
            log_info!(
                "[actor] tracker subscription stale (updated_at={}), auto-refreshing",
                updated_at
            );
            spawn_tracker_sub_refresh(engine.db.clone(), tracker_sub_tx.clone());
        }
    }

    // 启动时自动刷新：订阅启用且缓存超过 24 小时未更新。
    {
        let cfg = engine.db.get_all_config().await.unwrap_or_default();
        let sub_enabled = cfg
            .get("ed2k_server_sub_enabled")
            .map(|v| v == "true")
            .unwrap_or(true);
        let updated_at = cfg
            .get("ed2k_server_sub_updated_at")
            .and_then(|v| v.parse::<i64>().ok())
            .unwrap_or(0);
        // 缓存格式版本：pre-fix（v1）写入的缓存 IP 字节序被反转，全为死主机。
        // 版本不符即清空缓存 + 归零时间戳，强制用修正后的解析器重取。
        let cache_version = cfg
            .get("ed2k_server_sub_cache_version")
            .and_then(|v| v.parse::<i64>().ok())
            .unwrap_or(0);
        let version_stale =
            cache_version < fluxdown_engine::ed2k::server_subscription::CACHE_FORMAT_VERSION;
        if version_stale {
            log_info!(
                "[actor] ed2k server sub cache version {} < {}, invalidating (byte-order fix)",
                cache_version,
                fluxdown_engine::ed2k::server_subscription::CACHE_FORMAT_VERSION
            );
            if let Err(error) = engine.db.set_config("ed2k_server_sub_cache", "").await {
                super::shutdown_engine(engine, progress_task).await;
                return Err(ActorError::InvalidateEd2kCache(error));
            }
        }
        let now = chrono::Utc::now().timestamp();
        if sub_enabled
            && (version_stale
                || now.saturating_sub(updated_at)
                    > fluxdown_engine::ed2k::server_subscription::REFRESH_INTERVAL_SECS)
        {
            log_info!(
                "[actor] ed2k server subscription stale (updated_at={}, version_stale={}), auto-refreshing",
                updated_at,
                version_stale
            );
            spawn_ed2k_server_sub_refresh(engine.db.clone());
        }
    }

    // 启动时自动刷新 Kad nodes.dat：启用 Kad 且缓存超过 24 小时未更新（或为空）。
    {
        let cfg = engine.db.get_all_config().await.unwrap_or_default();
        let kad_enabled = cfg
            .get("ed2k_enable_kad")
            .map(|v| v == "true")
            .unwrap_or(true);
        let updated_at = cfg
            .get("ed2k_nodes_dat_updated_at")
            .and_then(|v| v.parse::<i64>().ok())
            .unwrap_or(0);
        let now = chrono::Utc::now().timestamp();
        if kad_enabled && now.saturating_sub(updated_at) > ED2K_NODES_DAT_REFRESH_SECS {
            log_info!(
                "[actor] ed2k nodes.dat stale (updated_at={}), auto-refreshing",
                updated_at
            );
            spawn_ed2k_nodes_dat_refresh(engine.db.clone());
        }
    }

    // 队列定时调度 tick：引擎侧做边沿检测（每边沿每天至多一次 + 当日补
    // 触发），此处只提供节拍。Delay 防休眠唤醒后积压 tick 连环触发。
    let mut queue_schedule_tick = tokio::time::interval(std::time::Duration::from_secs(20));
    queue_schedule_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    // ===== 辅助信号合并转发（做种节拍 / 关停 / 文件丢失清理） =====
    // 主 `select!` 已顶到 tokio 的 **64 分支硬上限**，因此这些事件
    // 由后台泵合流进**同一条** `aux_tx`，主循环只有一条
    // `Some(aux) = aux_rx.recv()` 分支。
    //
    // **新增任何事件源都必须走这里**，不要往主 `select!` 加分支。
    enum AuxSignal {
        Shutdown,
        /// BT 做种限制求值节拍（`SEEDING_EVAL_INTERVAL`，见 engine::bt_seeding）。
        SeedingTick,
        /// 文件跟踪扫描回流的「文件已消失」任务批次，需删除其任务记录
        /// （config `file_missing_action == "delete"`）。
        MissingCleanup(Vec<String>),
    }
    let (aux_tx, mut aux_rx) = mpsc::unbounded_channel::<AuxSignal>();
    let shutdown_tx = aux_tx.clone();
    tokio::spawn(async move {
        shutdown.cancelled().await;
        if shutdown_tx.send(AuxSignal::Shutdown).is_err() {
            tracing::debug!("hub actor already stopped before shutdown notification");
        }
    });
    // 文件丢失自动清理泵：引擎 detached 扫描 → mpsc → aux_tx → 主循环单分支。
    if let Some(mut rx) = missing_cleanup_rx {
        let cleanup_tx = aux_tx.clone();
        tokio::spawn(async move {
            while let Some(ids) = rx.recv().await {
                if cleanup_tx.send(AuxSignal::MissingCleanup(ids)).is_err() {
                    break;
                }
            }
        });
    }

    // 做种限制求值节拍：走 aux 泵（主 select! 已满 64 分支，不得新增分支）。
    let seeding_tx = aux_tx;
    {
        let mut seeding_interval =
            tokio::time::interval(fluxdown_engine::bt_seeding::SEEDING_EVAL_INTERVAL);
        seeding_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        tokio::spawn(async move {
            loop {
                seeding_interval.tick().await;
                if seeding_tx.send(AuxSignal::SeedingTick).is_err() {
                    break; // actor 已退出
                }
            }
        });
    }

    // 进循环前主动推一次快照。Dart 的 RequestAllTasks 可能在 receiver
    // 就绪前就发出（或二次 isolate 时旧 actor 已死），不能只靠请求灌表。
    engine.manager.load_and_send_all_tasks().await;
    engine.manager.send_all_queues().await;

    loop {
        tokio::select! {
            Some(signal) = create_recv.recv() => {
                let msg = signal.message;
                engine.manager
                    .create_task(NewTaskSpec {
                        url: msg.url,
                        save_dir: msg.save_dir,
                        file_name: msg.file_name,
                        segments: msg.segments,
                        cookies: msg.cookies,
                        torrent_file_bytes: msg.torrent_file_bytes,
                        proxy_url: msg.proxy_url,
                        user_agent: msg.user_agent,
                        queue_id: msg.queue_id,
                        checksum: msg.checksum,
                        ignore_tls_errors: msg.ignore_tls_errors,
                        extra_headers: msg.extra_headers,
                        selected_file_indices: msg.selected_file_indices,
                        start_paused: msg.start_paused,
                        http_user: msg.http_user,
                        http_password: msg.http_password,
                        save_site_auth: msg.save_site_auth,
                        ..Default::default()
                    })
                    .await;
                // 立即推送 AllTasks，确保 Dart 端在收到 TaskProgress 之前
                // 已通过 AllTasks 获得正确的 queue_id，防止新任务被错误归入默认队列。
                engine.manager.load_and_send_all_tasks().await;
            }
            Some(signal) = control_recv.recv() => {
                let msg = signal.message;
                match msg.action {
                    0 => engine.manager.pause_task(&msg.task_id).await,
                    1 => engine.manager.resume_task(&msg.task_id).await,
                    2 => engine.manager.cancel_task(&msg.task_id).await,
                    3 => engine.manager.delete_task(&msg.task_id, true).await,
                    4 => engine.manager.delete_task(&msg.task_id, false).await,
                    // 重新下载：丢弃已有产物与进度，从零重下。
                    5 => engine.manager.restart_task(&msg.task_id).await,
                    _ => {}
                }
            }
            Some(signal) = batch_control_recv.recv() => {
                let msg = signal.message;
                log_info!(
                    "[actor] batch control: {} tasks, action={}",
                    msg.task_ids.len(), msg.action,
                );
                match msg.action {
                    0 => engine.manager.batch_pause(&msg.task_ids).await,
                    1 => engine.manager.batch_resume(&msg.task_ids).await,
                    3 => engine.manager.delete_tasks_batch(&msg.task_ids, true).await,
                    4 => engine.manager.delete_tasks_batch(&msg.task_ids, false).await,
                    _ => {}
                }
            }
            Some(_) = all_recv.recv() => {
                engine.manager.load_and_send_all_tasks().await;
                // Also send queue list so Dart sidebar can show named queues.
                engine.manager.send_all_queues().await;
            }
            // 见上方「辅助信号合并转发」：两个后台泵合流到这一条分支，
            // 主 select! 因此停在 64 分支上限之内。
            Some(aux) = aux_rx.recv() => {
                match aux {
                AuxSignal::Shutdown => break,
                AuxSignal::SeedingTick => {
                    engine.manager.tick_seeding_evaluation().await;
                }
                AuxSignal::MissingCleanup(ids) => {
                    // `file_missing_action == "delete"`：文件已不在磁盘上，
                    // 只删任务记录（delete_files=false，无文件可删）。
                    log_info!("[actor] auto-deleting {} task(s) whose files vanished", ids.len());
                    engine.manager.delete_tasks_batch(&ids, false).await;
                    // 删除没有专属信号——重发全量快照，Dart 任务列表才会移除这些行。
                    engine.manager.load_and_send_all_tasks().await;
                }
                }
            }
            Some(_) = rescan_recv.recv() => {
                // 文件跟踪：桌面窗口聚焦 / 移动端回前台 → 重扫已完成任务文件是否仍在。
                engine.manager.spawn_file_scan();
            }
            Some(signal) = config_save_recv.recv() => {
                let msg = signal.message;
                // Persist to DB first.
                if let Err(e) = engine.db.set_config(&msg.key, &msg.value).await {
                    log_info!("Failed to save config: {}", e);
                }
                // Notify DownloadManager for runtime-effective settings.
                apply_config_key(
                    &mut engine,
                    &msg.key,
                    &msg.value,
                    &tracker_sub_tx,
                )
                .await;
            }
            Some(_) = config_req_recv.recv() => {
                // Dart CDN 遥测上报依赖此处先把内存样本刷进 config 表
                // （见 cdn_report_service.dart 文件头 / telemetry::flush）。
                engine.manager.flush_cdn_pending_reports().await;
                match engine.db.get_all_config().await {
                    Ok(map) => {
                        let entries: Vec<ConfigEntry> = map
                            .into_iter()
                            .map(|(key, value)| ConfigEntry { key, value })
                            .collect();
                        ConfigLoaded { entries }.send_signal_to_dart();
                    }
                    Err(e) => {
                        log_info!("Failed to load config: {}", e);
                    }
                }
            }
            // --- Dart confirmed an external download request ---
            Some(signal) = confirm_ext_recv.recv() => {
                let msg = signal.message;
                log_info!(
                    "[actor] user confirmed external download: url={}, cookies_len={}, extra_headers={}",
                    msg.url,
                    msg.cookies.len(),
                    msg.extra_headers.len(),
                );
                engine.manager
                    .create_task(NewTaskSpec {
                        url: msg.url,
                        save_dir: msg.save_dir,
                        file_name: msg.file_name,
                        segments: msg.segments,
                        cookies: msg.cookies,
                        referrer: msg.referrer,
                        hint_file_size: msg.hint_file_size,
                        proxy_url: msg.proxy_url,
                        user_agent: msg.user_agent,
                        queue_id: msg.queue_id,
                        ignore_tls_errors: msg.ignore_tls_errors,
                        extra_headers: msg.extra_headers,
                        audio_url: if msg.audio_url.is_empty() { None } else { Some(msg.audio_url) },
                        start_paused: msg.start_paused,
                        http_user: msg.http_user,
                        http_password: msg.http_password,
                        save_site_auth: msg.save_site_auth,
                        unattended_selection: msg.unattended,
                        ..Default::default()
                    })
                    .await;
                // 推送 AllTasks 确保 Dart 端获得正确 queue_id。
                engine.manager.load_and_send_all_tasks().await;
            }
            // --- Named queue management ---
            Some(signal) = move_task_queue_recv.recv() => {
                let msg = signal.message;
                log_info!("[actor] MoveTaskToQueue: task={}, queue={}", msg.task_id, msg.queue_id);
                engine.manager.move_task_to_queue(msg.task_id, msg.queue_id).await;
            }
            Some(_) = all_queues_recv.recv() => {
                engine.manager.send_all_queues().await;
            }
            _ = queue_schedule_tick.tick() => {
                engine.manager.tick_queue_schedules().await;
            }
            Some(done) = done_rx.recv() => {
                engine.manager.on_task_done(&done).await;
            }
            // --- Auto-retry for stalled/failed tasks ---
            Some(task_id) = retry_rx.recv() => {
                // 安全检查：仅在任务仍处于 error 状态时才自动恢复。
                // 如果用户已手动暂停、恢复或删除了该任务，跳过重试。
                if engine.manager.is_task_in_error(&task_id).await {
                    log_info!("[actor] auto-retry: resuming task {}", task_id);
                    // 使用 resume_task_auto 而非 resume_task：不重置自动重试计数，
                    // 使 on_task_done 中的累积计数能正确递增并最终触发重试上限。
                    engine.manager.resume_task_auto(&task_id).await;
                } else {
                    log_info!("[actor] auto-retry: skipping task {} (no longer in error state)", task_id);
                }
            }
            // --- Auto-update signals ---
            Some(signal) = check_update_recv.recv() => {
                let version = signal.message.current_version;
                let channel = signal.message.channel;
                tokio::spawn(async move {
                    let result = std::panic::AssertUnwindSafe(
                        updater::check(&version, &channel)
                    );
                    if futures_util::FutureExt::catch_unwind(result).await.is_err() {
                        UpdateCheckResult {
                            has_update: false,
                            latest_version: String::new(),
                            current_version: version,
                            download_url: String::new(),
                            file_size: 0,
                            published_at: String::new(),
                            error_message: "internal error (panic)".to_string(),
                        }.send_signal_to_dart();
                    }
                });
            }
            Some(signal) = download_update_recv.recv() => {
                let url = signal.message.url;
                let version = signal.message.version;
                let file_size = signal.message.file_size;
                tokio::spawn(async move {
                    updater::download(&url, &version, file_size).await;
                });
            }
            // --- HLS quality selection ---
            Some(signal) = select_hls_quality_recv.recv() => {
                let msg = signal.message;
                log_info!(
                    "[actor] HLS quality selected: task={}, index={}",
                    msg.task_id,
                    msg.selected_index,
                );
                engine.selector.provide_hls_selection(&msg.task_id, msg.selected_index);
            }
            // --- Resolve variant selection ---
            Some(signal) = select_resolve_variant_recv.recv() => {
                let msg = signal.message;
                log_info!(
                    "[actor] resolve variant selected: task={}, index={}",
                    msg.task_id,
                    msg.selected_index,
                );
                engine.selector.provide_variant_selection(&msg.task_id, msg.selected_index);
            }
            // --- Priority (Boost) download ---
            Some(signal) = set_priority_recv.recv() => {
                let task_id = signal.message.task_id;
                log_info!("[actor] SetPriorityTask: task_id={}", task_id);
                engine.manager.set_priority_task(task_id).await;
            }
            // --- BT file selection ---
            Some(signal) = select_bt_files_recv.recv() => {
                let msg = signal.message;
                log_info!(
                    "[actor] SelectBtFiles: task={}, selected={:?}",
                    msg.task_id,
                    msg.selected_indices,
                );
                engine.selector.provide_bt_selection(&msg.task_id, msg.selected_indices);
            }
            // --- Tracker subscription refresh finished ---
            Some(outcome) = tracker_sub_rx.recv() => {
                let all_cfg = engine.db.get_all_config().await.unwrap_or_default();
                if outcome.is_success() {
                    // 缓存已由后台任务写入 DB；重载 BtConfig 并失效会话，
                    // 使下一个 BT 任务用上最新的合并 tracker 列表。
                    engine.manager.set_bt_config(bt_config_from_map(&all_cfg));
                    engine.manager.invalidate_bt_session().await;
                }
            }
        }
    }
    super::shutdown_engine(engine, progress_task).await;
    Ok(())
}

/// 把单个已持久化的配置键 live-apply 到运行中的引擎（Dart `SaveConfig`
/// 信号分支使用，单键粒度）。
async fn apply_config_key(
    engine: &mut Engine,
    key: &str,
    value: &str,
    tracker_sub_tx: &mpsc::Sender<fluxdown_engine::tracker_subscription::FetchOutcome>,
) {
    match key {
        "max_concurrent_tasks" => {
            if let Ok(v) = value.parse::<usize>() {
                log_info!("[actor] updating max_concurrent to {}", v);
                engine.manager.set_max_concurrent(v).await;
            }
        }
        "speed_limit_bytes" => {
            if let Ok(v) = value.parse::<u64>() {
                log_info!("[actor] updating speed_limit to {} B/s", v);
                engine.manager.set_speed_limit(v);
            }
        }
        "upload_limit_bytes" => {
            if let Ok(v) = value.parse::<u64>() {
                log_info!("[actor] updating upload_limit to {} B/s", v);
                engine.manager.set_upload_speed_limit(v);
            }
        }
        "log_max_size_mb" => {
            if let Ok(mb) = value.parse::<u64>() {
                log_info!("[actor] updating log_max_size_mb to {}", mb);
                crate::logger::set_max_total_bytes(mb * 1024 * 1024);
            }
        }
        "default_save_dir" => {
            log_info!("[actor] updating default_save_dir to {}", value);
            engine.manager.set_default_save_dir(value.to_string());
        }
        // BT session-level config keys — update in-memory BtConfig and invalidate
        // the current session so the next BT download picks up changes.
        "bt_enabled"
        | "bt_enable_dht"
        | "bt_enable_upnp"
        | "bt_port_start"
        | "bt_port_end"
        | "bt_custom_trackers"
        | "bt_tracker_sub_enabled"
        | "bt_tracker_sub_urls"
        | "bt_mse_mode" => {
            log_info!("[actor] BT session config changed: {}={}", key, value);
            // Reload the full BT config from DB to stay consistent.
            let all_cfg = engine.db.get_all_config().await.unwrap_or_default();
            engine.manager.set_bt_config(bt_config_from_map(&all_cfg));
            // Invalidate (destroy) the current BT session so it is
            // re-created with the new config on next BT download.
            engine.manager.invalidate_bt_session().await;
            // 订阅地址变化 / 重新启用订阅 → 后台立即刷新一次。
            if key == "bt_tracker_sub_urls" || (key == "bt_tracker_sub_enabled" && value == "true")
            {
                spawn_tracker_sub_refresh(engine.db.clone(), tracker_sub_tx.clone());
            }
        }
        // Seeding limit keys — these are read live from BtConfig every evaluation
        // tick, so a simple in-memory update is enough; no session rebuild needed.
        "bt_seed_ratio_limit"
        | "bt_seed_post_ratio_limit"
        | "bt_seed_time_limit_minutes"
        | "bt_seed_inactive_time_limit_minutes"
        | "bt_seed_limit_operator"
        | "bt_seed_then_action"
        | "bt_seed_max_active" => {
            log_info!("[actor] BT seeding config changed: {}={}", key, value);
            let all_cfg = engine.db.get_all_config().await.unwrap_or_default();
            engine.manager.set_bt_config(bt_config_from_map(&all_cfg));
        }
        // ED2K 服务器订阅键：地址变化 / 重新启用 → 后台立即刷新一次。
        "ed2k_server_sub_urls" | "ed2k_server_sub_enabled" => {
            log_info!("[actor] ED2K server sub config changed: {}={}", key, value);
            if key == "ed2k_server_sub_urls"
                || (key == "ed2k_server_sub_enabled" && value == "true")
            {
                spawn_ed2k_server_sub_refresh(engine.db.clone());
            }
        }
        // Kad nodes.dat：URL 变化 / Kad 重新启用 → 后台立即刷新一次。
        "ed2k_nodes_dat_url" | "ed2k_enable_kad" => {
            log_info!("[actor] ED2K Kad config changed: {}={}", key, value);
            if key == "ed2k_nodes_dat_url" || (key == "ed2k_enable_kad" && value == "true") {
                spawn_ed2k_nodes_dat_refresh(engine.db.clone());
            }
        }
        // Proxy config keys — reload full proxy config from DB
        // and rebuild the HTTP client.
        "proxy_mode" | "proxy_type" | "proxy_host" | "proxy_port" | "proxy_username"
        | "proxy_password" | "proxy_no_list" => {
            log_info!("[actor] proxy config changed: {}={}", key, value);
            let all_cfg = engine.db.get_all_config().await.unwrap_or_default();
            let new_proxy = ProxyConfig::from_config_map(&all_cfg);
            if let Err(e) = engine.manager.set_proxy_config(new_proxy) {
                log_info!("[actor] failed to apply proxy config: {}", e);
            }
        }
        "global_user_agent" => {
            log_info!("[actor] user_agent changed: {}", value);
            if let Err(e) = engine.manager.set_user_agent(value.to_string()) {
                log_info!("[actor] failed to apply user_agent: {}", e);
            }
        }
        "default_segments" => {
            if let Ok(v) = value.parse::<i32>() {
                log_info!("[actor] updating default_segments to {}", v);
                engine.manager.set_default_segments(v);
            }
        }
        "auto_max_connections" => {
            if let Ok(v) = value.parse::<i32>() {
                log_info!("[actor] updating auto_max_connections to {}", v);
                engine.manager.set_auto_max_connections(v);
            }
        }
        "cdn_multi_enabled" => {
            let v = value == "1" || value == "true";
            log_info!("[actor] updating cdn_multi_enabled to {}", v);
            engine.manager.set_cdn_multi_enabled(v);
        }
        "multi_nic_enabled" => {
            let v = value == "1" || value == "true";
            log_info!("[actor] updating multi_nic_enabled to {}", v);
            engine.manager.set_multi_nic_enabled(v);
        }
        "cdn_max_nodes" => {
            if let Ok(v) = value.parse::<i32>() {
                let v = v.clamp(0, 8);
                log_info!("[actor] updating cdn_max_nodes to {}", v);
                engine.manager.set_cdn_max_nodes(v);
            }
        }
        "cdn_resolver_endpoints" => {
            log_info!("[actor] updating cdn_resolver_endpoints");
            engine.manager.set_cdn_resolver_endpoints(value);
        }
        "cdn_hints_base" => {
            log_info!("[actor] updating cdn_hints_base");
            engine.manager.set_cdn_hints_base(value);
        }
        "cdn_ecs_subnets" => {
            log_info!("[actor] updating cdn_ecs_subnets");
            engine.manager.set_cdn_ecs_subnets(value);
        }
        "cdn_pending_reports" if value.is_empty() => {
            // Dart 上报成功后写空串清空；引擎自己写入的非空值不回调（避免自触发）。
            log_info!("[actor] clearing cdn_pending_reports");
            engine.manager.clear_cdn_pending_reports();
        }
        "cdn_pending_reports" => {}
        "use_server_time" => {
            let v = value == "true";
            log_info!("[actor] updating use_server_time to {}", v);
            engine.manager.set_use_server_time(v);
        }
        "file_exists_behavior" => {
            log_info!("[actor] updating file_exists_behavior to {}", value);
            engine.manager.set_file_exists_behavior(
                download_manager::FileExistsBehavior::from_config_str(value),
            );
        }
        "file_missing_action" => {
            let v = value == "delete";
            log_info!("[actor] updating file_missing_action auto-delete to {}", v);
            engine.manager.set_missing_file_auto_delete(v);
        }
        "max_auto_retries" => {
            if let Ok(v) = value.parse::<i32>() {
                log_info!("[actor] updating max_auto_retries to {}", v);
                engine.manager.set_max_auto_retries(v);
            }
        }
        "auto_retry_delay_secs" => {
            if let Ok(v) = value.parse::<u64>() {
                log_info!("[actor] updating auto_retry_delay_secs to {}", v);
                engine.manager.set_auto_retry_delay_secs(v);
            }
        }
        // 值为空 = 用户在设置中点了「清除已学习的服务器策略」：清空内存缓存
        // 并重写持久化（非空值是引擎自己落盘的数据，不经此路径回流）。
        "domain_conn_caps" if value.is_empty() => {
            log_info!("[actor] clearing learned domain connection caps");
            engine.manager.clear_domain_conn_caps();
        }
        _ => {} // other config keys — no runtime action needed
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    /// 同进程二次 isolate：旧 runtime 释放 `engine.lock` 稍晚于新 actor 启动，
    /// 新 actor 必须在重试窗口内接手，而不是把 `WriterLeaseHeld` 当致命错误。
    #[tokio::test]
    async fn lease_retry_takes_over_after_previous_holder_releases() {
        let dir = std::env::temp_dir().join(format!(
            "fluxdown-hub-lease-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();

        let (_first_db, first_guard) = Db::open_exclusive(&dir).await.unwrap();
        let release = tokio::spawn(async move {
            tokio::time::sleep(LEASE_RETRY_INTERVAL * 2).await;
            drop(first_guard);
        });

        let second = open_db_with_lease_retry(&dir).await;
        release.await.unwrap();
        assert!(
            second.is_ok(),
            "expected takeover, got {:?}",
            second.as_ref().err()
        );
        drop(second);
        if let Err(error) = std::fs::remove_dir_all(&dir) {
            eprintln!("remove lease test directory failed: {error}");
        }
    }
}
