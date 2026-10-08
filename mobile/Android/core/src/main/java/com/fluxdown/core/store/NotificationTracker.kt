package com.fluxdown.core.store

import com.fluxdown.core.model.SelectionRequest
import com.fluxdown.core.model.Task
import com.fluxdown.core.model.TaskStatus

/** 一次需要通知的任务状态迁移。 */
data class NotificationEvent(
    val kind: Kind,
    val taskId: String,
    val fileName: String,
    val errorMessage: String,
) {
    enum class Kind { Completed, Failed }
}

/** 一次 [NotificationTracker.ingest] 的结果。 */
data class NotificationDelta(
    val events: List<NotificationEvent> = emptyList(),
    /** 本轮新出现的选择请求 id。 */
    val newSelections: List<String> = emptyList(),
    /** 本轮消失（已被解决 / 过期）的选择请求 id。 */
    val resolvedSelections: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = events.isEmpty() && newSelections.isEmpty() && resolvedSelections.isEmpty()
}

/**
 * 逐主机跟踪任务状态，产出「已完成 / 已失败」迁移（纯逻辑，对应 iOS `NotificationTracker`）。
 *
 * 规则：
 * - 每台主机的**首个已连上（live）快照只播种、不通知**：附着时已完成 / 已失败的任务永远不会被当作新事件；
 * - 主机变更、断线 / 重连（`isLive == false` 期间）都会重置：重新同步后的整表同样静默播种；
 * - 播种后新出现、且一出现就处于终态的任务（体积极小的任务在一个发布帧内完成）仅当 `completedAt` / `createdAt`
 *   落在 [FRESH_WINDOW] 内才通知，否则视为同步带来的历史任务；
 * - `errorMessage == "deleted"` 的失败是删除任务的标记，不通知；
 * - 同一状态不重复通知；任务重新下载后再次完成会再通知。
 *
 * 非线程安全：调用方串行使用。
 */
class NotificationTracker {
    private var hostId: String? = null
    private val statuses = HashMap<String, TaskStatus>()
    private var seenSelections: Set<String> = emptySet()
    private var isSeeded = false

    fun reset() {
        hostId = null
        statuses.clear()
        seenSelections = emptySet()
        isSeeded = false
    }

    /** [now] = Unix 秒。 */
    fun ingest(
        hostId: String,
        isLive: Boolean,
        tasks: List<Task>,
        selections: List<SelectionRequest>,
        now: Long,
    ): NotificationDelta {
        if (this.hostId != hostId) {
            reset()
            this.hostId = hostId
        }
        if (!isLive) {
            // 未连上 / 重连中：丢弃基线，恢复后重新静默播种。
            statuses.clear()
            seenSelections = emptySet()
            isSeeded = false
            return NotificationDelta()
        }
        if (!isSeeded) {
            for (task in tasks) statuses[task.taskId] = task.status
            seenSelections = selections.mapTo(HashSet()) { it.requestId }
            isSeeded = true
            return NotificationDelta()
        }

        val events = ArrayList<NotificationEvent>()
        for (task in tasks) {
            val previous = statuses[task.taskId]
            if (previous == task.status) continue
            statuses[task.taskId] = task.status
            when (task.status) {
                TaskStatus.Completed -> {
                    if (previous == null && !isFresh(task.completedAt, now)) continue
                    events += NotificationEvent(NotificationEvent.Kind.Completed, task.taskId, task.fileName, "")
                }
                TaskStatus.Failed -> {
                    if (task.errorMessage == DELETED_MARKER) continue
                    if (previous == null && !isFresh(task.createdAt, now)) continue
                    events += NotificationEvent(NotificationEvent.Kind.Failed, task.taskId, task.fileName, task.errorMessage)
                }
                else -> Unit
            }
        }
        if (statuses.size != tasks.size) {
            // 有任务被删除：只在数量不符时重建索引，常态路径不分配。
            val alive = tasks.mapTo(HashSet()) { it.taskId }
            statuses.keys.retainAll(alive)
        }

        val current = selections.mapTo(LinkedHashSet()) { it.requestId }
        var newSelections: List<String> = emptyList()
        var resolved: List<String> = emptyList()
        if (current != seenSelections) {
            newSelections = selections.map { it.requestId }.filter { it !in seenSelections }
            resolved = (seenSelections - current).sorted()
            seenSelections = current
        }
        return NotificationDelta(events, newSelections, resolved)
    }

    private fun isFresh(unixSeconds: Long, now: Long): Boolean =
        unixSeconds > 0 && now - unixSeconds <= FRESH_WINDOW && now - unixSeconds >= -FRESH_WINDOW

    companion object {
        /** 新出现即终态任务被视为「刚发生」的时间窗（秒）。 */
        const val FRESH_WINDOW = 30L

        /** 删除任务时引擎写入的失败标记。 */
        const val DELETED_MARKER = "deleted"
    }
}

/** 一批事件合并后的通知项。 */
sealed interface NotificationItem {
    data class Single(val event: NotificationEvent) : NotificationItem
    data class Summary(val kind: NotificationEvent.Kind, val count: Int, val names: List<String>) : NotificationItem
}

/** 去抖合批：窗口内同类事件 ≥ [SUMMARY_THRESHOLD] 条合并为一条汇总，否则逐条通知。 */
object NotificationBatch {
    /** 去抖窗口（自批内第一条事件起算，毫秒）。 */
    const val WINDOW_MS = 2_000L
    const val SUMMARY_THRESHOLD = 3

    /** 汇总里最多列出的文件名数。 */
    const val SUMMARY_NAMES = 3

    fun plan(events: List<NotificationEvent>): List<NotificationItem> {
        val items = ArrayList<NotificationItem>()
        for (kind in NotificationEvent.Kind.entries) {
            val group = events.filter { it.kind == kind }
            if (group.size >= SUMMARY_THRESHOLD) {
                items += NotificationItem.Summary(kind, group.size, group.take(SUMMARY_NAMES).map { it.fileName })
            } else {
                group.mapTo(items) { NotificationItem.Single(it) }
            }
        }
        return items
    }
}
