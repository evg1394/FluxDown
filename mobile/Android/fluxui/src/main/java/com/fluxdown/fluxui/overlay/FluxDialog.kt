package com.fluxdown.fluxui.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.material.FluxBlur
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.material.fluxAccentSurface
import com.fluxdown.fluxui.material.fluxGlow
import com.fluxdown.fluxui.material.softShadow
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxPressable

/**
 * 对话框按钮样式（全部为 50dp 全圆角胶囊，扁平纯色）：Primary = 强调色实心面；
 * Secondary = 中性浅底（取消 / 普通动作）；Destructive = coral 浅底 + coral 字。
 */
enum class FluxDialogButtonStyle { Primary, Secondary, Destructive }

/**
 * @param keepOpen true 时点击后不关闭对话框（对应规范 `onClick` 返回 false）
 */
@Immutable
class FluxDialogButton(
    val label: String,
    val style: FluxDialogButtonStyle = FluxDialogButtonStyle.Secondary,
    val keepOpen: Boolean = false,
    val onClick: () -> Unit = {},
)

/**
 * 对话框描述。
 * @param buttons 声明顺序与横排顺序一致：取消在前、主操作在后（横排时主操作在右）。
 *   纵排（[stacked] 或超过 2 个按钮）时逆序自上而下：主操作在最上、取消在最下（对齐 iOS / Material 纵排约定）。
 * @param dismissible false = 点遮罩无效、返回键被忽略并触发 reject（强制决策）
 * @param onDismiss 点遮罩 / 返回键取消时回调（在关闭之前）
 * @param danger 图标盘使用 coral 色；缺省自动：存在 Destructive 按钮即视为危险
 * @param stacked 按钮纵向全宽排列（超过 2 个按钮时自动纵排）
 * @param body 消息下方的自定义内容槽
 */
@Immutable
class FluxDialogSpec(
    val title: String,
    val buttons: List<FluxDialogButton>,
    val message: String? = null,
    val icon: ImageVector? = null,
    val dismissible: Boolean = true,
    val stacked: Boolean = false,
    val danger: Boolean? = null,
    val onDismiss: (() -> Unit)? = null,
    val body: (@Composable ColumnScope.() -> Unit)? = null,
)

internal class DialogEntry(val id: Long, val spec: FluxDialogSpec)

/**
 * z85。遮罩 dim + 模糊 10；对话框 `scale 1.06→1`、`alpha` 入场（`page` 弹簧，无回弹、无模糊：文字始终清晰），
 * 卡面带向下环境投影与背景分层。
 */
@Composable
internal fun DialogLayer(state: FluxOverlayState) {
    val entry = state.dialogEntry
    val menuOpen = state.menuEntry != null
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val prog = rememberOverlayProgress()
    val last = remember { Last<DialogEntry>() }
    if (entry != null) last.value = entry

    LaunchedEffect(entry) {
        if (entry != null) prog.enter(motion.of(motion.page), restartAt = 0f) else prog.exit(motion.of(motion.snap))
    }
    val cancel = {
        entry?.let {
            if (it.spec.dismissible) {
                it.spec.onDismiss?.invoke()
                state.dismissDialog()
            } else {
                haptics.reject()
            }
        }
        Unit
    }
    BackHandler(enabled = entry != null && !menuOpen, onBack = cancel)

    val shown = last.value
    if (shown == null || !prog.present) return
    val spec = shown.spec

    val colors = FluxTheme.colors
    val type = FluxTheme.type
    val danger = spec.danger ?: spec.buttons.any { it.style == FluxDialogButtonStyle.Destructive }
    val messageStyle = remember(type) { type.body.copy(lineHeight = 1.5.em) }
    val shape = FluxTheme.shapes.sheet
    val stacked = spec.stacked || spec.buttons.size > 2

    Box(
        Modifier
            .fillMaxSize()
            .fluxScrim(10.dp) { prog.value }
            .then(
                if (entry != null && spec.dismissible) {
                    Modifier.pointerInput(entry.id) { detectTapGestures { cancel() } }
                } else {
                    Modifier.pointerInput(Unit) { detectTapGestures { } }
                },
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val v = prog.value
                    alpha = v.coerceIn(0f, 1f)
                    val s = 1.06f - 0.06f * v
                    scaleX = s
                    scaleY = s
                }
                .fluxGlow(colors.softShadow(0.55f), 28.dp, shape, spread = (-12).dp, dy = 18.dp)
                .fluxGlass(FluxGlass.Sheet, shape, FluxBlur.Thick)
                .swallowTaps()
                .semantics { paneTitle = spec.title }
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 20.dp),
        ) {
            spec.icon?.let { icon ->
                Box(
                    Modifier
                        .padding(bottom = 16.dp)
                        .size(44.dp)
                        .background(
                            if (danger) colors.coral.copy(alpha = 0.12f) else colors.accentLo,
                            FluxTheme.shapes.tile,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    FluxIcon(icon, null, size = 22.dp, tint = if (danger) colors.coralText else colors.accentHi)
                }
            }
            FluxText(
                spec.title,
                modifier = Modifier.semantics { heading() },
                style = type.h1,
                color = colors.ink,
            )
            spec.message?.let {
                FluxText(
                    it,
                    modifier = Modifier.padding(top = 8.dp),
                    style = messageStyle,
                    color = colors.inkMuted,
                )
            }
            spec.body?.let { body ->
                Column(Modifier.padding(top = 14.dp)) { body() }
            }
            if (stacked) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    spec.buttons.asReversed().forEach { b ->
                        DialogButton(b, Modifier.fillMaxWidth()) { runButton(state, b) }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    spec.buttons.forEach { b ->
                        DialogButton(b, Modifier.weight(1f).fillMaxHeight()) { runButton(state, b) }
                    }
                }
            }
        }
    }
}

private fun runButton(state: FluxOverlayState, b: FluxDialogButton) {
    if (!b.keepOpen) state.dismissDialog()
    b.onClick()
}

@Composable
private fun DialogButton(button: FluxDialogButton, modifier: Modifier, onClick: () -> Unit) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val shape = FluxTheme.shapes.full
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val label = button.label
    val labelStyle = remember(type) { type.weight(type.body, 600).copy(textAlign = TextAlign.Center) }

    val base = modifier
        .heightIn(min = 50.dp)
        .fluxPressable(onClick, role = Role.Button, interactionSource = source)
    val styled = when (button.style) {
        FluxDialogButtonStyle.Primary -> base.fluxAccentSurface(c, shape)
        FluxDialogButtonStyle.Secondary -> base.background(c.ink.copy(alpha = if (pressed) 0.11f else 0.06f), shape)
        FluxDialogButtonStyle.Destructive -> base.background(c.coral.copy(alpha = if (pressed) 0.20f else 0.12f), shape)
    }
    val fg = when (button.style) {
        FluxDialogButtonStyle.Primary -> c.onAccent
        FluxDialogButtonStyle.Secondary -> c.ink
        FluxDialogButtonStyle.Destructive -> c.coralText
    }
    Box(
        styled
            .semantics {
                if (button.style == FluxDialogButtonStyle.Destructive) contentDescription = "危险：$label"
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        FluxText(label, style = labelStyle, color = fg, maxLines = 2)
    }
}
