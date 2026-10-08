import Accessibility
import Observation
import SwiftUI

/// Toast / Banner 的语气（§9.14）。
public nonisolated enum ToastTone: Sendable, Hashable, CaseIterable {
    case success, warning, error, info

    /// 默认 SF Symbol（§6.2：check / 三角 / 叹号圆 / info）。
    public var defaultSystemImage: String {
        switch self {
        case .success: "checkmark"
        case .warning: "exclamationmark.triangle.fill"
        case .error: "exclamationmark"
        case .info: "info"
        }
    }

    /// 实心图标底色（白色符号叠其上，≥ 4.5:1）。`info` 取强调色，符号色由 `FluxAccent.onAccent` 推导。
    func solidColor(accent: FluxAccent) -> Color {
        switch self {
        case .success: .fdToneSolidSuccess
        case .warning: .fdToneSolidWarning
        case .error: .fdToneSolidError
        case .info: accent.color
        }
    }

    func glyphColor(accent: FluxAccent) -> Color {
        self == .info ? accent.onAccent : .white
    }

    var sensoryFeedback: SensoryFeedback {
        switch self {
        case .success: FluxHaptic.success.sensoryFeedback
        case .warning: FluxHaptic.warning.sensoryFeedback
        case .error: FluxHaptic.error.sensoryFeedback
        case .info: FluxHaptic.soft.sensoryFeedback
        }
    }
}

/// 一条 Toast。相等性只看 `id`。
///
/// `nonisolated` + `Sendable`：`.animation(value: center.current)` / `.sensoryFeedback(trigger: center.current)`
/// 会在 SwiftUI 渲染线程比较 `ToastItem?`，`==` 不能是 MainActor 隔离。`action` 因此标成 `@MainActor @Sendable`：
/// 闭包本身只在主线程跑（Button 回调），但类型不再把整个结构体拖成 MainActor。
public nonisolated struct ToastItem: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let text: String
    public let tone: ToastTone
    public let systemImage: String
    /// 可选操作按钮标题（如「撤销」）。
    public let actionTitle: String?
    public let action: (@MainActor @Sendable () -> Void)?

    public init(
        id: UUID = UUID(),
        text: String,
        tone: ToastTone = .info,
        systemImage: String? = nil,
        actionTitle: String? = nil,
        action: (@MainActor @Sendable () -> Void)? = nil
    ) {
        self.id = id
        self.text = text
        self.tone = tone
        self.systemImage = systemImage ?? tone.defaultSystemImage
        self.actionTitle = actionTitle
        self.action = action
    }

    public static func == (lhs: ToastItem, rhs: ToastItem) -> Bool { lhs.id == rhs.id }
}

/// Toast 队列与定时（§9.14；04-rss-devices §0.2）。App 持有一个实例，根视图 `.toastHost(center)`。
///
/// 展示策略：
/// - **自动消失**：非错误 Toast 停留 `autoDismissDelay`（默认 2.5s）；**错误 Toast 不自动消失**，
///   直到用户点按 Toast 或关闭按钮（`dismiss()`）。
/// - **替换**：当前是非错误 Toast 时，新 Toast 立即替换它（计时重置）；相同内容连续触发也会重新计时。
/// - **排队**：当前是错误 Toast 时，新 Toast 进入 FIFO 队列（最多 `maxQueued` 条，超出丢弃最旧的），
///   待错误被关闭后依次展示 —— 错误必须被确认，后到的提示不能把它顶掉。
@Observable
@MainActor
public final class ToastCenter {
    /// 当前显示的 Toast。
    public private(set) var current: ToastItem?

    /// 非错误 Toast 的停留时长。
    public var autoDismissDelay: Duration = .milliseconds(2500)
    /// 错误展示期间最多排队条数。
    public var maxQueued = 5

    @ObservationIgnored private var queue: [ToastItem] = []
    @ObservationIgnored private var dismissTask: Task<Void, Never>?

    public init() {}

    /// 展示一条 Toast；`systemImage == nil` 时按语气取默认符号。
    public func show(
        text: String,
        tone: ToastTone = .info,
        systemImage: String? = nil,
        actionTitle: String? = nil,
        action: (@MainActor @Sendable () -> Void)? = nil
    ) {
        show(ToastItem(text: text, tone: tone, systemImage: systemImage, actionTitle: actionTitle, action: action))
    }

    public func show(_ item: ToastItem) {
        if let current, current.tone == .error {
            queue.append(item)
            if queue.count > maxQueued { queue.removeFirst(queue.count - maxQueued) }
            return
        }
        present(item)
    }

    /// 关闭当前 Toast（用户点按 / 关闭按钮）；若队列里有待显示项则接着展示。
    public func dismiss() {
        dismissTask?.cancel()
        dismissTask = nil
        current = nil
        if !queue.isEmpty { present(queue.removeFirst()) }
    }

    /// 仅当 `id` 仍是当前项时关闭（供定时器使用，避免误关新 Toast）。
    private func dismiss(id: UUID) {
        guard current?.id == id else { return }
        dismiss()
    }

    private func present(_ item: ToastItem) {
        dismissTask?.cancel()
        dismissTask = nil
        current = item
        guard item.tone != .error else { return }
        let delay = autoDismissDelay
        dismissTask = Task { [weak self] in
            do {
                try await Task.sleep(for: delay)
            } catch {
                return
            }
            self?.dismiss(id: item.id)
        }
    }
}

// MARK: - 展示

