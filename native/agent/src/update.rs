//! Проверка обновлений для русской сборки через GitHub Releases собственного fork.
//!
//! Репозиторий обновлений: evg1394/FluxDown.
//! Windows x64 asset: FluxDown-X.Y.Z-RU-windows-x64-setup.exe.

use std::cmp::Ordering;
use std::time::Duration;

use fluxdown_protocol::{ReleaseNoteDto, UpdateCheckResultDto};
use serde::Deserialize;

use crate::http_client::{HttpClientError, LazyHttpClient};

const GITHUB_API_BASE: &str = "https://api.github.com";
const GITHUB_REPO: &str = "evg1394/FluxDown";
const RELEASE_PAGE_URL: &str = "https://github.com/evg1394/FluxDown/releases";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);
const RELEASES_PER_PAGE: u32 = 50;

#[derive(Debug, thiserror::Error)]
pub enum UpdateError {
    #[error("unknown update channel: {0}")]
    InvalidChannel(String),
    #[error("update API request failed: {0}")]
    Http(#[from] reqwest::Error),
    #[error("update HTTP client unavailable: {0}")]
    Client(#[from] HttpClientError),
    #[error("update HTTP API returned status {0}")]
    Status(u16),
    #[error("update API response is invalid: {0}")]
    Decode(String),
}

#[derive(Debug, Deserialize)]
struct GithubAsset {
    name: String,
    browser_download_url: String,
}

#[derive(Debug, Deserialize)]
struct GithubRelease {
    tag_name: String,
    #[serde(default)]
    published_at: Option<String>,
    #[serde(default)]
    body: Option<String>,
    html_url: String,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    assets: Vec<GithubAsset>,
}

pub struct UpdateService {
    current_version: String,
    http: LazyHttpClient,
}

impl UpdateService {
    #[must_use]
    pub fn new(current_version: &str) -> Self {
        let user_agent = format!("fluxdown-agent/{current_version}");
        let http = LazyHttpClient::new(move || {
            reqwest::Client::builder()
                .connect_timeout(Duration::from_secs(10))
                .timeout(REQUEST_TIMEOUT)
                .user_agent(user_agent.clone())
        });
        Self {
            current_version: current_version.to_owned(),
            http,
        }
    }

    /// 查询自己 fork 的 GitHub Releases，并附带比当前版本新的更新说明。
    pub async fn check(&self, channel: &str) -> Result<UpdateCheckResultDto, UpdateError> {
        let channel = normalize_channel(channel)?;
        let release = self.fetch_release(channel).await?;
        let has_update = is_newer(&release.tag_name, &self.current_version).unwrap_or(false);
        let download_url = select_download_url(&release.assets);
        let notes = if has_update {
            self.fetch_notes(channel).await
        } else {
            Vec::new()
        };

        Ok(UpdateCheckResultDto {
            channel: channel.to_owned(),
            current_version: self.current_version.clone(),
            latest_version: normalize_version(&release.tag_name),
            has_update,
            download_url: download_url.unwrap_or_default(),
            release_page_url: release.html_url,
            notes,
        })
    }

    async fn fetch_release(&self, channel: &str) -> Result<GithubRelease, UpdateError> {
        if channel == "stable" {
            let url = format!(
                "{GITHUB_API_BASE}/repos/{GITHUB_REPO}/releases/latest"
            );
            let response = self.http.get().await?.get(&url).send().await?;
            if !response.status().is_success() {
                return Err(UpdateError::Status(response.status().as_u16()));
            }
            return response
                .json::<GithubRelease>()
                .await
                .map_err(|error| UpdateError::Decode(error.to_string()));
        }

        let releases = self.fetch_releases().await?;
        releases
            .into_iter()
            .find(|release| !release.draft && release.prerelease)
            .ok_or_else(|| UpdateError::Decode("no frontier release found".to_owned()))
    }

    async fn fetch_releases(&self) -> Result<Vec<GithubRelease>, UpdateError> {
        let url = format!(
            "{GITHUB_API_BASE}/repos/{GITHUB_REPO}/releases?per_page={RELEASES_PER_PAGE}"
        );
        let response = self.http.get().await?.get(&url).send().await?;
        if !response.status().is_success() {
            return Err(UpdateError::Status(response.status().as_u16()));
        }
        response
            .json::<Vec<GithubRelease>>()
            .await
            .map_err(|error| UpdateError::Decode(error.to_string()))
    }

    async fn fetch_notes(&self, channel: &str) -> Vec<ReleaseNoteDto> {
        let releases = match self.fetch_releases().await {
            Ok(releases) => releases,
            Err(error) => {
                tracing::debug!(error = %error, "release notes fetch failed");
                return Vec::new();
            }
        };

        releases
            .into_iter()
            .filter(|release| !release.draft)
            .filter(|release| {
                if channel == "frontier" {
                    release.prerelease
                } else {
                    !release.prerelease
                }
            })
            .filter(|release| is_newer(&release.tag_name, &self.current_version).unwrap_or(false))
            .filter_map(|release| {
                Some(ReleaseNoteDto {
                    version: normalize_version(&release.tag_name),
                    published_at: release.published_at.unwrap_or_default(),
                    body: release.body.unwrap_or_default(),
                })
            })
            .collect()
    }
}

fn normalize_channel(channel: &str) -> Result<&'static str, UpdateError> {
    match channel.trim() {
        "" | "stable" => Ok("stable"),
        "frontier" => Ok("frontier"),
        other => Err(UpdateError::InvalidChannel(other.to_owned())),
    }
}

fn normalize_version(version: &str) -> String {
    version.trim().strip_prefix('v').unwrap_or(version.trim()).to_owned()
}

/// Asset выбирается по точному имени, чтобы русская сборка никогда не скачала оригинальный EXE.
#[cfg(target_os = "windows")]
fn select_download_url(assets: &[GithubAsset]) -> Option<String> {
    let arch = std::env::consts::ARCH;
    let suffix = if arch == "aarch64" { "windows-arm64-setup.exe" } else { "windows-x64-setup.exe" };
    assets
        .iter()
        .find(|asset| asset.name.starts_with("FluxDown-")
            && asset.name.ends_with("-RU-") == false
            && asset.name.ends_with(suffix)
            && asset.name.contains("-RU-windows-"))
        .map(|asset| asset.browser_download_url.clone())
        .filter(|url| is_trusted_download_url(url))
}

#[cfg(not(target_os = "windows"))]
fn select_download_url(_assets: &[GithubAsset]) -> Option<String> {
    None
}

fn is_trusted_download_url(url: &str) -> bool {
    let Ok(parsed) = reqwest::Url::parse(url) else {
        return false;
    };
    if parsed.scheme() != "https" || !parsed.username().is_empty() || parsed.password().is_some() {
        return false;
    }
    matches!(parsed.host_str(), Some("github.com") | Some("objects.githubusercontent.com"))
}

#[derive(Debug, PartialEq, Eq)]
enum PreId {
    Num(u64),
    Text(String),
}

#[derive(Debug, PartialEq, Eq)]
struct SemVer {
    core: (u64, u64, u64),
    pre: Vec<PreId>,
}

fn parse_semver(input: &str) -> Result<SemVer, UpdateError> {
    let input = input.trim();
    let input = input.strip_prefix('v').unwrap_or(input);
    let input = input.split('+').next().unwrap_or(input);
    let (core, pre) = match input.split_once('-') {
        Some((core, pre)) => (core, Some(pre)),
        None => (input, None),
    };
    let mut parts = core.split('.');
    let mut next_part = |name: &str| -> Result<u64, UpdateError> {
        parts
            .next()
            .ok_or_else(|| UpdateError::Decode(format!("missing {name} in version: {input}")))?
            .parse::<u64>()
            .map_err(|_| UpdateError::Decode(format!("invalid {name} in version: {input}")))
    };
    let major = next_part("major")?;
    let minor = next_part("minor")?;
    let patch = next_part("patch")?;
    if parts.next().is_some() {
        return Err(UpdateError::Decode(format!("invalid version: {input}")));
    }
    let pre = match pre {
        None => Vec::new(),
        Some("") => return Err(UpdateError::Decode(format!("empty prerelease: {input}"))),
        Some(pre) => pre
            .split('.')
            .map(|id| match id.parse::<u64>() {
                Ok(number) => PreId::Num(number),
                Err(_) => PreId::Text(id.to_owned()),
            })
            .collect(),
    };
    Ok(SemVer {
        core: (major, minor, patch),
        pre,
    })
}

fn cmp_pre(a: &[PreId], b: &[PreId]) -> Ordering {
    for (x, y) in a.iter().zip(b.iter()) {
        let ordering = match (x, y) {
            (PreId::Num(m), PreId::Num(n)) => m.cmp(n),
            (PreId::Text(m), PreId::Text(n)) => m.cmp(n),
            (PreId::Num(_), PreId::Text(_)) => Ordering::Less,
            (PreId::Text(_), PreId::Num(_)) => Ordering::Greater,
        };
        if ordering != Ordering::Equal {
            return ordering;
        }
    }
    a.len().cmp(&b.len())
}

fn cmp_semver(a: &SemVer, b: &SemVer) -> Ordering {
    match a.core.cmp(&b.core) {
        Ordering::Equal => {}
        ordering => return ordering,
    }
    match (a.pre.is_empty(), b.pre.is_empty()) {
        (true, true) => Ordering::Equal,
        (true, false) => Ordering::Greater,
        (false, true) => Ordering::Less,
        (false, false) => cmp_pre(&a.pre, &b.pre),
    }
}

pub fn is_newer(latest: &str, current: &str) -> Result<bool, UpdateError> {
    let latest = parse_semver(latest)?;
    let current = parse_semver(current)?;
    Ok(cmp_semver(&latest, &current) == Ordering::Greater)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::{is_newer, normalize_channel, normalize_version};

    #[test]
    fn stable_versions_compare_numerically() {
        assert!(is_newer("1.3.0", "1.2.5").unwrap());
        assert!(is_newer("1.10.0", "1.9.9").unwrap());
        assert!(!is_newer("1.2.5", "1.2.5").unwrap());
        assert!(is_newer("v2.0.0", "1.99.99").unwrap());
    }

    #[test]
    fn prerelease_precedence_follows_semver() {
        assert!(is_newer("1.3.0", "1.3.0-rc.1").unwrap());
        assert!(!is_newer("1.3.0-rc.2", "1.3.0").unwrap());
        assert!(is_newer("1.3.0-rc.2", "1.3.0-rc.1").unwrap());
        assert!(is_newer("1.4.0-rc.1", "1.3.0").unwrap());
    }

    #[test]
    fn channels_normalize_and_reject_unknown() {
        assert_eq!(normalize_channel("" ).unwrap(), "stable");
        assert_eq!(normalize_channel("stable").unwrap(), "stable");
        assert_eq!(normalize_channel(" frontier ").unwrap(), "frontier");
        assert!(normalize_channel("nightly").is_err());
    }

    #[test]
    fn versions_drop_tag_prefix() {
        assert_eq!(normalize_version("v0.5.3"), "0.5.3");
        assert_eq!(normalize_version("0.5.3"), "0.5.3");
    }
}
