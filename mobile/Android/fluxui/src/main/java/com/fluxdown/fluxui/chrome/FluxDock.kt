package com.fluxdown.fluxui.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.util.lerp
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.material.FluxBlur
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.FluxGlassKind
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.theme.FluxScaleGroup
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** 导航坞徽标：计数或红点。 */
@Immutable
sealed interface FluxDockBadge {
    /** 计数徽标（> 99 显示 `99+`；≤ 0 不显示）。 */
    @Immutable
    data class Count(val n: Int) : FluxDockBadge

    /** 8dp 珊瑚红点。 */
    @Immutable
    data object Dot : FluxDockBadge
}

/**
 * 导航坞目的地。[badgeDescription] 会并入无障碍描述（如“3 条未读”），徽标本身不单独朗读。
 */
@Immutable
data class FluxDockItem(
    val icon: ImageVector,
    val label: String,
    val badge: FluxDockBadge? = null,
    val badgeDescription: String? = null,
)

private val ItemSize = 52.dp
private val DockHeight = 64.dp
private val DockPad = 6.dp
private val ItemGap = 4.dp
private val MiniWidth = 64.dp

/**
 * 浮动导航坞（01 §12.2）：Real 玻璃 G3（canvasMix .38）胶囊，选中项展开为“图标 + 文字”，
 * 液态指示器两段弹簧（位置 `liquid`、宽度 `fluid`）。
 *
 * 本组件在父容器内**占满宽度、固定高 64**；坞本体宽 = 容器宽 − [reservedEnd]（球位 64 + 间距 12），
 * 迷你态宽 64。由应用负责摆放（左右 16、距底 26dp 等）。必须位于 `fluxBackdropSource` 之后的兄弟层。
 *
 * - [mini]：应用按滚动方向决定（向下 Δ>5dp 迷你，向上 / 顶部展开）；迷你态点击坞 → [onExpand]，不触发选中。
 * - [hidden]：推入页 / Expanded 隐藏（`alpha 0、translateY 30dp、blur 8`，退场结束后移出组合）。
 * - [selectionMode]：多选模式，坞淡出（`translateY 18、scale .94、blur 10`），由 [SelectionDock] 接替。
 * - 选中项宽 = `max(112dp, 52dp + 标签实测宽 + 8dp + 12dp)`；总宽放不下时退化为“全部仅图标”（见 §3.3）。
 * - 点击不同项触发 tick 触感；点击当前项同样回调 [onSelect]（应用可据此回顶并展开坞）。
 */
@Composable
fun FluxDock(
    items: List<FluxDockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    mini: Boolean = false,
    onExpand: () -> Unit = {},
    hidden: Boolean = false,
    selectionMode: Boolean = false,
    reservedEnd: Dp = 76.dp,
    contentDescription: String = "主导航",
    expandDescription: String = "展开导航",
) {
    if (items.isEmpty()) return
    val motion = FluxTheme.motion
    val hideP = rememberChromePresence(!hidden, motion.fluid)
    val selP = rememberChromePresence(!selectionMode, motion.liquid)
    var availPx by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .fillMaxWidth()
            .height(DockHeight)
            .onSizeChanged { availPx = it.width },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (hideP.composed && selP.composed) {
            DockBody(
                items = items,
                selected = selected.coerceIn(0, items.lastIndex),
                onSelect = onSelect,
                mini = mini,
                onExpand = onExpand,
                interactive = !hidden && !selectionMode,
                hideP = hideP,
                selP = selP,
                availPx = availPx,
                reservedEnd = reservedEnd,
                groupDescription = contentDescription,
                expandDescription = expandDescription,
            )
        }
    }
}

