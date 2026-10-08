import FluxDomain
import FluxUI
import Foundation
import Observation
import UIKit

/// R2 条目流状态（同 Web `useRssItems`）。视图以 `.id(sourceId)` 挂载：切换订阅即整体重置
/// （条目、选择、忙碌集合、横幅），晚到的旧订阅结果随视图卸载被丢弃。
///
/// 条目来源 `daemon.rss.getItems`；`daemon.rssItemRevisions[sourceId]` 变化、手动重试、动作成功后重拉。
/// 拉取期间保留旧条目（避免闪烁）。
@MainActor
@Observable
final class RssItemsModel {
    nonisolated enum Phase: Equatable {
        /// 首次拉取中（尚无条目）。
        case loading
        case loaded
        case failed
    }

    /// 页内横幅（批量结果 / 新条目提示 / 失败）。
    nonisolated struct Feedback: Equatable {
        var text: String
        var tone: BannerTone
        var systemImage: String
        /// 点按横幅滚到列表顶部（新条目提示）。
        var jumpsToTop = false
    }

    let sourceId: String
    private(set) var items: [RssItemDto] = []
    private(set) var phase: Phase = .loading
    var query = ""
    var oldestFirst = false
    /// 选择模式下已选 guid。
    var selection: Set<String> = []
    var isSelecting = false
    /// 正在处理的 guid（行内转圈 / 「准备中…」）。
    private(set) var busy: Set<String> = []
    private(set) var readBusy = false
    var feedback: Feedback?
    /// 递增即触发重拉（视图 `.task(id:)` 的一部分）。
    private(set) var reloadTick = 0
    /// 行内展开详情的 guid。
    var expanded: Set<String> = []
    /// 递增即让列表滚到顶部（新条目横幅被点按）。
    private(set) var scrollTopTick = 0

    @ObservationIgnored private unowned let container: AppContainer
    @ObservationIgnored private unowned let actions: TaskActions
    @ObservationIgnored private var lastNoticeId = 0

    init(sourceId: String, container: AppContainer, actions: TaskActions) {
        self.sourceId = sourceId
        self.container = container
        self.actions = actions
        lastNoticeId = container.store.notices.last?.id ?? 0
    }

    // MARK: 派生

    func visible() -> [RssItemDto] {
        RssFormat.visible(items, query: query, oldestFirst: oldestFirst)
    }

    /// 已建任务条目引用的任务 ID 集合。
    var linkedTaskIds: Set<String> {
        Set(items.lazy.filter { $0.state == .downloaded && !$0.taskId.isEmpty }.map(\.taskId))
    }

    func clearSelection() {
        selection = []
    }

    func toggleSelecting() {
        isSelecting.toggle()
        if !isSelecting { selection = [] }
    }

    /// 只作用于当前搜索 / 排序下**可见**条目。
    func setVisibleSelected(_ visible: [RssItemDto], selected: Bool) {
        let ids = visible.map(\.guid)
        if selected { selection.formUnion(ids) } else { selection.subtract(ids) }
    }

    func reload() {
        reloadTick += 1
    }

    func dismissFeedback() {
        feedback = nil
    }

    // MARK: 拉取

    func load() async {
        let session = container.session
        if items.isEmpty { phase = .loading }
        do throws(HostError) {
            let list: [RssItemDto] = try await session.call(
                HostMethod.daemonRssGetItems,
                params: RssSourceIdParams(sourceId: sourceId)
            )
            guard !Task.isCancelled else { return }
            items = list
            let known = Set(list.map(\.guid))
            selection.formIntersection(known)
            expanded.formIntersection(known)
            phase = .loaded
        } catch {
            guard !Task.isCancelled else { return }
            if items.isEmpty { phase = .failed }
            feedback = Feedback(text: ErrorText.describe(error), tone: .error, systemImage: "exclamationmark.circle")
        }
    }

    /// 引擎推来新条目（`rssItemsChanged` 通知）：提示「N 条新条目」。
    func consumeNotices(_ notices: [HostNotice]) {
        for notice in notices where notice.id > lastNoticeId {
            lastNoticeId = notice.id
            guard notice.name == HostNoticeName.rssItemsChanged,
                  let payload = notice.decode(RssItemsChangedNotice.self),
                  payload.sourceId == sourceId
            else { continue }
            let known = Set(items.map(\.guid))
            let added = payload.items.filter { !known.contains($0.guid) }.count
            if added > 0 {
                feedback = Feedback(text: L("rssItemsUpdated", ["n": added]), tone: .info, systemImage: "tray.and.arrow.down", jumpsToTop: true)
            }
        }
    }

    /// 新条目横幅被点按：滚到顶部并收起横幅。
    func jumpToTop() {
        scrollTopTick += 1
        feedback = nil
    }

    // MARK: 条目动作

    /// 逐条 `itemAction`（批量 = 每条一次调用）；全部结束后写入横幅并重拉。
    func act(_ guids: [String], action: RssItemAction) {
        guard actions.guardWritable() else { return }
        let known = Set(items.map(\.guid))
        let todo = guids.filter { known.contains($0) && !busy.contains($0) }
        guard !todo.isEmpty else { return }
        busy.formUnion(todo)
        let session = container.session
        let sourceId = sourceId
        Task {
            var done = 0
            var failed = 0
            var lastError: HostError?
            for guid in todo {
                do throws(HostError) {
                    try await session.callVoid(
                        HostMethod.daemonRssItemAction,
                        params: RssItemActionParams(sourceId: sourceId, guid: guid, action: action)
                    )
                    done += 1
                    selection.remove(guid)
                } catch {
                    failed += 1
                    lastError = error
                }
                busy.remove(guid)
            }
            if done == 1, failed == 0, action == .download {
                feedback = Feedback(text: L("rssTaskCreated"), tone: .success, systemImage: "checkmark.circle")
            } else if failed > 0 {
                let detail = lastError.map { ErrorText.describe($0) } ?? L("localServiceActionFailed")
                feedback = Feedback(
                    text: L("rssBatchResult", ["done": done, "failed": failed]) + " · " + detail,
                    tone: .error,
                    systemImage: "exclamationmark.triangle"
                )
            } else {
                feedback = Feedback(text: L("rssBatchResult", ["done": done, "failed": failed]), tone: .success, systemImage: "checkmark.circle")
            }
            if done > 0 { reload() }
            if selection.isEmpty, isSelecting, done > 0 { isSelecting = false }
        }
    }

    func markAllRead() {
        guard actions.guardWritable(), !readBusy else { return }
        readBusy = true
        let session = container.session
        let sourceId = sourceId
        Task {
            defer { readBusy = false }
            do throws(HostError) {
                try await session.callVoid(
                    HostMethod.daemonRssItemAction,
                    params: RssItemActionParams(sourceId: sourceId, action: .readAll)
                )
                container.toasts.show(text: L("mobileRssMarkedReadToast"), tone: .success, systemImage: FluxSymbol.done)
                reload()
            } catch {
                feedback = Feedback(text: ErrorText.describe(error), tone: .error, systemImage: "exclamationmark.circle")
            }
        }
    }

    func copyLink(_ item: RssItemDto) {
        UIPasteboard.general.string = item.effectiveLink
        container.toasts.show(text: L("mobileRssCopied"), tone: .success, systemImage: FluxSymbol.copy)
    }
}
