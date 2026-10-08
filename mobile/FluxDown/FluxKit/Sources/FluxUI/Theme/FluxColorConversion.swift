import SwiftUI
import UIKit

// 0xRRGGBB 与 `Color` / `UIColor` 的互转，以及「在某底色上的前景色」。颜色字面量只允许出现在 Theme 目录。

extension Color {
    /// 0xRRGGBB → sRGB 颜色。
    public init(rgb: UInt32) {
        self.init(
            .sRGB,
            red: Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >> 8) & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255,
            opacity: 1
        )
    }

    /// 解析为 0xRRGGBB（忽略 alpha）；无法解析 → nil。
    public var rgb: UInt32? { UIColor(self).rgb }

    /// 在 `fill` 底色上的前景色：白字对比 ≥ 3:1 用白，否则用黑。
    public static func fdOnColor(_ fill: Color) -> Color {
        guard let rgb = fill.rgb else { return .white }
        return Contrast.ratio(0xFFFFFF, rgb) >= 3 ? .white : .black
    }
}

extension UIColor {
    /// 解析为 0xRRGGBB（sRGB，忽略 alpha）。
    public var rgb: UInt32? {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        guard getRed(&r, green: &g, blue: &b, alpha: &a) else { return nil }
        func byte(_ v: CGFloat) -> UInt32 { UInt32((min(max(v, 0), 1) * 255).rounded()) }
        return (byte(r) << 16) | (byte(g) << 8) | byte(b)
    }
}