@Composable
private fun DockBody(
    items: List<FluxDockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    mini: Boolean,
    onExpand: () -> Unit,
    interactive: Boolean,
    hideP: ChromePresence,
    selP: ChromePresence,
    availPx: Int,
    reservedEnd: Dp,
    groupDescription: String,
    expandDescription: String,
) {
    val colors = FluxTheme.colors
    val type = FluxTheme.type
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val density = LocalDensity.current
    val n = items.size

    val labelStyle = remember(type) { type.weight(type.sm, 600).copy(letterSpacing = 0.005.em) }
    val measurer = rememberTextMeasurer()
    val labelWidths = remember(items, labelStyle, density) {
        items.map {
            with(density) {
                measurer.measure(it.label, labelStyle, softWrap = false, maxLines = 1).size.width.toDp().value
            }
        }
    }
    // 选中项宽（dp）：max(112, 52 + 标签 + 8 + 12)；放不下时全部仅图标。
    val fullSelWidths = remember(labelWidths) { labelWidths.map { max(112f, 52f + it + 8f + 12f) } }
    val availDp = availPx / density.density - reservedEnd.value
    val natural = 12f + 56f * (n - 1) + fullSelWidths[selected]
    val iconOnly = availPx > 0 && natural > availDp
    val selWidths = remember(fullSelWidths, iconOnly) { if (iconOnly) fullSelWidths.map { 52f } else fullSelWidths }

    val targetLeft = if (mini) 6f else 6f + 56f * selected
    val targetWidth = if (mini) 52f else selWidths[selected]

    val miniA = remember { Animatable(if (mini) 1f else 0f) }
    val selA = remember(n) { List(n) { Animatable(if (it == selected) 1f else 0f) } }
    val indLeft = remember { Animatable(targetLeft) }
    val indWidth = remember { Animatable(targetWidth) }
    LaunchedEffect(selected, mini, targetWidth, n) {
        launch { indLeft.animateTo(targetLeft, motion.of(motion.liquid)) }
        launch { indWidth.animateTo(targetWidth, motion.of(motion.fluid)) }
        launch { miniA.animateTo(if (mini) 1f else 0f, motion.of(motion.liquid)) }
        selA.forEachIndexed { i, a -> launch { a.animateTo(if (i == selected) 1f else 0f, motion.of(motion.liquid)) } }
    }

    val shape = FluxTheme.shapes.sheet
    Box(
        Modifier
            .layout { measurable, c ->
                val miniPx = MiniWidth.roundToPx()
                val expanded = (c.maxWidth - reservedEnd.roundToPx()).coerceAtLeast(miniPx)
                val w = lerp(expanded.toFloat(), miniPx.toFloat(), miniA.value.coerceIn(0f, 1f)).roundToInt()
                val p = measurable.measure(Constraints.fixed(w, DockHeight.roundToPx()))
                layout(p.width, p.height) { p.place(0, 0) }
            }
            .chromeFade(translateY = 30.dp, blur = 8.dp) { hideP.progress.value }
            .chromeFade(translateY = 18.dp, scaleFrom = 0.94f, blur = 10.dp) { selP.progress.value }
            .fluxGlass(FluxGlass.G3, shape, FluxBlur.Regular, FluxGlassKind.Real, canvasMix = 0.38f)
            .clip(shape)
            .semantics {
                isTraversalGroup = true
                contentDescription = groupDescription
            },
    ) {
        Layout(
            content = {
                DockIndicator()
                items.forEachIndexed { i, item ->
                    DockItemView(
                        item = item,
                        isSelected = i == selected,
                        sel = selA[i],
                        mini = miniA,
                        labelStyle = labelStyle,
                        showLabel = !iconOnly,
                        enabled = interactive && !mini,
                        onClick = {
                            if (i != selected) haptics.tick()
                            onSelect(i)
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val itemH = ItemSize.roundToPx()
            val top = DockPad.roundToPx()
            val gap = ItemGap.roundToPx()
            val m = miniA.value.coerceIn(0f, 1f)
            val indPlaceable = measurables[0].measure(
                Constraints.fixed(indWidth.value.dp.roundToPx().coerceAtLeast(0), itemH),
            )
            val xs = IntArray(n)
            val placeables = arrayOfNulls<Placeable>(n)
            var cursor = DockPad.roundToPx()
            for (i in 0 until n) {
                val s = selA[i].value.coerceIn(0f, 1f)
                val base = lerp(52f, selWidths[i], s)
                val w = lerp(base, 52f * s, m).dp.roundToPx().coerceAtLeast(0)
                val x = (if (i == 0) cursor else cursor + gap) - (ItemGap.toPx() * m * (1f - s)).roundToInt()
                placeables[i] = measurables[i + 1].measure(Constraints.fixed(w, itemH))
                xs[i] = x
                cursor = x + w
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                indPlaceable.place(indLeft.value.dp.roundToPx(), top)
                for (i in 0 until n) placeables[i]?.place(xs[i], top)
            }
        }
        if (mini) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = expandDescription,
                        role = Role.Button,
                        onClick = onExpand,
                    ),
            )
        }
    }
}

/** 液态指示器：`glass4` 纯色底 + 顶沿高光描边 + 发丝线（无渐变、无辉光）。 */
@Composable
private fun DockIndicator() {
    val c = FluxTheme.colors
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier
            .drawWithCache {
                val fill = c.glass4
                val hl = Brush.verticalGradient(0f to c.highlight, 0.18f to Color.Transparent)
                val outline = shape.createOutline(size, layoutDirection, this)
                val hw = 0.5.dp.toPx()
                onDrawBehind {
                    drawOutline(outline, fill)
                    drawOutline(outline, hl, style = Stroke(hw))
                    drawOutline(outline, c.hairlineStrong, style = Stroke(hw))
                }
            },
    )
}

