//! 文件头魔数 → 扩展名。只在名字缺扩展名（或是脚本端点名）时作为类型证据使用，
//! 所以只收录魔数明确、误判代价低的格式。

/// 嗅探窗口上限：调用方最多传入这么多字节。
const MAX_HEAD: usize = 4096;

/// 按文件头魔数推断扩展名（不含点）；认不出返回 `None`。`head` 超过 4096 字节的部分忽略。
pub fn sniff_extension(head: &[u8]) -> Option<&'static str> {
    let head = &head[..head.len().min(MAX_HEAD)];

    if head.starts_with(b"PK\x03\x04") {
        return Some(zip_flavor(head));
    }
    // 空 zip / 分卷 zip 的头。
    if head.starts_with(b"PK\x05\x06") || head.starts_with(b"PK\x07\x08") {
        return Some("zip");
    }
    if head.starts_with(b"Rar!\x1A\x07") {
        return Some("rar");
    }
    if head.starts_with(&[0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C]) {
        return Some("7z");
    }
    if head.starts_with(&[0x1F, 0x8B]) {
        return Some("gz");
    }
    if head.len() >= 4 && head.starts_with(b"BZh") && (b'1'..=b'9').contains(&head[3]) {
        return Some("bz2");
    }
    if head.starts_with(&[0xFD, b'7', b'z', b'X', b'Z', 0x00]) {
        return Some("xz");
    }
    if head.starts_with(&[0x28, 0xB5, 0x2F, 0xFD]) {
        return Some("zst");
    }
    if head.starts_with(b"%PDF-") {
        return Some("pdf");
    }
    if head.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) {
        return Some("png");
    }
    if head.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Some("jpg");
    }
    if head.starts_with(b"GIF87a") || head.starts_with(b"GIF89a") {
        return Some("gif");
    }
    if head.len() >= 12 && head.starts_with(b"RIFF") {
        match &head[8..12] {
            b"WEBP" => return Some("webp"),
            b"WAVE" => return Some("wav"),
            b"AVI " => return Some("avi"),
            _ => {}
        }
    }
    if head.len() >= 12 && &head[4..8] == b"ftyp" {
        return Some(ftyp_flavor(&head[8..12]));
    }
    if head.starts_with(&[0x1A, 0x45, 0xDF, 0xA3]) {
        // EBML 头里的 DocType 在最前面几十字节内。
        let window = &head[..head.len().min(64)];
        return Some(if window.windows(4).any(|w| w == b"webm") {
            "webm"
        } else {
            "mkv"
        });
    }
    if head.starts_with(b"fLaC") {
        return Some("flac");
    }
    if head.starts_with(b"OggS") {
        return Some("ogg");
    }
    if head.starts_with(b"ID3") {
        return Some("mp3");
    }
    if is_pe_executable(head) {
        return Some("exe");
    }
    if head.starts_with(b"!<arch>\ndebian-binary") {
        return Some("deb");
    }
    if head.starts_with(&[0xED, 0xAB, 0xEE, 0xDB]) {
        return Some("rpm");
    }
    if head.starts_with(&[0x00, b'a', b's', b'm', 0x01, 0x00, 0x00, 0x00]) {
        return Some("wasm");
    }
    if is_torrent(head) {
        return Some("torrent");
    }
    if head.len() >= 262 && &head[257..262] == b"ustar" {
        return Some("tar");
    }
    if head.starts_with(b"wOFF") {
        return Some("woff");
    }
    if head.starts_with(b"wOF2") {
        return Some("woff2");
    }
    if head.starts_with(b"OTTO") {
        return Some("otf");
    }
    if head.starts_with(b"MSCF") {
        return Some("cab");
    }
    if head.starts_with(b"FLV\x01") {
        return Some("flv");
    }
    if is_mpeg_ts(head) {
        return Some("ts");
    }
    None
}

/// zip 容器细分：epub（首项 `mimetype` 内容为 `application/epub+zip`）、docx / xlsx / pptx
/// （带 `[Content_Types].xml` 且本地文件头里出现 `word/`、`xl/`、`ppt/`）、apk（含
/// `AndroidManifest.xml`），其余都是 zip。
fn zip_flavor(head: &[u8]) -> &'static str {
    if head.len() >= 30 {
        let name_len = usize::from(u16::from_le_bytes([head[26], head[27]]));
        let extra_len = usize::from(u16::from_le_bytes([head[28], head[29]]));
        if head.get(30..30 + name_len) == Some(b"mimetype") {
            let data_start = 30 + name_len + extra_len;
            if head
                .get(data_start..)
                .is_some_and(|data| data.starts_with(b"application/epub+zip"))
            {
                return "epub";
            }
        }
    }
    if contains(head, b"[Content_Types].xml") {
        if contains(head, b"word/") {
            return "docx";
        }
        if contains(head, b"xl/") {
            return "xlsx";
        }
        if contains(head, b"ppt/") {
            return "pptx";
        }
    }
    if contains(head, b"AndroidManifest.xml") {
        return "apk";
    }
    "zip"
}

