package com.fluxdown.fluxui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier.Node
import kotlinx.coroutines.launch

/**
 * 无水波的按压指示：按下缩放到 [pressedScale]，松开用 `press` 弹簧回弹（8.7% 过冲）。
 * 作为 [androidx.compose.foundation.LocalIndication] 的默认实现。
 */
class FluxPressIndicationFactory(private val pressedScale: Float) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, pressedScale)
    override fun equals(other: Any?): Boolean = other is FluxPressIndicationFactory && other.pressedScale == pressedScale
    override fun hashCode(): Int = pressedScale.hashCode()
}

val FluxPressIndication = FluxPressIndicationFactory(0.97f)

private class PressNode(
    private val source: InteractionSource,
    private val pressedScale: Float,
) : Node(), DrawModifierNode {
    private val scaleAnim = Animatable(1f)
    private val pressSpring = FluxMotion(reduce = false).press.spec<Float>()

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                val target = when (i) {
                    is PressInteraction.Press -> pressedScale
                    is PressInteraction.Release, is PressInteraction.Cancel -> 1f
                    else -> return@collect
                }
                launch {
                    scaleAnim.animateTo(target, pressSpring) { invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val s = scaleAnim.value
        if (s == 1f) drawContent() else scale(s) { this@draw.drawContent() }
    }
}

/**
 * 所有可点组件的统一入口：按压缩放（无水波）+ 可选长按（触感 longPress）。
 * 视觉尺寸小于 48dp 的孤立控件请再叠加 [fluxTouchTarget]。
 */
@Composable
fun Modifier.fluxPressable(
    onClick: () -> Unit,
    scale: Float = 0.97f,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val haptics = FluxTheme.haptics
    val indication = remember(scale) { if (scale == 0.97f) FluxPressIndication else FluxPressIndicationFactory(scale) }
    return combinedClickable(
        interactionSource = interactionSource,
        indication = indication,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onLongClick = onLongClick?.let { cb -> { haptics.longPress(); cb() } },
        onClick = onClick,
    )
}

/** 命中区下限 48dp（不改变视觉尺寸的组件请用 Box 包裹后居中）。 */
fun Modifier.fluxTouchTarget(min: Dp = 48.dp): Modifier = sizeIn(minWidth = min, minHeight = min)

/** FluxUI 的文本原语：默认读 [LocalFluxTextStyle] / [LocalFluxContentColor]。 */
@Composable
fun FluxText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalFluxTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    softWrap: Boolean = true,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val c = color.takeOrElse { style.color.takeOrElse { FluxTheme.contentColor } }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = c),
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        onTextLayout = onTextLayout,
    )
}
