import FluxDomain
import FluxUI
import SwiftUI

// 详情「日志」页（02-downloads §5.6）：`daemon.task.activity` 历史分页 + `taskActivityAdded` 实时事件，
// 游标逻辑由 FluxDomain 的 `TaskActivityFeed` 承担；这里只做泵（Web `useActivity.ts` 的移植）与渲染。

// MARK: - 文案

enum TaskActivityText {
    /// kind → 文案；未识别返回 nil（走 `detailActivityKindUnknown (kind)`）。
    static func kindLabel(_ kind: String) -> String? {
        switch kind {
        case "status": L("detailActivityKindStatus")
        case "error": L("detailActivityKindError")
        case "split": L("detailActivityKindSplit")
        case "cdn_pool": L("detailActivityKindCdnPool")
        case "cdn_kick": L("detailActivityKindCdnKick")
        case "cdn_breaker": L("detailActivityKindCdnBreaker")
        case "cdn_fallback": L("detailActivityKindCdnFallback")
        case "cdn_summary": L("detailActivityKindCdnSummary")
        case "nic_links": L("detailActivityKindNicLinks")
        case "nic_off": L("detailActivityKindNicOff")
        case "retry": L("detailActivityKindRetry")
        case "journal_overflow": L("detailActivityKindJournalOverflow")
        default: nil
        }
    }

    /// `status` 事件携带的引擎状态码 → 状态词（0 / 5 等待，1 下载中，2 暂停，3 完成，其余失败）。
    static func statusWord(_ status: Int32) -> String {
        switch status {
        case 0, 5: L("statusPending")
        case 1: L("statusDownloading")
        case 2: L("statusPaused")
        case 3: L("statusCompleted")
        default: L("statusError")
        }
    }

    /// 一行事件的文本：`状态: 下载中` / `错误: message` / `活动 (kind): message`。
    static func describe(_ entry: TaskActivityDto) -> String {
        if let label = kindLabel(entry.kind) {
            if entry.kind == "status", let status = entry.status {
                return "\(label): \(statusWord(status))"
            }
            return entry.message.isEmpty ? label : "\(label): \(entry.message)"
        }
        let label = L("detailActivityKindUnknown")
        return entry.message.isEmpty ? "\(label) (\(entry.kind))" : "\(label) (\(entry.kind)): \(entry.message)"
    }

    /// `HH:mm:ss`；时间戳无效 = 「—」。
    static func time(_ ms: Int64) -> String {
        guard ms > 0 else { return Format.dash }
        return Date(timeIntervalSince1970: TimeInterval(ms) / 1000).formatted(
            .verbatim(
                "\(hour: .twoDigits(clock: .twentyFourHour, hourCycle: .zeroBased)):\(minute: .twoDigits):\(second: .twoDigits)",
                locale: Locale(identifier: "en_US_POSIX"),
                timeZone: .current,
                calendar: Calendar(identifier: .gregorian)
            )
        )
    }

    /// `YYYY-MM-DD`；时间戳无效 = 空。
    static func date(_ ms: Int64) -> String {
        guard ms > 0 else { return "" }
        return Date(timeIntervalSince1970: TimeInterval(ms) / 1000).formatted(
            .verbatim(
                "\(year: .defaultDigits)-\(month: .twoDigits)-\(day: .twoDigits)",
                locale: Locale(identifier: "en_US_POSIX"),
                timeZone: .current,
                calendar: Calendar(identifier: .gregorian)
            )
        )
    }
}

// MARK: - 模型

/// 单任务活动日志：持有游标并驱动 `daemon.task.activity` 取页。每个 taskId 一个实例。
@MainActor
@Observable
final class TaskActivityModel {
    private(set) var feed: TaskActivityFeed

    @ObservationIgnored private var live = false
    @ObservationIgnored private var started = false
    /// 已处理的最大通知序号；nil = 尚未取基线（只处理挂载之后到达的事件，历史由页查询补齐）。
    @ObservationIgnored private var lastNoticeId: Int?

    init(taskId: String) {
        feed = TaskActivityFeed(taskId: taskId)
    }

    /// 连接状态变化：离线挂起；重新在线补齐（已加载走 afterId，否则重取最新页）。
    func connectionChanged(live: Bool, session: any HostSession) {
        let wasLive = self.live
        self.live = live
        if !live {
            feed.suspend()
        } else if started, !wasLive {
            feed.reconnect()
        }
        started = true
        pump(session: session)
    }

