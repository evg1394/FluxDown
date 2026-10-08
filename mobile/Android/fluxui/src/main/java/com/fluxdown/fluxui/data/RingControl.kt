package com.fluxdown.fluxui.data

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.theme.FluxColors
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxPressable

/**
 * 环钮动作种类（§12.10）。决定弧色 / 字形 / 辉光；动作名由调用方经 `contentDescription` 给出。
 *
 * | kind | 弧 | 字形 |
 * |---|---|---|
 * | Pause | accentHi + 辉光（进度弧） | 实心暂停 |
 * | Queued | inkFaint | 实心暂停 |
 * | Spinning | accentHi 弧持续旋转 1.1s | 实心暂停 |
 * | Play | inkMuted | 实心播放 |
 * | Retry | coral | RotateCw |
 * | Redownload | 满环 inkFaint | RotateCw |
 * | Seeding | mint | Check（mintText） |
 * | Open | 轨道 inkFaint，无进度弧 | ExternalLink（inkMuted） |
 * | More | 轨道 inkFaint，无进度弧 | Ellipsis（inkMuted）：没有主操作时打开动作菜单 |
 */
enum class RingKind { Pause, Play, Retry, Redownload, Seeding, Open, Spinning, Queued, More }

/** 环钮尺寸：sm 32 / md 36 / lg 52（viewBox 36，半径 16，描边 2 按比例缩放）。 */
enum class RingSize(val dp: Dp) { Sm(32.dp), Md(36.dp), Lg(52.dp) }

@Immutable
private class RingLook(
    val arc: Color,
    val glow: Boolean,
    val track: Color,
    val glyph: Color,
    val showArc: Boolean,
)

private fun ringLook(kind: RingKind, c: FluxColors): RingLook = when (kind) {
    RingKind.Pause -> RingLook(c.accentHi, true, c.hairlineStrong, c.accentHi, true)
    RingKind.Spinning -> RingLook(c.accentHi, true, c.hairlineStrong, c.ink, true)
    RingKind.Queued -> RingLook(c.inkFaint, false, c.hairlineStrong, c.ink, true)
    RingKind.Play -> RingLook(c.inkMuted, false, c.hairlineStrong, c.ink, true)
    RingKind.Retry -> RingLook(c.coral, false, c.hairlineStrong, c.ink, true)
    RingKind.Redownload -> RingLook(c.inkFaint, false, c.inkFaint, c.inkMuted, false)
    RingKind.Seeding -> RingLook(c.mint, false, c.hairlineStrong, c.mintText, true)
    RingKind.Open, RingKind.More -> RingLook(c.inkFaint, false, c.inkFaint, c.inkMuted, false)
}

private fun ringIcon(kind: RingKind): ImageVector? = when (kind) {
    RingKind.Retry, RingKind.Redownload -> FluxIcons.RotateCw
    RingKind.Seeding -> FluxIcons.Check
    RingKind.Open -> FluxIcons.ExternalLink
    RingKind.More -> FluxIcons.Ellipsis
    else -> null
}

/**
 * 行尾细环进度控制钮：环 = 进度，中心字形 = 当前可执行的动作。
 *
 * - [progress] 0..1（null = 无弧；[RingKind.Seeding] 为 null 时画满环；[RingKind.Spinning] 为 null 时画 25% 旋转弧）。
 * - 弧长以 0.9s 线性补间贴合 1 Hz 数据（Reduce motion 时瞬时）；按压缩放 .88；点击触发 SEGMENT_TICK。
 * - [contentDescription] 是**动作名**（暂停 / 继续 / 重试 …），不读百分比。
 * - 视觉尺寸 = [size]；Compose 命中测试会自动把小于 48dp 的可点节点扩到 48dp，布局不被撑大。
 */
