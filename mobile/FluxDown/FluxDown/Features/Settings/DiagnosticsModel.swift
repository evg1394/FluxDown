import FluxDomain
import FluxUI
import Foundation
import Observation
import UIKit
import os

/// 诊断页状态：设备检查（快、本地）+ 主机诊断（`agent.diagnostics.run`，慢 ≈60 s）+ 修复。
/// 设备检查进入页面 / 回到前台时自动刷新；主机诊断只在用户点「运行诊断」或修复后运行。
@MainActor
@Observable
final class DiagnosticsModel {
    nonisolated struct HostReport: Equatable {
        var report: DiagnosticsReportDto
        /// 已滤掉桌面专属项。
        var checks: [DiagnosticCheckDto]
        var env: DaemonDiagnosticsDescribe?
    }

    private(set) var device: [MobileCheck] = []
    private(set) var host: HostReport?
    private(set) var isRunning = false
    /// 正在修复的检查项（`index-id-target`：同名队列 / 目录会产生相同的 id·target）。
    private(set) var repairingTag: String?
    private(set) var lastRunAt: Date?
    /// 点过「运行诊断」（汇总卡据此区分「尚未运行」）。
    private(set) var hasRun = false

    @ObservationIgnored private let log = Logger(subsystem: "com.fluxdown.app", category: "diagnostics")

    var isBusy: Bool { isRunning || repairingTag != nil }

    /// warn + error 的条数（设备 + 主机）。
    var issueCount: Int {
        MobileChecks.issueCount(device) + (host.map { DiagnosticsLogic.issueCount($0.checks) } ?? 0)
    }

    static func tag(_ index: Int, _ check: DiagnosticCheckDto) -> String { "\(index)-\(check.id)-\(check.target)" }

    // MARK: 设备检查

    func refreshDevice(container: AppContainer) async {
        device = await MobileCheckRunner.run(container: container)
    }

    // MARK: 运行

    func run(container: AppContainer) async {
        guard !isBusy else { return }
        isRunning = true
        defer { isRunning = false }
        hasRun = true

        // 设备检查先出结果；主机诊断随后（慢）。
        await refreshDevice(container: container)

        var hostFailed = false
        if container.store.state.connection == .live {
            do throws(HostError) {
                host = try await fetchHost(container: container, keepingEnv: nil)
            } catch {
                hostFailed = true
                container.toasts.show(text: ErrorText.describe(error), tone: .error)
            }
        } else {
            host = nil
            hostFailed = true
            container.toasts.show(text: L("localServiceDisconnected"), tone: .warning)
        }
        lastRunAt = Date()
        publish(container: container)
        guard !hostFailed else { return }
        let issues = issueCount
        if issues == 0 {
            container.toasts.show(text: L("doctorRunDoneHealthy"), tone: .success)
        } else {
            container.toasts.show(text: L("doctorRunDoneIssues", ["n": issues]), tone: .warning)
        }
    }

    /// 执行修复；成功与否都重新诊断（可能只完成了一部分）。
    func repair(_ check: DiagnosticCheckDto, tag: String, container: AppContainer) async {
        guard !isBusy, let repair = DiagnosticsLogic.repair(for: check, hasLabel: { L10n.shared.has($0) }) else { return }
        repairingTag = tag
        defer { repairingTag = nil }
        do throws(HostError) {
            try await container.session.callVoid(HostMethod.agentDiagnosticsRepair, params: repair)
        } catch {
            container.toasts.show(text: Self.repairErrorText(error), tone: .error)
        }
        do throws(HostError) {
            host = try await fetchHost(container: container, keepingEnv: host?.env)
            lastRunAt = Date()
            publish(container: container)
        } catch {
            container.toasts.show(text: ErrorText.describe(error), tone: .error)
        }
    }

    static func repairErrorText(_ error: HostError) -> String {
        if let key = DiagnosticsLogic.repairErrorKey(reason: error.reason) { return L(key) }
        return L("doctorRepairFailed", ["error": ErrorText.describe(error)])
    }

    // MARK: 内部

    /// `agent.diagnostics.run` + `daemon.diagnostics.describe`（后者失败不影响报告）。
    private func fetchHost(container: AppContainer, keepingEnv previous: DaemonDiagnosticsDescribe?) async throws(HostError) -> HostReport {
        let report: DiagnosticsReportDto = try await container.session.call(HostMethod.agentDiagnosticsRun)
        var env = previous
        do throws(HostError) {
            env = try await container.session.call(HostMethod.daemonDiagnosticsDescribe)
        } catch {
            log.notice("describe unavailable: \(error.description, privacy: .public)")
        }
        return HostReport(report: report, checks: DiagnosticsLogic.visibleChecks(report.checks), env: env)
    }

    private func publish(container: AppContainer) {
        DiagnosticsSummary.shared.record(hostID: container.host.id, issues: issueCount)
    }

    // MARK: 报告

    func reportText(container: AppContainer) -> String {
        let device = self.device.map {
            DiagnosticsLogic.ReportLine(level: $0.level, label: $0.title, target: "", detail: $0.detail, hint: $0.hint)
        }
        var hostPart: DiagnosticsLogic.HostPart?
        if let host {
            let lines = host.checks.map { check in
                DiagnosticsLogic.ReportLine(
                    level: check.level, label: Self.label(check), target: check.target, detail: check.detail,
                    hint: Self.hintText(check) ?? check.hint
                )
            }
            hostPart = DiagnosticsLogic.HostPart(
                name: container.host.displayName, appVersion: host.report.appVersion, platform: host.report.platform,
                dataDir: host.report.agentDataDir, daemonConnected: host.report.daemonConnected,
                daemonSummary: host.env.map(Self.envSummary), lines: lines
            )
        }
        let ui = UIDevice.current
        return DiagnosticsLogic.renderReport(DiagnosticsLogic.ReportInput(
            appVersion: SettingsAppVersion.current,
            deviceDescription: "\(ui.systemName) \(ui.systemVersion) · \(ui.model)",
            generatedAt: lastRunAt ?? Date(), device: device, host: hostPart
        ))
    }

    /// 检查项标题（不含目标）：无文案的未知 id 回退为 id 本身。
    static func label(_ check: DiagnosticCheckDto) -> String {
        let key = DiagnosticsLogic.checkKey(check.id)
        return L10n.shared.has(key) ? L(key) : check.id
    }

    /// 提示文案；无该提示码的文案时 nil。
    static func hintText(_ check: DiagnosticCheckDto) -> String? {
        guard !check.hint.isEmpty else { return nil }
        let key = DiagnosticsLogic.hintKey(check.hint)
        return L10n.shared.has(key) ? L(key) : nil
    }

    static func envSummary(_ env: DaemonDiagnosticsDescribe) -> String {
        [
            env.service.serviceVersion,
            L("mobileDiagEnvCounts", ["tasks": env.tasks, "queues": env.queues, "groups": env.groups]),
            "log dir \(env.logDir)",
        ].joined(separator: " · ")
    }
}
