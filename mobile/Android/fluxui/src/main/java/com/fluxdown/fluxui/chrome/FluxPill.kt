package com.fluxdown.fluxui.chrome

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxPressable

/**
 * 描边胶囊（01 §12.12）：分类 / 过滤 / 范围徽章。
 *
 * 尺寸：高 34（[small] 28）、水平内边距 14（11）、全圆；默认描边 `hairlineStrong` .5dp、字 `inkMuted`；
 * [selected] = `accentHi` 字 + accentLo 纯色底 + 1dp accent 60% 描边（扁平，无辉光）；[solid] = `glass2` 底、无描边。
 * 内容顺序：[icon]（范围徽章前缀）· [dot]（6dp 分类色点）· 文字 · [count]（mono，inkFaint）· [trailingIcon]（如 ✕）。
 * 无文字、无计数的纯图标胶囊（如分类行尾的“＋”）为 34dp 方形，需提供 [contentDescription]。
 *
 * [toggleable] = true 时语义为 `Role.Checkbox` + `toggleableState`；范围徽章（点按清除）传 false → `Role.Button`。
 * 按压 `scale .95`；[onLongClick] 触发 longPress 触感。
 */
@Composable
fun FluxPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    count: Int? = null,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    small: Boolean = false,
    solid: Boolean = false,
    toggleable: Boolean = true,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val motion = FluxTheme.motion
    val t by animateFloatAsState(if (selected) 1f else 0f, motion.of(motion.fluid), label = "pill")
    val shape = CircleShape
    val h = if (small) 28.dp else 34.dp
    val iconOnly = text.isEmpty() && count == null && dot == null && trailingIcon == null
    val textStyle = remember(type, small) {
        if (small) type.weight(type.micro, 500).copy(letterSpacing = 0.02.em) else type.weight(type.sm, 500)
    }
    val countStyle = remember(type) { type.weight(type.monoS, 500, mono = true) }
    val textColor = lerp(c.inkMuted, c.accentHi, t)
    val iconSize = if (small) 12.dp else 14.dp

    Box(
        modifier
            .height(h)
            .fluxPressable(onClick = onClick, scale = 0.95f, role = if (toggleable) Role.Checkbox else Role.Button, onLongClick = onLongClick)
            .semantics {
                if (toggleable) toggleableState = ToggleableState(selected)
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(h)
                .then(if (iconOnly) Modifier.width(h) else Modifier)
                .then(
                    if (solid) {
                        Modifier.background(c.glass2, shape)
                    } else {
                        Modifier
                            .background(lerp(Color.Transparent, c.accentLo, t), shape)
                            .border((0.5f + 0.5f * t).dp, lerp(c.hairlineStrong, c.accent.copy(alpha = 0.6f), t), shape)
                    },
                )
                .then(if (iconOnly) Modifier else Modifier.padding(horizontal = if (small) 11.dp else 14.dp)),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) FluxIcon(icon, null, size = iconSize, tint = textColor)
            if (dot != null) {
                Box(Modifier.size(6.dp).background(dot, CircleShape))
            }
            if (text.isNotEmpty()) {
                FluxText(text, style = textStyle, color = textColor, maxLines = 1, softWrap = false)
            }
            if (count != null) {
                FluxText(count.toString(), style = countStyle, color = c.inkFaint, maxLines = 1, softWrap = false)
            }
            if (trailingIcon != null) FluxIcon(trailingIcon, null, size = 12.dp, tint = c.inkMuted)
        }
    }
}
