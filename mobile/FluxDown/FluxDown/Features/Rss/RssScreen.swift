import FluxDomain
import FluxUI
import SwiftUI

/// 「订阅」标签根页。
/// - compact：`NavigationStack`，订阅列表（R1）→ 推入条目流（R2）；
/// - regular（iPad / 宽窗口）：`NavigationSplitView` 订阅 | 条目 两栏。
/// 订阅属于主机（本机或 `--server`），动作经 `daemon.rss.*`；`daemon.*` 能力恒可达，不做门控。内容层全部是系统 `List`，不上玻璃。
struct RssScreen: View {
    @Environment(AppContainer.self) private var container
    @Environment(TaskActions.self) private var actions

    var body: some View {
        RssContent(container: container, actions: actions)
    }
}

extension View {
    /// 订阅删除确认框挂在触发它的视图上：
    /// 仅当待删请求指向 `source` 且来自 `origin` 时呈现——列表行与条目页菜单可同时存在，各自只响应自己发起的请求。
    func rssDeleteConfirmation(_ rss: RssModel, source: RssSource, origin: RssDeleteOrigin) -> some View {
        alert(
            L("rssDeleteSource"),
            isPresented: Binding(
                get: { rss.pendingDelete.map { $0.origin == origin && $0.source.sourceId == source.sourceId } ?? false },
                set: { if !$0 { rss.pendingDelete = nil } }
            ),
            presenting: rss.pendingDelete
        ) { request in
            Button(L("rssDeleteSource"), role: .destructive) { rss.confirmDelete(request.source) }
            Button(L("cancel"), role: .cancel) {}
        } message: { request in
            Text(L("rssDeleteConfirmDesc", ["name": RssFormat.title(of: request.source)]))
        }
    }
}

private struct RssContent: View {
    @Environment(AppContainer.self) private var container
    @Environment(TaskActions.self) private var actions
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var rss: RssModel
    /// compact 推入的订阅 id 栈。
    @State private var path: [String] = []

    init(container: AppContainer, actions: TaskActions) {
        _rss = State(initialValue: RssModel(container: container, actions: actions))
    }

    var body: some View {
        @Bindable var rss = rss
        let sources = container.store.state.rssSources
        Group {
            if sizeClass == .regular {
                splitLayout(sources: sources)
            } else {
                stackLayout
            }
        }
        .sheet(item: $rss.editor) { target in
            RssEditorSheet(target: target, container: container, rss: rss)
        }
        // 全局搜索「新建订阅」：标签被选中且搜索 Sheet 收起后打开创建编辑器。
        .task(id: container.router.pendingIntent) {
            guard container.router.pendingIntent == .newRssSource, await container.router.claim(.newRssSource) else { return }
            rss.openEditor(.create)
        }
        .onChange(of: sources.map { "\($0.sourceId):\($0.unreadCount)" }, initial: true) {
            rss.trackUnread(sources)
        }
        .onChange(of: sources.map(\.sourceId)) { _, ids in
            path.removeAll { !ids.contains($0) }
            if let selected = rss.selectedId, !ids.contains(selected) { rss.selectedId = nil }
        }
    }

    private var stackLayout: some View {
        NavigationStack(path: $path) {
            RssFeedsList(rss: rss, mode: .stack)
                .navigationDestination(for: String.self) { sourceId in
                    RssItemsScreen(sourceId: sourceId, container: container, actions: actions, rss: rss)
                        .id(sourceId)
                }
        }
    }

    private func splitLayout(sources: [RssSource]) -> some View {
        NavigationSplitView {
            RssFeedsList(rss: rss, mode: .split)
                .navigationSplitViewColumnWidth(min: 320, ideal: 380, max: 480)
        } detail: {
            NavigationStack {
                if let id = rss.selectedId, sources.contains(where: { $0.sourceId == id }) {
                    RssItemsScreen(sourceId: id, container: container, actions: actions, rss: rss)
                        .id(id)
                } else {
                    ContentUnavailableView {
                        Label(L("rssSubscriptions"), systemImage: "dot.radiowaves.up.forward")
                    } description: {
                        Text(L("mobileRssSelectFeedHint"))
                    }
                }
            }
        }
    }
}

// MARK: - R1 订阅列表

private struct RssFeedsList: View {
    nonisolated enum Mode { case stack, split }

    @Environment(AppContainer.self) private var container
    let rss: RssModel
    let mode: Mode

