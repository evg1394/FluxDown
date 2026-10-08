package com.fluxdown.fluxui.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxTouchTarget

/**
 * 单选点（§12.35）：22×22，选中 = accentHi 边 + 10dp 内点（scale 0→1，`liquid`）；命中区 48dp；触感 `tick`。
 * [onClick] 为 null 时仅显示（用于整行可选的单选行）。分组请给容器加 `Modifier.selectableGroup()`。
 */
@Composable
fun FluxRadio(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = FluxTheme.colors
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val p = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected, motion) { p.animateTo(if (selected) 1f else 0f, motion.of(motion.liquid)) }
    val select = if (onClick != null) {
        Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = { haptics.tick(); onClick() },
        )
    } else {
        Modifier
    }
    Box(
        modifier
            .fluxFocusRing(CircleShape, visualWidth = 22.dp, visualHeight = 22.dp)
            .then(select)
            .fluxTouchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val pc = p.value.coerceIn(0f, 1f)
                val sw = 1.5.dp.toPx()
                drawCircle(
                    lerp(c.hairlineStrong, c.accentHi, pc),
                    radius = size.minDimension / 2f - sw / 2f,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(sw),
                )
                val dot = 5.dp.toPx() * p.value
                if (dot > 0f) drawCircle(c.accentHi, radius = dot, center = Offset(size.width / 2f, size.height / 2f))
            }
        }
    }
}
