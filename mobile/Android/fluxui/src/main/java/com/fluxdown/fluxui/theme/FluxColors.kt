package com.fluxdown.fluxui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** 文件分类（分类色点只做辅助提示；权威信号是图块内的类别图标）。 */
enum class FileCategory { Video, Audio, Document, Image, Program, Archive, Ebook, Other }

/**
 * Flux Lumen 色板：12 阶中性灰 + 单一发光强调色（及其派生）+ 三个降饱和状态色 + 8 个分类色点。
 * 其余颜色不存在。业务视图只能经 [FluxTheme.colors] 读取（docs 01-foundations §2 / §11.1）。
 */
@Immutable
class FluxColors(
    val dark: Boolean,
    /** n1..n12（ramp[0] = canvas）。保留阶用于降级实色、图表网格。 */
    val ramp: List<Color>,
    val canvas: Color,
    val ink: Color,
    val inkMuted: Color,
    /** 仅用于占位符、尾随次要元信息、chev 与装饰；不承载唯一信息（对比度 ≈4.1）。 */
    val inkFaint: Color,
    val glass1: Color,
    val glass2: Color,
    val glass3: Color,
    val glass4: Color,
    val glassSolid1: Color,
    val glassSolid2: Color,
    val glassSolid3: Color,
    val glassSolid4: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val highlight: Color,
    val sheetBg: Color,
    val menuBg: Color,
    val dim: Color,
    val scrimTopFrom: Color,
    val accent: Color,
    val accentHi: Color,
    val accentLo: Color,
    val accentMid: Color,
    val accentGlow: Color,
    val accentFill: Color,
    val onAccent: Color,
    val coral: Color,
    val coralText: Color,
    val mint: Color,
    val mintText: Color,
    val amber: Color,
    val amberText: Color,
    val onCoral: Color,
    val violet: Color,
    val flowDone: Color,
    val flowRemain: Color,
    val flowFail: Color,
    private val categoryColors: Array<Color>,
    val grainAlpha: Float,
    val grainBlend: BlendMode,
) {
    fun category(c: FileCategory): Color = categoryColors[c.ordinal]

    /** `canvas X% + glassN` 的复合底（Oklab 预乘插值，等价 CSS color-mix）。 */
    fun mixCanvas(glass: Color, canvasFraction: Float): Color = lerp(glass, canvas, canvasFraction)

    companion object {
        private val DarkRamp = longArrayOf(
            0xFF07080A, 0xFF0B0D10, 0xFF101216, 0xFF15181D, 0xFF1B1E25, 0xFF232730,
            0xFF2F3440, 0xFF424857, 0xFF6B7183, 0xFF9298A8, 0xFFC3C7D2, 0xFFF1F2F5,
        ).map(::Color)
        /**
         * 纸面：带一丝冷调（OKLab 色相与品牌蓝同向、彩度 ≤ .02）的中性灰。暖黄纸面与蓝色强调色互相打架，
         * 整屏发旧发灰；冷中性让白色玻璃面更干净、强调色更通透。各阶对 n1 的对比度与旧纸面逐阶持平（±0.1）。
         */
        private val PaperRamp = longArrayOf(
            0xFFF4F5F7, 0xFFEDEEF1, 0xFFE5E7EB, 0xFFDCDFE4, 0xFFD0D4DA, 0xFFC1C5CD,
            0xFFA6ABB5, 0xFF878D99, 0xFF676D79, 0xFF4A4F5A, 0xFF2B2F37, 0xFF0E1116,
        ).map(::Color)
        private val CategoryDark = longArrayOf(
            0xFFB28CFF, 0xFFFF8AD0, 0xFF6FB4FF, 0xFF5FE3A8, 0xFFFFB064, 0xFFE6D36A, 0xFF5CD6D6, 0xFF8E94A4,
        )

        fun of(dark: Boolean, seed: Color, imported: ImportedPalette? = null): FluxColors {
            val ramp = imported?.ramp(dark) ?: if (dark) DarkRamp else PaperRamp
            val canvas = ramp[0]
            val acc = resolveAccent(seed, dark, canvas)
            val w = Color.White
            val inkRaw = if (dark) Color(0xFFF1F2F5) else Color(0xFF0E1116)
            val g = if (dark) floatArrayOf(.04f, .07f, .10f, .14f) else floatArrayOf(.46f, .66f, .82f, .94f)
            val line = if (dark) w else Color(0xFF0E1116)
            val coral = Color(if (dark) 0xFFFF5A5F else 0xFFD93A41)
            return FluxColors(
                dark = dark,
                ramp = ramp,
                canvas = canvas,
                ink = ramp[11],
                inkMuted = if (dark || imported != null) ramp[9] else Color(0xFF525866),
                inkFaint = if (dark || imported != null) ramp[8] else Color(0xFF707683),
                glass1 = w.copy(alpha = g[0]),
                glass2 = w.copy(alpha = g[1]),
                glass3 = w.copy(alpha = g[2]),
                glass4 = w.copy(alpha = g[3]),
                glassSolid1 = w.copy(alpha = g[0]).compositeOver(canvas),
                glassSolid2 = w.copy(alpha = g[1]).compositeOver(canvas),
                glassSolid3 = w.copy(alpha = g[2]).compositeOver(canvas),
                glassSolid4 = w.copy(alpha = g[3]).compositeOver(canvas),
                hairline = line.copy(alpha = if (dark) .08f else .09f),
                hairlineStrong = line.copy(alpha = if (dark) .16f else .18f),
                highlight = w.copy(alpha = if (dark) .13f else .90f),
                sheetBg = if (dark) Color(20, 22, 28).copy(alpha = .74f) else Color(250, 251, 253).copy(alpha = .82f),
                menuBg = if (dark) Color(26, 29, 36).copy(alpha = .78f) else Color(252, 253, 254).copy(alpha = .86f),
                // 浅色模式压暗而非提亮：白雾遮罩会让浅色对话框 / Sheet 与背景糊成一片，失去层次
                dim = if (dark) Color(2, 3, 5).copy(alpha = .55f) else Color(14, 17, 22).copy(alpha = .24f),
                scrimTopFrom = canvas.copy(alpha = if (dark) .85f else .90f),
                accent = seed,
                accentHi = acc.hi,
                // 浅色用压暗后的实色填充着色：亮种子色叠在纸面上偏淡、发雾；填充色更饱满，同样透明度下选中底更干净
                accentLo = if (dark) seed.copy(alpha = .16f) else acc.fill.copy(alpha = .11f),
                accentMid = if (dark) seed.copy(alpha = .34f) else acc.fill.copy(alpha = .24f),
                accentGlow = acc.hi.copy(alpha = .55f),
                accentFill = acc.fill,
                onAccent = acc.on,
                coral = coral,
                coralText = Color(if (dark) 0xFFFF7A7F else 0xFFC42A31),
                mint = Color(if (dark) 0xFF3DDC97 else 0xFF0E9F63),
                mintText = Color(if (dark) 0xFF3DDC97 else 0xFF097F4E),
                amber = Color(if (dark) 0xFFF5B544 else 0xFFB7791F),
                amberText = Color(if (dark) 0xFFF5B544 else 0xFF946016),
                onCoral = if (dark) Color(0xFF04101F) else w,
                violet = Color(0xFF8B5CF6),
                flowDone = inkRaw.copy(alpha = if (dark) .52f else .55f),
                flowRemain = inkRaw.copy(alpha = .08f),
                flowFail = coral.copy(alpha = if (dark) .70f else .75f),
                categoryColors = Array(CategoryDark.size) { i ->
                    // 浅色只沿 OKLab 明度压到 ≤ .60（对纸面 ≥ 3:1），保留彩度；混黑会让色点发脏
                    Color(CategoryDark[i]).let { if (dark) it else it.withOklabLightness(min(it.oklabLightness(), .60f)) }
                },
                grainAlpha = if (dark) .035f else .03f,
                grainBlend = if (dark) BlendMode.Softlight else BlendMode.Multiply,
            )
        }
    }

    /** 静态资产常量（启动图标、小组件首帧、通知 tint），运行时颜色一律走派生值。 */
    object Brand {
        val glowBlue = Color(0xFF5B9BFF)
        val flux = Color(0xFF3B82F6)
    }
}

