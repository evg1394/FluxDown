import FluxDomain
import FluxUI
import SwiftUI

/// 根页工具栏右上角的全局搜索入口（`SheetRoute.search`）。
struct GlobalSearchButton: View {
    @Environment(AppContainer.self) private var container

    var body: some View {
        Button(L("mobileSearchTitle"), systemImage: FluxSymbol.search) {
            container.router.sheet = .search
        }
    }
}

/// G1 全局搜索（= PC 命令面板）：任务（文件名）· 命令 · 设置（每个分类页 + 每个设置行，与设置首页搜索共用 `SettingsIndex`）。
/// 各根页右上角 `magnifyingglass` 按钮以 `.search` Sheet 呈现；选中结果 = 先收起 Sheet 再导航 / 执行命令。
///
/// 顶部是自带的搜索栏（玻璃胶囊输入框 + 关闭钮，`safeAreaBar` 吸顶），**不用系统 `.searchable`**：
/// Sheet 里的 `.searchable(isPresented: true)` 只能在呈现完成后才激活，系统先按「导航栏 + 抽屉搜索框」布局，
/// 激活时再把导航栏收起、搜索框上移并换出取消钮——首帧版式与最终版式不同，呈现后必然卡一下再跳。
/// 自带搜索栏首帧即最终版式，键盘只是随后升起，不再有二次布局。
/// 范围条（全部 / 任务 / 命令 / 设置）在输入后出现，与系统 `searchScopes` 的显示时机一致。
/// 结果是内容层的分组列表，不上玻璃。匹配与排序见 `SearchMatcher`（逐条移植 Android `CommandSearch.kt`）。
struct GlobalSearchScreen: View {
    @Environment(AppContainer.self) private var container
    @Environment(TaskActions.self) private var actions
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.fluxAccent) private var accent

    @State private var query = ""
    @State private var scope: SearchScope = .all
    @FocusState private var fieldFocused: Bool

    var body: some View {
        let state = container.store.state
        let results = SearchResults(
            query: query,
            scope: scope,
            tasks: state.tasks,
            commands: commandEntries(),
            settings: settingEntries()
        )
        let tokens = SearchMatcher.tokens(query)
        List {
            if !results.shownTasks.isEmpty {
                Section {
                    ForEach(results.shownTasks, id: \.item.taskId) { hit in
                        TaskHitRow(task: hit.item, title: highlighted(hit.item.fileName, tokens: tokens)) {
                            finish()
                            container.router.showTask(hit.item.taskId)
                        }
                    }
                    if scope == .all, results.taskHits.count > results.shownTasks.count {
                        Button(L("mobileSearchShowAllTasks", ["n": results.taskHits.count])) { scope = .tasks }
                    }
                } header: {
                    sectionHeader(L("searchGroupTasks"), count: results.searching ? results.taskHits.count : nil)
                }
            }
            if !results.shownCommands.isEmpty {
                Section {
                    ForEach(results.shownCommands, id: \.item.id) { hit in
                        entryRow(hit.item, tokens: tokens, chevron: false)
                    }
                } header: {
                    sectionHeader(L("mobileSearchGroupCommands"), count: nil)
                }
            }
            if !results.shownSettings.isEmpty {
                Section {
                    ForEach(results.shownSettings, id: \.item.id) { hit in
                        entryRow(hit.item, tokens: tokens, chevron: true)
                    }
                } header: {
                    sectionHeader(L("searchGroupSettings"), count: nil)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollDismissesKeyboard(.interactively)
        .overlay {
            if results.isEmpty { emptyState(searching: results.searching) }
        }
        .safeAreaBar(edge: .top, spacing: 0) {
            searchBar(results: results)
        }
        .fluxAnimation(.smooth, value: results.searching)
        .presentationDetents([.large])
        // 呈现即聚焦：版式不随聚焦变化，键盘升起与 Sheet 上滑并行即可。
        .task { fieldFocused = true }
    }

    // MARK: 搜索栏

    private func searchBar(results: SearchResults) -> some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: FluxSymbol.search)
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                    TextField(L("mobileSearchTitle"), text: $query, prompt: Text(L("searchPlaceholder")))
                        .focused($fieldFocused)
                        .submitLabel(.search)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onSubmit { runFirst(results.first) }
                    if !query.isEmpty {
                        Button(L("mobileSiteAuthSearchClear"), systemImage: "xmark.circle.fill") {
                            query = ""
                            fieldFocused = true
                        }
                        .labelStyle(.iconOnly)
                        .foregroundStyle(.secondary)
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .glassEffect(.regular.interactive(), in: .capsule)

                Button(L("close"), systemImage: FluxSymbol.close, role: .close) { finish() }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .controlSize(.large)
                    .keyboardShortcut(.cancelAction)
            }
            if results.searching {
                Picker(L("mobileSearchTitle"), selection: $scope) {
                    ForEach(SearchScope.allCases, id: \.self) { item in
                        scopeLabel(item, count: results.count(for: item)).tag(item)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 8)
    }

    // MARK: 条目

    private func commandEntries() -> [SearchEntry] {
        let state = container.store.state
        return SearchCommands.entries(
            isDark: colorScheme == .dark,
            canAddDevice: state.has(HostCapability.agentAuth) || state.has(HostCapability.agentDeviceLink),
            showsThemeToggle: state.preferences.bool("ui.show_activity_theme", default: true)
        )
    }

    /// 设置 = 每个分类页入口 + 每一行（`SettingsIndex`，与页面渲染共用可见性判定：被条件隐藏 / 能力缺失 / 桌面专属的行不出现）。
    private func settingEntries() -> [SearchEntry] {
        SettingsIndex.entries(state: container.store.state, isLocalHost: container.isLocalHost).map(SearchEntry.init(setting:))
    }

    // MARK: 动作

    private func runFirst(_ first: SearchResults.First?) {
        switch first {
        case let .task(id):
            finish()
            container.router.showTask(id)
        case let .entry(action):
            run(action)
        case nil:
            break
        }
    }

    /// 选中命令 / 设置页 = 先收起搜索 Sheet，再执行导航 / 命令（新建 / 活动面板 / 队列 / 添加主机直接替换当前 Sheet）。
    private func run(_ action: SearchAction) {
        let router = container.router
        switch action {
        case .newDownload: router.openNewDownload()
        case .activity: router.sheet = .activity
        case .openQueues: router.sheet = .queues
        case .addHost: router.sheet = .addHost
        case .newRssSource: router.perform(.newRssSource)
        case .addDevice: router.perform(.addDevice)
        case .pauseAll: finish(); actions.pauseAll()
        case .resumeAll: finish(); actions.resumeAll()
        case .toggleTheme:
            finish()
            let next: ThemeMode = colorScheme == .dark ? .light : .dark
            container.appearance.mode = next
            AppearanceSync.shared.modeChanged(next)
        case let .goTab(tab): finish(); router.tab = tab
        case let .openSettings(route): finish(); router.showSettings(route)
        case let .openSettingsRow(route, row):
            finish()
            SettingsFocus.shared.request(row)
            router.showSettings(route)
        }
    }

    /// 结束搜索：收起 Sheet（查询状态随视图销毁）。仅当前 Sheet 仍是搜索时生效，避免误关已替换上来的 Sheet。
    private func finish() {
        if container.router.sheet == .search { container.router.sheet = nil }
    }

    // MARK: 视图片段

    private func entryRow(_ entry: SearchEntry, tokens: [String], chevron: Bool) -> some View {
        Button {
            run(entry.action)
        } label: {
            HitRow(
                title: highlighted(entry.title, tokens: tokens),
                sub: entry.sub,
                chevron: chevron,
                accessibilityTitle: entry.title
            ) {
                GlyphTile(systemImage: entry.systemImage, tint: .secondary, size: 32)
            }
        }
        .buttonStyle(.plain)
    }

    private func sectionHeader(_ title: String, count: Int?) -> some View {
        HStack {
            Text(title)
            if let count {
                Spacer()
                Text(count, format: .number).monospacedDigit()
            }
        }
    }

    private func scopeLabel(_ scope: SearchScope, count: Int?) -> some View {
        let title = switch scope {
        case .all: L("tabAll")
        case .tasks: L("searchGroupTasks")
        case .commands: L("mobileSearchGroupCommands")
        case .settings: L("searchGroupSettings")
        }
        return Text(count.map { "\(title) \($0)" } ?? title)
            .accessibilityLabel(count.map { "\(title), \(L("mobileSearchResultsCount", ["n": $0]))" } ?? title)
    }

    @ViewBuilder
    private func emptyState(searching: Bool) -> some View {
        if searching {
            ContentUnavailableView(
                L("commandPaletteNoResults"),
                systemImage: FluxSymbol.search,
                description: Text(L("mobileSearchNoResultTip"))
            )
        } else {
            ContentUnavailableView(L("searchTasksPlaceholder"), systemImage: FluxSymbol.search)
        }
    }

    /// 标题里命中的部分用强调文字色 + 半粗体标出（目标取整串命中，否则第一个命中词）。
    private func highlighted(_ text: String, tokens: [String]) -> AttributedString {
        var out = AttributedString(text)
        let target = SearchMatcher.highlightTarget(text: text, query: query, tokens: tokens)
        for range in SearchMatcher.ranges(of: target, in: text) {
            guard let attributed = Range(range, in: out) else { continue }
            out[attributed].foregroundColor = accent.text
            out[attributed].font = .body.weight(.semibold)
        }
        return out
    }
}

/// 结果行（任务 / 命令 / 设置共用的版式）：前导图标 · 标题（单行，命中高亮）· 副行 · 可选 chevron。
private struct HitRow<Leading: View>: View {
    let title: AttributedString
    let sub: String?
    let chevron: Bool
    let accessibilityTitle: String
    @ViewBuilder let leading: Leading

    var body: some View {
        HStack(spacing: 14) {
            leading
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if let sub {
                    Text(sub)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if chevron {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel([accessibilityTitle, sub].compactMap { $0 }.joined(separator: ", "))
    }
}

/// 任务命中行：文件类别图标 · 文件名 · 「状态 · 大小」。
private struct TaskHitRow: View {
    let task: DownloadTask
    let title: AttributedString
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HitRow(title: title, sub: subtitle, chevron: true, accessibilityTitle: task.fileName) {
                KindIcon(kind: FileKind.from(fileName: task.fileName), size: 32, dimmed: task.fileMissing)
            }
        }
        .buttonStyle(.plain)
    }

    private var subtitle: String? {
        let parts = [
            statusLabel(task.status),
            task.totalBytes > 0 ? Format.bytes(task.totalBytes).description : nil,
        ].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func statusLabel(_ status: TaskStatus) -> String? {
        switch status {
        case .pending: L("statusPending")
        case .downloading: L("statusDownloading")
        case .paused: L("statusPaused")
        case .completed: L("statusCompleted")
        case .failed: L("statusError")
        case .preparing: L("statusPreparing")
        case .unknown: nil
        }
    }
}
