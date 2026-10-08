package com.fluxdown.fluxui.controls

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.FluxGlassKind
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.launch

/** 分段选项：[count] 以 monoS 显示在标签后。 */
@Immutable
data class SegOption<T>(val value: T, val label: String, val icon: ImageVector? = null, val count: Int? = null)

/**
 * 分段控件（§12.13）：外框 r16 · padding 3 · glass2 + hairline，选中块 glass4 + 高光 + hairlineStrong，
 * 位置 `liquid`、宽度 `fluid` 滑动；格等宽。Compose 视觉高 48（small = 38，命中区仍补足 48）。
 * §3.3：任一格标签放不下时整体改为纵向单选列表（不截断文字）。
 */
@Composable
fun <T> FluxSegmented(
    options: List<SegOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val t = FluxTheme.type
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val selStyle = remember(t, small) { t.weight(if (small) t.micro else t.sm, 600).copy(textAlign = TextAlign.Center) }
    val countStyle = remember(t) { t.monoS }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxW = constraints.maxWidth
        val fits = remember(options, maxW, selStyle, density) {
            if (maxW == Constraints.Infinity || options.isEmpty()) {
                true
            } else {
                with(density) {
                    val cell = (maxW - 6.dp.toPx()) / options.size
                    options.all { o ->
                        var w = measurer.measure(o.label, selStyle, maxLines = 1, softWrap = false).size.width + 20.dp.toPx()
                        if (o.icon != null) w += 22.dp.toPx()
                        if (o.count != null) w += 6.dp.toPx() + measurer.measure(o.count.toString(), countStyle, maxLines = 1, softWrap = false).size.width
                        w <= cell
                    }
                }
            }
        }
        if (fits) {
            SegmentedRow(options, selected, onSelect, small, maxW.toFloat(), selStyle)
        } else {
            SegmentedColumn(options, selected, onSelect)
        }
    }
}

@Composable
private fun <T> SegmentedRow(
    options: List<SegOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    small: Boolean,
    widthPx: Float,
    selStyle: TextStyle,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val density = LocalDensity.current
    val n = options.size.coerceAtLeast(1)
    val idx = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val pad = with(density) { 3.dp.toPx() }
    val cellW = (widthPx - 2 * pad) / n
    val left = remember { Animatable(pad + idx * cellW) }
    val width = remember { Animatable(cellW) }
    val initialized = remember { booleanArrayOf(false) }
    LaunchedEffect(idx, cellW, motion) {
        val targetL = pad + idx * cellW
        if (!initialized[0]) {
            initialized[0] = true
            left.snapTo(targetL)
            width.snapTo(cellW)
        } else {
            launch { left.animateTo(targetL, motion.of(motion.liquid)) }
            launch { width.animateTo(cellW, motion.of(motion.fluid)) }
        }
    }
    val unselStyle = remember(t, small) { t.weight(if (small) t.micro else t.sm, 500).copy(textAlign = TextAlign.Center) }
    val cellH = if (small) 32.dp else 42.dp

    Box(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .fillMaxWidth()
                .selectableGroup()
                .drawBehind {
                    val r = 16.dp.toPx()
                    val hw = 0.5.dp.toPx()
                    drawRoundRect(c.glass2, cornerRadius = CornerRadius(r))
                    drawRoundRect(
                        c.hairline, Offset(hw / 2f, hw / 2f), Size(size.width - hw, size.height - hw),
                        CornerRadius(r - hw / 2f), style = Stroke(hw),
                    )
                    // 选中块
                    val ih = size.height - 2 * pad
                    val ir = 13.dp.toPx()
                    val tl = Offset(left.value, pad)
                    val sz = Size(width.value, ih)
                    drawRoundRect(c.glass4, tl, sz, CornerRadius(ir))
                    drawRoundRect(
                        Brush.verticalGradient(0f to c.highlight, 0.4f to Color.Transparent, startY = pad, endY = pad + ih),
                        tl, sz, CornerRadius(ir), style = Stroke(hw),
                    )
                    drawRoundRect(
                        c.hairlineStrong, Offset(tl.x - hw / 2f, tl.y - hw / 2f), Size(sz.width + hw, sz.height + hw),
                        CornerRadius(ir + hw / 2f), style = Stroke(hw),
                    )
                }
                .padding(3.dp),
        ) {
            options.forEachIndexed { i, o ->
                val on = i == idx
                val fg by animateColorAsState(if (on) c.ink else c.inkMuted, motion.of(motion.snap), label = "seg-fg")
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = cellH)
                        .selectable(
                            selected = on,
                            role = Role.RadioButton,
                            onClick = {
                                if (!on) {
                                    haptics.tick()
                                    onSelect(o.value)
                                }
                            },
                        )
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (o.icon != null) FluxIcon(o.icon, null, size = 16.dp, tint = fg)
                    FluxText(o.label, style = if (on) selStyle else unselStyle, color = fg, maxLines = 1)
                    if (o.count != null) FluxText(o.count.toString(), style = t.monoS, color = c.inkFaint, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun <T> SegmentedColumn(
    options: List<SegOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val shape = FluxTheme.shapes.control
    Column(
        Modifier
            .fillMaxWidth()
            .selectableGroup()
            .fluxGlass(FluxGlass.G2, shape, kind = FluxGlassKind.Flat, strongLine = false)
            .clip(shape),
    ) {
        options.forEachIndexed { i, o ->
            if (i > 0) FluxDivider(startInset = 16.dp)
            FluxRadioRow(
                title = o.label,
                selected = o.value == selected,
                onSelect = { onSelect(o.value) },
                icon = o.icon,
                value = o.count?.toString(),
            )
        }
    }
}
