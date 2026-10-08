//! 文件名清洗：路径分隔符 / Windows 非法字符 / 保留设备名 / 双向控制符 / 长度。

/// 文件名单组件的最大字节数（F051）。
///
/// 大多数文件系统（ext4/APFS/NTFS）的单路径组件上限为 255 字节；这里取 200
/// 作为保守预算，给 `.fdownloading` 临时后缀（13 字节）及未来可能的 dedup
/// `" (NN)"` 后缀留出余量。超长的 Content-Disposition / URL 段若原样放行，
/// `save_dir.join(name) + ".fdownloading"` 会触顶导致 create 报 ENAMETOOLONG，
/// 下载以晦涩 OS 错误失败。多字节 CJK 约 66 字即可触及 200 字节。
const MAX_FILENAME_BYTES: usize = 200;

/// Windows 保留设备名（不区分大小写，比较时取扩展名前的 stem）。
///
/// 在 Windows 上创建这些名字（无论是否带扩展名，如 `CON`、`NUL.txt`）会失败
/// 或行为异常。本项目主要目标平台为 Windows，故统一在文件名出口处规避。
const WINDOWS_RESERVED_NAMES: &[&str] = &[
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
    "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// Remove or replace characters that are illegal in file names on Windows/macOS/Linux.
///
/// 额外保证（F051）：
///   - 规避 Windows 保留设备名（CON/PRN/AUX/NUL/COM1-9/LPT1-9）——在 stem 前
///     加下划线；
///   - 双向控制符（U+061C、U+200E/F、U+202A–202E、U+2066–2069）替换为 `_`，U+FEFF / U+200B 删除；
///   - 把结果按字节截断到 [`MAX_FILENAME_BYTES`]，截断在 char 边界进行，避免
///     切断多字节 CJK 字符。
pub fn sanitize_filename(name: &str) -> String {
    let s: String = name
        .chars()
        .filter_map(|c| match c {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => Some('_'),
            c if c.is_control() => Some('_'),
            // 双向控制符会让 `invoice\u{202E}fdp.exe` 在界面上显示成 `invoiceexe.pdf`。
            '\u{061C}'
            | '\u{200E}'
            | '\u{200F}'
            | '\u{202A}'..='\u{202E}'
            | '\u{2066}'..='\u{2069}' => Some('_'),
            // BOM / 零宽空格不可见，只会制造「看起来相同」的不同文件名；ZWJ 保留（emoji 序列）。
            '\u{FEFF}' | '\u{200B}' => None,
            c => Some(c),
        })
        .collect();
    let s = s.trim_matches(|c: char| c == '.' || c == ' ');
    if s.is_empty() {
        return "download".to_string();
    }

    // --- F051(1): Windows 保留设备名规避 ---
    // 取扩展名前的 stem（首个 '.' 之前的部分）做大小写无关比较。
    let stem_end = s.find('.').unwrap_or(s.len());
    let stem = &s[..stem_end];
    let s = if WINDOWS_RESERVED_NAMES
        .iter()
        .any(|r| stem.eq_ignore_ascii_case(r))
    {
        format!("_{}", s)
    } else {
        s.to_string()
    };

    // --- F051(2): 字节长度截断（在 char 边界） ---
    if s.len() <= MAX_FILENAME_BYTES {
        return s;
    }
    // 保留扩展名（最后一个 '.' 起的部分），从 stem 尾部按 char 边界裁剪。
    let ext_start = s.rfind('.').unwrap_or(s.len());
    let (stem, ext) = s.split_at(ext_start);
    let budget = MAX_FILENAME_BYTES.saturating_sub(ext.len());
    // 找到 <= budget 的最大 char 边界。
    let cut = stem
        .char_indices()
        .map(|(i, _)| i)
        .take_while(|&i| i <= budget)
        .last()
        .unwrap_or(0);
    let truncated = format!("{}{}", &stem[..cut], ext);
    // 截断后再次 trim 尾部 '.'/' '（避免裁出以点/空格结尾的名）；若整体为空则兜底。
    let truncated = truncated.trim_matches(|c: char| c == '.' || c == ' ');
    if truncated.is_empty() {
        "download".to_string()
    } else {
        truncated.to_string()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::sanitize_filename;

    // -----------------------------------------------------------------------
    // sanitize_filename
    // -----------------------------------------------------------------------

    #[test]
    fn sanitize_replaces_illegal_chars() {
        assert_eq!(sanitize_filename("file<1>:2.txt"), "file_1__2.txt");
    }

    #[test]
    fn sanitize_replaces_all_special_chars() {
        assert_eq!(
            sanitize_filename(r#"a<b>c:d"e/f\g|h?i*j"#),
            "a_b_c_d_e_f_g_h_i_j"
        );
    }

    #[test]
    fn sanitize_strips_leading_trailing_dots_and_spaces() {
        assert_eq!(sanitize_filename("...file..."), "file");
        assert_eq!(sanitize_filename("  file  "), "file");
        assert_eq!(sanitize_filename("..file.."), "file");
    }

    #[test]
    fn sanitize_empty_and_only_dots() {
        assert_eq!(sanitize_filename(""), "download");
        assert_eq!(sanitize_filename("..."), "download");
        assert_eq!(sanitize_filename("   "), "download");
    }

    #[test]
    fn sanitize_control_characters() {
        assert_eq!(sanitize_filename("file\x00name\x1F.txt"), "file_name_.txt");
    }

    #[test]
    fn sanitize_blocks_path_traversal() {
        // 安全回归：用户/API 显式提供的 file_name（RPC `out` / 管理 API file_name /
        // 浏览器接管）经 sanitize_filename 后必须不含路径分隔符，且不是绝对路径，
        // 否则 save_dir.join(name) 会穿越 save_dir 落盘任意路径。
        for evil in [
            "../../../etc/passwd",
            "..\\..\\Windows\\System32\\evil.exe",
            "/etc/passwd",
            "C:\\Windows\\evil.exe",
            "foo/../bar",
        ] {
            let safe = sanitize_filename(evil);
            let p = std::path::Path::new(&safe);
            assert!(
                !safe.contains('/') && !safe.contains('\\'),
                "sanitized {evil:?} → {safe:?} still contains a path separator"
            );
            assert!(
                !p.is_absolute(),
                "sanitized {evil:?} → {safe:?} is still an absolute path"
            );
            assert!(
                p.components().count() == 1,
                "sanitized {evil:?} → {safe:?} resolves to multiple path components"
            );
        }
    }

    #[test]
    fn sanitize_preserves_unicode() {
        assert_eq!(sanitize_filename("文件下载.zip"), "文件下载.zip");
        assert_eq!(sanitize_filename("ファイル.tar.gz"), "ファイル.tar.gz");
    }

    #[test]
    fn sanitize_windows_reserved_names() {
        // F051: Windows 保留设备名（含/不含扩展名、混合大小写）应加下划线规避。
        assert_eq!(sanitize_filename("CON"), "_CON");
        assert_eq!(sanitize_filename("NUL.txt"), "_NUL.txt");
        assert_eq!(sanitize_filename("com1"), "_com1");
        assert_eq!(sanitize_filename("LpT9.log"), "_LpT9.log");
        assert_eq!(sanitize_filename("Aux.tar.gz"), "_Aux.tar.gz");
        // 非保留名不受影响（仅 stem 完全匹配才规避）。
        assert_eq!(sanitize_filename("CONSOLE.txt"), "CONSOLE.txt");
        assert_eq!(sanitize_filename("COM10.txt"), "COM10.txt");
    }

    #[test]
    fn sanitize_truncates_overlong_names_at_char_boundary() {
        // F051: 超过 200 字节的名字应截断，且保留扩展名、不切断多字节字符。
        let long_ascii = format!("{}.bin", "a".repeat(300));
        let out = sanitize_filename(&long_ascii);
        assert!(out.len() <= 200, "ascii truncated len = {}", out.len());
        assert!(out.ends_with(".bin"), "extension preserved: {out}");

        // 多字节 CJK：每个 '永' 3 字节，120 个 = 360 字节。
        let long_cjk = format!("{}.mp4", "永".repeat(120));
        let out = sanitize_filename(&long_cjk);
        assert!(out.len() <= 200, "cjk truncated len = {}", out.len());
        assert!(out.ends_with(".mp4"), "extension preserved: {out}");
        // 截断必须落在 char 边界——能成功重新解析为合法 UTF-8（String 本身保证）。
        assert!(out.starts_with('永'));

        // 未超限的名字原样返回。
        assert_eq!(sanitize_filename("short.txt"), "short.txt");
    }

    #[test]
    fn sanitize_replaces_bidi_controls() {
        // 防 `invoice\u{202E}fdp.exe` 在界面上伪装成 `invoiceexe.pdf`。
        assert_eq!(
            sanitize_filename("invoice\u{202E}fdp.exe"),
            "invoice_fdp.exe"
        );
        for c in [
            '\u{061C}', '\u{200E}', '\u{200F}', '\u{202A}', '\u{202B}', '\u{202C}', '\u{202D}',
            '\u{202E}', '\u{2066}', '\u{2067}', '\u{2068}', '\u{2069}',
        ] {
            assert_eq!(
                sanitize_filename(&format!("a{c}b.txt")),
                "a_b.txt",
                "U+{:04X}",
                c as u32
            );
        }
    }

    #[test]
    fn sanitize_removes_invisible_and_keeps_zwj() {
        assert_eq!(sanitize_filename("\u{FEFF}a\u{200B}b.txt"), "ab.txt");
        // ZWJ 是 emoji 序列的组成部分，必须保留。
        let family = "\u{1F468}\u{200D}\u{1F469}.png";
        assert_eq!(sanitize_filename(family), family);
    }
}
