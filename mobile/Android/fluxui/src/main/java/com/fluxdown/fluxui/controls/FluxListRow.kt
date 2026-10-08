package com.fluxdown.fluxui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 列表行（§12.17）：min 56 · padding 10×16 · gap 14。行首 32dp 图标盘（[icon] + [iconTone]，盘本身中性）或自定义 [leading]；
 * 标题 body 450（[danger] = coral）；副标题 sm inkMuted；尾部 = [value]（sm inkMuted，最多占 48% 宽，单行省略）+ [trailing] + [chevron]。
 * 按压 / 键盘焦点底 `glass2`，[selected] 底 `accentLo`，禁用 α .45。[cloud] 在标题后加云标（随账号同步，带无障碍说明）。
 * 无涟漪。整行合并语义（标题、副标题、值）。放进 [GlassSection] 的 `row { }` 使用；有图标盘时记得 `row(hasIcon = true)`。
 * [role] 默认 Button；单选 / 多选 / 开关行请用 [FluxRadioRow] / [FluxCheckboxRow] / [FluxSwitchRow]。
 */
@Composable
fun FluxListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTone: Tone = Tone.Neutral,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    cloud: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    role: Role = Role.Button,
) {
    val c = FluxTheme.colors
    val source = remember { MutableInteractionSource() }
    val gesture = rowClickable(source, enabled, role, onClick, onLongClick, selected)
    ListRowImpl(
        title, modifier, subtitle, icon, iconTone, leading, value, trailing, chevron, cloud,
        titleColor = if (danger) c.coralText else Color.Unspecified,
        enabled = enabled, selected = selected, source = source, gesture = gesture, mergeText = true,
    )
}

/** 开关行：整行 `toggleable(Role.Switch)`，尾部 [FluxSwitch] 仅作显示。 */
@Composable
fun FluxSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTone: Tone = Tone.Neutral,
    cloud: Boolean = false,
    enabled: Boolean = true,
) {
    val haptics = FluxTheme.haptics
    val source = remember { MutableInteractionSource() }
    val gesture = Modifier.toggleable(
        value = checked,
        interactionSource = source,
        indication = null,
        enabled = enabled,
        role = Role.Switch,
        onValueChange = { haptics.tick(); onCheckedChange(it) },
    )
    ListRowImpl(
        title, modifier, subtitle, icon, iconTone, null, null,
        { FluxSwitch(checked, null, Modifier.clearAndSetSemantics { }) }, false, cloud,
        titleColor = Color.Unspecified, enabled = enabled, selected = false, source = source, gesture = gesture, mergeText = true,
    )
}

/** 单选行（pick 列表）：整行 `selectable(Role.RadioButton)`，选中底 `accentLo`，尾部 [FluxRadio] 仅作显示；[value] 为右侧附注（如计数）。 */
@Composable
fun FluxRadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTone: Tone = Tone.Neutral,
    value: String? = null,
    enabled: Boolean = true,
) {
    val haptics = FluxTheme.haptics
    val source = remember { MutableInteractionSource() }
    val gesture = Modifier.selectable(
        selected = selected,
        interactionSource = source,
        indication = null,
        enabled = enabled,
        role = Role.RadioButton,
        onClick = { haptics.tick(); onSelect() },
    )
    ListRowImpl(
        title, modifier, subtitle, icon, iconTone, null, value,
        { FluxRadio(selected, null, Modifier.clearAndSetSemantics { }) }, false, false,
        titleColor = Color.Unspecified, enabled = enabled, selected = selected, source = source, gesture = gesture, mergeText = true,
    )
}

/** 多选行：整行 `triStateToggleable(Role.Checkbox)`，尾部 [FluxCheckbox] 仅作显示。 */
@Composable
fun FluxCheckboxRow(
    title: String,
    state: ToggleableState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTone: Tone = Tone.Neutral,
    round: Boolean = false,
    enabled: Boolean = true,
) {
    val haptics = FluxTheme.haptics
    val source = remember { MutableInteractionSource() }
    val gesture = Modifier.triStateToggleable(
        state = state,
        interactionSource = source,
        indication = null,
        enabled = enabled,
        role = Role.Checkbox,
        onClick = { haptics.tick(); onClick() },
    )
    ListRowImpl(
        title, modifier, subtitle, icon, iconTone, null, null,
        { FluxCheckbox(state, null, Modifier.clearAndSetSemantics { }, round) }, false, false,
        titleColor = Color.Unspecified, enabled = enabled, selected = false, source = source, gesture = gesture, mergeText = true,
    )
}

/** 动作行（设置域 `action`）：标题染 [tone]（Accent / Coral），可选尾部加载环；loading 时忽略点击。 */
@Composable
fun FluxActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Accent,
    subtitle: String? = null,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val gesture = rowClickable(source, enabled && !loading, Role.Button, onClick, null, false)
    val spinnerColor = FluxTheme.colors.inkMuted
    ListRowImpl(
        title, modifier, subtitle, icon, tone, null, null,
        if (loading) { { InlineSpinner(18.dp, spinnerColor) } } else null, false, false,
        titleColor = tone.content(), enabled = enabled, selected = false, source = source, gesture = gesture, mergeText = true,
    )
}

