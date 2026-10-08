import SwiftUI
import UIKit

// 色彩令牌（docs/mobile-ui/ios/01-foundations.md §3）。本文件是 FluxUI / App 中唯一允许出现颜色字面量的位置：
// 业务视图一律用 `Color.fd*`、系统语义色（`.primary` / `.secondary` / 分组背景）与 `.tint`。

extension Color {
    /// 浅 / 深两套值的动态色（随外观与「增强对比度」由系统切换 trait）。
    ///
    /// `nonisolated` + `@Sendable` 不是装饰：`UIColor(dynamicProvider:)` 的 block 会被 SwiftUI 渲染线程
    /// （`com.apple.SwiftUI.AsyncRenderer`）在非主线程解析颜色时调用；默认 MainActor 隔离下该闭包会被推断为
    /// MainActor 隔离，block 入口的 `dispatch_assert_queue(main)` 随即陷入断点。
    nonisolated static func fdDynamic(light: UInt32, dark: UInt32, lightAlpha: Double = 1, darkAlpha: Double = 1) -> Color {
        Color(uiColor: UIColor { @Sendable trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(rgb: dark, alpha: darkAlpha)
                : UIColor(rgb: light, alpha: lightAlpha)
        })
    }

    // MARK: 状态色（§3.B）——圆点 / 轨道 / 图标用基色

    /// 下载中 = 强调色（随用户 tint）。
    public nonisolated static var fdStatusDownloading: Color { .accentColor }
    public nonisolated static let fdStatusCompleted = fdDynamic(light: 0x8E8E93, dark: 0x98989D)
    public nonisolated static let fdStatusFailed = fdDynamic(light: 0xFF3B30, dark: 0xFF453A)
    public nonisolated static let fdStatusPaused = fdDynamic(light: 0x8E8E93, dark: 0x98989D)
    public nonisolated static let fdStatusQueued = fdDynamic(light: 0xAEAEB2, dark: 0x636366)
    public nonisolated static let fdStatusSeeding = fdDynamic(light: 0x34C759, dark: 0x30D158)
    public nonisolated static let fdStatusWarning = fdDynamic(light: 0xFF9500, dark: 0xFF9F0A)
    public nonisolated static let fdBoost = fdDynamic(light: 0x5856D6, dark: 0x5E5CE6)

    /// 开关「开」态底色：统一系统绿（不随用户强调色）。
    public nonisolated static let fdToggleOn = fdDynamic(light: 0x34C759, dark: 0x30D158)

    // MARK: 状态文字（§3.E）——状态词作文字时用加深变体（对白 / 深色卡片 ≥ 4.5:1）

    public nonisolated static let fdStatusFailedText = fdDynamic(light: 0xD13027, dark: 0xFF5950)
    public nonisolated static let fdStatusSeedingText = fdDynamic(light: 0x217F39, dark: 0x30D158)
    public nonisolated static let fdStatusWarningText = fdDynamic(light: 0xA15E00, dark: 0xFF9F0A)
    public nonisolated static let fdStatusNeutralText = fdDynamic(light: 0x6D6D71, dark: 0x98989D)

    /// 进度轨道：light rgb(113 113 122 / .16)，dark rgb(161 161 166 / .20)。
    public nonisolated static let fdProgressTrack = fdDynamic(light: 0x71717A, dark: 0xA1A1A6, lightAlpha: 0.16, darkAlpha: 0.20)
    /// 增强对比度下的进度轨道（§2.7：α 提高到 .3）。
    public nonisolated static let fdProgressTrackIncreased = fdDynamic(light: 0x71717A, dark: 0xA1A1A6, lightAlpha: 0.30, darkAlpha: 0.32)

    // MARK: 实心语气色（§3.E）——白色符号 / 文字叠在其上，对比度 ≥ 4.5:1（浅深两套一致，不随外观变亮）

    public nonisolated static let fdToneSolidSuccess = fdDynamic(light: 0x217F39, dark: 0x217F39)
    public nonisolated static let fdToneSolidWarning = fdDynamic(light: 0xA15E00, dark: 0xA15E00)
    public nonisolated static let fdToneSolidError = fdDynamic(light: 0xD13027, dark: 0xD13027)

    /// 范围条选中药丸（§9.5）：light #FFFFFF / dark rgb(120 120 128 / .45)。
    public nonisolated static let fdScopePill = fdDynamic(light: 0xFFFFFF, dark: 0x787880, darkAlpha: 0.45)

    // MARK: 类别色（§3.C）