/** 导入主题（ThemeDocument v2）两端色 → 12 阶灰（§2.9）。 */
@Immutable
class ImportedPalette(val background: Color, val foreground: Color) {
    fun ramp(dark: Boolean): List<Color> = (if (dark) T_DARK else T_PAPER).map { lerp(background, foreground, it) }

    private companion object {
        val T_DARK = floatArrayOf(0f, .029f, .058f, .090f, .122f, .168f, .231f, .324f, .503f, .660f, .841f, 1f)
        val T_PAPER = floatArrayOf(0f, .027f, .057f, .092f, .135f, .193f, .293f, .416f, .552f, .686f, .835f, 1f)
    }
}

@Immutable
data class AccentSlots(val fill: Color, val on: Color, val hi: Color)

/** WCAG 2.x 对比度。 */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + .05f
    val lb = b.luminance() + .05f
    return max(la, lb) / min(la, lb)
}

/**
 * 强调色护栏（§2.3）：保证按钮标签对纯色填充 [AccentSlots.fill] ≥ 4.5:1，accentHi 对 canvas ≥ 4.5:1。
 * 纯函数；`lerp` 即 Oklab 插值，与 CSS `color-mix(in oklab)` 等价。
 *
 * 浅色模式为满足白字对比度需要压暗强调色：只沿 OKLab 明度轴移动并保持彩度（[withOklabLightness]），
 * 不向黑色混合——混黑会同时削掉约 25% 彩度，蓝色会变成发灰的牛仔蓝，看起来“不活跃”。
 */
