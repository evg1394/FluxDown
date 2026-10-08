import FluxDomain
import FluxUI
import SwiftUI

/// 服务器目录选择器（`daemon.fs.list`，仅子目录）：远端主机的路径在服务器上，手机无法用系统选择器浏览。
/// 点目录进入，「上一级」返回，「选择此文件夹」确认。起始路径无效时回退到主机默认保存目录。
struct RemoteDirectoryPicker: View {
    let startPath: String
    let onPick: (String) -> Void

    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss

    @State private var listing: FsListResponse?
    @State private var isLoading = true
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            List {
                if let listing {
                    Section {
                        if let parent = listing.parent {
                            Button {
                                Task { await load(parent) }
                            } label: {
                                Label(L("mobileFolderUp"), systemImage: "arrow.turn.left.up")
                                    .frame(minHeight: 44, alignment: .leading)
                                    .contentShape(.rect)
                            }
                        }
                        ForEach(listing.dirs) { entry in
                            Button {
                                Task { await load(entry.path) }
                            } label: {
                                HStack {
                                    Label(entry.name, systemImage: FluxSymbol.folder)
                                    Spacer(minLength: 8)
                                    Image(systemName: "chevron.right")
                                        .font(.footnote.weight(.semibold))
                                        .foregroundStyle(.tertiary)
                                        .accessibilityHidden(true)
                                }
                                .frame(minHeight: 44)
                                .contentShape(.rect)
                            }
                            .buttonStyle(.plain)
                        }
                    } header: {
                        Text(listing.path).font(.fluxMono).textCase(nil).textSelection(.enabled)
                    }
                }
            }
            .disabled(isLoading && listing != nil)
            .overlay {
                if isLoading, listing == nil {
                    ProgressView()
                } else if let failure, listing == nil {
                    ContentUnavailableView(failure, systemImage: "exclamationmark.triangle")
                } else if let listing, listing.dirs.isEmpty {
                    if listing.denied {
                        ContentUnavailableView(L("mobileFolderDenied"), systemImage: "lock")
                    } else {
                        ContentUnavailableView(L("mobileFolderNoSubfolders"), systemImage: FluxSymbol.folder)
                    }
                }
            }
            .navigationTitle(L("selectSaveDir"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("mobilePickThisFolder")) {
                        if let listing { onPick(listing.path) }
                        dismiss()
                    }
                    .disabled(listing == nil)
                }
            }
            .task { await load(startPath.isEmpty ? nil : startPath) }
        }
        .presentationDetents([.medium, .large])
    }

    private func load(_ path: String?) async {
        isLoading = true
        defer { isLoading = false }
        do throws(HostError) {
            listing = try await list(path)
            failure = nil
        } catch {
            // 起始路径可能已不存在：回退到默认保存目录再试一次。
            if path != nil, listing == nil, let fallback = try? await list(nil) {
                listing = fallback
                failure = nil
            } else {
                let message = ErrorText.describe(error)
                failure = message
                if listing != nil { container.toasts.show(text: message, tone: .error) }
            }
        }
    }

    private func list(_ path: String?) async throws(HostError) -> FsListResponse {
        try await container.session.call(HostMethod.daemonFsList, params: FsListParams(path: path))
    }
}
