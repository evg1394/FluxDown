package com.fluxdown.app.feature.rss

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.platform.LocalContext
import com.fluxdown.app.R
import com.fluxdown.app.actions.LocalTaskActions
import com.fluxdown.app.actions.TaskActions
import com.fluxdown.app.feature.settings.extensions.copyPlainText
import com.fluxdown.app.i18n.str
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.core.host.HostException
import com.fluxdown.core.host.HostSession
import com.fluxdown.core.model.RssSource
import com.fluxdown.core.protocol.HostMethod
import com.fluxdown.core.protocol.RssItemAction
import com.fluxdown.core.protocol.callUnit
import com.fluxdown.core.protocol.rssItemActionParams
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxOverlayState
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxHaptics
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 同时抓取的订阅数上限（R1：下拉“全部抓取” ≤ 4 并发）。 */
private const val REFRESH_PARALLELISM = 4

/**
 * 订阅动作（订阅列表与条目流共用）：只读拦截 + 触感 + toast。命令在应用级作用域里执行
 * （离开本页不取消，结果仍有回执）。
 * [busy] = 正在抓取的订阅（进程级共享集合：列表行显示点阵轨道，条目流显示「抓取中」）。
 */
@Stable
internal class RssController(
    private val scope: CoroutineScope,
    private val session: () -> HostSession,
    val busy: SnapshotStateSet<String>,
    private val overlays: FluxOverlayState,
    private val haptics: FluxHaptics,
    private val context: Context,
    private val actions: TaskActions,
) {
    /** 正在「全部标为已读」的订阅。 */
    val markingRead = SnapshotStateSet<String>()

    private fun s(id: Int, vararg args: Pair<String, Any?>) = context.str(id, *args)

    /** @return true = 抓取成功。 */
    private suspend fun fetch(source: RssSource): Boolean {
        busy.add(source.sourceId)
        return try {
            session().refreshRssSource(source.sourceId)
            true
        } catch (e: HostException) {
            haptics.reject()
            overlays.toast(actions.errorText(e), FluxToastKind.Error)
            false
        } finally {
            busy.remove(source.sourceId)
        }
    }

    fun refresh(source: RssSource) {
        if (!actions.guard() || source.sourceId in busy) return
        scope.launch {
            if (fetch(source)) {
                overlays.toast(s(R.string.mobileRssRefreshed, "name" to source.name.ifBlank { source.url }), FluxToastKind.Success)
            }
        }
    }

    fun refreshAll(sources: List<RssSource>) {
        if (!actions.guard()) return
        val targets = sources.filter { it.enabled && it.sourceId !in busy }
        if (targets.isEmpty()) {
            overlays.toast(s(R.string.mobileRssNothingToRefresh), FluxToastKind.Info)
            return
        }
        scope.launch {
            val gate = Semaphore(REFRESH_PARALLELISM)
            val results = coroutineScope { targets.map { src -> async { gate.withPermit { fetch(src) } } }.awaitAll() }
            val ok = results.count { it }
            val failed = results.size - ok
            haptics.confirm()
            overlays.toast(
                s(R.string.mobileRssRefreshSummary, "ok" to ok, "failed" to failed),
                if (failed == 0) FluxToastKind.Success else FluxToastKind.Warn,
            )
        }
    }

    fun toggle(source: RssSource) {
        if (!actions.guard()) return
        val enable = !source.enabled
        scope.launch {
            try {
                session().setRssSourceEnabled(source.sourceId, enable)
                haptics.confirm()
                overlays.toast(
                    s(if (enable) R.string.mobileRssEnabledToast else R.string.mobileRssDisabledToast),
                    FluxToastKind.Info,
                )
            } catch (e: HostException) {
                haptics.reject()
                overlays.toast(actions.errorText(e), FluxToastKind.Error)
            }
        }
    }

    /**
     * 全部标记已读（`itemAction{readAll}`，忽略 guid）；逐订阅调用。
     * 失败：有 [onFailure] 时交给调用方呈现（条目流用页内横幅），否则 toast。[onSuccess] 仅在全部成功后调用。
     */
    fun markAllRead(
        sources: List<RssSource>,
        onFailure: ((HostException) -> Unit)? = null,
        onSuccess: () -> Unit = {},
    ) {
        if (!actions.guard()) return
        val ids = sources.map { it.sourceId }.filter { it !in markingRead }
        if (ids.isEmpty()) return
        markingRead.addAll(ids)
        scope.launch {
            var failure: HostException? = null
            for (id in ids) {
                try {
                    session().callUnit(HostMethod.daemonRssItemAction, rssItemActionParams(id, RssItemAction.ReadAll))
                } catch (e: HostException) {
                    failure = e
                } finally {
                    markingRead.remove(id)
                }
            }
            val failed = failure
            if (failed == null) {
                haptics.confirm()
                overlays.toast(s(R.string.mobileRssMarkedReadToast), FluxToastKind.Success, FluxIcons.Check)
                onSuccess()
            } else {
                haptics.reject()
                if (onFailure != null) onFailure(failed) else overlays.toast(actions.errorText(failed), FluxToastKind.Error)
            }
        }
    }

    fun copyLink(source: RssSource) = copyText(source.url)

    /** 复制文本并 toast「已复制」。 */
    fun copyText(text: String) {
        context.copyPlainText(text)
        haptics.confirm()
        overlays.toast(s(R.string.mobileRssCopied), FluxToastKind.Success, FluxIcons.Copy)
    }

    fun errorText(e: HostException): String = actions.errorText(e)
}

@Composable
internal fun rememberRssController(): RssController {
    val container = LocalAppContainer.current
    val overlays = LocalFluxOverlays.current
    val actions = LocalTaskActions.current
    val haptics = FluxTheme.haptics
    val appContext = LocalContext.current.applicationContext
    return remember(container, overlays, haptics, actions, appContext) {
        RssController(container.appScope, { container.session }, container.rssFetching, overlays, haptics, appContext, actions)
    }
}
