package com.fluxdown.fluxui.material

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import com.fluxdown.fluxui.theme.FluxTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.fluxdown.fluxui.theme.FluxColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * 系统启动画面 → [FluxLaunchReveal] 的交接点（每次冷 / 温启动一个实例）。
 *
 * 系统启动画面在首帧绘制后才回调宿主；宿主读出其中 logo 画框的窗口坐标与画面底色交给 [handOff]，
 * 揭幕先按该位置与颜色画一帧（被系统启动画面盖住），下一帧再调 `release` 移除系统画面，两者像素级接续。
 */
@Stable
class LaunchRevealState {
    internal var mark by mutableStateOf<Rect?>(null)
    internal var splashBackground by mutableStateOf<Color?>(null)
    internal var handedOff by mutableStateOf(false)
    private var release: (() -> Unit)? = null
    private var started = false

    /**
     * [markInWindow]：系统启动画面里 logo 画框（`fluxdown_logo.svg` 的 400 单位方框）的窗口坐标，
     * 拿不到为 null（退回居中默认尺寸）；[background]：系统启动画面底色（拿不到为 null，直接用主题底色）；
     * [release]：移除系统启动画面。揭幕已开始（等待超时）时立即移除。
     */
    fun handOff(markInWindow: Rect?, background: Color?, release: () -> Unit) {
        if (started) {
            release()
            return
        }
        this.release = release
        mark = markInWindow
        splashBackground = background
        handedOff = true
    }

    internal fun start() {
        started = true
        release?.invoke()
        release = null
    }
}

/**
 * 启动揭幕「箭头开窗」：主题底色 + 强调色下载箭头 → 箭头吸气般微缩并下沉（「按下下载」）→
 * 箭头本身成为窗口，以箭头头部为锚点指数放大直到盖满屏幕，窗口内的强调色逐渐褪去露出界面，界面同时从 1.08 收回原尺寸。
 * 思路取自 Twitter 启动遮罩揭幕（开源复刻：PiXeL16/RevealingSplashView）：只用一个品牌形状作遮罩，不叠加其它元素。
 *
 * **跟随主题**：底色 = [FluxTheme] 的 canvas（App 明暗 / 导入主题），箭头 = 主题强调色（`accentFill`）。
 * 系统启动画面是静态资源（系统明暗下的 canvas + 品牌蓝箭头），二者不同时在吸气段把底色与箭头色平滑过渡到主题值；
 * 默认外观（跟随系统 + 品牌蓝）下无过渡。
 *
 * **不拖慢启动**：[content] 与幕布同一首帧组合、绘制，揭幕只决定何时看见；
 * 系统启动画面交接后立即开始（无交接时至多等 [HANDOFF_WAIT_MS]），全程 [TOTAL_MS]；幕布不接收触摸。
 * 结束后幕布与内容的 graphicsLayer 一并移除，不留常驻开销；逐帧只在绘制阶段读进度，不触发重组，路径与矩阵复用。
 *
 * [state] 为 null（恢复实例、系统关闭动画）时直接呈现 [content]。
 */
