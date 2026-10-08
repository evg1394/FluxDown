import FluxDomain
import FluxUI
import Foundation
import Observation
import OSLog

/// V4 直连配对页签的状态机（本机配对码 / 发现 / 手动配对 / SAS 核对）。
///
/// 生命周期：页签出现 `start()`（开启发现 + 申请配对码），消失或 sheet 关闭 `stop()`（关闭发现 + `stopPairing`）；
/// SAS 核对页挂起时 sheet 被关闭则 `pairFinish(accept:false)`，避免对端悬挂会话。
/// 所有失败都落到可见的错误文案（`AccountText.error(_, context: .pairing)`），不伪造数据；只有拆除阶段（无界面可显示）才写日志。
@MainActor
@Observable
final class PairingModel {
    nonisolated struct Verify: Equatable {
        var token: String
        var sas: String
        var peerName: String
        var peerFingerprint: String
    }

    nonisolated enum Step: Equatable {
        /// 表单（本机配对码 + 发现列表 + 手动地址）。
        case form
        /// SAS 核对：等待用户确认或拒绝。
        case verify(Verify)
        /// 已确认，等待对端确认（对端有 60 秒窗口）。
        case finishing(Verify)
        /// 已配对（对端设备名）。
        case done(String)
        /// 对端拒绝。
        case rejected
    }

    var address = ""
    var code = ""
    private(set) var step: Step = .form
    private(set) var connecting = false
    /// 表单 / 核对页的内联错误。
    private(set) var message: String?

    private(set) var own: LinkPairingCodeDto?
    /// 本机配对码的有效总时长（秒，取自拿到码时的剩余时间），供倒计时环使用。
    private(set) var ownTotal: TimeInterval = 120
    private(set) var ownError: String?
    private var ownRequests = 0
    private(set) var discoveryError: String?
    private(set) var scanning = false

    var ownLoading: Bool { ownRequests > 0 }

    @ObservationIgnored private unowned let container: AppContainer
    @ObservationIgnored private var active = false
    @ObservationIgnored private var pairingStarted = false
    @ObservationIgnored private var closed = false
    @ObservationIgnored private var chain: Task<Void, Never>?
    @ObservationIgnored private var expiryTask: Task<Void, Never>?
    private static let log = Logger(subsystem: "com.fluxdown.FluxDown", category: "pairing")

    /// 配对码到期后重新申请前的最短等待（防止主机时钟偏差造成刷新风暴）。
    private static let minRefreshDelayMs: Int64 = 3000

    init(container: AppContainer) {
        self.container = container
    }

    // MARK: 生命周期

    /// 直连页签出现：开启发现并申请本机配对码。
    func start() {
        guard !active else { return }
        active = true
        closed = false
        enqueue { [self] in await setDiscovery(true, report: true) }
        refreshOwnCode()
    }

    /// 直连页签消失：关闭发现与配对码广播（顺序在已排队的操作之后）。
    func stop() {
        guard active else { return }
        active = false
        expiryTask?.cancel()
        expiryTask = nil
        own = nil
        ownError = nil
        let stopCode = pairingStarted
        pairingStarted = false
        enqueue { [self] in
            await setDiscovery(false, report: false)
            if stopCode { await stopPairing() }
        }
    }

    /// sheet 关闭：停止一切，并放弃仍在等待核对的会话。
    func dismissed() {
        closed = true
        stop()
        if case let .verify(verify) = step {
            step = .form
            Task { await rejectSilently(token: verify.token) }
        }
    }

    // MARK: 本机配对码

    /// 申请（或换一个）本机配对码；已有申请在途时忽略。
    func refreshOwnCode() {
        guard active, ownRequests == 0 else { return }
        ownRequests += 1
        pairingStarted = true
        enqueue { [self] in await runRefreshOwn() }
    }

