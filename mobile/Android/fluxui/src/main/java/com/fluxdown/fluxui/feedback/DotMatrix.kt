package com.fluxdown.fluxui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** 9×9 点阵字形（原型 `GLYPH`）。`'#'` 亮 · `'o'` 强调色 · `'+'` 半亮 · `'.'` 灭。每个字形至少一枚强调点作视觉焦点。 */
enum class FluxGlyph(val rows: List<String>) {
    File(listOf("..#####..", "..#...##.", "..#...#.#", "..#...###", "..#.....#", "..#.o.o.#", "..#.....#", "..#.....#", "..#######")),
    Inbox(listOf(".........", ".#######.", ".#.....#.", ".#.....#.", ".#.....#.", ".##...##.", ".#.#o#.#.", ".#.....#.", ".#######.")),
    Check(listOf(".........", ".......o.", "......oo.", ".....oo..", ".o..oo...", ".oooo....", "..oo.....", ".........", ".........")),
    Search(listOf("..#####..", ".#.....#.", "#.o.....#", "#.......#", "#.......#", ".#.....#.", "..#####..", "......##.", ".......##")),
    Wifi(listOf(".........", "..#####..", ".#.....#.", "#..###..#", "..#...#..", "....#....", "....o....", ".........", ".........")),
    Rss(listOf("#####....", "....##...", "......#..", "..##..#..", "...#...#.", "#..#...#.", "##.#...#.", ".........", "o........")),
    Device(listOf("..#####..", "..#...#..", "..#...#..", "..#...#..", "..#...#..", "..#...#..", "..#...#..", "..#####..", "....o....")),
    Download(listOf("....#....", "....#....", "....#....", "..#.#.#..", "...###...", "....o....", ".#######.", ".#.....#.", ".#######.")),
    Plug(listOf(".#.....#.", ".#.....#.", ".#######.", ".#.....#.", "..#...#..", "...###...", "....#....", "....#....", "....o...."));
}

/** 字形集合与数字点阵（3×5，`.` `:` 带强调点）。 */
object DotGlyphs {
    val File: List<String> = FluxGlyph.File.rows
    val Inbox: List<String> = FluxGlyph.Inbox.rows
    val Check: List<String> = FluxGlyph.Check.rows
    val Search: List<String> = FluxGlyph.Search.rows
    val Wifi: List<String> = FluxGlyph.Wifi.rows
    val Rss: List<String> = FluxGlyph.Rss.rows
    val Device: List<String> = FluxGlyph.Device.rows
    val Download: List<String> = FluxGlyph.Download.rows
    val Plug: List<String> = FluxGlyph.Plug.rows

    private val DIGITS: Map<Char, List<String>> = mapOf(
        '0' to listOf("###", "#.#", "#.#", "#.#", "###"),
        '1' to listOf(".#.", "##.", ".#.", ".#.", "###"),
        '2' to listOf("###", "..#", "###", "#..", "###"),
        '3' to listOf("###", "..#", "###", "..#", "###"),
        '4' to listOf("#.#", "#.#", "###", "..#", "..#"),
        '5' to listOf("###", "#..", "###", "..#", "###"),
        '6' to listOf("###", "#..", "###", "#.#", "###"),
        '7' to listOf("###", "..#", ".#.", ".#.", ".#."),
        '8' to listOf("###", "#.#", "###", "#.#", "###"),
        '9' to listOf("###", "#.#", "###", "..#", "###"),
        '.' to listOf(".", ".", ".", ".", "o"),
        ':' to listOf(".", "o", ".", "o", "."),
        '-' to listOf("...", "...", "###", "...", "..."),
        ' ' to listOf(".", ".", ".", ".", "."),
    )

    /** 文本（数字 / `.` `:` `-` / 空格；其余视作空格）→ 5 行点阵，字间 1 列空。 */
    fun text(s: String): List<String> {
        val rows = Array(5) { StringBuilder() }
        s.forEachIndexed { i, ch ->
            val g = DIGITS[ch] ?: DIGITS.getValue(' ')
            for (r in 0 until 5) {
                if (i > 0) rows[r].append('.')
                rows[r].append(g[r])
            }
        }
        return rows.map { it.toString() }
    }
}

