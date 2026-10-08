import Foundation

// 跨设备任务（`agent.remote.*`，经 FluxCloud）。镜像 `native/protocol/src/agent.rs::RemoteTaskDto` 等；
// 控制矩阵镜像 Web `batchPlan.ts::remoteCan` ↔ GPUI `dispatch.rs::remote_action_applies`。

/// `RemoteTaskStatus`：云端新增、本端不认识的状态落入 `unknown`（不参与控制）。
public enum RemoteTaskStatus: WireStringEnum {
    case accepted, downloading, paused, completed, failed, canceled, pending
    case unknown(String)

    public init(wire: String) {
        switch wire {
        case "accepted": self = .accepted
        case "downloading": self = .downloading
        case "paused": self = .paused
        case "completed": self = .completed
        case "failed": self = .failed
        case "canceled": self = .canceled
        case "pending": self = .pending
        default: self = .unknown(wire)
        }
    }

    public var wire: String {
        switch self {
        case .accepted: "accepted"
        case .downloading: "downloading"
        case .paused: "paused"
        case .completed: "completed"
        case .failed: "failed"
        case .canceled: "canceled"
        case .pending: "pending"
        case let .unknown(raw): raw
        }
    }

    public var isUnknown: Bool {
        if case .unknown = self { true } else { false }
    }

    /// 任务已结束（不再有速度 / 进度变化）。
    public var isTerminal: Bool {
        switch self {
        case .completed, .failed, .canceled: true
        default: false
        }
    }

    /// 暂停 / 继续这类需要目标设备在线才能送达的命令（取消 / 删除在云端直接生效）。
    public func needsTargetOnline(for action: RemoteCommandAction) -> Bool {
        action == .pause || action == .resume
    }

    /// 云端命令是否适用于该状态：未知一律不可控制（含删除）；暂停 / 取消只对进行中，继续只对已暂停（取消也适用），
    /// 删除对任何已知状态成立。
    public func allows(_ action: RemoteCommandAction) -> Bool {
        switch self {
        case .unknown:
            false
        case .pending, .accepted, .downloading:
            action == .pause || action == .cancel || action == .delete
        case .paused:
            action == .resume || action == .cancel || action == .delete
        case .completed, .failed, .canceled:
            action == .delete
        }
    }
}

/// `agent.remote.command` 的动作。
public enum RemoteCommandAction: String, Sendable, Hashable, Codable, CaseIterable {
    case pause, resume
    /// 云端直接置 `canceled`（不依赖目标在线），目标设备删除其本地任务、保留已下载文件。
    case cancel
    /// 云端直接删除记录；目标在线时按 `deleteFiles` 决定是否同时删文件。
    case delete
}

/// 跨设备任务的 UI 投影（`RemoteTaskDto`）。
public struct RemoteTaskDto: Sendable, Hashable, Identifiable, Codable {
    public var id: String
    public var fromDevice: String
    /// 目标设备的 `CloudDeviceRecord.deviceId`。
    public var toDevice: String
    public var url: String
    public var saveDir: String?
    /// 云端对未指定文件名的任务回 `null`，按空串处理。
    public var fileName: String
    public var status: RemoteTaskStatus
    public var totalBytes: Int64?
    public var downloadedBytes: Int64
    public var speed: Int64
    /// 0…1。
    public var progress: Double
    public var error: String?
    public var createdAt: String
    public var updatedAt: String

    public init(
        id: String, fromDevice: String = "", toDevice: String = "", url: String = "", saveDir: String? = nil,
        fileName: String = "", status: RemoteTaskStatus = .pending, totalBytes: Int64? = nil, downloadedBytes: Int64 = 0,
        speed: Int64 = 0, progress: Double = 0, error: String? = nil, createdAt: String = "", updatedAt: String = ""
    ) {
        self.id = id
        self.fromDevice = fromDevice
        self.toDevice = toDevice
        self.url = url
        self.saveDir = saveDir
        self.fileName = fileName
        self.status = status
        self.totalBytes = totalBytes
        self.downloadedBytes = downloadedBytes
        self.speed = speed
        self.progress = progress
        self.error = error
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        fromDevice = try c.decodeIfPresent(String.self, forKey: .fromDevice) ?? ""
        toDevice = try c.decodeIfPresent(String.self, forKey: .toDevice) ?? ""
        url = try c.decodeIfPresent(String.self, forKey: .url) ?? ""
        saveDir = try c.decodeIfPresent(String.self, forKey: .saveDir)
        fileName = try c.decodeIfPresent(String.self, forKey: .fileName) ?? ""
        status = try c.decodeIfPresent(RemoteTaskStatus.self, forKey: .status) ?? .pending
        totalBytes = try c.decodeIfPresent(Int64.self, forKey: .totalBytes)
        downloadedBytes = try c.decodeIfPresent(Int64.self, forKey: .downloadedBytes) ?? 0
        speed = try c.decodeIfPresent(Int64.self, forKey: .speed) ?? 0
        progress = try c.decodeIfPresent(Double.self, forKey: .progress) ?? 0
        error = try c.decodeIfPresent(String.self, forKey: .error)
        createdAt = try c.decodeIfPresent(String.self, forKey: .createdAt) ?? ""
        updatedAt = try c.decodeIfPresent(String.self, forKey: .updatedAt) ?? ""
    }
}

