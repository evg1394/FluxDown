package com.fluxdown.fluxui.data

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.theme.FluxTheme
import kotlin.math.max

/**
 * 流带的一段：[fraction] = 该段占总字节的份额（按真实字节区间，不要求和为 1，内部归一化），
 * [filled] = 该段已下载比例 0..1，[active] = 该段正有连接在写入（仅 [FlowStripState.Downloading] 下生效）。
 */
@Immutable
data class FlowSegmentUi(val fraction: Float, val filled: Float, val active: Boolean = false) {
    companion object {
        /**
         * 任务组的“成员伪段”：每个成员一段、长度均等，`filled` = 成员进度，`active` = 成员正在下载。
         */
        fun members(progress: List<Float>, active: List<Boolean>): List<FlowSegmentUi> =
            List(progress.size) { i -> FlowSegmentUi(1f, progress[i].coerceIn(0f, 1f), active.getOrElse(i) { false }) }
    }
}

/** 流带状态（§2.6 / §12.9）。Queued 与 Pending 同为虚线轨道，Preparing 为纯色短段往返滑动。 */
enum class FlowStripState { Downloading, Paused, Failed, Completed, Seeding, Queued, Pending, Preparing, Missing }

/** 流带高度档：xs 2 / 默认 4 / lg 8（详情页英雄）。 */
object FlowStripHeight {
    val Xs: Dp = 2.dp
    val Regular: Dp = 4.dp
    val Lg: Dp = 8.dp
}

private val PrepEasing = CubicBezierEasing(0.45f, 0f, 0.15f, 1f)

/** 装饰性动画开关：Reduce motion 或 TalkBack 触摸探索开启时停用（§10.8）。 */
@Composable
internal fun rememberDecorativeMotion(): Boolean {
    val reduce = FluxTheme.motion.reduce
    val ctx = LocalContext.current
    val touchExploration = remember(ctx) {
        ctx.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    return !reduce && !touchExploration
}

/**
 * 流带：按真实字节区间着色的分段条。
 *
 * - 各段按 [FlowSegmentUi.fraction] 等比分宽，段间 1dp 缝；[segments] 为空时画一条空轨道。
 * - 活跃段：accent 纯色（无渐变、无光束、无辉光）；其余段按状态着 done / paused / fail / seed 纯色。
 * - 单 Canvas 绘制；动画值只在绘制阶段读取，不触发重组。
 * - 无障碍：以整体进度的 `progressBarRangeInfo` 暴露，不逐段朗读；[semanticsLabel] 可选作 stateDescription（如“已下载 42%”）。
 */
@Composable
fun FlowStrip(
    segments: List<FlowSegmentUi>,
    state: FlowStripState,
    modifier: Modifier = Modifier,
    height: Dp = FlowStripHeight.Regular,
    semanticsLabel: String? = null,
) {
    val colors = FluxTheme.colors
    val decor = rememberDecorativeMotion()
    val phase: State<Float>? = when {
        !decor -> null
        state == FlowStripState.Preparing -> rememberInfiniteTransition(label = "flowPrep")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = PrepEasing), RepeatMode.Restart), label = "prep")
        else -> null
    }
    val segState = rememberUpdatedState(segments)
    val stateState = rememberUpdatedState(state)
    val progress = remember(segments, state) { overallProgress(segments, state) }

    Spacer(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                if (semanticsLabel != null) stateDescription = semanticsLabel
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val c = FlowCache(
                    w = w,
                    h = h,
                    gap = 1.dp.toPx(),
                    clip = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(h / 2f))) },
                    dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                    remain = colors.flowRemain,
                    done = colors.flowDone,
                    donePaused = colors.flowDone.copy(alpha = colors.flowDone.alpha * 0.62f),
                    fail = colors.flowFail,
                    seed = colors.mint.copy(alpha = 0.82f),
                    active = colors.accent,
                    prep = colors.accentHi.copy(alpha = 0.8f),
                )
                onDrawBehind { drawFlow(c, segState.value, stateState.value, phase?.value) }
            },
    )
}

private class FlowCache(
    val w: Float,
    val h: Float,
    val gap: Float,
    val clip: Path,
    val dash: PathEffect,
    val remain: Color,
    val done: Color,
    val donePaused: Color,
    val fail: Color,
    val seed: Color,
    val active: Color,
    val prep: Color,
)

private fun overallProgress(segments: List<FlowSegmentUi>, state: FlowStripState): Float {
    if (state == FlowStripState.Completed || state == FlowStripState.Seeding || state == FlowStripState.Missing) return 1f
    var total = 0f
    var done = 0f
    for (i in segments.indices) {
        val s = segments[i]
        total += s.fraction
        done += s.fraction * s.filled.coerceIn(0f, 1f)
    }
    return if (total <= 0f) 0f else (done / total).coerceIn(0f, 1f)
}

private fun DrawScope.drawFlow(c: FlowCache, segs: List<FlowSegmentUi>, st: FlowStripState, phase: Float?) {
    val n = segs.size
    val h = c.h
    val queued = st == FlowStripState.Queued || st == FlowStripState.Pending
    val downloading = st == FlowStripState.Downloading
    val doneColor = when (st) {
        FlowStripState.Paused -> c.donePaused
        FlowStripState.Failed -> c.fail
        FlowStripState.Seeding -> c.seed
        else -> c.done
    }
    val forceFull = st == FlowStripState.Completed || st == FlowStripState.Seeding || st == FlowStripState.Missing

    var total = 0f
    for (i in 0 until n) total += segs[i].fraction
    if (total <= 0f) total = 1f
    val usable = (c.w - c.gap * (n - 1)).coerceAtLeast(0f)

    clipPath(c.clip) {
        if (n == 0) {
            drawTrack(c, 0f, c.w, queued)
        } else {
            var x = 0f
            for (i in 0 until n) {
                val s = segs[i]
                val sw = max(1f, usable * s.fraction / total)
                drawTrack(c, x, sw, queued)
                val f = if (forceFull) 1f else s.filled.coerceIn(0f, 1f)
                val fw = sw * f
                val active = downloading && s.active
                if (fw > 0f) {
                    drawRect(if (active) c.active else doneColor, Offset(x, 0f), Size(fw, h))
                }
                x += sw + c.gap
            }
        }
        if (st == FlowStripState.Preparing && phase != null) {
            val bw = c.w * 0.4f
            drawRect(c.prep, Offset(-bw + phase * c.w * 1.44f, 0f), Size(bw, h))
        }
    }
}

private fun DrawScope.drawTrack(c: FlowCache, x: Float, w: Float, dashed: Boolean) {
    if (dashed) {
        drawLine(c.remain, Offset(x, c.h / 2f), Offset(x + w, c.h / 2f), strokeWidth = c.h, cap = StrokeCap.Butt, pathEffect = c.dash)
    } else {
        drawRect(c.remain, Offset(x, 0f), Size(w, c.h))
    }
}
