//! Mobile auto-update module: version check via website API proxy and
//! multi-segment concurrent download of the Android APK. Installation is
//! handled on the Dart/Kotlin side (FileProvider install intent).
//!
//! All HTTP requests go through the website API (`/api/release`, `/api/download/:fn`).
//! `/api/download` 302s to the Cloudflare-cached mirror of the Aliyun OSS copy
//! (`dl-fluxdown.zerx.dev`, or a short-lived presigned OSS URL when no CDN base
//! is configured) when the release pipeline has synced the asset there, and
//! falls back to the GitHub release CDN otherwise. All honor Range requests, and
//! every segment below requests `/api/download` itself (fresh 302 each time), so
//! the multi-segment download works transparently and never depends on a
//! single redirect target's lifetime.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicI64, Ordering};
use std::time::Duration;

use futures_util::StreamExt;
use reqwest::Client;
use rinf::RustSignal;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::io::{AsyncSeekExt, AsyncWriteExt};

use crate::logger::log_info;
use crate::signals::{UpdateCheckResult, UpdateDownloadProgress};

/// Number of concurrent segments for update downloads.
/// Kept modest — update files are typically 10-30 MB and served from CDN.
const UPDATE_SEGMENTS: i32 = 8;

/// Minimum file size (bytes) to use multi-segment download. Below this
/// threshold the overhead of multiple connections is not worth it.
const MIN_SIZE_FOR_MULTI_SEGMENT: i64 = 2 * 1024 * 1024; // 2 MB

/// Per-segment retry budget for transient network errors. Each retry resumes
/// from the bytes already flushed to disk, never re-downloading the range.
const SEGMENT_RETRIES: u32 = 3;

/// Flush-to-disk interval. Resume progress counters only advance after a
/// flush, so on abrupt failure the sidecar never claims bytes that were still
/// sitting in tokio's write buffer (which would corrupt a resumed download).
const FLUSH_INTERVAL: i64 = 1024 * 1024; // 1 MB

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const UPDATE_API_BASE: &str = "https://fluxdown.zerx.dev";

// ---------------------------------------------------------------------------
// Error
// ---------------------------------------------------------------------------

#[derive(Error, Debug)]
pub enum UpdateError {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("semver error: {0}")]
    Semver(String),
    #[error("{0}")]
    Other(String),
}

// ---------------------------------------------------------------------------
// API response types (matching website /api/release)
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct AssetInfo {
    #[allow(dead_code)]
    name: String,
    size: i64,
    download_url: String,
}

/// `/api/release` 顶层 `mobile` 字段（官网按「完整包含 APK 的最新 release」
/// 选取）。`mobile` 为 `null` 表示尚无移动端 release —— 视为已是最新，而非错误。
#[derive(Deserialize)]
struct MobileReleaseEnvelope {
    mobile: Option<MobileRelease>,
}

#[derive(Deserialize)]
struct MobileRelease {
    version: String,
    assets: MobileAssets,
}

#[derive(Deserialize)]
struct MobileAssets {
    android_arm64: Option<AssetInfo>,
    android_armv7: Option<AssetInfo>,
    android_x64: Option<AssetInfo>,
    android_universal: Option<AssetInfo>,
}

// ---------------------------------------------------------------------------
// Asset selection
// ---------------------------------------------------------------------------

/// 按运行时 ABI 选择 APK 资产，缺失时兜底 universal；非 Android（iOS）无可下载资产。
///
/// `std::env::consts::ARCH`：`aarch64` → arm64-v8a、`arm` → armeabi-v7a、
/// `x86_64` → x86_64。未知 ABI 直接取 universal。
fn select_mobile_asset(assets: &MobileAssets) -> Option<&AssetInfo> {
    if !cfg!(target_os = "android") {
        return None;
    }
    let preferred = match std::env::consts::ARCH {
        "aarch64" => assets.android_arm64.as_ref(),
        "arm" => assets.android_armv7.as_ref(),
        "x86_64" => assets.android_x64.as_ref(),
        _ => None,
    };
    preferred.or(assets.android_universal.as_ref())
}

