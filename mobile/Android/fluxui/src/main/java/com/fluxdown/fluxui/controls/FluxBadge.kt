package com.fluxdown.fluxui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme

enum class BadgeTone { Accent, Coral, Quiet }

/**
 * 角标（§12.37）：[count] 非空 = 计数胶囊（min 18×18 · r9，超过 99 显示 “99+”，micro 档 mono 600）；null = 8×8 状态点。
 * 不单独朗读——请把数量并入所属项的 contentDescription。
 */
@Composable
fun FluxBadge(
    count: Int?,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.Accent,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val bg = when (tone) {
        BadgeTone.Accent -> c.accent
        BadgeTone.Coral -> c.coral
        BadgeTone.Quiet -> c.glass3
    }
    val fg = when (tone) {
        BadgeTone.Accent -> c.onAccent
        BadgeTone.Coral -> c.onCoral
        BadgeTone.Quiet -> c.inkMuted
    }
    if (count == null) {
        Box(modifier.size(8.dp).background(bg, CircleShape).clearAndSetSemantics { })
    } else {
        val style = remember(t) { t.weight(t.micro, 600, mono = true).copy(letterSpacing = 0.em, textAlign = TextAlign.Center) }
        val shape = remember { RoundedCornerShape(9.dp) }
        Box(
            modifier
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                .background(bg, shape)
                .padding(horizontal = 5.dp)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            FluxText(if (count > 99) "99+" else count.toString(), style = style, color = fg, maxLines = 1)
        }
    }
}