@Composable
fun FluxLaunchReveal(
    state: LaunchRevealState?,
    fallbackMarkSize: Dp,
    content: @Composable () -> Unit,
) {
    var active by remember { mutableStateOf(state != null) }
    val progress = remember { Animatable(0f) }
    if (active && state != null) {
        LaunchedEffect(state) {
            withTimeoutOrNull(HANDOFF_WAIT_MS) { snapshotFlow { state.handedOff }.first { it } }
            // 第一帧按交接位置绘制（仍在系统画面之下），下一帧开始时上一帧已提交，此时移除系统画面不会露缝。
            withFrameNanos { }
            withFrameNanos { }
            state.start()
            progress.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
            active = false
        }
    }
    val revealing = active && state != null
    Box(Modifier.fillMaxSize()) {
        // content 恒在同一槽位：揭幕结束只摘掉 graphicsLayer，不重建界面。
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (revealing) {
                        Modifier.graphicsLayer {
                            val s = CONTENT_SCALE_FROM + (1f - CONTENT_SCALE_FROM) * easeOutCubic(phase(progress.value))
                            scaleX = s
                            scaleY = s
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }
        if (revealing) {
            val fallbackPx = with(LocalDensity.current) { fallbackMarkSize.toPx() }
            val paths = remember { PortalPaths() }
            var origin by remember { mutableStateOf(Offset.Zero) }
            val canvas = FluxTheme.colors.canvas
            val accent = FluxTheme.colors.accentFill
            Canvas(Modifier.fillMaxSize().onPlaced { origin = it.positionInWindow() }) {
                val mark = state.mark?.translate(-origin) ?: Rect(center, fallbackPx / 2f)
                // 有系统画面交接：从它的底色与品牌蓝箭头出发，吸气段过渡到主题色；否则首帧即主题色。
                val handed = state.handedOff
                drawPortal(
                    t = progress.value,
                    mark = mark,
                    fromCurtain = state.splashBackground ?: canvas,
                    curtain = canvas,
                    fromInk = if (handed) FluxColors.Brand.flux else accent,
                    ink = accent,
                    paths = paths,
                )
            }
        }
    }
}

/** 系统启动画面无交接时的最长等待：超时即按默认位置开始，避免为动画而等。 */
private const val HANDOFF_WAIT_MS = 160L
private const val TOTAL_MS = 650

/** 前 200ms 吸气下沉，其余开窗。 */
private const val INHALE = 200f / TOTAL_MS
private const val INHALE_SCALE = 0.88f
private const val DIP = 0.04f
private const val CONTENT_SCALE_FROM = 1.08f

/** 品牌箭头，坐标同 `assets/logo/fluxdown_logo.svg`（画框 56..456，中心 256）。 */
private const val ARROW_PATH =
    "M226 131Q226 119 238 119L274 119Q286 119 286 131L286 296L331 251Q340 242 349 251L363 265" +
        "Q372 274 363 283L265 381Q256 390 247 381L149 283Q140 274 149 265L163 251Q172 242 181 251L226 296Z"
private const val LOGO_CENTER = 256f
private const val LOGO_SIDE = 400f

/** 放大锚点 = 箭头头部三角形内心（logo 单位）；[ARROW_INRADIUS] 为其内切圆半径，用于算盖满屏幕所需倍率。 */
private const val ANCHOR_X = 256f
private const val ANCHOR_Y = 318f
private const val ARROW_INRADIUS = 44f

/** 窗口内强调色在开窗前 60% 褪尽：底色与界面同为 canvas，箭头靠这段强调色被看见。 */
private const val INK_FADE = 0.6f

private class PortalPaths {
    val arrow: Path = PathParser().parsePathString(ARROW_PATH).toPath()
    val frame = Path()
    val curtain = Path().apply { fillType = PathFillType.EvenOdd }
    val matrix = Matrix()
}

private fun phase(t: Float): Float = ((t - INHALE) / (1f - INHALE)).coerceIn(0f, 1f)

private fun easeOutCubic(x: Float): Float {
    val r = 1f - x
    return 1f - r * r * r
}

private fun easeInOutCubic(x: Float): Float =
    if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f

private fun DrawScope.drawPortal(
    t: Float,
    mark: Rect,
    fromCurtain: Color,
    curtain: Color,
    fromInk: Color,
    ink: Color,
    paths: PortalPaths,
) {
    val unit = mark.width / LOGO_SIDE
    val inhale = easeInOutCubic((t / INHALE).coerceIn(0f, 1f))
    val u = phase(t)

    // 锚点静止时的屏幕位置 + 吸气下沉。
    val ax = mark.center.x + (ANCHOR_X - LOGO_CENTER) * unit
    val ay = mark.center.y + (ANCHOR_Y - LOGO_CENTER) * unit + DIP * mark.width * inhale
    // 内切圆盖住最远屏幕角所需倍率；对数空间插值 = 视觉上匀速放大，ease-in 使起步与吸气末速度衔接。
    val reach = max(hypot(ax, ay), max(hypot(size.width - ax, ay), max(hypot(ax, size.height - ay), hypot(size.width - ax, size.height - ay))))
    val endScale = reach / (ARROW_INRADIUS * unit) * 1.08f
    val startScale = 1f - (1f - INHALE_SCALE) * inhale
    val k = exp(ln(startScale) + (ln(endScale) - ln(startScale)) * u * u) * unit

    paths.matrix.reset()
    paths.matrix.values[Matrix.ScaleX] = k
    paths.matrix.values[Matrix.ScaleY] = k
    paths.matrix.values[Matrix.TranslateX] = ax - ANCHOR_X * k
    paths.matrix.values[Matrix.TranslateY] = ay - ANCHOR_Y * k
    paths.frame.reset()
    paths.frame.addPath(paths.arrow)
    paths.frame.transform(paths.matrix)

    paths.curtain.reset()
    paths.curtain.addRect(Rect(Offset.Zero, size))
    paths.curtain.addPath(paths.frame)
    drawPath(paths.curtain, lerp(fromCurtain, curtain, inhale))

    // 窗口内的强调色随开窗褪去，露出界面。
    val inkAlpha = 1f - (u / INK_FADE).coerceIn(0f, 1f)
    if (inkAlpha > 0f) drawPath(paths.frame, lerp(fromInk, ink, inhale), alpha = inkAlpha)
}
