//! 邮箱验证码步骤的纯状态：有效期倒计时、重发冷却与「验证后替换设备」提示。
//!
//! 登录（设备验证 / 邮箱验证码）与注册验证共用；UI 每秒 `tick` 一次并在
//! [`CodeChallenge::is_idle`] 时停止计时器。

use std::collections::HashMap;
use std::time::{Duration, Instant};

use gpui::{Context, Window};

/// 云端对同一邮箱重发验证码的限频窗口；窗口内重发只会返回原验证步骤，不会真的发信。
pub(crate) const RESEND_COOLDOWN_SECS: u64 = 60;

/// 一次已发出的验证码。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct CodeChallenge {
    /// 验证码剩余有效秒数；0 = 已过期，需要重新发送。
    pub ttl_remaining: u64,
    /// 距离允许重发的剩余秒数；0 = 现在可以重发。
    pub resend_remaining: u64,
    /// 验证成功后会替换（登出）最久未用的设备。
    pub will_replace_devices: bool,
}

impl CodeChallenge {
    #[must_use]
    pub(crate) fn new(ttl_seconds: u64, will_replace_devices: bool) -> Self {
        Self {
            ttl_remaining: ttl_seconds,
            // 验证码有效期短于限频窗口时，过期即可重发。
            resend_remaining: RESEND_COOLDOWN_SECS.min(ttl_seconds),
            will_replace_devices,
        }
    }

    /// 过去 1 秒；返回显示是否发生变化（用于决定是否重绘）。
    pub(crate) fn tick(&mut self) -> bool {
        let before = *self;
        self.ttl_remaining = self.ttl_remaining.saturating_sub(1);
        self.resend_remaining = self.resend_remaining.saturating_sub(1);
        *self != before
    }

    #[must_use]
    pub(crate) fn is_expired(&self) -> bool {
        self.ttl_remaining == 0
    }

    #[must_use]
    pub(crate) fn can_resend(&self) -> bool {
        self.resend_remaining == 0
    }

    /// 两个计时都已走完：不再需要计时器。
    #[must_use]
    pub(crate) fn is_idle(&self) -> bool {
        self.ttl_remaining == 0 && self.resend_remaining == 0
    }

    /// 发码 `elapsed_secs` 秒后的状态：用于对话框关掉重开时恢复倒计时。
    #[must_use]
    pub(crate) fn after(ttl_seconds: u64, elapsed_secs: u64) -> Self {
        let fresh = Self::new(ttl_seconds, false);
        Self {
            ttl_remaining: fresh.ttl_remaining.saturating_sub(elapsed_secs),
            resend_remaining: fresh.resend_remaining.saturating_sub(elapsed_secs),
            will_replace_devices: false,
        }
    }
}

/// 跨对话框实例记住已发出的验证码（按流程 + 账号区分）：关掉再打开同一流程时恢复
/// 倒计时而不是重新发码，避免撞上云端 60s 限频、让用户误以为没发出去。
#[derive(Default)]
pub(crate) struct SentCodes {
    entries: HashMap<String, (Instant, u64)>,
}

impl SentCodes {
    pub(crate) fn record(&mut self, key: String, ttl_seconds: u64) {
        let now = Instant::now();
        self.entries.retain(|_, (at, ttl)| {
            !CodeChallenge::after(*ttl, now.duration_since(*at).as_secs()).is_idle()
        });
        self.entries.insert(key, (now, ttl_seconds));
    }

    /// 仍在有效期或冷却期内的验证码；两者都走完返回 `None`。
    #[must_use]
    pub(crate) fn restore(&self, key: &str) -> Option<CodeChallenge> {
        let (at, ttl) = self.entries.get(key)?;
        Some(CodeChallenge::after(*ttl, at.elapsed().as_secs())).filter(|c| !c.is_idle())
    }

    /// 验证码已被消费（流程成功）：重开对话框应重新发码而不是恢复旧倒计时。
    pub(crate) fn forget(&mut self, key: &str) {
        self.entries.remove(key);
    }
}

/// 每秒回调 `tick`（返回 `false` 结束）；视图销毁时自动停止。
/// 调用方用「代际」自行作废被新一轮验证码取代的旧计时器。
pub(crate) fn spawn_ticker<V: 'static>(
    window: &mut Window,
    cx: &mut Context<V>,
    tick: impl Fn(&mut V, &mut Window, &mut Context<V>) -> bool + 'static,
) {
    cx.spawn_in(window, async move |this, cx| {
        loop {
            cx.background_executor().timer(Duration::from_secs(1)).await;
            match this.update_in(cx, |this, window, cx| tick(this, window, cx)) {
                Ok(true) => {}
                Ok(false) | Err(_) => break,
            }
        }
    })
    .detach();
}

#[cfg(test)]
#[allow(clippy::expect_used)]
mod tests {
    use super::*;

    #[test]
    fn countdown_runs_ttl_and_resend_independently_until_idle() {
        let mut challenge = CodeChallenge::new(90, true);
        assert!(challenge.will_replace_devices);
        assert!(!challenge.can_resend());
        for _ in 0..RESEND_COOLDOWN_SECS {
            assert!(challenge.tick());
        }
        assert!(challenge.can_resend());
        assert!(!challenge.is_expired());
        assert_eq!(challenge.ttl_remaining, 90 - RESEND_COOLDOWN_SECS);
        for _ in 0..30 {
            assert!(challenge.tick());
        }
        assert!(challenge.is_expired());
        assert!(challenge.is_idle());
        // 走完后不再变化，计时器据此停止。
        assert!(!challenge.tick());
    }

    #[test]
    fn short_ttl_allows_resend_at_expiry() {
        let mut challenge = CodeChallenge::new(20, false);
        assert_eq!(challenge.resend_remaining, 20);
        for _ in 0..20 {
            challenge.tick();
        }
        assert!(challenge.is_expired());
        assert!(challenge.can_resend());
    }

    #[test]
    fn zero_ttl_is_expired_and_resendable_immediately() {
        let challenge = CodeChallenge::new(0, false);
        assert!(challenge.is_expired());
        assert!(challenge.can_resend());
        assert!(challenge.is_idle());
    }

    #[test]
    fn replacing_the_challenge_restarts_both_timers_and_notice() {
        let mut challenge = CodeChallenge::new(300, false);
        for _ in 0..100 {
            challenge.tick();
        }
        challenge = CodeChallenge::new(300, true);
        assert_eq!(challenge.ttl_remaining, 300);
        assert_eq!(challenge.resend_remaining, RESEND_COOLDOWN_SECS);
        assert!(challenge.will_replace_devices);
    }

    #[test]
    fn restored_challenge_subtracts_elapsed_time_from_both_timers() {
        let challenge = CodeChallenge::after(600, 45);
        assert_eq!(challenge.ttl_remaining, 555);
        assert_eq!(challenge.resend_remaining, RESEND_COOLDOWN_SECS - 45);
        let cooled = CodeChallenge::after(600, 90);
        assert!(cooled.can_resend());
        assert!(!cooled.is_expired());
        assert!(CodeChallenge::after(600, 600).is_idle());
    }

    #[test]
    fn sent_codes_restore_only_the_recorded_flow() {
        let mut codes = SentCodes::default();
        codes.record("email-old:u1".to_owned(), 600);
        let restored = codes.restore("email-old:u1").expect("recorded code");
        assert!(!restored.can_resend());
        assert!(codes.restore("email-old:u2").is_none());
        codes.record("expired".to_owned(), 0);
        assert!(codes.restore("expired").is_none());
    }
}
