import Foundation

/// 1 Hz 速度采样环（波形 60 点 = 60 秒）。值类型：每次记录返回新值，便于 SwiftUI 比较。
/// 采样按墙钟秒分桶：两次事件间隔 n 秒则以上一值补齐 n−1 个桶（事件稀疏时横轴仍是真实时间）。
/// 与 Android `SpeedHistory.kt` 同语义。
public struct SpeedHistory: Sendable, Hashable {
    public static let capacity = 60
    public static let empty = SpeedHistory()

    private var down: [Int64]
    private var up: [Int64]
    /// 已写入的点数（≤ capacity）。
    public private(set) var count: Int
    /// 最近一个桶对应的 Unix 秒。
    public private(set) var lastSecond: Int64

    private init() {
        down = Array(repeating: 0, count: Self.capacity)
        up = Array(repeating: 0, count: Self.capacity)
        count = 0
        lastSecond = 0
    }

    /// 第 i 个点（0 = 最旧）。
    public func downAt(_ i: Int) -> Int64 { down[slot(i)] }
    public func upAt(_ i: Int) -> Int64 { up[slot(i)] }

    public var latestDown: Int64 { count == 0 ? 0 : downAt(count - 1) }
    public var latestUp: Int64 { count == 0 ? 0 : upAt(count - 1) }

    /// 最旧 → 最新。
    public var downSamples: [Int64] { (0..<count).map(downAt) }
    public var upSamples: [Int64] { (0..<count).map(upAt) }

    public func maxDown() -> Int64 { (0..<count).reduce(0) { max($0, downAt($1)) } }
    public func maxUp() -> Int64 { (0..<count).reduce(0) { max($0, upAt($1)) } }

    private func slot(_ i: Int) -> Int {
        Self.modulo(lastSecond - Int64(count) + 1 + Int64(i))
    }

    private static func modulo(_ second: Int64) -> Int {
        let cap = Int64(capacity)
        return Int(((second % cap) + cap) % cap)
    }

    public func recording(nowMs: Int64, down downBps: Int64, up upBps: Int64) -> SpeedHistory {
        let sec = nowMs / 1000
        if count > 0, sec < lastSecond { return self }
        var next = self
        if count > 0, sec == lastSecond {
            let s = Self.modulo(sec)
            next.down[s] = downBps
            next.up[s] = upBps
            return next
        }
        let gap = count == 0 ? 1 : Int(min(sec - lastSecond, Int64(Self.capacity)))
        let fillDown = count == 0 ? downBps : latestDown
        let fillUp = count == 0 ? upBps : latestUp
        if gap > 1 {
            for k in stride(from: gap - 1, through: 1, by: -1) {
                let s = Self.modulo(sec - Int64(k))
                next.down[s] = fillDown
                next.up[s] = fillUp
            }
        }
        let s = Self.modulo(sec)
        next.down[s] = downBps
        next.up[s] = upBps
        next.count = min(count + gap, Self.capacity)
        next.lastSecond = sec
        return next
    }
}
