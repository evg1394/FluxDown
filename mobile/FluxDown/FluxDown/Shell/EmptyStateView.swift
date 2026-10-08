import FluxUI
import SwiftUI

/// 页面级空态（首次使用：还没有任何下载 / 订阅 / 推送目标）。
///
/// 版式与系统 `ContentUnavailableView` 同尺度（「文件」「提醒事项」的空态）：次要色单色符号 · 标题 · 说明 · 一个按钮，
/// 不加底圆 / 插画 / 第二按钮。差别只有两点：
/// - 符号出现时用 iOS 26 系统 `drawOn` 描画一次（不循环；减弱动态效果下直接显示）；
/// - 主动作是常规尺寸的 `.glassProminent` 胶囊（与工具栏 / 底栏主操作同一 Liquid Glass 语言）。
///
/// 搜索无结果 / 筛选为空 / 加载失败等「结果态」继续用系统 `ContentUnavailableView`。
struct EmptyStateView<Action: View>: View {
    let title: String
    let message: String?
    let systemImage: String
    @ViewBuilder var action: Action

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// false = 符号尚未描画（`drawOn` 激活时隐藏，取消激活即描画出现）。
    @State private var drawn = false

    init(_ title: String, message: String? = nil, systemImage: String, @ViewBuilder action: () -> Action) {
        self.title = title
        self.message = message
        self.systemImage = systemImage
        self.action = action()
    }

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: systemImage)
                .font(.system(size: 44, weight: .light))
                .imageScale(.medium)
                .foregroundStyle(.secondary)
                .symbolEffect(.drawOn.byLayer, options: .speed(0.9), isActive: !drawn)
                .accessibilityHidden(true)

            VStack(spacing: 6) {
                Text(title)
                    .font(.title3.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                if let message, !message.isEmpty {
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)

            action
                .buttonStyle(.glassProminent)
                .buttonBorderShape(.capsule)
                .padding(.top, 4)
        }
        .frame(maxWidth: 300)
        .padding(.horizontal, 32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task {
            guard !drawn else { return }
            if reduceMotion {
                drawn = true
                return
            }
            // 等切标签 / 推入转场结束再描画，否则动画在转场中途被看不见地播完。
            do {
                try await Task.sleep(for: .milliseconds(250))
            } catch {
                return
            }
            drawn = true
        }
    }
}
