package com.fluxdown.core.protocol

/**
 * 外观偏好 ↔ `agent.preferences` 的线上形态映射（纯逻辑，同 iOS `AppearanceWire`）。
 *
 * PC 端只认 `appearance.theme_mode`（system/light/dark）、`appearance.color_scheme`（blue/green/violet/rose/custom，
 * 未知值回落蓝色）与 `appearance.custom_color`（Flutter `Color.toARGB32()` 的无符号 ARGB 整数，仅 `custom` 时生效）。
 * Android 本地方案集与 PC 一致（预设 + custom），iOS 的 orange / indigo 预设在线上是 `custom` + 对应色值，
 * 在这里按普通自定义色处理。
 */
object AppearanceWire {
    const val MODE_KEY = "appearance.theme_mode"
    const val SCHEME_KEY = "appearance.color_scheme"
    const val CUSTOM_KEY = "appearance.custom_color"

    private val sharedPresets = setOf("blue", "green", "violet", "rose")
    private val modes = setOf("system", "light", "dark")

    /** 主机偏好里的外观值（缺失为 null）。[customArgb] 为无符号 32 位 ARGB（Long）。 */
    data class Host(val mode: String? = null, val scheme: String? = null, val customArgb: Long? = null) {
        val isEmpty: Boolean get() = mode == null && scheme == null && customArgb == null

        companion object {
            fun of(prefs: AgentPreferences): Host = Host(
                mode = prefs[MODE_KEY].stringOrNull,
                scheme = prefs[SCHEME_KEY].stringOrNull,
                customArgb = prefs[CUSTOM_KEY].longOrNull,
            )
        }
    }

    /** 应用到本地的结果：null = 保持本地不变。[customArgb] 为不透明 ARGB（Int）。 */
    data class Local(val mode: String? = null, val scheme: String? = null, val customArgb: Int? = null)

    /** 无符号 ARGB（Long）→ 不透明 ARGB Int（Alpha 强制 FF，同 iOS 忽略 alpha）。 */
    fun opaqueArgb(unsigned: Long): Int = (0xFF000000L or (unsigned and 0xFFFFFFL)).toInt()

    /** 本地 ARGB Int → 线上无符号整数（Alpha 强制 FF）。 */
    fun unsignedArgb(argb: Int): Long = 0xFF000000L or (argb.toLong() and 0xFFFFFFL)

    /** 主机值 → 本地外观。 */
    fun decode(host: Host): Local {
        val mode = host.mode?.takeIf { it in modes }
        val scheme = host.scheme
        return when {
            scheme != null && scheme in sharedPresets -> Local(mode, scheme)
            scheme == "custom" -> {
                val argb = host.customArgb
                if (argb != null) Local(mode, "custom", opaqueArgb(argb)) else Local(mode, "custom")
            }
            else -> Local(mode) // 缺失 / 未知方案：保持本地
        }
    }

    /** 本地外观 → 要写的偏好键值（wire 字符串；方案与自定义色同批写入，同 GPUI）。 */
    fun encode(scheme: String, customArgb: Int): Map<String, String> {
        if (scheme in sharedPresets) return mapOf(SCHEME_KEY to scheme)
        return mapOf(SCHEME_KEY to "custom", CUSTOM_KEY to unsignedArgb(customArgb).toString())
    }
}
