import FluxDomain
import FluxUI
import SwiftUI

/// S12 · Webhook：端点列表（配置键 `webhook.endpoints`）+ 推送记录（`daemon.webhookDeliveries` 分区）。
///
/// 端点写入一律「读最新配置 → 变换 → `expectedRevision` 补丁；冲突时重读并重放（≤3 次）」，串行执行
/// （`WebhookEndpointWriter`；AGENTS.md §5）。
struct WebhookPage: View {
    @Environment(AppContainer.self) private var container

    var body: some View {
        WebhookContent(container: container)
    }
}

private struct WebhookContent: View {
    @Environment(HostStore.self) private var store
    @State private var model: WebhookModel
    @State private var deliveries = SectionMemo<[WebhookDelivery]>(empty: [])
    @State private var editing: EditorTarget?
    @State private var deleteTarget: WebhookEndpoint?
    @State private var logFilter = ""

    private nonisolated enum EditorTarget: Identifiable {
        case new
        case edit(WebhookEndpoint)

        var id: String {
            switch self {
            case .new: "new"
            case let .edit(endpoint): endpoint.id
            }
        }
    }

    /// 页面只渲染最近这么多条（GPUI 同）。
    private static let visibleDeliveries = 50

    init(container: AppContainer) {
        _model = State(initialValue: WebhookModel(container: container))
    }

    var body: some View {
        let state = store.state
        let endpoints = WebhookEndpoint.parseList(state.config[WebhookEndpoint.configKey])
        let log = deliveries.value(state.sections[HostSection.daemonWebhookDeliveries])
        let readOnly = state.isReadOnly
        let blank = endpoints.isEmpty && log.isEmpty
        SettingsPage(title: L("webhookNavTitle"), showsReadOnlyBanner: true) {
            if !blank {
                endpointSection(endpoints, log: log, readOnly: readOnly)
                logSection(endpoints, log: log, readOnly: readOnly)
            }
        }
        .overlay {
            if blank {
                EmptyStateView(L("webhookEmptyTitle"), message: L("webhookEmptyDesc"), systemImage: FluxSymbol.webhook) {
                    Button(L("webhookAddEndpoint"), systemImage: FluxSymbol.add) { editing = .new }
                        .disabled(readOnly)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button(L("webhookAddEndpoint"), systemImage: FluxSymbol.add) { editing = .new }
                    .disabled(readOnly)
            }
        }
        .sheet(item: $editing) { target in
            switch target {
            case .new: WebhookEditorSheet(existing: nil, model: model)
            case let .edit(endpoint): WebhookEditorSheet(existing: endpoint, model: model)
            }
        }
    }

    // MARK: 端点

    @ViewBuilder
    private func endpointSection(_ endpoints: [WebhookEndpoint], log: [WebhookDelivery], readOnly: Bool) -> some View {
        Section {
            if endpoints.isEmpty {
                Label(L("webhookEmptyTitle"), systemImage: "bolt.horizontal")
                    .foregroundStyle(.secondary)
            }
            ForEach(endpoints) { endpoint in
                WebhookEndpointRow(
                    endpoint: endpoint,
                    latest: latestWebhookDelivery(log, endpointId: endpoint.id),
                    testing: model.testingId == endpoint.id,
                    testBusy: model.testingId != nil,
                    report: model.testReport?.endpointId == endpoint.id ? model.testReport : nil,
                    readOnly: readOnly,
                    onToggle: { enabled in Task { await model.setEnabled(endpoint, enabled) } },
                    onEdit: { editing = .edit(endpoint) },
                    onTest: { Task { await model.test(endpoint) } },
                    onDelete: { deleteTarget = endpoint }
                )
                .alert(
                    L("webhookRowDeleteConfirm"),
                    isPresented: Binding(
                        get: { deleteTarget?.id == endpoint.id },
                        set: { if !$0 { deleteTarget = nil } }
                    )
                ) {
                    Button(L("webhookRowDelete"), role: .destructive) { Task { await model.remove(endpoint) } }
                    Button(L("cancel"), role: .cancel) {}
                } message: {
                    Text(endpoint.name)
                }
            }
            Button {
                editing = .new
            } label: {
                Label(L("webhookAddEndpoint"), systemImage: "plus.circle.fill")
            }
            .disabled(readOnly)
        } header: {
            Text(L("notifyGroupWebhook"))
        } footer: {
            Text(L("webhookSemantics"))
        }
    }

    // MARK: 推送记录

    @ViewBuilder
    private func logSection(_ endpoints: [WebhookEndpoint], log: [WebhookDelivery], readOnly: Bool) -> some View {
        let known = Set(endpoints.map(\.id))
        let filter = logFilter.isEmpty || known.contains(logFilter) || log.contains { $0.endpointId == logFilter } ? logFilter : ""
        let visible = log
            .filter { filter.isEmpty || $0.endpointId == filter }
            .sorted { $0.timestampMs > $1.timestampMs }
            .prefix(Self.visibleDeliveries)
        Section {
            if let text = model.simulateText {
                Text(text).font(.footnote).foregroundStyle(.secondary)
            }
            if visible.isEmpty {
                Text(L("webhookLogEmpty")).font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(Array(visible)) { delivery in
                NavigationLink {
                    WebhookDeliveryDetailPage(delivery: delivery)
                } label: {
                    DeliveryRow(delivery: delivery)
                }
            }
            Button {
                Task { await model.simulate() }
            } label: {
                HStack {
                    Label(model.simulating ? L("webhookLogPending") : L("webhookLogSimulate"), systemImage: "paperplane")
                    if model.simulating {
                        Spacer()
                        ProgressView()
                    }
                }
            }
            .disabled(readOnly || model.simulating)
            Button(role: .destructive) {
                Task { await model.clearDeliveries() }
            } label: {
                Label(L("webhookLogClear"), systemImage: FluxSymbol.delete)
            }
            .disabled(readOnly || log.isEmpty || model.clearing)
        } header: {
            HStack {
                Text(L("webhookDeliveryLog"))
                Spacer()
                if endpoints.count > 1 || !filter.isEmpty {
                    filterMenu(endpoints, filter: filter)
                }
            }
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("webhookLogSubtitle"))
                Text(L("webhookLogSimulateHint"))
            }
        }
    }

