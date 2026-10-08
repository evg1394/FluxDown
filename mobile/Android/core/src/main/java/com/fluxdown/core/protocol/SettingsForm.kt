package com.fluxdown.core.protocol

/**
 * 一次渲染内的配置视图：显示值 = 乐观值 ?: 主机值 ?: 目录默认（同 iOS `SettingsConfigForm` / Web `useDaemonValue`）。
 * daemon 配置键与 agent 偏好键共用一套读取；写入由 `ConfigEditor` 按 [SettingsCatalog] 路由。
 *
 * [optimistic] / [optimisticPrefs] 可以是 Compose 快照 Map：在组合内读取即建立订阅。
 */
class SettingsForm(
    /** `daemon.config` 快照值（wire 字符串）。 */
    val host: Map<String, String>,
    val optimistic: Map<String, String> = emptyMap(),
    /** `agent.preferences` 当前值。 */
    val prefs: Map<String, JsonValue> = emptyMap(),
    /** 原始 JSON 偏好的乐观值（如 `custom_categories`）。 */
    val optimisticPrefs: Map<String, JsonValue> = emptyMap(),
) {
    /** 主机配置已加载（连接前 / 旧主机为空：daemon 行一律不渲染）。 */
    val isLoaded: Boolean get() = host.isNotEmpty()

    /** 键可用：偏好键恒可用（缺省取目录默认）；daemon 键要求配置已加载（目录内的键缺省取默认值）。 */
    fun has(key: String): Boolean {
        val field = SettingsCatalog.field(key)
        if (field?.store == SettingField.Store.Preference) return true
        return isLoaded && (host.containsKey(key) || field != null)
    }

    fun any(vararg keys: String): Boolean = keys.any { has(it) }

    /** 显示值（wire 字符串）；不可用键 → null。 */
    fun value(key: String): String? {
        optimistic[key]?.let { return it }
        if (SettingsCatalog.field(key)?.store == SettingField.Store.Preference) {
            return SettingsCatalog.wireFromJson(optimisticPrefs[key] ?: prefs[key]) ?: SettingsCatalog.defaultWire(key)
        }
        if (!isLoaded) return null
        return host[key] ?: SettingsCatalog.field(key)?.defaultWire
    }

    /** 原始 JSON 偏好（乐观值优先）。 */
    fun pref(key: String): JsonValue? = optimisticPrefs[key] ?: prefs[key]

    fun bool(key: String): Boolean = value(key).let { it == "true" || it == "1" }

    fun int(key: String, default: Int): Int = value(key)?.trim()?.toIntOrNull() ?: default

    fun long(key: String, default: Long): Long = value(key)?.trim()?.toLongOrNull() ?: default

    fun double(key: String, default: Double): Double = value(key)?.trim()?.toDoubleOrNull() ?: default

    fun string(key: String, default: String = ""): String = value(key) ?: default

    companion object {
        fun wire(flag: Boolean): String = if (flag) "true" else "false"

        /** 主机值与期望值相同（浮点按数值比较：daemon 把 `1.0` 规范成 `1`）。 */
        fun configMatches(actual: String?, expected: String): Boolean {
            if (actual == null) return false
            if (actual == expected) return true
            val a = actual.toDoubleOrNull() ?: return false
            val e = expected.toDoubleOrNull() ?: return false
            return a == e
        }
    }
}
