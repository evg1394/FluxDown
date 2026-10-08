package com.fluxdown.fluxui.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxPressable

/**
 * 选择字段（§12.22）：外观同 [FluxField]，值区为文本，尾部 `ChevronsUpDown` 18 inkMuted。
 * 只是“字段”——点击回调 [onClick]，由应用打开选择器 Sheet（内容可用 [FluxPickList]）。
 * [value] 为 null 时显示 [placeholder]（inkFaint）。语义 `Role.DropdownList`，状态“折叠”。
 */
@Composable
fun FluxSelect(
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "请选择",
    hint: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val style = remember(t) { t.body.copy(lineHeight = 1.35.em) }
    val shown = value ?: placeholder
    FieldFrame(
        modifier = modifier,
        label = label,
        count = null,
        hint = hint,
        error = error,
        warning = null,
        enabled = enabled,
        boxModifier = Modifier
            .fluxPressable(onClick = onClick, scale = 0.985f, enabled = enabled, role = Role.DropdownList)
            .semantics {
                contentDescription = if (label != null) "$label，$shown" else shown
                stateDescription = "折叠"
            },
    ) {
        if (leadingIcon != null) FluxIcon(leadingIcon, null, size = 18.dp, tint = c.inkMuted)
        Box(Modifier.weight(1f).padding(vertical = FieldTextPadding), contentAlignment = Alignment.CenterStart) {
            FluxText(shown, style = style, color = if (value == null) c.inkFaint else c.ink)
        }
        FluxIcon(FluxIcons.ChevronsUpDown, null, size = 18.dp, tint = c.inkMuted)
    }
}

/** 选择器选项。 */
@Immutable
data class SelectOption<T>(
    val value: T,
    val label: String,
    val hint: String? = null,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
)

/**
 * 选择器内容：一个 [GlassSection] 单选行列表（选中项 = `accentLo` 底 + 单选点）。
 * 放进应用的选择 Sheet；选一项后触感 `tick` 由行完成，是否关闭 Sheet 由调用方在 [onSelect] 里决定。
 */
@Composable
fun <T> FluxPickList(
    options: List<SelectOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
) {
    GlassSection(modifier.selectableGroup(), title = title, footer = footer) {
        for (o in options) {
            row(hasIcon = o.icon != null) {
                FluxRadioRow(
                    title = o.label,
                    selected = o.value == selected,
                    onSelect = { onSelect(o.value) },
                    subtitle = o.hint,
                    icon = o.icon,
                    enabled = o.enabled,
                )
            }
        }
    }
}
