import Foundation

/// 「文件已存在」可选动作（`FileExistsAction`）。取消下载不是动作：用 `SelectionOutcome.cancelled`。
public enum FileExistsAction: Sendable, Hashable {
    case rename
    case overwrite
    case skip
}

/// 保存目录里已有同名文件的询问内容（`SelectionKind::FileExists`），字段由主机算好。
public struct FileConflict: Sendable, Hashable {
    public var fileName: String
    public var saveDir: String
    /// 已有文件大小；主机读不到时为 nil。
    public var existingSize: UInt64?
    public var existingModifiedUnixMs: Int64?
    /// 即将下载的大小；未知为 nil。
    public var incomingSize: Int64?
    /// 选「重命名」时将使用的文件名。
    public var renamePreview: String
    /// 该协议可用的动作；不含 `skip` 时不显示「跳过」。
    public var actions: [FileExistsAction]

    public init(
        fileName: String,
        saveDir: String,
        existingSize: UInt64? = nil,
        existingModifiedUnixMs: Int64? = nil,
        incomingSize: Int64? = nil,
        renamePreview: String,
        actions: [FileExistsAction]
    ) {
        self.fileName = fileName
        self.saveDir = saveDir
        self.existingSize = existingSize
        self.existingModifiedUnixMs = existingModifiedUnixMs
        self.incomingSize = incomingSize
        self.renamePreview = renamePreview
        self.actions = actions
    }

    public func allows(_ action: FileExistsAction) -> Bool { actions.contains(action) }
}

extension SelectionRequest {
    /// 该请求为「文件已存在」时的询问内容。
    public var fileConflict: FileConflict? {
        if case let .fileExists(conflict) = kind { conflict } else { nil }
    }
}

extension Sequence<SelectionRequest> {
    /// 全部待答的「文件已存在」请求（保持主机给出的顺序）：客户端把它们聚合进同一个对话框。
    public var fileConflicts: [SelectionRequest] { filter { $0.fileConflict != nil } }
}

/// 聚合对话框的批量动作（全部重命名 / 覆盖 / 跳过）：只对 `actions` 允许该动作的请求生效，
/// 其余请求不动（调用方据此计数与禁用按钮）。
public enum FileConflictBulk {
    public static func targets(_ requests: [SelectionRequest], action: FileExistsAction) -> [SelectionRequest] {
        requests.filter { $0.fileConflict?.allows(action) == true }
    }
}
