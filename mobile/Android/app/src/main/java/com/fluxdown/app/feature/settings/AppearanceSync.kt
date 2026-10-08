package com.fluxdown.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdown.app.R
import com.fluxdown.app.data.AppearanceRepo
import com.fluxdown.app.data.AppearanceState
import com.fluxdown.app.data.ThemeMode
import com.fluxdown.app.i18n.str
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.app.shell.hostState
import com.fluxdown.core.model.HostRef
import com.fluxdown.core.protocol.AppearanceWire
import com.fluxdown.core.protocol.preferences
import com.fluxdown.core.store.Connection
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/*
 * 外观同步（根级，同 iOS AppearanceSync）：
 * - 本机外观（[AppearanceRepo.state]）变化 → `appearance.theme_mode` / `color_scheme` / `custom_color`（云同步偏好，经 ConfigEditor 立即写入）；
 *   命令搜索等任何入口改外观都经 repo，因此都会被推送。
 * - 主机偏好变化（含切换主机后的首个快照、重连）→ 反向应用到本机，以主机为准；
 * - 离线（只读）期间的修改只写本机，标脏，重连后若主机值没有变化就补推上去；
 * - 本机主机首次发现偏好里没有外观值而本机已自定义过（旧版本只存本机）时，把本机值迁移上去。
 * Android 专属的动态取色不同步。
 * 防回声：只推送与主机当前值不同的键；由主机应用到本机引起的本地变化不回推。
 */

private data class Look(val mode: ThemeMode, val scheme: String, val custom: Int)

private fun AppearanceState.look() = Look(mode, scheme, customColor)

/** 一次组合生命周期内的同步簿记（仅在主线程协程里访问）。 */
private class SyncBook {
    var local: Look? = null
    var appliedFromHost: Look? = null
    var lastHostId: String? = null
    var lastHost: AppearanceWire.Host? = null
    var dirty = false
}

private fun AppearanceWire.Host.wire(key: String): String? = when (key) {
    AppearanceWire.MODE_KEY -> mode
    AppearanceWire.SCHEME_KEY -> scheme
    else -> customArgb?.toString()
}

/**
 * 只保留与「最终会落到主机的值」不同的键：在途写入（[ConfigEditor.optimistic]）优先于主机已确认值。
 * 只比主机确认值会在上一次写入未确认时丢掉「改回原值」的第二次修改，随后被落地的第一次写入反向覆盖。
 */
private fun Map<String, String>.differingFrom(host: AppearanceWire.Host, inFlight: Map<String, String>): Map<String, String> =
    filter { (key, value) -> (inFlight[key] ?: host.wire(key)) != value }

private fun allValues(look: Look): Map<String, String> =
    AppearanceWire.encode(look.scheme, look.custom) + (AppearanceWire.MODE_KEY to look.mode.wire)

@Composable
internal fun AppearanceSyncEffect() {
    val container = LocalAppContainer.current
    val overlays = LocalFluxOverlays.current
    val editor = LocalConfigEditor.current
    val hostState = hostState()
    val hostRef by container.host.collectAsStateWithLifecycle()
    val repo = container.appearance
    val isLive by remember { derivedStateOf { hostState.value.connection == Connection.Live } }
    val hostValue by remember { derivedStateOf { AppearanceWire.Host.of(hostState.value.preferences) } }
    val book = remember { SyncBook() }
    val latestLive by rememberUpdatedState(isLive)
    val latestHost by rememberUpdatedState(hostValue)
    val failText = str(R.string.localServiceActionFailed)

    // 本机 → 主机
    LaunchedEffect(repo) {
        repo.state.map { it.look() }.distinctUntilChanged().collect { look ->
            val previous = book.local
            book.local = look
            if (previous == null) return@collect // 首个快照：只记录（迁移由主机侧效应处理）
            if (look == book.appliedFromHost) {
                book.appliedFromHost = null // 主机值应用到本机引起的变化：不回推
                return@collect
            }
            val values = LinkedHashMap<String, String>()
            if (look.mode != previous.mode) values[AppearanceWire.MODE_KEY] = look.mode.wire
            if (look.scheme != previous.scheme || look.custom != previous.custom) {
                values += AppearanceWire.encode(look.scheme, look.custom)
            }
            val pending = values.differingFrom(latestHost, editor.optimistic)
            if (pending.isEmpty()) return@collect
            if (latestLive) editor.setNow(pending) else book.dirty = true
        }
    }

    // 主机 → 本机（偏好变化 / 重连 / 切换主机）
    LaunchedEffect(hostRef.id, isLive, hostValue) {
        if (!isLive) return@LaunchedEffect
        val hostId = hostRef.id
        val local = repo.state.first().look()
        if (hostId != book.lastHostId || hostValue != book.lastHost) {
            val firstForHost = hostId != book.lastHostId
            book.lastHostId = hostId
            book.lastHost = hostValue
            try {
                applyHost(repo, book, local, AppearanceWire.decode(hostValue), editor.optimistic)
            } catch (e: IOException) {
                book.appliedFromHost = null
                overlays.toast(failText, FluxToastKind.Error)
            }
            if (firstForHost && hostRef is HostRef.Local) migrateLocal(editor, hostValue, local)
            book.dirty = false
        } else if (book.dirty) {
            book.dirty = false
            val pending = allValues(local).differingFrom(hostValue, editor.optimistic)
            if (pending.isNotEmpty()) editor.setNow(pending)
        }
    }
}

/** 主机值应用到本机；仍有在途写入的键跳过（主机稍后会追上本机，避免先闪回旧值）。 */
private suspend fun applyHost(
    repo: AppearanceRepo,
    book: SyncBook,
    local: Look,
    decoded: AppearanceWire.Local,
    inFlight: Map<String, String>,
) {
    val accentPending = AppearanceWire.SCHEME_KEY in inFlight || AppearanceWire.CUSTOM_KEY in inFlight
    val mode = decoded.mode?.takeIf { AppearanceWire.MODE_KEY !in inFlight }?.let { ThemeMode.of(it) }?.takeIf { it != local.mode }
    val scheme = decoded.scheme?.takeIf { !accentPending && it != local.scheme }
    val custom = decoded.customArgb?.takeIf { !accentPending && it != local.custom }
    if (mode == null && scheme == null && custom == null) return
    book.appliedFromHost = Look(mode ?: local.mode, scheme ?: local.scheme, custom ?: local.custom)
    repo.applyHost(mode, scheme, custom)
}

/** 旧版本外观只存本机：主机偏好里没有的键，本机非默认就迁移上去。 */
private fun migrateLocal(editor: ConfigEditor, host: AppearanceWire.Host, local: Look) {
    val values = LinkedHashMap<String, String>()
    if (host.mode == null && local.mode != ThemeMode.System) values[AppearanceWire.MODE_KEY] = local.mode.wire
    if (host.scheme == null && local.scheme != "blue") values += AppearanceWire.encode(local.scheme, local.custom)
    if (values.isNotEmpty()) editor.setNow(values)
}