    private func runRefreshOwn() async {
        defer { ownRequests -= 1 }
        guard active else { return }
        do throws(HostError) {
            let result = try await container.agent.linkPairingCode()
            guard active else { return }
            own = result
            ownError = nil
            let remaining = LinkRules.secondsUntil(expiresAtUnixMs: result.expiresAtUnixMs, nowMs: AccountText.nowMs())
            ownTotal = max(1, TimeInterval(remaining))
            scheduleExpiry(result.expiresAtUnixMs)
        } catch {
            ownError = AccountText.error(error, context: .pairing)
        }
    }

    /// 配对码到期自动换新。
    private func scheduleExpiry(_ expiresAtUnixMs: Int64) {
        expiryTask?.cancel()
        let wait = max(expiresAtUnixMs - AccountText.nowMs(), Self.minRefreshDelayMs)
        expiryTask = Task { [self] in
            do {
                try await Task.sleep(for: .milliseconds(wait))
            } catch {
                return
            }
            refreshOwnCode()
        }
    }

    // MARK: 发现

    private func setDiscovery(_ enabled: Bool, report: Bool) async {
        do throws(HostError) {
            try await container.agent.linkDiscoverySet(enabled: enabled)
            if enabled { discoveryError = nil }
        } catch {
            if report {
                discoveryError = AccountText.error(error, context: .pairing)
            } else {
                Self.log.error("link discovery off failed: \(error.description, privacy: .public)")
            }
        }
    }

    /// 重新搜索：发现关 → 开。
    func rescan() {
        guard active, !scanning else { return }
        scanning = true
        enqueue { [self] in
            await setDiscovery(false, report: true)
            await setDiscovery(true, report: true)
            scanning = false
        }
    }

    private func stopPairing() async {
        do throws(HostError) {
            try await container.agent.linkStopPairing()
        } catch {
            Self.log.error("link stopPairing failed: \(error.description, privacy: .public)")
        }
    }

    private func enqueue(_ operation: @escaping @MainActor () async -> Void) {
        let previous = chain
        chain = Task {
            await previous?.value
            await operation()
        }
    }

    // MARK: 配对

    /// 校验输入并发起配对（`agent.link.pairBegin`），成功进入 SAS 核对。
    func begin() async {
        guard !connecting, step == .form else { return }
        switch LinkRules.validate(address: address, code: code) {
        case let .failure(input):
            message = L(input.key)
        case let .success(params):
            connecting = true
            message = nil
            defer { connecting = false }
            do throws(HostError) {
                let response = try await container.agent.linkPairBegin(params)
                if closed {
                    // sheet 已在等待期间关闭：不留悬挂会话。
                    await rejectSilently(token: response.token)
                    return
                }
                step = .verify(Verify(
                    token: response.token,
                    sas: response.sas,
                    peerName: response.peerName,
                    peerFingerprint: response.peerFingerprint
                ))
            } catch {
                message = AccountText.error(error, context: .pairing)
                FluxHaptic.error.play()
            }
        }
    }

    /// SAS 核对后的决定：确认 → 等待对端；拒绝 → 回到表单。
    func finish(accept: Bool) async {
        guard case let .verify(verify) = step else { return }
        message = nil
        if accept { step = .finishing(verify) }
        do throws(HostError) {
            let response = try await container.agent.linkPairFinish(token: verify.token, accept: accept)
            if !accept {
                step = .form
                code = ""
            } else if response.paired {
                let name = response.device?.name ?? verify.peerName
                step = .done(name)
                FluxHaptic.success.play()
                container.toasts.show(text: L("localPairingPaired", ["device": name]), tone: .success)
            } else {
                step = .rejected
                FluxHaptic.warning.play()
            }
        } catch {
            step = .form
            message = AccountText.error(error, context: .pairing)
            FluxHaptic.error.play()
        }
    }

    /// 拒绝页 / 完成后返回表单。
    func backToForm() {
        step = .form
        message = nil
        code = ""
    }

    private func rejectSilently(token: String) async {
        do throws(HostError) {
            _ = try await container.agent.linkPairFinish(token: token, accept: false)
        } catch {
            Self.log.error("link pairFinish(reject) failed: \(error.description, privacy: .public)")
        }
    }
}
