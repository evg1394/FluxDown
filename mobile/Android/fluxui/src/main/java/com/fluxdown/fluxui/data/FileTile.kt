package com.fluxdown.fluxui.data

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.material.FluxGlass
import com.fluxdown.fluxui.material.FluxGlassKind
import com.fluxdown.fluxui.material.fluxGlass
import com.fluxdown.fluxui.theme.FileCategory
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxPressable

/** 图块尺寸：sm 32 / r10 / 图标 17；md 40 / r12 / 图标 20；lg 56 / r17 / 图标 26。 */
enum class FileTileSize(val dp: Dp, val iconDp: Dp) {
    Sm(32.dp, 17.dp), Md(40.dp, 20.dp), Lg(56.dp, 26.dp)
}

/** 分类 → 图块图标（film / music / file-text / image / cpu / archive / library / file）；BT 任务且分类为 other 用 magnet。 */
fun fileTileIcon(category: FileCategory, bt: Boolean = false): ImageVector = when (category) {
    FileCategory.Video -> FluxIcons.Film
    FileCategory.Audio -> FluxIcons.Music
    FileCategory.Document -> FluxIcons.FileText
    FileCategory.Image -> FluxIcons.Image
    FileCategory.Program -> FluxIcons.Cpu
    FileCategory.Archive -> FluxIcons.Archive
    FileCategory.Ebook -> FluxIcons.Library
    FileCategory.Other -> if (bt) FluxIcons.Magnet else FluxIcons.File
}

/**
 * 文件图块：glass2 + 发丝线 + 顶沿高光（Flat，可安全用于列表项）+ 单色线性图标；
 * 右上角 (−2,−2) 为 6dp 分类色点（2dp canvas 色环 + 8dp 同色辉光），[categoryColor] 为 null 时不画点。
 *
 * - 选择模式：底 glass3；[selected] 时覆盖层 accentHi + onAccent 勾，scale .7→1（liquid 弹簧）。
 * - [onClick] 非 null 时可点：未进入选择模式 = LONG_PRESS 触感（进入多选），选择模式 = SEGMENT_TICK（切换勾选）。
 * - 装饰节点：不进入无障碍树，行节点负责朗读（`TaskRow` 以 customActions 暴露“选择”）。
 */
@Composable
fun FileTile(
    icon: ImageVector,
    categoryColor: Color?,
    modifier: Modifier = Modifier,
    size: FileTileSize = FileTileSize.Md,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = FluxTheme.colors
    val shapes = FluxTheme.shapes
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val shape = when (size) {
        FileTileSize.Sm -> shapes.tileSm
        FileTileSize.Md -> shapes.tile
        FileTileSize.Lg -> shapes.tileLg
    }
    val sel = animateFloatAsState(
        if (selectionMode && selected) 1f else 0f,
        motion.of(motion.liquid),
        label = "tileSel",
    )
    val overlayVisible by remember { derivedStateOf { sel.value > 0.01f } }

    Box(
        modifier
            .size(size.dp)
            .then(
                if (onClick != null) {
                    Modifier.fluxPressable(
                        onClick = {
                            if (selectionMode) haptics.tick() else haptics.longPress()
                            onClick()
                        },
                        scale = 0.9f,
                        role = null,
                    )
                } else Modifier,
            )
            .fluxGlass(
                level = if (selectionMode) FluxGlass.G3 else FluxGlass.G2,
                shape = shape,
                kind = FluxGlassKind.Flat,
            )
            .categoryDot(categoryColor, colors.canvas)
            .semantics { hideFromAccessibility() },
        contentAlignment = Alignment.Center,
    ) {
        FluxIcon(icon, contentDescription = null, size = size.iconDp, tint = colors.ink)
        if (selectionMode && overlayVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val v = sel.value
                        val k = 0.7f + 0.3f * v
                        scaleX = k
                        scaleY = k
                        alpha = v.coerceIn(0f, 1f)
                    }
                    .background(colors.accentHi, shape),
                contentAlignment = Alignment.Center,
            ) {
                FluxIcon(FluxIcons.Check, contentDescription = null, size = 18.dp, tint = colors.onAccent)
            }
        }
    }
}

/** 6dp 分类点：中心 = (右 −1dp, 上 +1dp)，即盒子落在 (−2,−2) 外挂位置。 */
private fun Modifier.categoryDot(color: Color?, canvas: Color): Modifier {
    if (color == null) return this
    return drawWithCache {
        val r = 3.dp.toPx()
        val ring = r + 2.dp.toPx()
        val glowR = r + 8.dp.toPx()
        val glow = Brush.radialGradient(
            0f to color.copy(alpha = 0.6f),
            r / glowR to color.copy(alpha = 0.5f),
            1f to Color.Transparent,
            center = Offset.Zero,
            radius = glowR,
        )
        val c = Offset(size.width - 1.dp.toPx(), 1.dp.toPx())
        onDrawWithContent {
            drawContent()
            translate(c.x, c.y) { drawCircle(glow, glowR, Offset.Zero) }
            drawCircle(canvas, ring, c)
            drawCircle(color, r, c)
        }
    }
}
