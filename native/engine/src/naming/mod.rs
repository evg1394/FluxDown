//! 下载文件名命名管线。
//!
//! 证据权威顺序（高 → 低）：
//! 1. 响应 `Content-Disposition`（`filename*` > `filename*0..N` 续行 > `filename`）；
//! 2. URL 查询里的 `response-content-disposition`（S3/OSS/COS/GCS）/ `rscd`（Azure SAS）——
//!    服务器会原样回显，是确定性信号；
//! 3. URL 路径末段（请求 URL 与重定向后 URL 择优）；
//! 4. 类型证据：响应 `Content-Type` → 扩展名；完成后文件头魔数 → 扩展名。
//!
//! 用户/API 显式给定的名字不经过本模块的推断，只做 [`sanitize_filename`]。
//! 解码规则的完整规范见 [`disposition`] 与 [`charset`]，扩展端 `fluxDown/utils/filename.ts`
//! 与之逐条一致。

mod charset;
mod disposition;
mod mime;
mod sanitize;
mod sniff;

pub use charset::decode_legacy_bytes;
pub use disposition::disposition_filename;
pub use mime::{has_plausible_extension, is_script_endpoint_name, mime_to_ext};
pub use sanitize::sanitize_filename;
pub use sniff::sniff_extension;

use charset::percent_decode_bytes;

/// 解码所需的上下文先验。
#[derive(Debug, Default, Clone, Copy)]
pub struct NameHints<'a> {
    /// 下载 URL 的主机名（TLD 先验：`.kr` / `.cn` / `.tw` / `.jp` …）。
    pub host: Option<&'a str>,
    /// 发起页的 `document.characterSet`（仅浏览器扩展能提供）。
    pub page_charset: Option<&'a str>,
}

/// 名字的来源，供上层判断它是权威名还是引擎推断名。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NameSource {
    /// 响应 `Content-Disposition`。
    Disposition,
    /// URL 查询里的 `response-content-disposition` / `rscd`。
    QueryDisposition,
    /// URL 路径末段（可能被 `Content-Type` 补/换了扩展名）。
    UrlPath,
    /// 仅由 `Content-Type` 构造的 `download.<ext>`。
    ContentType,
    /// 什么证据都没有的 `download`。
    Fallback,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedName {
    /// 已 sanitize 的文件名。
    pub name: String,
    pub source: NameSource,
}

/// 响应 + URL 命名：Content-Disposition → 查询里的 CD → URL 路径末段 → Content-Type → `download`。
///
/// URL 路径末段在请求 URL 与重定向后 URL 之间择优（见 [`url_derived_filename`]），再按
/// `Content-Type` 补扩展名（无像样扩展名）或换扩展名（脚本端点名，如 `download.php` +
/// `application/zip` → `download.zip`）。`hints.host` 取 `final_url` 的主机。
pub fn extract_filename(
    headers: &reqwest::header::HeaderMap,
    request_url: &str,
    final_url: &str,
) -> ResolvedName {
    let hints = NameHints {
        host: url_host(final_url),
        page_charset: None,
    };

    if let Some(value) = headers.get(reqwest::header::CONTENT_DISPOSITION)
        && let Some(raw) = disposition_filename(value.as_bytes(), hints)
    {
        return ResolvedName {
            name: sanitize_filename(&raw),
            source: NameSource::Disposition,
        };
    }

    // 预签名 URL 重定向后，真正带 CD 查询参数的往往是最终 URL。
    for url in [final_url, request_url] {
        if let Some(name) = query_disposition_name(url) {
            return ResolvedName {
                name,
                source: NameSource::QueryDisposition,
            };
        }
    }

    let content_type_ext = headers
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|ct| ct.to_str().ok())
        .and_then(mime_to_ext);

    // URL 段本身没有像样的扩展名（`pbs.twimg.com/media/<id>?format=jpg`、`/download/123`）
    // 时按 Content-Type 补扩展名，与浏览器命名一致，否则落盘文件无后缀、系统无法按类型打开
    // （#783）；脚本端点名（`download.php`）则被类型证据替换扩展名。
    if let Some(name) = url_derived_filename(request_url, final_url) {
        let name = content_type_ext
            .and_then(|ext| with_type_extension(&name, ext))
            .unwrap_or(name);
        return ResolvedName {
            name,
            source: NameSource::UrlPath,
        };
    }

    if let Some(ext) = content_type_ext {
        return ResolvedName {
            name: format!("download.{ext}"),
            source: NameSource::ContentType,
        };
    }

    ResolvedName {
        name: "download".to_string(),
        source: NameSource::Fallback,
    }
}

