package com.fluxdown.fluxui.controls

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.material.fluxGlow
import com.fluxdown.fluxui.theme.FluxScaleGroup
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme

/** 0.5dp 发丝线。[inset] = 左缩进 62（对齐有图标盘的行文字起点）；[startInset] 可自定义缩进。 */
@Composable
fun FluxDivider(
    modifier: Modifier = Modifier,
    inset: Boolean = false,
    startInset: Dp = if (inset) 62.dp else 0.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(0.5.dp)
            .background(FluxTheme.colors.hairline),
    )
}

/** 分组小标题（`micro` inkMuted，padding 22 / 6 / 8），右侧可放 [action]。`GlassSection(title = …)` 内部已使用。 */
@Composable
fun FluxSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = FluxTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 22.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluxText(text, style = FluxTheme.type.micro, color = c.inkMuted, modifier = Modifier.weight(1f, fill = false))
        action?.invoke()
    }
}

/** 分组脚注 / 说明（`sm` inkFaint，行高 1.45，padding 8 / 8 / 0）。 */
@Composable
fun FluxSectionFoot(text: String, modifier: Modifier = Modifier) {
    val t = FluxTheme.type
    FluxText(
        text,
        style = remember(t) { t.sm.copy(lineHeight = 1.45.em) },
        color = FluxTheme.colors.inkFaint,
        modifier = modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
    )
}

/**
 * 键值行（`kvr`）：左键 sm inkMuted（不收缩），右值 sm 右对齐可换行（任意处断行）；[mono] = 等宽 12（哈希 / 路径）；
 * [tone] 给值着 `*-t` 色；[onClick] 非空时整行可点（典型：复制并由调用方弹 Toast）。
 * 放进 [GlassSection] 的 `row { }`，行间发丝线由分组提供。
 */
@Composable
fun FluxKeyValue(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    tone: Tone = Tone.Neutral,
    onClick: (() -> Unit)? = null,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val valueStyle = remember(t, mono) {
        (if (mono) t.mono else t.sm).copy(textAlign = TextAlign.End, lineBreak = LineBreak.Simple)
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FluxText(key, style = t.sm, color = c.inkMuted, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
        FluxText(
            value.ifEmpty { "—" },
            style = valueStyle,
            color = tone.content(),
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            modifier = Modifier.weight(1f),
        )
    }
}

/** [FluxStatRow] 的一项。 */
@Immutable
data class StatItem(val value: String, val label: String, val unit: String? = null, val tone: Tone = Tone.Neutral)

/**
 * 统计格（`stat`）：值 mono 20·kl / 600 / −2%（单位 0.6em inkMuted，同行基线对齐），键 `micro` +6% inkMuted。
 * 值是会刷新的数字，使用等宽数字。
 */
@Composable
fun FluxStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    tone: Tone = Tone.Neutral,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val valueStyle = remember(t) {
        t.sized(t.weight(t.mono, 600, mono = true), 20f, FluxScaleGroup.Kl).copy(letterSpacing = (-0.02f).em, lineHeight = 1.0.em)
    }
    val unitStyle = remember(t) { t.sized(t.weight(t.mono, 500, mono = true), 12f, FluxScaleGroup.Kl).copy(lineHeight = 1.0.em) }
    val keyStyle = remember(t) { t.micro.copy(letterSpacing = 0.06.em) }
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row {
            FluxText(value, style = valueStyle, color = tone.content(), maxLines = 1, modifier = Modifier.alignByBaseline())
            if (unit != null) {
                FluxText(unit, style = unitStyle, color = c.inkMuted, maxLines = 1, modifier = Modifier.padding(start = 2.dp).alignByBaseline())
            }
        }
        FluxText(label, style = keyStyle, color = c.inkMuted)
    }
}

/** 等分列的统计行，列间 0.5dp 发丝线。 */
@Composable
fun FluxStatRow(stats: List<StatItem>, modifier: Modifier = Modifier) {
    val hair = FluxTheme.colors.hairline
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        stats.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().background(hair))
            FluxStat(s.value, s.label, Modifier.weight(1f), s.unit, s.tone)
        }
    }
}

/**
 * 线性进度（`progress-line`，非任务进度：备份 / 同步等）：高 4 · r2，轨道 `flowRemain`，
 * 填充 accent 纯色；宽度 0.6 s 线性补间（Reduce motion 瞬时）。[progress] ∈ 0..1。
 */
@Composable
fun FluxProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val c = FluxTheme.colors
    val reduce = FluxTheme.motion.reduce
    val p = animateFloatAsState(
        progress.coerceIn(0f, 1f),
        if (reduce) snap() else tween(600, easing = LinearEasing),
        label = "progress-line",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f) }
            .drawWithCache {
                val h = size.height
                val corner = CornerRadius(h / 2f)
                onDrawBehind {
                    drawRoundRect(c.flowRemain, cornerRadius = corner)
                    val w = size.width * p.value
                    if (w > 0f) {
                        drawRoundRect(c.accent, size = Size(w, h), cornerRadius = corner)
                    }
                }
            },
    )
}

/**
 * 在线 / 状态点（`hp-dot`）：[size] 圆点 + 同色微光（默认 mint 7dp）。Neutral = 无色灰点（不发光）。
 * 纯装饰，状态必须同时用文字表达（§10.7）。
 */
@Composable
fun FluxPresenceDot(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Mint,
    size: Dp = 7.dp,
    glow: Boolean = true,
) {
    val color: Color = tone.fill()
    Box(
        modifier
            .size(size)
            .then(if (glow && tone != Tone.Neutral) Modifier.fluxGlow(color, 4.dp, CircleShape) else Modifier)
            .background(color, CircleShape),
    )
}