// ---------------------------------------------------------------------------
// SemVer comparison (major.minor.patch with optional prerelease suffix)
// ---------------------------------------------------------------------------
//
// Release channels map to the SemVer prerelease suffix: stable builds are
// tagged `vX.Y.Z`, frontier builds `vX.Y.Z-rc.N` (any `-suffix`). Comparison
// follows SemVer 2.0 precedence (§11) so that:
//   * a stable release outranks its own prereleases: `1.3.0 > 1.3.0-rc.2`
//   * a prerelease of a higher core outranks a lower stable: `1.4.0-rc.1 > 1.3.0`
//   * prereleases order by dot-separated identifiers (numeric < non-numeric,
//     numeric compared numerically, text lexically).
// Build metadata (`+meta`) is ignored, as SemVer requires.

/// A single prerelease identifier. Numeric identifiers compare numerically and
/// always rank below alphanumeric ones (SemVer 2.0 §11.4).
enum PreId {
    Num(u64),
    Text(String),
}

/// Parsed semantic version: `major.minor.patch` core plus optional prerelease
/// identifiers. Only the subset FluxDown emits (`X.Y.Z` / `X.Y.Z-pre`) is used.
struct SemVer {
    core: (u64, u64, u64),
    pre: Vec<PreId>,
}

fn parse_semver(s: &str) -> Result<SemVer, UpdateError> {
    let s = s.strip_prefix('v').unwrap_or(s);
    // Drop build metadata (everything after the first '+').
    let s = s.split('+').next().unwrap_or(s);
    // Split the core from the prerelease suffix on the first '-'.
    let (core_str, pre_str) = match s.split_once('-') {
        Some((core, pre)) => (core, Some(pre)),
        None => (s, None),
    };

    let parts: Vec<&str> = core_str.split('.').collect();
    if parts.len() != 3 {
        return Err(UpdateError::Semver(format!("invalid version: {s}")));
    }
    let major = parts[0]
        .parse::<u64>()
        .map_err(|_| UpdateError::Semver(format!("invalid major: {}", parts[0])))?;
    let minor = parts[1]
        .parse::<u64>()
        .map_err(|_| UpdateError::Semver(format!("invalid minor: {}", parts[1])))?;
    let patch = parts[2]
        .parse::<u64>()
        .map_err(|_| UpdateError::Semver(format!("invalid patch: {}", parts[2])))?;

    let pre = match pre_str {
        None => Vec::new(),
        Some("") => {
            return Err(UpdateError::Semver(format!("empty prerelease: {s}")));
        }
        Some(p) => p
            .split('.')
            .map(|id| match id.parse::<u64>() {
                Ok(n) => PreId::Num(n),
                Err(_) => PreId::Text(id.to_string()),
            })
            .collect(),
    };

    Ok(SemVer {
        core: (major, minor, patch),
        pre,
    })
}

/// Compare prerelease identifier lists (SemVer 2.0 §11.4).
fn cmp_pre(a: &[PreId], b: &[PreId]) -> std::cmp::Ordering {
    use std::cmp::Ordering;
    for (x, y) in a.iter().zip(b.iter()) {
        let ord = match (x, y) {
            (PreId::Num(m), PreId::Num(n)) => m.cmp(n),
            (PreId::Text(m), PreId::Text(n)) => m.cmp(n),
            (PreId::Num(_), PreId::Text(_)) => Ordering::Less,
            (PreId::Text(_), PreId::Num(_)) => Ordering::Greater,
        };
        if ord != Ordering::Equal {
            return ord;
        }
    }
    // All shared identifiers equal → the longer set has higher precedence.
    a.len().cmp(&b.len())
}

/// SemVer 2.0 precedence. When cores are equal, a version with no prerelease
/// outranks one that carries a prerelease suffix.
fn cmp_semver(a: &SemVer, b: &SemVer) -> std::cmp::Ordering {
    use std::cmp::Ordering;
    match a.core.cmp(&b.core) {
        Ordering::Equal => {}
        non_eq => return non_eq,
    }
    match (a.pre.is_empty(), b.pre.is_empty()) {
        (true, true) => Ordering::Equal,
        (true, false) => Ordering::Greater, // stable > prerelease
        (false, true) => Ordering::Less,    // prerelease < stable
        (false, false) => cmp_pre(&a.pre, &b.pre),
    }
}

