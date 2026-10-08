import Charts
import SwiftUI

/// 环形图的一块（来源构成）。
public nonisolated struct DonutSlice: Identifiable {
    public let id: String
    public let label: String
    public let value: Double
    public let color: Color

    public init(label: String, value: Double, color: Color) {
        id = label
        self.label = label
        self.value = value
        self.color = color
    }
}

/// 来源环形图（§9.10）：Swift Charts `SectorMark`，内径比 0.8，段间 1pt 缝，底环 `fdProgressTrack`。
///
/// - `center`：环心内容槽（如「已下载 / 总字节」两行，圆体粗体等宽数字由调用方用 `fluxStatNumber`）。
/// - 总量为 0 时只画底环。
/// - 无障碍：整图作为一个元素，label = `accessibilityLabel`，value = 「名称 占比」列表（占比 < 0.1% 显示 `<0.1%`）。
public struct SourceDonut<Center: View>: View {
    public let slices: [DonutSlice]
    public let size: CGFloat
    public let accessibilityLabel: String
    private let center: Center

    public init(
        slices: [DonutSlice],
        size: CGFloat = 150,
        accessibilityLabel: String = "",
        @ViewBuilder center: () -> Center
    ) {
        self.slices = slices
        self.size = size
        self.accessibilityLabel = accessibilityLabel
        self.center = center()
    }

    private var visible: [DonutSlice] { slices.filter { $0.value > 0 } }
    private var total: Double { visible.reduce(0) { $0 + $1.value } }

    public var body: some View {
        Chart {
            if visible.isEmpty {
                SectorMark(angle: .value("track", 1), innerRadius: .ratio(0.8))
                    .foregroundStyle(Color.fdProgressTrack)
            }
            ForEach(visible) { slice in
                SectorMark(angle: .value(slice.label, slice.value), innerRadius: .ratio(0.8), angularInset: 1)
                    .foregroundStyle(slice.color)
            }
        }
        .chartLegend(.hidden)
        .chartYAxis(.hidden)
        .chartXAxis(.hidden)
        .frame(width: size, height: size)
        .overlay {
            center
                .multilineTextAlignment(.center)
                .frame(maxWidth: size * 0.6)
                .minimumScaleFactor(0.6)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityValue(summary)
    }

    private var summary: String {
        guard total > 0 else { return "" }
        return visible.map { slice in
            let fraction = slice.value / total
            let percent = fraction < 0.001 ? "<0.1%" : fraction.formatted(.percent.precision(.fractionLength(0...1)))
            return "\(slice.label) \(percent)"
        }.joined(separator: ", ")
    }
}

extension SourceDonut where Center == EmptyView {
    public init(slices: [DonutSlice], size: CGFloat = 150, accessibilityLabel: String = "") {
        self.init(slices: slices, size: size, accessibilityLabel: accessibilityLabel) { EmptyView() }
    }
}

#Preview("SourceDonut") {
    HStack(spacing: 24) {
        SourceDonut(
            slices: [
                DonutSlice(label: "Origin", value: 640, color: .accentColor),
                DonutSlice(label: "CDN", value: 220, color: .fdKindVideo),
                DonutSlice(label: "Proxy", value: 90, color: .fdKindImage),
                DonutSlice(label: "Multi-NIC", value: 50, color: .fdKindEbook),
            ],
            accessibilityLabel: "Sources"
        ) {
            VStack(spacing: 2) {
                Text("Downloaded").font(.caption).foregroundStyle(.secondary)
                Text("1.0 GB").font(.title3.bold()).fontDesign(.rounded).monospacedDigit()
            }
        }
        SourceDonut(slices: [])
    }
    .padding()
}

#Preview("SourceDonut · 深色") {
    SourceDonut(slices: [DonutSlice(label: "Peers", value: 100, color: .fdStatusSeeding)]) {
        Text("100%").font(.title3.bold()).fontDesign(.rounded)
    }
    .padding()
    .preferredColorScheme(.dark)
}
