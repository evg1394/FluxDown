import SwiftUI

/// 全局活动条内容（§9.6），供 `TabView.tabViewBottomAccessory` 使用。
///
/// **内容里不加玻璃**：附件容器的胶囊玻璃由系统提供（§2.1）。点按整条打开详情由调用方在附件上挂手势。
///
/// - 活跃（`activeCount > 0`）：迷你波形 + 两行文字 + 暂停全部按钮。
/// - 全部已暂停（`isPaused`）：播放按钮；文字由调用方给出（如「全部已暂停 / n 个任务可恢复」）。
/// - 空闲：`arrow.down.to.line` 图标，无按钮。
/// - `placement == .inline`（标签栏收起后并排，高 62）只显示第一行；`.expanded` 显示两行。
/// - AX 字号：隐藏波形，文字最多两行。
/// - 暂停 / 继续按钮视觉 34，触控 ≥ 44；`pauseLabel` / `resumeLabel` 由调用方本地化。
public struct ActivityBarContent: View {
    public let downText: String
    public let upText: String
    public let activeCount: Int
    public let samples: [Double]
    public let isPaused: Bool
    public let onTogglePause: () -> Void
    public let pauseLabel: String
    public let resumeLabel: String

    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var toggleCount = 0

    public init(
        downText: String,
        upText: String,
        activeCount: Int,
        samples: [Double],
        isPaused: Bool,
        onTogglePause: @escaping () -> Void,
        pauseLabel: String = "Pause all",
        resumeLabel: String = "Resume all"
    ) {
        self.downText = downText
        self.upText = upText
        self.activeCount = activeCount
        self.samples = samples
        self.isPaused = isPaused
        self.onTogglePause = onTogglePause
        self.pauseLabel = pauseLabel
        self.resumeLabel = resumeLabel
    }

    private var isAccessibilitySize: Bool { typeSize.isAccessibilitySize }
    private var isInline: Bool { placement == .inline }
    private var isActive: Bool { activeCount > 0 }
    private var showsButton: Bool { isActive || isPaused }

    public var body: some View {
        HStack(spacing: 10) {
            leading
            texts
            Spacer(minLength: 0)
            if showsButton { toggleButton }
        }
        .padding(.horizontal, 16)
    }

    @ViewBuilder private var leading: some View {
        if isActive {
            if !isAccessibilitySize {
                SpeedWaveform(samples: samples)
                    .frame(width: 58, height: 24)
            }
        } else {
            Image(systemName: isPaused ? "pause.circle" : "arrow.down.to.line")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
        }
    }

    private var texts: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(downText)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .lineLimit(isAccessibilitySize ? 2 : 1)
            if !isInline, !upText.isEmpty {
                Text(upText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .lineLimit(isAccessibilitySize ? 2 : 1)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var toggleButton: some View {
        Button {
            toggleCount += 1
            onTogglePause()
        } label: {
            Image(systemName: isPaused ? FluxSymbol.resume : FluxSymbol.pause)
                .font(.system(size: 14, weight: .semibold))
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 34, height: 34)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(.circle)
        }
        .buttonStyle(.fluxPress(scale: 0.88))
        .sensoryFeedback(FluxHaptic.medium.sensoryFeedback, trigger: toggleCount)
        .accessibilityLabel(isPaused ? resumeLabel : pauseLabel)
    }
}

#Preview("ActivityBarContent") {
    let wave: [Double] = [2, 5, 9, 7, 12, 15, 11, 18, 22, 19, 24, 20]
    // 附件胶囊由系统提供；预览里用 secondarySystemBackground 胶囊仅作示意。
    VStack(spacing: 14) {
        ActivityBarContent(downText: "3 tasks · 42.1 MB/s", upText: "↑ 1.2 MB/s · 2 queued", activeCount: 3, samples: wave, isPaused: false) {}
            .frame(height: 50)
            .background(Color(uiColor: .secondarySystemBackground), in: .capsule)
        ActivityBarContent(downText: "All paused", upText: "5 tasks can resume", activeCount: 0, samples: [], isPaused: true) {}
            .frame(height: 50)
            .background(Color(uiColor: .secondarySystemBackground), in: .capsule)
        ActivityBarContent(downText: "Idle", upText: "", activeCount: 0, samples: [], isPaused: false) {}
            .frame(height: 50)
            .background(Color(uiColor: .secondarySystemBackground), in: .capsule)
    }
    .padding()
}

#Preview("ActivityBarContent · 深色 / AX3") {
    ActivityBarContent(
        downText: "3 tasks · 42.1 MB/s",
        upText: "↑ 1.2 MB/s · 2 queued",
        activeCount: 3,
        samples: [2, 5, 9, 7, 12, 15],
        isPaused: false
    ) {}
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility3)
    .padding(.vertical, 40)
}
