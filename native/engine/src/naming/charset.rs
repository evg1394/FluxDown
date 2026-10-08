//! 未声明字符集的字节串解码（旧式文件名）与百分号解码。
//!
//! 现实里大量 HTTP 服务器、FTP/ed2k/magnet 链接直接发 GBK / Big5 / Shift_JIS / EUC-KR /
//! windows-1252 字节而不声明字符集。这里按「严格 UTF-8 → 页面字符集 → `.kr` 的
//! EUC-KR → 候选打分」的固定顺序挑选解码，**结果恒成功**。打分规则见 [`score_gbk`] 等，
//! 扩展端 `fluxDown/utils/filename.ts` 与本文件逐条一致。

use encoding_rs::{
    BIG5, Encoding, GB18030, GBK, SHIFT_JIS, UTF_8, UTF_16BE, UTF_16LE, WINDOWS_1252,
};

use super::NameHints;

/// 单个十六进制 ASCII 字节 → 半字节；非十六进制返回 `None`。
pub(crate) fn hex_nibble(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

/// `input` 中是否含至少一个合法 `%XX` 转义。
pub(crate) fn has_percent_escape(input: &[u8]) -> bool {
    input
        .windows(3)
        .any(|w| w[0] == b'%' && hex_nibble(w[1]).is_some() && hex_nibble(w[2]).is_some())
}

/// 百分号解码为原始字节：合法 `%XX` 展开，其余字节（含非法 `%`、字面 `+`、字面非 ASCII
/// 字节）原样保留。
///
/// 全程按字节处理，绝不对 `&str` 切片：`%` 后紧跟多字节 UTF-8 字符时不会落到非字符
/// 边界。`+` 不是空格——路径段、Content-Disposition、RFC 8187 里它都是字面加号。
pub(crate) fn percent_decode_bytes(input: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(input.len());
    let mut i = 0;
    while i < input.len() {
        if input[i] == b'%'
            && let Some(&hi) = input.get(i + 1)
            && let Some(&lo) = input.get(i + 2)
            && let (Some(hi), Some(lo)) = (hex_nibble(hi), hex_nibble(lo))
        {
            out.push((hi << 4) | lo);
            i += 3;
            continue;
        }
        out.push(input[i]);
        i += 1;
    }
    out
}

/// 严格解码：遇到非法序列返回 `None`，不做 BOM 嗅探、不做替换。
fn strict_decode(bytes: &[u8], encoding: &'static Encoding) -> Option<String> {
    encoding
        .decode_without_bom_handling_and_without_replacement(bytes)
        .map(std::borrow::Cow::into_owned)
}

/// 按 WHATWG 标签严格解码（RFC 2047 encoded-word 声明的字符集）。
///
/// 未知标签、或字节不满足该字符集（含声明 UTF-8 但字节非法）→ `None`。
pub(crate) fn decode_with_label(bytes: &[u8], label: &[u8]) -> Option<String> {
    let encoding = Encoding::for_label(label)?;
    strict_decode(bytes, encoding)
}

/// 按 WHATWG 标签解码 RFC 8187 `filename*` / RFC 2231 续行声明的字符集。
///
/// - 未知标签 → `None`；
/// - 声明 UTF-8 但字节非法 → 走 [`decode_legacy_bytes`]（老服务器常声明 UTF-8 却发 GBK）；
/// - 其他字符集严格解码失败 → `None`。
pub(crate) fn decode_declared(bytes: &[u8], label: &[u8], hints: NameHints<'_>) -> Option<String> {
    let encoding = Encoding::for_label(label)?;
    if encoding == UTF_8 {
        return Some(match std::str::from_utf8(bytes) {
            Ok(s) => s.to_owned(),
            Err(_) => decode_legacy_bytes(bytes, hints),
        });
    }
    strict_decode(bytes, encoding)
}

/// 未声明字符集的字节解码，恒成功。
///
/// 1. 严格 UTF-8 成功 → 采用；
/// 2. `page_charset` 是无打分器的 ASCII 兼容编码（EUC-KR / EUC-JP / windows-1250 /
///    ISO-8859-x / KOI8 …，不含 UTF-8/UTF-16、GBK/Big5/Shift_JIS、windows-1252）且能严格
///    解码 → 采用；
/// 3. host 的 TLD 是 `.kr` 且 EUC-KR 能严格解码 → 采用；
/// 4. GBK / Big5 / Shift_JIS / windows-1252 中能严格解码的候选按原始字节打分，取最高分，
///    平分按此顺序取靠前者（windows-1252 恒能解码，保证有结果）。
///
/// 打分先验：TLD `.tw/.hk/.mo` → Big5 +1，`.cn` → GBK +1，`.jp` → Shift_JIS +1；
/// 页面字符集是 gbk/gb2312/gb18030 → GBK +4，big5 → Big5 +4，shift_jis → Shift_JIS +4。
pub fn decode_legacy_bytes(bytes: &[u8], hints: NameHints<'_>) -> String {
    if let Ok(s) = std::str::from_utf8(bytes) {
        return s.to_owned();
    }

    let page = hints
        .page_charset
        .and_then(|label| Encoding::for_label(label.as_bytes()));
    if let Some(encoding) = page
        && is_unscored_encoding(encoding)
        && let Some(s) = strict_decode(bytes, encoding)
    {
        return s;
    }

    let tld = hints.host.map(host_tld).unwrap_or_default();
    if tld == "kr"
        && let Some(s) = strict_decode(bytes, encoding_rs::EUC_KR)
    {
        return s;
    }

    let page_is = |encodings: &[&'static Encoding]| page.is_some_and(|p| encodings.contains(&p));
    let mut best: Option<(i32, String)> = None;
    let mut consider = |score: i32, decoded: Option<String>| {
        if let Some(decoded) = decoded
            && best.as_ref().is_none_or(|(top, _)| score > *top)
        {
            best = Some((score, decoded));
        }
    };

    // 候选顺序即平分时的优先级：GBK > Big5 > Shift_JIS > windows-1252。
    consider(
        score_gbk(bytes) + i32::from(tld == "cn") + if page_is(&[GBK, GB18030]) { 4 } else { 0 },
        strict_decode(bytes, GBK),
    );
    consider(
        score_big5(bytes)
            + i32::from(matches!(tld, "tw" | "hk" | "mo"))
            + if page_is(&[BIG5]) { 4 } else { 0 },
        strict_decode(bytes, BIG5),
    );
    consider(
        score_shift_jis(bytes) + i32::from(tld == "jp") + if page_is(&[SHIFT_JIS]) { 4 } else { 0 },
        strict_decode(bytes, SHIFT_JIS),
    );
    consider(
        score_windows_1252(bytes),
        strict_decode(bytes, WINDOWS_1252),
    );

    match best {
        Some((_, decoded)) => decoded,
        // windows-1252 对任意字节串都能严格解码，这里仅为类型完备。
        None => WINDOWS_1252.decode(bytes).0.into_owned(),
    }
}

/// 页面字符集是否属于「没有打分器、能严格解码就直接采用」的一类。
///
/// 排除 UTF-8 / UTF-16（字节恰好满足严格解码会误采用）、只参与打分的
/// GBK/GB18030/Big5/Shift_JIS/windows-1252、非 ASCII 兼容编码（ISO-2022-JP / replacement）
/// 以及 x-user-defined。
fn is_unscored_encoding(encoding: &'static Encoding) -> bool {
    encoding != UTF_8
        && encoding != UTF_16LE
        && encoding != UTF_16BE
        && encoding != GBK
        && encoding != GB18030
        && encoding != BIG5
        && encoding != SHIFT_JIS
        && encoding != WINDOWS_1252
        && encoding != encoding_rs::X_USER_DEFINED
        && encoding.is_ascii_compatible()
}

/// host 的 TLD（忽略大小写、尾随 `.`），只识别打分/规则用到的
/// `kr`/`cn`/`tw`/`hk`/`mo`/`jp`，返回对应小写字面量；其余返回空串。
fn host_tld(host: &str) -> &'static str {
    let tld = host
        .trim()
        .trim_end_matches('.')
        .rsplit('.')
        .next()
        .unwrap_or("");
    for known in ["kr", "cn", "tw", "hk", "mo", "jp"] {
        if tld.eq_ignore_ascii_case(known) {
            return known;
        }
    }
    ""
}

/// GBK 打分（按原始字节，ASCII 跳过；调用方保证该候选已能严格解码）。
///
/// - `b2 ∈ 0x30..=0x39`：四字节序列，-3，前进 4；
/// - `b1 ∈ B0..=D7` 且 `b2 ∈ A1..=FE`：GB2312 一级字，+2；
/// - `b1 ∈ D8..=F7` 且 `b2 ∈ A1..=FE`：0；`b1 ∈ {A1, A3}` 且 `b2 ∈ A1..=FE`：0；
/// - `b1 ∈ A2..=A9` 且 `b2 ∈ A1..=FE`：-2（假名/希腊/西里尔/拼音/制表符，典型误解码）；
/// - 其余双字节（GBK 扩展区生僻字）：-2；
/// - 单字节 `0x80`（欧元符号，GBK 的唯一单字节高位码）：-2，前进 1。
fn score_gbk(bytes: &[u8]) -> i32 {
    let mut score = 0;
    let mut i = 0;
    while i < bytes.len() {
        let b1 = bytes[i];
        if b1 < 0x80 {
            i += 1;
            continue;
        }
        let Some(&b2) = bytes.get(i + 1).filter(|_| b1 != 0x80) else {
            score -= 2;
            i += 1;
            continue;
        };
        if (0x30..=0x39).contains(&b2) {
            score -= 3;
            i += 4;
            continue;
        }
        let trail_a1_fe = (0xA1..=0xFE).contains(&b2);
        score += match b1 {
            0xB0..=0xD7 if trail_a1_fe => 2,
            0xD8..=0xF7 if trail_a1_fe => 0,
            0xA1 | 0xA3 if trail_a1_fe => 0,
            _ => -2,
        };
        i += 2;
    }
    score
}

/// Big5 打分：`b1 ∈ A4..=C5`，或 `b1 = C6` 且 `b2 ≤ 7E` → +2（常用字 A440–C67E）；
/// `b1 ∈ C9..=F9`（次常用字）或 `A1..=A3`（符号）→ 0；其余 → -2。
fn score_big5(bytes: &[u8]) -> i32 {
    let mut score = 0;
    let mut i = 0;
    while i < bytes.len() {
        let b1 = bytes[i];
        if b1 < 0x80 {
            i += 1;
            continue;
        }
        let Some(&b2) = bytes.get(i + 1) else {
            score -= 2;
            break;
        };
        score += match b1 {
            0xA4..=0xC5 => 2,
            0xC6 if b2 <= 0x7E => 2,
            0xC9..=0xF9 | 0xA1..=0xA3 => 0,
            _ => -2,
        };
        i += 2;
    }
    score
}

/// Shift_JIS 打分：
///
/// - 单字节 `A1..=DF`（半角片假名）：-1，前进 1；其他单字节（`80`、`A0`、`FD..=FF`）：-2，
///   前进 1；
/// - `b1 = 82` 且 `b2 ∈ 9F..=F1`（平假名）、`b1 = 83` 且 `b2 ∈ 40..=96`（片假名）：+2；
/// - `b1 = 81`：0；
/// - `b1 ∈ 89..=97`，或 `b1 = 88` 且 `b2 ≥ 9F`，或 `b1 = 98` 且 `b2 ≤ 72`（JIS 第一水准）：+2；
/// - `b1 ∈ 98..=9F` 或 `E0..=EA`：0；
/// - 其余：-2。
fn score_shift_jis(bytes: &[u8]) -> i32 {
    let mut score = 0;
    let mut i = 0;
    while i < bytes.len() {
        let b1 = bytes[i];
        if b1 < 0x80 {
            i += 1;
            continue;
        }
        match b1 {
            0xA1..=0xDF => {
                score -= 1;
                i += 1;
                continue;
            }
            0x80 | 0xA0 | 0xFD..=0xFF => {
                score -= 2;
                i += 1;
                continue;
            }
            _ => {}
        }
        let Some(&b2) = bytes.get(i + 1) else {
            score -= 2;
            break;
        };
        score += match b1 {
            0x82 if (0x9F..=0xF1).contains(&b2) => 2,
            0x83 if (0x40..=0x96).contains(&b2) => 2,
            0x81 => 0,
            0x89..=0x97 => 2,
            0x88 if b2 >= 0x9F => 2,
            0x98 if b2 <= 0x72 => 2,
            0x98..=0x9F | 0xE0..=0xEA => 0,
            _ => -2,
        };
        i += 2;
    }
    score
}

/// windows-1252 打分（逐字节）：`80..=9F` → -3；`A0..=BF` → -1；`D7`、`F7`（×、÷）→ -1；
/// 其余 `C0..=FF`（带音标字母）→ +1。
fn score_windows_1252(bytes: &[u8]) -> i32 {
    bytes
        .iter()
        .map(|&b| match b {
            0x80..=0x9F => -3,
            0xA0..=0xBF | 0xD7 | 0xF7 => -1,
            0xC0..=0xFF => 1,
            _ => 0,
        })
        .sum()
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::{
        decode_declared, decode_legacy_bytes, decode_with_label, has_percent_escape, hex_nibble,
        percent_decode_bytes,
    };
    use crate::naming::NameHints;

    fn legacy(bytes: &[u8]) -> String {
        decode_legacy_bytes(bytes, NameHints::default())
    }

    #[test]
    fn hex_nibble_parses_valid_and_rejects_invalid() {
        assert_eq!(hex_nibble(b'0'), Some(0));
        assert_eq!(hex_nibble(b'f'), Some(15));
        assert_eq!(hex_nibble(b'A'), Some(10));
        assert_eq!(hex_nibble(b'g'), None);
        assert_eq!(hex_nibble(b'%'), None);
        // 多字节 UTF-8 字符的首字节绝不能被当作合法 nibble。
        assert_eq!(hex_nibble("折".as_bytes()[0]), None);
    }

    #[test]
    fn percent_decode_basic_and_plus_is_literal() {
        assert_eq!(percent_decode_bytes(b"hello%20world"), b"hello world");
        // F046: `+` 在路径段 / Content-Disposition 里是字面加号。
        assert_eq!(percent_decode_bytes(b"C++Primer.pdf"), b"C++Primer.pdf");
        assert_eq!(
            percent_decode_bytes(b"v1.2+build%20final.bin"),
            b"v1.2+build final.bin"
        );
    }

    #[test]
    fn percent_decode_keeps_invalid_escapes_without_panic() {
        // F017: `%` 紧跟多字节 UTF-8 字符不得 panic，`%` 按字面保留。
        assert_eq!(
            percent_decode_bytes("50%折扣.txt".as_bytes()),
            "50%折扣.txt".as_bytes()
        );
        assert_eq!(
            percent_decode_bytes("%a你.zip".as_bytes()),
            "%a你.zip".as_bytes()
        );
        assert_eq!(percent_decode_bytes(b"test%"), b"test%");
        assert_eq!(percent_decode_bytes(b"%zz"), b"%zz");
        assert_eq!(percent_decode_bytes(b"ab%4"), b"ab%4");
        assert_eq!(percent_decode_bytes(b"%41"), b"A");
    }

    #[test]
    fn has_percent_escape_requires_two_hex_digits() {
        assert!(has_percent_escape(b"a%20b"));
        assert!(has_percent_escape(b"%41"));
        assert!(!has_percent_escape(b"50%off.pdf"));
        assert!(!has_percent_escape(b"50%"));
        assert!(!has_percent_escape(b"%4"));
    }

    #[test]
    fn legacy_utf8_wins_first() {
        assert_eq!(legacy("中文.txt".as_bytes()), "中文.txt");
    }

    #[test]
    fn legacy_gbk_chinese() {
        // “文件” 的 GBK = CE C4 BC FE。
        assert_eq!(legacy(&[0xCE, 0xC4, 0xBC, 0xFE]), "文件");
    }

    #[test]
    fn legacy_big5_beats_gbk_mojibake() {
        // Big5 “中文” = A4 A4 A4 E5；GBK 会错误解码为日文假名。
        assert_eq!(legacy(&[0xA4, 0xA4, 0xA4, 0xE5]), "中文");
        // Big5 “檔案下載” = C0 C9 AE D7 A4 55 B8 FC；GBK 会产生生僻字/私用区字符。
        assert_eq!(
            legacy(&[0xC0, 0xC9, 0xAE, 0xD7, 0xA4, 0x55, 0xB8, 0xFC]),
            "檔案下載"
        );
    }

    #[test]
    fn legacy_ambiguous_bytes_tie_goes_to_gbk() {
        // C0 C9 在 GBK / Big5 下都是常用字，同分按 GBK 优先。
        assert_eq!(legacy(&[0xC0, 0xC9]), "郎");
    }

    #[test]
    fn legacy_never_fails_on_undecodable_bytes() {
        // 0x81 0x7F：既非 UTF-8 也非合法 GBK/Big5/Shift_JIS 序列，windows-1252 兜底。
        assert_eq!(legacy(&[0x81, 0x7F]), "\u{81}\u{7f}");
        // 0x80 在 GBK 下是 €，GBK 候选能解码但得分为负，windows-1252 的 0x80 同样是 €。
        assert_eq!(legacy(&[0x80]), "€");
    }

    #[test]
    fn legacy_tld_prior_breaks_ties() {
        // A4 A4 A4 E5：GBK 与 Big5 都能解码，.tw 先验让 Big5 胜出且分数更高。
        let hints = NameHints {
            host: Some("example.com.tw"),
            page_charset: None,
        };
        assert_eq!(
            decode_legacy_bytes(&[0xA4, 0xA4, 0xA4, 0xE5], hints),
            "中文"
        );
    }

    #[test]
    fn legacy_page_charset_direct_adoption_and_prior() {
        // 无打分器的页面字符集（EUC-KR）：能严格解码就直接采用。
        let hints = NameHints {
            host: None,
            page_charset: Some("EUC-KR"),
        };
        assert_eq!(
            decode_legacy_bytes(&[0xBA, 0xB8, 0xB0, 0xED], hints),
            "보고"
        );
        // 有打分器的页面字符集只加先验：Big5 页面但字节只能当 GBK 解码时仍得 GBK。
        let hints = NameHints {
            host: None,
            page_charset: Some("Big5"),
        };
        assert_eq!(
            decode_legacy_bytes(&[0xC8, 0xED, 0xBC, 0xFE], hints),
            "软件"
        );
    }

    #[test]
    fn legacy_kr_tld_uses_euc_kr() {
        let hints = NameHints {
            host: Some("www.example.co.kr"),
            page_charset: None,
        };
        assert_eq!(
            decode_legacy_bytes(&[0xBA, 0xB8, 0xB0, 0xED], hints),
            "보고"
        );
    }

    #[test]
    fn decode_declared_rules() {
        let hints = NameHints::default();
        // 严格按声明解码。
        assert_eq!(
            decode_declared(&[0xC2, 0xA1], b"Big5", hints).as_deref(),
            Some("癒")
        );
        // 未知标签 → None。
        assert_eq!(decode_declared(b"abc", b"x-unknown", hints), None);
        // 非 UTF-8 字符集严格解码失败 → None（0x81 0x7F 不是合法 GBK）。
        assert_eq!(decode_declared(&[0x81, 0x7F], b"gbk", hints), None);
        // 声明 UTF-8 但字节非法 → 旧式字节解码。
        assert_eq!(
            decode_declared(&[0xCE, 0xC4, 0xBC, 0xFE], b"UTF-8", hints).as_deref(),
            Some("文件")
        );
        // ISO-8859-1 按 WHATWG 即 windows-1252。
        assert_eq!(
            decode_declared(b"caf\xE9", b"iso-8859-1", hints).as_deref(),
            Some("café")
        );
    }

    #[test]
    fn decode_with_label_is_strict_for_utf8() {
        // encoded-word 声明 UTF-8 但字节非法 → None（不走旧式字节解码）。
        assert_eq!(decode_with_label(&[0xCE, 0xC4, 0xBC, 0xFE], b"UTF-8"), None);
        assert_eq!(
            decode_with_label("中".as_bytes(), b"utf-8").as_deref(),
            Some("中")
        );
        assert_eq!(decode_with_label(b"abc", b"x-unknown"), None);
    }

    #[test]
    fn page_charset_utf16_and_1252_are_not_adopted_directly() {
        // 字节恰好是合法 UTF-16LE 也不得被 page_charset=utf-16le 直接采用。
        let hints = NameHints {
            host: None,
            page_charset: Some("utf-16le"),
        };
        assert_eq!(
            decode_legacy_bytes(&[0xCE, 0xC4, 0xBC, 0xFE], hints),
            "文件"
        );
        // windows-1252 页面只参与打分：GBK 字节仍按 GBK 解码。
        let hints = NameHints {
            host: None,
            page_charset: Some("windows-1252"),
        };
        assert_eq!(
            decode_legacy_bytes(&[0xCE, 0xC4, 0xBC, 0xFE], hints),
            "文件"
        );
    }
}