/**
 * 步进行（设置域 `stepper`）：标题 + 描述，尾部 [FluxStepper]。字体 ≥ 150% 时步进器换行到标题下方。
 * 浮点步长见 [FluxStepper]（放大整数 + [format]）。
 */
@Composable
fun FluxStepperRow(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    step: Int = 1,
    format: (Int) -> String = { it.toString() },
    editable: Boolean = false,
    enabled: Boolean = true,
    cloud: Boolean = false,
) {
    val name = if (subtitle == null) title else "$title，$subtitle"
    val stepper: @Composable (Modifier) -> Unit = { m ->
        FluxStepper(value, onValueChange, range, m, step, format, name, editable = editable, enabled = enabled)
    }
    if (FluxTheme.type.fontScale >= 1.5f) {
        Column(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RowTitleBlock(title, subtitle, cloud, Color.Unspecified, Modifier.clearAndSetSemantics { })
            stepper(Modifier)
        }
    } else {
        ListRowImpl(
            title, modifier, subtitle, null, Tone.Neutral, null, null,
            { stepper(Modifier) }, false, cloud,
            titleColor = Color.Unspecified, enabled = true, selected = false,
            source = remember { MutableInteractionSource() }, gesture = Modifier, mergeText = false,
        )
    }
}

/** 字段行（设置域 `field`）：标题 + 描述在上，下方整宽放 [content]（通常是 [FluxField] / [FluxNumberField]）。 */
@Composable
fun FluxFieldRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    cloud: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RowTitleBlock(title, subtitle, cloud, Color.Unspecified, Modifier)
        content()
    }
}

@Composable
private fun rowClickable(
    source: MutableInteractionSource,
    enabled: Boolean,
    role: Role,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    selected: Boolean,
): Modifier {
    val haptics = FluxTheme.haptics
    return if (onClick != null || onLongClick != null) {
        Modifier
            .combinedClickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = role,
                onLongClick = onLongClick?.let { cb -> { haptics.longPress(); cb() } },
                onClick = onClick ?: {},
            )
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
    } else {
        Modifier.semantics(mergeDescendants = true) { if (selected) this.selected = true }
    }
}

@Composable
private fun RowTitleBlock(
    title: String,
    subtitle: String?,
    cloud: Boolean,
    titleColor: Color,
    modifier: Modifier,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val titleStyle = remember(t) { t.weight(t.body, 450).copy(lineHeight = 1.3.em) }
    val subStyle = remember(t) { t.sm.copy(lineHeight = 1.35.em) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FluxText(
                title,
                style = titleStyle,
                color = if (titleColor == Color.Unspecified) c.ink else titleColor,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (cloud) FluxIcon(FluxIcons.Cloud, "已云同步", Modifier.padding(start = 6.dp), 13.dp, c.inkFaint)
        }
        if (subtitle != null) {
            FluxText(subtitle, style = subStyle, color = c.inkMuted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ListRowImpl(
    title: String,
    modifier: Modifier,
    subtitle: String?,
    icon: ImageVector?,
    iconTone: Tone,
    leading: (@Composable () -> Unit)?,
    value: String?,
    trailing: (@Composable () -> Unit)?,
    chevron: Boolean,
    cloud: Boolean,
    titleColor: Color,
    enabled: Boolean,
    selected: Boolean,
    source: MutableInteractionSource,
    gesture: Modifier,
    mergeText: Boolean,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val valueStyle = remember(t) { t.sm }
    val tile = FluxTheme.shapes.tileSm

    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .then(gesture)
            .drawBehind {
                if (selected) drawRect(c.accentLo)
                if (pressed || focused) drawRect(c.glass2)
            }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        } else if (icon != null) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(c.glass2, tile)
                    .border(0.5.dp, c.hairline, tile),
                contentAlignment = Alignment.Center,
            ) {
                FluxIcon(icon, null, size = 18.dp, tint = iconTone.content())
            }
        }
        RowTitleBlock(
            title, subtitle, cloud, titleColor,
            Modifier.weight(1f).then(if (mergeText) Modifier else Modifier.clearAndSetSemantics { }),
        )
        if (value != null || trailing != null || chevron) {
            Row(
                if (trailing == null) Modifier.maxWidthFraction(0.48f) else Modifier,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (value != null) {
                    FluxText(value, style = valueStyle, color = c.inkMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                }
                trailing?.invoke()
                if (chevron) FluxIcon(FluxIcons.ChevronRight, null, size = 18.dp, tint = c.inkFaint)
            }
        }
    }
}

/** 最大宽度限制为可用宽度的 [fraction]（`.lv { max-width: 48% }`）。 */
private fun Modifier.maxWidthFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val maxW = if (constraints.maxWidth == Constraints.Infinity) {
        constraints.maxWidth
    } else {
        min(constraints.maxWidth, (constraints.maxWidth * fraction).roundToInt())
    }
    val p = measurable.measure(constraints.copy(maxWidth = maxW))
    layout(p.width, p.height) { p.place(0, 0) }
}
