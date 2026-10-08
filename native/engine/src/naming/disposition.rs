//! `Content-Disposition` 文件名解析（RFC 6266 / RFC 8187 / RFC 2231 / RFC 2047 + 现实世界的
//! 非标准变体）。
//!
//! 输入是响应头的**原始字节**：HTTP 头是字节序列，老服务器会直接塞 UTF-8 / GBK / Big5 字节，
//! 全程按 `&[u8]` 切分即可，不需要任何 Latin-1 载体。扩展端
//! `fluxDown/utils/filename.ts` 与本文件逐条一致，共享用例见 `fixtures/content_disposition.json`。

use std::borrow::Cow;

use base64::Engine as _;
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};

use super::NameHints;
use super::charset::{
    decode_declared, decode_legacy_bytes, decode_with_label, has_percent_escape, hex_nibble,
    percent_decode_bytes,
};

/// RFC 2047 的 B 编码在现实里常省略 `=` 填充，解码时对填充宽容。
const BASE64_LENIENT: GeneralPurpose = GeneralPurpose::new(
    &base64::alphabet::STANDARD,
    GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent),
);

/// 单个 `(参数名, 参数值)`，均为 `header` 的子切片，已两端去 ASCII 空白。
type Param<'a> = (&'a [u8], &'a [u8]);

/// 从 `Content-Disposition` 头的原始字节提取文件名。
///
/// 优先级：`filename*`（RFC 8187）> `filename*0..N` 续行（RFC 2231）> `filename`
/// （含 RFC 2047 encoded-word / 百分号编码 / 裸字节）。返回值已 trim，**未** sanitize；
/// 没有文件名（缺参数、结果为空）返回 `None`。
pub fn disposition_filename(header: &[u8], hints: NameHints<'_>) -> Option<String> {
    let params = split_params(header);

    for (name, value) in &params {
        if name.eq_ignore_ascii_case(b"filename*")
            && let Some(decoded) = decode_ext_value(strip_ext_quotes(value), hints)
        {
            return Some(decoded);
        }
    }

    if let Some(decoded) = continuation_filename(&params, hints) {
        return Some(decoded);
    }

    for (name, value) in &params {
        if name.eq_ignore_ascii_case(b"filename")
            && let Some(decoded) = plain_filename(value, hints)
        {
            return Some(decoded);
        }
    }
    None
}

/// 按 RFC 6266 把头切成参数：`;` 在双引号内不分隔；引号内只有 `\"` 与 `\\` 是转义对
/// （反斜杠后跟其他字节原样保留——Big5 / Shift_JIS 的尾字节 `0x5C` 不能被当成转义吃掉）。
/// 没有 `=` 的片段（如 `attachment`）被跳过，`=` 两侧空白被容忍。
fn split_params(header: &[u8]) -> Vec<Param<'_>> {
    let mut parts: Vec<&[u8]> = Vec::new();
    let mut start = 0usize;
    let mut in_quotes = false;
    let mut i = 0usize;
    while i < header.len() {
        match header[i] {
            b'\\' if in_quotes && matches!(header.get(i + 1), Some(b'"' | b'\\')) => i += 1,
            b'"' => in_quotes = !in_quotes,
            b';' if !in_quotes => {
                parts.push(&header[start..i]);
                start = i + 1;
            }
            _ => {}
        }
        i += 1;
    }
    parts.push(&header[start..]);

    parts
        .into_iter()
        .filter_map(|part| {
            let eq = part.iter().position(|&b| b == b'=')?;
            Some((part[..eq].trim_ascii(), part[eq + 1..].trim_ascii()))
        })
        .collect()
}

