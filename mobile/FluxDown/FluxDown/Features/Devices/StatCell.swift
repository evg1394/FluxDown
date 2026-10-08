import FluxUI
import SwiftUI

/// 三栏读数的一格：品牌大数字（圆体 + 等宽数字）+ 单位 + 说明。
struct StatCell: View {
    nonisolated enum Emphasis { case none, accent, failure }

    let value: String
    let unit: String?
    let label: String
    var emphasis: Emphasis = .none

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .fluxStatNumber(.tile)
                    .foregroundStyle(valueStyle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                if let unit {
                    Text(unit).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(2)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue([value, unit].compactMap { $0 }.joined(separator: " "))
    }

    private var valueStyle: AnyShapeStyle {
        switch emphasis {
        case .none: AnyShapeStyle(.primary)
        case .accent: AnyShapeStyle(.tint)
        case .failure: AnyShapeStyle(Color.fdStatusFailedText)
        }
    }
}

/// 一行等分的读数；Dynamic Type 放大到放不下时自动改为纵排。
struct StatRow: View {
    let cells: [StatCell]

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 12) {
                ForEach(cells.indices, id: \.self) { cells[$0].frame(maxWidth: .infinity, alignment: .leading) }
            }
            VStack(alignment: .leading, spacing: 12) {
                ForEach(cells.indices, id: \.self) { cells[$0] }
            }
        }
    }
}
