import FluxDomain
import FluxUI
import SwiftUI

/// X4 · 文件已存在（`file_exists_behavior = ask`）：所有待答的 `fileExists` 请求聚合在同一个 Sheet。
///
/// - 一条 → 卡片（`FileConflictCard`）；多条 → 列表（点行进卡片）+ 底栏批量「全部重命名 / 覆盖 / 跳过 / 取消」。
/// - 请求从 `state.selections` 消失（含其它设备已答复 / 超时按重命名处理）自动退出；最后一条消失即关闭。
/// - 下拉关闭或「稍后决定」≠ 答复：请求继续等待，根视图记入 `dismissedSelections`，横幅 / 任务行角标可重新打开。
/// - 倒计时只显示最早到期的请求；到期由主机按重命名处理，客户端不发请求。
struct FileConflictSheet: View {
    @Environment(AppContainer.self) private var container

    var body: some View {
        FileConflictHost(container: container)
    }
}

private struct FileConflictHost: View {
    let container: AppContainer

    @Environment(\.dismiss) private var dismiss
    @State private var resolver: SelectionResolver
    /// 列表里已进入详情的请求 id。
    @State private var path: [String] = []
    /// 请求清空后的退场动画期间沿用最后一次内容。
    @State private var last: [SelectionRequest] = []

    init(container: AppContainer) {
        self.container = container
        _resolver = State(initialValue: SelectionResolver(container: container))
    }

    private var requests: [SelectionRequest] { container.store.state.selections.fileConflicts }

    var body: some View {
        let shown = requests.isEmpty ? last : requests
        NavigationStack(path: $path) {
            root(shown)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L("fileConflictLater")) { dismiss() }
                    }
                }
                .navigationDestination(for: String.self) { id in
                    if let request = shown.first(where: { $0.requestId == id }), let conflict = request.fileConflict {
                        FileConflictCard(request: request, conflict: conflict, resolver: resolver, deadline: Self.deadline(shown))
                    } else {
                        Color.clear
                    }
                }
        }
        .presentationDetents(shown.count > 1 ? [.large] : [.medium, .large])
        .presentationDragIndicator(.visible)
        .onChange(of: requests, initial: true) { old, new in
            if new.isEmpty {
                dismiss()
                return
            }
            last = new
            let alive = Set(new.map(\.requestId))
            path.removeAll { !alive.contains($0) }
            // 消失的请求不是我们答复的 → 其它设备已完成选择（或主机超时按重命名处理）。
            let vanished = old.map(\.requestId).filter { !alive.contains($0) }
            if vanished.contains(where: { !resolver.wasOurs($0) }) {
                container.toasts.show(text: L("mobileSelectionResolvedElsewhere"), tone: .info, systemImage: "checkmark.circle")
            }
        }
    }

    @ViewBuilder
    private func root(_ shown: [SelectionRequest]) -> some View {
        if shown.count == 1, let request = shown.first, let conflict = request.fileConflict {
            FileConflictCard(request: request, conflict: conflict, resolver: resolver, deadline: Self.deadline(shown))
                .id(request.requestId)
        } else {
            FileConflictList(requests: shown, resolver: resolver, deadline: Self.deadline(shown))
        }
    }

    /// 最早到期的请求（主机届时按重命名处理）。
    private static func deadline(_ requests: [SelectionRequest]) -> Date {
        let ms = requests.map(\.deadlineUnixMs).min() ?? 0
        return Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    }
}

/// 多条询问：每行一个文件（大小 / 修改时间摘要），点进去逐个决定；底栏批量。
private struct FileConflictList: View {
    let requests: [SelectionRequest]
    let resolver: SelectionResolver
    let deadline: Date

    @State private var confirmOverwriteAll = false

    private var canSkipAny: Bool { !FileConflictBulk.targets(requests, action: .skip).isEmpty }

    var body: some View {
        List {
            Section {
                ForEach(requests) { request in
                    if let conflict = request.fileConflict {
                        NavigationLink(value: request.requestId) {
                            row(conflict)
                        }
                        .contextMenu { rowMenu(request, conflict) }
                    }
                }
            } header: {
                Text(L("fileConflictDesc")).textCase(nil)
            } footer: {
                FileConflictCountdown(deadline: deadline)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("fileConflictTitleMany", ["count": requests.count]))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .bottomBar) {
                Menu {
                    Button(L("fileConflictOverwriteAll"), systemImage: FluxSymbol.conflictOverwrite, role: .destructive) {
                        confirmOverwriteAll = true
                    }
                    Button(L("fileConflictSkipAll"), systemImage: FluxSymbol.conflictSkip) {
                        resolver.resolveAll(requests, outcome: .fileExists(action: .skip))
                    }
                    .disabled(!canSkipAny)
                    Button(L("fileConflictCancelAll"), systemImage: FluxSymbol.conflictCancel, role: .destructive) {
                        resolver.resolveAll(requests, outcome: .cancelled)
                    }
                } label: {
                    Label(L("mobileSelectionToolbar"), systemImage: "ellipsis")
                }
                .alert(L("fileConflictOverwriteAll"), isPresented: $confirmOverwriteAll) {
                    Button(L("fileConflictOverwriteAll"), role: .destructive) {
                        resolver.resolveAll(requests, outcome: .fileExists(action: .overwrite))
                    }
                    Button(L("cancel"), role: .cancel) {}
                } message: {
                    Text(L("fileConflictOverwriteHint"))
                }
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button(L("fileConflictRenameAll")) {
                    resolver.resolveAll(requests, outcome: .fileExists(action: .rename))
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    @ViewBuilder
    private func rowMenu(_ request: SelectionRequest, _ conflict: FileConflict) -> some View {
        Button(L("fileConflictRename"), systemImage: FluxSymbol.conflictRename) {
            resolve(request, .fileExists(action: .rename))
        }
        Button(L("fileConflictOverwrite"), systemImage: FluxSymbol.conflictOverwrite, role: .destructive) {
            resolve(request, .fileExists(action: .overwrite))
        }
        if conflict.allows(.skip) {
            Button(L("fileConflictSkip"), systemImage: FluxSymbol.conflictSkip) {
                resolve(request, .fileExists(action: .skip))
            }
        }
        Button(L("fileConflictCancelDownload"), systemImage: FluxSymbol.conflictCancel) {
            resolver.cancel(request, onSuccess: {})
        }
    }

    private func resolve(_ request: SelectionRequest, _ outcome: SelectionOutcome) {
        resolver.resolve(request, outcome, successToast: nil, userInitiated: true, onSuccess: {})
    }

    private func row(_ conflict: FileConflict) -> some View {
        HStack(spacing: 12) {
            KindIcon(kind: FileKind.from(fileName: conflict.fileName), size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(conflict.fileName)
                    .font(.subheadline.monospaced())
                    .lineLimit(1)
                    .truncationMode(.middle)
                let summary = FileConflictFormat.summary(conflict)
                if !summary.isEmpty {
                    Text(summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}
