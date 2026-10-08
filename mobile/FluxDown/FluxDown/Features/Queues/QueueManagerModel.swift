import FluxDomain
import FluxUI
import SwiftUI

/// D7 队列列表的写操作（启停 / 删除）。列表本身读 `container.store.state.queues`（`queuesChanged` 实时），
/// 这里不做乐观更新：开关只反映主机回流的 `isRunning`。
@MainActor
@Observable
final class QueueManagerModel {
    /// 正在执行写操作的队列（开关 / 删除期间置灰）。
    private(set) var busy: Set<String> = []
    /// 最近一次失败文案（Sheet 内以横幅呈现；下一次写操作开始时清除）。
    var errorText: String?
    /// 待确认删除的队列。
    var pendingDelete: TaskQueue?

    /// `daemon.queue.start/stop`。
    func setRunning(_ running: Bool, queue: TaskQueue, container: AppContainer) {
        guard writable(container), busy.insert(queue.queueId).inserted else { return }
        errorText = nil
        let session = container.session
        let queueId = queue.queueId
        let name = queue.displayName
        Task {
            do throws(HostError) {
                try await session.callVoid(
                    running ? HostMethod.daemonQueueStart : HostMethod.daemonQueueStop,
                    params: QueueIdParams(queueId: queueId)
                )
                container.toasts.show(
                    text: QueueText.runningToast(running, name: name),
                    tone: .info,
                    systemImage: running ? FluxSymbol.resume : FluxSymbol.pause
                )
            } catch {
                errorText = ErrorText.describe(error)
                FluxHaptic.error.play()
            }
            busy.remove(queueId)
        }
    }

    /// `daemon.queue.delete`（内置队列不可删，调用方已过滤；此处再兜底）。
    func delete(_ queue: TaskQueue, container: AppContainer) {
        pendingDelete = nil
        guard !QueueIds.isBuiltin(queue.queueId), writable(container), busy.insert(queue.queueId).inserted else { return }
        errorText = nil
        let session = container.session
        let queueId = queue.queueId
        let name = queue.displayName
        Task {
            do throws(HostError) {
                try await session.callVoid(HostMethod.daemonQueueDelete, params: QueueIdParams(queueId: queueId))
                container.toasts.show(text: L("queueDeletedToast", ["name": name]), tone: .success, systemImage: FluxSymbol.delete)
            } catch {
                errorText = ErrorText.describe(error)
                FluxHaptic.error.play()
            }
            busy.remove(queueId)
        }
    }

    /// 只读（断连宽限后）拒绝写操作。
    private func writable(_ container: AppContainer) -> Bool {
        guard container.store.state.isReadOnly else { return true }
        errorText = L("localServiceDisconnected")
        return false
    }
}
