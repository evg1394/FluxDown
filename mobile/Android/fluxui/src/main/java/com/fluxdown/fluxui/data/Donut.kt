package com.fluxdown.fluxui.data

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fluxdown.fluxui.theme.FluxTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 环形图的一个分段。颜色只应取受限集合（`accentHi / inkMuted / inkFaint / mint / amber`，不新增色相），
 * 含义由图例文字承载（色盲不依赖颜色）。
 */
@Immutable
data class DonutSlice(val value: Float, val color: Color, val label: String)

/**
 * 环形占比图（来源构成 / 磁盘占用）：默认 140dp，环半径 size/2−9，描边 8dp，圆端，12 点钟顺时针，
 * 多于一段时段间留 3dp 视觉间隙；轨道 flowRemain；首次出现按 soft 弹簧顺时针展开（Reduce motion 时直接到位）。
 *
 * @param centerText 中心主文案（mono，字号 size×0.14，600）。
 * @param sub 中心下方说明（micro inkMuted）。
 * @param contentDescription 缺省拼接各段“标签 占比%”。
 */
@Composable
fun Donut(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    centerText: String = "",
    sub: String? = null,
    contentDescription: String? = null,
) {
    val colors = FluxTheme.colors
    val type = FluxTheme.type
    val motion = FluxTheme.motion
    val reveal = remember { Animatable(if (motion.reduce) 1f else 0f) }
    LaunchedEffect(motion) {
        if (motion.reduce) reveal.snapTo(1f) else reveal.animateTo(1f, motion.of(motion.soft))
    }
    val cd = contentDescription ?: remember(slices) {
        val total = slices.sumOf { it.value.coerceAtLeast(0f).toDouble() }.toFloat()
        if (total <= 0f) "" else slices.filter { it.value > 0f }
            .joinToString(", ") { "${it.label} ${(it.value / total * 100f).roundToInt()}%" }
    }
    val centerStyle = remember(type, colors, size) {
        type.weight(type.mono, 600, mono = true)
            .copy(color = colors.ink, fontSize = (size.value * 0.14f).sp, letterSpacing = (-0.02).em, textAlign = TextAlign.Center)
    }
    val subStyle = remember(type, colors) { type.micro.copy(color = colors.inkMuted, textAlign = TextAlign.Center) }

    Box(
        modifier
            .size(size)
            .clearAndSetSemantics {
                role = Role.Image
                this.contentDescription = cd
            }
            .drawWithCache {
                val sw = 8.dp.toPx()
                val r = this.size.width / 2f - 9.dp.toPx()
                val c = 2f * PI.toFloat() * r
                val gapPx = 3.dp.toPx()
                val topLeft = Offset(this.size.width / 2f - r, this.size.height / 2f - r)
                val arcSize = Size(2f * r, 2f * r)
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val trackStroke = Stroke(sw)
                val arcStroke = Stroke(sw, cap = StrokeCap.Round)
                val degPerPx = 180f / (PI.toFloat() * r)
                onDrawBehind {
                    drawCircle(colors.flowRemain, r, center, style = trackStroke)
                    var total = 0f
                    var n = 0
                    for (i in slices.indices) {
                        val v = slices[i].value
                        if (v > 0f) {
                            total += v
                            n++
                        }
                    }
                    if (total <= 0f) return@onDrawBehind
                    val gap = if (n > 1) gapPx else 0f
                    val revealDeg = reveal.value.coerceIn(0f, 1f) * 360f
                    var pos = 0f
                    for (i in slices.indices) {
                        val s = slices[i]
                        if (s.value <= 0f) continue
                        val share = s.value / total * c
                        // 圆端会向两侧各外扩半个描边：弧体长 = 份额 − 间隙 − 描边，使视觉间隙恰为 gap
                        val room = share - gap - sw
                        val len: Float
                        val start: Float
                        if (n <= 1) {
                            len = share
                            start = 0f
                        } else if (room >= 0f) {
                            len = room
                            start = pos + gap / 2f + sw / 2f
                        } else {
                            len = 0f
                            start = pos + share / 2f
                        }
                        val startDeg = start * degPerPx
                        val sweepDeg = len * degPerPx
                        if (len > 0f) {
                            val drawn = (revealDeg - startDeg).coerceIn(0f, sweepDeg)
                            if (drawn > 0f) {
                                drawArc(s.color, -90f + startDeg, drawn, false, topLeft, arcSize, style = arcStroke)
                            }
                        } else if (revealDeg >= startDeg) {
                            val a = (startDeg - 90f) * (PI.toFloat() / 180f)
                            drawCircle(s.color, sw / 2f, Offset(center.x + r * cos(a), center.y + r * sin(a)))
                        }
                        pos += share
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(centerText, style = centerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) BasicText(sub, Modifier.padding(top = 2.dp), style = subStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
