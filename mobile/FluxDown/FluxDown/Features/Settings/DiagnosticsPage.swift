import FluxDomain
import FluxUI
import SwiftUI
import UIKit

/// S13 · 诊断：汇总卡 + 运行诊断 / 复制报告，「此设备」检查、「下载主机」检查（`agent.diagnostics.run`）、
/// 主机环境信息与日志工具。设备检查进页面即自动运行（快，纯本地）；主机诊断慢（≈60 s）：行内进度，页面其余部分照常可用。
struct DiagnosticsPage: View {
    @Environment(AppContainer.self) private var container
    @Environment(HostStore.self) private var store
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL

    @State private var model = DiagnosticsModel()

    var body: some View {
        let state = store.state
        let live = state.connection == .live
        SettingsPage(title: L("settingsCatDoctor")) {
            summarySection
            deviceSection
            hostSection(live: live)
            if let host = model.host { environmentSection(host) }
            LogsSection()
        }
        .fluxAnimation(.smooth, value: model.host)
        .fluxAnimation(.smooth, value: model.device)
        .task { await model.refreshDevice(container: container) }
        .onChange(of: scenePhase) { _, phase in
            // 从系统设置改完权限回来：设备检查自动刷新。
            if phase == .active { Task { await model.refreshDevice(container: container) } }
        }
    }

    // MARK: 汇总

    private var summarySection: some View {
        Section {
            summaryRow
                .settingsRow("diagnostics.summary")
            SettingsActionRow(
                title: L("doctorRun"), runningTitle: L("doctorRunning"), systemImage: "stethoscope",
                isRunning: model.isRunning
            ) {
                Task { await model.run(container: container) }
            }
            .disabled(model.isBusy && !model.isRunning)
            .settingsRow("diagnostics.run")
            if model.hasRun {
                SettingsActionRow(title: L("doctorCopyReport"), systemImage: FluxSymbol.copy) {
                    UIPasteboard.general.string = model.reportText(container: container)
                    container.toasts.show(text: L("doctorCopied"), tone: .success)
                }
                .settingsRow("diagnostics.copy")
                ShareLink(item: model.reportText(container: container)) {
                    Label(L("mobileDiagShareReport"), systemImage: FluxSymbol.share)
                        .frame(minHeight: 44, alignment: .leading)
                }
                .settingsRow("diagnostics.share")
            }
        } header: {
            Text(L("doctorTitle"))
        } footer: {
            Text(L("mobileDiagDesc"))
        }
    }

