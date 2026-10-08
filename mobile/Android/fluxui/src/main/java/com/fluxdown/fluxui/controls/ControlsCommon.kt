package com.fluxdown.fluxui.controls

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.fluxdown.fluxui.theme.FluxTheme
import kotlin.math.max
import kotlin.math.min

/** 语义色调：图标色 / 标签 / 状态点共用。`Neutral` = 普通墨色。 */
enum class Tone { Neutral, Accent, Coral, Mint, Amber }

/** 色调对应的“文字 / 图标”色（`*-t` 可读档）。 */
@Composable
@ReadOnlyComposable
internal fun Tone.content(): Color {
    val c = FluxTheme.colors
    return when (this) {
        Tone.Neutral -> c.ink
        Tone.Accent -> c.accentHi
        Tone.Coral -> c.coralText
        Tone.Mint -> c.mintText
        Tone.Amber -> c.amberText
    }
}

/** 色调对应的填充色（点 / 描边基色）。 */
@Composable
@ReadOnlyComposable
internal fun Tone.fill(): Color {
    val c = FluxTheme.colors
    return when (this) {
        Tone.Neutral -> c.inkFaint
        Tone.Accent -> c.accent
        Tone.Coral -> c.coral
        Tone.Mint -> c.mint
        Tone.Amber -> c.amber
    }
}

/** 向外（d > 0）/ 向内扩张轮廓，圆角半径同步增减（同心圆角）。 */
internal fun Outline.inflate(d: Float): Outline = when (this) {
    is Outline.Rectangle -> Outline.Rectangle(rect.inflate(d))
    is Outline.Rounded -> {
        val r = roundRect
        fun grow(c: CornerRadius) = if (c.x == 0f && c.y == 0f) c else CornerRadius(max(0f, c.x + d), max(0f, c.y + d))
        Outline.Rounded(
            RoundRect(
                r.left - d, r.top - d, r.right + d, r.bottom + d,
                grow(r.topLeftCornerRadius), grow(r.topRightCornerRadius),
                grow(r.bottomRightCornerRadius), grow(r.bottomLeftCornerRadius),
            ),
        )
    }
    is Outline.Generic -> this
}

/**
 * §10.6 键盘 / D-pad 焦点环：1.5dp 实色 `accentHi` + 4dp 间隙；触屏焦点不显示。
 *
 * 必须放在 `clickable / toggleable / selectable / focusable` **之前**（先 onFocusChanged 再 focusable），
 * 环画在被修饰节点之外，所以不会随按压缩放。控件视觉小于修饰节点（48dp 命中区包住 24dp 控件）时，
 * 用 [visualWidth] / [visualHeight] 指定视觉尺寸（居中）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.fluxFocusRing(
    shape: Shape,
    visualWidth: Dp = Dp.Unspecified,
    visualHeight: Dp = Dp.Unspecified,
): Modifier {
    val c = FluxTheme.colors
    val input = LocalInputModeManager.current
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.hasFocus }
        .drawWithCache {
            val gap = 4.dp.toPx()
            val sw = 1.5.dp.toPx()
            val vw = if (visualWidth.isSpecified) min(size.width, visualWidth.toPx()) else size.width
            val vh = if (visualHeight.isSpecified) min(size.height, visualHeight.toPx()) else size.height
            val dx = (size.width - vw) / 2f
            val dy = (size.height - vh) / 2f
            val base = shape.createOutline(Size(vw, vh), layoutDirection, this)
            val ring = base.inflate(gap + sw / 2f)
            onDrawWithContent {
                drawContent()
                if (focused && input.inputMode == InputMode.Keyboard) {
                    translate(dx, dy) {
                        drawOutline(ring, c.accentHi, style = Stroke(sw))
                    }
                }
            }
        }
}

/** 环形加载指示（按钮 loading）：底圈 18% + 90° 圆端弧，1 s 线性旋转；Reduce motion 静止。 */
@Composable
internal fun InlineSpinner(size: Dp, color: Color, modifier: Modifier = Modifier) {
    val reduce = FluxTheme.motion.reduce
    val rot = if (reduce) null else rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    Canvas(modifier.size(size)) {
        val sw = 2.dp.toPx()
        val d = Size(this.size.width - sw, this.size.height - sw)
        val tl = Offset(sw / 2f, sw / 2f)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, tl, d, style = Stroke(sw))
        rotate(rot?.value ?: 0f) {
            drawArc(color, -90f, 90f, false, tl, d, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

/** 布局上两侧各内缩 [amount]（等价 CSS 负 margin），内容仍按原尺寸绘制。 */
internal fun Modifier.horizontalOverlap(amount: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val a = amount.roundToPx()
    layout(max(0, p.width - 2 * a), p.height) { p.place(-a, 0) }
}
