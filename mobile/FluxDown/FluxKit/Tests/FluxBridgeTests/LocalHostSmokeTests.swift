import FluxBridge
import FluxDomain
import Foundation
import Network
import Testing

/// 经真实 UniFFI 绑定在 iOS 上跑进程内 daemon + agent（同 Android `LocalHostSmokeTest`）：
/// 首个信号为快照、下载一个回环 HTTP 文件落盘、配置冲突经 FFI 往返为 `.conflict`。
/// 本机主机是进程级单例，两个场景串行执行。
@Suite(.serialized)
struct LocalHostSmokeTests {
    static let payload: Data = Data((0..<(2 * 1024 * 1024)).map { UInt8(truncatingIfNeeded: ($0 &* 31) ^ ($0 >> 8)) })
    static let step: Duration = .seconds(30)

    @Test func localHostDownloadsAFileThroughTheGeneratedBindings() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("fluxdown-smoke-\(UUID().uuidString)")
        let server = try await LoopbackServer.start(payload: Self.payload)
        defer {
            server.stop()
            try? FileManager.default.removeItem(at: root)
        }
        let saveDir = root.appendingPathComponent("downloads").path
        let host = try await FluxBridge.openLocal(dataDir: root.appendingPathComponent("data").path, saveDir: saveDir, deviceName: nil)
        var signals = host.signals.makeAsyncIterator()
        defer { host.close() }

        guard case let .snapshot(snapshot)? = await signals.next() else {
            Issue.record("first signal must be a snapshot")
            await FluxBridge.shutdownLocal()
            return
        }
        #expect(snapshot.daemonConnected)
        #expect(!snapshot.categories.isEmpty)
        #expect(snapshot.config["default_save_dir"] == saveDir)

        let taskId = try await host.createTask(CreateTaskRequest(url: "http://127.0.0.1:\(server.port)/smoke.bin"))
        #expect(!taskId.isEmpty)
        let deadline = ContinuousClock.now + Self.step
        var completed = false
        while !completed, ContinuousClock.now < deadline, let signal = await signals.next() {
            switch signal {
            case let .event(.taskChanged(task)): completed = task.taskId == taskId && task.status == .completed
            case let .event(.taskProgress(p)): completed = p.taskId == taskId && p.status == TaskStatus.completed.rawValue
            case let .snapshot(s): completed = s.tasks.contains { $0.taskId == taskId && $0.status == .completed }
            case let .fatal(error): Issue.record("session went fatal: \(error)")
            default: break
            }
        }
        #expect(completed)
        let file = URL(fileURLWithPath: saveDir).appendingPathComponent("smoke.bin")
        #expect(try Data(contentsOf: file) == Self.payload)

        host.close()
        await FluxBridge.shutdownLocal()
    }

    @Test func configConflictCrossesTheBindingsAsTypedError() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("fluxdown-smoke-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let host = try await FluxBridge.openLocal(
            dataDir: root.appendingPathComponent("data").path,
            saveDir: root.appendingPathComponent("downloads").path,
            deviceName: nil
        )
        var signals = host.signals.makeAsyncIterator()
        guard case let .snapshot(snapshot)? = await signals.next() else {
            Issue.record("first signal must be a snapshot")
            host.close()
            await FluxBridge.shutdownLocal()
            return
        }
        let revision = snapshot.configRevision
        try await host.patchConfig(expectedRevision: revision, values: ["max_auto_retries": "5"])
        do {
            try await host.patchConfig(expectedRevision: revision, values: ["max_auto_retries": "6"])
            Issue.record("a stale config revision must be rejected")
        } catch {
            #expect(error.code == .conflict)
        }
        host.close()
        await FluxBridge.shutdownLocal()
    }

    private struct QueueCreate: Encodable { var name: String }
    private struct TaskIdParams: Encodable { var id: String }
    private struct PreferencesPatch: Encodable { var values: [String: JSONValue]; var sync: Bool }
    private struct PreferencesPatched: Decodable { var ok: Bool }
    private struct PreferencesSection: Decodable { var values: [String: JSONValue] }

    /// 通用通道经真实 FFI：快照带全部分区键、`call` 读 / 写 / 类型化错误、写入后分区经事件到达 store。
    @Test @MainActor func genericChannelRoundTripsThroughTheGeneratedBindings() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("fluxdown-smoke-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let host = try await FluxBridge.openLocal(
            dataDir: root.appendingPathComponent("data").path,
            saveDir: root.appendingPathComponent("downloads").path,
            deviceName: nil
        )
        let store = HostStore(publishInterval: .milliseconds(10))
        store.attach(host)
        defer { store.detach() }

        #expect(await Self.eventually { store.state.connection == .live })
        for key in HostSection.all {
            #expect(store.state.sections[key] != nil, "snapshot must carry section \(key)")
            #expect(store.state.section(key, as: JSONValue.self) != nil, "section \(key) must be valid JSON")
        }
        #expect(store.state.section(HostSection.agentSession, as: JSONValue.self) == JSONValue.null)

        // 读：未配置 RSS 源 → 空数组。
        let sources: [JSONValue] = try await host.call(HostMethod.daemonRssListSources)
        #expect(sources.isEmpty)

        // 写：建队列后经 call 读回，类型化事件同步到 store。
        try await host.callVoid(HostMethod.daemonQueueCreate, params: QueueCreate(name: "Weekend"))
        let queues: [JSONValue] = try await host.call(HostMethod.daemonQueueList)
        #expect(queues.contains { $0["name"]?.stringValue == "Weekend" })
        #expect(await Self.eventually { store.state.queues.contains { $0.name == "Weekend" } })

        // 分区增量：偏好写入 → `agent.preferences` 分区到达 store。
        let patched: PreferencesPatched = try await host.call(
            HostMethod.agentPreferencesPatch,
            params: PreferencesPatch(values: ["download.keep_awake": .bool(true)], sync: false)
        )
        #expect(patched.ok)
        #expect(await Self.eventually {
            store.state.section(HostSection.agentPreferences, as: PreferencesSection.self)?
                .values["download.keep_awake"] == JSONValue.bool(true)
        })

        // 确定性的离线类型化错误：未知任务 → notFound；会话层方法被拒 → invalidArgument。
        do {
            let _: JSONValue = try await host.call(HostMethod.daemonTaskGet, params: TaskIdParams(id: "no-such-task"))
            Issue.record("an unknown task must be rejected")
        } catch {
            #expect(error.code == .notFound)
        }
        do {
            _ = try await host.call(HostMethod.systemSnapshot, params: nil)
            Issue.record("system.* must not be callable")
        } catch {
            #expect(error.code == .invalidArgument)
        }
        do {
            _ = try await host.call(HostMethod.daemonTaskList, params: Data("{broken".utf8))
            Issue.record("invalid params JSON must be rejected")
        } catch {
            #expect(error.code == .invalidArgument)
        }

        store.detach()
        host.close()
        await FluxBridge.shutdownLocal()
    }

    @MainActor
    private static func eventually(_ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + step
        while !condition() {
            if ContinuousClock.now >= deadline { return false }
            try? await Task.sleep(for: .milliseconds(20))
        }
        return true
    }
}

