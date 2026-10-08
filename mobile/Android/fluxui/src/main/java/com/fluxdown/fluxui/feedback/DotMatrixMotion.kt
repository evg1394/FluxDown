package com.fluxdown.fluxui.feedback

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import com.fluxdown.fluxui.theme.FluxHaptics
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.floor

internal const val K_OFF = 0
internal const val K_ON = 1
internal const val K_MID = 2
internal const val K_HI = 3

internal fun dotKind(ch: Char): Int = when (ch) {
    '#' -> K_ON
    'o' -> K_HI
    '+' -> K_MID
    else -> K_OFF
}

/** 亮度权重：亮 / 强调 1，半亮 .45，灭 0。扫光、透镜、涟漪按它在“提亮”与“微染强调色”之间分配增益。 */
internal fun dotLit(k: Int): Float = when (k) {
    K_ON, K_HI -> 1f
    K_MID -> 0.45f
    else -> 0f
}

/** 时间轴（ms，相对帧时钟）。所有阶段都有确定终点：终点帧的绘制结果与静止态逐点相同，帧循环据此休眠。 */
internal object DotTimeline {
    /** 入场：灭点自中心向外铺开，亮点再晚一拍按同样次序回弹“落笔”。 */
    const val INTRO_SPREAD_MS = 380f
    const val INTRO_LIT_DELAY_MS = 180f
    const val INTRO_DOT_MS = 440f
    const val INTRO_END_MS = 1_000L // LIT_DELAY + SPREAD + DOT

    /** 呼吸式扫光：入场后静置 0.9 s 首扫，之后每 6.4 s 扫一次、每次 1.8 s；其余时间完全静止（防视觉疲劳）。 */
    const val SWEEP_FIRST_REST_MS = 900L
    const val SWEEP_MS = 1_800L
    const val CYCLE_MS = 6_400L
    const val SWEEP_HALF_WIDTH = 0.24f

    /** 字形切换：变化的点自左向右错相收缩 → 换形 → 回弹。 */
    const val MORPH_COL_SPREAD_MS = 260f
    const val MORPH_ROW_SPREAD_MS = 60f
    const val MORPH_DOT_MS = 380f
    const val MORPH_END_MS = 700L // COL + ROW + DOT

    /** 按压透镜淡入 / 抬手淡出；轻点涟漪。 */
    const val PRESS_IN_MS = 140f
    const val PRESS_OUT_MS = 360f
    const val RIPPLE_MS = 760f
    const val MAX_RIPPLES = 3

    /** 透镜半径（以点距计）。 */
    const val LENS_STEPS = 3.2f
}

/** 时间戳占位：事件发生在帧间，下一帧回调时落到帧时钟上，保证所有阶段与绘制同一时基。 */
internal const val PENDING = -1L
internal const val NEVER = Long.MIN_VALUE

/** [start] 到 [now] 的已逝毫秒；[NEVER] = 早已结束，[PENDING] = 本帧刚开始。 */
internal fun elapsed(start: Long, now: Long): Long = when (start) {
    NEVER -> Long.MAX_VALUE
    PENDING -> 0L
    else -> now - start
}

internal fun easeOutCubic(x: Float): Float {
    val y = 1f - x
    return 1f - y * y * y
}

internal fun easeInOutCubic(x: Float): Float = if (x < 0.5f) {
    4f * x * x * x
} else {
    val y = -2f * x + 2f
    1f - y * y * y / 2f
}

/** 回弹缓出：0 → ≈1.1 → 1。 */
internal fun backOut(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val y = x - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}

/** 扫光进度 0..1；不在扫光窗口返回 NaN。[sinceIntro] = 入场起点至今。 */
internal fun sweepProgress(sinceIntro: Long): Float {
    val t = sinceIntro - DotTimeline.INTRO_END_MS - DotTimeline.SWEEP_FIRST_REST_MS
    if (t < 0L) return Float.NaN
    val phase = t % DotTimeline.CYCLE_MS
    if (phase >= DotTimeline.SWEEP_MS) return Float.NaN
    return phase.toFloat() / DotTimeline.SWEEP_MS
}

