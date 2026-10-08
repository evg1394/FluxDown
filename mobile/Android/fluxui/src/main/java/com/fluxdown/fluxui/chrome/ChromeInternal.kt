package com.fluxdown.fluxui.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.theme.FluxSpring
import com.fluxdown.fluxui.theme.FluxTheme

/**
 * 出入场进度：`progress` 0..1（弹簧驱动），`composed` 表示内容是否仍需留在组合中
 * （退场动画结束后移除，真玻璃面不再占用背景副本，也不再进入无障碍树）。
 */
@Stable
internal class ChromePresence(visible: Boolean) {
    val progress = Animatable(if (visible) 1f else 0f)
    var composed by mutableStateOf(visible)
}

@Composable
internal fun rememberChromePresence(visible: Boolean, spring: FluxSpring): ChromePresence {
    val motion = FluxTheme.motion
    val p = remember { ChromePresence(visible) }
    LaunchedEffect(visible, motion) {
        if (visible) p.composed = true
        p.progress.animateTo(if (visible) 1f else 0f, motion.of(spring))
        if (!visible) p.composed = false
    }
    return p
}

/**
 * 出入场图层：`alpha / translateY / scale / blur` 随 [progress]（1 = 完全显示）变化。
 * [progress] 在 graphicsLayer 内读取，动画期间不触发重组；完全显示时不挂模糊效果。
 */
internal fun Modifier.chromeFade(
    translateY: Dp = 0.dp,
    scaleFrom: Float = 1f,
    blur: Dp = 0.dp,
    pivotY: Float = 0.5f,
    progress: () -> Float,
): Modifier = graphicsLayer {
    val raw = progress()
    val p = raw.coerceIn(0f, 1f)
    alpha = p
    translationY = translateY.toPx() * (1f - raw)
    val s = scaleFrom + (1f - scaleFrom) * raw
    scaleX = s
    scaleY = s
    transformOrigin = TransformOrigin(0.5f, pivotY)
    val b = blur.toPx() * (1f - p)
    renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
}
