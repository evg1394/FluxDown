import SwiftUI

/// 徽标语气（§9.16）。底色 = 语气基色 α .15，文字 = §3.E 加深文字变体。
public nonisolated enum BadgeTone: Sendable, Hashable, CaseIterable {
    case accent, neutral, success, warning, failure

    /// 语气基色（底色 / 描边用）。
    public var color: Color {
        switch self {
        case .accent: .accentColor
        case .neutral: .fdStatusCompleted
        case .success: .fdStatusSeeding
        case .warning: .fdStatusWarning
        case .failure: .fdStatusFailed
        }
    }

    /// 作文字的加深变体（对白 / 深色卡片 ≥ 4.5:1）。强调色文字由当前 `FluxAccent` 推导。
    public func textColor(accent: FluxAccent = .blue) -> Color {
        switch self {
        case .accent: accent.text
        case .neutral: .fdStatusNeutralText
        case .success: .fdStatusSeedingText
        case .warning: .fdStatusWarningText
        case .failure: .fdStatusFailedText
        }
    }
}

extension SegmentTone {
    /// 任务状态语气对应的徽标语气。
    public var badgeTone: BadgeTone {
        switch self {
        case .downloading: .accent
        case .paused, .queued, .completed: .neutral
        case .failed: .failure
        case .seeding: .success
        }
    }
}

/// 状态徽标（§9.16）：高 20 的小胶囊，caption2 semibold，可带前置图标；
/// Dynamic Type 封顶 `.xxxLarge`；增强对比度加 1pt 描边；状态靠「文字 + 可选图标」表达，不只靠颜色。
public struct StatusBadge: View {
    public let text: String
    public let tone: BadgeTone
    public let systemImage: String?

    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.fluxAccent) private var accent

    public init(text: String, tone: BadgeTone = .neutral, systemImage: String? = nil) {
        self.text = text
        self.tone = tone
        self.systemImage = systemImage
    }

    public var body: some View {
        HStack(spacing: 3) {
            if let systemImage {
                Image(systemName: systemImage).imageScale(.small)
            }
            Text(text)
        }
        .font(.fluxBadge)
        .foregroundStyle(tone.textColor(accent: accent))
        .lineLimit(1)
        .padding(.horizontal, 7)
        .frame(minHeight: 20)
        .background(tone.color.opacity(0.15), in: .rect(cornerRadius: 6, style: .continuous))
        .overlay {
            if contrast == .increased {
                RoundedRectangle(cornerRadius: 6, style: .continuous).stroke(tone.color, lineWidth: 1)
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
}

/// 协议徽标（`HTTP / BT / ED2K / FTP / HLS`）：中性灰底小胶囊，文字用 `fdStatusNeutralText`（≥ 4.5:1）。
public struct ProtocolBadge: View {
    public let text: String

    @Environment(\.colorSchemeContrast) private var contrast

    public init(text: String) {
        self.text = text
    }

    public var body: some View {
        Text(text)
            .font(.fluxBadge)
            .foregroundStyle(Color.fdStatusNeutralText)
            .lineLimit(1)
            .padding(.horizontal, 7)
            .frame(minHeight: 20)
            .background(Color(uiColor: .tertiarySystemFill), in: .capsule)
            .overlay {
                if contrast == .increased {
                    Capsule().stroke(Color(uiColor: .separator), lineWidth: 1)
                }
            }
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
}

#Preview("StatusBadge / ProtocolBadge") {
    VStack(alignment: .leading, spacing: 12) {
        HStack {
            StatusBadge(text: "Downloading", tone: .accent, systemImage: "arrow.down.circle")
            StatusBadge(text: "Seeding", tone: .success, systemImage: "arrow.up.circle")
            StatusBadge(text: "Paused", tone: .neutral, systemImage: "pause.circle")
        }
        HStack {
            StatusBadge(text: "File missing", tone: .warning, systemImage: "exclamationmark.triangle")
            StatusBadge(text: "Failed", tone: .failure, systemImage: "exclamationmark.circle")
            StatusBadge(text: "Queued")
        }
        HStack {
            ProtocolBadge(text: "HTTP")
            ProtocolBadge(text: "BT")
            ProtocolBadge(text: "ED2K")
            ProtocolBadge(text: "FTP")
            ProtocolBadge(text: "HLS")
        }
    }
    .padding()
}

#Preview("StatusBadge · 深色 / AX5 封顶") {
    VStack(alignment: .leading, spacing: 12) {
        HStack {
            StatusBadge(text: "Failed", tone: .failure, systemImage: "exclamationmark.circle")
            StatusBadge(text: "Seeding", tone: .success)
            ProtocolBadge(text: "BT")
        }
    }
    .padding()
    .background(Color(uiColor: .secondarySystemGroupedBackground))
    .preferredColorScheme(.dark)
    .dynamicTypeSize(.accessibility5)
}