/// 只看 URL 的命名：先查询里的 `response-content-disposition` / `rscd`，再路径末段。
pub fn name_from_url(url: &str) -> Option<ResolvedName> {
    if let Some(name) = query_disposition_name(url) {
        return Some(ResolvedName {
            name,
            source: NameSource::QueryDisposition,
        });
    }
    extract_from_url(url).map(|name| ResolvedName {
        name,
        source: NameSource::UrlPath,
    })
}

/// 一个实际下载响应的命名证据：`(Content-Disposition 名（已 sanitize），Content-Type)`。
/// 没有 CD 时名字为 `None`；没有 Content-Type 时为空串。`url` 是响应的最终 URL（TLD 先验）。
pub fn response_naming(
    headers: &reqwest::header::HeaderMap,
    url: &str,
) -> (Option<String>, String) {
    let hints = NameHints {
        host: url_host(url),
        page_charset: None,
    };
    let disposition = headers
        .get(reqwest::header::CONTENT_DISPOSITION)
        .and_then(|value| disposition_filename(value.as_bytes(), hints))
        .map(|raw| sanitize_filename(&raw));
    let mime = headers
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|ct| ct.to_str().ok())
        .unwrap_or("")
        .trim()
        .to_string();
    (disposition, mime)
}

/// URL 路径末段（去掉 query / fragment，百分号解码，sanitize）。
///
/// 末段为空（以 `/` 结尾）返回 `None`。解码按 URL 主机作 TLD 先验。
pub fn extract_from_url(url: &str) -> Option<String> {
    let path = url.split('?').next().unwrap_or(url);
    let path = path.split('#').next().unwrap_or(path);
    let segment = path.rsplit('/').next()?;
    let hints = NameHints {
        host: url_host(url),
        page_charset: None,
    };
    let decoded = decode_legacy_bytes(&percent_decode_bytes(segment.as_bytes()), hints);
    let decoded = decoded.trim();
    if decoded.is_empty() || decoded == "/" {
        return None;
    }
    Some(sanitize_filename(decoded))
}

/// 类型证据补/换扩展名。
///
/// - 名字没有像样的扩展名 → 追加 `.{ext}`；
/// - 名字是脚本端点名 → 把扩展名换成 `ext`；
/// - 其余（显式扩展名）不动，返回 `None`。
///
/// `html` 不产出：transit/错误页靠「名字不像网页」被 HTML 安全网拦下，补成 `.html` 会让它们
/// 被当成正常下载落盘；脚本端点名遇到 HTML 类型证据也不能换。
fn with_type_extension(name: &str, ext: &str) -> Option<String> {
    if ext == "html" {
        return None;
    }
    if !has_plausible_extension(name) {
        return Some(sanitize_filename(&format!("{name}.{ext}")));
    }
    if is_script_endpoint_name(name)
        && let Some(dot) = name.rfind('.')
    {
        let replaced = sanitize_filename(&format!("{}.{ext}", &name[..dot]));
        return (replaced != name).then_some(replaced);
    }
    None
}

/// 在请求 URL 与重定向后 URL 的末段之间择名。
///
/// 重定向让两边都可能持有真名：GitHub 的 `archive/refs/tags/<tag>.zip` 重定向到 codeload 的
/// `.../zip/refs/tags/<tag>`（扩展名丢了，tag 里的点如 `11.0-1b` 反而造出假的 `.0-1b`），而
/// `download.php` 风格的端点重定向到 CDN URL，真文件名只在那里。没有放之四海皆准的静态偏好，
/// 逐例判断：
///
/// a. 最终段 == 请求段去掉其（可能多段的）扩展名 → 重定向必然丢了扩展名（codeload 模式，
///    `.zip` 与 `.tar.gz` 均适用），用请求段，把扩展名找回来；
/// b. 最终段带像样的扩展名 → 信它（短链、`download.php` → CDN 重定向）；
/// c. 只有请求段带像样的扩展名 → 用请求段（重定向目标结构上没有文件名）；
/// d. 都不像文件名 → 最终段，其次请求段。请求段这一档优先于 MIME 兜底：无扩展名的请求段
///    胜过泛化的 `download.<ext>`。
fn url_derived_filename(request_url: &str, final_url: &str) -> Option<String> {
    let from_request = extract_from_url(request_url);
    let from_final = extract_from_url(final_url);
    if let (Some(req), Some(fin)) = (&from_request, &from_final)
        && let Some(rest) = req.strip_prefix(fin.as_str())
        && let Some(ext) = rest.strip_prefix('.')
        && !ext.is_empty()
        && ext.split('.').all(|part| {
            (1..=10).contains(&part.len()) && part.chars().all(|c| c.is_ascii_alphanumeric())
        })
    {
        return from_request;
    }
    if let Some(fin) = &from_final
        && has_plausible_extension(fin)
    {
        return from_final;
    }
    if let Some(req) = &from_request
        && has_plausible_extension(req)
    {
        return from_request;
    }
    from_final.or(from_request)
}

