package com.fluxdown.app.feature.rss

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import com.fluxdown.app.R
import com.fluxdown.app.actions.TaskActions
import com.fluxdown.app.i18n.str
import com.fluxdown.core.host.HostEvent
import com.fluxdown.core.host.HostException
import com.fluxdown.core.host.HostSession
import com.fluxdown.core.model.RssSource
import com.fluxdown.core.protocol.HostMethod
import com.fluxdown.core.protocol.HostNotice
import com.fluxdown.core.protocol.Json
import com.fluxdown.core.protocol.RssItemAction
import com.fluxdown.core.protocol.RssItemDto
import com.fluxdown.core.protocol.RssItemsChangedNotice
import com.fluxdown.core.protocol.callJson
import com.fluxdown.core.protocol.callUnit
import com.fluxdown.core.protocol.jsonObject
import com.fluxdown.core.protocol.rssItemActionParams
import com.fluxdown.fluxui.overlay.FluxBannerKind
import com.fluxdown.fluxui.theme.FluxHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class ItemsPhase {
    /** 首次拉取中（尚无条目）。 */
    Loading,
    Loaded,
    Failed,
}

/** 页内横幅（批量结果 / 新条目提示 / 失败）；[jumpsToTop] = 点按横幅滚到列表顶部（新条目提示）。 */
@Immutable
internal data class ItemsFeedback(val text: String, val kind: FluxBannerKind, val jumpsToTop: Boolean = false)

/**
 * R2 条目流状态（同 iOS `RssItemsModel` / Web `useRssItems`）。页面以 `remember(sourceId)` 持有：
 * 切换订阅即整体重置（条目、选择、忙碌集合、横幅）。
 *
 * 条目来源 `daemon.rss.getItems`；`daemon.rssItemRevisions[sourceId]` 变化、手动重试、动作成功后重拉
 * （由页面的 `LaunchedEffect` 调 [load]）。拉取期间保留旧条目（避免闪烁）。
 * 条目动作在应用级作用域 [scope] 里执行（离开页面不取消批量）。
 */
