import FluxDomain
import Foundation

/// 本机引擎的聚合活动量（同 Android `LocalActivity`，与当前选中的主机无关）：
/// 驱动系统后台续跑任务的存续与文案。来源见 `LocalActivityMonitor`。
nonisolated struct LocalActivity: Equatable, Sendable {
    var active = 0
    var pending = 0
    var retryPending = 0
    var downBps: Int64 = 0
    var upBps: Int64 = 0
    /// 本轮活动的字节加权总进度（系统进度条）。
    var progress = TransferProgress.indeterminate

    static let idle = LocalActivity()

    init() {}

    init(stats: RuntimeStats, progress: TransferProgress) {
        active = Int(stats.activeTasks)
        pending = Int(stats.pendingTasks)
        retryPending = Int(stats.retryPendingTasks)
        downBps = stats.totalDownloadBps
        upBps = stats.totalUploadBps
        self.progress = progress
    }

    /// 活跃 + 排队 + 待重试都算“还在工作”：进程必须保活。
    var busy: Bool { active + pending + retryPending > 0 }

    /// 排队 + 待重试。
    var waiting: Int { pending + retryPending }

    /// 系统进度条标题：有活跃任务 =「正在下载 N 个任务」；仅排队 / 待重试 =「N 个任务等待中」。
    var title: String {
        if active > 0 {
            return L(active == 1 ? "fgServiceActiveTitleOne" : "fgServiceActiveTitleOther", ["count": active])
        }
        return L("mobileDlNotifWaiting", ["n": waiting])
    }

    /// 副标题：「速度 {speed}」（+ 「 · N 个任务等待中」）；无活跃任务时为待命文案。
    var subtitle: String {
        guard active > 0 else { return L("fgServiceIdleText") }
        var parts = [L("fgServiceActiveText", ["speed": Format.speedOrZero(downBps)])]
        if waiting > 0 { parts.append(L("mobileDlNotifWaiting", ["n": waiting])) }
        return parts.joined(separator: " · ")
    }
}

/// 汇报给 `Progress` 的进度：字节计数；`total < 0` = 不确定（本轮没有任何已知大小的任务）。
nonisolated struct TransferProgress: Equatable, Sendable {
    var completed: Int64
    var total: Int64

    var isIndeterminate: Bool { total < 0 }

    /// 不确定进度仍携带已下载字节数（`completed`），让系统看到“还在前进”而不判定停滞。
    static let indeterminate = TransferProgress(completed: 0, total: -1)
}

/// 一轮“忙碌期”内参与总进度的任务集合：保证系统进度条不因个别任务完成而回退。
///
/// 规则（由 `HostState.tasks` 逐次喂入）：
/// - 下载中 / 准备中 → 加入并更新；已完成的成员保留（计入 100%）；
/// - 暂停 / 失败 / 未知状态 / 已删除 → 移出（用户停止或不再相关）；
/// - 排队中的任务开始下载后才加入（排队任务可能长期不跑，不应拉高总量）。
/// 忙碌期结束（空闲）时 `reset()`。
nonisolated struct TransferBatch {
    private struct Member {
        var downloaded: Int64
        var total: Int64
    }

    private var members: [String: Member] = [:]

    mutating func reset() {
        guard !members.isEmpty else { return }
        members.removeAll(keepingCapacity: true)
    }

    mutating func observe(_ tasks: [DownloadTask]) {
        var kept = 0
        for task in tasks {
            if task.status.isActive {
                members[task.taskId] = Member(downloaded: task.downloadedBytes, total: task.totalBytes)
                kept += 1
            } else if task.status == .completed, members[task.taskId] != nil {
                // 完成：已下载 = 总量（总量未知时取已下载）。
                let total = max(task.totalBytes, task.downloadedBytes)
                members[task.taskId] = Member(downloaded: total, total: total)
                kept += 1
            } else {
                members.removeValue(forKey: task.taskId)
            }
        }
        // 已从任务表消失（删除）的成员：仅在计数不符时才做一次 O(m·n) 清理。
        if kept < members.count {
            let ids = Set(tasks.lazy.map(\.taskId))
            members = members.filter { ids.contains($0.key) }
        }
    }

    /// 字节加权总进度：只统计已知大小（`total > 0`）的成员；`completed` 至多 `total − 1`
    /// （只有当前任务由我们显式 `setTaskCompleted` 才算完成，避免系统提前视为已完成）。
    var progress: TransferProgress {
        var completed: Int64 = 0
        var total: Int64 = 0
        var downloaded: Int64 = 0
        for member in members.values {
            downloaded += member.downloaded
            guard member.total > 0 else { continue }
            completed += min(member.downloaded, member.total)
            total += member.total
        }
        guard total > 0 else { return TransferProgress(completed: downloaded, total: -1) }
        return TransferProgress(completed: min(completed, total - 1), total: total)
    }
}