/** 对角坐标 [u] ∈ [0,1] 处的扫光强度：紧支撑 smoothstep 光带，进度端点处全场恰为 0。 */
internal fun sweepBand(u: Float, progress: Float): Float {
    if (progress.isNaN()) return 0f
    val w = DotTimeline.SWEEP_HALF_WIDTH
    val center = -w + (1f + 2f * w) * easeInOutCubic(progress)
    val x = 1f - abs(u - center) / w
    if (x <= 0f) return 0f
    return x * x * (3f - 2f * x)
}

/**
 * 点阵动画状态：帧时钟、当前 / 上一字形、触摸透镜与涟漪。
 *
 * 只有 [clock]、[target]、[previous] 是快照状态；其余字段由手势与帧回调在主线程写、绘制阶段读，
 * 帧时钟推进即触发重绘。帧循环在全部阶段静止时休眠到下一次扫光，触摸经 [wake] 唤醒。
 */
@Stable
internal class DotMatrixState(initial: List<String>) {
    val clock = mutableLongStateOf(0L)
    var target: List<String> by mutableStateOf(initial)
        private set
    var previous: List<String>? by mutableStateOf(null)
        private set

    var introStart = 0L
        private set
    var morphStart = NEVER
        private set

    var touchX = 0f
        private set
    var touchY = 0f
        private set
    private var pressed = false
    private var pressAt = NEVER
    private var releaseAt = NEVER
    private var releaseLevel = 0f
    private var hover = -1

    val rippleAt = LongArray(DotTimeline.MAX_RIPPLES) { NEVER }
    val rippleX = FloatArray(DotTimeline.MAX_RIPPLES)
    val rippleY = FloatArray(DotTimeline.MAX_RIPPLES)
    private var rippleNext = 0

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** 换字形：同尺寸且动画开启时逐点形变，否则（尺寸变化）重放入场。 */
    fun retarget(rows: List<String>, live: Boolean) {
        if (rows == target) return
        val sameShape = rows.size == target.size && columns(rows) == columns(target)
        if (live && sameShape) {
            previous = target
            morphStart = PENDING
        } else {
            previous = null
            morphStart = NEVER
            if (live) introStart = PENDING
        }
        target = rows
        wake.trySend(Unit)
    }

    fun press(x: Float, y: Float, cell: Int) {
        pressed = true
        touchX = x
        touchY = y
        pressAt = PENDING
        releaseAt = NEVER
        hover = cell
        wake.trySend(Unit)
    }

    /** 跟随手指；滑入一个新的亮点时返回 true（供触感刻度）。 */
    fun move(x: Float, y: Float, cell: Int): Boolean {
        touchX = x
        touchY = y
        if (cell == hover) return false
        hover = cell
        return cell >= 0 && isLit(cell)
    }

    /** 抬手 / 手势被父级滚动接管。[ripple] = 轻点（未越过 touch slop）时自抬手点放一圈涟漪。幂等。 */
    fun release(ripple: Boolean) {
        if (!pressed) return
        pressed = false
        releaseAt = PENDING
        hover = -1
        if (ripple) {
            rippleAt[rippleNext] = PENDING
            rippleX[rippleNext] = touchX
            rippleY[rippleNext] = touchY
            rippleNext = (rippleNext + 1) % DotTimeline.MAX_RIPPLES
        }
        wake.trySend(Unit)
    }

    /** 帧回调：推进时钟并把帧间事件的 [PENDING] 时间戳落到本帧。 */
    fun onFrame(now: Long) {
        clock.longValue = now
        if (introStart == PENDING) introStart = now
        if (morphStart == PENDING) morphStart = now
        if (pressAt == PENDING) pressAt = now
        if (releaseAt == PENDING) {
            releaseLevel = pressLevel(now)
            releaseAt = now
        }
        for (i in rippleAt.indices) {
            if (rippleAt[i] == PENDING) rippleAt[i] = now
        }
    }