    private var summaryRow: some View {
        let issues = model.issueCount
        let (symbol, color, text): (String, Color, String) = if !model.hasRun {
            ("stethoscope", .secondary, L("doctorNeverRun"))
        } else if issues == 0 {
            ("checkmark.seal.fill", Color.fdStatusSeedingText, L("doctorAllHealthy"))
        } else {
            ("exclamationmark.triangle.fill", Color.fdStatusWarningText, L("doctorIssuesFound", ["n": issues]))
        }
        return VStack(alignment: .leading, spacing: 4) {
            Label {
                Text(text)
            } icon: {
                Image(systemName: symbol).foregroundStyle(color).accessibilityHidden(true)
            }
            .font(.headline)
            if let last = model.lastRunAt {
                Text(L("doctorLastRun", ["time": last.formatted(date: .abbreviated, time: .standard)]))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: 此设备

    private var deviceSection: some View {
        Section {
            if model.device.isEmpty {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(L("doctorRunning")).foregroundStyle(.secondary)
                }
                .frame(minHeight: 44)
            } else {
                ForEach(model.device) { check in
                    DiagnosticsCheckRow(
                        title: check.title, subtitle: nil, detail: check.detail, level: check.level, hint: check.hint,
                        repairTitle: check.canOpenSettings ? L("doctorActionOpenSettings") : nil,
                        repairBusy: false, repairDisabled: false
                    ) {
                        openSystemSettings()
                    }
                    .settingsRow(Self.deviceRowID(check.id))
                }
            }
        } header: {
            Text(L("mobileDiagSectionDevice"))
        }
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        openURL(url) { accepted in
            if !accepted { container.toasts.show(text: L("mobileNoSettingsApp"), tone: .error) }
        }
    }

    // MARK: 下载主机

    @ViewBuilder
    private func hostSection(live: Bool) -> some View {
        Section {
            if let host = model.host {
                if host.checks.isEmpty {
                    SettingsStatusLine(text: L("doctorAllHealthy"), tone: .success)
                }
                ForEach(Array(host.checks.enumerated()), id: \.offset) { index, check in
                    let tag = DiagnosticsModel.tag(index, check)
                    let repair = DiagnosticsLogic.repair(for: check, hasLabel: { L10n.shared.has($0) })
                    DiagnosticsCheckRow(
                        title: DiagnosticsModel.label(check), subtitle: check.target.isEmpty ? nil : check.target,
                        detail: check.detail, level: check.level, hint: DiagnosticsModel.hintText(check) ?? "",
                        repairTitle: repair.map { L(DiagnosticsLogic.actionKey($0.action)) },
                        repairBusy: model.repairingTag == tag,
                        repairDisabled: model.isBusy || !live
                    ) {
                        Task { await model.repair(check, tag: tag, container: container) }
                    }
                    .settingsRow("diagnostics.host.\(tag)")
                }
            } else if live {
                Text(L("mobileDiagHostNotRun")).foregroundStyle(.secondary)
            }
            if !live {
                SettingsStatusLine(text: L("localServiceDisconnected"), tone: .warning, systemImage: FluxSymbol.offline)
            }
        } header: {
            Text(L("mobileDiagSectionHost", ["name": container.host.displayName]))
        }
    }

    private func environmentSection(_ host: DiagnosticsModel.HostReport) -> some View {
        Section {
            SettingsInfoRow(title: L("currentVersion"), value: host.report.appVersion)
            SettingsInfoRow(title: L("mobileDiagEnvPlatform"), value: host.report.platform)
            SettingsInfoRow(title: L("doctorCheckDataDir"), value: host.report.agentDataDir)
            if let env = host.env {
                SettingsInfoRow(
                    title: L("doctorCheckDaemon"),
                    value: "\(env.service.serviceVersion) · "
                        + L("mobileDiagEnvCounts", ["tasks": env.tasks, "queues": env.queues, "groups": env.groups])
                )
                SettingsInfoRow(title: L("doctorCheckLogDir"), value: env.logDir)
            }
        } header: {
            Text(L("doctorEnvTitle"))
        }
    }

    static func deviceRowID(_ id: MobileCheckID) -> String { "diagnostics.device.\(id.rawValue)" }
}

extension DiagnosticsPage {
    /// 设置搜索索引：运行 / 复制报告、各设备检查、日志行（主机检查项取决于运行结果，不入索引）。
    static func searchEntries(_ ctx: SettingsSearchContext) -> [SettingsEntry] {
        let name = L("settingsCatDoctor")
        var entries = [
            SettingsEntry(
                id: "diagnostics.run", route: .diagnostics, title: L("doctorRun"), detail: L("mobileDiagDesc"),
                breadcrumb: name, symbol: "stethoscope"
            ),
            SettingsEntry(
                id: "diagnostics.copy", route: .diagnostics, title: L("doctorCopyReport"), detail: "",
                breadcrumb: name, symbol: "stethoscope"
            ),
        ]
        let device = "\(name) › \(L("mobileDiagSectionDevice"))"
        for id in MobileCheckID.allCases {
            entries.append(SettingsEntry(
                id: deviceRowID(id), route: .diagnostics, title: L(id.titleKey), detail: "",
                breadcrumb: device, symbol: "iphone"
            ))
        }
        entries += LogsSection.searchEntries(ctx, route: .diagnostics, breadcrumb: "\(name) › \(L("mobileLogsTitle"))")
        return entries
    }

    /// 设置首页读数：最近一次运行的结论；从未运行为 nil。
    static func readout(_ ctx: SettingsSearchContext) -> String? {
        DiagnosticsSummary.shared.readout
    }
}

// MARK: - 检查行

/// 一条检查：标题 + 级别徽标（图标 + 文字）、详情、可展开处理建议、可选修复按钮。
private struct DiagnosticsCheckRow: View {
    let title: String
    let subtitle: String?
    let detail: String
    let level: DiagnosticLevel
    let hint: String
    let repairTitle: String?
    let repairBusy: Bool
    let repairDisabled: Bool
    let onRepair: () -> Void

    @State private var hintExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LeadingTrailingRow(alignment: .firstTextBaseline, spacing: 8) {
                titleBlock
            } trailing: {
                DiagnosticsLevelBadge(level: level)
            }
            if !detail.isEmpty {
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
            if !hint.isEmpty {
                DisclosureGroup(L("mobileDiagHowToFix"), isExpanded: $hintExpanded) {
                    Text(hint)
                        .font(.footnote)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 4)
                }
                .font(.footnote.weight(.medium))
                .fluxAnimation(.smooth, value: hintExpanded)
            }
            if let repairTitle {
                Button(action: onRepair) {
                    HStack(spacing: 8) {
                        if repairBusy { ProgressView() }
                        Text(repairBusy ? L("doctorRepairing") : repairTitle)
                    }
                    .frame(minHeight: 44, alignment: .leading)
                    .contentShape(.rect)
                }
                .buttonStyle(.borderless)
                .disabled(repairDisabled || repairBusy)
            }
        }
        .accessibilityElement(children: .contain)
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            if let subtitle {
                Text(subtitle)
                    .font(.fluxMono)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
        }
    }
}

/// 级别徽标：图标 + 文字（状态不只靠颜色）。
private struct DiagnosticsLevelBadge: View {
    let level: DiagnosticLevel

    var body: some View {
        let (tone, symbol): (BadgeTone, String) = switch level {
        case .ok: (.success, "checkmark.circle.fill")
        case .warn: (.warning, "exclamationmark.triangle.fill")
        case .error: (.failure, "xmark.octagon.fill")
        case .info, .unknown: (.neutral, "info.circle.fill")
        }
        StatusBadge(text: L(DiagnosticsLogic.levelKey(level)), tone: tone, systemImage: symbol)
    }
}
