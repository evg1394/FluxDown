import FluxDomain
import FluxUI
import Foundation
import UniformTypeIdentifiers

/// N3 · `.torrent` 导入（`02-downloads.md` §13）。
///
/// 每个文件：security-scoped 读取（主线程外，上限 `maxBytes + 1`）→ 粗校验 → `daemon.task.create{request.torrentB64}`。
/// 本机与远端主机同一路径；BT 文件选择（X1）由引擎的选择请求自行弹出。单个失败不影响后续文件。
@MainActor
enum TorrentImport {
    nonisolated struct Failure: Hashable {
        let fileName: String
        let message: String
    }

    nonisolated struct Outcome: Hashable {
        var created = 0
        var failures: [Failure] = []
    }

    /// 文件选择器 / 系统「打开方式」使用的种子类型；系统未登记时按扩展名推断，最后退回 `.data`（由校验兜底）。
    static let contentType: UTType =
        UTType("org.bittorrent.torrent") ?? UTType(filenameExtension: "torrent", conformingTo: .data) ?? .data

    /// 逐个提交；只读主机直接拒绝。返回成功数与逐文件失败（失败已各自弹 Toast）。
    @discardableResult
    static func submit(
        _ urls: [URL],
        container: AppContainer,
        saveDir: String,
        queueId: String,
        startPaused: Bool
    ) async -> Outcome {
        var outcome = Outcome()
        guard !urls.isEmpty else { return outcome }
        let toasts = container.toasts
        if container.store.state.isReadOnly {
            FluxHaptic.error.play()
            toasts.show(text: L("localServiceDisconnected"), tone: .error, systemImage: FluxSymbol.offline)
            return outcome
        }
        let session = container.session
        let dir = saveDir.trimmingCharacters(in: .whitespacesAndNewlines)

        for url in urls {
            let name = url.lastPathComponent
            let data: Data
            let read = await Task.detached(priority: .userInitiated) { readTorrentData(url) }.value
            switch read {
            case let .data(bytes):
                data = bytes
            case .unreadable:
                reject(&outcome, name, L("torrentImportReadFailed", ["name": name]), tone: .error, toasts: toasts)
                continue
            }
            if let issue = TorrentFile.validate(data) {
                let message =
                    switch issue {
                    case .empty: L("torrentImportEmpty", ["name": name])
                    case .tooLarge: L("torrentImportTooLarge", ["name": name])
                    case .notTorrent: L("torrentImportNotTorrent", ["name": name])
                    }
                reject(&outcome, name, message, tone: .warning, toasts: toasts)
                continue
            }
            do throws(HostError) {
                let _: CreatedTask = try await session.call(
                    HostMethod.daemonTaskCreate,
                    params: TorrentFile.createParams(data: data, saveDir: dir, queueId: queueId, startPaused: startPaused)
                )
                outcome.created += 1
            } catch {
                reject(&outcome, name, L("torrentImportFailed", ["name": name, "error": ErrorText.describe(error)]), tone: .error, toasts: toasts)
            }
        }

        if outcome.created > 0 {
            FluxHaptic.success.play()
            let text = outcome.created == 1 ? L("torrentFileSelected") : L("torrentFileCount", ["count": outcome.created])
            toasts.show(text: text, tone: .success, systemImage: "document.badge.plus")
        } else if outcome.failures.isEmpty == false {
            FluxHaptic.error.play()
        }
        return outcome
    }

    /// 「文件」App「用 FluxDown 打开」的单个 `.torrent`：默认目录与默认队列（空串由主机取默认）立即提交。
    /// 供 `RootView.onOpenURL` 调用；非 `.torrent` 文件 URL 给出不支持提示。
    static func openFromSystem(_ url: URL, container: AppContainer) {
        guard canOpen(url) else {
            FluxHaptic.warning.play()
            container.toasts.show(text: L("unsupportedDropHint"), tone: .warning)
            return
        }
        Task {
            await submit([url], container: container, saveDir: "", queueId: "", startPaused: false)
        }
    }