    /** 透镜强度 0..1：按压时缓入，抬手后自抬手时的强度缓出到 0。 */
    fun lens(now: Long): Float = when {
        pressed || releaseAt == PENDING -> pressLevel(now)
        releaseAt == NEVER -> 0f
        else -> {
            val p = (elapsed(releaseAt, now) / DotTimeline.PRESS_OUT_MS).coerceIn(0f, 1f)
            releaseLevel * (1f - easeOutCubic(p))
        }
    }

    /** 第 [i] 圈涟漪进度；未激活或已结束返回 -1。 */
    fun rippleProgress(i: Int, now: Long): Float {
        val p = elapsed(rippleAt[i], now) / DotTimeline.RIPPLE_MS
        return if (p >= 1f) -1f else p
    }

    /** 下一次画面会变化的时刻；= [now] 表示需要逐帧。 */
    fun nextChangeAt(now: Long): Long {
        if (introStart == PENDING || morphStart == PENDING || pressAt == PENDING || releaseAt == PENDING) return now
        if (now - introStart < DotTimeline.INTRO_END_MS) return now
        if (elapsed(morphStart, now) < DotTimeline.MORPH_END_MS) return now
        if (pressed || lens(now) > 0f) return now
        for (i in rippleAt.indices) {
            if (rippleProgress(i, now) >= 0f) return now
        }
        val firstSweep = introStart + DotTimeline.INTRO_END_MS + DotTimeline.SWEEP_FIRST_REST_MS
        if (now < firstSweep) return firstSweep
        val phase = (now - firstSweep) % DotTimeline.CYCLE_MS
        return if (phase < DotTimeline.SWEEP_MS) now else now + (DotTimeline.CYCLE_MS - phase)
    }

    /** 帧循环：以首帧为 0；静止阶段休眠到 [nextChangeAt]，触摸 / 换字形即时唤醒。 */
    suspend fun runClock() {
        val t0 = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it - t0 }
            onFrame(now)
            val next = nextChangeAt(now)
            if (next > now) withTimeoutOrNull(next - now) { wake.receive() }
        }
    }

    /** 坐标所在格（行优先下标），出界 -1。 */
    fun cellAt(x: Float, y: Float, step: Float): Int {
        val rows = target
        val cols = columns(rows)
        if (x < 0f || y < 0f || step <= 0f) return -1
        val col = floor(x / step).toInt()
        val row = floor(y / step).toInt()
        if (col >= cols || row >= rows.size) return -1
        return row * cols + col
    }

    private fun isLit(cell: Int): Boolean {
        val rows = target
        val cols = columns(rows)
        if (cols == 0) return false
        val ch = rows.getOrNull(cell / cols)?.getOrNull(cell % cols) ?: return false
        return dotLit(dotKind(ch)) > 0f
    }

    private fun pressLevel(now: Long): Float {
        if (pressAt == NEVER) return 0f
        return easeOutCubic((elapsed(pressAt, now) / DotTimeline.PRESS_IN_MS).coerceIn(0f, 1f))
    }
}

internal fun columns(rows: List<String>): Int = rows.maxOfOrNull { it.length } ?: 0

/**
 * 点阵触摸：按下即出透镜（附近点放大、外推、染强调色），拖过亮点给 frequent 刻度；轻点抬手放涟漪 + tick。
 * 不消费事件——父级滚动越过 slop 后（Final 阶段可见已消费）透镜立即收起，不抢列表滚动。
 */
internal suspend fun PointerInputScope.trackDotMatrixTouch(
    state: DotMatrixState,
    haptics: FluxHaptics,
    step: Float,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        state.press(down.position.x, down.position.y, state.cellAt(down.position.x, down.position.y, step))
        var tap = true
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
                if (change == null || change.isConsumed) {
                    tap = false
                    break
                }
                if (!change.pressed) break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
                val x = change.position.x
                val y = change.position.y
                if (state.move(x, y, state.cellAt(x, y, step))) haptics.frequent()
            }
            if (tap) haptics.tick()
            state.release(ripple = tap)
        } finally {
            state.release(ripple = false)
        }
    }
}
