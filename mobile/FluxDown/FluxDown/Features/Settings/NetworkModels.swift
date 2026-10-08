import FluxDomain
import Foundation
import Observation

// 网络与代理页的异步状态：系统代理检测 + 连通性测试（慢 RPC），站点凭据列表。
// 模型不持有会话：每次调用由视图传入当前 `HostSession`（切换主机后会话会变），并在 await 之后检查取消 / 主机，
// 避免旧主机的迟到结果覆盖新主机。

// MARK: - 代理检测与测试

@MainActor
@Observable
final class NetworkProxyModel {
    nonisolated enum Detection: Equatable {
        case idle
        case detecting
        case detected(SystemProxyDto)
        case failed(String)

        /// 已检测到的系统代理（仅 `detected == true`）。
        var system: SystemProxyDto? {
            if case let .detected(dto) = self, dto.detected { return dto }
            return nil
        }
    }

    nonisolated enum Test: Equatable {
        case idle
        case running
        case success(latencyMs: Int64)
        /// `detail` 已是可直接展示的原因（不含 `proxyTestFailed` 外壳）。
        case failure(detail: String)
    }

    private(set) var detection: Detection = .idle
    private(set) var test: Test = .idle
    /// 每次重置 / 新测试 +1：丢弃过期（请求参数已变或已切主机）的测试结果。
    private var testGeneration = 0

    /// `daemon.config.systemProxy`：进入系统代理模式 / 页面出现 / 重试时重新检测。
    func detect(using session: any HostSession) async {
        detection = .detecting
        do throws(HostError) {
            let dto: SystemProxyDto = try await session.call(HostMethod.daemonConfigSystemProxy)
            guard !Task.isCancelled else { return }
            detection = .detected(dto)
        } catch {
            guard !Task.isCancelled else { return }
            detection = .failed(ErrorText.describe(error))
        }
    }

    /// 参数（模式 / 类型 / 地址 / 端口 / 凭据）或主机变化后，旧结果不再代表当前配置。
    func resetTest() {
        guard test != .idle else { return }
        testGeneration += 1
        test = .idle
    }

    /// `daemon.config.proxyTest`（慢方法）：测试期间页面其余部分保持可交互。
    func runTest(_ request: ProxyTestRequest, using session: any HostSession) async {
        guard test != .running else { return }
        testGeneration += 1
        let generation = testGeneration
        test = .running
        let next: Test
        do throws(HostError) {
            let response: ProxyTestResponse = try await session.call(HostMethod.daemonConfigProxyTest, params: request)
            next = .success(latencyMs: response.latencyMs)
        } catch {
            next = .failure(detail: NetworkProxyTest.failureDetail(
                message: error.message,
                fallback: ErrorText.describe(error),
                tlsHint: L("proxyTestTlsEndpointHint")
            ))
        }
        guard generation == testGeneration else { return }
        test = next
    }
}

// MARK: - 站点凭据

@MainActor
@Observable
final class SiteAuthModel {
    nonisolated enum Phase: Equatable {
        case loading
        case loaded
        /// 首次加载失败（已有数据时的刷新失败保持 `.loaded`，不打断列表）。
        case failed(String)
    }

    private(set) var entries: [SiteAuthEntryDto] = []
    private(set) var phase: Phase = .loading
    private(set) var isClearing = false
    /// 正在删除的站点（行内禁用，避免重复请求）。
    private(set) var deleting: Set<String> = []
    private var loadedHost: String?

    /// `daemon.siteAuth.list`。切换主机先清空旧列表；已有数据时刷新失败静默保留旧数据。
    func load(using session: any HostSession, hostID: String) async {
        if loadedHost != hostID {
            loadedHost = hostID
            entries = []
            deleting = []
            phase = .loading
        } else if entries.isEmpty {
            phase = .loading
        }
        do throws(HostError) {
            let list: [SiteAuthEntryDto] = try await session.call(HostMethod.daemonSiteAuthList)
            guard !Task.isCancelled, loadedHost == hostID else { return }
            entries = list
            phase = .loaded
        } catch {
            guard !Task.isCancelled, loadedHost == hostID else { return }
            if phase != .loaded { phase = .failed(ErrorText.describe(error)) }
        }
    }

    /// `daemon.siteAuth.delete`：先乐观移除，成功后以服务端返回的新列表为准；失败则恢复并抛出。
    func delete(_ site: String, using session: any HostSession) async throws(HostError) {
        guard !deleting.contains(site) else { return }
        let before = entries
        deleting.insert(site)
        entries.removeAll { $0.site == site }
        defer { deleting.remove(site) }
        do throws(HostError) {
            let list: [SiteAuthEntryDto] = try await session.call(
                HostMethod.daemonSiteAuthDelete, params: SiteAuthDeleteParams(site: site)
            )
            entries = list
        } catch {
            entries = before
            throw error
        }
    }

    /// `daemon.siteAuth.clear`：返回清空后的列表。
    func clearAll(using session: any HostSession) async throws(HostError) {
        guard !isClearing else { return }
        isClearing = true
        defer { isClearing = false }
        entries = try await session.call(HostMethod.daemonSiteAuthClear)
    }

    /// 编辑表单回填：`daemon.siteAuth.get`（明文密码仅供表单，不进入列表状态）；站点已不存在 → nil。
    func credential(for site: String, using session: any HostSession) async throws(HostError) -> SiteAuthCredentialDto? {
        try await session.call(HostMethod.daemonSiteAuthGet, params: SiteAuthGetParams(site: site))
    }

    /// `daemon.siteAuth.save` 后重新拉取列表（保存结果只带脱敏条目，且站点可能被服务端归一化）。
    func save(_ request: SiteAuthSaveRequest, using session: any HostSession) async throws(HostError) {
        let _: SiteAuthEntryDto = try await session.call(HostMethod.daemonSiteAuthSave, params: request)
        do throws(HostError) {
            entries = try await session.call(HostMethod.daemonSiteAuthList)
            phase = .loaded
        } catch {
            // 保存已成功；列表刷新失败不应让表单报错——下次进入页面会重新加载。
            phase = entries.isEmpty ? .failed(ErrorText.describe(error)) : .loaded
        }
    }
}
