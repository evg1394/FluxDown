import FluxDomain
import SwiftUI

/// 「下载」标签根页。compact：`NavigationStack(path: router.downloadsPath)`，详情推入（zoom 转场）；
/// regular（iPad / 宽窗口）：`NavigationSplitView` 列表栏 | 详情栏，由 `router.selectedTaskId` 驱动（02-downloads §20）。
/// 两种布局共用同一个 `DownloadsModel`（筛选 / 多选 / 派生列表）。
struct DownloadsScreen: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var model = DownloadsModel()

    var body: some View {
        Group {
            if sizeClass == .regular {
                DownloadsSplitLayout(model: model)
            } else {
                DownloadsStackLayout(model: model)
            }
        }
        .onAppear { model.bind(store: container.store, prefs: container.viewPrefs) }
    }
}

private struct DownloadsStackLayout: View {
    let model: DownloadsModel
    @Environment(AppContainer.self) private var container
    @Namespace private var zoom

    var body: some View {
        @Bindable var router = container.router
        NavigationStack(path: $router.downloadsPath) {
            DownloadsListScreen(
                model: model,
                zoom: zoom,
                currentDetailId: nil,
                onOpen: { id in
                    router.selectedTaskId = id
                    router.downloadsPath.append(.task(id))
                },
                onOpenGroup: { id in
                    router.downloadsPath.append(.group(id))
                }
            )
            .navigationDestination(for: DownloadsRoute.self) { route in
                switch route {
                case let .task(id):
                    // zoom 只在“列表行 → 详情”时有源；从组详情成员推入的任务用默认推入转场。
                    if router.downloadsPath.first == .task(id) {
                        TaskDetailScreen(taskId: id)
                            .navigationTransition(.zoom(sourceID: id, in: zoom))
                    } else {
                        TaskDetailScreen(taskId: id)
                    }
                case let .group(id):
                    GroupDetailScreen(groupId: id)
                }
            }
        }
        .environment(\.downloadsNavigation, DownloadsNavigation(
            open: { route in
                if case let .task(id) = route { router.selectedTaskId = id }
                router.downloadsPath.append(route)
            },
            closeGroup: { id in
                if router.downloadsPath.last == .group(id) { router.downloadsPath.removeLast() }
            },
            closeTask: { id in
                if router.downloadsPath.last == .task(id) { router.downloadsPath.removeLast() }
                if router.selectedTaskId == id { router.selectedTaskId = nil }
            }
        ))
    }
}

private struct DownloadsSplitLayout: View {
    let model: DownloadsModel
    @Environment(AppContainer.self) private var container
    /// 右栏栈：组详情内的成员行在此推入任务详情。
    @State private var detailPath: [DownloadsRoute] = []

    var body: some View {
        let router = container.router
        NavigationSplitView {
            DownloadsListScreen(
                model: model,
                zoom: nil,
                currentDetailId: router.selectedTaskId,
                onOpen: { id in
                    model.detailGroupId = nil
                    router.selectedTaskId = id
                },
                onOpenGroup: { id in
                    router.selectedTaskId = nil
                    model.detailGroupId = id
                }
            )
            .navigationSplitViewColumnWidth(min: 340, ideal: 408, max: 520)
        } detail: {
            NavigationStack(path: $detailPath) {
                detailRoot(router: router)
                    .navigationDestination(for: DownloadsRoute.self) { route in
                        switch route {
                        case let .task(id):
                            TaskDetailScreen(taskId: id)
                        case let .group(id):
                            GroupDetailScreen(groupId: id)
                        }
                    }
            }
            .environment(\.downloadsNavigation, DownloadsNavigation(
                open: { route in detailPath.append(route) },
                closeGroup: { id in
                    if model.detailGroupId == id { model.detailGroupId = nil }
                },
                closeTask: { id in
                    if detailPath.last == .task(id) { detailPath.removeLast() }
                    if detailPath.isEmpty, router.selectedTaskId == id { router.selectedTaskId = nil }
                }
            ))
        }
        // 其它入口（全局搜索 / 通知）选中任务 → 右栏让位给任务详情。
        .onChange(of: router.selectedTaskId) { _, id in
            if id != nil { model.detailGroupId = nil }
            detailPath = []
        }
        .onChange(of: model.detailGroupId) { detailPath = [] }
    }

    @ViewBuilder
    private func detailRoot(router: AppRouter) -> some View {
        if let groupId = model.detailGroupId {
            GroupDetailScreen(groupId: groupId)
        } else if let id = router.selectedTaskId {
            TaskDetailScreen(taskId: id).id(id)
        } else {
            ContentUnavailableView {
                Label(L("mobileTaskDetail"), systemImage: "info.circle")
            } description: {
                Text(L("mobileSelectTaskHint"))
            }
        }
    }
}
