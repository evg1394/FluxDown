import FluxDomain
import FluxUI
import SwiftUI

/// V1a 全部已信任设备：可搜索（名称 / 平台）的完整列表；行点击进入 V3，滑动重命名 / 删除，下拉刷新。
struct AllDevicesScreen: View {
    @Environment(AppContainer.self) private var container
    @Environment(DevicesModel.self) private var model

    @State private var query = ""
    @State private var renaming: CloudDeviceRecord?
    @State private var deleting: CloudDeviceRecord?

    var body: some View {
        let state = container.store.state
        let presenceKnown = model.presenceKnown(state)
        let readOnly = state.isReadOnly
        let sorted = DeviceRules.sorted(model.cloudRecords, presenceKnown: presenceKnown)
        let rows = DeviceRules.filter(sorted, query: query)
        let counts = state.has(HostCapability.agentRemoteTasks)
            ? RemoteTaskRules.countByTarget(model.remoteTasks(state))
            : [:]
        List {
            ForEach(rows) { record in
                NavigationLink(value: DevicesRoute.cloudDevice(id: record.id)) {
                    CloudDeviceRow(record: record, presenceKnown: presenceKnown, remoteCount: counts[record.deviceId] ?? 0)
                }
                .cloudDeviceActions(record, enabled: !readOnly, renaming: $renaming, deleting: $deleting)
            }
        }
        .listStyle(.insetGrouped)
        .overlay { emptyOverlay(rows: rows, total: sorted.count) }
        .navigationTitle(L("accountDevicesManageAllTitle"))
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("accountDevicesSearchHint"))
        .refreshable {
            if let error = await model.loadCloud() {
                container.toasts.show(text: AccountText.error(error), tone: .error)
            }
        }
        .fluxAnimation(.smooth, value: rows.map(\.id))
        .cloudDeviceRenameAlert(renaming: $renaming)
    }

    /// 空态放在 `overlay`（不放进 List 行里被压成固定行高）。
    @ViewBuilder
    private func emptyOverlay(rows: [CloudDeviceRecord], total: Int) -> some View {
        if rows.isEmpty {
            if total > 0 {
                ContentUnavailableView(L("accountDevicesSearchNoResults"), systemImage: FluxSymbol.search)
            } else if case .failed = model.cloudPhase {
                ContentUnavailableView(L("accountDevicesLoadFailed"), systemImage: "exclamationmark.triangle")
            } else if model.cloudPhase == .loading || model.cloudPhase == .idle {
                ProgressView()
                    .accessibilityLabel(L("mobileLoading"))
            } else {
                ContentUnavailableView(L("accountDevicesEmpty"), systemImage: "laptopcomputer")
            }
        }
    }
}
