package com.fluxdown.core.store

import com.fluxdown.core.model.SelectionKind
import com.fluxdown.core.model.SelectionOutcome
import com.fluxdown.core.model.SelectionRequest
import com.fluxdown.core.model.Task
import com.fluxdown.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 完成 / 失败迁移检测与合批（对应 iOS `NotificationTrackerTests` / `NotificationPlanTests`）。 */
class NotificationTrackerTest {
    private val now = 1_760_000_000L

    private fun task(id: String, status: TaskStatus, error: String = "", createdAt: Long = 0, completedAt: Long = 0) = Task(
        taskId = id, url = "https://example.com/$id", originUrl = "", fileName = "$id.bin", saveDir = "/dl",
        status = status, downloadedBytes = 0, totalBytes = 0, errorMessage = error,
        createdAt = createdAt, completedAt = completedAt,
    )

    private fun selection(id: String) = SelectionRequest(id, "t-$id", SelectionKind.Bt(emptyList()), SelectionOutcome.Cancelled, 0)

    private fun NotificationTracker.feed(
        tasks: List<Task>,
        host: String = "h",
        live: Boolean = true,
        selections: List<SelectionRequest> = emptyList(),
    ) = ingest(host, live, tasks, selections, now)

    private fun event(id: String, kind: NotificationEvent.Kind = NotificationEvent.Kind.Completed) =
        NotificationEvent(kind, id, "$id.bin", if (kind == NotificationEvent.Kind.Failed) "err" else "")

    @Test fun firstLiveSnapshotSeedsSilently() {
        val tracker = NotificationTracker()
        val snapshot = listOf(task("a", TaskStatus.Completed), task("b", TaskStatus.Failed, error = "boom"), task("c", TaskStatus.Downloading))
        assertTrue(tracker.feed(snapshot).isEmpty)
        // 播种后同一状态不会再触发。
        assertTrue(tracker.feed(snapshot).isEmpty)
    }

    @Test fun notConnectedSnapshotDoesNotSeed() {
        val tracker = NotificationTracker()
        // 连接中的空表不能当作基线，否则首个真实快照里的已完成任务会被当成新完成。
        assertTrue(tracker.feed(emptyList(), live = false).isEmpty)
        assertTrue(tracker.feed(listOf(task("a", TaskStatus.Completed))).isEmpty)
    }

    @Test fun completionAndFailureTransitionsNotifyOnce() {
        val tracker = NotificationTracker()
        tracker.feed(listOf(task("a", TaskStatus.Downloading), task("b", TaskStatus.Downloading)))
        val done = listOf(task("a", TaskStatus.Completed), task("b", TaskStatus.Failed, error = "timeout"))
        assertEquals(
            listOf(
                NotificationEvent(NotificationEvent.Kind.Completed, "a", "a.bin", ""),
                NotificationEvent(NotificationEvent.Kind.Failed, "b", "b.bin", "timeout"),
            ),
            tracker.feed(done).events,
        )
        assertTrue(tracker.feed(done).isEmpty)
    }

    @Test fun redownloadNotifiesAgain() {
        val tracker = NotificationTracker()
        tracker.feed(listOf(task("a", TaskStatus.Completed)))
        assertTrue(tracker.feed(listOf(task("a", TaskStatus.Pending))).isEmpty)
        assertEquals(listOf("a"), tracker.feed(listOf(task("a", TaskStatus.Completed))).events.map { it.taskId })
    }

    @Test fun deletedMarkerIsIgnored() {
        val tracker = NotificationTracker()
        tracker.feed(listOf(task("a", TaskStatus.Downloading)))
        assertTrue(tracker.feed(listOf(task("a", TaskStatus.Failed, error = "deleted"))).isEmpty)
    }

