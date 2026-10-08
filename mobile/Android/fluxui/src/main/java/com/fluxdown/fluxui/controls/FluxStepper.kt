package com.fluxdown.fluxui.controls

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.FluxGlassKind
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.theme.FluxPressIndicationFactory
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 步进器（§12.20，Compose 高 48）：− 值 +。点按步进（触感 `tick`）；按住 400 ms 后连加（`frequent`），
 * 到边界停止并 `reject`；边界处按钮 α .3 且禁用。数值变化以 8dp 竖向位移 + 淡入滚动。
 *
 * 浮点（如分享率 0.1 步长）：用放大后的整数承载，并通过 [format] 显示（例如 `value = 15` → "1.5"）。
 * [editable] = true 时点击数值变成数字输入（失焦 / 完成键提交，钳制到 [range]；[parse] 把输入还原成整数）。
 * [label] 为无障碍名称；语义含 stateDescription 与 增加 / 减少 自定义动作。
 */
@Composable
fun FluxStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    format: (Int) -> String = { it.toString() },
    label: String,
    editable: Boolean = false,
    parse: (String) -> Int? = { it.trim().toIntOrNull() },
    enabled: Boolean = true,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val density = LocalDensity.current
    val valueState = rememberUpdatedState(value)
    val onChange = rememberUpdatedState(onValueChange)
    val rangeState = rememberUpdatedState(range)

    /** 步进一次；返回 false 表示已在边界（并给出 reject 触感）。 */
    fun stepBy(direction: Int, repeat: Boolean): Boolean {
        val r = rangeState.value
        val nv = (valueState.value + direction * step).coerceIn(r.first, r.last)
        if (nv == valueState.value) {
            haptics.reject()
            return false
        }
        if (repeat) haptics.frequent() else haptics.tick()
        onChange.value(nv)
        return true
    }

    val shape = remember { RoundedCornerShape(20.dp) }
    val valueStyle = remember(t) {
        t.weight(t.body, 600, mono = true).copy(letterSpacing = (-0.01f).em, fontFeatureSettings = "tnum", textAlign = TextAlign.Center)
    }
    val canDec = enabled && value - step >= range.first
    val canInc = enabled && value + step <= range.last
    var editing by remember { mutableStateOf(false) }
    val offsetPx = with(density) { 8.dp.roundToPx() }

    Row(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .fluxGlass(FluxGlass.G2, shape, kind = FluxGlassKind.Flat, strongLine = false)
            .heightIn(min = 40.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = format(value)
                customActions = listOf(
                    CustomAccessibilityAction("增加") { if (canInc) stepBy(1, false) else false },
                    CustomAccessibilityAction("减少") { if (canDec) stepBy(-1, false) else false },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(FluxIcons.Minus, canDec) { rep -> stepBy(-1, rep) }
        Box(
            Modifier
                .widthIn(min = 54.dp)
                .heightIn(min = 40.dp)
                .then(
                    if (editable && enabled && !editing) {
                        Modifier.clickable(interactionSource = null, indication = null) { editing = true }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (editing) {
                StepperEditor(
                    initial = format(value),
                    style = valueStyle,
                    onCommit = { text ->
                        editing = false
                        val parsed = parse(text)
                        if (parsed != null) {
                            val r = rangeState.value
                            val clamped = parsed.coerceIn(r.first, r.last)
                            if (clamped != parsed) haptics.reject()
                            if (clamped != valueState.value) onChange.value(clamped)
                        } else {
                            haptics.reject()
                        }
                    },
                )
            } else {
                AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        val spec = motion.fluid
                        (slideInVertically(motion.of<IntOffset>(spec)) { dir * offsetPx } + fadeIn(motion.of<Float>(spec))) togetherWith
                            (slideOutVertically(motion.of<IntOffset>(spec)) { -dir * offsetPx } + fadeOut(motion.of<Float>(spec))) using
                            SizeTransform(clip = false)
                    },
                    label = "stepper-value",
                ) { v ->
                    FluxText(format(v), style = valueStyle, color = c.ink, maxLines = 1)
                }
            }
        }
        StepButton(FluxIcons.Plus, canInc) { rep -> stepBy(1, rep) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, enabled: Boolean, onStep: (repeat: Boolean) -> Boolean) {
    val c = FluxTheme.colors
    val source = remember { MutableInteractionSource() }
    val press = remember { FluxPressIndicationFactory(0.88f) }
    val stepState = rememberUpdatedState(onStep)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .widthIn(min = 46.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .indication(source, press)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        val down = PressInteraction.Press(offset)
                        source.emit(down)
                        var repeated = false
                        var released = false
                        try {
                            coroutineScope {
                                val job = launch {
                                    delay(400)
                                    repeated = true
                                    while (stepState.value(true)) delay(70)
                                }
                                released = tryAwaitRelease()
                                job.cancel()
                                if (released && !repeated) stepState.value(false)
                            }
                        } finally {
                            source.tryEmit(if (released) PressInteraction.Release(down) else PressInteraction.Cancel(down))
                        }
                    },
                )
            }
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        FluxIcon(icon, null, size = 18.dp, tint = c.ink)
    }
}

@Composable
private fun StepperEditor(initial: String, style: androidx.compose.ui.text.TextStyle, onCommit: (String) -> Unit) {
    val c = FluxTheme.colors
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val commit = rememberUpdatedState(onCommit)
    var hadFocus by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val cursor = remember(c) { SolidColor(c.accentHi) }
    BasicTextField(
        value = field,
        onValueChange = { v -> field = v.copy(text = v.text.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }.take(12)) },
        modifier = Modifier
            .widthIn(min = 54.dp)
            .requiredSize(width = 54.dp, height = 28.dp)
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) {
                    hadFocus = true
                } else if (hadFocus && !done) {
                    done = true
                    commit.value(field.text)
                }
            },
        textStyle = style.copy(color = c.ink),
        singleLine = true,
        cursorBrush = cursor,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (!done) {
                done = true
                commit.value(field.text)
            }
        }),
        decorationBox = { inner -> Box(Modifier.size(54.dp, 28.dp), contentAlignment = Alignment.Center) { inner() } },
    )
}
