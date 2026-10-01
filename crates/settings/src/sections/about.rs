//! 关于：版本、软件更新、日志导出、浏览器扩展与捐赠链接。

use fluxdown_protocol::method;
use fluxdown_ui_components::{ButtonVariant, FluxIcon, button, loading_button};
use fluxdown_ui_i18n::system_locale;
use fluxdown_ui_theme::active_theme;
use gpui::{App, IntoElement as _, ParentElement, SharedString, Styled, div};
use gpui_component::{h_flex, v_flex};
use serde_json::json;

use super::SectionContext;
use crate::ui::{Control, SettingsPage, SettingsRow, SettingsSection, body_text, meta_text};

pub(crate) const APP_VERSION: &str = fluxdown_protocol::APP_VERSION;
const WEBSITE: &str = "https://fluxdown.zerx.dev";
const CHROME_STORE: &str = "https://chromewebstore.google.com/search/FluxDown";
const FIREFOX_STORE: &str = "https://addons.mozilla.org/firefox/addon/fluxdown/";
const EDGE_STORE: &str = "https://microsoftedge.microsoft.com/addons/search/FluxDown";
const DONATE: &str = "https://fluxdown.zerx.dev/sponsor";

pub(crate) fn page(ctx: &SectionContext, _cx: &mut App) -> SettingsPage {
    SettingsPage::new(
        "about",
        ctx.t("settingsCatAbout"),
        ctx.t("settingsCatAboutDesc"),
        FluxIcon::Info,
    )
    .sections([
        version_section(ctx),
        update_section(ctx),
        logs_section(ctx),
        links_section(ctx),
    ])
}

fn version_section(ctx: &SectionContext) -> SettingsSection {
    let version = SharedString::from(format!("v{APP_VERSION}"));
    let protocol = SharedString::from(fluxdown_protocol::PROTOCOL_VERSION.to_string());
    SettingsSection::new()
        .title(SharedString::from("FluxDown"))
        .row(ctx.item(
            "currentVersion",
            None,
            Control::custom(move |_, _, _, cx: &mut App| body_text(cx).child(version.clone())),
        ))
        .row(ctx.item(
            "protocolVersionLabel",
            None,
            Control::custom(move |_, _, _, cx: &mut App| body_text(cx).child(protocol.clone())),
        ))
}

fn update_section(ctx: &SectionContext) -> SettingsSection {
    let channels = vec![
        (SharedString::from("stable"), ctx.t("updateChannelStable")),
        (
            SharedString::from("frontier"),
            ctx.t("updateChannelFrontier"),
        ),
    ];
    SettingsSection::new()
        .title(ctx.t("softwareUpdate"))
        .row(ctx.item(
            "updateChannel",
            Some("updateChannelDesc"),
            ctx.pref_dropdown("general.update_channel", "stable", channels),
        ))
        .row(ctx.item(
            "autoCheckUpdate",
            Some("autoCheckUpdateDesc"),
            ctx.pref_switch("general.auto_check_update", true),
        ))
        .row(ctx.item(
            "checkUpdate",
            Some("checkUpdateDesc"),
            check_update_control(ctx),
        ))
        .row(release_notes_item(ctx))
}

fn check_update_control(ctx: &SectionContext) -> Control {
    let store = ctx.store();
    let translator = ctx.translator.clone();
    let check = ctx.t("checkUpdate");
    let latest = ctx.t("latestVersion");
    Control::custom(move |disabled, _key, _window, cx: &mut App| {
        let tokens = active_theme(cx).tokens();
        let busy = store.read(cx).is_busy("update");
        let result = store.read(cx).update_check().cloned();
        let status = result.as_ref().map(|result| {
            if result.has_update {
                translator.text_with("newVersionFound", &[("v", &result.latest_version)])
            } else {
                format!("{latest}: v{}", result.latest_version)
            }
        });
        let click_store = store.clone();
        let download_url = result
            .as_ref()
            .filter(|result| result.has_update)
            .map(|result| {
                if result.download_url.is_empty() {
                    result.release_page_url.clone()
                } else {
                    result.download_url.clone()
                }
            })
            .filter(|url| !url.is_empty());
        let update_now = translator.text("updateNow").to_owned();
        h_flex()
            .gap(tokens.spacing.sm)
            .items_center()
            .children(status.map(|status| meta_text(cx).child(SharedString::from(status))))
            .children(download_url.map(|url| {
                button(
                    "about-update-now",
                    SharedString::from(update_now.clone()),
                    ButtonVariant::Primary,
                    cx,
                )
                .on_click(move |_, _, cx| cx.open_url(&url))
            }))
            .child(
                loading_button(
                    "about-check-update",
                    check.clone(),
                    ButtonVariant::Secondary,
                    busy,
                    cx,
                )
                .disabled(disabled || busy)
                .on_click(move |_, _, cx| {
                    click_store.update(cx, |store, cx| {
                        let channel = store.pref_str("general.update_channel", "stable");
                        store.check_update(Some(channel), cx);
                    });
                }),
            )
    })
}