/** 灭点 α 与边缘渐隐：中心 .09 → 四角 .09 × .45，让方阵收成柔和的圆形底纹。 */
private const val OFF_ALPHA = 0.09f
private const val OFF_VIGNETTE = 0.55f

/** 扫光方向：对角坐标 u = .62·x + .38·y（偏横向的斜扫）。 */
private const val SWEEP_X_WEIGHT = 0.62f

private fun baseAlpha(kind: Int, vignette: Float): Float = when (kind) {
    K_ON -> 0.92f
    K_MID -> 0.40f
    K_HI -> 1f
    else -> OFF_ALPHA * vignette
}

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * 点阵图形（§12.32，原型 `ui.dotMatrix`）。圆点 [dot] / 间距 [gap]；颜色 `ink`：灭 α .09（向四角渐隐到 ×.45）·
 * 亮 .92 · 半亮 .40 · 强调点 `accentHi` + 8dp `accentGlow` 辉光。
 *
 * [animate] 且未开 Reduce motion 时：
 * - 入场：灭点自中心向外回弹铺开，亮点晚一拍按同样次序“落笔”（1 s）。
 * - 待机：每 6.4 s 一道 1.8 s 的斜向扫光，经过的亮点放大、染强调色，灭点泛起淡淡一线；其余时间完全静止。
 * - 换字形（同尺寸）：变化的点自左向右错相收缩换形；尺寸变化则重放入场。
 * - 触摸：按住出透镜（附近点放大外推并染色），拖过亮点给触感刻度；轻点放一圈涟漪。不消费事件，不抢父级滚动。
 *
 * 单 Canvas 逐点绘制，时钟只在绘制阶段读取（不触发重组）；全部阶段静止时帧循环休眠到下一次扫光。
 * [label] 非空时 `Role.Image` + contentDescription；为空则视为装饰，清除语义。
 */
