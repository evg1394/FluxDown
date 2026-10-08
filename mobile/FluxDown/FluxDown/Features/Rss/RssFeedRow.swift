import FluxDomain
import FluxUI
import SwiftUI

/// R1 订阅行：图标方块（正常 = 强调色底 / 失败 = 红底描边 / 停用 = 灰底 + 降低不透明度）+ 名称 + 主机名 +
/// 状态行 + 未读徽标。点按由外层（`NavigationLink` / 列表选择）处理；本视图只负责展示与 VoiceOver 合并朗读。
struct RssFeedRow: View {
    @Environment(\.fluxAccent) private var accent

    let source: RssSource
    let refreshing: Bool

    private var failed: Bool { source.failCount > 0 }

    var body: some View {
        let title = RssFormat.title(of: source)
        let host = RssFormat.host(of: source)
        HStack(spacing: 14) {
            GlyphTile(
                systemImage: "dot.radiowaves.up.forward",
                tint: tileTint,
                ring: failed ? Color.fdStatusFailed.opacity(0.55) : nil,
                fill: tileFill,
                size: 38
            )
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .truncationMode(.middle)
                Text(host)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                RssStatusLine(source: source, refreshing: refreshing)
                if failed, !source.lastError.isEmpty {
                    Text(source.lastError)
                        .font(.caption.monospaced())
                        .foregroundStyle(Color.fdStatusFailedText)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            if refreshing {
                ProgressView().controlSize(.small)
            }
            if source.unreadCount > 0 {
                StatusBadge(text: RssFormat.badgeText(unread: source.unreadCount), tone: .accent)
                    .contentTransition(.numericText())
                    .accessibilityHidden(true)
            }
        }
        .opacity(source.enabled ? 1 : 0.55)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityDescription(title: title, host: host))
    }

    private var tileTint: Color {
        if failed { return .fdStatusFailedText }
        if !source.enabled { return .secondary }
        return source.unreadCount > 0 ? accent.text : .primary
    }

    private var tileFill: Color {
        if failed { return Color.fdStatusFailed.opacity(0.14) }
        if !source.enabled { return Color.secondary.opacity(0.14) }
        return accent.color.opacity(0.14)
    }

    private func accessibilityDescription(title: String, host: String) -> String {
        let status = RssFormat.status(of: source, refreshing: refreshing, now: .now)
        var parts = [title, host, RssFormat.statusText(status), RssFormat.detailText(of: source)]
        if failed, !source.lastError.isEmpty { parts.append(source.lastError) }
        if source.unreadCount > 0 { parts.append(L("mobileUnreadCount", ["n": source.unreadCount])) }
        return parts.joined(separator: ", ")
    }
}

/// 状态行：抓取状态（着色）+ 「 · 间隔 · 模式 · 已停用」。相对时间随分钟刷新（仅前台，纯本地时钟）。
struct RssStatusLine: View {
    @Environment(\.fluxAccent) private var accent

    let source: RssSource
    let refreshing: Bool

    private var failed: Bool { source.failCount > 0 }

    var body: some View {
        TimelineView(.everyMinute) { context in
            let status = RssFormat.status(of: source, refreshing: refreshing, now: context.date)
            HStack(alignment: .firstTextBaseline, spacing: 5) {
                if failed, !refreshing {
                    Image(systemName: FluxSymbol.warning)
                        .font(.footnote)
                        .foregroundStyle(Color.fdStatusFailedText)
                        .accessibilityHidden(true)
                }
                Text(attributedStatus(status))
                    .font(.footnote)
                    .lineLimit(3)
            }
        }
    }

    private func attributedStatus(_ status: RssFormat.Status) -> AttributedString {
        var head = AttributedString(RssFormat.statusText(status))
        head.foregroundColor = statusColor(status)
        var tail = AttributedString(" · " + RssFormat.detailText(of: source))
        tail.foregroundColor = .secondary
        return head + tail
    }

    private func statusColor(_ status: RssFormat.Status) -> Color {
        switch status {
        case .refreshing: accent.text
        case .failed: .fdStatusFailedText
        case .lastFetch, .neverFetched: .secondary
        }
    }
}
