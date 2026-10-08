package com.fluxdown.fluxui.chrome

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.material.FluxBlur
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.FluxGlassKind
import com.fluxdown.fluxui.material.fluxAccentSurface
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.material.fluxGlow
import com.fluxdown.fluxui.theme.FluxScaleGroup
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxPressable

/** 侧栏顶层目的地（下载 / 订阅 / 设备 / 设置）。 */
@Immutable
data class FluxRailNavItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val count: Int? = null,
    val hot: Boolean = false,
)

/**
 * 扩展档侧栏（01 §12.33）：宽 272dp、Real 玻璃 G1（canvasMix .40）、自上而下为
 * 品牌行 → 全宽“新建”主按钮 → 顶层目的地（行高 42）→ [extra]（当前目的地的二级上下文，如状态文件夹 / 队列 / 设备，
 * 由 [FluxRailHeader] 与 [FluxRailItem] 组成）→ 底部 [footer]（吸底，如紧凑速度仪表 / 账户）。
 *
 * 自带状态栏 / 导航栏 inset 内边距；除 footer 外的内容整体可纵向滚动。
 * a11y：容器为遍历组；顶层项 `Role.Tab`（选中态），子项 `Role.Button`。
 *
 * @param brandMark 品牌徽标槽；默认画 28dp 纯色圆角方块 + 品牌名首字母。
 */
@Composable
fun FluxRail(
    nav: List<FluxRailNavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    newLabel: String,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
    brandName: String = "FluxDown",
    brandMark: (@Composable () -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    Column(
        modifier
            .width(272.dp)
            .fillMaxHeight()
            .fluxGlass(FluxGlass.G1, RectangleShape, FluxBlur.Regular, FluxGlassKind.Real, canvasMix = 0.40f)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom))
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp)
            .semantics { isTraversalGroup = true },
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val brandStyle = remember(type) { type.weight(type.body, 600).copy(letterSpacing = (-0.01).em) }
            Row(
                Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (brandMark != null) brandMark() else DefaultBrandMark(brandName)
                FluxText(brandName, style = brandStyle, color = c.ink, maxLines = 1)
            }
            RailPrimaryButton(newLabel, onNew, Modifier.padding(bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                nav.forEach { item ->
                    FluxRailItem(
                        label = item.label,
                        onClick = { onSelect(item.id) },
                        icon = item.icon,
                        selected = item.id == selected,
                        count = item.count,
                        hot = item.hot,
                    )
                }
            }
            extra?.invoke(this)
        }
        if (footer != null) {
            Column(Modifier.padding(top = 10.dp)) { footer() }
        }
    }
}

@Composable
private fun DefaultBrandMark(name: String) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val shape = RoundedCornerShape(9.dp)
    val style = remember(type) { type.sized(type.weight(type.sm, 700), 14f, FluxScaleGroup.Ks) }
    Box(
        Modifier
            .size(28.dp)
            .clip(shape)
            .background(c.accentFill),
        contentAlignment = Alignment.Center,
    ) {
        FluxText(name.take(1).uppercase(), style = style, color = c.onAccent, maxLines = 1, softWrap = false)
    }
}

/** 全宽主按钮（`.btn.primary`：强调色实心面 [fluxAccentSurface] + 向下投影）。 */
@Composable
private fun RailPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val shape = CircleShape
    val style = remember(type) { type.weight(type.body, 600).copy(letterSpacing = (-0.005).em) }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .fluxPressable(onClick = onClick, role = Role.Button)
            .fluxAccentSurface(c, shape, lift = 6.dp)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluxIcon(FluxIcons.Plus, null, size = 20.dp, tint = c.onAccent)
        FluxText(label, style = style, color = c.onAccent, maxLines = 1, softWrap = false)
    }
}

/**
 * 侧栏行（`.rl-i`）：高 42（[sub] 子项 36、左内边距 40、`sm` 字）、圆角 14；
 * 选中 = `ink` 字 + `glass3` 底 + 顶沿高光 + `hairlineStrong`、图标与计数 accentHi；[hot]（如失败）计数 `coralText`。
 * [dot] 非空时在图标位显示 7dp 状态点（true = mint 辉光 / false = inkFaint）。
 * 语义：顶层项 `Role.Tab`、子项 `Role.Button`，均带选中态。
 */
@Composable
fun FluxRailItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    count: Int? = null,
    hot: Boolean = false,
    sub: Boolean = false,
    dot: Boolean? = null,
) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val motion = FluxTheme.motion
    val tState = animateFloatAsState(if (selected) 1f else 0f, motion.of(motion.fluid), label = "railItem")
    val t = tState.value
    val shape = remember { RoundedCornerShape(14.dp) }
    val labelStyle = remember(type, sub) { if (sub) type.weight(type.sm, 500) else type.weight(type.body, 500) }
    val countStyle = remember(type) { type.weight(type.monoS, 500, mono = true) }
    Row(
        modifier
            .fillMaxWidth()
            .height(if (sub) 36.dp else 42.dp)
            .clip(shape)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                role = if (sub) Role.Button else Role.Tab,
                onClick = onClick,
            )
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val hl = Brush.verticalGradient(0f to c.highlight, 0.3f to Color.Transparent)
                val hw = 0.5.dp.toPx()
                onDrawBehind {
                    val a = tState.value.coerceIn(0f, 1f)
                    if (a > 0.001f) {
                        drawOutline(outline, c.glass3, alpha = a)
                        drawOutline(outline, hl, alpha = a, style = Stroke(hw))
                        drawOutline(outline, c.hairlineStrong, alpha = a, style = Stroke(hw))
                    }
                }
            }
            .padding(start = if (sub) 40.dp else 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(
                Modifier
                    .size(7.dp)
                    .then(if (dot) Modifier.fluxGlow(c.mint, 3.dp, CircleShape) else Modifier)
                    .background(if (dot) c.mint else c.inkFaint, CircleShape),
            )
        } else if (icon != null) {
            FluxIcon(icon, null, size = 20.dp, tint = lerp(c.inkMuted, c.accentHi, t))
        }
        FluxText(
            label,
            modifier = Modifier.weight(1f),
            style = labelStyle,
            color = lerp(c.inkMuted, c.ink, t),
            maxLines = 1,
        )
        if (count != null) {
            FluxText(
                count.toString(),
                style = countStyle,
                color = when {
                    hot -> c.coralText
                    selected -> c.accentHi
                    else -> c.inkFaint
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** 侧栏分区头（`.rl-h`）：`micro` inkFaint，padding 16/12/6；[action] 为右侧操作槽（如 ⚙）。 */
@Composable
fun FluxRailHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = FluxTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluxText(
            text,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            style = FluxTheme.type.micro,
            color = c.inkFaint,
            maxLines = 1,
        )
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}