@Composable
fun DotMatrix(
    rows: List<String>,
    modifier: Modifier = Modifier,
    dot: Dp = 6.dp,
    gap: Dp = 3.dp,
    animate: Boolean = true,
    label: String? = null,
) {
    val cols = columns(rows)
    val nRows = rows.size
    if (cols == 0 || nRows == 0) return
    val c = FluxTheme.colors
    val ink = c.ink
    val hiColor = c.accentHi
    val glow = c.accentGlow
    val haptics = FluxTheme.haptics
    val live = animate && !FluxTheme.motion.reduce

    val state = remember { DotMatrixState(rows) }
    SideEffect { state.retarget(rows, live) }
    if (live) {
        LaunchedEffect(state) { state.runClock() }
    }

    val sem = if (label != null) {
        Modifier.semantics {
            role = Role.Image
            contentDescription = label
        }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    val touch = if (live) {
        Modifier.pointerInput(state, haptics, dot, gap) { trackDotMatrixTouch(state, haptics, (dot + gap).toPx()) }
    } else {
        Modifier
    }

    Spacer(
        modifier
            .size(dot * cols + gap * (cols - 1), dot * nRows + gap * (nRows - 1))
            .then(sem)
            .then(touch)
            .drawWithCache {
                val tgt = state.target
                val prev = state.previous
                val gCols = columns(tgt)
                val gRows = tgt.size
                val d = dot.toPx()
                val step = d + gap.toPx()
                val r = d / 2f
                val n = gRows * gCols
                val kindTo = IntArray(n)
                val kindFrom = IntArray(n)
                val cx = FloatArray(n)
                val cy = FloatArray(n)
                val vignette = FloatArray(n)
                val diag = FloatArray(n)
                val introDelay = FloatArray(n)
                val morphDelay = FloatArray(n)
                val midX = (gCols - 1) * step / 2f + r
                val midY = (gRows - 1) * step / 2f + r
                val maxDist = max(sqrt((midX - r) * (midX - r) + (midY - r) * (midY - r)), 1f)
                val spanX = max(gCols - 1, 1).toFloat()
                val spanY = max(gRows - 1, 1).toFloat()
                for (row in 0 until gRows) {
                    val line = tgt[row]
                    val prevLine = prev?.getOrNull(row)
                    for (col in 0 until gCols) {
                        val i = row * gCols + col
                        val k = dotKind(line.getOrElse(col) { '.' })
                        kindTo[i] = k
                        kindFrom[i] = if (prevLine != null) dotKind(prevLine.getOrElse(col) { '.' }) else k
                        cx[i] = col * step + r
                        cy[i] = row * step + r
                        val dx = cx[i] - midX
                        val dy = cy[i] - midY
                        val dn = (sqrt(dx * dx + dy * dy) / maxDist).coerceAtMost(1f)
                        vignette[i] = 1f - OFF_VIGNETTE * dn * dn
                        val fx = col / spanX
                        val fy = row / spanY
                        diag[i] = SWEEP_X_WEIGHT * fx + (1f - SWEEP_X_WEIGHT) * fy
                        introDelay[i] = (if (dotLit(k) > 0f) DotTimeline.INTRO_LIT_DELAY_MS else 0f) + dn * DotTimeline.INTRO_SPREAD_MS
                        morphDelay[i] = fx * DotTimeline.MORPH_COL_SPREAD_MS + fy * DotTimeline.MORPH_ROW_SPREAD_MS
                    }
                }
                val glowR = r + 8.dp.toPx()
                val glowBrush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to glow,
                        (r / glowR) to glow.copy(alpha = glow.alpha * 0.55f),
                        1f to glow.copy(alpha = 0f),
                    ),
                    center = Offset.Zero,
                    radius = glowR,
                )
                val lensR2 = (DotTimeline.LENS_STEPS * step).let { it * it }
                val rippleReach = maxDist * 2.1f
                val ringW = step * 1.3f
                val ripple = FloatArray(DotTimeline.MAX_RIPPLES)

                onDrawBehind {
                    val now = if (live) state.clock.longValue else 0L
                    val introEl = if (live) elapsed(state.introStart, now).toFloat() else Float.MAX_VALUE
                    val morphEl = if (live && prev != null) elapsed(state.morphStart, now).toFloat() else Float.MAX_VALUE
                    val sweep = if (live) sweepProgress(elapsed(state.introStart, now)) else Float.NaN
                    val lens = if (live) state.lens(now) else 0f
                    val tx = state.touchX
                    val ty = state.touchY
                    var anyRipple = false
                    for (j in ripple.indices) {
                        ripple[j] = if (live) state.rippleProgress(j, now) else -1f
                        if (ripple[j] >= 0f) anyRipple = true
                    }

                    for (i in 0 until n) {
                        val kf = kindFrom[i]
                        val kt = kindTo[i]
                        val v = vignette[i]
                        var scale = 1f
                        var alpha: Float
                        var tint: Float
                        val lit: Float
                        if (kf == kt) {
                            alpha = baseAlpha(kt, v)
                            tint = if (kt == K_HI) 1f else 0f
                            lit = dotLit(kt)
                        } else {
                            val q = ((morphEl - morphDelay[i]) / DotTimeline.MORPH_DOT_MS).coerceIn(0f, 1f)
                            val e = easeInOutCubic(q)
                            val bump = sin(PI.toFloat() * q)
                            alpha = lerpF(baseAlpha(kf, v), baseAlpha(kt, v), e)
                            tint = lerpF(if (kf == K_HI) 1f else 0f, if (kt == K_HI) 1f else 0f, e)
                            lit = lerpF(dotLit(kf), dotLit(kt), e)
                            scale = 1f - 0.6f * bump
                            tint += 0.6f * bump
                        }
                        val hiness = tint.coerceAtMost(1f)

                        val iq = ((introEl - introDelay[i]) / DotTimeline.INTRO_DOT_MS).coerceIn(0f, 1f)
                        val introA = if (iq < 1f) easeOutCubic(iq) else 1f
                        if (iq < 1f) {
                            scale *= backOut(iq)
                            alpha *= introA
                        }

                        val b = sweepBand(diag[i], sweep)
                        if (b > 0f) {
                            alpha += b * (0.10f * (1f - lit) + (1f - alpha) * lit)
                            tint += b * (0.35f + 0.5f * lit)
                            scale *= 1f + 0.16f * b * lit
                        }

                        var ox = 0f
                        var oy = 0f
                        if (lens > 0f) {
                            val dx = cx[i] - tx
                            val dy = cy[i] - ty
                            val d2 = (dx * dx + dy * dy) / lensR2
                            if (d2 < 1f) {
                                val f0 = 1f - d2
                                val f = f0 * f0 * lens
                                scale *= 1f + 0.5f * f
                                alpha += f * (0.28f * (1f - lit) + (1f - alpha) * lit)
                                tint += 0.55f * f
                                val dist = sqrt(dx * dx + dy * dy)
                                if (dist > 0.5f) {
                                    val push = 0.32f * step * f * sqrt(d2) / dist
                                    ox = dx * push
                                    oy = dy * push
                                }
                            }
                        }

                        if (anyRipple) {
                            for (j in ripple.indices) {
                                val p = ripple[j]
                                if (p < 0f) continue
                                val dx = cx[i] - state.rippleX[j]
                                val dy = cy[i] - state.rippleY[j]
                                val x = 1f - abs(sqrt(dx * dx + dy * dy) - p * rippleReach) / ringW
                                if (x <= 0f) continue
                                val g = x * x * (3f - 2f * x) * (1f - p)
                                scale *= 1f + 0.45f * g
                                alpha += g * (0.35f * (1f - lit) + (1f - alpha) * lit)
                                tint += 0.8f * g
                            }
                        }

                        val px = cx[i] + ox
                        val py = cy[i] + oy
                        if (hiness > 0.01f) {
                            val glowA = hiness * (0.7f + 0.3f * b) * introA
                            translate(px, py) { drawCircle(glowBrush, glowR, Offset.Zero, alpha = glowA) }
                        }
                        val color: Color = lerp(ink, hiColor, tint.coerceIn(0f, 1f))
                        drawCircle(color.copy(alpha = alpha.coerceIn(0f, 1f)), r * scale, Offset(px, py))
                    }
                }
            },
    )
}

