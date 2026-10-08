import FluxDomain
import Foundation
import Testing
@testable import FluxDown

@MainActor
struct BackgroundTransfersTests {
    private func task(_ id: String, _ status: TaskStatus, _ done: Int64, _ total: Int64) -> DownloadTask {
        DownloadTask(
            taskId: id,
            url: "https://example.com/\(id)",
            fileName: id,
            status: status,
            downloadedBytes: done,
            totalBytes: total
        )
    }

    // MARK: TransferBatch

    @Test func activeTasksAreByteWeighted() {
        var batch = TransferBatch()
        batch.observe([task("a", .downloading, 50, 100), task("b", .preparing, 0, 300)])
        #expect(batch.progress == TransferProgress(completed: 50, total: 400))
    }

    @Test func completionDoesNotMakeProgressRegress() {
        var batch = TransferBatch()
        batch.observe([task("a", .downloading, 80, 100), task("b", .downloading, 40, 100)])
        let before = batch.progress
        batch.observe([task("a", .completed, 100, 100), task("b", .downloading, 40, 100)])
        let after = batch.progress
        #expect(before == TransferProgress(completed: 120, total: 200))
        #expect(after == TransferProgress(completed: 140, total: 200))
    }

    @Test func pausedFailedAndDeletedMembersLeaveTheBatch() {
        var batch = TransferBatch()
        batch.observe([task("a", .downloading, 10, 100), task("b", .downloading, 20, 200), task("c", .downloading, 30, 300)])
        batch.observe([task("a", .paused, 10, 100), task("b", .failed, 20, 200), task("c", .downloading, 30, 300)])
        #expect(batch.progress == TransferProgress(completed: 30, total: 300))
        // c 从任务表消失（被删除）。
        batch.observe([task("a", .paused, 10, 100)])
        #expect(batch.progress.isIndeterminate)
        #expect(batch.progress.completed == 0)
    }

    @Test func queuedAndForeignCompletedTasksDoNotJoin() {
        var batch = TransferBatch()
        batch.observe([
            task("queued", .pending, 0, 1000),
            task("old", .completed, 500, 500),
            task("run", .downloading, 25, 100),
        ])
        #expect(batch.progress == TransferProgress(completed: 25, total: 100))
    }

    @Test func unknownSizeIsIndeterminateButStillCarriesBytes() {
        var batch = TransferBatch()
        batch.observe([task("stream", .downloading, 500, 0)])
        #expect(batch.progress.isIndeterminate)
        #expect(batch.progress.completed == 500)
        // 已有已知大小的成员时，未知大小的成员不参与比例。
        batch.observe([task("stream", .downloading, 900, 0), task("file", .downloading, 10, 100)])
        #expect(batch.progress == TransferProgress(completed: 10, total: 100))
    }

    @Test func neverReportsCompletionBeforeTheTaskIsFinished() {
        var batch = TransferBatch()
        batch.observe([task("a", .downloading, 100, 100)])
        #expect(batch.progress == TransferProgress(completed: 99, total: 100))
        batch.observe([task("a", .completed, 100, 100)])
        #expect(batch.progress == TransferProgress(completed: 99, total: 100))
    }

    @Test func resetStartsANewRound() {
        var batch = TransferBatch()
        batch.observe([task("a", .downloading, 50, 100)])
        batch.reset()
        #expect(batch.progress.isIndeterminate)
        batch.observe([task("a", .completed, 100, 100)])
        #expect(batch.progress.isIndeterminate)
    }

    // MARK: LocalActivity

    @Test func busyCountsActivePendingAndRetryPending() {
        #expect(!LocalActivity.idle.busy)
        let retryOnly = LocalActivity(stats: RuntimeStats(retryPendingTasks: 1), progress: .indeterminate)
        #expect(retryOnly.busy)
        let mixed = LocalActivity(
            stats: RuntimeStats(activeTasks: 2, pendingTasks: 3, totalDownloadBps: 1_000_000, retryPendingTasks: 1),
            progress: .indeterminate
        )
        #expect(mixed.active == 2)
        #expect(mixed.waiting == 4)
        #expect(mixed.downBps == 1_000_000)
    }

    @Test func textReflectsCountsAndSpeed() {
        let active = LocalActivity(
            stats: RuntimeStats(activeTasks: 2, pendingTasks: 3, totalDownloadBps: 2_500_000),
            progress: .indeterminate
        )
        #expect(active.title.contains("2"))
        #expect(active.subtitle.contains(Format.speedOrZero(2_500_000).description))
        #expect(active.subtitle.contains("3"))

        let waitingOnly = LocalActivity(stats: RuntimeStats(pendingTasks: 5), progress: .indeterminate)
        #expect(waitingOnly.title.contains("5"))
    }
}