@Composable
private fun DockItemView(
    item: FluxDockItem,
    isSelected: Boolean,
    sel: Animatable<Float, AnimationVector1D>,
    mini: Animatable<Float, AnimationVector1D>,
    labelStyle: TextStyle,
    showLabel: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = FluxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val description = item.badgeDescription?.let { "${item.label}，$it" } ?: item.label
    Box(
        Modifier
            .clip(RoundedCornerShape(26.dp))
            .selectable(
                selected = isSelected,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .wrapContentWidth(align = Alignment.CenterHorizontally, unbounded = true)
                .clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp)) {
                FluxIcon(
                    item.icon, null,
                    Modifier.graphicsLayer { alpha = 1f - sel.value.coerceIn(0f, 1f) },
                    tint = if (pressed) c.ink else c.inkMuted,
                )
                FluxIcon(
                    item.icon, null,
                    Modifier.graphicsLayer { alpha = sel.value.coerceIn(0f, 1f) },
                    tint = c.accentHi,
                )
            }
            if (showLabel) {
                FluxText(
                    item.label,
                    modifier = Modifier
                        .layout { m, _ ->
                            val p = m.measure(Constraints())
                            val f = (sel.value * (1f - mini.value)).coerceIn(0f, 1f)
                            val gap = 8.dp.roundToPx()
                            val w = ((p.width + gap) * f).roundToInt()
                            layout(w, p.height) { p.place((gap * f).roundToInt(), 0) }
                        }
                        .graphicsLayer { alpha = (sel.value * (1f - mini.value)).coerceIn(0f, 1f) },
                    style = labelStyle,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                )
            }
        }
        val badge = item.badge
        if (badge != null && !(badge is FluxDockBadge.Count && badge.n <= 0)) {
            DockBadgeView(badge, sel, mini)
        }
    }
}

@Composable
private fun BoxScope.DockBadgeView(
    badge: FluxDockBadge,
    sel: Animatable<Float, AnimationVector1D>,
    mini: Animatable<Float, AnimationVector1D>,
) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val position = Modifier
        .align(Alignment.TopEnd)
        .offset {
            val right = lerp(11f, 5f, sel.value.coerceIn(0f, 1f))
            val top = if (badge is FluxDockBadge.Dot) 12.dp else 9.dp
            IntOffset(-right.dp.roundToPx(), top.roundToPx())
        }
        .graphicsLayer { alpha = 1f - mini.value.coerceIn(0f, 1f) }
    when (badge) {
        is FluxDockBadge.Count -> {
            val style = remember(type) {
                type.sized(type.weight(type.monoS, 600, mono = true), 10f, FluxScaleGroup.Ks)
            }
            Box(
                position
                    .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.accent)
                    .padding(horizontal = 4.dp)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                FluxText(
                    if (badge.n > 99) "99+" else badge.n.toString(),
                    style = style,
                    color = c.onAccent,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        FluxDockBadge.Dot -> Box(
            position
                .size(8.dp)
                .clip(CircleShape)
                .background(c.coral)
                .clearAndSetSemantics { },
        )
    }
}