fn is_newer(latest: &str, current: &str) -> Result<bool, UpdateError> {
    let l = parse_semver(latest)?;
    let c = parse_semver(current)?;
    Ok(cmp_semver(&l, &c) == std::cmp::Ordering::Greater)
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/// Check for updates by querying the website API proxy.
/// Sends `UpdateCheckResult` signal back to Dart.
pub async fn check(current_version: &str, channel: &str) {
    let result = check_inner(current_version, channel).await;
    match result {
        Ok(()) => {} // signal already sent inside check_inner
        Err(e) => {
            fluxdown_engine::logger::report_error("updater", "check for update", &e);
            UpdateCheckResult {
                has_update: false,
                latest_version: String::new(),
                current_version: current_version.to_string(),
                download_url: String::new(),
                file_size: 0,
                published_at: String::new(),
                error_message: e.to_string(),
            }
            .send_signal_to_dart();
        }
    }
}

/// 检查：解析 `/api/release` 顶层 `mobile` 字段（移动端独立选取）。
/// `mobile == null`（尚无移动端 release）→ 已是最新。
async fn check_inner(current_version: &str, channel: &str) -> Result<(), UpdateError> {
    let client = Client::new();
    let url = format!("{UPDATE_API_BASE}/api/release?channel={channel}");

    let resp = client
        .get(&url)
        .timeout(Duration::from_secs(15))
        .send()
        .await?;

    if !resp.status().is_success() {
        return Err(UpdateError::Other(format!(
            "API returned status {}",
            resp.status()
        )));
    }

    let envelope: MobileReleaseEnvelope = resp.json().await?;

    let Some(mobile) = envelope.mobile else {
        // 尚无移动端 release —— 视为已是最新版本。
        UpdateCheckResult {
            has_update: false,
            latest_version: current_version.to_string(),
            current_version: current_version.to_string(),
            download_url: String::new(),
            file_size: 0,
            published_at: String::new(),
            error_message: String::new(),
        }
        .send_signal_to_dart();
        return Ok(());
    };

    let has_update = is_newer(&mobile.version, current_version).unwrap_or(false);

    let (download_url, file_size) = match select_mobile_asset(&mobile.assets) {
        Some(asset) => {
            let full_url = if asset.download_url.starts_with('/') {
                format!("{UPDATE_API_BASE}{}", asset.download_url)
            } else {
                asset.download_url.clone()
            };
            (full_url, asset.size)
        }
        None => (String::new(), 0),
    };

    UpdateCheckResult {
        // 没有可下载资产时不提示更新（无法完成下载流程）。
        has_update: has_update && !download_url.is_empty(),
        latest_version: mobile.version,
        current_version: current_version.to_string(),
        download_url,
        file_size,
        published_at: String::new(),
        error_message: String::new(),
    }
    .send_signal_to_dart();

    Ok(())
}

/// Download the update installer to a temp directory.
/// Sends periodic `UpdateDownloadProgress` signals to Dart.
///
/// Uses multi-segment concurrent downloading (like the main download engine)
/// to maximise throughput and showcase the product's core capability.
/// Falls back to single-stream when the server does not support Range requests.
/// A failed or interrupted download leaves the partial artifact plus a
/// `<file>.resume.json` sidecar behind; the next download attempt for the
/// same version resumes from the recorded per-segment offsets.
pub async fn download(url: &str, version: &str, file_size: i64) {
    let result = download_inner(url, version, file_size).await;
    if let Err(e) = result {
        fluxdown_engine::logger::report_error("updater", "download update", &e);
        UpdateDownloadProgress {
            version: version.to_string(),
            downloaded_bytes: 0,
            total_bytes: 0,
            speed: 0,
            status: 2, // error
            installer_path: String::new(),
            error_message: e.to_string(),
            segments: 0,
            active_segments: 0,
        }
        .send_signal_to_dart();
    }
}

// ---------------------------------------------------------------------------
// Security: filename & script interpolation sanitizers
// ---------------------------------------------------------------------------

/// Sanitize a filename extracted from a URL to prevent path-traversal attacks.
/// Strips directory components, `..` sequences, NUL bytes, and URL query
/// strings (`?…`) that would produce OS-invalid filenames on Windows.
/// Falls back to a safe default if the result is empty.
fn sanitize_filename(raw: &str) -> String {
    // Strip query string and fragment before extracting the filename.
    // Each fallback must land on the previously-stripped value, never the
    // original `raw` — otherwise a URL without a `#` fragment would restore
    // the query string and yield an OS-invalid Windows filename.
    let base = raw.split_once('?').map(|(b, _)| b).unwrap_or(raw);
    let without_query = base.split_once('#').map(|(b, _)| b).unwrap_or(base);

    let name = without_query
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or("")
        .replace("..", "")
        .replace('\0', "");
    let name = name.trim();
    if name.is_empty() || name == "." {
        "FluxDown-update".to_string()
    } else {
        name.to_string()
    }
}

/// Where to deposit the downloaded update artifact.
///
/// On Android we use the app-private cache dir (`/data/data/<pkg>/cache`) —
/// `TMPDIR` is not guaranteed in an Android app process, and the FileProvider
/// declared in the manifest exposes exactly this directory for the install
/// intent on the Dart/Kotlin side. Elsewhere we fall back to the OS temp dir.
fn pick_download_dir() -> PathBuf {
    #[cfg(target_os = "android")]
    {
        if let Some(pkg) = fluxdown_engine::data_dir::android_package_name() {
            let cache = PathBuf::from(format!("/data/data/{pkg}/cache"));
            if cache.is_dir() || std::fs::create_dir_all(&cache).is_ok() {
                return cache;
            }
        }
    }
    std::env::temp_dir()
}

async fn download_inner(url: &str, version: &str, hint_file_size: i64) -> Result<(), UpdateError> {
    let client = Client::builder()
        .timeout(Duration::from_secs(600))
        .build()?;

    // ── Phase 1: Probe Range support via GET Range:0-0 ──────────────────
    // We already know the file size from the check phase (`hint_file_size`).
    // HEAD requests often fail through API proxies / CDN redirects (returning
    // 0 Content-Length), so we probe Range support with a tiny GET instead.
    let mut supports_range = false;
    let mut total_bytes = hint_file_size;

    let probe_resp = client.get(url).header("Range", "bytes=0-0").send().await;

    if let Ok(resp) = probe_resp {
        if resp.status() == reqwest::StatusCode::PARTIAL_CONTENT {
            supports_range = true;
            // Try to extract total size from Content-Range: bytes 0-0/<total>
            if let Some(cr) = resp.headers().get("content-range")
                && let Ok(cr_str) = cr.to_str()
                && let Some(slash_pos) = cr_str.rfind('/')
                && let Ok(size) = cr_str[slash_pos + 1..].parse::<i64>()
                && size > 0
            {
                total_bytes = size;
            }
        } else if resp.status().is_success() {
            // Server ignored Range, returned 200 OK — no Range support.
            // Try to get Content-Length as fallback for total_bytes.
            if total_bytes <= 0 {
                total_bytes = resp.content_length().unwrap_or(0) as i64;
            }
        }
    }

    // If we still don't know the size, it's not critical — progress will
    // show as indeterminate. But we can't do multi-segment without knowing it.

    let raw_name = url
        .rsplit('/')
        .next()
        .filter(|n| !n.is_empty())
        .unwrap_or("FluxDown-update");
    let file_name = sanitize_filename(raw_name);
    let download_dir = pick_download_dir();
    let file_path = download_dir.join(&file_name);

    let use_multi = supports_range
        && total_bytes > 0
        && total_bytes >= MIN_SIZE_FOR_MULTI_SEGMENT
        && UPDATE_SEGMENTS > 1;

    log_info!(
        "[updater] download {} total_bytes={} (hint={}) supports_range={} multi={}",
        file_name,
        total_bytes,
        hint_file_size,
        supports_range,
        use_multi
    );

    if use_multi {
        download_multi_segment(url, version, &file_path, total_bytes, &client).await?;
    } else {
        download_single_stream(
            url,
            version,
            &file_path,
            total_bytes,
            &client,
            supports_range,
        )
        .await?;
    }

    let installer_path = file_path.to_string_lossy().to_string();

    // Send completion signal
    UpdateDownloadProgress {
        version: version.to_string(),
        downloaded_bytes: total_bytes,
        total_bytes,
        speed: 0,
        status: 1, // completed
        installer_path,
        error_message: String::new(),
        segments: if use_multi { UPDATE_SEGMENTS } else { 1 },
        active_segments: 0,
    }
    .send_signal_to_dart();

    Ok(())
}

// ---------------------------------------------------------------------------
// Single-stream fallback (original behaviour)
// ---------------------------------------------------------------------------

async fn download_single_stream(
    url: &str,
    version: &str,
    file_path: &PathBuf,
    total_bytes: i64,
    client: &Client,
    supports_range: bool,
) -> Result<(), UpdateError> {
    // Resume is possible only when the server honours Range and the final
    // size is known (the file is then pre-allocated to full size, exactly
    // like the multi-segment path, so `load_resume`'s length check applies).
    let track_resume = supports_range && total_bytes > 0;
    let ranges = [SegmentRange {
        start: 0,
        end: total_bytes - 1,
    }];

    let mut resume_from: i64 = 0;
    if track_resume && let Some(done) = load_resume(file_path, version, total_bytes, &ranges).await
    {
        resume_from = done[0].min(total_bytes);
    }
    if track_resume && resume_from >= total_bytes {
        // Everything already on disk from a previous attempt.
        remove_resume(file_path).await;
        return Ok(());
    }

    let mut request = client.get(url);
    if resume_from > 0 {
        request = request.header("Range", format!("bytes={resume_from}-"));
    }
    let resp = request.send().await?;
    if !resp.status().is_success() {
        return Err(UpdateError::Other(format!(
            "Download returned status {}",
            resp.status()
        )));
    }
    if resume_from > 0 && resp.status() != reqwest::StatusCode::PARTIAL_CONTENT {
        // Server ignored the Range header after all — restart from scratch.
        log_info!(
            "[updater] resume rejected (got {}), restarting",
            resp.status()
        );
        resume_from = 0;
    }

    let mut file = if track_resume {
        let file = tokio::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(false)
            .open(file_path)
            .await?;
        file.set_len(total_bytes as u64).await?;
        file
    } else {
        tokio::fs::File::create(file_path).await?
    };
    if resume_from > 0 {
        file.seek(std::io::SeekFrom::Start(resume_from as u64))
            .await?;
    }

    let mut stream = resp.bytes_stream();

    let mut downloaded: i64 = resume_from;
    let mut flushed: i64 = resume_from;
    let mut unflushed: i64 = 0;
    let mut last_report = std::time::Instant::now();
    let mut last_downloaded_for_speed: i64 = downloaded;
    let mut last_speed_time = std::time::Instant::now();
    let report_interval = Duration::from_millis(200);
    let mut ticks: u32 = 0;

    while let Some(chunk) = stream.next().await {
        let chunk = match chunk {
            Ok(c) => c,
            Err(e) => {
                // Persist progress (flushed bytes only) so the next attempt
                // resumes instead of restarting.
                if track_resume {
                    save_resume(file_path, version, total_bytes, &ranges, &[flushed]).await;
                }
                return Err(UpdateError::Http(e));
            }
        };
        file.write_all(&chunk).await?;
        downloaded += chunk.len() as i64;
        unflushed += chunk.len() as i64;
        if unflushed >= FLUSH_INTERVAL {
            file.flush().await?;
            flushed = downloaded;
            unflushed = 0;
        }

        let now = std::time::Instant::now();
        if now.duration_since(last_report) >= report_interval {
            let elapsed_secs = now.duration_since(last_speed_time).as_secs_f64();
            let speed = if elapsed_secs > 0.0 {
                ((downloaded - last_downloaded_for_speed) as f64 / elapsed_secs) as i64
            } else {
                0
            };
            last_downloaded_for_speed = downloaded;
            last_speed_time = now;

            UpdateDownloadProgress {
                version: version.to_string(),
                downloaded_bytes: downloaded,
                total_bytes,
                speed,
                status: 0,
                installer_path: String::new(),
                error_message: String::new(),
                segments: 1,
                active_segments: 1,
            }
            .send_signal_to_dart();

            last_report = now;
            ticks += 1;
            if track_resume && ticks.is_multiple_of(5) {
                save_resume(file_path, version, total_bytes, &ranges, &[flushed]).await;
            }
        }
    }

    file.flush().await?;
    flushed = downloaded;

    if track_resume {
        if downloaded < total_bytes {
            // Connection closed early — keep state for the next attempt.
            save_resume(file_path, version, total_bytes, &ranges, &[flushed]).await;
            return Err(UpdateError::Other(format!(
                "connection closed early: {downloaded}/{total_bytes} bytes"
            )));
        }
        remove_resume(file_path).await;
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Resume state (sidecar file next to the partially-downloaded artifact)
// ---------------------------------------------------------------------------

/// Sidecar recording per-segment progress so a failed/interrupted update
/// download resumes instead of restarting. The `version` field guards against
/// resuming a stale partial from a previous release (asset filenames are
/// version-less, so the artifact path alone cannot disambiguate).
#[derive(Serialize, Deserialize)]
struct ResumeState {
    version: String,
    total_bytes: i64,
    starts: Vec<i64>,
    ends: Vec<i64>,
    done: Vec<i64>,
}

/// `<artifact>.resume.json` next to the download artifact.
fn resume_path(file_path: &Path) -> PathBuf {
    let mut name = file_path
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    name.push(".resume.json");
    file_path.with_file_name(name)
}

/// Load and validate resume state. Returns the per-segment completed byte
/// counts only when the artifact is already pre-allocated to `total_bytes`
/// and the sidecar matches version, size, and the exact segment layout.
async fn load_resume(
    file_path: &Path,
    version: &str,
    total_bytes: i64,
    ranges: &[SegmentRange],
) -> Option<Vec<i64>> {
    let meta = tokio::fs::metadata(file_path).await.ok()?;
    if meta.len() as i64 != total_bytes {
        return None;
    }
    let bytes = tokio::fs::read(resume_path(file_path)).await.ok()?;
    let state: ResumeState = serde_json::from_slice(&bytes).ok()?;
    if state.version != version
        || state.total_bytes != total_bytes
        || state.starts.len() != ranges.len()
        || state.ends.len() != ranges.len()
        || state.done.len() != ranges.len()
    {
        return None;
    }
    for (i, r) in ranges.iter().enumerate() {
        let seg_len = r.end - r.start + 1;
        if state.starts[i] != r.start
            || state.ends[i] != r.end
            || state.done[i] < 0
            || state.done[i] > seg_len
        {
            return None;
        }
    }
    Some(state.done)
}

async fn remove_resume(file_path: &Path) {
    match tokio::fs::remove_file(resume_path(file_path)).await {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => {
            crate::logger::report_error("updater", "remove completed resume state", &error)
        }
    }
}

/// Best-effort persist of resume state; failures are reported (the next attempt
/// may need to start fresh).
async fn save_resume(
    file_path: &Path,
    version: &str,
    total_bytes: i64,
    ranges: &[SegmentRange],
    done: &[i64],
) {
    let state = ResumeState {
        version: version.to_string(),
        total_bytes,
        starts: ranges.iter().map(|r| r.start).collect(),
        ends: ranges.iter().map(|r| r.end).collect(),
        done: done.to_vec(),
    };
    let bytes = match serde_json::to_vec(&state) {
        Ok(bytes) => bytes,
        Err(error) => {
            crate::logger::report_error("updater", "serialize resume state", &error);
            return;
        }
    };
    if let Err(error) = tokio::fs::write(resume_path(file_path), bytes).await {
        crate::logger::report_error("updater", "persist resume state", &error);
    }
}

// ---------------------------------------------------------------------------
// Multi-segment concurrent download
// ---------------------------------------------------------------------------

/// Per-segment byte range [start, end] (inclusive).
struct SegmentRange {
    start: i64,
    end: i64,
}

async fn download_multi_segment(
    url: &str,
    version: &str,
    file_path: &PathBuf,
    total_bytes: i64,
    client: &Client,
) -> Result<(), UpdateError> {
    let seg_count = UPDATE_SEGMENTS as i64;

    // Compute byte ranges for each segment
    let seg_size = total_bytes / seg_count;
    let mut ranges: Vec<SegmentRange> = Vec::with_capacity(seg_count as usize);
    for i in 0..seg_count {
        let start = i * seg_size;
        let end = if i == seg_count - 1 {
            total_bytes - 1
        } else {
            (i + 1) * seg_size - 1
        };
        ranges.push(SegmentRange { start, end });
    }
    let ranges = Arc::new(ranges);

    // Resume: a matching sidecar + full-size artifact from a previous failed
    // attempt lets every segment continue where it stopped.
    let resumed = load_resume(file_path, version, total_bytes, &ranges).await;
    if resumed.is_none() {
        // Fresh start: pre-allocate the output file to the full size.
        let file = tokio::fs::File::create(file_path).await?;
        file.set_len(total_bytes as u64).await?;
    }
    let initial: Vec<i64> = resumed.unwrap_or_else(|| vec![0; seg_count as usize]);
    let initial_total: i64 = initial.iter().sum();
    if initial_total > 0 {
        log_info!(
            "[updater] resuming update download: {}/{} bytes already on disk",
            initial_total,
            total_bytes
        );
    }

    // Shared progress counters — each segment atomically advances its own
    // counter (absolute completed bytes within the segment) so the reporter
    // task can sum them lock-free and persist resume state.
    let segment_progress: Arc<Vec<AtomicI64>> =
        Arc::new(initial.iter().map(|v| AtomicI64::new(*v)).collect());
    let active_count = Arc::new(AtomicI64::new(seg_count));

    // Spawn a progress reporter task (also persists resume state ~1×/s)
    let ver = version.to_string();
    let prog = Arc::clone(&segment_progress);
    let active = Arc::clone(&active_count);
    let reporter_ranges = Arc::clone(&ranges);
    let reporter_path = file_path.clone();
    let reporter = tokio::spawn(async move {
        let report_interval = Duration::from_millis(200);
        let mut last_total: i64 = initial_total;
        let mut last_time = std::time::Instant::now();
        let mut ticks: u32 = 0;

        loop {
            tokio::time::sleep(report_interval).await;
            ticks += 1;

            let downloaded: i64 = prog.iter().map(|a| a.load(Ordering::Relaxed)).sum();
            let now = std::time::Instant::now();
            let elapsed = now.duration_since(last_time).as_secs_f64();
            let speed = if elapsed > 0.0 {
                ((downloaded - last_total) as f64 / elapsed) as i64
            } else {
                0
            };
            last_total = downloaded;
            last_time = now;

            let cur_active = active.load(Ordering::Relaxed) as i32;

            UpdateDownloadProgress {
                version: ver.clone(),
                downloaded_bytes: downloaded,
                total_bytes,
                speed,
                status: 0,
                installer_path: String::new(),
                error_message: String::new(),
                segments: UPDATE_SEGMENTS,
                active_segments: cur_active,
            }
            .send_signal_to_dart();

            // All bytes received — stop reporting
            if downloaded >= total_bytes {
                break;
            }

            if ticks.is_multiple_of(5) {
                let done: Vec<i64> = prog.iter().map(|a| a.load(Ordering::Relaxed)).collect();
                save_resume(&reporter_path, &ver, total_bytes, &reporter_ranges, &done).await;
            }
        }
    });

    // Spawn one task per segment
    let mut handles = Vec::with_capacity(seg_count as usize);
    for idx in 0..seg_count as usize {
        let client = client.clone();
        let url = url.to_string();
        let file_path = file_path.clone();
        let seg_prog = Arc::clone(&segment_progress);
        let active_cnt = Arc::clone(&active_count);
        let ranges = Arc::clone(&ranges);

        let handle = tokio::spawn(async move {
            let result =
                download_segment(&client, &url, &file_path, idx, &ranges[idx], &seg_prog).await;
            active_cnt.fetch_sub(1, Ordering::Relaxed);
            result
        });
        handles.push(handle);
    }

    // Await all segment tasks and collect errors
    let mut first_error: Option<UpdateError> = None;
    for handle in handles {
        match handle.await {
            Ok(Ok(())) => {}
            Ok(Err(e)) => {
                if first_error.is_none() {
                    first_error = Some(e);
                }
            }
            Err(join_err) => {
                if first_error.is_none() {
                    first_error = Some(UpdateError::Other(format!(
                        "segment task panicked: {join_err}"
                    )));
                }
            }
        }
    }

    // Stop the reporter
    reporter.abort();
    if let Err(error) = reporter.await {
        if error.is_cancelled() {
            tracing::debug!("update progress reporter cancelled after download");
        } else {
            crate::logger::report_error("updater", "join update progress reporter", &error);
            if first_error.is_none() {
                first_error = Some(UpdateError::Other(format!(
                    "progress reporter failed: {error}"
                )));
            }
        }
    }

    if let Some(e) = first_error {
        // Keep the partial artifact and persist final progress so the next
        // attempt resumes instead of restarting from zero.
        let done: Vec<i64> = segment_progress
            .iter()
            .map(|a| a.load(Ordering::Relaxed))
            .collect();
        save_resume(file_path, version, total_bytes, &ranges, &done).await;
        return Err(e);
    }

    remove_resume(file_path).await;
    Ok(())
}

/// Download a single byte-range segment with retry. Each retry (and each
/// fresh `download` invocation, via the resume sidecar) continues from the
/// bytes already flushed to disk instead of re-downloading the whole range.
async fn download_segment(
    client: &Client,
    url: &str,
    file_path: &PathBuf,
    idx: usize,
    range: &SegmentRange,
    progress: &Arc<Vec<AtomicI64>>,
) -> Result<(), UpdateError> {
    let seg_len = range.end - range.start + 1;
    let mut attempt: u32 = 0;

    loop {
        let done = progress[idx].load(Ordering::Relaxed).clamp(0, seg_len);
        if done >= seg_len {
            return Ok(());
        }
        match download_segment_attempt(client, url, file_path, idx, range, done, progress).await {
            Ok(()) => {
                log_info!(
                    "[updater] segment {} finished: {}-{}",
                    idx,
                    range.start,
                    range.end
                );
                return Ok(());
            }
            Err(e) => {
                attempt += 1;
                if attempt > SEGMENT_RETRIES {
                    return Err(e);
                }
                log_info!(
                    "[updater] segment {} attempt {} failed: {}; retrying",
                    idx,
                    attempt,
                    e
                );
                tokio::time::sleep(Duration::from_secs(2u64 << (attempt - 1))).await;
            }
        }
    }
}

/// One attempt at a segment: requests only the missing tail of the range and
/// writes at the correct offset. `done` = bytes already valid on disk.
///
/// The progress counter (which feeds the resume sidecar) only advances after
/// `flush`, so an abrupt failure can never record bytes still sitting in
/// tokio's write buffer.
async fn download_segment_attempt(
    client: &Client,
    url: &str,
    file_path: &PathBuf,
    idx: usize,
    range: &SegmentRange,
    done: i64,
    progress: &Arc<Vec<AtomicI64>>,
) -> Result<(), UpdateError> {
    let start = range.start + done;
    let range_header = format!("bytes={}-{}", start, range.end);

    let resp = client
        .get(url)
        .header("Range", &range_header)
        .send()
        .await?;

    // Accept only 206 Partial Content. A 200 OK would be the whole file —
    // writing it at this offset would corrupt the artifact.
    if resp.status() != reqwest::StatusCode::PARTIAL_CONTENT {
        return Err(UpdateError::Other(format!(
            "segment {idx} expected 206 Partial Content, got {}",
            resp.status()
        )));
    }

    let mut file = tokio::fs::OpenOptions::new()
        .write(true)
        .open(file_path)
        .await?;
    file.seek(std::io::SeekFrom::Start(start as u64)).await?;

    let expected = range.end - start + 1;
    let mut stream = resp.bytes_stream();
    let mut written: i64 = 0;
    let mut unflushed: i64 = 0;

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(UpdateError::Http)?;
        if written + chunk.len() as i64 > expected {
            return Err(UpdateError::Other(format!(
                "segment {idx} server sent more bytes than requested"
            )));
        }
        file.write_all(&chunk).await?;
        written += chunk.len() as i64;
        unflushed += chunk.len() as i64;
        if unflushed >= FLUSH_INTERVAL {
            file.flush().await?;
            unflushed = 0;
            progress[idx].store(done + written, Ordering::Relaxed);
        }
    }

    file.flush().await?;

    if written < expected {
        // Short read: keep flushed progress, report as retryable error.
        progress[idx].store(done + written, Ordering::Relaxed);
        return Err(UpdateError::Other(format!(
            "segment {idx} truncated: got {written} of {expected} bytes"
        )));
    }

    progress[idx].store(done + written, Ordering::Relaxed);
    log_info!(
        "[updater] segment {} wrote {} bytes at offset {}",
        idx,
        written,
        start
    );
    Ok(())
}
