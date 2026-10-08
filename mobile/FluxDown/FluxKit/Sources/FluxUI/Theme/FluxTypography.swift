import SwiftUI

// 字体助手（docs/mobile-ui/ios/01-foundations.md §4）。文字一律用文字样式，不写固定 pt；
// 固定 pt 只用于品牌大数字，并经 `@ScaledMetric` 随 Dynamic Type 缩放。

/// 品牌大数字档位（§4.2 表）。
public nonisolated enum FluxStatSize: Sendable, Hashable, CaseIterable {
    /// 详情英雄三栏统计值 26 · bold rounded。
    case hero
    /// 速度页磁贴 21 · bold rounded。
    case tile
    /// Live Activity 大数字 24 · bold rounded。
    case liveActivity
    /// 小组件主数字 30 · bold rounded。
    case widget
    /// 灵动岛紧凑速度 13 · semibold rounded。
    case island

    /// 基准字号（Large 档，pt）。
    public var points: CGFloat {
        switch self {
        case .hero: 26
        case .tile: 21
        case .liveActivity: 24
        case .widget: 30
        case .island: 13
        }
    }

    public var weight: Font.Weight {
        self == .island ? .semibold : .bold
    }
}

private struct FluxStatNumberModifier: ViewModifier {
    @ScaledMetric private var points: CGFloat
    private let weight: Font.Weight

    init(size: FluxStatSize) {
        _points = ScaledMetric(wrappedValue: size.points, relativeTo: .title)
        weight = size.weight
    }

    func body(content: Content) -> some View {
        content
            .font(.system(size: points, weight: weight, design: .rounded))
            .monospacedDigit()
    }
}

extension View {
    /// 品牌大数字：圆体 + 等宽数字，字号随 Dynamic Type 缩放（`@ScaledMetric(relativeTo: .title)`）。
    public func fluxStatNumber(_ size: FluxStatSize = .hero) -> some View {
        modifier(FluxStatNumberModifier(size: size))
    }

    /// 实时变化的数字（速度、百分比、剩余时间、计数）：`.monospacedDigit()`（§4.3）。
    public func fluxLiveNumber() -> some View {
        monospacedDigit()
    }
}

extension Font {
    /// 任务名：callout semibold（§4.1）。
    public static let fluxTaskName: Font = .callout.weight(.semibold)
    /// 徽标 / 协议标签：caption2 semibold（§4.1）。
    public static let fluxBadge: Font = .caption2.weight(.semibold)
    /// 等宽：哈希、路径、链接、日志时间戳（§4.2）。
    public static let fluxMono: Font = .system(.footnote, design: .monospaced)
}
