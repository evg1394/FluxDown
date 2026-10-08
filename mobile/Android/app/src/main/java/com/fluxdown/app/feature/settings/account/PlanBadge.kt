package com.fluxdown.app.feature.settings.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fluxdown.core.protocol.AccountRules
import com.fluxdown.core.protocol.CloudPlan
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme

/**
 * 套餐徽标（只读；同 iOS `PlanBadgeView` / GPUI `profile.rs::plan_tag` / Web `PlanBadge.tsx`）：
 * outline | solid | medal | ribbon | plain，纯色无渐变。仅当 `CloudPlan.badge` 非空时显示，不含任何购买入口。
 */
@Composable
internal fun PlanBadge(plan: CloudPlan, ordinal: Long?, modifier: Modifier = Modifier) {
    val text = AccountRules.planBadgeText(plan, ordinal) ?: return
    val accent = FluxTheme.colors.accent
    // 云端下发的徽标色（RRGGBB / AARRGGBB）；非法回退强调色。
    val color = remember(plan.badgeColor, accent) {
        AccountRules.parseBadgeColor(plan.badgeColor)
            ?.let { Color(it.red.toFloat(), it.green.toFloat(), it.blue.toFloat(), it.alpha.toFloat()) }
            ?: accent
    }
    val t = FluxTheme.type
    val style = remember(t) { t.weight(t.monoS, 600, mono = true) }
    val capsule = CircleShape
    val base = modifier
        .heightIn(min = 20.dp)
        .semantics { contentDescription = text }
    when (plan.badgeStyle) {
        "outline" -> Row(
            base
                .clip(capsule)
                .background(color.copy(alpha = color.alpha * 0.08f))
                .border(1.dp, color, capsule)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxIcon(FluxIcons.Crown, null, size = 11.dp, tint = color)
            FluxText(text, style = style, color = color, maxLines = 1)
        }
        "solid" -> Row(
            base
                .clip(capsule)
                .background(color)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxText(text, style = style, color = Color.White, maxLines = 1)
        }
        "medal" -> Row(
            base
                .height(IntrinsicSize.Min)
                .clip(capsule)
                .border(1.dp, color, capsule),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.fillMaxHeight().background(color).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                FluxIcon(FluxIcons.Crown, null, size = 11.dp, tint = Color.White)
            }
            FluxText(text, Modifier.padding(horizontal = 6.dp), style = style, color = color, maxLines = 1)
        }
        "ribbon" -> Row(
            base
                .background(color, RibbonShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxText(text, style = style, color = Color.White, maxLines = 1)
        }
        else -> Row(
            base
                .clip(capsule)
                .background(color.copy(alpha = color.alpha * 0.12f))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxText(text, style = style, color = color, maxLines = 1)
        }
    }
}

/** 左右两端内凹的丝带。 */
private object RibbonShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val notch = size.height * 0.35f
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width - notch, size.height / 2f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            lineTo(notch, size.height / 2f)
            close()
        }
        return Outline.Generic(path)
    }
}
