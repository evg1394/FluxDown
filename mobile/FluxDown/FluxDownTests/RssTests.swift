import FluxDomain
import Foundation
import Testing
@testable import FluxDown

@MainActor
struct RssTests {
    private func source(
        name: String = "Feed",
        url: String = "https://example.com/rss.xml",
        enabled: Bool = true,
        autoDownload: Bool = true,
        interval: Int32 = 30,
        lastSuccess: Int64 = 0,
        failCount: Int32 = 0,
        unread: Int32 = 0
    ) -> RssSource {
        RssSource(
            sourceId: "s",
            name: name,
            url: url,
            enabled: enabled,
            autoDownload: autoDownload,
            intervalMinutes: interval,
            lastSuccessAt: lastSuccess,
            lastError: "",
            failCount: failCount,
            unreadCount: unread
        )
    }

    @Test func agoBuckets() {
        #expect(RssFormat.ago(deltaSeconds: -5) == .justNow)
        #expect(RssFormat.ago(deltaSeconds: 59) == .justNow)
        #expect(RssFormat.ago(deltaSeconds: 60) == .minutes(1))
        #expect(RssFormat.ago(deltaSeconds: 3_599) == .minutes(59))
        #expect(RssFormat.ago(deltaSeconds: 3_600) == .hours(1))
        #expect(RssFormat.ago(deltaSeconds: 86_399) == .hours(23))
        #expect(RssFormat.ago(deltaSeconds: 86_400 * 3 + 5) == .days(3))
    }

    @Test func intervalUsesHoursOnlyForWholeHours() {
        #expect(RssFormat.intervalText(minutes: 30) == L("rssEveryMinutes", ["n": 30]))
        #expect(RssFormat.intervalText(minutes: 90) == L("rssEveryMinutes", ["n": 90]))
        #expect(RssFormat.intervalText(minutes: 60) == L("rssEveryHours", ["n": 1]))
        #expect(RssFormat.intervalText(minutes: 720) == L("rssEveryHours", ["n": 12]))
    }

    @Test func badgeCapsAtNinetyNinePlus() {
        #expect(RssFormat.badgeText(unread: 7) == "7")
        #expect(RssFormat.badgeText(unread: 99) == "99")
        #expect(RssFormat.badgeText(unread: 100) == "99+")
    }

    @Test func titleFallsBackToUrlAndHostToUrl() {
        #expect(RssFormat.title(of: source(name: "  ")) == "https://example.com/rss.xml")
        #expect(RssFormat.title(of: source(name: "Mikan")) == "Mikan")
        #expect(RssFormat.host(of: source()) == "example.com")
        #expect(RssFormat.host(of: source(url: "not a url")) == "not a url")
    }

    @Test func statusPriorityRefreshingThenFailedThenLastFetch() {
        let now = Date(timeIntervalSince1970: 10_000)
        let ok = source(lastSuccess: 10_000 - 120)
        #expect(RssFormat.status(of: ok, refreshing: true, now: now) == .refreshing)
        #expect(RssFormat.status(of: source(lastSuccess: 1, failCount: 3), refreshing: false, now: now) == .failed(count: 3))
        #expect(RssFormat.status(of: ok, refreshing: false, now: now) == .lastFetch(.minutes(2)))
        #expect(RssFormat.status(of: source(), refreshing: false, now: now) == .neverFetched)
    }

    @Test func detailTextAppendsDisabledTag() {
        let enabled = RssFormat.detailText(of: source(autoDownload: false))
        #expect(enabled == [L("rssEveryMinutes", ["n": 30]), L("rssCollectMode")].joined(separator: " · "))
        let disabled = RssFormat.detailText(of: source(enabled: false))
        #expect(disabled.hasSuffix(L("mobileRssDisabled")))
        #expect(disabled.contains(L("rssAutoDownloadOn")))
    }
}
