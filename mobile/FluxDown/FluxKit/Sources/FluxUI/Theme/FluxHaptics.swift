import SwiftUI
import UIKit

/// 触感种类（§8.1）。
///
/// - 绑定到视图状态变化：`.sensoryFeedback(kind.sensoryFeedback, trigger:)`。
/// - 来自 Store 事件（网络回调）等无视图状态可绑的位置：`kind.play()`。
///
/// 系统组件（Toggle / Picker / Menu / TabView / swipeActions…）自带触感，不得重复触发（§8.2）。
public nonisolated enum FluxHaptic: Sendable, Hashable, CaseIterable {
    case light, medium, soft, rigid, selection, success, warning, error

    /// 对应的 SwiftUI `SensoryFeedback`。
    public var sensoryFeedback: SensoryFeedback {
        switch self {
        case .light: .impact(weight: .light)
        case .medium: .impact(weight: .medium)
        case .soft: .impact(flexibility: .soft)
        case .rigid: .impact(flexibility: .rigid)
        case .selection: .selection
        case .success: .success
        case .warning: .warning
        case .error: .error
        }
    }

    /// 立即触发（UIKit 发生器；遵守系统「系统触感」开关）。
    @MainActor
    public func play() {
        switch self {
        case .light: UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .medium: UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case .soft: UIImpactFeedbackGenerator(style: .soft).impactOccurred()
        case .rigid: UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
        case .selection: UISelectionFeedbackGenerator().selectionChanged()
        case .success: UINotificationFeedbackGenerator().notificationOccurred(.success)
        case .warning: UINotificationFeedbackGenerator().notificationOccurred(.warning)
        case .error: UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
    }
}
