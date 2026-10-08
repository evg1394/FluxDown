package com.fluxdown.fluxui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧循环只在画面完全静止时休眠：休眠窗口内扫光、透镜、涟漪必须全为 0，
 * 否则动画会冻结在半途（下一次唤醒前停在中间帧）。
 */
class DotMatrixStateTest {
    private val glyph = FluxGlyph.Search.rows

    private fun assertSettledWhenSleeping(state: DotMatrixState, from: Long, to: Long) {
        var now = from
        while (now <= to) {
            state.onFrame(now)
            if (state.nextChangeAt(now) > now) {
                assertEquals("lens @$now", 0f, state.lens(now))
                for (j in 0 until DotTimeline.MAX_RIPPLES) assertTrue("ripple $j @$now", state.rippleProgress(j, now) < 0f)
                val sweep = sweepProgress(elapsed(state.introStart, now))
                for (k in 0..20) assertEquals("sweep u=${k / 20f} @$now", 0f, sweepBand(k / 20f, sweep))
            }
            now += 4
        }
    }

    @Test
    fun idleSleepsOnlyBetweenSweeps() {
        val state = DotMatrixState(glyph)
        assertSettledWhenSleeping(state, 0, DotTimeline.CYCLE_MS * 3)
        // 进入静止段后确实会休眠到下一次扫光，而不是逐帧空转。
        val restAt = DotTimeline.INTRO_END_MS + DotTimeline.SWEEP_FIRST_REST_MS + DotTimeline.SWEEP_MS + 10
        state.onFrame(restAt)
        assertEquals(
            DotTimeline.INTRO_END_MS + DotTimeline.SWEEP_FIRST_REST_MS + DotTimeline.CYCLE_MS,
            state.nextChangeAt(restAt),
        )
    }

    @Test
    fun tapKeepsFramesUntilLensAndRippleFinish() {
        val state = DotMatrixState(glyph)
        // 选在首次扫光结束后的静止段，结束判断不受扫光干扰。
        val start = DotTimeline.INTRO_END_MS + DotTimeline.SWEEP_FIRST_REST_MS + DotTimeline.SWEEP_MS + 100
        state.onFrame(start)
        state.press(10f, 10f, 0)
        state.onFrame(start + 16)
        state.release(ripple = true)
        state.onFrame(start + 80)
        assertTrue(state.lens(start + 80) > 0f)
        assertEquals(start + 96, state.nextChangeAt(start + 96))
        assertSettledWhenSleeping(state, start + 80, start + 80 + DotTimeline.RIPPLE_MS.toLong() + 200)
        assertTrue(state.nextChangeAt(start + 80 + DotTimeline.RIPPLE_MS.toLong() + 1) > start + 80 + DotTimeline.RIPPLE_MS.toLong() + 1)
    }

    @Test
    fun retargetMorphsSameShapeAndReplaysIntroOtherwise() {
        val state = DotMatrixState(glyph)
        state.onFrame(5_000)
        state.retarget(FluxGlyph.Inbox.rows, live = true)
        assertEquals(glyph, state.previous)
        state.onFrame(5_016)
        assertEquals(5_016L, state.morphStart)
        assertEquals(5_016L, state.nextChangeAt(5_016))

        state.retarget(DotGlyphs.text("12"), live = true)
        assertNull(state.previous)
        state.onFrame(6_000)
        assertEquals(6_000L, state.introStart)
    }
}
