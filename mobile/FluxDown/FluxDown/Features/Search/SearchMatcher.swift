import FluxDomain
import Foundation

/// 命令 / 设置页被选中后要执行的动作（由 `GlobalSearchScreen` 映射到路由 / `TaskActions` / 外观）。
nonisolated enum SearchAction: Hashable {
    case newDownload
    case pauseAll
    case resumeAll
    case activity
    case toggleTheme
    case goTab(AppTab)
    /// 打开设置分类页。
    case openSettings(SettingsRoute)
    /// 打开设置页并滚动 / 脉冲高亮到某一行（行 id = 页面里 `.settingsRow` 的 id）。
    case openSettingsRow(SettingsRoute, String)
    /// 队列管理 Sheet（D7 / D8）。
    case openQueues
    /// 订阅页：新建订阅。
    case newRssSource
    /// 设备页：添加设备（配对）。
    case addDevice
    /// 添加远端主机 Sheet（V2）。
    case addHost
}

/// 命令 / 设置页条目（同 Android `CommandSearch.kt` 的 `Entry`）：
/// `keywords` 只做连续子串匹配（降权），`sub` 同样参与降权匹配。
nonisolated struct SearchEntry: Identifiable, Hashable {
    let id: String
    let title: String
    let sub: String?
    let keywords: [String]
    let systemImage: String
    let action: SearchAction
}

extension SearchEntry {
    /// 设置条目 → 搜索条目：行条目跳转到所属页并定位到该行（副行 = 面包屑，说明与别名词参与降权匹配）；
    /// 分类入口（`category.*`）只打开该页（副行 = 分类说明）。
    init(setting entry: SettingsEntry) {
        let isCategory = entry.id.hasPrefix("category.")
        self.init(
            id: "setting.\(entry.route).\(entry.id)",
            title: entry.title,
            sub: isCategory ? (entry.detail.isEmpty ? nil : entry.detail) : entry.breadcrumb,
            keywords: entry.keywords + (isCategory || entry.detail.isEmpty ? [] : [entry.detail]),
            systemImage: entry.symbol,
            action: isCategory ? .openSettings(entry.route) : .openSettingsRow(entry.route, entry.id)
        )
    }
}

/// G1 的命令条目（纯构造，便于单测显隐规则）。顺序 = 空查询时的展示顺序（全部范围下只显示前 8 条）。
enum SearchCommands {
    /// - Parameters:
    ///   - isDark: 当前是否暗色（决定切换明暗命令的文案 / 图标）。
    ///   - canAddDevice: 主机具备云账户或局域网直连能力（设备页才有「添加设备」）。
    ///   - showsThemeToggle: `ui.show_activity_theme`（云同步偏好，通用设置「入口」）；关闭则不提供切换明暗。
    static func entries(isDark: Bool, canAddDevice: Bool, showsThemeToggle: Bool) -> [SearchEntry] {
        func command(_ id: String, _ titleKey: String, _ image: String, _ action: SearchAction, keywords: [String] = []) -> SearchEntry {
            SearchEntry(id: id, title: L(titleKey), sub: nil, keywords: keywords, systemImage: image, action: action)
        }
        func goTo(_ id: String, _ pageKey: String, _ image: String, _ tab: AppTab) -> SearchEntry {
            SearchEntry(
                id: id,
                title: L("commandPaletteGoTo", ["page": L(pageKey)]),
                sub: nil,
                keywords: [],
                systemImage: image,
                action: .goTab(tab)
            )
        }
        var entries = [
            command("new", "newDownload", "plus", .newDownload),
            command("pause-all", "pauseAll", "pause.fill", .pauseAll, keywords: SearchMatcher.words(L("commandPalettePauseAllAliases"))),
            command("resume-all", "resumeAll", "play.fill", .resumeAll, keywords: SearchMatcher.words(L("commandPaletteResumeAllAliases"))),
            command("activity", "mobileSearchCmdActivity", "waveform.path.ecg", .activity),
            command("queues", "manageQueueAction", "list.number", .openQueues),
            command("new-rss", "rssAddSource", "dot.radiowaves.up.forward", .newRssSource, keywords: [L("rssSubscriptions")]),
        ]
        if canAddDevice {
            entries.append(command("add-device", "addDeviceEntry", "laptopcomputer", .addDevice, keywords: [L("mobileNavDevices")]))
        }
        entries.append(command("add-host", "mobileHostAdd", "server.rack", .addHost, keywords: [L("mobileHostSwitchTitle")]))
        if showsThemeToggle {
            entries.append(command(
                "theme", isDark ? "toggleToLight" : "toggleToDark", isDark ? "sun.max" : "moon", .toggleTheme,
                keywords: [L("themeMode"), L("themeModeDark"), L("themeModeLight"), L("activityThemeToggle")]
            ))
        }
        entries += [
            goTo("go-dl", "mobileNavDownloads", "arrow.down.circle", .downloads),
            goTo("go-rss", "mobileNavRss", "dot.radiowaves.up.forward", .rss),
            goTo("go-dev", "mobileNavDevices", "network", .devices),
            goTo("go-settings", "mobileNavSettings", "gearshape", .settings),
        ]
        return entries
    }
}

