package com.fluxdown.app.feature.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.fluxdown.app.AppContainer
import com.fluxdown.core.host.HostErrorCode
import com.fluxdown.core.host.HostException
import com.fluxdown.core.protocol.JsonValue
import com.fluxdown.core.protocol.Normalized
import com.fluxdown.core.protocol.SettingsCatalog
import com.fluxdown.core.protocol.SettingsForm
import com.fluxdown.core.protocol.SettingsValidationError
import com.fluxdown.core.protocol.SettingsWritePlan
import com.fluxdown.core.protocol.fetchConfigRevision
import com.fluxdown.core.protocol.patchPreferences
import com.fluxdown.core.protocol.preferences
import com.fluxdown.core.store.HostState
import com.fluxdown.fluxui.overlay.FluxOverlayState
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.theme.FluxHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 设置的乐观写入器（唯一写入口：页面不得自行 patch），写路径镜像 iOS `ConfigEditor` / Web `writeStore.ts` /
 * GPUI `SettingsStore`。写入按 [SettingsCatalog] 路由（[SettingsWritePlan]）：
 * - 不在云同步目录的 daemon 键 → `daemon.config.patch {expectedRevision, values}`，Conflict 时读取最新版本重放（至多 3 次）；
 * - 落在云同步目录的 daemon 键 → `agent.preferences.patch`（同步键名 + JSON 类型值，否则下次拉取会被旧云端值覆盖）；
 * - agent 偏好 → `agent.preferences.patch`：目录内默认同步，其余 `sync: false`。
 *
 * 行为：[set] 先写乐观值 → 同键 250ms 防抖（`immediate` 跳过）→ 提交；提交之间串行，并等到 store 反映新值
 * （偏好以返回的 revision 为准）后才结束；值非法不发请求；失败回滚乐观值 + 行内说明（[failures]，3 秒淡出）+ toast。
 * 写入在 `appScope` 中执行，离开页面不会丢失尚在防抖中的修改；期间切换主机则丢弃，避免写到另一台主机。
 */
