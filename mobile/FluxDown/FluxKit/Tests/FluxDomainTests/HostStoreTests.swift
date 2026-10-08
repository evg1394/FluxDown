import FluxDomain
import Foundation
import Testing

/// 与 Android `HostStoreTest` 覆盖同一组 reducer 规则（删除哨兵 / 旧采样丢弃 / 非活跃清段 / Stale 只读 / 合帧）。
@MainActor
struct HostStoreTests {
    private func task(_ id: String, _ status: TaskStatus) -> DownloadTask {
        DownloadTask(taskId: id, url: "https://x/\(id)", fileName: "\(id).bin", saveDir: "/d", status: status, downloadedBytes: 50, totalBytes: 100)
    }

    private func seg(_ i: Int32, _ active: Bool?) -> Segment {
        Segment(index: i, startByte: Int64(i) * 10, endByte: Int64(i) * 10 + 9, downloadedBytes: 5, active: active)
    }

    private func runtime(_ id: String, _ seq: UInt64, _ segs: [Segment], transfers: UInt32 = 2) -> TaskRuntime {
        TaskRuntime(taskId: id, sampleSequence: seq, activeTransfers: transfers, connectedPeers: nil, totalBytes: 100, segments: segs)
    }

    private func snapshot(_ tasks: [DownloadTask], runtime: [String: TaskRuntime] = [:]) -> HostSignal {
        .snapshot(HostSnapshot(
            info: HostInfo(serviceName: "t", serviceVersion: "1", protocolVersion: 7, capabilities: []),
            tasks: tasks,
            runtime: runtime,
            configRevision: 1
        ))
    }

    private func progress(_ id: String, _ status: TaskStatus, speed: Int64, error: String = "") -> HostSignal {
        .event(.taskProgress(TaskProgress(taskId: id, status: status.rawValue, downloadedBytes: 60, totalBytes: 100, speed: speed, errorMessage: error)))
    }

    /// 合帧间隔设为一小时：非结构性事件只能经 `flush()` 发布，测试不依赖时序。
    private func store() -> HostStore { HostStore(clock: { 1_000_000 }, publishInterval: .seconds(3600)) }

    @Test func progressWithDeletedSentinelRemovesTaskAndRuntime() {
        let s = store()
        s.apply(snapshot([task("a", .downloading)], runtime: ["a": runtime("a", 1, [seg(0, true)])]))
        s.apply(progress("a", .failed, speed: 0, error: "deleted"))
        #expect(s.state.task("a") == nil)
        #expect(s.state.runtime["a"] == nil)
    }

    @Test func staleRuntimeSampleIsDroppedAndEmptySegmentsKeepPrevious() {
        let s = store()
        s.apply(snapshot([task("a", .downloading)], runtime: ["a": runtime("a", 5, [seg(0, true), seg(1, true)])]))
        s.apply(.event(.taskRuntimeChanged(runtime("a", 4, [seg(0, false)]))))
        s.flush()
        #expect(s.state.runtime["a"]?.segments.count == 2)

        s.apply(.event(.taskRuntimeChanged(runtime("a", 6, [], transfers: 1))))
        s.flush()
        let rt = s.state.runtime["a"]
        #expect(rt?.sampleSequence == 6)
        #expect(rt?.segments.count == 2)
        #expect(rt?.activeTransfers == 1)
    }

    @Test func pausingClearsActiveSegmentsAndLiveSpeed() {
        let s = store()
        s.apply(snapshot([task("a", .downloading)], runtime: ["a": runtime("a", 1, [seg(0, true), seg(1, nil)])]))
        s.apply(progress("a", .downloading, speed: 4096))
        s.flush()
        #expect(s.state.speed("a").down == 4096)

        s.apply(progress("a", .paused, speed: 4096))
        let st = s.state
        #expect(st.task("a")?.status == .paused)
        #expect(st.speed("a").down == 0)
        #expect(st.runtime["a"]?.segments.allSatisfy { $0.active == false } == true)
        #expect(st.runtime["a"]?.activeTransfers == 0)
    }