    /// 是否为可由本类型直接导入的 `.torrent` 文件 URL。
    static func canOpen(_ url: URL) -> Bool {
        url.isFileURL && TorrentFile.isTorrentFileName(url.lastPathComponent)
    }

    private static func reject(
        _ outcome: inout Outcome,
        _ fileName: String,
        _ message: String,
        tone: ToastTone,
        toasts: ToastCenter
    ) {
        outcome.failures.append(Failure(fileName: fileName, message: message))
        toasts.show(text: message, tone: tone)
    }
}

private nonisolated enum TorrentReadResult: Sendable {
    case data(Data)
    case unreadable
}

/// 读取种子字节（需要 security-scoped 访问）；至多读 `maxBytes + 1`，足以判定「过大」而不把大文件读进内存。
private nonisolated func readTorrentData(_ url: URL) -> TorrentReadResult {
    let scoped = url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    guard let handle = try? FileHandle(forReadingFrom: url) else { return .unreadable }
    defer { try? handle.close() }
    guard let data = try? handle.read(upToCount: TorrentFile.maxBytes + 1) else { return .unreadable }
    return .data(data)
}

// MARK: - 拖放（iPad / Stage Manager）

/// 拖入 N1 的文件：`NSItemProvider` 的文件表示只在回调内有效，先按扩展名归类，再把（受限大小的）内容暂存到临时目录，
/// 交给与文件选择器相同的导入路径，用完 `discard`。
nonisolated enum DroppedFiles {
    nonisolated enum Kind: Sendable, Hashable {
        case torrent
        /// `.txt` / `.url` / `.list`：宽松解析链接。
        case text
    }

    nonisolated struct File: Sendable, Hashable {
        let url: URL
        let kind: Kind
    }

    /// 暂存内容上限：种子上限（TXT 读取自身还有更小的上限）。
    private static let stageLimit = TorrentFile.maxBytes + 1

    static func kind(ofFileNamed name: String) -> Kind? {
        switch (name as NSString).pathExtension.lowercased() {
        case "torrent": .torrent
        case "txt", "url", "list": .text
        default: nil
        }
    }

    /// 加载全部提供者；不支持的类型计入 `rejected`，加载 / 暂存 I/O 失败计入 `failed`。
    @MainActor
    static func load(_ providers: [NSItemProvider]) async -> (files: [File], rejected: Int, failed: Int) {
        var files: [File] = []
        var rejected = 0
        var failed = 0
        for provider in providers {
            let staged: Staged = await withCheckedContinuation { continuation in
                _ = provider.loadFileRepresentation(forTypeIdentifier: UTType.data.identifier) { url, _ in
                    continuation.resume(returning: url.map(stage) ?? .failed)
                }
            }
            switch staged {
            case let .file(file): files.append(file)
            case .unsupported: rejected += 1
            case .failed: failed += 1
            }
        }
        return (files, rejected, failed)
    }

    /// 删除暂存的临时副本（每个文件独占一个目录）。
    static func discard(_ files: [File]) {
        for file in files {
            try? FileManager.default.removeItem(at: file.url.deletingLastPathComponent())
        }
    }

    private enum Staged {
        case file(File)
        case unsupported
        case failed
    }

    private static func stage(_ source: URL) -> Staged {
        guard let kind = kind(ofFileNamed: source.lastPathComponent) else { return .unsupported }
        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("fluxdown-drop-" + UUID().uuidString, isDirectory: true)
        let destination = dir.appendingPathComponent(source.lastPathComponent)
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            let handle = try FileHandle(forReadingFrom: source)
            defer { try? handle.close() }
            let data = try handle.read(upToCount: stageLimit) ?? Data()
            try data.write(to: destination)
            return .file(File(url: destination, kind: kind))
        } catch {
            try? fm.removeItem(at: dir)
            return .failed
        }
    }
}
