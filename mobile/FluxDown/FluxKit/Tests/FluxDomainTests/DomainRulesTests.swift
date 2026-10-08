import FluxDomain
import Testing

struct DomainRulesTests {
    @Test func categoryIndexPicksFirstSpecificMatchAndFallsBackToOther() {
        let index = CategoryIndex(TaskCategory.builtin)
        #expect(index.categoryOf("Movie.MKV")?.builtinType == "video")
        #expect(index.categoryOf("no-extension")?.builtinType == "other")
        let other = TaskCategory.builtin.first { $0.isOther }!
        let all = TaskCategory.builtin.first { $0.isAll }!
        #expect(index.matches(other, "a.unknownext"))
        #expect(!index.matches(other, "a.zip"))
        #expect(index.matches(all, "anything"))
    }

    @Test func regexCategoryWinsByPositionAndInvalidRegexMatchesNothing() {
        let docs = TaskCategory(id: "c1", name: "Specs", icon: "file", extensions: [], regexPattern: "spec.*\\.pdf$", position: 0)
        let broken = TaskCategory(id: "c2", name: "Broken", icon: "file", extensions: [], regexPattern: "([", position: 1)
        let index = CategoryIndex([docs, broken] + TaskCategory.builtin.map { var c = $0; c.position += 10; return c })
        #expect(index.categoryOf("SPEC-v2.PDF")?.id == "c1")
        #expect(index.categoryOf("manual.pdf")?.builtinType == "document")
        #expect(!index.matches(broken, "(["))
    }

    @Test func bytesUseBinaryUnitsWithMagnitudeDependentDigits() {
        #expect(Format.bytes(0).description == "0 B")
        #expect(Format.bytes(1023).description == "1023 B")
        #expect(Format.bytes(1536).description == "1.50 KB")
        #expect(Format.bytes(15 * 1024 * 1024).description == "15.0 MB")
        #expect(Format.bytes(150 * 1024 * 1024 * 1024).description == "150 GB")
        #expect(Format.speed(0) == nil)
    }

    @Test func etaIsUnknownWhenStalledUnsizedDoneOrBeyondADay() {
        #expect(Format.etaSeconds(downloaded: 0, total: 100, speed: 0) == nil)
        #expect(Format.etaSeconds(downloaded: 0, total: 0, speed: 10) == nil)
        #expect(Format.etaSeconds(downloaded: 100, total: 100, speed: 10) == nil)
        #expect(Format.etaSeconds(downloaded: 0, total: 86_401, speed: 1) == nil)
        #expect(Format.etaSeconds(downloaded: 0, total: 101, speed: 10) == 11)
        #expect(Format.percent(0.12345) == "12.3%")
    }

    @Test func translationsComeFromSharedAssetsWithEnglishKeyFallback() {
        let zh = L10n(locale: "zh")
        let en = L10n(locale: "en")
        #expect(zh("cancel") == "取消")
        #expect(en("cancel") == "Cancel")
        #expect(zh("__missing_key__") == "__missing_key__")
        #expect(L10n.fill("{n} items", ["n": 3]) == "3 items")
    }

    @Test func taskProtocolAndShareUrlFollowSentinels() {
        let torrent = DownloadTask(taskId: "t", url: "torrent-file://abc", originUrl: "https://x/a.torrent", fileName: "a", status: .downloading)
        #expect(torrent.protocol == .bt)
        #expect(torrent.shareUrl == "https://x/a.torrent")
        let hls = DownloadTask(taskId: "h", url: "https://x/live.m3u8?token=1", fileName: "v", status: .pending)
        #expect(hls.protocol == .hls)
        #expect(hls.shareUrl == hls.url)
        #expect(hls.progress == nil)
    }
}
