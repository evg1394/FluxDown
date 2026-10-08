package com.fluxdown.fluxui.data

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.theme.FluxScaleGroup
import com.fluxdown.fluxui.theme.FluxTheme

/**
 * 倒计时环（X1–X3 自动默认项 / V5 入站配对）：默认 44dp，半径 size/2−3，描边 2.5dp，轨道 hairlineStrong，
 * 弧 accentHi + 辉光（[warn] 时转 amber，色彩以 fluid 弹簧过渡），中心 mono 13/600 剩余秒数。
 *
 * - [remainingFraction] 在绘制阶段读取（0..1，1 = 满环）：由调用方按“当前时间重新计算”逐帧或逐秒提供，
 *   本组件不做补间也不累减。
 * - [warn]（剩余 ≤ 5s）时每秒（[seconds] 变化时）触发一次 tick 触感。
 * - 无障碍：平时不朗读；[warn] 且给出 [liveDescription]（如“4 秒后自动选择默认项”）时作为 Polite liveRegion 播报。
 */
@Composable
fun CountdownRing(
    remainingFraction: () -> Float,
    seconds: Int,
    warn: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    liveDescription: String? = null,
) {
    val colors = FluxTheme.colors
    val type = FluxTheme.type
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val arcColor = animateColorAsState(if (warn) colors.amber else colors.accentHi, motion.of(motion.fluid), label = "cdArc")
    val glowColor = animateColorAsState(
        (if (warn) colors.amber else colors.accentGlow).let { it.copy(alpha = if (warn) 0.5f else it.alpha) },
        motion.of(motion.fluid),
        label = "cdGlow",
    )
    val textStyle = remember(type, colors, warn) {
        type.sized(type.weight(type.mono, 600, mono = true), 13f, FluxScaleGroup.Ks)
            .copy(color = if (warn) colors.amberText else colors.ink, textAlign = TextAlign.Center)
    }
    LaunchedEffect(seconds, warn) {
        if (warn && seconds > 0) haptics.tick()
    }
    val latest = rememberUpdatedState(remainingFraction)

    Box(
        modifier
            .size(size)
            .clearAndSetSemantics {
                if (warn && liveDescription != null) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = liveDescription
                }
            }
            .drawWithCache {
                val sw = 2.5.dp.toPx()
                val r = this.size.width / 2f - 3.dp.toPx()
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val topLeft = Offset(center.x - r, center.y - r)
                val arcSize = Size(2f * r, 2f * r)
                val trackStroke = Stroke(sw)
                val arcStroke = Stroke(sw, cap = StrokeCap.Round)
                val glowA = Stroke(sw + 4.dp.toPx(), cap = StrokeCap.Round)
                val glowB = Stroke(sw + 2.dp.toPx(), cap = StrokeCap.Round)
                onDrawBehind {
                    drawCircle(colors.hairlineStrong, r, center, style = trackStroke)
                    val sweep = 360f * latest.value().coerceIn(0f, 1f)
                    if (sweep > 0.5f) {
                        val g = glowColor.value
                        drawArc(g.copy(alpha = g.alpha * 0.4f), -90f, sweep, false, topLeft, arcSize, style = glowA)
                        drawArc(g.copy(alpha = g.alpha * 0.6f), -90f, sweep, false, topLeft, arcSize, style = glowB)
                        drawArc(arcColor.value, -90f, sweep, false, topLeft, arcSize, style = arcStroke)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(seconds.toString(), style = textStyle, maxLines = 1)
    }
}
