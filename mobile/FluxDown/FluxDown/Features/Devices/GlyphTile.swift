import SwiftUI

/// 圆角方块图标（订阅 / 设备 / 主机 / 搜索命令共用）：内容层的中性填充底 + SF Symbol，**不上玻璃**。
/// 纯装饰，对 VoiceOver 隐藏（含义由相邻文字承载）；边长随 Dynamic Type 缩放，封顶 2×。
struct GlyphTile: View {
    let systemImage: String
    let tint: Color
    /// 非空时叠一圈描边（失败态）。
    let ring: Color?
    let fill: Color

    private let baseSize: CGFloat
    @ScaledMetric private var scaledSize: CGFloat

    init(
        systemImage: String,
        tint: Color = .primary,
        ring: Color? = nil,
        fill: Color = Color(uiColor: .tertiarySystemFill),
        size: CGFloat = 40
    ) {
        self.systemImage = systemImage
        self.tint = tint
        self.ring = ring
        self.fill = fill
        baseSize = size
        _scaledSize = ScaledMetric(wrappedValue: size, relativeTo: .body)
    }

    private var side: CGFloat { min(scaledSize, baseSize * 2) }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: side * 0.28, style: .continuous)
        Image(systemName: systemImage)
            .font(.system(size: side * 0.48, weight: .medium))
            .foregroundStyle(tint)
            .frame(width: side, height: side)
            .background(fill, in: shape)
            .overlay {
                if let ring { shape.strokeBorder(ring, lineWidth: 1) }
            }
            .accessibilityHidden(true)
    }
}
