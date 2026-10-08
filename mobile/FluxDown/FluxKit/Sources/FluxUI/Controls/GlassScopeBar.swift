import SwiftUI

/// 范围条计数的文字语气（§9.5：失败计数用加深失败色，下载中计数用 accentText）。
public nonisolated enum ScopeCountTone: Sendable, Hashable {
    case secondary, accent, failure
}

/// `GlassScopeBar` 的一项。
public nonisolated struct ScopeItem<ID: Hashable>: Identifiable {
    public let id: ID
    public let title: String
    /// 计数；`nil` 或 0 不显示。
    public let count: Int?
    /// 可选前置图标（SF Symbol）。
    public let systemImage: String?
    public let countTone: ScopeCountTone

    public init(id: ID, title: String, count: Int? = nil, systemImage: String? = nil, countTone: ScopeCountTone = .secondary) {
        self.id = id
        self.title = title
        self.count = count
        self.systemImage = systemImage
        self.countTone = countTone
    }
}

/// 范围条（§9.5）：玻璃胶囊容器（内边距 3，项高 36）+ 非玻璃选中药丸（`matchedGeometryEffect` + `.bouncy`）。
///
/// - 只有容器是玻璃（`glassEffect(.regular, in: .capsule)`）；药丸是内容层的 `fdScopePill`，不叠玻璃。
/// - 可横向滚动，选中项自动滚入可见区；选中变化触发 `.selection` 触感。
/// - ≥ AX3 退化为系统 `Menu`（`.glass` 按钮样式）选择。
/// - 降低透明度：容器变为不透明 `secondarySystemBackground` + 发丝描边；增强对比度：药丸加描边。
/// - VoiceOver：每项 label = 标题、value = 计数，选中项带 `isSelected`。
public struct GlassScopeBar<Selection: Hashable>: View {
    public let items: [ScopeItem<Selection>]
    @Binding public var selection: Selection

    @Namespace private var pillNamespace
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.fluxAccent) private var accent

    public init(items: [ScopeItem<Selection>], selection: Binding<Selection>) {
        self.items = items
        _selection = selection
    }

    public var body: some View {
        Group {
            if typeSize >= .accessibility3 { menuForm } else { barForm }
        }
        .sensoryFeedback(FluxHaptic.selection.sensoryFeedback, trigger: selection)
    }

    // MARK: 条形态

    private var barForm: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 0) {
                    ForEach(items) { item in segment(item) }
                }
                .padding(3)
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)
            .clipShape(.capsule)
            .fluxGlass(in: .capsule)
            .fluxAnimation(.bouncy, value: selection)
            .onChange(of: selection) { _, newValue in
                withAnimation(.fluxSmooth) { proxy.scrollTo(newValue, anchor: .center) }
            }
        }
    }

    private func segment(_ item: ScopeItem<Selection>) -> some View {
        let isSelected = item.id == selection
        return Button {
            selection = item.id
        } label: {
            label(for: item, isSelected: isSelected)
                .padding(.horizontal, 13)
                .frame(minHeight: 36)
                .background {
                    if isSelected {
                        Capsule()
                            .fill(Color.fdScopePill)
                            .shadow(color: .black.opacity(0.14), radius: 4, y: 2)
                            .overlay {
                                if contrast == .increased {
                                    Capsule().stroke(Color(uiColor: .separator), lineWidth: 1)
                                }
                            }
                            .matchedGeometryEffect(id: "pill", in: pillNamespace)
                    }
                }
                .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .id(item.id)
        .accessibilityLabel(item.title)
        .accessibilityValue(valueText(item))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func label(for item: ScopeItem<Selection>, isSelected: Bool) -> some View {
        HStack(spacing: 5) {
            if let symbol = item.systemImage {
                Image(systemName: symbol)
            }
            Text(item.title)
            if let count = item.count, count > 0 {
                Text(count, format: .number)
                    .font(.caption)
                    .foregroundStyle(countStyle(item.countTone, isSelected: isSelected))
            }
        }
        .font(.subheadline.weight(isSelected ? .semibold : .medium))
        .monospacedDigit()
        .foregroundStyle(isSelected ? Color.primary : Color.secondary)
        .lineLimit(1)
        .fixedSize()
    }

    private func countStyle(_ tone: ScopeCountTone, isSelected: Bool) -> Color {
        switch tone {
        case .secondary: isSelected ? .primary : .secondary
        case .accent: accent.text
        case .failure: .fdStatusFailedText
        }
    }

    private func valueText(_ item: ScopeItem<Selection>) -> String {
        guard let count = item.count, count > 0 else { return "" }
        return count.formatted()
    }

    // MARK: ≥ AX3：Menu 选择

    private var menuForm: some View {
        Menu {
            Picker(selection: $selection) {
                ForEach(items) { item in
                    Text(menuTitle(item)).tag(item.id)
                }
            } label: {
                EmptyView()
            }
        } label: {
            HStack(spacing: 8) {
                if let current = items.first(where: { $0.id == selection }) {
                    Text(menuTitle(current)).monospacedDigit()
                }
                Image(systemName: "chevron.up.chevron.down").imageScale(.small)
            }
            .font(.subheadline.weight(.semibold))
            .frame(minHeight: 44)
        }
        .buttonStyle(.glass)
    }

    private func menuTitle(_ item: ScopeItem<Selection>) -> String {
        guard let count = item.count, count > 0 else { return item.title }
        return "\(item.title) \(count.formatted())"
    }
}

#Preview("GlassScopeBar") {
    struct Demo: View {
        @State private var selection = "all"
        var body: some View {
            VStack(spacing: 24) {
                GlassScopeBar(
                    items: [
                        ScopeItem(id: "all", title: "All", count: 24),
                        ScopeItem(id: "down", title: "Downloading", count: 3, countTone: .accent),
                        ScopeItem(id: "done", title: "Completed", count: 18),
                        ScopeItem(id: "fail", title: "Failed", count: 2, countTone: .failure),
                        ScopeItem(id: "paused", title: "Paused", count: 1),
                    ],
                    selection: $selection
                )
                .padding(.horizontal)
                Text(selection).font(.caption).foregroundStyle(.secondary)
            }
            .padding(.vertical, 40)
            .background(Color(uiColor: .systemGroupedBackground))
        }
    }
    return Demo()
}

#Preview("GlassScopeBar · 深色 / AX3 Menu") {
    struct Demo: View {
        @State private var selection = 1
        var body: some View {
            GlassScopeBar(
                items: [
                    ScopeItem(id: 0, title: "All", count: 24),
                    ScopeItem(id: 1, title: "Downloading", count: 3, countTone: .accent),
                    ScopeItem(id: 2, title: "Failed", count: 2, countTone: .failure),
                ],
                selection: $selection
            )
            .padding()
            .background(Color(uiColor: .systemGroupedBackground))
            .dynamicTypeSize(.accessibility3)
            .preferredColorScheme(.dark)
        }
    }
    return Demo()
}