struct ToastView: View {
    let item: ToastItem
    let dismissLabel: String
    let onDismiss: () -> Void
    /// 胶囊在窗口坐标系中的位置；透传窗口据此决定哪些触摸属于 Toast。空闲 / 消失时为 `.zero`。
    var onFrameChange: (CGRect) -> Void = { _ in }

    @Environment(\.fluxAccent) private var accent
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .subheadline) private var iconSize: CGFloat = 22

    var body: some View {
        let content = HStack(spacing: 10) {
            Image(systemName: item.systemImage)
                .font(.system(size: iconSize * 0.6, weight: .bold))
                .foregroundStyle(item.tone.glyphColor(accent: accent))
                .frame(width: iconSize, height: iconSize)
                .background(item.tone.solidColor(accent: accent), in: .circle)
                .accessibilityHidden(true)
            Text(item.text)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.primary)
                .multilineTextAlignment(.leading)
            if let title = item.actionTitle, let action = item.action {
                Button(title) {
                    action()
                    onDismiss()
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(accent.text)
                .buttonStyle(.plain)
                .frame(minHeight: 44)
            }
            if item.tone == .error {
                Button(action: onDismiss) {
                    Image(systemName: FluxSymbol.close)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 28, minHeight: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(dismissLabel)
            }
        }
        .padding(.vertical, 12)
        .padding(.leading, 14)
        .padding(.trailing, item.tone == .error ? 12 : 20)
        .frame(minWidth: 150, maxWidth: 350)

        content
            .fluxGlass(in: .capsule, floating: true, materialize: !reduceMotion)
            .contentShape(.capsule)
            .onTapGesture(perform: onDismiss)
            .transition(.opacity)
            .accessibilityElement(children: .contain)
            .accessibilityAction(named: dismissLabel, onDismiss)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { onFrameChange($0) }
            .onDisappear { onFrameChange(.zero) }
    }
}

private struct ToastHostModifier: ViewModifier {
    let center: ToastCenter
    let dismissLabel: String

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.layoutDirection) private var layoutDirection
    @Environment(\.locale) private var locale
    @Environment(\.fluxAccent) private var accent

    func body(content: Content) -> some View {
        content.background {
            ToastWindowAnchor(
                center: center,
                configuration: ToastOverlayConfiguration(
                    dismissLabel: dismissLabel,
                    accent: accent,
                    colorScheme: colorScheme,
                    dynamicTypeSize: dynamicTypeSize,
                    layoutDirection: layoutDirection,
                    locale: locale
                )
            )
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
        }
    }
}

extension View {
    /// 安装 Toast 宿主：Toast 渲染在一块独立的透传 `UIWindow` 里，**始终在 Sheet / fullScreenCover / alert 之上**。
    ///
    /// - 在 App 根视图上调用一次（`toastHost` 是唯一集成点）。窗口按 `UIWindowScene` 创建，
    ///   随宿主视图出现 / 消失安装 / 移除；同一场景里重复调用共用一块窗口（引用计数）。
    /// - 窗口级别高于 `.normal`，不成为 key window，只有胶囊本身响应点按，其余区域触摸透传到下层。
    ///   空闲（无 Toast）时窗口隐藏。
    /// - 外观跟随宿主视图所在环境：`colorScheme`（含 `preferredColorScheme`）、`dynamicTypeSize`、
    ///   `layoutDirection`、`locale`、`fluxAccent`（同时作为 `.tint`）。因此请把 `.preferredColorScheme` /
    ///   `.environment(\.fluxAccent, …)` 设在 `toastHost` **之上或同一视图**。系统级偏好（降低透明度、增强对比度、
    ///   减弱动态效果）由窗口的 trait / 环境直接给出。
    /// - 位置取 04-rss-devices §0.2 的「顶部玻璃胶囊」（避开标签栏 + 活动条附件），最大宽 350，
    ///   玻璃为 `glassEffect(.regular, in: .capsule)` + `.materialize` 出现；降低透明度 → 不透明底；
    ///   增强对比度 → 1pt 描边；减弱动态效果 → 仅淡入。出现时触发语气对应触感并向 VoiceOver 播报（不抢焦点）。
    ///
    /// - Parameter dismissLabel: 关闭按钮 / 关闭动作的 VoiceOver 名称（调用方本地化）。
    public func toastHost(_ center: ToastCenter, dismissLabel: String = "Dismiss") -> some View {
        modifier(ToastHostModifier(center: center, dismissLabel: dismissLabel))
    }
}

#Preview("Toast") {
    struct Demo: View {
        @State private var center = ToastCenter()
        var body: some View {
            List {
                Button("Success") { center.show(text: "Link copied", tone: .success) }
                Button("Info") { center.show(text: "Added to queue", tone: .info) }
                Button("Warning") { center.show(text: "Network switched to cellular", tone: .warning) }
                Button("Error (sticky)") { center.show(text: "Failed to add: unsupported URL scheme", tone: .error) }
                Button("Action") {
                    center.show(text: "Endpoint deleted", tone: .info, systemImage: FluxSymbol.delete, actionTitle: "Undo") {}
                }
            }
            .toastHost(center)
        }
    }
    return Demo()
}

#Preview("Toast · 深色 / AX3") {
    struct Demo: View {
        @State private var center = ToastCenter()
        var body: some View {
            Color(uiColor: .systemGroupedBackground)
                .ignoresSafeArea()
                .toastHost(center)
                .task { center.show(text: "Failed to add: unsupported URL scheme", tone: .error) }
        }
    }
    return Demo()
        .preferredColorScheme(.dark)
        .dynamicTypeSize(.accessibility3)
}
