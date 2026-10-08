package com.fluxdown.fluxui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme

/**
 * 标签（§12.36，不可交互）：高 ≥ 20 · r6 · 水平 7 · gap 5，文字 monoS 600 +2%，可选 11dp 图标。
 * Neutral = glass2 底 + hairline + inkMuted 字；Accent = accentLo 底 + accent@32% 描边 + accentHi 字；
 * Coral / Mint / Amber = 填充色 12% 底 + 30% 描边 + `*-t` 字。
 */
@Composable
fun FluxTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    icon: ImageVector? = null,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val shape = remember { RoundedCornerShape(6.dp) }
    val style = remember(t) { t.weight(t.monoS, 600, mono = true).copy(letterSpacing = 0.02.em) }
    val fg = if (tone == Tone.Neutral) c.inkMuted else tone.content()
    val bg = when (tone) {
        Tone.Neutral -> c.glass2
        Tone.Accent -> c.accentLo
        Tone.Coral -> c.coral.copy(alpha = 0.12f)
        Tone.Mint -> c.mint.copy(alpha = 0.12f)
        Tone.Amber -> c.amber.copy(alpha = 0.12f)
    }
    val stroke = when (tone) {
        Tone.Neutral -> c.hairline
        Tone.Accent -> c.accent.copy(alpha = 0.32f)
        Tone.Coral -> c.coral.copy(alpha = 0.3f)
        Tone.Mint -> c.mint.copy(alpha = 0.3f)
        Tone.Amber -> c.amber.copy(alpha = 0.3f)
    }
    Row(
        modifier
            .background(bg, shape)
            .border(0.5.dp, stroke, shape)
            .heightIn(min = 20.dp)
            .padding(horizontal = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) FluxIcon(icon, null, size = 11.dp, tint = fg)
        FluxText(text, style = style, color = fg, maxLines = 1)
    }
}