    public nonisolated static let fdKindVideo = fdDynamic(light: 0xAF52DE, dark: 0xBF5AF2)
    public nonisolated static let fdKindAudio = fdDynamic(light: 0xFF2D55, dark: 0xFF375F)
    /// 文档固定品牌蓝，不随强调色。
    public nonisolated static let fdKindDocument = fdDynamic(light: 0x3B82F6, dark: 0x3B82F6)
    public nonisolated static let fdKindImage = fdDynamic(light: 0xFF9500, dark: 0xFF9F0A)
    public nonisolated static let fdKindProgram = fdDynamic(light: 0x34C759, dark: 0x30D158)
    public nonisolated static let fdKindArchive = fdDynamic(light: 0xA2845E, dark: 0xAC8E68)
    public nonisolated static let fdKindEbook = fdDynamic(light: 0x30B0C7, dark: 0x40C8E0)
    public nonisolated static let fdKindOther = fdDynamic(light: 0x8E8E93, dark: 0x8E8E93)
}

extension UIColor {
    nonisolated convenience init(rgb: UInt32, alpha: Double = 1) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha
        )
    }
}

/// 强调色（§3.D）：预设 + 自定义；`accentText` / `onAccent` 由对比度算法推导。
public nonisolated struct FluxAccent: Sendable, Hashable, Identifiable {
    /// `appearance.color_scheme` 的 wire 值（blue / green / violet / rose / orange / indigo / custom）。
    public let id: String
    public let rgb: UInt32

    public init(id: String, rgb: UInt32) {
        self.id = id
        self.rgb = rgb
    }

    /// 默认 FluxDown 蓝 #3B82F6。
    public static let blue = FluxAccent(id: "blue", rgb: 0x3B82F6)
    public static let presets: [FluxAccent] = [
        .blue,
        FluxAccent(id: "green", rgb: 0x34C759),
        FluxAccent(id: "violet", rgb: 0xAF52DE),
        FluxAccent(id: "rose", rgb: 0xFF2D55),
        FluxAccent(id: "orange", rgb: 0xFF9500),
        FluxAccent(id: "indigo", rgb: 0x6366F1),
    ]

    public static func preset(_ id: String) -> FluxAccent { presets.first { $0.id == id } ?? .blue }

    public var color: Color { Color(uiColor: UIColor(rgb: rgb)) }

    /// 强调色作文字 / 链接：相对卡片与页面底的最差对比 ≥ 4.5:1（默认蓝直接用 PC token 值）。
    public var text: Color {
        if self == .blue { return Color.fdDynamic(light: 0x1E6FF5, dark: 0x5392F7) }
        let light = Contrast.ensure(rgb, against: [0xFFFFFF, 0xF2F2F7], target: 4.5, dark: false)
        let dark = Contrast.ensure(rgb, against: [0x1C1C1E, 0x2C2C2E, 0x000000], target: 4.5, dark: true)
        return Color.fdDynamic(light: light, dark: dark)
    }

    /// 强调色底上的文字：白字对强调色 ≥ 3:1 用白，否则用黑。
    public var onAccent: Color {
        Contrast.ratio(0xFFFFFF, rgb) >= 3 ? .white : .black
    }
}

/// WCAG 2.x 相对亮度与对比度。
public nonisolated enum Contrast {
    static func channel(_ c: Double) -> Double {
        c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
    }

    static func components(_ rgb: UInt32) -> SIMD3<Double> {
        SIMD3(Double((rgb >> 16) & 0xFF), Double((rgb >> 8) & 0xFF), Double(rgb & 0xFF)) / 255
    }

    static func pack(_ c: SIMD3<Double>) -> UInt32 {
        let r = UInt32((min(max(c.x, 0), 1) * 255).rounded())
        let g = UInt32((min(max(c.y, 0), 1) * 255).rounded())
        let b = UInt32((min(max(c.z, 0), 1) * 255).rounded())
        return (r << 16) | (g << 8) | b
    }

    static func luminance(_ c: SIMD3<Double>) -> Double {
        0.2126 * channel(c.x) + 0.7152 * channel(c.y) + 0.0722 * channel(c.z)
    }

    public static func ratio(_ a: UInt32, _ b: UInt32) -> Double {
        let la = luminance(components(a))
        let lb = luminance(components(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// 向黑（浅色）/ 白（深色）线性混合，步长 0.01，直到对全部底色达到目标对比度。
    public static func ensure(_ rgb: UInt32, against surfaces: [UInt32], target: Double, dark: Bool) -> UInt32 {
        let accent = components(rgb)
        let toward: SIMD3<Double> = dark ? SIMD3(1, 1, 1) : SIMD3(0, 0, 0)
        for step in 0...100 {
            let candidate = pack(accent + (toward - accent) * (Double(step) / 100))
            if surfaces.allSatisfy({ ratio(candidate, $0) >= target }) { return candidate }
        }
        return pack(toward)
    }
}
