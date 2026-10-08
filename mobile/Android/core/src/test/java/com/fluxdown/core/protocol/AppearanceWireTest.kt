package com.fluxdown.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceWireTest {
    @Test
    fun sharedPresetsWriteOnlyTheSchemeKey() {
        assertEquals(mapOf("appearance.color_scheme" to "green"), AppearanceWire.encode("green", 0x123456))
        assertEquals(mapOf("appearance.color_scheme" to "blue"), AppearanceWire.encode("blue", 0x123456))
    }

    @Test
    fun customWritesSchemeAndUnsignedArgbTogether() {
        val wire = AppearanceWire.encode("custom", 0x00112233)
        assertEquals("custom", wire["appearance.color_scheme"])
        assertEquals(0xFF112233L.toString(), wire["appearance.custom_color"])
        assertEquals(2, wire.size)
        // Int 负数（Alpha = FF）也是无符号输出
        assertEquals("4284704497", AppearanceWire.encode("custom", 0xFF6366F1.toInt())["appearance.custom_color"])
    }

    @Test
    fun encodedValuesPassCatalogValidation() {
        for (scheme in listOf("blue", "custom")) {
            for ((key, value) in AppearanceWire.encode(scheme, 0xFFABCDEF.toInt())) {
                assertTrue("$key=$value", SettingsCatalog.normalize(key, value) is Normalized.Ok)
            }
        }
    }

    @Test
    fun argbRoundTripsIgnoringAlpha() {
        assertEquals(0xFF112233L, AppearanceWire.unsignedArgb(0x00112233))
        assertEquals(0xFF112233.toInt(), AppearanceWire.opaqueArgb(0xFF112233L))
        assertEquals(0xFF112233.toInt(), AppearanceWire.opaqueArgb(0x00112233L))
    }

    @Test
    fun decodeMapsHostValuesToLocalAppearance() {
        assertEquals(AppearanceWire.Local("dark", "rose", null), AppearanceWire.decode(AppearanceWire.Host("dark", "rose", null)))
        assertEquals(
            AppearanceWire.Local(null, "custom", 0xFF112233.toInt()),
            AppearanceWire.decode(AppearanceWire.Host(null, "custom", 0xFF112233L)),
        )
        assertEquals(AppearanceWire.Local(null, "custom", null), AppearanceWire.decode(AppearanceWire.Host(null, "custom", null)))
    }

    @Test
    fun decodeIgnoresMissingOrUnknownValues() {
        assertEquals(AppearanceWire.Local(), AppearanceWire.decode(AppearanceWire.Host()))
        assertEquals(AppearanceWire.Local(), AppearanceWire.decode(AppearanceWire.Host("auto", "teal", 5)))
        assertTrue(AppearanceWire.Host().isEmpty)
    }

    @Test
    fun hostValuesComeFromThePreferencesSection() {
        val prefs = AgentPreferences(
            values = mapOf(
                "appearance.theme_mode" to JsonValue.Str("light"),
                "appearance.color_scheme" to JsonValue.Str("custom"),
                "appearance.custom_color" to JsonValue.Num("4278255360"),
            ),
        )
        assertEquals(AppearanceWire.Host("light", "custom", 0xFF00FF00L), AppearanceWire.Host.of(prefs))
    }
}
