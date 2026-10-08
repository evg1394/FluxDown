package com.fluxdown.fluxui.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.FluxGlassKind
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlin.math.roundToInt

/**
 * 滑杆（§12.19）。轨道 4dp / 拇指 22dp / 触控高 ≥ 48dp；按压时拇指外环 + 数值气泡（[format] 格式化），
 * 拖动每个新值触发 `frequent` 触感（限频）。[steps] = 中间离散档位数（同 Material 语义），[ticks] = 刻度数（≥ 2 才画）。
 *
 * 气泡画在组件上缘之外（约 36dp 的净空），外层容器不要裁剪（`GlassSection` 内请在滑杆行上方留白）。
 * 横向拖动与父级竖向滚动互不抢占（先过横向触摸斜率才接管）。
 * [label] 是无障碍名称；数值 / 范围通过 `progressBarRangeInfo` 暴露，键盘 ←/→ 可调。
 */
@Composable
fun FluxSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    format: (Float) -> String = { it.toString() },
    ticks: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    label: String,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val density = LocalDensity.current
    val valueState = rememberUpdatedState(value)
    val onChange = rememberUpdatedState(onValueChange)
    val finished = rememberUpdatedState(onValueChangeFinished)
    var widthPx by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val emphasis = remember { Animatable(0f) }
    LaunchedEffect(dragging, motion) { emphasis.animateTo(if (dragging) 1f else 0f, motion.of(motion.fluid)) }

    val start = range.start
    val span = range.endInclusive - range.start
    val thumbR = with(density) { 11.dp.toPx() }

    fun fracOf(v: Float): Float = if (span <= 0f) 0f else ((v - start) / span).coerceIn(0f, 1f)
    fun snapFrac(f: Float): Float {
        if (steps <= 0) return f
        val n = steps + 1
        return (f * n).roundToInt() / n.toFloat()
    }
    fun valueAt(x: Float): Float {
        val usable = (widthPx - 2 * thumbR).coerceAtLeast(1f)
        return start + snapFrac(((x - thumbR) / usable).coerceIn(0f, 1f)) * span
    }
    fun commit(v: Float): Boolean {
        if (v == valueState.value) return false
        haptics.frequent()
        onChange.value(v)
        return true
    }
    fun nudge(direction: Int) {
        val unit = if (steps > 0) span / (steps + 1) else span / 20f
        val nv = (valueState.value + direction * unit).coerceIn(start, start + span)
        commit(start + snapFrac(fracOf(nv)) * span)
    }

    val bubbleShape = remember { RoundedCornerShape(10.dp) }
    val bubbleStyle = remember(t) { t.weight(t.monoS, 600, mono = true) }
    val thumbXPx: () -> Float = {
        val usable = (widthPx - 2 * thumbR).coerceAtLeast(0f)
        thumbR + fracOf(valueState.value) * usable
    }

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .fluxFocusRing(RoundedCornerShape(12.dp))
            .onKeyEvent { e ->
                if (!enabled || e.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (e.key) {
                        Key.DirectionRight, Key.DirectionUp -> { nudge(1); true }
                        Key.DirectionLeft, Key.DirectionDown -> { nudge(-1); true }
                        else -> false
                    }
                }
            }
            .focusable(enabled)
            .onSizeChanged { widthPx = it.width.toFloat(); heightPx = it.height.toFloat() }
            .pointerInput(enabled, start, span, steps) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    var changed = false
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (slop != null) {
                        changed = commit(valueAt(slop.position.x)) || changed
                        horizontalDrag(down.id) { change ->
                            changed = commit(valueAt(change.position.x)) || changed
                            change.consume()
                        }
                    } else {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed) changed = commit(valueAt(up.position.x))
                    }
                    dragging = false
                    if (changed) finished.value?.invoke()
                }
            }
            .semantics {
                contentDescription = label
                stateDescription = format(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range, steps)
                setProgress { v ->
                    val nv = start + snapFrac(fracOf(v)) * span
                    if (nv != valueState.value) onChange.value(nv)
                    true
                }
                if (!enabled) disabled()
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        ) {
            val cy = size.height / 2f
            val trackH = 4.dp.toPx()
            val trackR = CornerRadius(trackH / 2f)
            val trackTop = cy - trackH / 2f
            val e = emphasis.value
            val tx = thumbXPx()
            // 轨道
            drawRoundRect(c.glass4, Offset(0f, trackTop), Size(size.width, trackH), trackR)
            // 刻度
            if (ticks >= 2) {
                val tw = 1.dp.toPx()
                val th = 4.dp.toPx()
                val ty = cy + 8.dp.toPx()
                for (i in 0 until ticks) {
                    val x = i * (size.width - tw) / (ticks - 1)
                    drawRect(c.hairlineStrong, Offset(x, ty), Size(tw, th))
                }
            }
            // 填充（纯色 accent）
            if (tx > 0f) {
                clipRect(right = tx) {
                    drawRoundRect(c.accent, Offset(0f, trackTop), Size(size.width, trackH), trackR)
                }
            }
            // 拇指
            val tr = 11.dp.toPx()
            if (e > 0.001f) {
                drawCircle(c.accentLo.copy(alpha = c.accentLo.alpha * e), tr + 6.dp.toPx() * e, Offset(tx, cy))
            }
            drawCircle(Color.Black.copy(alpha = 0.16f), tr + 0.75.dp.toPx(), Offset(tx, cy + 0.5.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.3f), tr, Offset(tx, cy + 1.dp.toPx()))
            drawCircle(Color.White, tr, Offset(tx, cy))
            drawCircle(Color.Black.copy(alpha = 0.12f), tr - 0.25.dp.toPx(), Offset(tx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5.dp.toPx()))
        }
        // 气泡：零尺寸占位，按拇指位置放在其上方；α / scale / translateY 由 emphasis 驱动
        Box(
            Modifier
                .align(androidx.compose.ui.Alignment.TopStart)
                .layout { measurable, _ ->
                    val p = measurable.measure(Constraints())
                    layout(0, 0) {
                        // 气泡底边 = 拇指顶（中线 − 11dp）上方 3dp
                        val x = (thumbXPx() - p.width / 2f).roundToInt()
                        val y = (heightPx / 2f - 14.dp.toPx() - p.height).roundToInt()
                        p.placeRelative(x, y)
                    }
                }
                .graphicsLayer {
                    val e = emphasis.value
                    alpha = e.coerceIn(0f, 1f)
                    scaleX = 0.8f + 0.2f * e
                    scaleY = 0.8f + 0.2f * e
                    translationY = 6.dp.toPx() * (1f - e)
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .fluxGlass(FluxGlass.Sheet, bubbleShape, kind = FluxGlassKind.Flat)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            FluxText(format(value), style = bubbleStyle, color = c.ink, maxLines = 1)
        }
    }
}