/// URL 查询里的 `response-content-disposition` / `rscd`（参数名不区分大小写）→ 已 sanitize
/// 的文件名。值按 `application/x-www-form-urlencoded` 解码（`+` = 空格、`%XX` = 字节）。
fn query_disposition_name(url: &str) -> Option<String> {
    let (_, query) = url.split_once('?')?;
    let query = query.split('#').next().unwrap_or(query);
    let hints = NameHints {
        host: url_host(url),
        page_charset: None,
    };
    query.split('&').find_map(|pair| {
        let (key, value) = pair.split_once('=')?;
        if !(key.eq_ignore_ascii_case("response-content-disposition")
            || key.eq_ignore_ascii_case("rscd"))
        {
            return None;
        }
        let plus_as_space: Vec<u8> = value
            .bytes()
            .map(|b| if b == b'+' { b' ' } else { b })
            .collect();
        let header = percent_decode_bytes(&plus_as_space);
        disposition_filename(&header, hints).map(|name| sanitize_filename(&name))
    })
}

/// URL 的主机部分（不含 userinfo / 端口）；无法识别返回 `None`。
fn url_host(url: &str) -> Option<&str> {
    let after_scheme = url.split_once("://")?.1;
    let authority = after_scheme
        .split(['/', '?', '#'])
        .next()
        .unwrap_or(after_scheme);
    let host_port = authority.rsplit('@').next().unwrap_or(authority);
    let host = if let Some(rest) = host_port.strip_prefix('[') {
        rest.split(']').next().unwrap_or(rest)
    } else {
        host_port.split(':').next().unwrap_or(host_port)
    };
    (!host.is_empty()).then_some(host)
}

/// 命名证据：下载过程中观察到的、可用来精修「引擎自行推断」的名字。
#[derive(Debug, Default, Clone, Copy)]
pub struct NameEvidence<'a> {
    /// 实际 GET 响应观察到的 Content-Disposition 名（已解码，未 sanitize）。
    pub disposition: Option<&'a str>,
    /// 实际 GET 响应的 `Content-Type`。
    pub mime: Option<&'a str>,
    /// 文件头魔数嗅探出的扩展名。
    pub sniffed_ext: Option<&'static str>,
}