    var body: some View {
        @Bindable var rss = rss
        let state = container.store.state
        let sources = state.rssSources
        let visible = rss.visibleSources(sources)
        let failing = sources.filter { $0.failCount > 0 }.count
        let unread = sources.reduce(0) { $0 + Int($1.unreadCount) }
        let readOnly = state.isReadOnly
        Group {
            if mode == .split {
                List(selection: $rss.selectedId) { listContent(sources: sources, visible: visible, failing: failing, unread: unread, readOnly: readOnly, state: state) }
            } else {
                List { listContent(sources: sources, visible: visible, failing: failing, unread: unread, readOnly: readOnly, state: state) }
            }
        }
        .listStyle(.insetGrouped)
        .overlay { overlayState(sources: sources, visible: visible, connection: state.connection) }
        .refreshable { await rss.refreshAll(sources) }
        // 订阅属于主机：标题副行显示当前主机（与下载页同款标题菜单切换主机），不再单独占一个左上角胶囊。
        .rootNavigationTitle(L("rssSubscriptions"), subtitle: hostTitleSubtitle(container.host, status: state.connection.statusText))
        .toolbarTitleMenu { HostTitleMenuItems(status: state.connection.statusText) }
        .modifier(OptionalSearch(enabled: sources.count >= RssModel.searchThreshold, text: $rss.query))
        .toolbar { toolbarContent(sources: sources, readOnly: readOnly) }
        .fluxAnimation(.smooth, value: visible.map(\.sourceId))
    }

    // MARK: 内容

    @ViewBuilder
    private func listContent(sources: [RssSource], visible: [RssSource], failing: Int, unread: Int, readOnly: Bool, state: HostState) -> some View {
        if showsOfflineBanner(state.connection) {
            Section {
                Banner(text: L("localServiceDisconnected"), tone: .warning, systemImage: FluxSymbol.offline)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
            }
        }
        if let feedback = rss.feedback {
            Section {
                Banner(
                    text: feedback,
                    tone: .info,
                    systemImage: "tray.and.arrow.down",
                    slim: true,
                    action: BannerAction(title: L("close")) { rss.feedback = nil }
                )
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
        }
        if failing > 0 {
            Section {
                Banner(
                    text: L("mobileRssFailedBanner", ["n": failing]),
                    tone: .warning,
                    systemImage: FluxSymbol.warning,
                    action: BannerAction(title: L(rss.failingOnly ? "mobileRssShowAll" : "mobileRssShowFailing")) {
                        rss.failingOnly.toggle()
                    }
                )
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
        }
        if !sources.isEmpty {
            Section {
                StatRow(cells: [
                    StatCell(value: String(unread), unit: nil, label: L("mobileRssStatUnread"), emphasis: unread > 0 ? .accent : .none),
                    StatCell(value: String(sources.count), unit: nil, label: L("mobileRssStatFeeds")),
                    StatCell(value: String(failing), unit: nil, label: L("mobileRssStatFailing"), emphasis: failing > 0 ? .failure : .none),
                ])
                .padding(.vertical, 4)
            }
            Section {
                ForEach(visible) { source in
                    row(source, readOnly: readOnly)
                }
            } header: {
                Text("\(L("rssSubscriptions")) · \(visible.count)")
            }
        }
    }

    @ViewBuilder
    private func row(_ source: RssSource, readOnly: Bool) -> some View {
        let refreshing = rss.busy.contains(source.sourceId)
        Group {
            if mode == .stack {
                NavigationLink(value: source.sourceId) {
                    RssFeedRow(source: source, refreshing: refreshing)
                }
            } else {
                RssFeedRow(source: source, refreshing: refreshing)
                    .tag(source.sourceId)
            }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if !readOnly {
                Button(L("rssRefreshNow"), systemImage: FluxSymbol.retry) { rss.refresh(source) }
                    .tint(.accentColor)
                    .disabled(refreshing)
                if source.unreadCount > 0 {
                    Button(L("rssMarkAllRead"), systemImage: "checkmark.circle") { rss.markAllRead(source) }
                        .tint(Color.fdStatusPaused)
                }
            }
        }
        // 删除只弹确认框：不用 `role: .destructive`（会让 List 先行移除该行而数据未变，导致行数不一致崩溃）。
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !readOnly {
                Button(L("rssDeleteSource"), systemImage: FluxSymbol.delete) { rss.requestDelete(source, from: .feedList) }
                    .tint(Color.fdStatusFailed)
                Button(L("rssManageTitle"), systemImage: "slider.horizontal.3") {
                    rss.openEditor(.edit(sourceId: source.sourceId))
                }
                .tint(Color.fdBoost)
            }
        }
        .contextMenu {
            Button(L("rssManageTitle"), systemImage: "slider.horizontal.3") {
                rss.openEditor(.edit(sourceId: source.sourceId))
            }
            .disabled(readOnly)
            Button(L("rssRefreshNow"), systemImage: FluxSymbol.retry) { rss.refresh(source) }
                .disabled(refreshing || readOnly)
            Button(L("rssMarkAllRead"), systemImage: "checkmark.circle") { rss.markAllRead(source) }
                .disabled(source.unreadCount == 0 || readOnly)
            Button(L("copyUrl"), systemImage: FluxSymbol.copy) { rss.copyLink(source) }
            Button(L(source.enabled ? "mobileRssDisable" : "mobileRssEnable"), systemImage: source.enabled ? "pause.circle" : "play.circle") {
                rss.toggle(source)
            }
            .disabled(readOnly)
            Divider()
            Button(L("rssDeleteSource"), systemImage: FluxSymbol.delete, role: .destructive) { rss.requestDelete(source, from: .feedList) }
                .disabled(readOnly)
        }
        .rssDeleteConfirmation(rss, source: source, origin: .feedList)
    }

    // MARK: 空 / 加载

    @ViewBuilder
    private func overlayState(sources: [RssSource], visible: [RssSource], connection: Connection) -> some View {
        if sources.isEmpty {
            if connection == .connecting {
                ProgressView().controlSize(.large)
            } else {
                EmptyStateView(L("mobileRssEmptyTitle"), message: L("iosRssEmptySub"), systemImage: FluxSymbol.subscriptions) {
                    Button(L("rssAddSource"), systemImage: FluxSymbol.add) { rss.openEditor(.create) }
                        .disabled(container.store.state.isReadOnly)
                }
            }
        } else if visible.isEmpty {
            if !rss.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                ContentUnavailableView.search(text: rss.query)
            } else {
                ContentUnavailableView {
                    Label(L("mobileRssFilterEmpty"), systemImage: "line.3.horizontal.decrease.circle")
                } actions: {
                    Button(L("mobileRssClearFilter")) {
                        rss.failingOnly = false
                        rss.unreadOnly = false
                    }
                }
            }
        }
    }

