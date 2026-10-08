import SwiftUI

/// 横幅语气，与 Toast 共用（info 取强调色 / warning 橙 / error 红 / success 绿）。
public typealias BannerTone = ToastTone

/// `Banner` 的操作按钮。
public struct BannerAction {
    public let title: String
    public let handler: () -> Void

    public init(title: String, handler: @escaping () -> Void) {
        self.title = title
        self.handler = handler
    }
}

/// 页内横幅（§9.13）：**内容层，不上玻璃**。
///
/// 圆角 22（`slim` 18），内边距 14/16；左侧 30×30（slim 26）圆角图标方块（实心语气色 + 白符号），
/// 文字 `.subheadline`，可选操作行（强调文字色、触控 ≥ 44）。底色 = 卡片色上叠语气色 α .12；
/// 增强对比度加 1pt 语气描边。语气用图标形状 + 文字双通道表达（不只靠颜色）。
public struct Banner: View {
    public let text: String
    public let tone: BannerTone
    public let systemImage: String?
    public let slim: Bool
    public let action: BannerAction?

    @Environment(\.fluxAccent) private var accent
    @Environment(\.colorSchemeContrast) private var contrast
    @ScaledMetric(relativeTo: .subheadline) private var iconSide: CGFloat = 30

    public init(
        text: String,
        tone: BannerTone = .info,
        systemImage: String? = nil,
        slim: Bool = false,
        action: BannerAction? = nil
    ) {
        self.text = text
        self.tone = tone
        self.systemImage = systemImage
        self.slim = slim
        self.action = action
    }

    private var side: CGFloat { min((slim ? iconSide * 26 / 30 : iconSide), 56) }
    private var radius: CGFloat { slim ? 18 : 22 }

    public var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage ?? tone.defaultSystemImage)
                .font(.system(size: side * 0.57, weight: .semibold))
                .foregroundStyle(tone.glyphColor(accent: accent))
                .frame(width: side, height: side)
                .background(tone.solidColor(accent: accent), in: .rect(cornerRadius: side * 0.3, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(text)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
                if let action {
                    Button(action.title, action: action.handler)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(accent.text)
                        .buttonStyle(.plain)
                        .frame(minHeight: 44, alignment: .leading)
                        .contentShape(.rect)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, slim ? 10 : 14)
        .background {
            shape.fill(Color(uiColor: .secondarySystemGroupedBackground))
            shape.fill(tone.solidColor(accent: accent).opacity(0.12))
        }
        .overlay {
            if contrast == .increased {
                shape.stroke(tone.solidColor(accent: accent), lineWidth: 1)
            }
        }
        .accessibilityElement(children: .contain)
    }
}

#Preview("Banner") {
    ScrollView {
        VStack(spacing: 12) {
            Banner(text: "Disconnected from host. Read-only until the connection is restored.", tone: .warning, systemImage: FluxSymbol.offline,
                   action: BannerAction(title: "Retry") {})
            Banner(text: "Boost is on for this task.", tone: .info)
            Banner(text: "Saved.", tone: .success, slim: true)
            Banner(text: "The plugin was disabled after repeated failures.", tone: .error,
                   action: BannerAction(title: "Re-enable") {})
        }
        .padding()
    }
    .background(Color(uiColor: .systemGroupedBackground))
}

#Preview("Banner · 深色 / AX3") {
    Banner(text: "Disconnected from host. Read-only until the connection is restored.", tone: .warning,
           action: BannerAction(title: "Retry") {})
        .padding()
        .background(Color(uiColor: .systemGroupedBackground))
        .preferredColorScheme(.dark)
        .dynamicTypeSize(.accessibility3)
}
