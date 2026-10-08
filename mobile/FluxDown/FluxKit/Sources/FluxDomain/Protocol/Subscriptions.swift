import Foundation

// BT Tracker / eD2K 服务器订阅（`daemon.bt.trackerSubscription.refresh` / `daemon.ed2k.serverSubscription.refresh`）。
// 镜像 `native/protocol/src/daemon.rs`（TrackerSubRefreshResponse / Ed2kServerSubRefreshResponse）；两个方法都不带参数。
// 列表型配置键的存储格式镜像 GPUI `sections/subscription.rs::ListFormat` 与 Web `listFormat.ts`。

/// 订阅刷新结果的公共视图（BT / eD2K 两种响应共用同一套展示逻辑）。
public struct SubscriptionRefreshOutcome: Sendable, Hashable {
    /// 至少一个订阅源拉取成功。
    public var success: Bool
    /// 去重合并后的唯一条目数（Tracker / 服务器）。
    public var count: Int64
    public var okSources: Int64
    public var totalSources: Int64
    /// 缓存更新时间（Unix 秒；本次未成功时沿用旧值）。
    public var updatedAt: Int64
    /// 全部源失败时的错误摘要（成功时为空）。
    public var error: String

    public init(success: Bool, count: Int64, okSources: Int64, totalSources: Int64, updatedAt: Int64, error: String) {
        self.success = success
        self.count = count
        self.okSources = okSources
        self.totalSources = totalSources
        self.updatedAt = updatedAt
        self.error = error
    }
}

/// `daemon.bt.trackerSubscription.refresh` 结果。
public struct TrackerSubRefreshResponse: Sendable, Hashable, Codable {
    public var success: Bool
    public var trackerCount: Int64
    public var okSources: Int64
    public var totalSources: Int64
    public var updatedAt: Int64
    public var error: String

    public init(
        success: Bool = false, trackerCount: Int64 = 0, okSources: Int64 = 0, totalSources: Int64 = 0,
        updatedAt: Int64 = 0, error: String = ""
    ) {
        self.success = success
        self.trackerCount = trackerCount
        self.okSources = okSources
        self.totalSources = totalSources
        self.updatedAt = updatedAt
        self.error = error
    }

    private enum CodingKeys: String, CodingKey { case success, trackerCount, okSources, totalSources, updatedAt, error }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        success = try container.decodeIfPresent(Bool.self, forKey: .success) ?? false
        trackerCount = try container.decodeIfPresent(Int64.self, forKey: .trackerCount) ?? 0
        okSources = try container.decodeIfPresent(Int64.self, forKey: .okSources) ?? 0
        totalSources = try container.decodeIfPresent(Int64.self, forKey: .totalSources) ?? 0
        updatedAt = try container.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        error = try container.decodeIfPresent(String.self, forKey: .error) ?? ""
    }

    public var outcome: SubscriptionRefreshOutcome {
        SubscriptionRefreshOutcome(
            success: success, count: trackerCount, okSources: okSources, totalSources: totalSources,
            updatedAt: updatedAt, error: error
        )
    }
}

/// `daemon.ed2k.serverSubscription.refresh` 结果。
public struct Ed2kServerSubRefreshResponse: Sendable, Hashable, Codable {
    public var success: Bool
    public var serverCount: Int64
    public var okSources: Int64
    public var totalSources: Int64
    public var updatedAt: Int64
    public var error: String

    public init(
        success: Bool = false, serverCount: Int64 = 0, okSources: Int64 = 0, totalSources: Int64 = 0,
        updatedAt: Int64 = 0, error: String = ""
    ) {
        self.success = success
        self.serverCount = serverCount
        self.okSources = okSources
        self.totalSources = totalSources
        self.updatedAt = updatedAt
        self.error = error
    }

    private enum CodingKeys: String, CodingKey { case success, serverCount, okSources, totalSources, updatedAt, error }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        success = try container.decodeIfPresent(Bool.self, forKey: .success) ?? false
        serverCount = try container.decodeIfPresent(Int64.self, forKey: .serverCount) ?? 0
        okSources = try container.decodeIfPresent(Int64.self, forKey: .okSources) ?? 0
        totalSources = try container.decodeIfPresent(Int64.self, forKey: .totalSources) ?? 0
        updatedAt = try container.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        error = try container.decodeIfPresent(String.self, forKey: .error) ?? ""
    }

    public var outcome: SubscriptionRefreshOutcome {
        SubscriptionRefreshOutcome(
            success: success, count: serverCount, okSources: okSources, totalSources: totalSources,
            updatedAt: updatedAt, error: error
        )
    }
}

// MARK: - 列表型配置键

/// 列表型配置键的存储格式；编辑区一律每行一个条目。
public enum SubscriptionListFormat: Sendable, Hashable {
    /// 按行存储（Tracker、订阅地址；保留 `#` 注释等自由文本）。
    case lines
    /// 逗号分隔的 `host:port`（`ed2k_server_list` / `ed2k_server_sub_cache`）；读取时同时容忍换行 / 空白分隔的旧值。
    case comma

    /// 存储值中的非空条目（首尾去空白）。
    public func entries(_ stored: String) -> [String] {
        let parts: [Substring]
        switch self {
        case .lines:
            parts = stored.split(omittingEmptySubsequences: false) { $0 == "\n" || $0 == "\r" || $0 == "\r\n" }
        case .comma:
            parts = stored.split(omittingEmptySubsequences: false) { $0 == "," || $0.isWhitespace }
        }
        return parts
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    /// 存储值中的条目数。
    public func count(_ stored: String) -> Int { entries(stored).count }

    /// 存储值 → 编辑区文本（每行一个）。
    public func toEditor(_ stored: String) -> String {
        switch self {
        case .lines: stored
        case .comma: entries(stored).joined(separator: "\n")
        }
    }

    /// 编辑区文本 → 存储值：`lines` 仅去首尾空白；`comma` 按条目去空白并忽略大小写去重（保留首次出现的写法）。
    public func toStored(_ text: String) -> String {
        switch self {
        case .lines:
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        case .comma:
            var seen = Set<String>()
            return entries(text)
                .filter { seen.insert($0.lowercased()).inserted }
                .joined(separator: ",")
        }
    }
}