    @Test fun hostChangeAndReconnectReseedSilently() {
        val tracker = NotificationTracker()
        tracker.feed(listOf(task("a", TaskStatus.Downloading)), host = "one")
        // 换主机：新主机里已完成的任务不通知。
        assertTrue(tracker.feed(listOf(task("a", TaskStatus.Completed)), host = "two").isEmpty)
        assertTrue(tracker.feed(listOf(task("a", TaskStatus.Completed)), host = "two").isEmpty)
        // 断线重连后整表重新同步：断线期间完成的任务不通知。
        tracker.feed(listOf(task("b", TaskStatus.Downloading)), host = "two")
        assertTrue(tracker.feed(emptyList(), host = "two", live = false).isEmpty)
        assertTrue(tracker.feed(listOf(task("b", TaskStatus.Completed)), host = "two").isEmpty)
    }

    @Test fun unseenTerminalTasksNotifyOnlyWhenFresh() {
        val tracker = NotificationTracker()
        tracker.feed(listOf(task("seed", TaskStatus.Downloading)))
        val delta = tracker.feed(
            listOf(
                task("seed", TaskStatus.Downloading),
                task("quick", TaskStatus.Completed, completedAt = now - 5),
                task("old", TaskStatus.Completed, completedAt = now - 3600),
                task("none", TaskStatus.Completed),
                task("bad", TaskStatus.Failed, error = "x", createdAt = now - 2),
                task("badOld", TaskStatus.Failed, error = "x", createdAt = now - 9999),
            ),
        )
        assertEquals(listOf("quick", "bad"), delta.events.map { it.taskId })
    }

    @Test fun removedTasksAreForgotten() {
        val tracker = NotificationTracker()
        tracker.feed(listOf(task("a", TaskStatus.Completed), task("b", TaskStatus.Downloading)))
        tracker.feed(listOf(task("b", TaskStatus.Downloading)))
        // `a` 被删除后同 id 回来（status 已是 completed）按新任务处理：不新鲜 → 不通知。
        assertTrue(tracker.feed(listOf(task("a", TaskStatus.Completed), task("b", TaskStatus.Downloading))).isEmpty)
    }

    @Test fun selectionRequestsAreTrackedAndResolved() {
        val tracker = NotificationTracker()
        // 播种时已挂起的请求不通知。
        assertTrue(tracker.feed(emptyList(), selections = listOf(selection("old"))).isEmpty)
        val gained = tracker.feed(emptyList(), selections = listOf(selection("old"), selection("new")))
        assertEquals(listOf("new"), gained.newSelections)
        assertTrue(gained.resolvedSelections.isEmpty())
        val resolved = tracker.feed(emptyList(), selections = listOf(selection("new")))
        assertTrue(resolved.newSelections.isEmpty())
        assertEquals(listOf("old"), resolved.resolvedSelections)
    }

    // ── 合批 ──

    @Test fun fewEventsStayIndividual() {
        val items = NotificationBatch.plan(listOf(event("a"), event("b")))
        assertEquals(listOf(NotificationItem.Single(event("a")), NotificationItem.Single(event("b"))), items)
    }

    @Test fun threeCompletionsBecomeOneSummary() {
        val items = NotificationBatch.plan(listOf(event("a"), event("b"), event("c"), event("d")))
        assertEquals(
            listOf(NotificationItem.Summary(NotificationEvent.Kind.Completed, 4, listOf("a.bin", "b.bin", "c.bin"))),
            items,
        )
    }

    @Test fun completionsAndFailuresSummarizeIndependently() {
        val failed = NotificationEvent.Kind.Failed
        val items = NotificationBatch.plan(listOf(event("a"), event("b"), event("c"), event("x", failed), event("y", failed)))
        assertEquals(
            listOf(
                NotificationItem.Summary(NotificationEvent.Kind.Completed, 3, listOf("a.bin", "b.bin", "c.bin")),
                NotificationItem.Single(event("x", failed)),
                NotificationItem.Single(event("y", failed)),
            ),
            items,
        )
    }
}
