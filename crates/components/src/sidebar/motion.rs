use std::time::{Duration, Instant};

use gpui_component::animation::ease_in_out_cubic;

const DURATION: Duration = Duration::from_millis(200);

/// 侧栏宽度与透明度共用的展开量；反向切换从当前帧继续，不跳到端点。
pub(crate) struct SidebarMotion {
    from: f32,
    to: f32,
    started_at: Option<Instant>,
}

impl SidebarMotion {
    pub(crate) fn settled(open: bool) -> Self {
        let amount = if open { 1. } else { 0. };
        Self {
            from: amount,
            to: amount,
            started_at: None,
        }
    }

    pub(crate) fn retarget(&mut self, open: bool, now: Instant, animate: bool) {
        let target = if open { 1. } else { 0. };
        if !animate {
            *self = Self::settled(open);
        } else if self.to != target {
            self.from = self.amount(now);
            self.to = target;
            self.started_at = Some(now);
        }
    }

    pub(crate) fn amount(&self, now: Instant) -> f32 {
        let progress = self.progress(now);
        self.from + (self.to - self.from) * ease_in_out_cubic(progress)
    }

    pub(crate) fn is_animating(&self, now: Instant) -> bool {
        self.from != self.to && self.progress(now) < 1.
    }

    fn progress(&self, now: Instant) -> f32 {
        self.started_at.map_or(1., |started_at| {
            (now.saturating_duration_since(started_at).as_secs_f32() / DURATION.as_secs_f32())
                .clamp(0., 1.)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::{DURATION, SidebarMotion};
    use std::time::Instant;

    #[test]
    fn reversing_and_repeated_renders_preserve_current_frame() {
        let start = Instant::now();
        let mut motion = SidebarMotion::settled(true);
        motion.retarget(false, start, true);
        let halfway = start + DURATION / 2;
        let amount = motion.amount(halfway);
        assert!((amount - 0.5).abs() < f32::EPSILON);
        motion.retarget(false, halfway, true);
        assert_eq!(motion.amount(halfway), amount);
        assert_eq!(motion.amount(start + DURATION), 0.);
        motion.retarget(true, halfway, true);
        assert_eq!(motion.amount(halfway), amount);
        assert!(motion.amount(halfway + DURATION / 2) > amount);
        assert_eq!(motion.amount(halfway + DURATION), 1.);
        assert!(!motion.is_animating(halfway + DURATION));
    }

    #[test]
    fn hidden_sections_or_reduced_motion_cancel_inflight_transition() {
        let start = Instant::now();
        let mut motion = SidebarMotion::settled(true);
        motion.retarget(false, start, true);
        motion.retarget(false, start + DURATION / 2, false);
        assert_eq!(motion.amount(start + DURATION / 2), 0.);
        assert!(!motion.is_animating(start + DURATION / 2));
        motion.retarget(true, start + DURATION, false);
        assert_eq!(motion.amount(start + DURATION), 1.);
        assert!(!motion.is_animating(start + DURATION));
    }
}
