package com.fluxdown.fluxui.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.FluxGlassKind
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.theme.FluxTheme

/** [GlassSection] 的内容构建域：按顺序登记行 / 自由内容，组件负责行间发丝线。 */
@Stable
interface GlassSectionScope {
    /**
     * 一行（通常是 [FluxListRow] 系列 / [FluxKeyValue]）。相邻两行之间自动插入 0.5dp 发丝线；
     * 线的左缩进：两行都带行首图标盘 → 62dp，否则 16dp。[hasIcon] 标明本行是否有 32dp 行首图标盘。
     */
    fun row(hasIcon: Boolean = false, content: @Composable () -> Unit)

    /** 自由内容（统计、滑杆、字段……）；[padded] = 内边距 16（`gsec-pad`）。前后不插发丝线。 */
    fun custom(padded: Boolean = false, content: @Composable () -> Unit)
}

private class SectionItem(val isRow: Boolean, val hasIcon: Boolean, val padded: Boolean, val content: @Composable () -> Unit)

private class SectionScopeImpl : GlassSectionScope {
    val items = ArrayList<SectionItem>()
    override fun row(hasIcon: Boolean, content: @Composable () -> Unit) {
        items += SectionItem(true, hasIcon, false, content)
    }

    override fun custom(padded: Boolean, content: @Composable () -> Unit) {
        items += SectionItem(false, false, padded, content)
    }
}

/**
 * 烟晶分组（§12.16）：成组内容唯一容器（不在卡片里嵌卡片、不做逐行卡片）。
 * [title]（micro，卡片外上方，可带右侧 [action]）→ r24 Flat 玻璃卡（[level] 默认 G1，实填不模糊，溢出裁剪）→ [footer]（sm inkFaint 脚注）。
 * 注意：Flat 玻璃，所以可以放进 LazyColumn 的 item。
 */
@Composable
fun GlassSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    action: (@Composable () -> Unit)? = null,
    level: FluxGlass = FluxGlass.G1,
    content: GlassSectionScope.() -> Unit,
) {
    val items = SectionScopeImpl().apply(content).items
    val card = FluxTheme.shapes.card
    Column(modifier) {
        if (title != null || action != null) FluxSectionTitle(title.orEmpty(), action = action)
        Column(
            Modifier
                .fillMaxWidth()
                .fluxGlass(level, card, kind = FluxGlassKind.Flat, strongLine = false)
                .clip(card),
        ) {
            items.forEachIndexed { i, item ->
                val prev = items.getOrNull(i - 1)
                if (item.isRow && prev != null && prev.isRow) {
                    FluxDivider(startInset = if (item.hasIcon && prev.hasIcon) 62.dp else 16.dp)
                }
                if (item.padded) Box(Modifier.padding(16.dp)) { item.content() } else item.content()
            }
        }
        if (footer != null) FluxSectionFoot(footer)
    }
}