    private func filterMenu(_ endpoints: [WebhookEndpoint], filter: String) -> some View {
        Menu {
            Picker(L("webhookLogFilterLabel"), selection: $logFilter) {
                Text(L("webhookLogFilterAll")).tag("")
                ForEach(endpoints) { Text($0.name).tag($0.id) }
            }
        } label: {
            Label(
                filter.isEmpty ? L("webhookLogFilterAll") : (endpoints.first { $0.id == filter }?.name ?? filter),
                systemImage: "line.3.horizontal.decrease.circle"
            )
            .font(.footnote)
            .textCase(nil)
            .frame(minHeight: 44)
            .contentShape(.rect)
        }
        .accessibilityLabel(L("webhookLogFilterLabel"))
    }
}

// MARK: - 端点行

private struct WebhookEndpointRow: View {
    let endpoint: WebhookEndpoint
    let latest: WebhookDelivery?
    let testing: Bool
    let testBusy: Bool
    let report: WebhookTestReport?
    let readOnly: Bool
    let onToggle: (Bool) -> Void
    let onEdit: () -> Void
    let onTest: () -> Void
    let onDelete: () -> Void

    @Environment(AppContainer.self) private var container

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Toggle(endpoint.name, isOn: Binding(get: { endpoint.enabled }, set: { onToggle($0) }))
                .labelsHidden()
                .tint(Color.fdToggleOn)
                .disabled(readOnly)
            Button(action: onEdit) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(endpoint.name.isEmpty ? endpoint.id : endpoint.name).font(.headline)
                    Text(verbatim: "\(endpoint.url) · \(endpoint.events.joined(separator: ", "))")
                        .font(.fluxMono)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .truncationMode(.middle)
                    health
                    if let report {
                        Label(report.text, systemImage: report.success ? FluxSymbol.success : "xmark.octagon.fill")
                            .font(.footnote)
                            .foregroundStyle(report.success ? Color.fdStatusSeedingText : Color.fdStatusFailedText)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(readOnly)
            if testing { ProgressView() }
        }
        // 删除只弹确认框：不用 `role: .destructive`（会让 List 先行移除该行而数据未变，导致行数不一致崩溃）。
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(L("webhookRowDelete"), systemImage: FluxSymbol.delete, action: onDelete)
                .tint(Color.fdStatusFailed)
                .disabled(readOnly)
        }
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            Button(L("webhookRowTest"), systemImage: "paperplane", action: onTest)
                .tint(.accentColor)
                .disabled(readOnly || testBusy)
        }
        .contextMenu {
            Button(L("webhookRowEdit"), systemImage: FluxSymbol.edit, action: onEdit).disabled(readOnly)
            Button(L("webhookRowTest"), systemImage: "paperplane", action: onTest).disabled(readOnly || testBusy)
            Button(L("copyUrl"), systemImage: FluxSymbol.copy) {
                ExtensionsClipboard.copy(endpoint.url)
                container.toasts.show(text: L("webhookCopied"), tone: .success)
            }
            Divider()
            Button(L("webhookRowDelete"), systemImage: FluxSymbol.delete, role: .destructive, action: onDelete).disabled(readOnly)
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private var health: some View {
        if !endpoint.enabled {
            Label(L("webhookHealthDisabled"), systemImage: "pause.circle")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } else if let latest {
            if latest.success {
                Label(L("webhookHealthOk", ["time": ExtensionsFormat.latency(latest.latencyMs)]), systemImage: FluxSymbol.success)
                    .font(.footnote)
                    .foregroundStyle(Color.fdStatusSeedingText)
            } else {
                Label(
                    L("webhookHealthFail", ["detail": latest.error.isEmpty ? ExtensionsFormat.httpStatus(latest.statusCode) : latest.error]),
                    systemImage: FluxSymbol.warning
                )
                .font(.footnote)
                .foregroundStyle(Color.fdStatusFailedText)
            }
        } else {
            Label(L("webhookHealthNone"), systemImage: "circle.dashed")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - 投递

private struct DeliveryRow: View {
    let delivery: WebhookDelivery

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: "\(delivery.endpointName) · \(delivery.event)")
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(ExtensionsFormat.timestamp(delivery.timestampMs))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(delivery.success ? Color.secondary : Color.fdStatusFailedText)
            }
            Text(verbatim: "\(ExtensionsFormat.deliverySummary(delivery)) · \(L("webhookAttempts", ["n": delivery.attempts]))")
                .font(.footnote)
                .foregroundStyle(delivery.success ? Color.secondary : Color.fdStatusFailedText)
                .lineLimit(2)
        }
        .accessibilityElement(children: .combine)
    }
}