/// ISO BMFF `ftyp` 的 major brand → 扩展名；未知 brand 按 mp4。
fn ftyp_flavor(brand: &[u8]) -> &'static str {
    match brand {
        b"M4A " | b"M4B " => "m4a",
        b"qt  " => "mov",
        b"heic" | b"heix" | b"hevc" | b"hevx" | b"heim" | b"heis" => "heic",
        b"avif" | b"avis" => "avif",
        b if b.starts_with(b"3gp") || b.starts_with(b"3g2") => "3gp",
        _ => "mp4",
    }
}

/// `MZ` + `e_lfanew`（偏移 0x3C，小端）指向 `PE\0\0`。
fn is_pe_executable(head: &[u8]) -> bool {
    if !head.starts_with(b"MZ") || head.len() < 0x40 {
        return false;
    }
    let offset = u32::from_le_bytes([head[0x3C], head[0x3D], head[0x3E], head[0x3F]]);
    usize::try_from(offset)
        .ok()
        .and_then(|offset| head.get(offset..offset.checked_add(4)?))
        .is_some_and(|sig| sig == b"PE\0\0")
}

/// bencode 字典开头的常见 `.torrent` 顶层键。
fn is_torrent(head: &[u8]) -> bool {
    const PREFIXES: &[&[u8]] = &[
        b"d8:announce",
        b"d13:announce-list",
        b"d4:info",
        b"d10:created by",
        b"d7:comment",
        b"d13:creation date",
        b"d8:encoding",
    ];
    PREFIXES.iter().any(|prefix| head.starts_with(prefix))
}