/// 带分数的命中。
struct SearchHit<Item> {
    let item: Item
    let score: Int
}

/// G1 命令搜索的匹配与排序（逐条移植 Android `CommandSearch.kt`；拼音首字母不在范围内）：
/// 任务按文件名匹配（大小写不敏感，多词须全部命中）；命令 / 设置页按 标题 > 关键词 > 描述 逐级降权。
enum SearchMatcher {
    /// 相等 100 / 前缀 90 / 词边界 82 / 子串 76；未命中 0。
    static func tokenScore(_ text: String, _ tok: String) -> Int {
        let t = Array(text.lowercased())
        let k = Array(tok.lowercased())
        if t == k { return 100 }
        if t.starts(with: k) { return 90 }
        guard let i = firstIndex(of: k, in: t) else { return 0 }
        if i == 0 { return 90 }
        let before = t[i - 1]
        return (before.isLetter || before.isNumber) ? 76 : 82
    }

    /// 松散子序列（仅用于命令 / 设置标题）：24。
    static func looseScore(_ text: String, _ tok: String) -> Int {
        let k = Array(tok.lowercased())
        guard k.count >= 2 else { return 0 }
        var j = 0
        for ch in text.lowercased() where j < k.count && ch == k[j] { j += 1 }
        return j == k.count ? 24 : 0
    }

    /// 每个词都必须命中（标题 > 关键词 50 > 描述 40）；返回各词最低分，0 = 不匹配。
    static func entryScore(_ entry: SearchEntry, tokens: [String]) -> Int {
        var worst = Int.max
        for tok in tokens {
            var best = max(tokenScore(entry.title, tok), looseScore(entry.title, tok))
            if best < 50 {
                if entry.keywords.contains(where: { contains($0, tok) }) {
                    best = 50
                } else if let sub = entry.sub, contains(sub, tok) {
                    best = 40
                }
            }
            if best <= 0 { return 0 }
            worst = min(worst, best)
        }
        return worst == Int.max ? 0 : worst
    }

    static func taskScore(_ name: String, tokens: [String]) -> Int {
        var worst = Int.max
        for tok in tokens {
            let s = tokenScore(name, tok)
            if s <= 0 { return 0 }
            worst = min(worst, s)
        }
        return worst == Int.max ? 0 : worst
    }

    /// 无词 = 全部条目（100，保持原序）；否则过滤并按分数降序（同分保持原序）。
    static func rankEntries(_ entries: [SearchEntry], tokens: [String]) -> [SearchHit<SearchEntry>] {
        guard !tokens.isEmpty else { return entries.map { SearchHit(item: $0, score: 100) } }
        return entries.enumerated()
            .compactMap { offset, entry -> (Int, SearchHit<SearchEntry>)? in
                let score = entryScore(entry, tokens: tokens)
                return score > 0 ? (offset, SearchHit(item: entry, score: score)) : nil
            }
            .sorted { $0.1.score != $1.1.score ? $0.1.score > $1.1.score : $0.0 < $1.0 }
            .map(\.1)
    }

    /// 无词 = 空；否则按文件名过滤，分数降序、同分按创建时间新 → 旧（再同则保持原序）。
    static func rankTasks(_ tasks: [DownloadTask], tokens: [String]) -> [SearchHit<DownloadTask>] {
        guard !tokens.isEmpty else { return [] }
        return tasks.enumerated()
            .compactMap { offset, task -> (Int, SearchHit<DownloadTask>)? in
                let score = taskScore(task.fileName, tokens: tokens)
                return score > 0 ? (offset, SearchHit(item: task, score: score)) : nil
            }
            .sorted { lhs, rhs in
                if lhs.1.score != rhs.1.score { return lhs.1.score > rhs.1.score }
                if lhs.1.item.createdAt != rhs.1.item.createdAt { return lhs.1.item.createdAt > rhs.1.item.createdAt }
                return lhs.0 < rhs.0
            }
            .map(\.1)
    }

    /// 查询串 → 词（空白分隔，去空）。
    static func tokens(_ query: String) -> [String] {
        query.split(whereSeparator: \.isWhitespace).map(String.init)
    }

