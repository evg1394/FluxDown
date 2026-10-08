import Accessibility
import SwiftUI

/// 倒计时环（§9.12）：36pt，轨道 `systemFill` 3pt，进度为强调色 3pt 圆头，中心为剩余秒数（圆体粗体等宽数字）。
///
/// - 由 `TimelineView` 按 `deadline` 驱动，环连续消耗；不持有计时器状态，重建视图不会重置。
/// - VoiceOver：label = `label(秒数)`（调用方本地化，如「剩余 12 秒」）；剩 10 / 5 / 3 秒各播报一次。
/// - `onExpire` 在剩余秒数首次到 0 时触发一次（页面据此「按默认处理」并给 success 触感）。
/// - 倒计时是信息，不是装饰：减弱动态效果下仍然走动。
public struct CountdownRing: View {
    public let deadline: Date
    public let total: TimeInterval
    public let label: (Int) -> String
    public let onExpire: (() -> Void)?

    @ScaledMetric(relativeTo: .body) private var scaledDiameter: CGFloat = 36

    public init(
        deadline: Date,
        total: TimeInterval,
        label: @escaping (Int) -> String = { "\($0)" },
        onExpire: (() -> Void)? = nil
    ) {
        self.deadline = deadline
        self.total = total
        self.label = label
        self.onExpire = onExpire
    }

    public var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 15)) { context in
            CountdownRingBody(
                remaining: max(deadline.timeIntervalSince(context.date), 0),
                total: total,
                diameter: min(scaledDiameter, 56),
                label: label,
                onExpire: onExpire
            )
        }
    }
}

private struct CountdownRingBody: View {
    let remaining: TimeInterval
    let total: TimeInterval
    let diameter: CGFloat
    let label: (Int) -> String
    let onExpire: (() -> Void)?

    @State private var didExpire = false

    private var seconds: Int { Int(remaining.rounded(.up)) }
    private var fraction: Double { min(max(remaining / max(total, 0.001), 0), 1) }

    var body: some View {
        ZStack {
            Circle().stroke(Color(uiColor: .systemFill), lineWidth: 3)
            Circle()
                .trim(from: 0, to: fraction)
                .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text(seconds, format: .number)
                .font(.footnote.bold())
                .fontDesign(.rounded)
                .monospacedDigit()
                .minimumScaleFactor(0.7)
                .lineLimit(1)
                .padding(4)
        }
        .frame(width: diameter, height: diameter)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label(seconds))
        .onChange(of: seconds) { _, newValue in
            if [10, 5, 3].contains(newValue) {
                AccessibilityNotification.Announcement(label(newValue)).post()
            }
            if newValue == 0, !didExpire {
                didExpire = true
                onExpire?()
            }
        }
    }
}

#Preview("CountdownRing") {
    HStack(spacing: 24) {
        CountdownRing(deadline: .now.addingTimeInterval(30), total: 30) { "Remaining \($0) seconds" }
        CountdownRing(deadline: .now.addingTimeInterval(7), total: 30)
        CountdownRing(deadline: .now.addingTimeInterval(2), total: 30)
    }
    .padding()
}

#Preview("CountdownRing · 深色 / AX3") {
    CountdownRing(deadline: .now.addingTimeInterval(12), total: 30)
        .padding()
        .preferredColorScheme(.dark)
        .dynamicTypeSize(.accessibility3)
}
