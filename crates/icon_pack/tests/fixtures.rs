//! 图标包解析两端一致性：`tests/fixtures/cases.json` 同时被 TS 镜像
//! （`web/src/lib/icon-pack/pack.test.ts`）读取，诊断（按路径排序）、图标模式与匹配逐条比对。

use fluxdown_ui_icon_pack::{FileKind, IconMode, IconPackParseError, parse_icon_pack};
use serde_json::Value;

const CASES: &str = include_str!("fixtures/cases.json");

fn error_name(error: &IconPackParseError) -> &'static str {
    match error {
        IconPackParseError::InvalidJson(_) => "invalidJson",
        IconPackParseError::NotAnObject => "notAnObject",
        IconPackParseError::UnsupportedFormat(_) => "unsupportedFormat",
    }
}

#[test]
fn shared_fixture_cases() {
    let cases: Vec<Value> = serde_json::from_str(CASES).expect("fixture json");
    assert!(!cases.is_empty());
    for case in cases {
        let name = case["name"].as_str().expect("case name");
        let text = match &case["text"] {
            Value::String(text) => text.clone(),
            _ => case["pack"].to_string(),
        };
        let parsed = parse_icon_pack(&text);
        if let Some(expected) = case["error"].as_str() {
            let error = parsed.expect_err(name);
            assert_eq!(error_name(&error), expected, "{name}");
            continue;
        }
        let parsed = parsed.expect(name);

        let mut diagnostics: Vec<(String, &str)> = parsed
            .diagnostics
            .iter()
            .map(|diagnostic| (diagnostic.path.clone(), diagnostic.kind.wire_name()))
            .collect();
        diagnostics.sort();
        let mut expected: Vec<(String, &str)> = case["diagnostics"]
            .as_array()
            .expect("diagnostics")
            .iter()
            .map(|entry| {
                (
                    entry["path"].as_str().expect("path").to_owned(),
                    entry["kind"].as_str().expect("kind"),
                )
            })
            .collect();
        expected.sort();
        assert_eq!(diagnostics, expected, "{name}");

        let mut icons: Vec<(String, &str)> = parsed
            .pack
            .icons()
            .map(|(icon, entry)| {
                let mode = match entry.mode {
                    IconMode::Mask => "mask",
                    IconMode::Color => "color",
                };
                (icon.to_owned(), mode)
            })
            .collect();
        icons.sort();
        let mut expected_icons: Vec<(String, &str)> = case["icons"]
            .as_object()
            .expect("icons")
            .iter()
            .map(|(icon, mode)| (icon.clone(), mode.as_str().expect("mode")))
            .collect();
        expected_icons.sort();
        assert_eq!(icons, expected_icons, "{name}");

        for check in case["resolve"].as_array().expect("resolve") {
            let file = check["file"].as_str().expect("file").to_lowercase();
            let kind = FileKind::from_wire(check["kind"].as_str().expect("kind")).expect("kind");
            let actual = parsed
                .pack
                .matched_icon(&file, kind)
                .or_else(|| parsed.pack.default_icon())
                .map(|(icon, _)| icon);
            assert_eq!(actual, check["icon"].as_str(), "{name}: {file}");
        }
    }
}