    /// 消费新到达的一次性通知里的 `taskActivityAdded`。
    func consume(_ notices: [HostNotice]) {
        guard let last = lastNoticeId else {
            lastNoticeId = notices.last?.id ?? 0
            return
        }
        for notice in notices where notice.id > last {
            lastNoticeId = notice.id
            guard notice.name == HostNoticeName.taskActivityAdded,
                  let entry: TaskActivityDto = notice.decode() else { continue }
            feed.add(entry)
        }
    }

    func loadOlder(session: any HostSession) {
        feed.loadOlder()
        pump(session: session)
    }

    func retry(session: any HostSession) {
        feed.retry()
        pump(session: session)
    }

    /// 取下一张票据发查询；结果回来后继续取（补齐 / 实时缺口可能还有后续）。失败以 `nil` 交回游标。
    private func pump(session: any HostSession) {
        guard live, let ticket = feed.begin() else { return }
        Task {
            let page = try? await session.call(HostMethod.daemonTaskActivity, params: ticket.query) as TaskActivityPage
            if feed.finish(ticket, page: page) { pump(session: session) }
        }
    }
}

// MARK: - 页面

struct TaskActivityLogPage: View {
    @Environment(AppContainer.self) private var container

    @State private var model: TaskActivityModel

    init(taskId: String) {
        _model = State(initialValue: TaskActivityModel(taskId: taskId))
    }

    var body: some View {
        let feed = model.feed
        let entries = Array(feed.entries.reversed())
        let range = feed.retainedRange
        let session = container.session
        let live = container.store.state.connection == .live
        let lastNoticeId = container.store.notices.last?.id
        Section {
            // 提示行同时承载数据驱动（整个分页只挂一次，避免修饰符分发到每一行）。
            Text(L("detailLogHint"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .onChange(of: live, initial: true) { _, isLive in
                    model.connectionChanged(live: isLive, session: session)
                }
                .onChange(of: lastNoticeId, initial: true) {
                    model.consume(container.store.notices)
                }
            if range.truncated {
                warning(L("detailActivityTruncated"))
            }
            if feed.hasJournalGap {
                warning(L("detailActivityJournalGap"))
            }
            if let oldest = range.oldest, let newest = range.newest {
                Text(L("detailActivityRetainedRange", ["oldest": "#\(oldest)", "newest": "#\(newest)"]))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            if feed.failed {
                Label(L("detailActivityQueryFailed"), systemImage: FluxSymbol.failure)
                    .font(.subheadline)
                    .foregroundStyle(Color.fdStatusFailedText)
                Button(L("detailActivityRetry")) { model.retry(session: session) }
            }
            if feed.isLoading, entries.isEmpty {
                loadingRow
            }
            if feed.loaded, entries.isEmpty, !feed.isLoading, !feed.failed {
                Text(L("detailLogEmpty"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        if !entries.isEmpty {
            Section {
                ForEach(entries) { entry in
                    TaskActivityRow(entry: entry)
                }
                if feed.isLoading {
                    loadingRow
                }
                if feed.hasOlder, !feed.isLoading, !feed.failed {
                    Button {
                        model.loadOlder(session: session)
                    } label: {
                        Text(L("detailActivityLoadMore")).frame(maxWidth: .infinity)
                    }
                }
            }
        }
    }

    private func warning(_ text: String) -> some View {
        Label(text, systemImage: FluxSymbol.warning)
            .font(.footnote)
            .foregroundStyle(Color.fdStatusWarningText)
    }

    private var loadingRow: some View {
        HStack(spacing: 12) {
            ProgressView()
            Text(L("detailActivityLoading")).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// 一条事件：时间（等宽秒级 + 日期小字）+ 「kind：message」。AX 字号下改纵排。
private struct TaskActivityRow: View {
    let entry: TaskActivityDto

    @Environment(\.dynamicTypeSize) private var typeSize

    private var textStyle: AnyShapeStyle {
        switch entry.kind {
        case "error": AnyShapeStyle(Color.fdStatusFailedText)
        case "journal_overflow": AnyShapeStyle(Color.fdStatusWarningText)
        case "status": AnyShapeStyle(.tint)
        default: AnyShapeStyle(.primary)
        }
    }

    var body: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 12))
        let date = TaskActivityText.date(entry.timestampMs)
        layout {
            VStack(alignment: .leading, spacing: 0) {
                Text(TaskActivityText.time(entry.timestampMs))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(.secondary)
                if !date.isEmpty {
                    Text(date)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.tertiary)
                }
            }
            .fixedSize(horizontal: true, vertical: false)
            Text(TaskActivityText.describe(entry))
                .font(.subheadline)
                .foregroundStyle(textStyle)
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
