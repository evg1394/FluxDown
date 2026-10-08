package com.fluxdown.fluxui.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 作用域标签（状态文件夹）。[count] 为 null 时不显示计数；[hot] = 计数以 `coralText` 着色（如失败 > 0）。 */
@Immutable
data class ScopeTab<T>(val value: T, val label: String, val count: Int? = null, val hot: Boolean = false)

/**
 * 作用域标签（01 §12.11）：横向滚动的文字标签 + 计数，选中项下方有 2dp 纯色指示条
 * （`left` = `liquid`、`width` = `fluid`；首次布局不做动画），底部发丝线。
 *
 * 切换触发 tick 触感，并自动把选中项滚入可视区。无障碍：每项 `Role.Tab`，描述“{标签}，{countDescription(计数)}”。
 */
@Composable
fun <T> ScopeTabs(
    tabs: List<ScopeTab<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    countDescription: (Int) -> String = { "$it 个" },
) {
    val c = FluxTheme.colors
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val density = androidx.compose.ui.platform.LocalDensity.current
    val n = tabs.size
    val selIndex = tabs.indexOfFirst { it.value == selected }
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    val lefts = remember(n) { mutableStateListOf<Int>().apply { repeat(n) { add(-1) } } }
    val widths = remember(n) { mutableStateListOf<Int>().apply { repeat(n) { add(0) } } }
    val indLeft = remember { Animatable(0f) }
    val indWidth = remember { Animatable(0f) }
    var laidOut by remember { mutableStateOf(false) }

    val tLeft = lefts.getOrElse(selIndex) { -1 }
    val tWidth = widths.getOrElse(selIndex) { 0 }
    LaunchedEffect(selIndex, tLeft, tWidth) {
        if (selIndex < 0 || tLeft < 0 || tWidth <= 0) return@LaunchedEffect
        if (!laidOut) {
            indLeft.snapTo(tLeft.toFloat())
            indWidth.snapTo(tWidth.toFloat())
            laidOut = true
        } else {
            launch { indLeft.animateTo(tLeft.toFloat(), motion.of(motion.liquid)) }
            launch { indWidth.animateTo(tWidth.toFloat(), motion.of(motion.fluid)) }
        }
    }
    LaunchedEffect(selIndex, tLeft, tWidth, viewport) {
        if (selIndex < 0 || tLeft < 0 || tWidth <= 0 || viewport <= 0) return@LaunchedEffect
        val edge = with(density) { 16.dp.roundToPx() }
        val target = when {
            tLeft - edge < scroll.value -> tLeft - edge
            tLeft + tWidth + edge > scroll.value + viewport -> tLeft + tWidth + edge - viewport
            else -> return@LaunchedEffect
        }.coerceIn(0, scroll.maxValue)
        scroll.animateScrollTo(target, motion.of(motion.fluid))
    }

    val shape = remember { RoundedCornerShape(2.dp) }
    Box(
        modifier
            .onSizeChanged { viewport = it.width }
            .drawBehind {
                val y = size.height - 0.25.dp.toPx()
                drawLine(c.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
            }
            .semantics { isTraversalGroup = true },
    ) {
        Box(Modifier.horizontalScroll(scroll)) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                tabs.forEachIndexed { i, tab ->
                    ScopeTabItem(
                        tab = tab,
                        selected = i == selIndex,
                        onClick = {
                            if (i != selIndex) haptics.tick()
                            onSelect(tab.value)
                        },
                        countDescription = countDescription,
                        modifier = Modifier.onGloballyPositioned {
                            val l = it.positionInParent().x.roundToInt()
                            val w = it.size.width
                            if (lefts[i] != l) lefts[i] = l
                            if (widths[i] != w) widths[i] = w
                        },
                    )
                }
            }
            if (selIndex >= 0 && laidOut) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset { IntOffset(indLeft.value.roundToInt(), 0) }
                        .layout { m, _ ->
                            val w = indWidth.value.roundToInt().coerceAtLeast(0)
                            val p = m.measure(Constraints.fixed(w, 2.dp.roundToPx()))
                            layout(w, p.height) { p.place(0, 0) }
                        }
                        .background(c.accentHi, shape),
                )
            }
        }
    }
}

@Composable
private fun <T> ScopeTabItem(
    tab: ScopeTab<T>,
    selected: Boolean,
    onClick: () -> Unit,
    countDescription: (Int) -> String,
    modifier: Modifier,
) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val labelStyle = remember(type, selected) { type.weight(type.body, if (selected) 600 else 500) }
    val countStyle = remember(type) { type.weight(type.monoS, 500, mono = true) }
    val description = tab.count?.let { "${tab.label}，${countDescription(it)}" } ?: tab.label
    Row(
        modifier
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(top = 12.dp, bottom = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TabText(tab.label, labelStyle, if (selected) c.ink else c.inkMuted)
            if (tab.count != null) {
                TabText(
                    tab.count.toString(),
                    countStyle,
                    when {
                        tab.hot -> c.coralText
                        selected -> c.accentHi
                        else -> c.inkFaint
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.TabText(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color) {
    FluxText(text, modifier = Modifier.alignByBaseline(), style = style, color = color, maxLines = 1, softWrap = false)
}
