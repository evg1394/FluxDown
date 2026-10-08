package com.fluxdown.fluxui.theme

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 圆角刻度 32 / 24 / 16 / 12 / 全圆，固定不随主题变化；派生值按同心圆角规则（外 − 内边距）。 */
@Immutable
class FluxShapes(
    val sheet: Shape = RoundedCornerShape(32.dp),
    val card: Shape = RoundedCornerShape(24.dp),
    val menu: Shape = RoundedCornerShape(22.dp),
    val control: Shape = RoundedCornerShape(16.dp),
    val tile: Shape = RoundedCornerShape(12.dp),
    val tileSm: Shape = RoundedCornerShape(10.dp),
    val tileLg: Shape = RoundedCornerShape(17.dp),
    val full: Shape = CircleShape,
)

/** 4dp 栅格。布局层只用 4 的倍数；2 / 6 / 10 / 14 仅用于控件内部光学对齐。 */
@Immutable
class FluxSpace(
    val s05: Dp = 2.dp,
    val s1: Dp = 4.dp,
    val s1_5: Dp = 6.dp,
    val s2: Dp = 8.dp,
    val s2_5: Dp = 10.dp,
    val s3: Dp = 12.dp,
    val s3_5: Dp = 14.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 20.dp,
    val s6: Dp = 24.dp,
    val s8: Dp = 32.dp,
    /** compact 16dp；medium / expanded 20dp（由 FluxTheme 按窗口宽度注入）。 */
    val screenMargin: Dp = 16.dp,
    val touch: Dp = 48.dp,
    /** 导航坞高 64 + 底距 26 + 气隙 22。 */
    val dockHeight: Dp = 64.dp,
    /** 有坞页面内容底部留白（dock-h 112 + 24）。 */
    val dockClearance: Dp = 136.dp,
    /** 推入页（无坞）内容底部留白。 */
    val pageClearance: Dp = 56.dp,
)

/** 弹簧令牌：只用弹簧，不写时长曲线（§8.1）。 */
@Immutable
data class FluxSpring(val stiffness: Float, val damping: Float) {
    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> = spring(damping, stiffness, visibilityThreshold)
}

@Immutable
class FluxMotion(val reduce: Boolean) {
    val snap = FluxSpring(900f, 0.82f)
    val fluid = FluxSpring(380f, 0.78f)
    val soft = FluxSpring(200f, 0.90f)
    val liquid = FluxSpring(520f, 0.66f)
    val flow = FluxSpring(140f, 1.00f)
    val press = FluxSpring(1400f, 0.60f)

    /** 一切动画规格经此取得：Reduce motion 时一律瞬时。 */
    fun <T> of(s: FluxSpring, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        if (reduce) snap() else s.spec(visibilityThreshold)

    val flowStaggerMs = 38L
    val flowStaggerMax = 12
    val flowTranslate = 8.dp
    val flowBlur = 8.dp
    val longPressMs = 420L
    val orbHoldMs = 380L
}

/** 触感映射（§9）：直接取 View 常量，遵守系统触摸反馈总开关，无 App 内开关。 */
@Stable
class FluxHaptics(private val view: View) {
    private var lastFrequent = 0L

    fun tick() = fire(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = fire(HapticFeedbackConstants.CONFIRM)
    fun reject() = fire(HapticFeedbackConstants.REJECT)
    fun longPress() = fire(HapticFeedbackConstants.LONG_PRESS)
    fun click() = fire(HapticFeedbackConstants.VIRTUAL_KEY)
    fun gestureStart() = fire(HapticFeedbackConstants.GESTURE_START)
    fun gestureEnd() = fire(HapticFeedbackConstants.GESTURE_END)

    /** 连续拖动刻度，限频 ≥ 40ms。 */
    fun frequent() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrequent < 40) return
        lastFrequent = now
        fire(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun fire(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

/** 材质分级回退：省电 / 过热 / 低内存时 Real 玻璃退化为实色（信息结构一致）。 */
enum class FluxGlassMode { Blur, Solid }

@Immutable
data class FluxPerf(val glassMode: FluxGlassMode)

/** 窗口尺寸档（§13.1）：compact < 600dp ≤ medium < 840dp ≤ expanded。 */
enum class FluxWindowClass { Compact, Medium, Expanded }
