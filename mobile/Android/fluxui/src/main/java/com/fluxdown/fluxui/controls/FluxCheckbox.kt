package com.fluxdown.fluxui.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.theme.FluxPressIndicationFactory
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxTouchTarget

/**
 * 复选框（§12.34）：24×24 · r8（[round] = 全圆，用于选择列表），命中区 48×48。
 * On / Indeterminate = accentHi 底与边 + 勾（Minus）pop-in（`liquid`，scale .5→1 + α）；按压 `scale .92`；触感 `tick`。
 * [onClick] 为 null 时仅显示（语义由所在行承担，请在行里 `clearAndSetSemantics`）。
 */
@Composable
fun FluxCheckbox(
    state: ToggleableState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    enabled: Boolean = true,
) {
    val c = FluxTheme.colors
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val on = state != ToggleableState.Off
    val p = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on, motion) { p.animateTo(if (on) 1f else 0f, motion.of(motion.liquid)) }
    val shape = remember(round) { if (round) CircleShape else RoundedCornerShape(8.dp) }
    val source = remember { MutableInteractionSource() }
    val press = remember { FluxPressIndicationFactory(0.92f) }
    val toggle = if (onClick != null) {
        Modifier.triStateToggleable(
            state = state,
            interactionSource = source,
            indication = press,
            enabled = enabled,
            role = Role.Checkbox,
            onClick = { haptics.tick(); onClick() },
        )
    } else {
        Modifier
    }
    Box(
        modifier
            .fluxFocusRing(shape, visualWidth = 24.dp, visualHeight = 24.dp)
            .then(toggle)
            .fluxTouchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val pc = p.value.coerceIn(0f, 1f)
                        val sw = 1.5.dp.toPx()
                        val r = if (round) size.minDimension / 2f else 8.dp.toPx()
                        drawRoundRect(c.accentHi.copy(alpha = pc), cornerRadius = CornerRadius(r))
                        drawRoundRect(
                            lerp(c.hairlineStrong, c.accentHi, pc),
                            Offset(sw / 2f, sw / 2f), Size(size.width - sw, size.height - sw), CornerRadius(r - sw / 2f),
                            style = Stroke(sw),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                FluxIcon(
                    icon = if (state == ToggleableState.Indeterminate) FluxIcons.Minus else FluxIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.graphicsLayer {
                        val s = 0.5f + 0.5f * p.value
                        scaleX = s
                        scaleY = s
                        alpha = p.value.coerceIn(0f, 1f)
                    },
                    size = 15.dp,
                    tint = c.onAccent,
                )
            }
        }
    }
}

/** 布尔便捷重载（无“部分选中”）。 */
@Composable
fun FluxCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    enabled: Boolean = true,
) {
    FluxCheckbox(
        state = if (checked) ToggleableState.On else ToggleableState.Off,
        onClick = onCheckedChange?.let { cb -> { cb(!checked) } },
        modifier = modifier,
        round = round,
        enabled = enabled,
    )
}
