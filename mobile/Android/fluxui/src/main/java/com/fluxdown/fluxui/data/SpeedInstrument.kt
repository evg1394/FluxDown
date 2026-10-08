package com.fluxdown.fluxui.data

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.FluxGlassKind
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.theme.FluxScaleGroup
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxPressable

/** 速度读数：数字与单位分开显示（display 56 + 单位 20），如 `("12.4", "MB/s")`；空闲为 `("0", "B/s")`。 */
@Immutable
data class SpeedReading(val value: String, val unit: String)

/** 统计行的一项：[label]（如 `↑` / `活跃` / `剩余空间`，inkMuted）+ [value]（ink 600）。 */
@Immutable
data class InstrumentStat(val label: String, val value: String)

/**
 * 速度仪表（英雄卡，品牌签名之一）。Flat 玻璃（lerp(glass2, canvas, .30)）+ 呼吸点 + display 数字 + 波形 + 统计行；
 * 卡面纯色，无背景光。
 *
 * @param live 总下载速度 > 0：点呼吸（2s）；否则点 inkFaint。
 * @param waveform 在绘制阶段读取的波形数据（见 [Waveform]）。
 * @param stats 统计项，FlowRow 排布（gap 4/14），项间 2dp 圆点分隔。
 * @param pausedAll 右上按钮显示“全部恢复”（播放）而非“全部暂停”（暂停）。
 * @param title 左上标签（如“实时吞吐”，自动大写）。
 * @param compact Rail 底部紧凑变体：r24 · padding 14/16/12 · 波形 56 · 数字上距 4。
 * @param contentDescription 整卡朗读文案（“实时吞吐 {值}，上传 {值}，活跃 {n} 个任务，剩余空间 {值}”）；缺省拼接 title / speed / stats。
 *
 * 整卡点击 = [onClick]（→ 活动面板）；右上圆钮 = [onToggleAll]（SEGMENT_TICK）。波形装饰，不进无障碍树。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedInstrument(
    speed: SpeedReading,
    live: Boolean,
    waveform: () -> WaveSeries,
    stats: List<InstrumentStat>,
    pausedAll: Boolean,
    onToggleAll: () -> Unit,
    onClick: () -> Unit,
    title: String,
    pauseAllLabel: String,
    resumeAllLabel: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    contentDescription: String? = null,
) {
    val colors = FluxTheme.colors
    val type = FluxTheme.type
    val shapes = FluxTheme.shapes
    val haptics = FluxTheme.haptics
    val shape = if (compact) shapes.card else shapes.sheet
    val pad = if (compact) 16.dp else 20.dp

    val displayStyle = remember(type, colors) { type.display.copy(color = colors.ink) }
    val unitStyle = remember(type, colors) {
        type.weight(type.sized(type.bodyM, 20f, FluxScaleGroup.Kl), 500).copy(color = colors.inkMuted, letterSpacing = (-0.01).em)
    }
    val labelStyle = remember(type, colors) { type.micro.copy(color = colors.inkMuted) }
    val statStyle = remember(type, colors) { type.weight(type.mono, 500, mono = true).copy(color = colors.inkMuted) }
    val statBold = remember(type, colors) {
        SpanStyle(color = colors.ink, fontFamily = type.weight(type.mono, 600, mono = true).fontFamily)
    }
    val cd = contentDescription ?: remember(title, speed, stats) {
        buildString {
            append(title).append(' ').append(speed.value).append(' ').append(speed.unit)
            for (s in stats) append(", ").append(s.label).append(' ').append(s.value)
        }
    }
    val toggleLabel = if (pausedAll) resumeAllLabel else pauseAllLabel

    Column(
        modifier
            .fillMaxWidth()
            .fluxPressable(onClick = onClick, scale = 0.985f, role = Role.Button)
            .fluxGlass(FluxGlass.G2, shape, kind = FluxGlassKind.Flat, canvasMix = 0.30f)
            .clip(shape)
            .semantics { this.contentDescription = cd }
            .padding(start = pad, end = pad, top = if (compact) 14.dp else 20.dp, bottom = if (compact) 12.dp else 16.dp),
    ) {
        // 顶行：呼吸点 + 标签 | 48dp 全部暂停 / 恢复
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Row(
                Modifier.clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiveDot(live)
                val label = remember(title) { title.uppercase() }
                BasicText(label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier
                    .size(48.dp)
                    .fluxPressable(
                        onClick = {
                            haptics.tick()
                            onToggleAll()
                        },
                        scale = 0.92f,
                        role = Role.Button,
                    )
                    .fluxGlass(FluxGlass.G3, shapes.full, kind = FluxGlassKind.Flat)
                    .semantics { this.contentDescription = toggleLabel }
                    .drawBehind {
                        drawFilledGlyph(pausedAll, colors.ink, 20.dp.toPx(), size.width / 2f, size.height / 2f)
                    },
            )
        }

        // 数字
        Row(
            Modifier
                .padding(top = if (compact) 4.dp else 10.dp)
                .clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(speed.value, Modifier.alignByBaseline(), displayStyle, maxLines = 1, softWrap = false)
            BasicText(speed.unit, Modifier.alignByBaseline(), unitStyle, maxLines = 1, softWrap = false)
        }

        // 波形：左右 −pad 全幅出血
        Waveform(
            samples = waveform,
            modifier = Modifier
                .padding(top = 6.dp, bottom = 4.dp)
                .bleedHorizontal(pad)
                .height(if (compact) 56.dp else 92.dp),
        )

        // 统计行
        if (stats.isNotEmpty()) {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in stats.indices) {
                    if (i > 0) Box(Modifier.size(2.dp).background(colors.inkFaint, CircleShape))
                    BasicText(statText(stats[i], statBold), style = statStyle)
                }
            }
        }
    }
}

private fun statText(s: InstrumentStat, bold: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(s.label)
    append(' ')
    withStyle(bold) { append(s.value) }
}

/** 6dp 状态点：live = accentHi + 10dp 辉光 + 2s 呼吸；idle = inkFaint。 */
@Composable
private fun LiveDot(live: Boolean) {
    val colors = FluxTheme.colors
    val decor = rememberDecorativeMotion()
    val pulse: State<Float>? = if (live && decor) {
        rememberInfiniteTransition(label = "instrPulse")
            .animateFloat(1f, 0.4f, infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    } else null
    Box(
        Modifier
            .size(6.dp)
            .drawWithCache {
                val r = 3.dp.toPx()
                val glowR = r + 10.dp.toPx()
                val glow = Brush.radialGradient(
                    0f to colors.accentHi.copy(alpha = 0.55f),
                    r / glowR to colors.accentHi.copy(alpha = 0.45f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = glowR,
                )
                val c = Offset(size.width / 2f, size.height / 2f)
                onDrawBehind {
                    val a = pulse?.value ?: 1f
                    if (live) translate(c.x, c.y) { drawCircle(glow, glowR, Offset.Zero, alpha = a) }
                    drawCircle(if (live) colors.accentHi else colors.inkFaint, r, c, alpha = a)
                }
            },
    )
}

/** 抵消父级水平 padding，使内容全幅出血（CSS `margin: 0 -pad`）。 */
private fun Modifier.bleedHorizontal(pad: Dp): Modifier = layout { measurable, c ->
    val extra = pad.roundToPx()
    val width = c.maxWidth + 2 * extra
    val p = measurable.measure(c.copy(minWidth = width, maxWidth = width))
    layout(c.maxWidth, p.height) { p.place(-extra, 0) }
}
