import FluxDomain

/// 下载页筛选区各部分的显隐（云同步偏好 `ui.show_sidebar_status|queues|category`，通用设置「下载页显示」）。
/// 缺省全部显示。纯值，便于单测。
nonisolated struct FilterBarVisibility: Equatable {
    static let statusKey = "ui.show_sidebar_status"
    static let queuesKey = "ui.show_sidebar_queues"
    static let categoryKey = "ui.show_sidebar_category"

    var status = true
    var queues = true
    var categories = true

    init(status: Bool = true, queues: Bool = true, categories: Bool = true) {
        self.status = status
        self.queues = queues
        self.categories = categories
    }

    init(_ preferences: AgentPreferencesDto) {
        self.init(
            status: preferences.bool(Self.statusKey, default: true),
            queues: preferences.bool(Self.queuesKey, default: true),
            categories: preferences.bool(Self.categoryKey, default: true)
        )
    }

    /// 整个筛选区无内容可显示（状态条被隐藏，且没有可见的范围 / 分类芯片）。
    /// `hasScopeChip`：队列芯片当前是否会显示（已含 `queues` 开关）。
    func isEmpty(hasCategories: Bool, hasScopeChip: Bool) -> Bool {
        !status && !(categories && hasCategories) && !hasScopeChip
    }
}
