package com.fluxdown.fluxui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强调色护栏：标签对纯色填充 accentFill、accentHi 对 canvas 都 ≥ 4.5:1；
 * 浅色模式为达标而压暗时不得把强调色洗灰，否则主按钮会显得“不活跃”。
 */
class ResolveAccentTest {
    private val seeds = FluxAccent.Presets.map { it.second } + listOf(Color(0xFF06B6D4), Color(0xFFF59E0B), Color(0xFF1E3A8A))

    @Test
    fun contrastGuardHoldsInBothModes() {
        for (dark in listOf(false, true)) {
            for (seed in seeds) {
                val c = FluxColors.of(dark, seed)
                val label = "seed=$seed dark=$dark"
                assertTrue("$label onAccent/fill", contrast(c.onAccent, c.accentFill) >= 4.5f)
                if (!dark) assertTrue("$label accentHi/canvas", contrast(c.accentHi, c.canvas) >= 4.5f)
            }
        }
    }

    /**
     * Oklab 中向黑混合会把 L、a、b 等比缩小，彩度随明度线性流失：C = C₀·L/L₀（色域上限恒不低于它）。
     * 压暗后的槽位彩度不得低于这条“混黑基线”；处在色域边缘的种子（如青色）只能持平，
     * 默认品牌蓝有余量，必须保留 ≥ 95% 彩度（混黑实现只剩约 75%）。
     */
    @Test
    fun lightModeDarkeningKeepsChroma() {
        for (seed in seeds) {
            val c = FluxColors.of(false, seed)
            val c0 = seed.oklabChroma()
            val l0 = seed.oklabLightness()
            for ((name, slot) in listOf("fill" to c.accentFill, "hi" to c.accentHi)) {
                val mixBaseline = c0 * slot.oklabLightness() / l0
                assertTrue(
                    "seed=$seed $name chroma ${slot.oklabChroma()} below black-mix baseline $mixBaseline",
                    slot.oklabChroma() >= mixBaseline - 1e-3f,
                )
            }
        }
        val blue = FluxColors.of(false, FluxAccent.presetColor("blue"))
        val blueC = FluxAccent.presetColor("blue").oklabChroma()
        for (slot in listOf(blue.accentFill, blue.accentHi)) {
            assertTrue("brand blue washed out: ${slot.oklabChroma()} vs $blueC", slot.oklabChroma() >= blueC * 0.95f)
        }
    }

    @Test
    fun withOklabLightnessKeepsHueAndStaysInGamut() {
        val seed = Color(0xFF3B82F6)
        for (l in listOf(0.1f, 0.45f, 0.8f, 0.97f)) {
            val shifted = seed.withOklabLightness(l)
            assertTrue("L=$l -> ${shifted.oklabLightness()}", kotlin.math.abs(shifted.oklabLightness() - l) < 0.01f)
            assertTrue("L=$l out of gamut", listOf(shifted.red, shifted.green, shifted.blue).all { it in 0f..1f })
            assertTrue("L=$l blue no longer dominant", shifted.blue >= shifted.red && shifted.blue >= shifted.green)
        }
    }
}
