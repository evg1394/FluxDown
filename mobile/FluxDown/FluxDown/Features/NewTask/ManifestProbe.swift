import FluxDomain
import Foundation

/// 清单预解析的结局。
nonisolated enum ManifestProbeOutcome: Sendable {
    case manifest(ResolvePreviewResponse)
    /// 插件未返回清单；`reason` 非空 = 预解析报错文案。
    case empty(reason: String)
    case failed(HostError)
    case timedOut
    case cancelled
}

/// 清单预解析（`daemon.group.resolvePreview` / `agent.capture.preview`，慢方法）。
///
/// RPC 本身无法取消：结果、90 s 超时与用户取消三者先到者胜出，晚到的结果被丢弃
///（`AsyncStream` 只取第一个元素，之后的 `yield` 无效）。调用方用 `id` 判断自己是否仍是当前探测。
nonisolated struct ManifestProbe: Sendable, Identifiable {
    static let timeout: Duration = .seconds(90)

    let id = UUID()
    /// 来源域名（展示用；未知为空）。
    let host: String

    private let stream: AsyncStream<ManifestProbeOutcome>
    private let continuation: AsyncStream<ManifestProbeOutcome>.Continuation
    private let timer: Task<Void, Never>

    init<Params: Encodable & Sendable>(
        session: any HostSession,
        method: String,
        params: Params,
        host: String,
        timeout: Duration = ManifestProbe.timeout
    ) {
        let (stream, continuation) = AsyncStream<ManifestProbeOutcome>.makeStream(bufferingPolicy: .bufferingNewest(1))
        let timer = Task.detached {
            do { try await Task.sleep(for: timeout) } catch { return }
            continuation.yield(.timedOut)
            continuation.finish()
        }
        Task.detached {
            do throws(HostError) {
                let response: ResolvePreviewResponse = try await session.call(method, params: params)
                continuation.yield(response.hasManifest ? .manifest(response) : .empty(reason: response.error))
            } catch {
                continuation.yield(.failed(error))
            }
            continuation.finish()
            timer.cancel()
        }
        self.host = host
        self.stream = stream
        self.continuation = continuation
        self.timer = timer
    }

    /// 等待第一个结局（只应由一个调用方等待）。
    func outcome() async -> ManifestProbeOutcome {
        for await value in stream { return value }
        return .cancelled
    }

    /// 放弃探测：立即让等待方返回 `.cancelled`；在途 RPC 的结果被忽略。
    func cancel() {
        timer.cancel()
        continuation.yield(.cancelled)
        continuation.finish()
    }
}
