//! 剪贴板文本 → 候选链接：从任意文本中按出现顺序取出支持的 scheme 链接。

use std::collections::HashSet;
use std::sync::LazyLock;

use regex::Regex;

/// 最多扫描的文本字节数。
const MAX_SCAN_BYTES: usize = 1024 * 1024;
/// 最多返回的候选链接数。
const MAX_LINKS: usize = 200;

/// 链接体不含空白、引号、尖括号与中文标点（全角标点是正文与链接的天然分隔）。
static LINK_PATTERN: LazyLock<Option<Regex>> = LazyLock::new(|| {
    let body = r#"[^\s<>"'`，。；：！？、（）【】「」『』《》〈〉“”‘’]+"#;
    let pattern =
        format!(r"(?i)(?:(?:https?|ftps?)://|magnet:\?|ed2k://|thunder://|fluxdown:){body}");
    match Regex::new(&pattern) {
        Ok(regex) => Some(regex),
        Err(error) => {
            tracing::error!(error = %error, "clipboard link pattern invalid");
            None
        }
    }
});

/// 提取 `text` 中的候选链接（去重、保序），最多扫描前 1 MiB、返回 200 条。
pub fn extract_links(text: &str) -> Vec<String> {
    let Some(pattern) = LINK_PATTERN.as_ref() else {
        return Vec::new();
    };
    let mut seen = HashSet::new();
    let mut links = Vec::new();
    for found in pattern.find_iter(scan_window(text)) {
        let link = trim_trailing(found.as_str());
        if link.is_empty() || !seen.insert(link) {
            continue;
        }
        links.push(link.to_owned());
        if links.len() >= MAX_LINKS {
            break;
        }
    }
    links
}

fn scan_window(text: &str) -> &str {
    if text.len() <= MAX_SCAN_BYTES {
        return text;
    }
    let mut end = MAX_SCAN_BYTES;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

/// 剥掉结尾的标点；右括号只在没有对应左括号时剥掉（URL 自身成对括号保留）。
fn trim_trailing(link: &str) -> &str {
    let mut link = link;
    while let Some(last) = link.chars().next_back() {
        let strip = match last {
            '.' | ',' | ';' | ':' | '!' | '?' => true,
            ')' => unbalanced(link, '(', ')'),
            ']' => unbalanced(link, '[', ']'),
            '}' => unbalanced(link, '{', '}'),
            _ => false,
        };
        if !strip {
            break;
        }
        link = &link[..link.len() - last.len_utf8()];
    }
    link
}

fn unbalanced(link: &str, open: char, close: char) -> bool {
    let opens = link.chars().filter(|c| *c == open).count();
    let closes = link.chars().filter(|c| *c == close).count();
    closes > opens
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_link_from_chinese_sentence() {
        assert_eq!(
            extract_links("下载地址：https://a.com/b.zip，提取码 1234"),
            vec!["https://a.com/b.zip"]
        );
    }

    #[test]
    fn extracts_mixed_multiline_text_in_order_without_duplicates() {
        let text = "看这个 https://a.com/x.zip\n\
                    还有 magnet:?xt=urn:btih:abc&dn=n。\n\
                    thunder://QUFodHRwOi8vYS5iL2MuemlwWlo=\n\
                    ED2K://|file|a.iso|123|hash|/\n\
                    重复 https://a.com/x.zip";
        assert_eq!(
            extract_links(text),
            vec![
                "https://a.com/x.zip",
                "magnet:?xt=urn:btih:abc&dn=n",
                "thunder://QUFodHRwOi8vYS5iL2MuemlwWlo=",
                "ED2K://|file|a.iso|123|hash|/",
            ]
        );
    }

    #[test]
    fn strips_wrapping_brackets_and_punctuation() {
        assert_eq!(
            extract_links("（https://a.com/b.zip）"),
            vec!["https://a.com/b.zip"]
        );
        assert_eq!(
            extract_links("【https://a.com/b.zip】"),
            vec!["https://a.com/b.zip"]
        );
        assert_eq!(
            extract_links("「https://a.com/b.zip」"),
            vec!["https://a.com/b.zip"]
        );
        assert_eq!(
            extract_links("(https://a.com/b.zip)."),
            vec!["https://a.com/b.zip"]
        );
        assert_eq!(
            extract_links("\"https://a.com/b.zip\","),
            vec!["https://a.com/b.zip"]
        );
        assert_eq!(
            extract_links("<https://a.com/b.zip>"),
            vec!["https://a.com/b.zip"]
        );
        assert_eq!(
            extract_links("https://a.com/b.zip?!"),
            vec!["https://a.com/b.zip"]
        );
    }

    #[test]
    fn keeps_balanced_parentheses_inside_url() {
        assert_eq!(
            extract_links("https://en.wikipedia.org/wiki/A_(b)"),
            vec!["https://en.wikipedia.org/wiki/A_(b)"]
        );
        assert_eq!(
            extract_links("(https://en.wikipedia.org/wiki/A_(b))"),
            vec!["https://en.wikipedia.org/wiki/A_(b)"]
        );
    }

    #[test]
    fn ignores_text_without_links() {
        assert!(extract_links("hello world, nothing here").is_empty());
        assert!(extract_links("/tmp/a.zip").is_empty());
        assert!(extract_links("").is_empty());
    }

    #[test]
    fn caps_link_count_and_scan_window() {
        let many = (0..300)
            .map(|i| format!("https://a.com/{i}.zip"))
            .collect::<Vec<_>>()
            .join("\n");
        assert_eq!(extract_links(&many).len(), MAX_LINKS);

        let mut late = "x ".repeat(MAX_SCAN_BYTES);
        late.push_str("https://a.com/late.zip");
        assert!(extract_links(&late).is_empty());
    }
}
