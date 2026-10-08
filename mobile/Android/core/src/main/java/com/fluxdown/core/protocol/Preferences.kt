package com.fluxdown.core.protocol

import com.fluxdown.core.host.HostErrorCode
import com.fluxdown.core.host.HostException
import com.fluxdown.core.host.HostSession
import com.fluxdown.core.store.HostState

/**
 * `agent.preferences` 分区值（`AgentPreferencesDto`）：偏好全集及其原子版本。
 * 镜像 `native/protocol/src/agent.rs` 与 iOS `Preferences.swift`。
 */
data class AgentPreferences(
    val revision: Long = 0,
    val values: Map<String, JsonValue> = emptyMap(),
) {
    operator fun get(key: String): JsonValue? = values[key]

    fun bool(key: String, default: Boolean): Boolean = values[key].boolOrNull ?: default

    fun string(key: String, default: String): String = values[key].stringOrNull ?: default

    /** 偏好值的 wire 字符串形式（bool / 数字 / 字符串）；缺失或结构化值为 null。 */
    fun wire(key: String): String? = SettingsCatalog.wireFromJson(values[key])

    companion object {
        val Empty = AgentPreferences()

        fun parse(json: String?): AgentPreferences {
            val v = Json.parseOrNull(json) ?: return Empty
            return AgentPreferences(revision = v.long("revision"), values = v["values"].objOrNull.orEmpty())
        }
    }
}

/** 单项解码缓存：界面每次重组都会读 [HostState.preferences]，而分区字节只在偏好变化时才变。 */
private object PreferencesCache {
    @Volatile
    private var last: Pair<String?, AgentPreferences> = null to AgentPreferences.Empty

    fun decode(json: String?): AgentPreferences {
        val cached = last
        if (cached.first == json) return cached.second
        val value = AgentPreferences.parse(json)
        last = json to value
        return value
    }
}

/** 当前偏好（`agent.preferences` 分区）；分区缺失 / 损坏为空。相同文本只解码一次。 */
val HostState.preferences: AgentPreferences
    get() = PreferencesCache.decode(sections[HostSection.agentPreferences])

/** 解析某个通用分区（缺失 / 损坏为 null）。 */
fun HostState.section(name: String): JsonValue? = Json.parseOrNull(sections[name])

/** 主机是否声明了某能力（[HostCapability]）。 */
fun HostState.has(capability: String): Boolean = info?.capabilities?.contains(capability) == true

// ───────────────────────────── 通用通道 ─────────────────────────────

/**
 * 通用通道的 JSON 形态：[params] 为 null 时不带参数；结果解析失败抛 [HostException]（Internal）。
 * 方法名用 [HostMethod] 常量；参数 / 结果形状 = 协议 serde wire（camelCase）。
 */
suspend fun HostSession.callJson(method: String, params: JsonValue? = null): JsonValue {
    val raw = call(method, params?.toJson())
    return try {
        Json.parse(raw)
    } catch (e: JsonException) {
        throw HostException(HostErrorCode.Internal, message = "$method: malformed result: ${e.message}", cause = e)
    }
}

/** 不关心结果的调用。 */
suspend fun HostSession.callUnit(method: String, params: JsonValue? = null) {
    call(method, params?.toJson())
}

/** `agent.preferences.patch`：[sync] = false 只写本机偏好；值 `JsonValue.Null` = 恢复默认（墓碑）。返回落定后的偏好版本。 */
suspend fun HostSession.patchPreferences(values: Map<String, JsonValue>, sync: Boolean): Long {
    val params = LinkedHashMap<String, JsonValue>(2)
    params["values"] = JsonValue.Obj(values)
    if (!sync) params["sync"] = JsonValue.False
    return callJson(HostMethod.agentPreferencesPatch, JsonValue.Obj(params)).long("revision")
}

/** `daemon.config.get` 的当前版本（冲突后重放用）。 */
suspend fun HostSession.fetchConfigRevision(): Long = callJson(HostMethod.daemonConfigGet).long("revision")