    // MARK: 工具栏

    @ToolbarContentBuilder
    private func toolbarContent(sources: [RssSource], readOnly: Bool) -> some ToolbarContent {
        ToolbarItem(placement: .primaryAction) { GlobalSearchButton() }
        ToolbarSpacer(.fixed, placement: .primaryAction)
        ToolbarItemGroup(placement: .primaryAction) {
            Button(L("rssAddSource"), systemImage: FluxSymbol.add) { rss.openEditor(.create) }
                .disabled(readOnly)
            if !sources.isEmpty {
                moreMenu(sources: sources, readOnly: readOnly)
            }
        }
    }

    private func moreMenu(sources: [RssSource], readOnly: Bool) -> some View {
        @Bindable var rss = rss
        return Menu {
            Button(L("mobileRssRefreshAll"), systemImage: FluxSymbol.retry) {
                Task { await rss.refreshAll(sources) }
            }
            .disabled(readOnly || !rss.busy.isEmpty)
            Button(L("rssMarkAllRead"), systemImage: "checkmark.circle") {
                rss.markAllRead(sources.filter { $0.unreadCount > 0 })
            }
            .disabled(readOnly || !sources.contains { $0.unreadCount > 0 })
            Toggle(L("mobileRssUnreadOnly"), systemImage: "circle.fill", isOn: $rss.unreadOnly)
            Picker(L("mobileRssSort"), selection: $rss.sort) {
                ForEach(RssFeedSort.allCases) { sort in
                    Text(L(sort.titleKey)).tag(sort)
                }
            }
            .pickerStyle(.menu)
        } label: {
            Label(L("moreActions"), systemImage: FluxSymbol.more)
        }
    }

    /// 断连宽限后（stale / failed）才提示；冷启动的 `connecting` 不闪横幅。
    private func showsOfflineBanner(_ connection: Connection) -> Bool {
        switch connection {
        case .stale, .failed: true
        case .live, .connecting: false
        }
    }
}

/// 订阅数达到阈值才出现搜索栏（R1）。
private struct OptionalSearch: ViewModifier {
    let enabled: Bool
    @Binding var text: String

    @ViewBuilder
    func body(content: Content) -> some View {
        if enabled {
            content.searchable(
                text: $text, placement: .navigationBarDrawer(displayMode: .automatic), prompt: L("mobileRssSearchFeeds")
            )
        } else {
            content
        }
    }
}
