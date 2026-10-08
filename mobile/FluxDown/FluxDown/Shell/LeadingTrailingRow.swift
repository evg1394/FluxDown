import SwiftUI

/// 「左文右控件」行（系统「设置」的版式）：常规字号恒为左右排——左侧文字占剩余宽度并按需换行，右侧控件先取理想宽度；
/// 只有辅助功能字号（≥ AX1）才改为上下排。
///
/// 不用 `ViewThatFits`：它按「文字单行理想宽度」判断能否放下，说明文字稍长就整行改成上下排，
/// 同一分组里有的行左右、有的行上下（端口 / 计数这类行最明显）；文字随倒计时等变化时还会在两种版式间来回跳。
struct LeadingTrailingRow<Leading: View, Trailing: View>: View {
    private let alignment: VerticalAlignment
    private let spacing: CGFloat
    private let leading: Leading
    private let trailing: Trailing

    @Environment(\.dynamicTypeSize) private var typeSize

    init(
        alignment: VerticalAlignment = .center,
        spacing: CGFloat = 12,
        @ViewBuilder leading: () -> Leading,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.alignment = alignment
        self.spacing = spacing
        self.leading = leading()
        self.trailing = trailing()
    }

    var body: some View {
        if typeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 8) {
                leading
                trailing
            }
        } else {
            HStack(alignment: alignment, spacing: spacing) {
                leading.frame(maxWidth: .infinity, alignment: .leading)
                trailing.layoutPriority(1)
            }
        }
    }
}
