import SwiftUI

extension EnvironmentValues {
    /// 当前强调色预设（§3.D）。
    ///
    /// 根视图在 `.tint(accent.color)` 的同时注入本值：FluxUI 组件需要「强调色作文字」
    /// （`accent.text`，对比度 ≥ 4.5:1）与「强调色底上的文字」（`accent.onAccent`）时由此读取，
    /// 未注入时为默认品牌蓝。
    @Entry public var fluxAccent: FluxAccent = .blue
}
