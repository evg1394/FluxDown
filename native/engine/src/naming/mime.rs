//! MIME ↔ 扩展名：`Content-Type` 到扩展名的映射，以及「名字是否已有像样的扩展名」
//! 「是否脚本端点名」两个判定。

/// MIME type → common extension mapping for when there is no filename.
pub fn mime_to_ext(content_type: &str) -> Option<&'static str> {
    let ct = content_type.split(';').next().unwrap_or("").trim();
    match ct {
        "application/pdf" => Some("pdf"),
        "application/zip" => Some("zip"),
        "application/x-gzip" | "application/gzip" => Some("gz"),
        "application/x-tar" => Some("tar"),
        "application/x-bzip2" => Some("bz2"),
        "application/x-xz" => Some("xz"),
        "application/x-7z-compressed" => Some("7z"),
        "application/x-rar-compressed" | "application/vnd.rar" => Some("rar"),
        "application/json" => Some("json"),
        "application/xml" | "text/xml" => Some("xml"),
        "application/javascript" | "text/javascript" => Some("js"),
        "application/wasm" => Some("wasm"),
        "application/octet-stream" => None, // generic binary
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" => Some("xlsx"),
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" => Some("docx"),
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" => Some("pptx"),
        "application/msword" => Some("doc"),
        "application/vnd.ms-excel" => Some("xls"),
        "application/vnd.ms-powerpoint" => Some("ppt"),
        "application/x-iso9660-image" => Some("iso"),
        "application/x-msdownload" | "application/x-dosexec" => Some("exe"),
        "application/vnd.android.package-archive" => Some("apk"),
        "application/java-archive" => Some("jar"),
        "application/x-shockwave-flash" => Some("swf"),
        "application/x-debian-package" => Some("deb"),
        "application/x-rpm" => Some("rpm"),
        "application/x-msi" => Some("msi"),
        "application/vnd.apple.installer+xml" => Some("pkg"),
        "text/html" => Some("html"),
        "text/css" => Some("css"),
        "text/csv" => Some("csv"),
        "text/plain" => Some("txt"),
        "image/jpeg" => Some("jpg"),
        "image/png" => Some("png"),
        "image/gif" => Some("gif"),
        "image/webp" => Some("webp"),
        "image/svg+xml" => Some("svg"),
        "image/bmp" => Some("bmp"),
        "image/x-icon" | "image/vnd.microsoft.icon" => Some("ico"),
        "image/tiff" => Some("tiff"),
        "image/avif" => Some("avif"),
        "audio/mpeg" => Some("mp3"),
        "audio/ogg" => Some("ogg"),
        "audio/wav" | "audio/x-wav" => Some("wav"),
        "audio/flac" => Some("flac"),
        "audio/aac" => Some("aac"),
        "audio/mp4" | "audio/x-m4a" => Some("m4a"),
        "audio/webm" => Some("weba"),
        "video/mp4" => Some("mp4"),
        "video/webm" => Some("webm"),
        "video/x-matroska" => Some("mkv"),
        "video/x-msvideo" => Some("avi"),
        "video/quicktime" => Some("mov"),
        "video/x-flv" => Some("flv"),
        "video/mp2t" => Some("ts"),
        "video/3gpp" => Some("3gp"),
        "font/woff" => Some("woff"),
        "font/woff2" => Some("woff2"),
        "font/ttf" | "application/x-font-ttf" => Some("ttf"),
        "font/otf" => Some("otf"),
        _ => None,
    }
}

/// `name` 末尾的「扩展名」（最后一个 `.` 之后）是否像真扩展名：1–10 位 ASCII 字母数字。
///
/// 用来在两个候选 URL 末段之间偏向带真扩展名的那个，避免路径段里的版本号小数点
/// （GitHub codeload 重定向目标 `proton-11.0-1b`）被当成扩展名。
pub fn has_plausible_extension(name: &str) -> bool {
    match name.rfind('.') {
        Some(pos) if pos + 1 < name.len() => {
            let ext = &name[pos + 1..];
            (1..=10).contains(&ext.len()) && ext.chars().all(|c| c.is_ascii_alphanumeric())
        }
        _ => false,
    }
}

/// 动态页面 / 脚本端点常用的扩展名（小写）。这类名字描述的是「怎么生成内容」，而不是
/// 内容本身（`download.php` 实际返回 zip），故可被类型证据替换。
const SCRIPT_ENDPOINT_EXTENSIONS: &[&str] = &[
    "php", "asp", "aspx", "jsp", "jspx", "cgi", "pl", "do", "action", "html", "htm", "shtml",
];

/// 名字的扩展名（不区分大小写）是否属于脚本端点扩展名。
pub fn is_script_endpoint_name(name: &str) -> bool {
    match name.rfind('.') {
        Some(pos) => {
            let ext = &name[pos + 1..];
            SCRIPT_ENDPOINT_EXTENSIONS
                .iter()
                .any(|s| ext.eq_ignore_ascii_case(s))
        }
        None => false,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::{has_plausible_extension, is_script_endpoint_name, mime_to_ext};

    #[test]
    fn mime_to_ext_common_types() {
        assert_eq!(mime_to_ext("application/pdf"), Some("pdf"));
        assert_eq!(mime_to_ext("application/zip"), Some("zip"));
        assert_eq!(mime_to_ext("video/mp4"), Some("mp4"));
        assert_eq!(mime_to_ext("image/jpeg"), Some("jpg"));
    }

    #[test]
    fn mime_to_ext_with_charset_parameter() {
        // MIME type often comes with ";charset=utf-8"
        assert_eq!(mime_to_ext("text/html; charset=utf-8"), Some("html"));
    }

    #[test]
    fn mime_to_ext_unknown_and_generic_types() {
        assert_eq!(mime_to_ext("application/x-unknown-format"), None);
        assert_eq!(mime_to_ext("application/octet-stream"), None);
        assert_eq!(mime_to_ext("application/xhtml+xml"), None);
    }

    #[test]
    fn plausible_extension_rules() {
        assert!(has_plausible_extension("a.zip"));
        assert!(has_plausible_extension("a.tar.gz"));
        assert!(has_plausible_extension("v1.2"));
        assert!(!has_plausible_extension("abc"));
        assert!(!has_plausible_extension("abc."));
        assert!(!has_plausible_extension("a.中文"));
        assert!(!has_plausible_extension("a.verylongextension"));
    }

    #[test]
    fn script_endpoint_names() {
        for name in [
            "download.php",
            "get.ASP",
            "x.aspx",
            "a.jsp",
            "a.jspx",
            "a.cgi",
            "a.pl",
            "a.do",
            "a.action",
            "a.html",
            "a.HTM",
            "a.shtml",
        ] {
            assert!(is_script_endpoint_name(name), "{name}");
        }
        for name in ["a.zip", "a.php.zip", "download", "php", ""] {
            assert!(!is_script_endpoint_name(name), "{name}");
        }
    }
}
