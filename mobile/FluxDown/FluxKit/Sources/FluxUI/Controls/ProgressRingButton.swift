import SwiftUI

/// 圆环按钮中心字形（§9.4）。
public nonisolated enum RingGlyph: Sendable, Hashable, CaseIterable {
    /// 下载中 / 排队：`pause.fill`，环显示进度。
    case pause
    /// 已暂停：`play.fill`，环显示进度。
    case resume
    /// 失败：`arrow.clockwise`，满环（轨道 tint α .25）。
    case retry
    /// 已完成 / 做种：`FluxSymbol.openFile`，实心淡底，无环。
    case open
    /// 文件已删除：`arrow.clockwise`，实心淡底，无环。
    case redownload
    /// 准备 / 校验：`pause.fill`，固定 0.25 弧自旋（1.1s）。
    case preparing

    var symbolName: String {
        switch self {
        case .pause, .preparing: FluxSymbol.pause
        case .resume: FluxSymbol.resume
        case .retry, .redownload: FluxSymbol.retry
        case .open: FluxSymbol.openFile
        }
    }

    var symbolWeight: Font.Weight {
        switch self {
        case .retry, .redownload: .medium
        default: .semibold
        }
    }
}

/// App Store 式进度环按钮（§9.4）。
///
/// - 视觉直径 36pt（随 Dynamic Type 缩放，上限 56），环宽 3，字形 = 0.4 × 直径；触控区至少 44×44。
/// - `progress`（0…1）以 `.linear(0.9)` 插值（数据插值，减弱动态效果下直接跳变）；`nil` 视为 0。
/// - 按压 `scale(.88)` snappy + light 触感；暂停 ↔ 继续字形用 `symbolEffect(.replace)` 切换。
/// - 圆环自旋（`.preparing`）是加载指示，减弱动态效果下保留。
/// - `accessibilityLabel` 必须由调用方给出（动作词 + 文件名，如「暂停，Ubuntu 镜像」）；`accessibilityValue` 可选。
public struct ProgressRingButton: View {
    public let progress: Double?
    public let glyph: RingGlyph
    public let tint: Color
    public let accessibilityLabel: String
    public let accessibilityValue: String?
    public let action: () -> Void

    @ScaledMetric(relativeTo: .body) private var scaledDiameter: CGFloat = 36
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var tapCount = 0

    public init(
        progress: Double? = nil,
        glyph: RingGlyph,
        tint: Color = .accentColor,
        accessibilityLabel: String,
        accessibilityValue: String? = nil,
        action: @escaping () -> Void
    ) {
        self.progress = progress
        self.glyph = glyph
        self.tint = tint
        self.accessibilityLabel = accessibilityLabel
        self.accessibilityValue = accessibilityValue
        self.action = action
    }

    private var diameter: CGFloat { min(scaledDiameter, 56) }
    private var lineWidth: CGFloat { 3 }

    public var body: some View {
        Button {
            tapCount += 1
            action()
        } label: {
            ring
                .frame(width: diameter, height: diameter)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(.circle)
        }
        .buttonStyle(.fluxPress(scale: 0.88))
        .sensoryFeedback(FluxHaptic.light.sensoryFeedback, trigger: tapCount)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityValue(accessibilityValue ?? "")
    }

    private var ring: some View {
        ZStack {
            switch glyph {
            case .open, .redownload:
                Circle().fill(tint.opacity(glyph == .open ? 0.14 : 0.15))
            case .retry:
                Circle().stroke(tint.opacity(0.25), lineWidth: lineWidth)
                Circle().stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            case .preparing:
                Circle().stroke(Color.fdProgressTrack, lineWidth: lineWidth)
                spinner
            case .pause, .resume:
                Circle().stroke(Color.fdProgressTrack, lineWidth: lineWidth)
                Circle()
                    .trim(from: 0, to: clampedProgress)
                    .stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(reduceMotion ? nil : .linear(duration: 0.9), value: clampedProgress)
            }
            Image(systemName: glyph.symbolName)
                .font(.system(size: diameter * 0.4, weight: glyph.symbolWeight))
                .foregroundStyle(tint)
                .contentTransition(reduceMotion ? .identity : .symbolEffect(.replace))
                .animation(.fluxSnappy, value: glyph)
        }
    }

    private var clampedProgress: Double { min(max(progress ?? 0, 0), 1) }

    private var spinner: some View {
        TimelineView(.animation) { context in
            let angle = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.1) / 1.1 * 360
            Circle()
                .trim(from: 0, to: 0.25)
                .stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(angle - 90))
        }
    }
}

#Preview("ProgressRingButton") {
    struct Demo: View {
        @State private var progress = 0.35
        var body: some View {
            VStack(spacing: 18) {
                HStack(spacing: 18) {
                    ProgressRingButton(progress: progress, glyph: .pause, accessibilityLabel: "Pause") { progress = min(progress + 0.1, 1) }
                    ProgressRingButton(progress: 0.5, glyph: .pause, tint: .fdStatusQueued, accessibilityLabel: "Pause") {}
                    ProgressRingButton(progress: 0.5, glyph: .resume, tint: .fdStatusPaused, accessibilityLabel: "Resume") {}
                    ProgressRingButton(glyph: .retry, tint: .fdStatusFailed, accessibilityLabel: "Retry") {}
                }
                HStack(spacing: 18) {
                    ProgressRingButton(glyph: .open, accessibilityLabel: "Open") {}
                    ProgressRingButton(glyph: .redownload, tint: .fdStatusWarning, accessibilityLabel: "Redownload") {}
                    ProgressRingButton(glyph: .preparing, tint: .fdStatusQueued, accessibilityLabel: "Preparing") {}
                }
            }
            .padding()
        }
    }
    return Demo()
}

#Preview("ProgressRingButton · 深色 / AX3") {
    HStack(spacing: 18) {
        ProgressRingButton(progress: 0.62, glyph: .pause, accessibilityLabel: "Pause") {}
        ProgressRingButton(glyph: .open, accessibilityLabel: "Open") {}
    }
    .padding()
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility3)
}
