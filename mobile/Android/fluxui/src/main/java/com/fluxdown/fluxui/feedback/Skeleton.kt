package com.fluxdown.fluxui.feedback

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.theme.FluxTheme
import kotlin.math.PI
import kotlin.math.cos

/** 1.6 s 线性相位 0..1；Reduce motion 时返回 null（静态）。 */
@Composable
private fun rememberShimmerPhase(): State<Float>? {
    if (FluxTheme.motion.reduce) return null
    return rememberInfiniteTransition(label = "fluxShimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "fluxShimmerPhase",
    )
}

/**
 * 闪烁填充（§12.41）：`glass2 ↔ glass3` 纯色呼吸脉冲（无渐变），1.6 s 一个周期（余弦缓动，首尾无缝）。
 * Reduce motion：取 `glass2` 实色。相位在绘制阶段读取，不触发重组。[shape] 用于裁剪。
 */
@Composable
fun Modifier.fluxShimmer(shape: Shape = RectangleShape): Modifier = shimmer(shape, rememberShimmerPhase())

@Composable
private fun Modifier.shimmer(shape: Shape, phase: State<Float>?): Modifier {
    val c = FluxTheme.colors
    val g2 = c.glass2
    val g3 = c.glass3
    return this.clip(shape).drawBehind {
        val t = if (phase == null) 0f else 0.5f - 0.5f * cos(2f * PI.toFloat() * phase.value)
        drawRect(lerp(g2, g3, t))
    }
}

/**
 * 骨架块：闪烁的占位矩形（默认 `tile` r12；文本线条请传 `RoundedCornerShape(8.dp)` 并指定高度）。
 * 无语义（装饰），加载态由容器 `stateDescription` 表达。
 */
@Composable
fun FluxSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = FluxTheme.shapes.tile,
) {
    Spacer(modifier.shimmer(shape, rememberShimmerPhase()))
}

/**
 * 列表骨架行 ×[rows]（§12.41）：40×40 r12 方块 + 两条线（12dp×70 %、9dp×45 %，r8），行 padding 16、gap 12。
 * 一组共用一个相位。放在 GlassSection 内（行间发丝线由容器负责）。
 * TalkBack：整体 `stateDescription = `[loadingLabel]，子项无语义。
 */
@Composable
fun FluxSkeletonRows(
    rows: Int = 3,
    modifier: Modifier = Modifier,
    loadingLabel: String = "加载中",
) {
    val phase = rememberShimmerPhase()
    val line = RoundedCornerShape(8.dp)
    Column(modifier.clearAndSetSemantics { stateDescription = loadingLabel }) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.size(40.dp).shimmer(FluxTheme.shapes.tile, phase))
                Column(Modifier.weight(1f)) {
                    Spacer(Modifier.height(4.dp))
                    Spacer(Modifier.fillMaxWidth(0.70f).height(12.dp).shimmer(line, phase))
                    Spacer(Modifier.height(10.dp))
                    Spacer(Modifier.fillMaxWidth(0.45f).height(9.dp).shimmer(line, phase))
                }
            }
        }
    }
}

/**
 * 加载圈（§12.41，原型 `ui.loading`）：底圈 ink@18 % 2dp（24 视窗）+ accentHi 90° 圆端弧，1 s 线性旋转。
 * Reduce motion：停在 90° 弧。语义：indeterminate progressbar +[label]。
 */
@Composable
fun FluxSpinner(
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
    label: String = "加载中",
) {
    val c = FluxTheme.colors
    val track = c.ink.copy(alpha = 0.18f)
    val arc = c.accentHi
    val phase = if (FluxTheme.motion.reduce) null else rememberInfiniteTransition(label = "fluxSpinner").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "fluxSpinnerPhase",
    )
    Spacer(
        modifier
            .size(size)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }
            .drawWithCache {
                val u = this.size.width / 24f
                val sw = 2f * u
                val topLeft = Offset(3f * u, 3f * u)
                val arcSize = Size(18f * u, 18f * u)
                val ring = Stroke(width = sw)
                val cap = Stroke(width = sw, cap = StrokeCap.Round)
                onDrawBehind {
                    drawCircle(track, radius = 9f * u, style = ring)
                    rotate((phase?.value ?: 0f) * 360f, pivot = center) {
                        drawArc(arc, startAngle = -90f, sweepAngle = 90f, useCenter = false, topLeft = topLeft, size = arcSize, style = cap)
                    }
                }
            },
    )
}