fn release_notes_item(ctx: &SectionContext) -> SettingsRow {
    let store = ctx.store();
    let locale = system_locale();
    SettingsRow::custom(move |_, _, _, cx: &mut App| {
        let tokens = active_theme(cx).tokens();
        let Some(result) = store.read(cx).update_check().cloned() else {
            return div().into_any_element();
        };
        if result.notes.is_empty() {
            return div().into_any_element();
        }
        v_flex()
            .w_full()
            .gap(tokens.spacing.sm)
            .children(result.notes.into_iter().take(10).map(|note| {
                v_flex()
                    .gap(tokens.spacing.xxs)
                    .child(body_text(cx).child(SharedString::from(format!(
                        "v{} {}",
                        note.version, note.published_at
                    ))))
                    .child(meta_text(cx).child(SharedString::from(
                        localized_release_body(&note.body, &locale).to_owned(),
                    )))
            }))
            .into_any_element()
    })
}

fn localized_release_body<'a>(body: &'a str, locale: &str) -> &'a str {
    let language = locale
        .trim()
        .split(['-', '_', '.', '@'])
        .next()
        .unwrap_or("en");
    let chinese = language.eq_ignore_ascii_case("zh");
    let mut sections = [None, None];
    let mut previous = None;
    let mut cursor = 0;
    while let Some(offset) = body[cursor..].find("<!--") {
        let start = cursor + offset;
        let content_start = start + "<!--".len();
        let Some(offset) = body[content_start..].find("-->") else {
            break;
        };
        let content_end = content_start + offset;
        cursor = content_end + "-->".len();
        let index = match body[content_start..content_end].trim() {
            "fluxdown:lang:zh" => 0,
            "fluxdown:lang:en" => 1,
            _ => continue,
        };
        if let Some((index, section_start)) = previous {
            sections[index] = Some(body[section_start..start].trim());
        }
        previous = Some((index, cursor));
    }
    if let Some((index, section_start)) = previous {
        sections[index] = Some(body[section_start..].trim());
    }
    sections[usize::from(!chinese)]
        .or(sections[0])
        .or(sections[1])
        .unwrap_or(body)
}

fn logs_section(ctx: &SectionContext) -> SettingsSection {
    SettingsSection::new()
        .title(ctx.t("logExport"))
        .subtitle(ctx.t("logExportDesc"))
        .row(ctx.item(
            "logMaxSize",
            Some("logMaxSizeDesc"),
            ctx.daemon_number("log_max_size_mb").unit("MB"),
        ))
        .row(ctx.item("logExportButton", None, export_control(ctx)))
}

fn export_control(ctx: &SectionContext) -> Control {
    let store = ctx.store();
    let export = ctx.t("logExportButton");
    let open = ctx.t("doctorActionOpenLogDir");
    Control::custom(move |disabled, _key, _window, cx: &mut App| {
        let tokens = active_theme(cx).tokens();
        let busy = store.read(cx).is_busy("logExport");
        let opening = store.read(cx).is_busy_tagged("logExport", "openLogDir");
        let exporting = store.read(cx).is_busy_tagged("logExport", "exportLogs");
        let export_store = store.clone();
        let open_store = store.clone();
        h_flex()
            .gap(tokens.spacing.sm)
            .child(
                loading_button(
                    "about-open-log-dir",
                    open.clone(),
                    ButtonVariant::Secondary,
                    opening,
                    cx,
                )
                .disabled(disabled || opening)
                .on_click(move |_, _, cx| {
                    open_store.update(cx, |store, cx| {
                        store.call_with(
                            "logExport",
                            method::AGENT_DIAGNOSTICS_LOG_PATHS,
                            json!({}),
                            cx,
                            |store, result, cx| {
                                if let Ok(value) = result
                                    && let Some(dir) = value
                                        .get("agentLogDir")
                                        .and_then(serde_json::Value::as_str)
                                        .filter(|dir| !dir.is_empty())
                                {
                                    store.call_simple(
                                        "logExport",
                                        method::AGENT_PLATFORM_OPEN_PATH,
                                        json!({ "path": dir, "reveal": false }),
                                        None,
                                        cx,
                                    );
                                    store.tag_busy("logExport", "openLogDir");
                                }
                            },
                        );
                        store.tag_busy("logExport", "openLogDir");
                    });
                }),
            )
            .child(
                loading_button(
                    "about-export-logs",
                    export.clone(),
                    ButtonVariant::Primary,
                    exporting,
                    cx,
                )
                .disabled(disabled || busy)
                .on_click(move |_, _, cx| {
                    let store = export_store.clone();
                    let receiver =
                        cx.prompt_for_new_path(&std::env::temp_dir(), Some("fluxdown-logs.zip"));
                    cx.spawn(async move |cx| {
                        if let Ok(Ok(Some(path))) = receiver.await {
                            let target = path.display().to_string();
                            store.update(cx, |store, cx| {
                                store.call_simple(
                                    "logExport",
                                    method::AGENT_DIAGNOSTICS_EXPORT_LOGS,
                                    json!({ "targetPath": target }),
                                    Some("logExportSuccessNotice"),
                                    cx,
                                );
                                store.tag_busy("logExport", "exportLogs");
                            });
                        }
                    })
                    .detach();
                }),
            )
    })
}