fun resolveAccent(seed: Color, dark: Boolean, canvas: Color): AccentSlots {
    val inkOn = Color(0xFF04101F)
    val white = Color.White
    val l0 = seed.oklabLightness()
    val startL = if (dark) l0 else l0 * .88f
    var fill = if (dark) seed else seed.withOklabLightness(startL)
    val pref = if (dark) inkOn else white
    val alt = if (dark) white else inkOn
    var on = pref
    if (contrast(pref, fill) < 4.5f) {
        if (contrast(alt, fill) >= 4.5f) {
            on = alt
        } else {
            val step = if (dark) .01f else -.01f
            var i = 0
            // 每次从原色映射，避免反复 sRGB 量化和色域裁剪累计损失彩度。
            while (i < 100 && contrast(pref, fill) < 4.5f) {
                i++
                fill = seed.withOklabLightness(startL + step * i)
            }
        }
    }
    var hi = if (dark) lerp(seed, white, .24f) else seed.withOklabLightness(l0 * .88f)
    if (!dark) {
        var i = 0
        while (i < 100 && contrast(hi, canvas) < 4.5f) {
            i++
            hi = seed.withOklabLightness(l0 * .88f - .01f * i)
        }
    }
    return AccentSlots(fill, on, hi)
}

/** OKLab 明度 L（0..1）。 */
internal fun Color.oklabLightness(): Float = toOklab()[0].toFloat()

/**
 * 只把 OKLab 明度改为 [lightness]（夹到 0..1），保持色相与彩度；目标超出 sRGB 色域时
 * 按比例二分收缩彩度直到落回色域（不做逐通道截断，避免色相偏移）。α 保持不变。
 */
internal fun Color.withOklabLightness(lightness: Float): Color {
    val lab = toOklab()
    val l = lightness.coerceIn(0f, 1f).toDouble()
    var k = 1.0
    if (!inSrgbGamut(oklabToLinear(l, lab[1], lab[2]))) {
        var lo = 0.0
        var hi = 1.0
        repeat(20) {
            val mid = (lo + hi) / 2
            if (inSrgbGamut(oklabToLinear(l, lab[1] * mid, lab[2] * mid))) lo = mid else hi = mid
        }
        k = lo
    }
    val lin = oklabToLinear(l, lab[1] * k, lab[2] * k)
    return Color(linearToSrgb(lin[0]), linearToSrgb(lin[1]), linearToSrgb(lin[2]), alpha)
}

private const val GAMUT_EPS = 1e-4

private fun inSrgbGamut(lin: DoubleArray): Boolean = lin.all { it >= -GAMUT_EPS && it <= 1 + GAMUT_EPS }

private fun srgbToLinear(c: Float): Double {
    val d = c.toDouble()
    return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
}

private fun linearToSrgb(c: Double): Float {
    val d = c.coerceIn(0.0, 1.0)
    return (if (d <= 0.0031308) 12.92 * d else 1.055 * d.pow(1 / 2.4) - 0.055).toFloat()
}

/** sRGB → OKLab（Björn Ottosson 参考矩阵）。 */
private fun Color.toOklab(): DoubleArray {
    val s = convert(ColorSpaces.Srgb)
    val r = srgbToLinear(s.red)
    val g = srgbToLinear(s.green)
    val b = srgbToLinear(s.blue)
    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val q = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return doubleArrayOf(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * q,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * q,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * q,
    )
}

/** OKLab → 线性 sRGB（可能越界，由调用方判定色域）。 */
private fun oklabToLinear(lightness: Double, a: Double, b: Double): DoubleArray {
    val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val q = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    return doubleArrayOf(
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * q,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * q,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * q,
    )
}

/** OKLab 彩度 sqrt(a²+b²)，用于判定壁纸取色是否过灰（< 0.04 回退品牌蓝）。 */
fun Color.oklabChroma(): Float {
    val c = convert(ColorSpaces.Oklab)
    return sqrt(c.green * c.green + c.blue * c.blue)
}