@Composable
fun RingControl(
    progress: Float?,
    kind: RingKind,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: RingSize = RingSize.Md,
    enabled: Boolean = true,
) {
    val colors = FluxTheme.colors
    val motion = FluxTheme.motion
    val haptics = FluxTheme.haptics
    val look = remember(kind, colors) { ringLook(kind, colors) }
    val target = when (kind) {
        RingKind.Seeding -> progress ?: 1f
        RingKind.Spinning -> (progress ?: 0f).coerceAtLeast(0.25f)
        else -> progress ?: 0f
    }.coerceIn(0f, 1f)
    val arc = animateFloatAsState(
        target,
        if (motion.reduce) snap() else tween(900, easing = LinearEasing),
        label = "ringArc",
    )
    val decor = rememberDecorativeMotion()
    val spin: State<Float>? = if (kind == RingKind.Spinning && decor) {
        rememberInfiniteTransition(label = "ringSpin")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    } else null
    val icon = ringIcon(kind)
    val painter = if (icon != null) rememberVectorPainter(icon) else null
    val glyphDp = if (size == RingSize.Lg) 20.dp else 15.dp

    Box(
        modifier
            .size(size.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.4f))
            .fluxPressable(
                onClick = {
                    haptics.tick()
                    onClick()
                },
                scale = 0.88f,
                enabled = enabled,
                role = Role.Button,
            )
            .semantics { this.contentDescription = contentDescription }
            .drawWithCache {
                val s = this.size.width / 36f
                val strokeW = 2f * s
                val rad = 16f * s
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                val trackStroke = Stroke(strokeW)
                val arcStroke = Stroke(strokeW, cap = StrokeCap.Round)
                val glowA = Stroke(strokeW + 4f * s, cap = StrokeCap.Round)
                val glowB = Stroke(strokeW + 2f * s, cap = StrokeCap.Round)
                val glowColorA = colors.accentGlow.copy(alpha = 0.2f)
                val glowColorB = colors.accentGlow.copy(alpha = 0.32f)
                val topLeft = Offset(cx - rad, cy - rad)
                val arcSize = Size(2f * rad, 2f * rad)
                val g = glyphDp.toPx()
                val tint = ColorFilter.tint(look.glyph)
                val center = Offset(cx, cy)
                onDrawBehind {
                    drawCircle(look.track, rad, center, style = trackStroke)
                    val p = arc.value
                    if (look.showArc && p > 0.002f) {
                        val sweep = 360f * p
                        withTransform({ rotate(spin?.value ?: 0f, center) }) {
                            if (look.glow) {
                                drawArc(glowColorA, -90f, sweep, false, topLeft, arcSize, style = glowA)
                                drawArc(glowColorB, -90f, sweep, false, topLeft, arcSize, style = glowB)
                            }
                            drawArc(look.arc, -90f, sweep, false, topLeft, arcSize, style = arcStroke)
                        }
                    }
                    if (painter != null) {
                        withTransform({ translate(cx - g / 2f, cy - g / 2f) }) {
                            with(painter) { draw(Size(g, g), colorFilter = tint) }
                        }
                    } else {
                        drawFilledGlyph(kind == RingKind.Play, look.glyph, g, cx, cy)
                    }
                }
            },
    )
}

/** 实心暂停 / 播放字形（Lucide 24 栅格轮廓 + 1.5 圆角描边，与原型 `fill=currentColor` 一致）。 */
internal object FluxGlyphs {
    /** 两根竖条一次解析（`toPath(target)` 会先 rewind 目标，分两次解析会抹掉第一根）。 */
    val pause: Path by lazy {
        PathParser()
            .parsePathString(
                "M15 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1Z" +
                    "M6 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1Z",
            )
            .toPath()
    }
    val play: Path by lazy {
        PathParser()
            .parsePathString("M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z")
            .toPath()
    }
    val outline = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
}

/** 以 ([cx], [cy]) 为中心画 [sizePx] 见方的实心 暂停 / 播放 字形。 */
internal fun DrawScope.drawFilledGlyph(play: Boolean, color: Color, sizePx: Float, cx: Float, cy: Float) {
    val k = sizePx / 24f
    val path = if (play) FluxGlyphs.play else FluxGlyphs.pause
    withTransform({
        translate(cx - sizePx / 2f, cy - sizePx / 2f)
        scale(k, k, Offset.Zero)
    }) {
        drawPath(path, color, style = Fill)
        drawPath(path, color, style = FluxGlyphs.outline)
    }
}
