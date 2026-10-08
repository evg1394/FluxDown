import Foundation

/// 宽松字符串枚举：wire 上是字符串，主机升级后可能出现本端不认识的新值——必须解码成
/// `unknown(原值)` 而不是抛错（一个未知枚举值不应让整个列表 / 快照分区解码失败）。
///
/// 用法（每个枚举只写 `init(wire:)` 与 `wire`）：
/// ```swift
/// public enum PathStyle: WireStringEnum {
///     case windows, posix
///     case unknown(String)
///
///     public init(wire: String) {
///         switch wire {
///         case "windows": self = .windows
///         case "posix": self = .posix
///         default: self = .unknown(wire)
///         }
///     }
///
///     public var wire: String {
///         switch self {
///         case .windows: "windows"
///         case .posix: "posix"
///         case let .unknown(raw): raw
///         }
///     }
/// }
/// ```
/// `Codable` 由本协议的默认实现提供：解码永不因取值失败（仅类型不是字符串时才抛），编码写回原值，
/// 因此 `unknown` 往返无损。
///
/// serde 带标签联合（`{"type": "...", ...}` / `{"type": "...", "data": ...}`）的同类做法：
/// 先只解码判别字段，未知判别落入 `case unknown(type: String)`；已知判别再解码其载荷。
public protocol WireStringEnum: Codable, Hashable, Sendable {
    init(wire: String)
    var wire: String { get }
}

public extension WireStringEnum {
    init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        self.init(wire: try container.decode(String.self))
    }

    func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(wire)
    }
}
