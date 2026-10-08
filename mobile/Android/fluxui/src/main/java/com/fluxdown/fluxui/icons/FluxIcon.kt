package com.fluxdown.fluxui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.theme.FluxTheme

/** 线宽 1.5（24 栅格）；filled = 母版自带实心（如暂停 / 播放字形）。颜色由 [FluxIcon] 的 tint 决定。 */
internal fun fluxIcon(name: String, filled: Boolean, vararg paths: String): ImageVector {
    val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    for (d in paths) {
        // 每条路径独立解析：PathParser.toNodes() 返回解析器内部列表的引用，复用解析器会让所有路径共享同一份节点
        b.addPath(
            pathData = addPathNodes(d),
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

/**
 * 单色线性图标。[contentDescription] 为 null 时视为装饰（不进入无障碍树）。
 * 默认色 = 当前内容色（[FluxTheme.contentColor]）。
 */
@Composable
fun FluxIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = Color.Unspecified,
) {
    val color = tint.takeOrElse { FluxTheme.contentColor }
    val sem = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        modifier = modifier.then(sem).size(size),
        colorFilter = ColorFilter.tint(color),
    )
}