/// quoted-string 处理：去两端双引号，仅把 `\"` → `"`、`\\` → `\`；未加引号的值只做 trim。
fn unquote(value: &[u8]) -> Cow<'_, [u8]> {
    let value = value.trim_ascii();
    let Some(inner) = value.strip_prefix(b"\"") else {
        return Cow::Borrowed(value);
    };
    let inner = inner.strip_suffix(b"\"").unwrap_or(inner);
    if !inner.contains(&b'\\') {
        return Cow::Borrowed(inner);
    }
    let mut out = Vec::with_capacity(inner.len());
    let mut i = 0;
    while i < inner.len() {
        if inner[i] == b'\\'
            && let Some(&next @ (b'"' | b'\\')) = inner.get(i + 1)
        {
            out.push(next);
            i += 2;
        } else {
            out.push(inner[i]);
            i += 1;
        }
    }
    Cow::Owned(out)
}

/// RFC 8187 ext-value 是 token，不允许加引号；腾讯云 COS 等会把整个值用双引号包起来
/// （`filename*="UTF-8''foo.exe"`），这里去掉这层包裹（不做转义处理）。
fn strip_ext_quotes(value: &[u8]) -> &[u8] {
    let value = value.trim_ascii();
    let value = value.strip_prefix(b"\"").unwrap_or(value);
    let value = value.strip_suffix(b"\"").unwrap_or(value);
    value.trim_ascii()
}

/// 把 `charset'lang'payload` 拆成 `(charset, payload)`；缺少两个 `'` 返回 `None`。
fn split_ext_value(value: &[u8]) -> Option<(&[u8], &[u8])> {
    let first = value.iter().position(|&b| b == b'\'')?;
    let rest = &value[first + 1..];
    let second = rest.iter().position(|&b| b == b'\'')?;
    Some((&value[..first], &rest[second + 1..]))
}

/// 按声明字符集解码百分号编码后的字节；声明为空按未声明处理，未知标签返回 `None`。
fn decode_with_declared_charset(
    bytes: &[u8],
    charset: &[u8],
    hints: NameHints<'_>,
) -> Option<String> {
    let charset = charset.trim_ascii();
    if charset.is_empty() {
        return Some(decode_legacy_bytes(bytes, hints));
    }
    decode_declared(bytes, charset, hints)
}

fn trimmed_non_empty(decoded: &str) -> Option<String> {
    let trimmed = decoded.trim();
    (!trimmed.is_empty()).then(|| trimmed.to_owned())
}

/// `filename*=charset'lang'pct`。未知标签 / 严格解码失败 / 格式错误 → `None`（忽略该参数）。
fn decode_ext_value(value: &[u8], hints: NameHints<'_>) -> Option<String> {
    let (charset, payload) = split_ext_value(value)?;
    let bytes = percent_decode_bytes(payload);
    trimmed_non_empty(&decode_with_declared_charset(&bytes, charset, hints)?)
}

/// 去掉 `filename*` 后若只剩 `<序号>[*]`，返回 `(序号, 是否百分号编码段)`。
fn continuation_index(name: &[u8]) -> Option<(usize, bool)> {
    const PREFIX: &[u8] = b"filename*";
    if name.len() <= PREFIX.len() || !name[..PREFIX.len()].eq_ignore_ascii_case(PREFIX) {
        return None;
    }
    let rest = &name[PREFIX.len()..];
    let (digits, encoded) = match rest.strip_suffix(b"*") {
        Some(digits) => (digits, true),
        None => (rest, false),
    };
    // 序号至多 4 位：正常续行只有个位数，过长的数字必然缺段，直接不当续行。
    if digits.is_empty() || digits.len() > 4 || !digits.iter().all(u8::is_ascii_digit) {
        return None;
    }
    let index = digits
        .iter()
        .fold(0usize, |acc, &d| acc * 10 + usize::from(d - b'0'));
    Some((index, encoded))
}

