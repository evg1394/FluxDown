package com.fluxdown.fluxui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.FluxGlassKind
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme

/** 横幅语气：Info = accentHi · Warn = amber · Error = coral · Success = mint。 */
enum class FluxBannerKind { Info, Warn, Error, Success }

/**
 * 横幅（§12.29，Flat glass2 + 发丝线）：左侧 2dp 语气色条；Warn / Error 底按 7% / 8% 混入语气色。
 * Error 的 liveRegion 为 Assertive，其余 Polite。
 *
 * @param text 正文（sm / 1.5）
 * @param title 可选标题（body 600）
 * @param subtitle 次要说明（sm inkMuted）
 * @param icon 缺省：Info→Info · Warn→TriangleAlert · Error→CircleAlert · Success→CircleCheck
 * @param actions 动作槽（通常放 xs 按钮），位于正文下方，间距 6
 * @param onClose 非 null 时显示右上关闭钮
 * @param slim 紧凑：padding 10/10/10/14、r16、垂直居中
 */
@Composable
fun FluxBanner(
    text: String,
    modifier: Modifier = Modifier,
    kind: FluxBannerKind = FluxBannerKind.Info,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    slim: Boolean = false,
) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val tone = when (kind) {
        FluxBannerKind.Info -> c.accentHi
        FluxBannerKind.Warn -> c.amber
        FluxBannerKind.Error -> c.coral
        FluxBannerKind.Success -> c.mint
    }
    val tint = when (kind) {
        FluxBannerKind.Warn -> c.amber.copy(alpha = 0.07f)
        FluxBannerKind.Error -> c.coral.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val glyph = icon ?: when (kind) {
        FluxBannerKind.Info -> FluxIcons.Info
        FluxBannerKind.Warn -> FluxIcons.TriangleAlert
        FluxBannerKind.Error -> FluxIcons.CircleAlert
        FluxBannerKind.Success -> FluxIcons.CircleCheck
    }
    val shape = remember(slim) { RoundedCornerShape(if (slim) 16.dp else 20.dp) }
    val bodyStyle = remember(type) { type.sm.copy(lineHeight = 1.5.em) }
    val region = if (kind == FluxBannerKind.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite

    Box(
        modifier
            .fillMaxWidth()
            .fluxGlass(FluxGlass.G2, shape, kind = FluxGlassKind.Flat)
            .drawBehind { if (tint.alpha > 0f) drawRect(tint) }
            .semantics(mergeDescendants = true) { liveRegion = region },
    ) {
        // 左条：上下内缩 14，2dp 语气色
        Box(Modifier.matchParentSize().padding(vertical = 14.dp)) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(tone, RoundedCornerShape(1.dp)),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = if (slim) 14.dp else 16.dp, end = if (onClose != null) 4.dp else 12.dp)
                .padding(vertical = if (onClose != null) 6.dp else if (slim) 10.dp else 14.dp),
            verticalAlignment = if (slim) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FluxIcon(glyph, null, Modifier.padding(top = if (slim) 0.dp else 1.dp), size = 20.dp, tint = tone)
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 20.dp)
                    .padding(vertical = if (onClose != null) (if (slim) 4.dp else 8.dp) else 0.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                title?.let {
                    FluxText(it, Modifier.padding(bottom = 2.dp), style = type.weight(type.body, 600), color = c.ink)
                }
                FluxText(text, style = bodyStyle, color = c.ink)
                subtitle?.let {
                    FluxText(it, Modifier.padding(top = 2.dp), style = bodyStyle, color = c.inkMuted)
                }
                actions?.let {
                    Row(
                        Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = it,
                    )
                }
            }
            onClose?.let {
                OverlayIconButton(
                    FluxIcons.X,
                    "关闭",
                    it,
                    visualSize = 36.dp,
                    iconSize = 16.dp,
                    glass = false,
                )
            }
        }
    }
}