/// `agent.remote.dispatch` 参数：把下载经 FluxCloud 下发到本账号另一台受信任设备。
public struct RemoteDispatchParams: Sendable, Hashable, Codable {
    /// 目标设备的 `CloudDeviceRecord.deviceId`。
    public var toDevice: String
    public var url: String
    /// 省略 = 由目标设备按 URL 推断。
    public var fileName: String?
    /// 目标设备上的保存目录；省略 = 目标设备默认下载目录。必须符合目标设备路径风格。
    public var saveDir: String?

    public init(toDevice: String, url: String, fileName: String? = nil, saveDir: String? = nil) {
        self.toDevice = toDevice
        self.url = url
        self.fileName = fileName
        self.saveDir = saveDir
    }
}

public struct RemoteDispatchResult: Sendable, Hashable, Codable {
    public var task: RemoteTaskDto

    public init(task: RemoteTaskDto) { self.task = task }
}

/// `agent.remote.command` 参数。
public struct RemoteCommandParams: Sendable, Hashable, Codable {
    public var taskId: String
    public var action: RemoteCommandAction
    /// 幂等键；省略时 agent 生成唯一值（同一动作可重复下发）。
    public var commandId: String?
    /// 仅 `delete`：目标设备同时删除已下载文件。
    public var deleteFiles: Bool

    public init(taskId: String, action: RemoteCommandAction, commandId: String? = nil, deleteFiles: Bool = false) {
        self.taskId = taskId
        self.action = action
        self.commandId = commandId
        self.deleteFiles = deleteFiles
    }
}

// MARK: - 纯规则

/// 远程任务列表规则。
public enum RemoteTaskRules {
    /// 目标为本机的远程任务在本地已有真实任务，不再作为镜像行显示。
    public static func visible(_ tasks: [RemoteTaskDto], currentDeviceId: String?) -> [RemoteTaskDto] {
        guard let currentDeviceId else { return tasks }
        return tasks.filter { $0.toDevice != currentDeviceId }
    }

    /// 按目标设备计数。
    public static func countByTarget(_ tasks: [RemoteTaskDto]) -> [String: Int] {
        tasks.reduce(into: [:]) { counts, task in counts[task.toDevice, default: 0] += 1 }
    }

    /// 命令是否应当显示为可点：状态矩阵 + （暂停 / 继续）目标在线。`targetOnline == nil` = 在线状态未知，不据此禁用。
    public static func canIssue(_ action: RemoteCommandAction, to task: RemoteTaskDto, targetOnline: Bool?) -> Bool {
        guard task.status.allows(action) else { return false }
        if task.status.needsTargetOnline(for: action), targetOnline == false { return false }
        return true
    }

    /// 行尾单一主按钮：进行中 → 暂停，已暂停 → 继续，其余无。
    public static func primaryAction(for task: RemoteTaskDto) -> RemoteCommandAction? {
        if task.status.allows(.pause) { return .pause }
        if task.status.allows(.resume) { return .resume }
        return nil
    }

    /// 状态筛选（V1 §E 顶部分段）。
    public enum Filter: Sendable, Hashable, CaseIterable {
        case all, downloading, paused, completed, failedOrCanceled

        public func matches(_ status: RemoteTaskStatus) -> Bool {
            switch self {
            case .all: true
            case .downloading:
                switch status {
                case .pending, .accepted, .downloading: true
                default: false
                }
            case .paused: status == .paused
            case .completed: status == .completed
            case .failedOrCanceled: status == .failed || status == .canceled
            }
        }
    }

    /// 排序：未结束优先，同组按 `updatedAt`（ISO 字符串）降序。
    public static func sorted(_ tasks: [RemoteTaskDto]) -> [RemoteTaskDto] {
        tasks.enumerated().sorted { lhs, rhs in
            let a = lhs.element, b = rhs.element
            if a.status.isTerminal != b.status.isTerminal { return !a.status.isTerminal }
            if a.updatedAt != b.updatedAt { return a.updatedAt > b.updatedAt }
            return lhs.offset < rhs.offset
        }.map(\.element)
    }
}
