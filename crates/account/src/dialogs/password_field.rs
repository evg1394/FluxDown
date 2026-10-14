//! 账户密码输入框的字符约束：只接受可打印 ASCII（字母、数字、符号、空格）。
//!
//! GPUI 没有平台级「安全输入」开关，输入法仍能在框内组字；组字进行中不干预（中途改写文本会打乱
//! 平台输入法状态），提交后剔除非 ASCII 字符——中文、全角符号与粘贴进来的非 ASCII 都落不进密码。

use gpui::{App, Entity, EntityInputHandler as _, Window};
use gpui_component::input::{InputEvent, InputState};

/// 给密码输入框装上字符过滤；订阅随输入框释放。
pub(crate) fn restrict_to_ascii(input: &Entity<InputState>, window: &mut Window, cx: &mut App) {
    window
        .subscribe(input, cx, |input, event, window, cx| {
            if !matches!(event, InputEvent::Change) {
                return;
            }
            input.update(cx, |state, cx| {
                if state.marked_text_range(window, cx).is_some() {
                    return;
                }
                if let Some(filtered) = filtered(&state.value()) {
                    state.set_value(filtered, window, cx);
                }
            });
        })
        .detach();
}

fn is_allowed(c: char) -> bool {
    c.is_ascii_graphic() || c == ' '
}

/// 含不允许的字符时返回剔除后的文本；全部合法返回 `None`（不改写，保住光标与撤销历史）。
fn filtered(value: &str) -> Option<String> {
    if value.chars().all(is_allowed) {
        return None;
    }
    Some(value.chars().filter(|&c| is_allowed(c)).collect())
}

#[cfg(test)]
mod tests {
    use super::filtered;

    #[test]
    fn keeps_printable_ascii_and_strips_the_rest() {
        assert_eq!(filtered("Abc 123 !@#~"), None);
        assert_eq!(filtered("ab你好c"), Some("abc".to_owned()));
        assert_eq!(filtered("ａｂ１！"), Some(String::new()));
        assert_eq!(filtered("a\tb\u{3000}c"), Some("abc".to_owned()));
    }
}
