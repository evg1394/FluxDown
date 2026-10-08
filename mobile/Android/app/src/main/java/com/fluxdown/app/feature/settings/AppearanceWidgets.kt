package com.fluxdown.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.fluxdown.app.R
import com.fluxdown.app.data.ThemeMode
import com.fluxdown.app.i18n.str
import com.fluxdown.core.format.Format
import com.fluxdown.fluxui.controls.FluxField
import com.fluxdown.fluxui.controls.FluxSlider
import com.fluxdown.fluxui.data.FileTile
import com.fluxdown.fluxui.data.FileTileSize
import com.fluxdown.fluxui.data.FlowSegmentUi
import com.fluxdown.fluxui.data.FlowStrip
import com.fluxdown.fluxui.data.FlowStripState
import com.fluxdown.fluxui.data.fileTileIcon
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.theme.FileCategory
import com.fluxdown.fluxui.theme.FluxAccent
import com.fluxdown.fluxui.theme.FluxColors
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.contrast
import com.fluxdown.fluxui.theme.fluxPressable
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// ───────────────────────────── 明暗模式磁贴 ─────────────────────────────

private val ModeOrder = listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.System)

/** 三个模式磁贴（暗 / 亮 / 跟随系统）：磁贴里的迷你画布用两套真实调色板（以当前强调色）绘制。 */
@Composable
internal fun ModeTiles(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val seed = FluxTheme.colors.accent
    val dark = remember(seed) { FluxColors.of(true, seed) }
    val light = remember(seed) { FluxColors.of(false, seed) }
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in ModeOrder) {
            ModeTile(mode, mode == selected, dark, light, { onSelect(mode) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeTile(
    mode: ThemeMode,
    selected: Boolean,
    dark: FluxColors,
    light: FluxColors,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val motion = FluxTheme.motion
    val tileShape = RoundedCornerShape(20.dp)
    val ring by animateFloatAsState(if (selected) 1f else 0f, motion.of(motion.fluid), label = "mode-ring")
    val label = when (mode) {
        ThemeMode.Dark -> str(R.string.themeModeDark)
        ThemeMode.Light -> str(R.string.themeModeLight)
        ThemeMode.System -> str(R.string.themeModeSystem)
    }
    val icon = when (mode) {
        ThemeMode.Dark -> FluxIcons.Moon
        ThemeMode.Light -> FluxIcons.Sun
        ThemeMode.System -> FluxIcons.Smartphone
    }
    Box(
        modifier
            .heightIn(min = 96.dp)
            .fluxPressable(onClick = onClick, role = Role.RadioButton)
            .semantics { this.selected = selected }
            .drawBehind {
                val r = CornerRadius(20.dp.toPx())
                drawRoundRect(c.glass2, cornerRadius = r)
                drawRoundRect(c.hairline, cornerRadius = r, style = Stroke(0.5.dp.toPx()))
                if (ring > 0.001f) {
                    drawRoundRect(
                        c.accentLo.copy(alpha = c.accentLo.alpha * ring),
                        cornerRadius = r,
                    )
                    drawRoundRect(
                        c.accent.copy(alpha = ring),
                        cornerRadius = r,
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
            .padding(8.dp),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .drawBehind {
                        when (mode) {
                            ThemeMode.Dark -> drawMiniScreen(dark)
                            ThemeMode.Light -> drawMiniScreen(light)
                            ThemeMode.System -> {
                                drawMiniScreen(dark)
                                val lowerRight = Path().apply {
                                    moveTo(size.width, 0f)
                                    lineTo(size.width, size.height)
                                    lineTo(0f, size.height)
                                    close()
                                }
                                clipPath(lowerRight) { drawMiniScreen(light) }
                            }
                        }
                    }
                    .border(0.5.dp, c.hairline, RoundedCornerShape(12.dp)),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FluxIcon(icon, null, size = 16.dp, tint = if (selected) c.accentHi else c.inkMuted)
                FluxText(label, style = t.weight(t.sm, 500), color = c.ink, maxLines = 1)
            }
        }
        AnimatedVisibility(
            visible = selected,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            enter = scaleIn(motion.of(motion.liquid)) + fadeIn(motion.of(motion.fluid)),
            exit = scaleOut(motion.of(motion.snap)) + fadeOut(motion.of(motion.snap)),
        ) {
            Box(
                Modifier.size(17.dp).background(c.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                FluxIcon(FluxIcons.Check, null, size = 11.dp, tint = c.onAccent)
            }
        }
    }
}

/** 迷你界面：画布 + 强调条 + 两条玻璃行。 */
private fun DrawScope.drawMiniScreen(p: FluxColors) {
    drawRect(p.canvas)
    val pad = 8.dp.toPx()
    val inner = size.width - pad * 2
    drawRoundRect(p.accent, Offset(pad, pad), Size(size.width * 0.30f, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
    drawRoundRect(p.ramp[3], Offset(pad, pad + 10.dp.toPx()), Size(inner, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
    drawRoundRect(p.ramp[3], Offset(pad, pad + 20.dp.toPx()), Size(inner * 0.62f, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
}

// ───────────────────────────── 主题色圆点 ─────────────────────────────

private val HueWheel = List(7) { Color(ColorUtils.HSLToColor(floatArrayOf(it * 60f, 0.8f, 0.58f))) }

/**
 * 5 个 48dp 圆点：蓝 / 绿 / 紫 / 玫红 + 自定义（锥形彩虹）。[dimmed]（跟随壁纸取色时）降到 38% 且不可点。
 */
@Composable
internal fun AccentDots(
    scheme: String,
    customColor: Int,
    dimmed: Boolean,
    onPreset: (String) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val names = mapOf(
        "blue" to str(R.string.colorBlue),
        "green" to str(R.string.colorGreen),
        "violet" to str(R.string.colorViolet),
        "rose" to str(R.string.colorRose),
    )
    Row(
        modifier.fillMaxWidth().alpha(if (dimmed) 0.38f else 1f).selectableGroup(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for ((id, color) in FluxAccent.Presets) {
            AccentDot(
                name = names.getValue(id),
                selected = scheme == id,
                enabled = !dimmed,
                swatch = color,
                brush = null,
                onClick = { onPreset(id) },
                modifier = Modifier.weight(1f),
            )
        }
        val custom = Color(customColor)
        AccentDot(
            name = str(R.string.colorCustom),
            selected = scheme == "custom",
            enabled = !dimmed,
            swatch = custom,
            brush = if (scheme == "custom") null else Brush.sweepGradient(HueWheel),
            onClick = onCustom,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AccentDot(
    name: String,
    selected: Boolean,
    enabled: Boolean,
    swatch: Color,
    brush: Brush?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val motion = FluxTheme.motion
    val scale by animateFloatAsState(if (selected) 1f else 0.9f, motion.of(motion.liquid), label = "accent-dot")
    val onSwatch = remember(swatch) { FluxColors.of(true, swatch).onAccent }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(48.dp)
                .fluxPressable(onClick = onClick, enabled = enabled, role = Role.RadioButton, scale = 0.92f)
                .semantics {
                    this.selected = selected
                    contentDescription = name
                },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(48.dp).border(2.dp, swatch, CircleShape))
            }
            Box(
                Modifier
                    .size(38.dp)
                    .scale(scale)
                    .then(
                        if (brush != null) Modifier.background(brush, CircleShape) else Modifier.background(swatch, CircleShape),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    selected -> FluxIcon(FluxIcons.Check, null, size = 18.dp, tint = onSwatch)
                    brush != null -> FluxIcon(FluxIcons.Palette, null, size = 18.dp, tint = c.canvas)
                }
            }
        }
        FluxText(name, style = t.micro, color = if (selected) c.ink else c.inkMuted, maxLines = 1)
    }
}

// ───────────────────────────── 自定义取色器 ─────────────────────────────

private fun hslOf(argb: Int): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(argb, it) }

private fun argbOf(h: Float, s: Float, l: Float): Int =
    ColorUtils.HSLToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))) or (0xFF shl 24)

/**
 * 自定义强调色：色块 + `#RRGGBB` + 暗 / 亮画布样张，H / S / L 三滑杆与十六进制输入。
 * 每次变化回调 [onChange]（ARGB，alpha 固定 FF）；防抖写入由调用方负责。
 */
@Composable
internal fun CustomColorPicker(initial: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val start = remember { hslOf(initial) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var lum by remember { mutableFloatStateOf(start[2]) }
    var hexDraft by remember { mutableStateOf(initial.hexRgb().removePrefix("#")) }
    val argb = argbOf(hue, sat, lum)
    val color = Color(argb)
    val darkCanvas = remember(color) { FluxColors.of(true, color).canvas }
    val lightCanvas = remember(color) { FluxColors.of(false, color).canvas }
    val hexInvalid = hexDraft.length != 6

    fun applyHsl(h: Float, s: Float, l: Float) {
        hue = h
        sat = s
        lum = l
        val next = argbOf(h, s, l)
        hexDraft = next.hexRgb().removePrefix("#")
        onChange(next)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(color, RoundedCornerShape(16.dp))
                    .border(0.5.dp, c.hairlineStrong, RoundedCornerShape(16.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FluxText(argb.hexRgb(), style = t.weight(t.h2, 600, mono = true), color = c.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (canvas in listOf(darkCanvas, lightCanvas)) {
                        Box(
                            Modifier.size(24.dp).background(canvas, RoundedCornerShape(8.dp))
                                .border(0.5.dp, c.hairline, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(10.dp).background(color, CircleShape))
                        }
                    }
                }
            }
        }
        ChannelSlider(
            name = str(R.string.mobileColorHue),
            value = hue,
            range = 0f..360f,
            format = { "${it.roundToInt()}°" },
            onValue = { applyHsl(it, sat, lum) },
        )
        ChannelSlider(
            name = str(R.string.mobileColorSaturation),
            value = sat * 100f,
            range = 0f..100f,
            format = { "${it.roundToInt()}%" },
            onValue = { applyHsl(hue, it / 100f, lum) },
        )
        ChannelSlider(
            name = str(R.string.mobileColorLightness),
            value = lum * 100f,
            range = 0f..100f,
            format = { "${it.roundToInt()}%" },
            onValue = { applyHsl(hue, sat, it / 100f) },
        )
        Spacer(Modifier.height(6.dp))
        FluxField(
            value = hexDraft,
            onValueChange = { raw ->
                val cleaned = raw.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
                hexDraft = cleaned
                if (cleaned.length == 6) {
                    val parsed = cleaned.toInt(16) or (0xFF shl 24)
                    val hsl = hslOf(parsed)
                    hue = hsl[0]
                    sat = hsl[1]
                    lum = hsl[2]
                    onChange(parsed)
                }
            },
            label = str(R.string.mobileColorHex),
            prefix = "#",
            mono = true,
            error = if (hexInvalid) str(R.string.mobileHexInvalid) else null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions.Default,
        )
        if (contrast(color, c.canvas) < 3f) {
            FluxText(
                str(R.string.mobileColorLowContrast),
                style = t.sm,
                color = c.amberText,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/** 标签 + 数值读数；滑杆气泡画在上方，故先留出 36dp 净空。 */
@Composable
private fun ChannelSlider(
    name: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onValue: (Float) -> Unit,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    Column {
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FluxText(name, style = t.sm, color = c.inkMuted)
            FluxText(format(value), style = t.weight(t.sm, 500, mono = true), color = c.ink)
        }
        FluxSlider(
            value = value,
            onValueChange = onValue,
            range = range,
            format = format,
            label = name,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ───────────────────────────── 主题预览（简化 MiniApp） ─────────────────────────────

/**
 * 外观预览：真实组件（FlowStrip / FileTile）用当前强调色绘制的纯色迷你界面。
 * 数值为装饰用示意，读屏只读“预览”。
 */
@Composable
internal fun ThemePreview(modifier: Modifier = Modifier) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val shape = FluxTheme.shapes.card
    val speed = remember { Format.speedOrZero(12_582_912L) }
    val preview = str(R.string.mobilePreview)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.canvas, shape)
            .border(0.5.dp, c.hairline, shape)
            .clearAndSetSemantics { contentDescription = preview }
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FluxText(speed.value, style = t.weight(t.h1, 600, mono = true), color = c.ink, maxLines = 1)
                FluxText(speed.unit, style = t.sm, color = c.inkMuted, modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
            }
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                val path = Path()
                val steps = 48
                for (i in 0..steps) {
                    val x = size.width * i / steps
                    val phase = i / steps.toFloat()
                    val y = size.height * (0.55f - 0.28f * sin(phase * 2f * PI.toFloat() * 1.5f) - 0.12f * sin(phase * 2f * PI.toFloat() * 4f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, c.accentHi, style = Stroke(1.5.dp.toPx()))
            }
            PreviewRow(FileCategory.Video, 0.62f)
            PreviewRow(FileCategory.Archive, 0.28f)
        }
    }
}

@Composable
private fun PreviewRow(category: FileCategory, filled: Float) {
    val c = FluxTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FileTile(fileTileIcon(category), c.category(category), size = FileTileSize.Sm)
        FlowStrip(
            segments = listOf(FlowSegmentUi(fraction = 1f, filled = filled, active = true)),
            state = FlowStripState.Downloading,
            modifier = Modifier.weight(1f),
        )
    }
}