/// 用证据精修引擎推断出的名字 `current`；返回 sanitize 后且与 `current` 不同的新名，
/// 无需改动返回 `None`。
///
/// - `disposition` 存在 → 采用它（权威名优先于一切推断）；它本身没有像样的扩展名时
///   仍按类型证据追加（服务器给了 `filename="report"` 但内容是 PDF）；
/// - 否则用类型证据：无像样扩展名 → 追加；脚本端点名且证据不是 HTML → 换扩展名；
///   显式扩展名不动。
///
/// 类型证据里魔数优先于 `Content-Type`（魔数是对内容本身的观察，CDN 常把一切标成
/// `application/octet-stream` 或错标图片格式），唯一例外是魔数只认出泛化的 `zip`：
/// docx/xlsx/apk/jar/epub 等都是 zip 容器，此时更具体的 `Content-Type` 扩展名胜出。
pub fn refine_inferred_name(current: &str, evidence: &NameEvidence<'_>) -> Option<String> {
    let mime_ext = evidence.mime.and_then(mime_to_ext);
    let type_ext = match (evidence.sniffed_ext, mime_ext) {
        (Some("zip"), Some(mime @ ("docx" | "xlsx" | "pptx" | "apk" | "jar" | "epub"))) => {
            Some(mime)
        }
        (Some(sniffed), _) => Some(sniffed),
        (None, mime) => mime,
    };
    let disposition = evidence
        .disposition
        .map(str::trim)
        .filter(|name| !name.is_empty());
    let name = match disposition {
        Some(disposition) => {
            let name = sanitize_filename(disposition);
            match type_ext {
                Some(ext) if ext != "html" && !has_plausible_extension(&name) => {
                    sanitize_filename(&format!("{name}.{ext}"))
                }
                _ => name,
            }
        }
        None => with_type_extension(current, type_ext?)?,
    };
    (name != current).then_some(name)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::{
        NameEvidence, NameHints, NameSource, decode_legacy_bytes, extract_filename,
        extract_from_url, name_from_url, refine_inferred_name, sanitize_filename, url_host,
    };

    fn headers_with(pairs: &[(&'static str, &str)]) -> reqwest::header::HeaderMap {
        let mut headers = reqwest::header::HeaderMap::new();
        for (name, value) in pairs {
            headers.insert(
                reqwest::header::HeaderName::from_static(name),
                reqwest::header::HeaderValue::from_str(value).unwrap(),
            );
        }
        headers
    }

    fn make_headers_with_cd(value: &str) -> reqwest::header::HeaderMap {
        headers_with(&[("content-disposition", value)])
    }

    fn name_of(headers: &reqwest::header::HeaderMap, request_url: &str, final_url: &str) -> String {
        extract_filename(headers, request_url, final_url).name
    }

    #[test]
    fn url_host_extracts_host_only() {
        assert_eq!(url_host("https://example.com/a/b"), Some("example.com"));
        assert_eq!(url_host("http://u:p@a.co.kr:8080/x?y#z"), Some("a.co.kr"));
        assert_eq!(url_host("https://[::1]:80/x"), Some("::1"));
        assert_eq!(url_host("https://example.com?x=1"), Some("example.com"));
        assert_eq!(url_host("not a url"), None);
        assert_eq!(url_host("file:///tmp/a"), None);
    }

    // -----------------------------------------------------------------------
    // extract_from_url
    // -----------------------------------------------------------------------

    #[test]
    fn extract_from_url_basic() {
        let name = extract_from_url("https://example.com/path/file.zip");
        assert_eq!(name.as_deref(), Some("file.zip"));
    }

    #[test]
    fn extract_from_url_strips_query_and_fragment() {
        let name = extract_from_url("https://example.com/file.zip?v=1&token=abc#section");
        assert_eq!(name.as_deref(), Some("file.zip"));
    }

    #[test]
    fn extract_from_url_encoded_filename() {
        let name = extract_from_url("https://example.com/My%20File%20(1).pdf");
        assert_eq!(name.as_deref(), Some("My File (1).pdf"));
    }

    #[test]
    fn extract_from_url_trailing_slash_returns_none() {
        let name = extract_from_url("https://example.com/path/");
        assert!(
            name.is_none(),
            "trailing slash should return None, got: {name:?}"
        );
    }

    #[test]
    fn extract_from_url_no_path() {
        let name = extract_from_url("https://example.com");
        // The last segment is "example.com" — should extract it
        assert!(name.is_some());
    }

    #[test]
    fn extract_from_url_preserves_literal_plus() {
        // F046: 含字面 `+` 的文件名（C++ 教材、版本号 build metadata）不应被
        // `+`→空格 损坏。
        let name = extract_from_url("https://example.com/C++Primer.pdf");
        assert_eq!(name.as_deref(), Some("C++Primer.pdf"));
        let name = extract_from_url("https://example.com/v1.2+build.bin");
        assert_eq!(name.as_deref(), Some("v1.2+build.bin"));
    }

    #[test]
    fn extract_from_url_literal_percent_with_unicode_no_panic() {
        // F017: URL 路径段含字面 `%` 紧跟多字节 UTF-8 字符不应 panic。
        let name = extract_from_url("https://example.com/50%折扣.txt");
        assert_eq!(name.as_deref(), Some("50%折扣.txt"));
    }

    #[test]
    fn extract_from_url_chinese_filename() {
        let name = extract_from_url("https://example.com/%E4%B8%8B%E8%BD%BD.exe");
        assert_eq!(name.as_deref(), Some("下载.exe"));
    }

    #[test]
    fn extract_from_url_gbk_chinese_filename() {
        // 老旧中文站点用 GBK 编码中文：“文件” 的 GBK = CE C4 BC FE
        // UTF-8 解码会失败，必须回退到 GBK 才能得到可读文件名。
        let name = extract_from_url("http://example.com/%CE%C4%BC%FE.txt");
        assert_eq!(
            name.as_deref(),
            Some("文件.txt"),
            "GBK percent-encoded 中文 URL 应能被正确解码而不是保留原始 %XX"
        );
    }

    // -----------------------------------------------------------------------
    // extract_filename (integration of all strategies)
    // -----------------------------------------------------------------------

    #[test]
    fn extract_filename_prefers_content_disposition() {
        let headers = make_headers_with_cd("attachment; filename=\"from_header.zip\"");
        let name = name_of(
            &headers,
            "https://example.com/from_url.tar.gz",
            "https://example.com/from_url.tar.gz",
        );
        assert_eq!(name, "from_header.zip");
    }

    #[test]
    fn extract_filename_falls_back_to_url() {
        let headers = reqwest::header::HeaderMap::new();
        let name = name_of(
            &headers,
            "https://example.com/from_url.tar.gz",
            "https://example.com/from_url.tar.gz",
        );
        assert_eq!(name, "from_url.tar.gz");
    }

    #[test]
    fn extract_filename_prefers_original_url_extension_over_extensionless_redirect() {
        // Regression test for #223: GitHub's `archive/refs/tags/<tag>.zip`
        // redirects to codeload's `.../zip/refs/tags/<tag>`, which drops the
        // `.zip` suffix entirely. When the tag itself embeds a dot from a
        // version number ("proton-11.0-1b"), naively reading only the
        // post-redirect URL manufactures a bogus extension (".0-1b") instead
        // of the correct ".zip" from the original request URL.
        let headers = reqwest::header::HeaderMap::new();
        let name = name_of(
            &headers,
            "https://github.com/ValveSoftware/Proton/archive/refs/tags/proton-11.0-1b.zip",
            "https://codeload.github.com/ValveSoftware/Proton/zip/refs/tags/proton-11.0-1b",
        );
        assert_eq!(name, "proton-11.0-1b.zip");
    }

    #[test]
    fn extract_filename_falls_back_to_final_url_when_original_has_no_extension() {
        // A shortlink-style original URL has no real filename; the redirect
        // target does — still usable when Content-Disposition is absent.
        let headers = reqwest::header::HeaderMap::new();
        let name = name_of(
            &headers,
            "https://dl.example.com/abc123",
            "https://cdn.example.com/files/real-name.pdf",
        );
        assert_eq!(name, "real-name.pdf");
    }

    #[test]
    fn extract_filename_keeps_final_url_for_script_endpoint_redirect() {
        // Regression guard: a dynamic endpoint URL ("download.php") carries a
        // plausible extension itself, but the redirect target is the only URL
        // with the real filename. The pre-#224 behavior (trust the final URL)
        // must be preserved here — the request URL wins only when the final
        // segment lacks a real extension or is provably a truncation.
        let headers = reqwest::header::HeaderMap::new();
        let name = name_of(
            &headers,
            "https://example.com/download.php",
            "https://cdn.example.com/files/real-file.zip",
        );
        assert_eq!(name, "real-file.zip");
    }

    #[test]
    fn extract_filename_restores_extension_dropped_by_redirect_numeric_tag() {
        // Codeload pattern with a purely numeric pseudo-extension: the final
        // segment "v1.2" (ext "2" is 1 alnum char, hence "plausible") equals
        // the request segment minus ".zip" — the stem match must win.
        let headers = reqwest::header::HeaderMap::new();
        let name = name_of(
            &headers,
            "https://github.com/o/r/archive/refs/tags/v1.2.zip",
            "https://codeload.github.com/o/r/zip/refs/tags/v1.2",
        );
        assert_eq!(name, "v1.2.zip");
    }

    #[test]
    fn extract_filename_restores_extension_dropped_by_redirect_letter_suffix_tag() {
        // Tag whose pseudo-extension contains a letter ("0b") would pass the
        // plausibility check on the final URL; only the stem match catches it.
        let headers = reqwest::header::HeaderMap::new();
        let name = name_of(
            &headers,
            "https://github.com/o/r/archive/refs/tags/proton-1.0b.zip",
            "https://codeload.github.com/o/r/zip/refs/tags/proton-1.0b",
        );
        assert_eq!(name, "proton-1.0b.zip");
    }

    #[test]
    fn extract_filename_restores_multipart_extension_dropped_by_redirect() {
        // GitHub serves tarballs the same way: `archive/refs/tags/v1.2.tar.gz`
        // redirects to codeload's `tar.gz/refs/tags/v1.2`. The stem match must
        // strip the full multi-part extension, not just the last component —
        // otherwise the final segment "v1.2" wins via its plausible ext "2".
        let headers = reqwest::header::HeaderMap::new();
        let name = name_of(
            &headers,
            "https://github.com/o/r/archive/refs/tags/v1.2.tar.gz",
            "https://codeload.github.com/o/r/tar.gz/refs/tags/v1.2",
        );
        assert_eq!(name, "v1.2.tar.gz");
    }

    #[test]
    fn extract_filename_appends_content_type_extension_to_extensionless_url_name() {
        // #783: X.com 图片 URL 只在 query 里写格式，路径末段没有后缀。
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            reqwest::header::CONTENT_TYPE,
            reqwest::header::HeaderValue::from_static("image/jpeg"),
        );
        let url = "https://pbs.twimg.com/media/GxYzAbC?format=jpg&name=large";
        assert_eq!(name_of(&headers, url, url), "GxYzAbC.jpg");

        // 已有真实扩展名不动；HTML 不补（保留 HTML 安全网的判定依据）。
        let url = "https://example.com/files/report.pdf";
        assert_eq!(name_of(&headers, url, url), "report.pdf");
        headers.insert(
            reqwest::header::CONTENT_TYPE,
            reqwest::header::HeaderValue::from_static("text/html; charset=utf-8"),
        );
        let url = "https://example.com/d/abc123";
        assert_eq!(name_of(&headers, url, url), "abc123");
    }

    #[test]
    fn extract_filename_prefers_request_extension_when_final_has_none_and_no_stem_match() {
        // Branch (c) exclusively: the final segment exists but has no
        // plausible extension and is not a truncation of the request segment.
        let headers = reqwest::header::HeaderMap::new();
        let name = name_of(
            &headers,
            "https://example.com/pkg/setup.exe",
            "https://cdn.example.com/blob/8f3e-2b1",
        );
        assert_eq!(name, "setup.exe");
    }

    #[test]
    fn extract_filename_falls_back_to_final_when_neither_side_has_extension() {
        // Branch (d): neither segment looks like a filename → final URL wins,
        // matching the pre-redirect-aware fallback order.
        let headers = reqwest::header::HeaderMap::new();
        let name = name_of(
            &headers,
            "https://example.com/api/fetch",
            "https://cdn.example.com/blob/8f3e",
        );
        assert_eq!(name, "8f3e");
    }

    #[test]
    fn extract_filename_falls_back_to_mime() {
        let mut headers = reqwest::header::HeaderMap::new();
        if let Ok(v) = reqwest::header::HeaderValue::from_str("application/pdf") {
            headers.insert(reqwest::header::CONTENT_TYPE, v);
        }
        let name = name_of(&headers, "https://example.com/", "https://example.com/");
        assert_eq!(name, "download.pdf");
    }

    #[test]
    fn extract_filename_ultimate_fallback() {
        let headers = reqwest::header::HeaderMap::new();
        let name = name_of(&headers, "https://example.com/", "https://example.com/");
        assert_eq!(name, "download");
    }

    // === 新增 ===

    #[test]
    fn extract_filename_reports_source() {
        let h = make_headers_with_cd("attachment; filename=\"a.zip\"");
        let r = extract_filename(&h, "https://e.com/x.bin", "https://e.com/x.bin");
        assert_eq!(
            (r.name.as_str(), r.source),
            ("a.zip", NameSource::Disposition)
        );

        let none = reqwest::header::HeaderMap::new();
        let r = extract_filename(&none, "https://e.com/x.bin", "https://e.com/x.bin");
        assert_eq!((r.name.as_str(), r.source), ("x.bin", NameSource::UrlPath));

        let pdf = headers_with(&[("content-type", "application/pdf")]);
        let r = extract_filename(&pdf, "https://e.com/", "https://e.com/");
        assert_eq!(
            (r.name.as_str(), r.source),
            ("download.pdf", NameSource::ContentType)
        );

        let r = extract_filename(&none, "https://e.com/", "https://e.com/");
        assert_eq!(
            (r.name.as_str(), r.source),
            ("download", NameSource::Fallback)
        );
    }

    #[test]
    fn extract_filename_uses_final_url_host_for_legacy_bytes() {
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            reqwest::header::CONTENT_DISPOSITION,
            reqwest::header::HeaderValue::from_bytes(
                b"attachment; filename=\"\xbd\xb7\xbd\xb7.txt\"",
            )
            .unwrap(),
        );
        let r = extract_filename(&headers, "https://e.com/x", "https://cdn.example.co.kr/x");
        assert_eq!(
            r.name,
            decode_legacy_bytes(
                b"\xbd\xb7\xbd\xb7.txt",
                NameHints {
                    host: Some("cdn.example.co.kr"),
                    page_charset: None
                },
            )
        );
    }

    #[test]
    fn name_from_url_prefers_s3_response_content_disposition() {
        let url = "https://bucket.s3.amazonaws.com/key/abc123?response-content-disposition=attachment%3B%20filename%3D%22a%20b.zip%22&X-Amz-Signature=deadbeef";
        let r = name_from_url(url).unwrap();
        assert_eq!(r.name, "a b.zip");
        assert_eq!(r.source, NameSource::QueryDisposition);
    }

    #[test]
    fn name_from_url_reads_azure_rscd() {
        let url = "https://acct.blob.core.windows.net/c/blob?sv=2022&rscd=attachment%3B%20filename%3D%22report.pdf%22&sig=abc";
        let r = name_from_url(url).unwrap();
        assert_eq!(r.name, "report.pdf");
        assert_eq!(r.source, NameSource::QueryDisposition);
    }

    #[test]
    fn name_from_url_query_param_name_is_case_insensitive_and_supports_filename_star() {
        let url = "https://oss.example.com/k?Response-Content-Disposition=attachment%3Bfilename*%3DUTF-8%27%27%25E4%25B8%25AD%25E6%2596%2587.zip";
        let r = name_from_url(url).unwrap();
        assert_eq!(r.name, "中文.zip");
    }

    #[test]
    fn name_from_url_falls_back_to_path_segment() {
        let r = name_from_url("https://example.com/path/file.zip?v=1").unwrap();
        assert_eq!(r.name, "file.zip");
        assert_eq!(r.source, NameSource::UrlPath);
        assert!(name_from_url("https://example.com/path/").is_none());
        // 查询里的 CD 没有文件名 → 回落路径。
        let r = name_from_url("https://e.com/f.bin?rscd=inline").unwrap();
        assert_eq!(r.name, "f.bin");
    }

    #[test]
    fn extract_filename_query_disposition_on_final_url_beats_url_path() {
        let headers = reqwest::header::HeaderMap::new();
        let r = extract_filename(
            &headers,
            "https://api.example.com/download/123",
            "https://bucket.s3.amazonaws.com/k?response-content-disposition=attachment%3B%20filename%3D%22real.zip%22",
        );
        assert_eq!(r.name, "real.zip");
        assert_eq!(r.source, NameSource::QueryDisposition);
    }

    #[test]
    fn extract_filename_replaces_script_endpoint_extension_with_content_type() {
        let headers = headers_with(&[("content-type", "application/zip")]);
        let url = "https://example.com/download.php?id=1";
        assert_eq!(name_of(&headers, url, url), "download.zip");

        // HTML 类型证据不换。
        let html = headers_with(&[("content-type", "text/html; charset=utf-8")]);
        assert_eq!(name_of(&html, url, url), "download.php");

        // 没有类型证据不换。
        let none = reqwest::header::HeaderMap::new();
        assert_eq!(name_of(&none, url, url), "download.php");

        // 显式（非脚本）扩展名不动。
        let pdf_url = "https://example.com/files/report.pdf";
        assert_eq!(name_of(&headers, pdf_url, pdf_url), "report.pdf");
    }

    #[test]
    fn extract_from_url_uses_host_prior_for_legacy_segments() {
        // A4 A4 A4 E5 在 GBK / Big5 下都是常用字；.tw 先验下解为 Big5 “中文”。
        let name = extract_from_url("http://example.com.tw/%A4%A4%A4%E5.txt");
        assert_eq!(name.as_deref(), Some("中文.txt"));
    }

    #[test]
    fn extract_from_url_undecodable_bytes_still_yield_a_name() {
        // 0x81 0x7F 既非 UTF-8 也非合法 GBK/Big5/Shift_JIS：恒成功，不报错也不返回原串。
        let name = extract_from_url("http://example.com/%81%7F.txt").unwrap();
        assert_eq!(name, sanitize_filename("\u{81}\u{7f}.txt"));
    }

    fn refine(current: &str, evidence: NameEvidence<'_>) -> Option<String> {
        refine_inferred_name(current, &evidence)
    }

    #[test]
    fn refine_keeps_explicit_extension() {
        let ev = NameEvidence {
            disposition: None,
            mime: Some("application/zip"),
            sniffed_ext: Some("zip"),
        };
        assert_eq!(refine("data.bin", ev), None);
        assert_eq!(refine("a.zip", ev), None);
    }

    #[test]
    fn refine_disposition_wins() {
        let ev = NameEvidence {
            disposition: Some("真名.zip"),
            mime: Some("application/pdf"),
            sniffed_ext: Some("pdf"),
        };
        assert_eq!(refine("abc", ev).as_deref(), Some("真名.zip"));
        assert_eq!(refine("真名.zip", ev), None);
        let ev = NameEvidence {
            disposition: Some("a/b.zip"),
            ..NameEvidence::default()
        };
        assert_eq!(refine("x", ev).as_deref(), Some("a_b.zip"));
    }

    #[test]
    fn refine_replaces_script_endpoint() {
        let ev = NameEvidence {
            disposition: None,
            mime: Some("application/zip"),
            sniffed_ext: None,
        };
        assert_eq!(refine("download.php", ev).as_deref(), Some("download.zip"));
        let html = NameEvidence {
            mime: Some("text/html"),
            ..NameEvidence::default()
        };
        assert_eq!(refine("download.php", html), None);
        let sniffed = NameEvidence {
            mime: Some("text/html"),
            sniffed_ext: Some("rar"),
            ..NameEvidence::default()
        };
        assert_eq!(refine("page.php", sniffed).as_deref(), Some("page.rar"));
    }

    #[test]
    fn refine_appends_missing_extension() {
        let mime = NameEvidence {
            mime: Some("application/pdf"),
            ..NameEvidence::default()
        };
        assert_eq!(refine("abc123", mime).as_deref(), Some("abc123.pdf"));
        let sniffed = NameEvidence {
            mime: Some("application/octet-stream"),
            sniffed_ext: Some("7z"),
            ..NameEvidence::default()
        };
        assert_eq!(refine("blob", sniffed).as_deref(), Some("blob.7z"));
        // 魔数优先于 MIME。
        let both = NameEvidence {
            mime: Some("application/zip"),
            sniffed_ext: Some("docx"),
            ..NameEvidence::default()
        };
        assert_eq!(refine("report", both).as_deref(), Some("report.docx"));
        // 无证据 / 泛化类型 / HTML 都不动。
        assert_eq!(refine("abc", NameEvidence::default()), None);
        let generic = NameEvidence {
            mime: Some("application/octet-stream"),
            ..NameEvidence::default()
        };
        assert_eq!(refine("abc", generic), None);
        let html = NameEvidence {
            mime: Some("text/html"),
            ..NameEvidence::default()
        };
        assert_eq!(refine("abc", html), None);
    }

    #[test]
    fn refine_completes_extensionless_disposition_and_prefers_specific_zip_container_mime() {
        // 服务器给了权威名但没有扩展名：仍按类型证据补全。
        let cd = NameEvidence {
            disposition: Some("report"),
            mime: Some("application/octet-stream"),
            sniffed_ext: Some("pdf"),
        };
        assert_eq!(refine("x.php", cd).as_deref(), Some("report.pdf"));
        // 魔数只认出泛化 zip 时，更具体的 OOXML Content-Type 胜出；其余情况魔数胜出。
        let docx = NameEvidence {
            mime: Some("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            sniffed_ext: Some("zip"),
            ..NameEvidence::default()
        };
        assert_eq!(refine("blob", docx).as_deref(), Some("blob.docx"));
        let mislabeled = NameEvidence {
            mime: Some("image/jpeg"),
            sniffed_ext: Some("png"),
            ..NameEvidence::default()
        };
        assert_eq!(refine("img", mislabeled).as_deref(), Some("img.png"));
    }
}