/// RFC 2231 续行：`filename*0*=charset''pct; filename*1*=pct; filename*2="literal"`。
///
/// 按序号拼接字节（带 `*` 的段百分号解码，不带的为字面量/quoted-string），charset 取第 0 段。
/// 序号须从 0 连续，缺段或第 0 段 charset 格式错误则放弃续行。
fn continuation_filename(params: &[Param<'_>], hints: NameHints<'_>) -> Option<String> {
    let mut segments: Vec<(usize, bool, &[u8])> = params
        .iter()
        .filter_map(|(name, value)| {
            let (index, encoded) = continuation_index(name)?;
            Some((index, encoded, *value))
        })
        .collect();
    if segments.is_empty() {
        return None;
    }
    // 稳定排序 + 去重保留同序号的第一段。
    segments.sort_by_key(|&(index, _, _)| index);
    segments.dedup_by_key(|&mut (index, _, _)| index);
    if segments
        .iter()
        .enumerate()
        .any(|(position, &(index, _, _))| position != index)
    {
        return None;
    }

    let mut charset: &[u8] = b"";
    let mut bytes = Vec::new();
    for (position, &(_, encoded, value)) in segments.iter().enumerate() {
        if encoded {
            let value = strip_ext_quotes(value);
            let payload = if position == 0 {
                let (cs, payload) = split_ext_value(value)?;
                charset = cs;
                payload
            } else {
                value
            };
            bytes.extend_from_slice(&percent_decode_bytes(payload));
        } else {
            bytes.extend_from_slice(&unquote(value));
        }
    }
    trimmed_non_empty(&decode_with_declared_charset(&bytes, charset, hints)?)
}

/// `filename=`：quoted-string 处理后依次尝试 RFC 2047 encoded-word → 百分号编码 → 裸字节。
fn plain_filename(value: &[u8], hints: NameHints<'_>) -> Option<String> {
    let value = unquote(value);
    let value = value.trim_ascii();
    if value.is_empty() {
        return None;
    }

    if let Some(decoded) = decode_encoded_words(value, hints)
        && let Some(name) = trimmed_non_empty(&decoded)
    {
        return Some(name);
    }

    // 部分服务器（OBS / S3 类对象存储）把 filename= 的值整体百分号编码而不是用 filename*=。
    if has_percent_escape(value) {
        let decoded = decode_legacy_bytes(&percent_decode_bytes(value), hints);
        if let Some(name) = trimmed_non_empty(&decoded) {
            return Some(name);
        }
    }

    trimmed_non_empty(&decode_legacy_bytes(value, hints))
}

enum Piece<'a> {
    Word(String),
    Plain(&'a [u8]),
}

/// 解码值中的 RFC 2047 encoded-word（`=?charset?B|Q?text?=`，可多个）。
///
/// 相邻 encoded-word 之间的纯空白丢弃；其余文本按旧式字节解码原样保留。没有 encoded-word、
/// 或任一 encoded-word 无法解码（未知字符集、严格解码失败含 UTF-8 声明但字节非法、base64
/// 非法）→ `None`，调用方把整个值当普通值。
fn decode_encoded_words(value: &[u8], hints: NameHints<'_>) -> Option<String> {
    let mut pieces: Vec<Piece<'_>> = Vec::new();
    let mut plain_start = 0usize;
    let mut i = 0usize;
    let mut found = false;
    while i < value.len() {
        if value[i..].starts_with(b"=?")
            && let Some((len, decoded)) = parse_encoded_word(&value[i..])
        {
            if plain_start < i {
                pieces.push(Piece::Plain(&value[plain_start..i]));
            }
            pieces.push(Piece::Word(decoded?));
            found = true;
            i += len;
            plain_start = i;
            continue;
        }
        i += 1;
    }
    if !found {
        return None;
    }
    if plain_start < value.len() {
        pieces.push(Piece::Plain(&value[plain_start..]));
    }

    let mut out = String::new();
    for (index, piece) in pieces.iter().enumerate() {
        match piece {
            Piece::Word(text) => out.push_str(text),
            Piece::Plain(bytes) => {
                let between_words = bytes.iter().all(u8::is_ascii_whitespace)
                    && index > 0
                    && matches!(pieces[index - 1], Piece::Word(_))
                    && matches!(pieces.get(index + 1), Some(Piece::Word(_)));
                if !between_words {
                    out.push_str(&decode_legacy_bytes(bytes, hints));
                }
            }
        }
    }
    Some(out)
}

/// 在 `input`（以 `=?` 开头）处尝试解析一个 encoded-word。
///
/// - 结构不合法 → `None`（调用方把 `=` 当字面字节继续扫描）；
/// - 结构合法 → `Some((占用字节数, 解码结果))`，解码失败时结果为 `None`。
fn parse_encoded_word(input: &[u8]) -> Option<(usize, Option<String>)> {
    let rest = &input[2..];
    let charset_end = rest.iter().position(|&b| b == b'?')?;
    let charset = &rest[..charset_end];
    if charset.is_empty() || charset.iter().any(|b| b.is_ascii_whitespace()) {
        return None;
    }
    // RFC 2231 允许 `charset*language`，语言部分对解码无意义。
    let charset = charset.split(|&b| b == b'*').next().unwrap_or(charset);

    let rest = &rest[charset_end + 1..];
    let (&encoding, rest) = rest.split_first()?;
    if !matches!(encoding, b'B' | b'b' | b'Q' | b'q') {
        return None;
    }
    let rest = rest.strip_prefix(b"?")?;
    let text_end = rest.iter().position(|&b| b == b'?')?;
    let text = &rest[..text_end];
    if text.iter().any(|b| b.is_ascii_whitespace()) || rest.get(text_end + 1) != Some(&b'=') {
        return None;
    }
    let consumed = 2 + charset_end + 1 + 1 + 1 + text_end + 2;

    let bytes = match encoding {
        b'B' | b'b' => BASE64_LENIENT.decode(text).ok(),
        _ => Some(decode_q(text)),
    };
    let decoded = bytes.and_then(|bytes| decode_with_label(&bytes, charset));
    Some((consumed, decoded))
}

/// RFC 2047 Q 编码：`_` → 空格，`=XX` → 字节，其余字节原样。
fn decode_q(text: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(text.len());
    let mut i = 0;
    while i < text.len() {
        let escaped = if text[i] == b'='
            && let Some(&hi) = text.get(i + 1)
            && let Some(&lo) = text.get(i + 2)
        {
            hex_nibble(hi).zip(hex_nibble(lo))
        } else {
            None
        };
        match (text[i], escaped) {
            (_, Some((hi, lo))) => {
                out.push((hi << 4) | lo);
                i += 3;
            }
            (b'_', None) => {
                out.push(b' ');
                i += 1;
            }
            (byte, None) => {
                out.push(byte);
                i += 1;
            }
        }
    }
    out
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::disposition_filename;
    use crate::naming::{NameHints, sanitize_filename};

    /// 旧测试沿用的口径：解析后再 sanitize。
    fn extract_from_content_disposition(headers: &reqwest::header::HeaderMap) -> Option<String> {
        let value = headers.get(reqwest::header::CONTENT_DISPOSITION)?;
        disposition_filename(value.as_bytes(), NameHints::default())
            .map(|name| sanitize_filename(&name))
    }

    fn make_headers_with_cd(value: &str) -> reqwest::header::HeaderMap {
        make_headers_with_raw_cd(value.as_bytes())
    }

    fn make_headers_with_raw_cd(raw: &[u8]) -> reqwest::header::HeaderMap {
        let mut headers = reqwest::header::HeaderMap::new();
        let value = reqwest::header::HeaderValue::from_bytes(raw)
            .unwrap_or_else(|_| reqwest::header::HeaderValue::from_static("attachment"));
        headers.insert(reqwest::header::CONTENT_DISPOSITION, value);
        headers
    }

    fn hex_to_bytes(hex: &str) -> Vec<u8> {
        assert_eq!(hex.len() % 2, 0, "odd-length hex: {hex}");
        (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect()
    }

    /// 与扩展端共享的用例：逐条断言 `disposition_filename`。
    #[test]
    fn shared_fixture_cases() {
        let fixture: serde_json::Value =
            serde_json::from_str(include_str!("fixtures/content_disposition.json")).unwrap();
        let cases = fixture["cases"].as_array().unwrap();
        assert!(!cases.is_empty());
        for case in cases {
            let name = case["name"].as_str().unwrap();
            let bytes = match case["headerHex"].as_str() {
                Some(hex) => hex_to_bytes(hex),
                None => case["header"].as_str().unwrap().as_bytes().to_vec(),
            };
            let hints = NameHints {
                host: case["host"].as_str(),
                page_charset: case["pageCharset"].as_str(),
            };
            let expected = case["expected"].as_str().map(str::to_owned);
            assert_eq!(
                disposition_filename(&bytes, hints),
                expected,
                "fixture case: {name}"
            );
        }
    }

    fn parse(header: &str) -> Option<String> {
        disposition_filename(header.as_bytes(), NameHints::default())
    }

    #[test]
    fn rfc2047_declared_utf8_with_invalid_bytes_fails_whole_value() {
        // =?UTF-8?Q?=CE=C4?= 声明 UTF-8 但字节非法：encoded-word 解码失败，整个值按普通值处理
        //（不走旧式字节解码）；普通值里没有合法 %XX，原样保留。
        assert_eq!(
            parse("attachment; filename=\"=?UTF-8?Q?=CE=C4?=\"").as_deref(),
            Some("=?UTF-8?Q?=CE=C4?=")
        );
    }

    #[test]
    fn filename_star_declared_utf8_with_gbk_bytes_uses_legacy_decoding() {
        assert_eq!(
            parse("attachment; filename*=UTF-8''%CE%C4%BC%FE.txt").as_deref(),
            Some("文件.txt")
        );
    }

    #[test]
    fn parameter_before_first_semicolon_is_parsed() {
        assert_eq!(parse("filename=a.zip").as_deref(), Some("a.zip"));
    }

    #[test]
    fn continuation_with_gap_is_abandoned() {
        assert_eq!(
            parse("attachment; filename*0*=UTF-8''a; filename*2=\"b\"; filename=\"x.zip\"")
                .as_deref(),
            Some("x.zip")
        );
    }

    #[test]
    fn rfc2047_q_underscore_is_space() {
        assert_eq!(
            parse("attachment; filename=\"=?UTF-8?Q?a_b.txt?=\"").as_deref(),
            Some("a b.txt")
        );
    }

    #[test]
    fn rfc2047_base64_without_padding() {
        assert_eq!(
            parse("attachment; filename=\"=?UTF-8?B?5L2g5aW9LnppcA?=\"").as_deref(),
            Some("你好.zip")
        );
    }

    #[test]
    fn extract_from_content_disposition_param_name_case_and_spacing() {
        let h = make_headers_with_cd("attachment; FileName=\"report.pdf\"");
        assert_eq!(
            extract_from_content_disposition(&h).as_deref(),
            Some("report.pdf")
        );
        let h = make_headers_with_cd("attachment; filename = \"a.zip\"");
        assert_eq!(
            extract_from_content_disposition(&h).as_deref(),
            Some("a.zip")
        );
        let h = make_headers_with_cd("attachment; FILENAME*=UTF-8''My%20File.pdf");
        assert_eq!(
            extract_from_content_disposition(&h).as_deref(),
            Some("My File.pdf")
        );
    }

    #[test]
    fn extract_from_content_disposition_semicolon_inside_quotes() {
        let h = make_headers_with_cd("attachment; filename=\"a;b.zip\"; size=10");
        assert_eq!(
            extract_from_content_disposition(&h).as_deref(),
            Some("a;b.zip")
        );
    }

    #[test]
    fn extract_from_content_disposition_gbk_filename() {
        // 中文云存储 OBS/S3 类服务器可能返回 GBK 编码的 filename=
        let headers = make_headers_with_cd("attachment; filename=\"%CE%C4%BC%FE.txt\"");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(
            name.as_deref(),
            Some("文件.txt"),
            "GBK percent-encoded Content-Disposition 应能被正确解码"
        );
    }

    #[test]
    fn extract_from_content_disposition_big5_filename() {
        // 台湾站点常见的 Big5/CP950 编码：“中文” = A4 A4 A4 E5。
        let headers = make_headers_with_cd("attachment; filename=\"%A4%A4%A4%E5.txt\"");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(
            name.as_deref(),
            Some("中文.txt"),
            "Big5 percent-encoded Content-Disposition 应能被正确解码"
        );
    }

    #[test]
    fn extract_from_content_disposition_explicit_big5_charset() {
        let headers = make_headers_with_cd("attachment; filename*=Big5''%A4%A4%A4%E5.txt");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("中文.txt"));
    }

    #[test]
    fn extract_from_content_disposition_explicit_big5_charset_overrides_utf8() {
        // C2 A1 is valid UTF-8 (U+00A1) but Big5 "癒". The declared charset
        // must win when both decoders accept the same bytes.
        let headers = make_headers_with_cd("attachment; filename*=Big5''%C2%A1.txt");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("癒.txt"));
    }

    #[test]
    fn extract_from_content_disposition_raw_big5_bytes() {
        let headers = make_headers_with_raw_cd(b"attachment; filename=\"\xA4\xA4\xA4\xE5.txt\"");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("中文.txt"));
    }

    #[test]
    fn extract_from_content_disposition_raw_utf8_in_filename_star() {
        // 非标准但常见：filename* 的 ext-value 直接塞原始 UTF-8 字节而非 %XX。
        // 回归：Latin-1 载体若经 str::as_bytes 二次编码会得到 "ä¸\u{ad}æ__.txt"。
        let headers =
            make_headers_with_raw_cd(b"attachment; filename*=UTF-8''\xe4\xb8\xad\xe6\x96\x87.txt");
        assert_eq!(
            extract_from_content_disposition(&headers).as_deref(),
            Some("中文.txt")
        );
    }

    #[test]
    fn extract_from_content_disposition_raw_utf8_mixed_with_percent() {
        // 原始 UTF-8 字节与 %20 混排：percent 分支也必须先还原原始字节。
        let headers =
            make_headers_with_raw_cd(b"attachment; filename=\"\xe4\xb8\xad\xe6\x96\x87%20a.txt\"");
        assert_eq!(
            extract_from_content_disposition(&headers).as_deref(),
            Some("中文 a.txt")
        );
    }

    #[test]
    fn extract_from_content_disposition_raw_gbk_with_declared_charset() {
        let headers = make_headers_with_raw_cd(b"attachment; filename*=GBK''\xce\xc4\xbc\xfe.txt");
        assert_eq!(
            extract_from_content_disposition(&headers).as_deref(),
            Some("文件.txt")
        );
    }

    // -----------------------------------------------------------------------
    // extract_from_content_disposition (private, tested via extract_filename)
    // -----------------------------------------------------------------------

    #[test]
    fn content_disposition_quoted_filename() {
        let headers = make_headers_with_cd("attachment; filename=\"my_file.zip\"");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("my_file.zip"));
    }

    #[test]
    fn content_disposition_unquoted_filename() {
        let headers = make_headers_with_cd("attachment; filename=simple.txt");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("simple.txt"));
    }

    #[test]
    fn content_disposition_rfc5987_filename_star() {
        let headers = make_headers_with_cd("attachment; filename*=UTF-8''%E6%96%87%E4%BB%B6.pdf");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("文件.pdf"));
    }

    #[test]
    fn content_disposition_filename_star_quoted_ext_value() {
        // 腾讯云 COS（devtools.wxqcloud.qq.com.cn 等）把整个 ext-value 加了引号，
        // RFC 6266 不允许；尾引号若泄漏进文件名，会被 sanitize 成 `..exe_`。
        let headers =
            make_headers_with_cd("attachment; filename*=\"UTF-8''wechat_devtools_x64.exe\"");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("wechat_devtools_x64.exe"));
    }

    #[test]
    fn content_disposition_filename_star_overrides_plain() {
        let headers = make_headers_with_cd(
            "attachment; filename=\"fallback.txt\"; filename*=UTF-8''preferred.txt",
        );
        let name = extract_from_content_disposition(&headers);
        // filename* should take precedence
        assert_eq!(name.as_deref(), Some("preferred.txt"));
    }

    #[test]
    fn content_disposition_empty_filename() {
        let headers = make_headers_with_cd("attachment; filename=\"\"");
        let name = extract_from_content_disposition(&headers);
        assert!(name.is_none(), "empty filename should return None");
    }

    #[test]
    fn content_disposition_no_filename_param() {
        let headers = make_headers_with_cd("inline");
        let name = extract_from_content_disposition(&headers);
        assert!(name.is_none());
    }

    #[test]
    fn content_disposition_percent_encoded_filename_unquoted() {
        // Chinese cloud storage (OBS/S3) often sends percent-encoded filename=
        // instead of using the RFC 5987 filename*= syntax.
        let headers = make_headers_with_cd(
            "attachment;filename=%E6%B0%B8%E7%94%9F%E6%88%98%E5%A3%AB.Sisu.2022265.mp4",
        );
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("永生战士.Sisu.2022265.mp4"));
    }

    #[test]
    fn content_disposition_percent_encoded_filename_quoted() {
        let headers = make_headers_with_cd(
            "attachment; filename=\"%E6%B0%B8%E7%94%9F%E6%88%98%E5%A3%AB.Sisu.2022265.mp4\"",
        );
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("永生战士.Sisu.2022265.mp4"));
    }

    #[test]
    fn content_disposition_plain_ascii_with_percent_literal() {
        // A filename like "50%.txt" should NOT be mangled by the heuristic
        // because urlencoding_decode("50%.txt") will fail or leave it unchanged.
        let headers = make_headers_with_cd("attachment; filename=\"50%.txt\"");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("50%.txt"));
    }

    #[test]
    fn content_disposition_percent_encoded_spaces() {
        let headers = make_headers_with_cd("attachment; filename=My%20Great%20File.pdf");
        let name = extract_from_content_disposition(&headers);
        assert_eq!(name.as_deref(), Some("My Great File.pdf"));
    }

    #[test]
    fn content_disposition_raw_utf8_chinese_filename_extracted_correctly() {
        // Regression test for z-lib CDN: server sends raw UTF-8 bytes in filename="".
        // Before the fix (to_str) this returned None and callers fell back to the URL,
        // producing garbage like "redirection" or a hash string as the task name.
        // After the fix (from_utf8) the correct Chinese filename is extracted via
        // the filename*= parameter (RFC 5987 percent-encoding).
        let raw: &[u8] = b"attachment; filename=\"\xe4\xb8\x89\xe4\xbd\x93 (\xe5\x88\x98\xe6\x85\x88\xe6\xac\xa3).epub\"; filename*=UTF-8''%E4%B8%89%E4%BD%93%20(%E5%88%98%E6%85%88%E6%AC%A3).epub";

        let mut headers = reqwest::header::HeaderMap::new();
        let hv = reqwest::header::HeaderValue::from_bytes(raw)
            .expect("HeaderValue::from_bytes must accept arbitrary bytes");
        headers.insert(reqwest::header::CONTENT_DISPOSITION, hv);

        let name = extract_from_content_disposition(&headers);
        // filename*= (RFC 5987) takes priority and decodes to the correct Chinese name.
        assert_eq!(
            name.as_deref(),
            Some("三体 (刘慈欣).epub"),
            "raw UTF-8 bytes in filename= must not prevent filename*= from being parsed"
        );
    }
}
