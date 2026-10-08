import SwiftUI

// 动效令牌（docs/mobile-ui/ios/01-foundations.md §7）。
// 只用弹簧；唯一例外是数据插值（进度环 `.linear(0.9)`）与加载 / 品牌循环（呼吸、自旋、横扫）。

extension Animation {
    /// 页面推入 / 弹出、Sheet、Dock 显隐（0.5s，无过冲）。
    public nonisolated static let fluxSmooth: Animation = .smooth
    /// 按压回弹、开关滑块、折叠箭头、勾选（bounce .15）。
    public nonisolated static let fluxSnappy: Animation = .snappy
    /// 范围条药丸、菜单 / Toast 出现（bounce .30）。
    public nonisolated static let fluxBouncy: Animation = .bouncy
    /// 短距离位移、被挡回（0.3s，无过冲）。
    public nonisolated static let fluxRigid: Animation = .spring(duration: 0.3, bounce: 0)
    /// 手指跟随（拖动、擦洗）。
    public nonisolated static let fluxInteractive: Animation = .interactiveSpring(response: 0.15, dampingFraction: 0.86, blendDuration: 0.25)
}

/// 弹簧 token 的枚举形态，用于按「减弱动态效果」选择实际动画（§7.3）。
public nonisolated enum FluxMotion: Sendable, Hashable, CaseIterable {
    case smooth, snappy, bouncy, rigid, interactive

    /// 减弱动态效果下：有过冲的弹簧（snappy / bouncy / rigid）一律退回无过冲的 `.smooth`；
    /// 手指跟随的 `interactive` 保持不变（它不产生装饰性位移）。
    public func animation(reduceMotion: Bool) -> Animation {
        switch self {
        case .smooth: .fluxSmooth
        case .snappy: reduceMotion ? .fluxSmooth : .fluxSnappy
        case .bouncy: reduceMotion ? .fluxSmooth : .fluxBouncy
        case .rigid: reduceMotion ? .fluxSmooth : .fluxRigid
        case .interactive: .fluxInteractive
        }
    }
}

/// 在显式给出 `reduceMotion` 时执行带动画的状态变更（事件回调等拿不到 `@Environment` 的位置使用）。
@discardableResult
public func withFluxAnimation<Result>(
    _ motion: FluxMotion,
    reduceMotion: Bool,
    _ body: () throws -> Result
) rethrows -> Result {
    try withAnimation(motion.animation(reduceMotion: reduceMotion), body)
}

private struct FluxAnimationModifier<Value: Equatable>: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let motion: FluxMotion
    let value: Value

    func body(content: Content) -> some View {
        content.animation(motion.animation(reduceMotion: reduceMotion), value: value)
    }
}

extension View {
    /// `animation(_:value:)` 的减弱动态感知版：读取环境，按 `FluxMotion` 解析实际弹簧。
    public func fluxAnimation<Value: Equatable>(_ motion: FluxMotion, value: Value) -> some View {
        modifier(FluxAnimationModifier(motion: motion, value: value))
    }
}

/// 按压缩放按钮样式（§7.2：圆环 0.88、普通按钮 0.96，`.snappy`）。
public struct FluxPressScaleStyle: ButtonStyle {
    public var scale: CGFloat

    public init(scale: CGFloat = 0.96) {
        self.scale = scale
    }

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(.fluxSnappy, value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == FluxPressScaleStyle {
    /// `buttonStyle(.fluxPress(scale: 0.88))`。
    public static func fluxPress(scale: CGFloat = 0.96) -> FluxPressScaleStyle {
        FluxPressScaleStyle(scale: scale)
    }
}