fn links_section(ctx: &SectionContext) -> SettingsSection {
    SettingsSection::new()
        .title(ctx.t("extensionCardTitle"))
        .subtitle(ctx.t("extensionCardDesc"))
        .row(link_item(
            ctx,
            "extensionCardTitle",
            &[
                ("Chrome", CHROME_STORE),
                ("Firefox", FIREFOX_STORE),
                ("Edge", EDGE_STORE),
            ],
        ))
        .row(link_item(
            ctx,
            "donateTitle",
            &[("donateButton", DONATE), ("officialWebsite", WEBSITE)],
        ))
}

fn link_item(
    ctx: &SectionContext,
    title_key: &str,
    links: &[(&'static str, &'static str)],
) -> SettingsRow {
    let links: Vec<(SharedString, &'static str)> = links
        .iter()
        .map(|(label, url)| {
            let text = ctx.translator.text(label);
            (SharedString::from(text.to_owned()), *url)
        })
        .collect();
    let title = ctx.t(title_key);
    SettingsRow::custom(move |_, _, _, cx: &mut App| {
        let tokens = active_theme(cx).tokens();
        h_flex()
            .w_full()
            .items_center()
            .justify_between()
            .gap(tokens.spacing.md)
            .child(body_text(cx).child(title.clone()))
            .child(
                h_flex()
                    .gap(tokens.spacing.sm)
                    .children(links.iter().map(|(label, url)| {
                        let url = *url;
                        button(
                            SharedString::from(format!("about-link-{url}")),
                            label.clone(),
                            ButtonVariant::Secondary,
                            cx,
                        )
                        .on_click(move |_, _, cx| cx.open_url(url))
                    })),
            )
            .into_any_element()
    })
    .keywords([title_key.to_owned()])
}

#[cfg(test)]
mod tests {
    use super::localized_release_body;

    #[test]
    fn release_notes_follow_system_language() {
        let body = "前言\n<!-- fluxdown:lang:zh -->\n## 问题修复\n- 修复下载\n\
                    <!-- fluxdown:lang:en -->\n## Bug Fixes\n- Fix downloads\n";
        for locale in ["zh", "zh_CN", "zh-Hans-CN", "zh-Hant-TW", "ZH_hk"] {
            assert_eq!(
                localized_release_body(body, locale),
                "## 问题修复\n- 修复下载"
            );
        }
        for locale in ["en", "en-US", "fr_FR", "ja", ""] {
            assert_eq!(
                localized_release_body(body, locale),
                "## Bug Fixes\n- Fix downloads"
            );
        }
    }

    #[test]
    fn release_notes_handle_marker_order_and_whitespace() {
        let body = "<!--fluxdown:lang:en-->\r\nEnglish\r\n\
                    <!--\tfluxdown:lang:zh\n-->\r\n中文\r\n";
        assert_eq!(localized_release_body(body, "en"), "English");
        assert_eq!(localized_release_body(body, "zh"), "中文");
    }

    #[test]
    fn release_notes_preserve_legacy_and_use_available_translation() {
        let legacy = "## 旧版本\nUntranslated notes\n<!-- unrelated -->";
        assert_eq!(localized_release_body(legacy, "zh"), legacy);
        assert_eq!(localized_release_body(legacy, "en"), legacy);
        let chinese = "<!-- fluxdown:lang:zh -->\n中文";
        assert_eq!(localized_release_body(chinese, "en"), "中文");
        let english = "<!-- fluxdown:lang:en -->\nEnglish";
        assert_eq!(localized_release_body(english, "zh"), "English");
    }
}
