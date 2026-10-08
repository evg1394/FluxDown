import Foundation

/// 数值与单位分离（数字与单位分别排版，避免换行把单位孤立到下一行）。
public struct Measure: Sendable, Hashable, CustomStringConvertible {
    public var value: String
    public var unit: String

    public init(_ value: String, _ unit: String) {
        self.value = value
        self.unit = unit
    }

    public var description: String { "\(value) \(unit)" }
}

/// 与 Android `Format.kt` 同规则的格式化。
public enum Format {
    private static let units = ["B", "KB", "MB", "GB", "TB"]

    /// 1024 进制；≥100 不保留小数、≥10 保留 1 位、否则 2 位；B 不带小数；≤0 → "0 B"。
    public static func bytes(_ n: Int64) -> Measure {
        guard n > 0 else { return Measure("0", "B") }
        var v = Double(n)
        var i = 0
        while v >= 1024, i < units.count - 1 {
            v /= 1024
            i += 1
        }
        if i == 0 { return Measure(String(n), "B") }
        let digits = v >= 100 ? 0 : (v >= 10 ? 1 : 2)
        return Measure(String(format: "%.\(digits)f", locale: Locale(identifier: "en_US_POSIX"), v), units[i])
    }

    /// 无符号计数（磁盘剩余等）：超出 Int64 时截断到上限。
    public static func bytes(unsigned n: UInt64) -> Measure { bytes(Int64(clamping: n)) }

    /// 速度 > 0 → "{bytes}/s"；否则 nil（UI 显示「—」）。
    public static func speed(_ bps: Int64) -> Measure? {
        guard bps > 0 else { return nil }
        let b = bytes(bps)
        return Measure(b.value, b.unit + "/s")
    }

    /// 速度仪表：空闲显示 0 B/s。
    public static func speedOrZero(_ bps: Int64) -> Measure { speed(bps) ?? Measure("0", "B/s") }

    /// 百分比 floor 到 0.1。
    public static func percent(_ fraction: Double) -> String {
        let floored = (fraction * 1000).rounded(.down) / 10
        return String(format: "%.1f%%", locale: Locale(identifier: "en_US_POSIX"), floored)
    }

    /// ETA 秒数：速度 ≤0、总大小未知、已完成或超过 24h → nil（UI 显示「—」）。
    public static func etaSeconds(downloaded: Int64, total: Int64, speed: Int64) -> Int64? {
        guard speed > 0, total > 0, downloaded < total else { return nil }
        let seconds = Int64((Double(total - downloaded) / Double(speed)).rounded(.up))
        return (1...86_400).contains(seconds) ? seconds : nil
    }

    /// 文案中的「—」占位。
    public static let dash = "—"
}
