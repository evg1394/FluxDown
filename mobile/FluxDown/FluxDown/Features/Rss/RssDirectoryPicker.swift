import FluxDomain
import FluxUI
import SwiftUI

/// 订阅保存目录选择：浏览主机上的目录（`daemon.fs.list`，仅子目录）。
/// 本机主机把浏览限制在 App 自己的 Documents（`rootPath`）内，远端主机不限。
struct RssDirectoryPicker: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss

    /// 起始目录；空 = 主机默认保存目录。
    let initialPath: String
    /// 非空：不允许越过该目录向上。
    let rootPath: String?
    let onSelect: (String) -> Void

    @State private var listing: RssDirListing?
    @State private var loading = true
    @State private var failure: String?
    /// 区分并发请求：只接受最新一次的结果。
    @State private var requestId = 0

    var body: some View {
        List {
            if let failure {
                Section {
                    Label(failure, systemImage: FluxSymbol.warning)
                        .font(.footnote)
                        .foregroundStyle(Color.fdStatusFailedText)
                    Button(L("mobileRetry")) { load(listing?.path ?? initialPath) }
                }
            }
            if let listing {
                Section {
                    Text(listing.path)
                        .font(.callout.monospaced())
                        .textSelection(.enabled)
                } header: {
                    Text(L("rssSaveDirLabel"))
                }
                Section {
                    if let parent = parentPath(of: listing) {
                        Button {
                            load(parent)
                        } label: {
                            Label(L("webFsParent"), systemImage: "arrow.up.left")
                        }
                    }
                    if listing.denied {
                        Label(L("webFsDenied"), systemImage: "lock.fill")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else if listing.dirs.isEmpty {
                        Label(L("webFsEmpty"), systemImage: FluxSymbol.folder)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(listing.dirs) { dir in
                        Button {
                            load(dir.path)
                        } label: {
                            HStack {
                                Label(dir.name, systemImage: FluxSymbol.folder)
                                Spacer(minLength: 8)
                                Image(systemName: "chevron.right")
                                    .font(.footnote.weight(.semibold))
                                    .foregroundStyle(.tertiary)
                            }
                            .contentShape(Rectangle())
                        }
                        .foregroundStyle(.primary)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .overlay {
            if loading, listing == nil {
                ProgressView().controlSize(.large)
            }
        }
        .navigationTitle(L("webFsPickTitle"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button(L("mobileRssUseFolder"), systemImage: FluxSymbol.done) {
                    guard let listing else { return }
                    onSelect(listing.path)
                    dismiss()
                }
                .disabled(listing == nil || listing?.denied == true)
            }
        }
        .task { load(initialPath) }
    }

    private func parentPath(of listing: RssDirListing) -> String? {
        guard let parent = listing.parent, !parent.isEmpty else { return nil }
        if let rootPath, listing.path == rootPath { return nil }
        return parent
    }

    private func load(_ path: String) {
        requestId += 1
        let id = requestId
        loading = true
        failure = nil
        let session = container.session
        let target = path.isEmpty ? rootPath : path
        Task {
            do throws(HostError) {
                let result: RssDirListing = try await session.call(
                    HostMethod.daemonFsList,
                    params: RssDirListParams(path: target.map { container.isLocalHost ? LocalPaths.rebased($0) : $0 })
                )
                guard id == requestId else { return }
                listing = result
            } catch {
                guard id == requestId else { return }
                failure = ErrorText.describe(error)
            }
            if id == requestId { loading = false }
        }
    }
}