    @Test func staleSignalMakesStateReadOnlyAndDropsRuntimeAndSpeeds() {
        let s = store()
        s.apply(snapshot([task("a", .downloading)], runtime: ["a": runtime("a", 1, [seg(0, true)])]))
        s.apply(progress("a", .downloading, speed: 100))
        s.apply(.stale)
        let st = s.state
        #expect(st.isReadOnly)
        #expect(st.runtime.isEmpty)
        #expect(st.speeds.isEmpty)
        #expect(st.tasks.count == 1)
    }

    @Test func highFrequencyProgressIsCoalescedIntoOnePublish() {
        let s = store()
        s.apply(snapshot([task("a", .downloading)]))
        for i in 1...20 { s.apply(progress("a", .downloading, speed: Int64(i))) }
        #expect(s.state.speeds["a"] == nil, "non-structural progress must not publish synchronously")
        s.flush()
        #expect(s.state.speed("a").down == 20)
    }

    @Test func resyncSnapshotKeepsInsertionOrderAndSpeedHistory() {
        let s = store()
        s.apply(snapshot([task("a", .downloading), task("b", .paused)]))
        s.apply(.event(.taskChanged(task("c", .pending))))
        #expect(s.state.tasks.map(\.taskId) == ["a", "b", "c"])
        s.apply(.event(.runtimeStatsChanged(RuntimeStats(totalDownloadBps: 512))))
        s.apply(snapshot([task("a", .downloading)]))
        #expect(s.state.tasks.map(\.taskId) == ["a"])
        #expect(s.state.speedHistory.latestDown == 512)
    }

    // MARK: 通用分区 / 通知通道

    private struct Gateway: Decodable, Equatable { var takeoverEnabled: Bool }

    private func json(_ text: String) -> Data { Data(text.utf8) }

    @Test func snapshotSectionsAreDecodableAndSectionChangedReplacesThemImmediately() {
        let s = store()
        s.apply(.snapshot(HostSnapshot(
            info: HostInfo(serviceName: "t", serviceVersion: "1", protocolVersion: 7, capabilities: []),
            sections: [HostSection.agentGateway: json(#"{"takeoverEnabled":false}"#)]
        )))
        #expect(s.state.section(HostSection.agentGateway, as: Gateway.self) == Gateway(takeoverEnabled: false))
        #expect(s.state.section(HostSection.agentShell, as: Gateway.self) == nil, "missing section")

        // 结构性：不经 flush 立即可见。
        s.apply(.event(.sectionChanged(name: HostSection.agentGateway, json: json(#"{"takeoverEnabled":true}"#))))
        #expect(s.state.section(HostSection.agentGateway, as: Gateway.self) == Gateway(takeoverEnabled: true))

        // 解码失败（形状不符）不崩溃，返回 nil。
        s.apply(.event(.sectionChanged(name: HostSection.agentGateway, json: json("[1,2]"))))
        #expect(s.state.section(HostSection.agentGateway, as: Gateway.self) == nil)

        // 重同步快照整表替换分区。
        s.apply(.snapshot(HostSnapshot(
            info: HostInfo(serviceName: "t", serviceVersion: "1", protocolVersion: 7, capabilities: []),
            sections: [HostSection.agentShell: json("{}")]
        )))
        #expect(s.state.sections.keys.sorted() == [HostSection.agentShell])
    }

    @Test func noticesAreLoggedWithMonotonicIdsAndBoundedToTheLastFifty() {
        let s = store()
        s.apply(snapshot([]))
        let before = s.state.sections
        for i in 1...(HostStore.noticeLimit + 7) {
            s.apply(.event(.notice(name: HostNoticeName.captureTasksStarted, json: json("[\"t\(i)\"]"))))
        }
        #expect(s.notices.count == HostStore.noticeLimit)
        #expect(s.notices.first?.id == 8)
        #expect(s.notices.last?.id == HostStore.noticeLimit + 7)
        #expect(zip(s.notices, s.notices.dropFirst()).allSatisfy { $0.id + 1 == $1.id })
        #expect(s.notices.last?.decode([String].self) == ["t\(HostStore.noticeLimit + 7)"])
        #expect(s.notices.last?.name == "captureTasksStarted")
        #expect(s.state.sections == before, "notices never touch published state")
    }
}
