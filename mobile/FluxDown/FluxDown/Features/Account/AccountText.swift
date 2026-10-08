import FluxDomain
import Foundation

/// 账户 / 设备页的文案与格式化（规则在 `FluxDomain` 的 `AccountRules`；这里只做本地化与日期展示）。
enum AccountText {
    /// 错误文案：账户场景按 `reason` / `code` + 上下文映射到键，键不存在时回退通用 `ErrorText`。
    static func error(_ error: HostError, context: AccountErrorContext = .general) -> String {
        let key = AccountRules.errorKey(error, context: context)
        return L10n.shared.has(key) ? L(key) : ErrorText.describe(error)
    }

    /// 云端 ISO-8601 时间（`2025-01-02T03:04:05Z`）→ 本地短日期时间；无法解析返回 nil。
    static func dateTime(iso value: String) -> String? {
        guard let date = parseISO(value) else { return nil }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    static func dateTime(unix value: Int64) -> String? {
        LinkRules.date(fromUnix: value).map { $0.formatted(date: .abbreviated, time: .shortened) }
    }

    static func dateTime(unixMs value: Int64) -> String {
        Date(timeIntervalSince1970: Double(value) / 1000).formatted(date: .abbreviated, time: .shortened)
    }

    private static func parseISO(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: value) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: value)
    }

    /// 同步「已同步 · {time}」的相对时间文案。
    static func syncAgo(_ ago: SyncRules.Ago) -> String {
        switch ago {
        case .justNow: L("cloudSyncTimeJustNow")
        case let .minutes(n): L("cloudSyncTimeMinutesAgo", ["n": n])
        case let .hours(n): L("cloudSyncTimeHoursAgo", ["n": n])
        case let .days(n): L("cloudSyncTimeDaysAgo", ["n": n])
        }
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