@Stable
internal class RssItemsModel(
    val sourceId: String,
    private val scope: CoroutineScope,
    private val session: () -> HostSession,
    private val actions: TaskActions,
    private val controller: RssController,
    private val haptics: FluxHaptics,
    private val context: Context,
) {
    var items by mutableStateOf<List<RssItemDto>>(emptyList())
        private set
    var phase by mutableStateOf(ItemsPhase.Loading)
        private set
    var query by mutableStateOf("")
    var oldestFirst by mutableStateOf(false)

    /** 选择模式下已选 guid。 */
    val selection = SnapshotStateSet<String>()
    var selecting by mutableStateOf(false)
        private set

    /** 正在处理的 guid（行内「准备中…」）。 */
    val busy = SnapshotStateSet<String>()
    var feedback by mutableStateOf<ItemsFeedback?>(null)
        private set

    /** 递增即触发重拉（页面 `LaunchedEffect` 的键之一）。 */
    var reloadTick by mutableIntStateOf(0)
        private set

    /** 行内展开详情的 guid。 */
    val expanded = SnapshotStateSet<String>()

    /** 递增即让列表滚到顶部（新条目横幅被点按）。 */
    var scrollTopTick by mutableIntStateOf(0)
        private set

    val readBusy: Boolean get() = sourceId in controller.markingRead

    private fun s(id: Int, vararg args: Pair<String, Any?>) = context.str(id, *args)

    private fun show(value: ItemsFeedback) {
        when (value.kind) {
            FluxBannerKind.Success -> haptics.confirm()
            FluxBannerKind.Error -> haptics.reject()
            else -> Unit
        }
        feedback = value
    }

    fun dismissFeedback() {
        feedback = null
    }

    fun reload() {
        reloadTick++
    }

    fun toggleSelecting() {
        selecting = !selecting
        if (!selecting) selection.clear()
    }

    fun startSelecting(guid: String) {
        selecting = true
        selection.clear()
        selection.add(guid)
    }

    fun toggleSelected(guid: String) {
        if (!selection.remove(guid)) selection.add(guid)
    }

    fun toggleExpanded(guid: String) {
        if (!expanded.remove(guid)) expanded.add(guid)
    }

    /** 只作用于当前搜索 / 排序下**可见**条目。 */
    fun setVisibleSelected(visible: List<RssItemDto>, selected: Boolean) {
        val ids = visible.map { it.guid }
        if (selected) selection.addAll(ids) else selection.removeAll(ids.toSet())
    }

    fun clearSelection() = selection.clear()

    // ── 拉取 ────────────────────────────────────────────────────────────

    suspend fun load() {
        if (items.isEmpty()) phase = ItemsPhase.Loading
        try {
            val list = RssItemDto.listFromJson(session().callJson(HostMethod.daemonRssGetItems, jsonObject("sourceId" to sourceId)))
            items = list
            val known = list.mapTo(HashSet()) { it.guid }
            selection.retainAll(known)
            expanded.retainAll(known)
            phase = ItemsPhase.Loaded
        } catch (e: HostException) {
            if (items.isEmpty()) phase = ItemsPhase.Failed
            show(ItemsFeedback(actions.errorText(e), FluxBannerKind.Error))
        }
    }

    /** 引擎推来新条目（`rssItemsChanged` 通知）：提示「N 条新条目」。 */
    fun consumeNotice(notice: HostEvent.Notice) {
        if (notice.name != HostNotice.rssItemsChanged) return
        val payload = RssItemsChangedNotice.fromJson(Json.parseOrNull(notice.json)) ?: return
        if (payload.sourceId != sourceId) return
        val known = items.mapTo(HashSet()) { it.guid }
        val added = payload.items.count { it.guid !in known }
        if (added > 0) show(ItemsFeedback(s(R.string.rssItemsUpdated, "n" to added), FluxBannerKind.Info, jumpsToTop = true))
    }

    /** 新条目横幅被点按：滚到顶部并收起横幅。 */
    fun jumpToTop() {
        scrollTopTick++
        feedback = null
    }

    // ── 条目动作 ────────────────────────────────────────────────────────

    /** 逐条 `itemAction`（批量 = 每条一次调用）；全部结束后写入横幅并重拉。 */
    fun act(guids: Collection<String>, action: RssItemAction) {
        if (!actions.guard()) return
        val known = items.mapTo(HashSet()) { it.guid }
        val todo = guids.distinct().filter { it in known && it !in busy }
        if (todo.isEmpty()) return
        busy.addAll(todo)
        scope.launch {
            var done = 0
            var failed = 0
            var lastError: HostException? = null
            for (guid in todo) {
                try {
                    session().callUnit(HostMethod.daemonRssItemAction, rssItemActionParams(sourceId, action, guid))
                    done++
                    selection.remove(guid)
                } catch (e: HostException) {
                    failed++
                    lastError = e
                } finally {
                    busy.remove(guid)
                }
            }
            val error = lastError
            when {
                done == 1 && failed == 0 && action == RssItemAction.Download ->
                    show(ItemsFeedback(s(R.string.rssTaskCreated), FluxBannerKind.Success))
                failed > 0 -> {
                    val detail = error?.let { actions.errorText(it) } ?: s(R.string.localServiceActionFailed)
                    show(ItemsFeedback(s(R.string.rssBatchResult, "done" to done, "failed" to failed) + " · " + detail, FluxBannerKind.Error))
                }
                else -> show(ItemsFeedback(s(R.string.rssBatchResult, "done" to done, "failed" to failed), FluxBannerKind.Success))
            }
            if (done > 0) reload()
            if (selection.isEmpty() && selecting && done > 0) selecting = false
        }
    }

    fun markAllRead(source: RssSource) {
        controller.markAllRead(
            listOf(source),
            onFailure = { show(ItemsFeedback(actions.errorText(it), FluxBannerKind.Error)) },
            onSuccess = ::reload,
        )
    }

    fun copyLink(item: RssItemDto) = controller.copyText(item.effectiveLink)
}
