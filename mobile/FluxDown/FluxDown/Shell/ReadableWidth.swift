import SwiftUI

/// 版式常量与可读宽度（iPad / 宽窗口 / 台前调度下内容不随窗口无限拉宽）。
nonisolated enum FluxLayout {
    /// 表单、详情等阅读型内容的最大行宽（≈ 系统 readableContentGuide 在常用字号下的宽度）。
    static let readableWidth: CGFloat = 720
}

extension View {
    /// 滚动内容（`List` / `Form` / `ScrollView`）的可读宽度：容器宽于 `maxWidth` 时两侧加等量内容边距居中，
    /// 窄屏（iPhone / 分栏中的窄列）不变。只改 `.scrollContent` 边距，滚动指示器与系统栏仍贴窗口边缘。
    func readableContentWidth(_ maxWidth: CGFloat = FluxLayout.readableWidth) -> some View {
        modifier(ReadableContentWidth(maxWidth: maxWidth))
    }
}

private struct ReadableContentWidth: ViewModifier {
    let maxWidth: CGFloat
    @State private var width: CGFloat = 0

    func body(content: Content) -> some View {
        let inset = (width - maxWidth) / 2
        content
            .contentMargins(.horizontal, inset > 0 ? inset : nil, for: .scrollContent)
            .onGeometryChange(for: CGFloat.self) { proxy in
                proxy.size.width
            } action: { newWidth in
                width = newWidth
            }
    }
}
