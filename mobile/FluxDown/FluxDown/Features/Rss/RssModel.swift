import FluxDomain
import FluxUI
import Foundation
import Observation
import UIKit

/// 订阅列表排序（R1 ⋯ 菜单）。`manual` = 主机顺序（`position`）。
nonisolated enum RssFeedSort: String, CaseIterable, Identifiable {
    case manual, name, unread, lastFetch

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .manual: "mobileRssSortManual"
        case .name: "mobileRssSortName"
        case .unread: "mobileRssSortUnread"
        case .lastFetch: "mobileRssSortLastFetch"
        }
    }
}

/// R3 呈现目标。
nonisolated enum RssEditorTarget: Identifiable, Hashable {
    case create
    case edit(sourceId: String)

    var id: String {
        switch self {
        case .create: "create"
        case let .edit(sourceId): "edit:" + sourceId
        }
    }
}

/// 订阅删除确认的触发位置：确认 alert 由触发视图挂载，且同一订阅可同时出现在列表行与条目页菜单（iPad 两栏）。
nonisolated enum RssDeleteOrigin {
    /// R1 订阅列表行（滑动 / 上下文菜单）。
    case feedList
    /// R2 条目页工具栏「更多」菜单。
    case itemsMenu
}

/// 待确认的订阅删除。
nonisolated struct RssDeleteRequest {
    let source: RssSource
    let origin: RssDeleteOrigin
}

/// 订阅动作的唯一分发点（R1 行 / 菜单 / R2 工具栏共用）：只读拦截 + Toast + 抓取中状态。
@MainActor
@Observable
final class RssModel {
    /// 当前选中的订阅（compact = 推入的条目页；regular = 右栏）。
    var selectedId: String?
    /// 正在抓取的订阅（行内转圈 / R2 工具栏转圈共用）。
    private(set) var busy: Set<String> = []
    /// 正在标记已读的订阅。
    private(set) var markingRead: Set<String> = []
    var editor: RssEditorTarget?
    /// 待确认删除的订阅（alert，由 `origin` 对应的触发视图挂载，见 `rssDeleteConfirmation`）。
    var pendingDelete: RssDeleteRequest?
    var failingOnly = false
    var unreadOnly = false
    var sort: RssFeedSort = .manual
    var query = ""
    /// R1 页首短暂横幅（新条目到达）；可关闭。
    var feedback: String?

    @ObservationIgnored private unowned let container: AppContainer
    @ObservationIgnored private unowned let actions: TaskActions
    @ObservationIgnored private var lastUnread: [String: Int32]?

    /// 同时抓取的订阅数上限（R1：「全部抓取」≤ 4 并发）。
    private static let refreshParallelism = 4
    /// 订阅数达到该值才出现搜索栏（R1）。
    static let searchThreshold = 8

    init(container: AppContainer, actions: TaskActions) {
        self.container = container
        self.actions = actions
    }

    // MARK: 派生

    /// 搜索 / 仅未读 / 仅失败 / 排序后的列表。
    func visibleSources(_ sources: [RssSource]) -> [RssSource] {
        var list = sources
        if failingOnly { list = list.filter { $0.failCount > 0 } }
        if unreadOnly { list = list.filter { $0.unreadCount > 0 } }
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if !needle.isEmpty {
            list = list.filter { $0.name.lowercased().contains(needle) || $0.url.lowercased().contains(needle) }
        }
        switch sort {
        case .manual: break
        case .name:
            list.sort { RssFormat.title(of: $0).localizedStandardCompare(RssFormat.title(of: $1)) == .orderedAscending }
        case .unread:
            list = list.enumerated().sorted { lhs, rhs in
                lhs.element.unreadCount != rhs.element.unreadCount
                    ? lhs.element.unreadCount > rhs.element.unreadCount
                    : lhs.offset < rhs.offset
            }.map(\.element)
        case .lastFetch:
            list = list.enumerated().sorted { lhs, rhs in
                lhs.element.lastSuccessAt != rhs.element.lastSuccessAt
                    ? lhs.element.lastSuccessAt > rhs.element.lastSuccessAt
                    : lhs.offset < rhs.offset
            }.map(\.element)
        }
        return list
    }

    // MARK: 新条目提示

    /// 比较未读数：任一订阅的未读增加即给出「新增 N 条订阅内容」横幅（首次调用只记录基线）。
    func trackUnread(_ sources: [RssSource]) {
        let current = sources.reduce(into: [String: Int32]()) { $0[$1.sourceId] = $1.unreadCount }
        defer { lastUnread = current }
        guard let previous = lastUnread else { return }
        var added = 0
        for (id, unread) in current {
            guard let before = previous[id] else { continue }
            added += max(0, Int(unread) - Int(before))
        }
        if added > 0 { feedback = L("rssItemsUpdated", ["n": added]) }
    }

    // MARK: 抓取

