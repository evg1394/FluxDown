import FluxDomain
import Foundation
import Testing

/// 订阅刷新 DTO（`native/protocol/src/daemon.rs`）与列表型配置键格式（GPUI `subscription.rs` 同款用例）。
struct SubscriptionsProtocolTests {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try ProtocolJSON.makeDecoder().decode(type, from: Data(json.utf8))
    }

    // MARK: - DTO

    @Test func trackerRefreshDecodesWireShape() throws {
        let dto = try decode(
            TrackerSubRefreshResponse.self,
            #"{"success":true,"trackerCount":128,"okSources":2,"totalSources":3,"updatedAt":1759622400,"error":""}"#
        )
        #expect(dto.success)
        #expect(dto.trackerCount == 128)
        #expect(dto.okSources == 2)
        #expect(dto.totalSources == 3)
        #expect(dto.updatedAt == 1_759_622_400)
        #expect(dto.error.isEmpty)
        #expect(dto.outcome.count == 128)
    }

    @Test func trackerRefreshFailureCarriesErrorSummary() throws {
        let dto = try decode(
            TrackerSubRefreshResponse.self,
            #"{"success":false,"trackerCount":0,"okSources":0,"totalSources":2,"updatedAt":1759622400,"error":"all sources failed"}"#
        )
        #expect(!dto.success)
        #expect(dto.error == "all sources failed")
        #expect(dto.outcome.updatedAt == 1_759_622_400)
    }

    @Test func ed2kRefreshDecodesWireShape() throws {
        let dto = try decode(
            Ed2kServerSubRefreshResponse.self,
            #"{"success":true,"serverCount":42,"okSources":1,"totalSources":1,"updatedAt":1759622500,"error":""}"#
        )
        #expect(dto.success)
        #expect(dto.serverCount == 42)
        #expect(dto.totalSources == 1)
        #expect(dto.outcome.count == 42)
    }

    @Test func refreshDecodingIsLenient() throws {
        let empty = try decode(TrackerSubRefreshResponse.self, "{}")
        #expect(empty == TrackerSubRefreshResponse())
        let nulls = try decode(Ed2kServerSubRefreshResponse.self, #"{"success":true,"error":null,"updatedAt":null,"unknown":1}"#)
        #expect(nulls.success)
        #expect(nulls.error.isEmpty)
        #expect(nulls.updatedAt == 0)
    }

    // MARK: - 列表格式

    @Test func commaListIsEditedOnePerLine() {
        let stored = "176.123.5.89:4725,45.82.80.155:5687, 85.121.5.137:4232"
        #expect(SubscriptionListFormat.comma.toEditor(stored) == "176.123.5.89:4725\n45.82.80.155:5687\n85.121.5.137:4232")
    }

    @Test func commaListReadsLegacyLineSeparatedValue() {
        let legacy = "1.2.3.4:4661\r\n5.6.7.8:80\n"
        #expect(SubscriptionListFormat.comma.toEditor(legacy) == "1.2.3.4:4661\n5.6.7.8:80")
        #expect(SubscriptionListFormat.comma.count(legacy) == 2)
    }

    @Test func commaListSavesTrimmedDedupedCsv() {
        let text = " 1.2.3.4:4661 \n\nExample.org:4242\n1.2.3.4:4661\nexample.org:4242\n"
        #expect(SubscriptionListFormat.comma.toStored(text) == "1.2.3.4:4661,Example.org:4242")
    }

    @Test func trailingNewlineDoesNotChangeStoredValue() {
        for format in [SubscriptionListFormat.lines, .comma] {
            #expect(format.toStored("a:1\n") == format.toStored("a:1"))
        }
    }

    @Test func cacheCountsFollowStorageSeparator() {
        #expect(SubscriptionListFormat.comma.count("1.1.1.1:1,2.2.2.2:2,3.3.3.3:3") == 3)
        #expect(SubscriptionListFormat.lines.count("udp://a:1\n\nudp://b:2\n") == 2)
        #expect(SubscriptionListFormat.comma.count("") == 0)
        #expect(SubscriptionListFormat.lines.count("udp://a:1\r\nudp://b:2\r\n") == 2)
    }

    @Test func linesFormatKeepsInnerTextVerbatim() {
        let text = "# comment\nhttps://a.example/list.txt\n"
        #expect(SubscriptionListFormat.lines.toStored(text) == "# comment\nhttps://a.example/list.txt")
        #expect(SubscriptionListFormat.lines.toEditor(text) == text)
    }
}