/// MPEG-TS：同步字节 `0x47` 每 188 字节重复，检查 0 / 188 / 376 三处。
fn is_mpeg_ts(head: &[u8]) -> bool {
    head.len() > 376 && [0usize, 188, 376].iter().all(|&at| head[at] == 0x47)
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.windows(needle.len()).any(|w| w == needle)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::sniff_extension;

    /// 构造一个只有一个本地文件头的 zip 头：`name` 为首项名，`data` 紧随其后。
    fn zip_head(name: &str, data: &[u8], rest: &[u8]) -> Vec<u8> {
        let mut head = b"PK\x03\x04".to_vec();
        // 版本(2) + 标志(2) + 压缩方法(2) + 时间(2) + 日期(2) + crc(4) + 两个大小(8) = 22 字节。
        head.extend_from_slice(&[20, 0]);
        head.extend_from_slice(&[0u8; 20]);
        head.extend_from_slice(&u16::try_from(name.len()).unwrap().to_le_bytes());
        head.extend_from_slice(&0u16.to_le_bytes());
        head.extend_from_slice(name.as_bytes());
        head.extend_from_slice(data);
        head.extend_from_slice(rest);
        head
    }

    fn with_prefix(prefix: &[u8], total: usize) -> Vec<u8> {
        let mut v = prefix.to_vec();
        v.resize(total.max(prefix.len()), 0);
        v
    }

    #[test]
    fn sniff_zip_and_office_flavors() {
        assert_eq!(sniff_extension(&zip_head("a.txt", b"hi", b"")), Some("zip"));
        assert_eq!(sniff_extension(b"PK\x05\x06\0\0\0\0"), Some("zip"));
        assert_eq!(
            sniff_extension(&zip_head(
                "mimetype",
                b"application/epub+zip",
                b"PK\x03\x04META-INF/container.xml"
            )),
            Some("epub")
        );
        assert_eq!(
            sniff_extension(&zip_head(
                "[Content_Types].xml",
                b"",
                b"PK\x03\x04word/document.xml"
            )),
            Some("docx")
        );
        assert_eq!(
            sniff_extension(&zip_head(
                "[Content_Types].xml",
                b"",
                b"PK\x03\x04xl/workbook.xml"
            )),
            Some("xlsx")
        );
        assert_eq!(
            sniff_extension(&zip_head(
                "[Content_Types].xml",
                b"",
                b"PK\x03\x04ppt/presentation.xml"
            )),
            Some("pptx")
        );
        assert_eq!(
            sniff_extension(&zip_head("AndroidManifest.xml", b"", b"")),
            Some("apk")
        );
    }

    #[test]
    fn sniff_archives_and_compression() {
        assert_eq!(sniff_extension(b"Rar!\x1A\x07\x00abc"), Some("rar"));
        assert_eq!(
            sniff_extension(&[0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0, 4]),
            Some("7z")
        );
        assert_eq!(sniff_extension(&[0x1F, 0x8B, 8, 0]), Some("gz"));
        assert_eq!(sniff_extension(b"BZh91AY&SY"), Some("bz2"));
        assert_eq!(
            sniff_extension(&[0xFD, b'7', b'z', b'X', b'Z', 0, 0]),
            Some("xz")
        );
        assert_eq!(sniff_extension(&[0x28, 0xB5, 0x2F, 0xFD, 0]), Some("zst"));
        assert_eq!(
            sniff_extension(b"!<arch>\ndebian-binary   2.0"),
            Some("deb")
        );
        assert_eq!(
            sniff_extension(&[0xED, 0xAB, 0xEE, 0xDB, 3, 0]),
            Some("rpm")
        );
        assert_eq!(sniff_extension(b"MSCF\0\0\0\0"), Some("cab"));
        let mut tar = vec![0u8; 512];
        tar[257..262].copy_from_slice(b"ustar");
        assert_eq!(sniff_extension(&tar), Some("tar"));
    }

    #[test]
    fn sniff_documents_and_images() {
        assert_eq!(sniff_extension(b"%PDF-1.7\n"), Some("pdf"));
        assert_eq!(
            sniff_extension(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0]),
            Some("png")
        );
        assert_eq!(sniff_extension(&[0xFF, 0xD8, 0xFF, 0xE0]), Some("jpg"));
        assert_eq!(sniff_extension(b"GIF89a\x01\x00"), Some("gif"));
        assert_eq!(sniff_extension(b"GIF87a\x01\x00"), Some("gif"));
        assert_eq!(sniff_extension(b"RIFF\x24\0\0\0WEBPVP8 "), Some("webp"));
    }

    #[test]
    fn sniff_audio_video() {
        assert_eq!(sniff_extension(b"RIFF\x24\0\0\0WAVEfmt "), Some("wav"));
        assert_eq!(sniff_extension(b"RIFF\x24\0\0\0AVI LIST"), Some("avi"));
        assert_eq!(sniff_extension(b"\0\0\0\x18ftypisom\0\0\0\0"), Some("mp4"));
        assert_eq!(sniff_extension(b"\0\0\0\x18ftypM4A \0\0\0\0"), Some("m4a"));
        assert_eq!(sniff_extension(b"\0\0\0\x14ftypqt  \0\0\0\0"), Some("mov"));
        assert_eq!(sniff_extension(b"\0\0\0\x18ftypheic\0\0\0\0"), Some("heic"));
        assert_eq!(sniff_extension(b"\0\0\0\x18ftypavif\0\0\0\0"), Some("avif"));
        assert_eq!(sniff_extension(b"\0\0\0\x14ftyp3gp4\0\0\0\0"), Some("3gp"));
        let mut mkv = vec![0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x88];
        mkv.extend_from_slice(b"matroska");
        assert_eq!(sniff_extension(&mkv), Some("mkv"));
        let mut webm = vec![0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x84];
        webm.extend_from_slice(b"webm");
        assert_eq!(sniff_extension(&webm), Some("webm"));
        assert_eq!(sniff_extension(b"fLaC\0\0\0\x22"), Some("flac"));
        assert_eq!(sniff_extension(b"OggS\0\x02"), Some("ogg"));
        assert_eq!(sniff_extension(b"ID3\x04\0\0"), Some("mp3"));
        assert_eq!(sniff_extension(b"FLV\x01\x05"), Some("flv"));
        let mut ts = vec![0u8; 400];
        for at in [0usize, 188, 376] {
            ts[at] = 0x47;
        }
        assert_eq!(sniff_extension(&ts), Some("ts"));
    }

    #[test]
    fn sniff_binaries_and_misc() {
        let mut exe = with_prefix(b"MZ", 0x100);
        exe[0x3C] = 0x80;
        exe[0x80..0x84].copy_from_slice(b"PE\0\0");
        assert_eq!(sniff_extension(&exe), Some("exe"));
        assert_eq!(
            sniff_extension(&[0x00, b'a', b's', b'm', 1, 0, 0, 0]),
            Some("wasm")
        );
        assert_eq!(
            sniff_extension(b"d8:announce35:http://tracker.example/announce4:infod"),
            Some("torrent")
        );
        assert_eq!(sniff_extension(b"wOFF\0\x01\0\0"), Some("woff"));
        assert_eq!(sniff_extension(b"wOF2\0\x01\0\0"), Some("woff2"));
        assert_eq!(sniff_extension(b"OTTO\0\x0A"), Some("otf"));
    }

    #[test]
    fn sniff_rejects_non_magic() {
        assert_eq!(sniff_extension(b""), None);
        assert_eq!(sniff_extension(b"hello world, this is plain text"), None);
        assert_eq!(sniff_extension(b"<!DOCTYPE html><html></html>"), None);
        // 只有 MZ 而 e_lfanew 不指向 PE 签名：不是 exe。
        assert_eq!(sniff_extension(&with_prefix(b"MZ", 0x100)), None);
        // 只有一个 0x47：不是 TS。
        let mut one = vec![0u8; 400];
        one[0] = 0x47;
        assert_eq!(sniff_extension(&one), None);
    }
}
