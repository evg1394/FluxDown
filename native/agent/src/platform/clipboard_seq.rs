//! 系统剪贴板变更计数：计数未变就无需读取剪贴板内容。
//!
//! macOS 用 `NSPasteboard.changeCount`，Windows 用 `GetClipboardSequenceNumber`；其它平台
//! 没有廉价计数，返回 `None`，调用方退回读取文本比较。

/// 当前剪贴板变更计数；平台不支持（或 Windows 无窗口站权限）时为 `None`。
#[cfg(target_os = "macos")]
pub fn clipboard_change_count() -> Option<u64> {
    let count = objc2_app_kit::NSPasteboard::generalPasteboard().changeCount();
    u64::try_from(count).ok()
}

/// 当前剪贴板变更计数；平台不支持（或 Windows 无窗口站权限）时为 `None`。
#[cfg(windows)]
pub fn clipboard_change_count() -> Option<u64> {
    // SAFETY: `GetClipboardSequenceNumber` takes no arguments and has no preconditions;
    // it only reads the sequence number of the current window station.
    let sequence =
        unsafe { windows_sys::Win32::System::DataExchange::GetClipboardSequenceNumber() };
    // 0 表示调用线程没有访问当前窗口站的权限。
    (sequence != 0).then_some(u64::from(sequence))
}

/// 当前剪贴板变更计数；平台不支持（或 Windows 无窗口站权限）时为 `None`。
#[cfg(not(any(target_os = "macos", windows)))]
pub fn clipboard_change_count() -> Option<u64> {
    None
}