/// 最小回环 HTTP 服务：只服务一个固定载荷，支持 HEAD 与单段 Range。
final class LoopbackServer: @unchecked Sendable {
    private let listener: NWListener
    private let payload: Data
    private let queue = DispatchQueue(label: "loopback-server")
    let port: UInt16

    private init(listener: NWListener, payload: Data, port: UInt16) {
        self.listener = listener
        self.payload = payload
        self.port = port
    }

    static func start(payload: Data) async throws -> LoopbackServer {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        let port: UInt16 = try await withCheckedThrowingContinuation { continuation in
            let resumed = Resumed()
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if resumed.claim() { continuation.resume(returning: listener.port?.rawValue ?? 0) }
                case let .failed(error):
                    if resumed.claim() { continuation.resume(throwing: error) }
                default: break
                }
            }
            listener.newConnectionHandler = { _ in }
            listener.start(queue: DispatchQueue(label: "loopback-listener"))
        }
        let server = LoopbackServer(listener: listener, payload: payload, port: port)
        listener.newConnectionHandler = { [server] connection in server.serve(connection) }
        return server
    }

    func stop() { listener.cancel() }

    private func serve(_ connection: NWConnection) {
        connection.start(queue: queue)
        receiveRequest(connection, buffer: Data())
    }

    private func receiveRequest(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [self] data, _, done, error in
            var buffer = buffer
            if let data { buffer.append(data) }
            if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                respond(connection, head: String(decoding: buffer[..<end.lowerBound], as: UTF8.self))
            } else if !done, error == nil {
                receiveRequest(connection, buffer: buffer)
            } else {
                connection.cancel()
            }
        }
    }

    private func respond(_ connection: NWConnection, head: String) {
        let lines = head.components(separatedBy: "\r\n")
        let isHead = lines.first?.hasPrefix("HEAD ") ?? false
        var range = 0..<payload.count
        var partial = false
        if let header = lines.first(where: { $0.lowercased().hasPrefix("range: bytes=") }) {
            let spec = header.dropFirst("range: bytes=".count).split(separator: "-", omittingEmptySubsequences: false)
            if let start = Int(spec.first ?? "") {
                let end = spec.count > 1 ? (Int(spec[1]).map { min($0, payload.count - 1) } ?? payload.count - 1) : payload.count - 1
                range = start..<(end + 1)
                partial = true
            }
        }
        var response = partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n"
        response += "Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nConnection: close\r\n"
        response += "Content-Length: \(range.count)\r\n"
        if partial { response += "Content-Range: bytes \(range.lowerBound)-\(range.upperBound - 1)/\(payload.count)\r\n" }
        response += "\r\n"
        var body = Data(response.utf8)
        if !isHead { body.append(payload.subdata(in: range)) }
        connection.send(content: body, isComplete: true, completion: .contentProcessed { _ in connection.cancel() })
    }
}

/// 一次性恢复守卫（continuation 只能 resume 一次）。
private final class Resumed: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}
