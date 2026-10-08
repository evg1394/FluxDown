package com.fluxdown.fluxui.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxTouchTarget

/**
 * 开关（§12.18）：46×28，旋钮 22（按压变宽到 26，`snap`），位移 `liquid`；ON = 强调色纯色轨道 + 定边描边（无渐变、无辉光）。
 * 命中区外扩到 48dp。[onCheckedChange] 为 null 时仅显示（用于整行可点的开关行，语义由行承担）。
 * 切换触感 `tick`。
 */
@Composable
fun FluxSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = FluxTheme.colors
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    val knobW = remember { Animatable(22f) }
    LaunchedEffect(checked, motion) { pos.animateTo(if (checked) 1f else 0f, motion.of(motion.liquid)) }
    LaunchedEffect(pressed, motion) { knobW.animateTo(if (pressed) 26f else 22f, motion.of(motion.snap)) }

    val track = remember { RoundedCornerShape(14.dp) }
    val edge = remember(c) { lerp(c.accent, Color.White, 0.3f) }
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { haptics.tick(); onCheckedChange(it) },
        )
    } else {
        Modifier
    }

    Box(
        modifier
            .fluxFocusRing(track, visualWidth = 46.dp, visualHeight = 28.dp)
            .then(toggle)
            .fluxTouchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(46.dp, 28.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val p = pos.value
                val pc = p.coerceIn(0f, 1f)
                val h = size.height
                val w = size.width
                val cr = CornerRadius(h / 2f)
                val hw = 0.5.dp.toPx()
                // 轨道：OFF 底 + 描边，ON 纯色填充叠加
                drawRoundRect(c.glass4, cornerRadius = cr)
                drawRoundRect(
                    c.hairlineStrong,
                    Offset(hw / 2f, hw / 2f), Size(w - hw, h - hw), CornerRadius(h / 2f - hw / 2f),
                    style = Stroke(hw), alpha = 1f - pc,
                )
                if (pc > 0f) {
                    drawRoundRect(c.accentFill, cornerRadius = cr, alpha = pc)
                    drawRoundRect(
                        edge,
                        Offset(hw / 2f, hw / 2f), Size(w - hw, h - hw), CornerRadius(h / 2f - hw / 2f),
                        style = Stroke(hw), alpha = pc,
                    )
                }
                // 旋钮：宽 22 → 26 时保持右缘（位移 18 → 14）
                val kw = knobW.value.dp.toPx()
                val kh = 22.dp.toPx()
                val inset = 2.dp.toPx()
                val kx = inset + p * (w - 2 * inset - kw)
                val ky = (h - kh) / 2f
                val kr = CornerRadius(kh / 2f)
                drawRoundRect(Color.Black.copy(alpha = 0.14f), Offset(kx - 0.75.dp.toPx(), ky + 0.25.dp.toPx()), Size(kw + 1.5.dp.toPx(), kh + 1.5.dp.toPx()), CornerRadius(kh / 2f + 0.75.dp.toPx()))
                drawRoundRect(Color.Black.copy(alpha = 0.22f), Offset(kx, ky + 1.dp.toPx()), Size(kw, kh), kr)
                drawRoundRect(Color.White, Offset(kx, ky), Size(kw, kh), kr)
                drawRoundRect(
                    Color.Black.copy(alpha = 0.1f),
                    Offset(kx + hw / 2f, ky + hw / 2f), Size(kw - hw, kh - hw), CornerRadius(kh / 2f - hw / 2f),
                    style = Stroke(hw),
                )
            }
        }
    }
}
