import FluxDomain
import FluxUI
import SwiftUI

/// 一格大数字（数值 + 单位 + 说明）。
nonisolated struct TaskStatItem: Identifiable {
    let label: String
    let value: String
    let unit: String
    var tint: Color?

    var id: String { label }

    init(label: String, measure: Measure, tint: Color? = nil) {
        self.label = label
        value = measure.value
        unit = measure.unit
        self.tint = tint
    }

    init(label: String, value: String, unit: String = "", tint: Color? = nil) {
        self.label = label
        self.value = value
        self.unit = unit
        self.tint = tint
    }
}

/// 数值 + 单位：数值用品牌圆体大数字（随 Dynamic Type 缩放），单位次要色小字（01-foundations §4.2）。
struct TaskStatValue: View {
    let item: TaskStatItem
    var size: FluxStatSize = .hero

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text(item.value)
                .fluxStatNumber(size)
                .foregroundStyle(item.tint ?? Color.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .contentTransition(.numericText())
                .fluxAnimation(.smooth, value: item.value)
            if !item.unit.isEmpty {
                Text(item.unit)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
    }
}

/// 英雄区三栏统计（02-downloads §5.2）：≤ xxxLarge 三栏横排带竖分隔线；≥ AX1 单列纵排、去分隔线。
struct TaskHeroStats: View {
    let items: [TaskStatItem]
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        if typeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 12) {
                ForEach(items) { stat($0) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            HStack(alignment: .top, spacing: 0) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    if index > 0 {
                        Divider().padding(.horizontal, 10)
                    }
                    stat(item).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func stat(_ item: TaskStatItem) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            TaskStatValue(item: item)
            Text(item.label)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
    }
}

/// 圆角 22 的小磁贴三联（速度页 / 活动面板，01-foundations §5.1、§4.4：≥ AX1 单列）。
struct TaskStatTiles: View {
    let items: [TaskStatItem]
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 10))
        layout {
            ForEach(items) { item in
                VStack(alignment: .leading, spacing: 4) {
                    TaskStatValue(item: item, size: .tile)
                    Text(item.label)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: .rect(cornerRadius: 22, style: .continuous))
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// 可点按的键值行（带披露箭头），版式与 `KeyValueRow` 一致（键次要色 / 值右对齐，AX 纵排）。
struct TaskDetailLinkRow: View {
    let key: String
    let value: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            TaskDetailLinkLabel(key: key, value: value, showsChevron: true)
        }
        .buttonStyle(.plain)
    }
}

/// `TaskDetailLinkRow` 的内容版式；`NavigationLink` 行由系统自带披露箭头，传 `showsChevron: false`。
struct TaskDetailLinkLabel: View {
    let key: String
    let value: String
    var showsChevron = true
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 16))
        layout {
            Text(key).font(.subheadline).foregroundStyle(.secondary)
            if !typeSize.isAccessibilitySize { Spacer(minLength: 0) }
            HStack(spacing: 6) {
                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.trailing)
                if showsChevron {
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .contentShape(.rect)
    }
}

/// 把一段内容放进「清透、零内边距」的列表行（磁贴 / 横幅自带卡片底，不再叠一层分组底）。
extension View {
    func taskDetailBareRow() -> some View {
        listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}
