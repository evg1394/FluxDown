//! 线程数风险提示：超过 [`HIGH_SEGMENTS_WARN_ABOVE`] 时展开，超过
//! [`SEVERE_SEGMENTS_WARN_ABOVE`] 升级为危险档。新建下载对话框与队列管理共用。

use fluxdown_protocol::{HIGH_SEGMENTS_WARN_ABOVE, SEVERE_SEGMENTS_WARN_ABOVE};
use fluxdown_ui_components::{CalloutTone, RevealCallout};
use fluxdown_ui_i18n::Translator;
use gpui::ElementId;

/// 构建提示条；`segments` 为当前输入（0 = 自动）。须每帧渲染以播放退场动画。
pub(crate) fn threads_risk_callout(
    id: impl Into<ElementId>,
    segments: i64,
    translator: &Translator,
) -> RevealCallout {
    let (tone, title_key, desc_key) = if segments > i64::from(SEVERE_SEGMENTS_WARN_ABOVE) {
        (
            CalloutTone::Danger,
            "threadsRiskSevereTitle",
            "threadsRiskSevereDesc",
        )
    } else {
        (CalloutTone::Warning, "threadsRiskTitle", "threadsRiskDesc")
    };
    let count = segments.to_string();
    let limit = HIGH_SEGMENTS_WARN_ABOVE.to_string();
    RevealCallout::new(
        id,
        segments > i64::from(HIGH_SEGMENTS_WARN_ABOVE),
        tone,
        translator.text(title_key).to_owned(),
        translator.text_with(desc_key, &[("count", &count), ("limit", &limit)]),
    )
}

/// 输入框文本 → 线程数；空串 / 非法 → 0（不提示，交给提交时的校验报错）。
pub(crate) fn parse_segments_input(text: &str) -> i64 {
    text.trim().parse::<i64>().unwrap_or(0)
}