/// 推送详情：请求头 / 请求体 / 响应（4xx 不重试提示）。
private struct WebhookDeliveryDetailPage: View {
    let delivery: WebhookDelivery

    var body: some View {
        Form {
            Section {
                LabeledContent(L("webhookFieldName")) { Text(delivery.endpointName) }
                LabeledContent(L("webhookFieldEvents")) { Text(delivery.event) }
                LabeledContent(L("webhookFieldUrl")) { Text(delivery.url).font(.fluxMono).multilineTextAlignment(.trailing).textSelection(.enabled) }
                LabeledContent(L("mobileWebhookDeliveryStatus")) { Text(ExtensionsFormat.deliverySummary(delivery)) }
                LabeledContent(L("mobileWebhookDeliveryAttempts")) { Text(delivery.attempts, format: .number) }
                LabeledContent(L("mobileWebhookDeliveryTime")) { Text(ExtensionsFormat.timestamp(delivery.timestampMs)) }
            }
            if (400 ..< 500).contains(delivery.statusCode) {
                Section {
                    Label(L("webhookLogHint4xx"), systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(Color.fdStatusWarningText)
                }
            }
            if !delivery.requestHeaders.isEmpty {
                Section(L("mobileWebhookRequestHeaders")) { mono(delivery.requestHeaders) }
            }
            if !delivery.requestBody.isEmpty {
                Section(L("mobileWebhookRequestBody")) { mono(delivery.requestBody) }
            }
            if !delivery.responseBody.isEmpty {
                Section(L("webhookLogResponse")) { mono(delivery.responseBody) }
            }
        }
        .navigationTitle(delivery.endpointName)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func mono(_ text: String) -> some View {
        Text(text)
            .font(.fluxMono)
            .textSelection(.enabled)
    }
}