    /// 单个订阅立即抓取：成功 Toast，失败 Toast 原因。
    func refresh(_ source: RssSource) {
        guard actions.guardWritable(), !busy.contains(source.sourceId) else { return }
        Task {
            if await fetch(source.sourceId, reportsErrors: true) {
                container.toasts.show(
                    text: L("mobileRssRefreshed", ["name": RssFormat.title(of: source)]),
                    tone: .success,
                    systemImage: FluxSymbol.done
                )
            }
        }
    }

    /// 全部已启用的订阅（≤ 4 并发）；汇总 Toast。单个失败原因显示在各行的状态行里，不逐条弹错误。
    func refreshAll(_ sources: [RssSource]) async {
        guard actions.guardWritable() else { return }
        let targets = sources.filter { $0.enabled && !busy.contains($0.sourceId) }.map(\.sourceId)
        if targets.isEmpty {
            container.toasts.show(text: L("mobileRssNothingToRefresh"), tone: .info, systemImage: "info.circle")
            return
        }
        var succeeded = 0
        var failed = 0
        var next = 0
        await withTaskGroup(of: Bool.self) { group in
            func launch() {
                guard next < targets.count else { return }
                let id = targets[next]
                next += 1
                group.addTask { await self.fetch(id, reportsErrors: false) }
            }
            for _ in 0..<Self.refreshParallelism { launch() }
            while let ok = await group.next() {
                if ok { succeeded += 1 } else { failed += 1 }
                launch()
            }
        }
        container.toasts.show(
            text: L("mobileRssRefreshSummary", ["ok": succeeded, "failed": failed]),
            tone: failed == 0 ? .success : .warning,
            systemImage: failed == 0 ? FluxSymbol.done : "exclamationmark.triangle"
        )
    }

    /// 抓取 `sourceId`；@return true = 成功。
    private func fetch(_ sourceId: String, reportsErrors: Bool) async -> Bool {
        busy.insert(sourceId)
        defer { busy.remove(sourceId) }
        let session = container.session
        do throws(HostError) {
            try await session.refreshRssSource(sourceId)
            return true
        } catch {
            if reportsErrors {
                container.toasts.show(text: ErrorText.describe(error), tone: .error, systemImage: "exclamationmark.circle")
            }
            return false
        }
    }

    // MARK: 启停 / 已读 / 删除

    func toggle(_ source: RssSource) {
        let enable = !source.enabled
        actions.run(onSuccess: { [toasts = container.toasts] in
            toasts.show(text: L(enable ? "mobileRssEnabledToast" : "mobileRssDisabledToast"), tone: .info, systemImage: enable ? "play.circle" : "pause.circle")
        }) { session throws(HostError) in
            try await session.setRssSourceEnabled(source.sourceId, enabled: enable)
        }
    }

    /// 全部标记已读（`itemAction{readAll}`，忽略 guid）。
    func markAllRead(_ source: RssSource) {
        markAllRead([source])
    }

    func markAllRead(_ sources: [RssSource]) {
        guard actions.guardWritable() else { return }
        let ids = sources.map(\.sourceId).filter { !markingRead.contains($0) }
        guard !ids.isEmpty else { return }
        markingRead.formUnion(ids)
        let session = container.session
        Task {
            var failure: HostError?
            for id in ids {
                do throws(HostError) {
                    try await session.callVoid(
                        HostMethod.daemonRssItemAction,
                        params: RssItemActionParams(sourceId: id, action: .readAll)
                    )
                } catch {
                    failure = error
                }
                markingRead.remove(id)
            }
            if let failure {
                container.toasts.show(text: ErrorText.describe(failure), tone: .error, systemImage: "exclamationmark.circle")
            } else {
                container.toasts.show(text: L("mobileRssMarkedReadToast"), tone: .success, systemImage: FluxSymbol.done)
            }
        }
    }

    func requestDelete(_ source: RssSource, from origin: RssDeleteOrigin) {
        guard actions.guardWritable() else { return }
        pendingDelete = RssDeleteRequest(source: source, origin: origin)
    }

    func confirmDelete(_ source: RssSource) {
        actions.run(onSuccess: { [self] in
            if selectedId == source.sourceId { selectedId = nil }
            container.toasts.show(text: L("mobileRssDeletedToast"), tone: .info, systemImage: FluxSymbol.delete)
        }) { session throws(HostError) in
            try await session.callVoid(HostMethod.daemonRssDeleteSource, params: RssSourceIdParams(sourceId: source.sourceId))
        }
    }

    func copyLink(_ source: RssSource) {
        UIPasteboard.general.string = source.url
        container.toasts.show(text: L("mobileRssCopied"), tone: .success, systemImage: FluxSymbol.copy)
    }

    // MARK: 编辑器

    func openEditor(_ target: RssEditorTarget) {
        guard actions.guardWritable() else { return }
        editor = target
    }
}