    /// 以 `, | ，` 分隔的别名 / 关键词表（i18n 里的 `…Aliases` / `searchKeywords…`）。
    static func words(_ list: String) -> [String] {
        list.split(whereSeparator: { $0 == "," || $0 == "|" || $0 == "，" })
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    /// 高亮目标：整串命中优先，否则取第一个命中标题的词。
    static func highlightTarget(text: String, query: String, tokens: [String]) -> String {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.isEmpty { return "" }
        if contains(text, q) { return q }
        return tokens.first { contains(text, $0) } ?? ""
    }

    /// `text` 中 `target` 的全部不重叠出现（大小写不敏感）。
    static func ranges(of target: String, in text: String) -> [Range<String.Index>] {
        guard !target.isEmpty else { return [] }
        var result: [Range<String.Index>] = []
        var cursor = text.startIndex
        while cursor < text.endIndex,
              let found = text.range(of: target, options: .caseInsensitive, range: cursor..<text.endIndex) {
            result.append(found)
            cursor = found.upperBound
        }
        return result
    }

    private static func contains(_ text: String, _ needle: String) -> Bool {
        text.range(of: needle, options: .caseInsensitive) != nil
    }

    private static func firstIndex(of needle: [Character], in haystack: [Character]) -> Int? {
        guard !needle.isEmpty else { return 0 }
        guard haystack.count >= needle.count else { return nil }
        for start in 0...(haystack.count - needle.count) where haystack[start] == needle[0] {
            if haystack[start..<(start + needle.count)].elementsEqual(needle) { return start }
        }
        return nil
    }
}

/// 范围条（全部 / 任务 / 命令 / 设置）。
nonisolated enum SearchScope: Hashable, CaseIterable {
    case all, tasks, commands, settings
}

/// 一次查询的结果与各分区的展示截断（Android `SearchBody`：全部范围下各区有上限，单区范围放宽到 30）。
struct SearchResults {
    static let capAllTasks = 5
    static let capAllCommands = 6
    static let capAllCommandsIdle = 8
    static let capAllSettings = 3
    static let capScoped = 30

    let searching: Bool
    let scope: SearchScope
    /// 命中总数（范围条计数用，不受截断影响）。
    let taskHits: [SearchHit<DownloadTask>]
    let commandHits: [SearchHit<SearchEntry>]
    let settingHits: [SearchHit<SearchEntry>]
    let shownTasks: [SearchHit<DownloadTask>]
    let shownCommands: [SearchHit<SearchEntry>]
    let shownSettings: [SearchHit<SearchEntry>]

    init(
        query: String,
        scope: SearchScope,
        tasks: [DownloadTask],
        commands: [SearchEntry],
        settings: [SearchEntry]
    ) {
        let tokens = SearchMatcher.tokens(query)
        let searching = !tokens.isEmpty
        let all = scope == .all
        let taskHits = SearchMatcher.rankTasks(tasks, tokens: tokens)
        let commandHits = SearchMatcher.rankEntries(commands, tokens: tokens)
        let settingHits = SearchMatcher.rankEntries(settings, tokens: tokens)
        self.searching = searching
        self.scope = scope
        self.taskHits = taskHits
        self.commandHits = commandHits
        self.settingHits = settingHits
        shownTasks = all || scope == .tasks ? Array(taskHits.prefix(all ? Self.capAllTasks : Self.capScoped)) : []
        shownCommands = all || scope == .commands
            ? Array(commandHits.prefix(!all ? Self.capScoped : (searching ? Self.capAllCommands : Self.capAllCommandsIdle)))
            : []
        shownSettings = all || scope == .settings ? Array(settingHits.prefix(all ? Self.capAllSettings : Self.capScoped)) : []
    }

    var isEmpty: Bool { shownTasks.isEmpty && shownCommands.isEmpty && shownSettings.isEmpty }

    /// 范围条上的计数（未搜索时不显示）。
    func count(for scope: SearchScope) -> Int? {
        guard searching else { return nil }
        switch scope {
        case .all: return taskHits.count + commandHits.count + settingHits.count
        case .tasks: return taskHits.count
        case .commands: return commandHits.count
        case .settings: return settingHits.count
        }
    }

    /// 键盘「搜索」键执行的第一条结果：任务 > 命令 > 设置。
    nonisolated enum First: Equatable {
        case task(String)
        case entry(SearchAction)
    }

    var first: First? {
        if let hit = shownTasks.first { return .task(hit.item.taskId) }
        if let hit = shownCommands.first { return .entry(hit.item.action) }
        if let hit = shownSettings.first { return .entry(hit.item.action) }
        return nil
    }
}