/** 预置字形版本：`DotMatrix(FluxGlyph.Inbox)`。 */
@Composable
fun DotMatrix(
    glyph: FluxGlyph,
    modifier: Modifier = Modifier,
    dot: Dp = 6.dp,
    gap: Dp = 3.dp,
    animate: Boolean = true,
    label: String? = null,
) = DotMatrix(glyph.rows, modifier, dot, gap, animate, label)

/**
 * 空态（§12.32，原型 `ui.empty`）：**一律用点阵图形**。居中列：点阵（d 7 / gap 4）→ 标题 `h2`（间距 24）→
 * 说明 `sm inkMuted`（max 270、行高 1.5）→ [action]（间距 24）；padding 40 / 28。
 * 整体合并语义，点阵为装饰。
 */
@Composable
fun FluxEmpty(
    glyph: List<String>,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    Column(
        modifier.semantics(mergeDescendants = true) { }.padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        DotMatrix(glyph, dot = 7.dp, gap = 4.dp)
        Spacer(Modifier.height(24.dp))
        FluxText(title, style = t.h2.copy(textAlign = TextAlign.Center))
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            FluxText(
                subtitle,
                modifier = Modifier.widthIn(max = 270.dp),
                style = t.sm.copy(textAlign = TextAlign.Center, lineHeight = 1.5.em),
                color = c.inkMuted,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/** 预置字形版本：`FluxEmpty(FluxGlyph.Search, "没有结果")`。 */
@Composable
fun FluxEmpty(
    glyph: FluxGlyph,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) = FluxEmpty(glyph.rows, title, modifier, subtitle, action)