@Stable
internal class ConfigEditor(
    private val container: AppContainer,
    private val overlays: FluxOverlayState,
    private val haptics: FluxHaptics,
    private val errorText: (HostException) -> String,
    private val disconnectedText: String,
    private val invalidText: String,
) {
    /** 尚未被主机确认的本地值（wire，已规范化）：界面读取 `optimistic[key] ?: host[key]`。 */
    val optimistic = mutableStateMapOf<String, String>()

    /** 尚未被确认的原始 JSON 偏好（如 `custom_categories`）。 */
    val optimisticPrefs = mutableStateMapOf<String, JsonValue>()

    /** 最近一次失败的原因（按键）：行下方的红色说明。 */
    val failures = mutableStateMapOf<String, String>()

    private sealed interface Edit {
        data class Wire(val value: String) : Edit
        data class Json(val value: JsonValue) : Edit
    }

    private class Batch {
        val wires = LinkedHashMap<String, String>()
        val json = LinkedHashMap<String, JsonValue>()
    }

    private val timers = HashMap<String, Job>()
    private val queued = HashMap<String, Edit>()
    private val failureTimers = HashMap<String, Job>()
    private val lock = Mutex()

    private val state: HostState get() = container.store.state.value

    /** 当前表单视图（乐观值叠在主机值之上）；在组合内调用会订阅乐观值变化。 */
    fun form(state: HostState): SettingsForm =
        SettingsForm(state.config, optimistic, state.preferences.values, optimisticPrefs)

    /**
     * 防抖写入单个键（daemon 配置键或目录内偏好键，值为 wire 字符串）。
     * 值非法（越界 / 非成员 / 只读键）→ 不发请求，行内显示被拒原因；与主机一致且无待提交修改时忽略。
     */
    fun set(key: String, value: String, immediate: Boolean = false) {
        if (!writable()) return
        val wire = when (val n = SettingsCatalog.normalize(key, value)) {
            is Normalized.Rejected -> return reject(n.error)
            is Normalized.Ok -> n.wire
        }
        val hostForm = SettingsForm(state.config, prefs = state.preferences.values)
        if (wire == hostForm.value(key) && timers[key] == null && !optimistic.containsKey(key)) return
        optimistic[key] = wire
        clearFailure(key)
        stage(key, Edit.Wire(wire), immediate)
    }

    /** 防抖写入一个原始 JSON 偏好（`custom_categories` 等无 wire 字符串形态的值）；`JsonValue.Null` = 恢复默认。 */
    fun setPreference(key: String, value: JsonValue, immediate: Boolean = false) {
        if (!writable()) return
        SettingsWritePlan.validatePreferenceKey(key)?.let { return reject(it) }
        if (value == state.preferences[key] && timers[key] == null && !optimisticPrefs.containsKey(key)) return
        optimisticPrefs[key] = value
        clearFailure(key)
        stage(key, Edit.Json(value), immediate)
    }

    /** 立即写入多个键（对话框确认等一次性动作）；整批合法才发出。 */
    fun setNow(values: Map<String, String>, preferences: Map<String, JsonValue> = emptyMap()) {
        if (!writable()) return
        val normalized = LinkedHashMap<String, String>()
        for ((key, value) in values) {
            when (val n = SettingsCatalog.normalize(key, value)) {
                is Normalized.Rejected -> return reject(n.error)
                is Normalized.Ok -> normalized[key] = n.wire
            }
        }
        for (key in preferences.keys) SettingsWritePlan.validatePreferenceKey(key)?.let { return reject(it) }
        val batch = Batch()
        for ((key, wire) in normalized) {
            cancelPending(key)
            optimistic[key] = wire
            clearFailure(key)
            batch.wires[key] = wire
        }
        for ((key, json) in preferences) {
            cancelPending(key)
            optimisticPrefs[key] = json
            clearFailure(key)
            batch.json[key] = json
        }
        enqueue(batch, container.host.value.id)
    }

    private fun writable(): Boolean {
        if (!state.isReadOnly) return true
        haptics.reject()
        overlays.toast(disconnectedText, FluxToastKind.Error)
        return false
    }

    private fun cancelPending(key: String) {
        timers.remove(key)?.cancel()
        queued.remove(key)
    }

    private fun stage(key: String, edit: Edit, immediate: Boolean) {
        val host = container.host.value.id
        timers.remove(key)?.cancel()
        queued[key] = edit
        if (immediate) {
            flush(key, host)
            return
        }
        timers[key] = container.appScope.launch {
            delay(DEBOUNCE_MS)
            timers.remove(key)
            flush(key, host)
        }
    }

    private fun flush(key: String, host: String) {
        val edit = queued.remove(key) ?: return
        val batch = Batch()
        when (edit) {
            is Edit.Wire -> batch.wires[key] = edit.value
            is Edit.Json -> batch.json[key] = edit.value
        }
        enqueue(batch, host)
    }

    private fun enqueue(batch: Batch, host: String) {
        container.appScope.launch {
            lock.withLock {
                try {
                    commit(batch, host)
                } finally {
                    release(batch)
                }
            }
        }
    }

    private suspend fun commit(batch: Batch, host: String) {
        if (container.host.value.id != host) return // 期间切换了主机：丢弃
        val plan = when (val made = SettingsWritePlan.make(batch.wires)) {
            is SettingsWritePlan.Result.Rejected -> return reject(made.error)
            is SettingsWritePlan.Result.Ok -> made.plan
        }
        for ((key, value) in batch.json) plan.addPreference(key, value)

        val failedKeys = ArrayList<String>()
        var firstError: HostException? = null
        val confirmations = ArrayList<(HostState) -> Boolean>()

        if (plan.daemon.isNotEmpty()) {
            try {
                patchDaemonWithRetry(plan.daemon)
                val expected = HashMap(plan.daemon)
                confirmations += { s -> expected.all { (k, v) -> SettingsForm.configMatches(s.config[k], v) } }
            } catch (e: HostException) {
                firstError = firstError ?: e
                failedKeys += plan.daemon.keys
            }
        }
        for (sync in booleanArrayOf(true, false)) {
            val values = if (sync) plan.syncedPreferences else plan.localPreferences
            if (values.isEmpty()) continue
            try {
                val revision = patchPreferencesWithRetry(values, sync)
                val daemonExpect = values.mapNotNull { (name, json) ->
                    val daemonKey = plan.syncedDaemonKeys[name] ?: return@mapNotNull null
                    val wire = SettingsCatalog.wireFromJson(json) ?: return@mapNotNull null
                    daemonKey to wire
                }
                confirmations += { s ->
                    s.preferences.revision >= revision &&
                        daemonExpect.all { (k, v) -> SettingsForm.configMatches(s.config[k], v) }
                }
            } catch (e: HostException) {
                firstError = firstError ?: e
                failedKeys += values.keys.map { plan.syncedDaemonKeys[it] ?: it }
            }
        }

        if (confirmations.isNotEmpty()) {
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                container.store.state.first { s -> confirmations.all { it(s) } }
            }
        }
        val error = firstError ?: return
        val message = errorText(error)
        for (key in failedKeys) if (isStillPending(key, batch)) showFailure(key, message)
        haptics.reject()
        overlays.toast(message, FluxToastKind.Error)
    }

    /** 乐观值仍是本批写入的值（没有被之后的修改取代）。 */
    private fun isStillPending(key: String, batch: Batch): Boolean {
        batch.wires[key]?.let { return optimistic[key] == it }
        batch.json[key]?.let { return optimisticPrefs[key] == it }
        return false
    }

    /** 提交结束（成功等到 store 反映 / 失败回滚）：丢弃仍等于本批值且没有更新修改在途的乐观值。 */
    private fun release(batch: Batch) {
        for ((key, value) in batch.wires) {
            if (optimistic[key] == value && key !in timers && key !in queued) optimistic.remove(key)
        }
        for ((key, value) in batch.json) {
            if (optimisticPrefs[key] == value && key !in timers && key !in queued) optimisticPrefs.remove(key)
        }
    }

    private suspend fun patchDaemonWithRetry(values: Map<String, String>) {
        var retries = 0
        var hint = 0L
        while (true) {
            val revision = maxOf(state.configRevision, hint)
            try {
                container.session.patchConfig(revision, values)
                return
            } catch (e: HostException) {
                if (e.code != HostErrorCode.Conflict || retries >= MAX_CONFLICT_RETRIES) throw e
                retries++
                // HostException 不携带冲突回带的 revision：读取最新版本；读不到就等 store 推进。
                val fresh = try {
                    container.session.fetchConfigRevision()
                } catch (c: CancellationException) {
                    throw c
                } catch (_: HostException) {
                    null
                }
                if (fresh != null && fresh != revision) {
                    hint = fresh
                } else {
                    withTimeoutOrNull(CONFLICT_WAIT_MS) {
                        container.store.state.first { it.configRevision != revision }
                    }
                }
            }
        }
    }

    private suspend fun patchPreferencesWithRetry(values: Map<String, JsonValue>, sync: Boolean): Long {
        var retries = 0
        while (true) {
            try {
                return container.session.patchPreferences(values, sync)
            } catch (e: HostException) {
                // 同步的 daemon 键由 agent 代写 daemon，版本竞争时回报 Conflict；偏好本身是逐键 LWW。
                if (e.code != HostErrorCode.Conflict || retries >= MAX_CONFLICT_RETRIES) throw e
                retries++
                delay(PREF_RETRY_DELAY_MS)
            }
        }
    }

    /** 校验失败（不发请求）：行内说明 + toast。 */
    private fun reject(error: SettingsValidationError) {
        showFailure(error.key, invalidText)
        haptics.reject()
        overlays.toast(invalidText, FluxToastKind.Error)
    }

    private fun showFailure(key: String, message: String) {
        failures[key] = message
        failureTimers.remove(key)?.cancel()
        failureTimers[key] = container.appScope.launch {
            delay(FAILURE_LIFETIME_MS)
            failures.remove(key)
            failureTimers.remove(key)
        }
    }

    private fun clearFailure(key: String) {
        failureTimers.remove(key)?.cancel()
        failures.remove(key)
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val CONFIRM_TIMEOUT_MS = 2_000L
        const val CONFLICT_WAIT_MS = 1_000L
        const val FAILURE_LIFETIME_MS = 3_000L
        const val PREF_RETRY_DELAY_MS = 200L

        /** Conflict 后的最多重放次数（同 Web `MAX_CONFLICT_RETRIES`）。 */
        const val MAX_CONFLICT_RETRIES = 3
    }
}

/** 应用级唯一的设置写入器（AppShell 提供）。 */
internal val LocalConfigEditor = staticCompositionLocalOf<ConfigEditor> { error("ConfigEditor missing") }
