import SwiftUI

// 功能层玻璃的统一入口（§2.3 / §2.7）：仅 Toast、范围条等「自绘件」使用。
// 降低透明度 → 不透明 `secondarySystemBackground` + 发丝描边（+ 可选投影，保证与同色底可分辨）；
// 增强对比度 → 1pt `separator` 描边。

struct FluxGlassModifier<S: Shape>: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    let shape: S
    /// 悬浮件（Toast）需要投影；贴在内容里的范围条不需要。
    let floating: Bool
    /// 出现时使用 `.materialize`（须在 `GlassEffectContainer` 内）。
    let materialize: Bool

    func body(content: Content) -> some View {
        if reduceTransparency {
            content
                .background(Color(uiColor: .secondarySystemBackground), in: shape)
                .overlay { shape.stroke(Color(uiColor: .separator), lineWidth: 1) }
                .shadow(color: .black.opacity(floating ? 0.12 : 0), radius: 8, y: 2)
        } else if materialize {
            content
                .glassEffect(.regular, in: shape)
                .glassEffectTransition(.materialize)
                .overlay { contrastStroke }
        } else {
            content
                .glassEffect(.regular, in: shape)
                .overlay { contrastStroke }
        }
    }

    @ViewBuilder private var contrastStroke: some View {
        if contrast == .increased {
            shape.stroke(Color(uiColor: .separator), lineWidth: 1)
        }
    }
}

extension View {
    func fluxGlass<S: Shape>(in shape: S, floating: Bool = false, materialize: Bool = false) -> some View {
        modifier(FluxGlassModifier(shape: shape, floating: floating, materialize: materialize))
    }
}
