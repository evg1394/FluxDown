import Foundation

/// 协议 wire 的 JSON 编解码（serde camelCase，与 Swift 属性名一致，因此不做键转换）。
///
/// 约定（给 `FluxDomain/Protocol/<Area>.swift` 的 DTO 作者）：
/// - Rust `Option<T>` ↔ Swift `T?`：解码时缺键 / `null` 都得 `nil`；编码时 `nil` 省略键
///   （serde 对缺失的 `Option` 字段按 `None` 处理，对 `#[serde(default)]` 字段按默认值处理）。
/// - Rust `#[serde(default)]` 字段（旧主机可能不下发）在 Swift 里用可选或在
///   `init(from:)` 里 `decodeIfPresent(...) ?? 默认值`，不要写成必填。
/// - 时间一律保持 wire 的字符串 / 整数，不做 `Date` 策略转换。
/// - 任意 JSON 值（偏好值、权益…）用 ``JSONValue``。
/// - 取值集合会随主机版本增长的枚举必须用 ``WireStringEnum`` 的宽松模式：未知值落入
///   `unknown(String)`，绝不抛出解码错误。
/// - serde 的内部 / 相邻标签联合（`{"type":..,"data":..}`）：先解码判别字段，未知判别同样落入
///   `unknown`，见 ``WireStringEnum`` 的文档。
public enum ProtocolJSON {
    public static func makeDecoder() -> JSONDecoder {
        JSONDecoder()
    }

    public static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        // 稳定键序：便于测试与日志对比。
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return encoder
    }

    /// 解码协议 JSON；失败映射为 `HostError(.internal)`（`what` 用于说明来源，如方法名 / 分区键）。
    public static func decode<T: Decodable>(_: T.Type = T.self, from data: Data, what: String) throws(HostError) -> T {
        do {
            return try makeDecoder().decode(T.self, from: data)
        } catch {
            throw HostError(.internal, message: "undecodable \(what): \(describe(error))")
        }
    }

    /// 编码协议 JSON；失败映射为 `HostError(.internal)`。
    public static func encode(_ value: some Encodable, what: String) throws(HostError) -> Data {
        do {
            return try makeEncoder().encode(value)
        } catch {
            throw HostError(.internal, message: "encode \(what) failed: \(describe(error))")
        }
    }

    private static func describe(_ error: any Error) -> String {
        if let decoding = error as? DecodingError {
            switch decoding {
            case let .keyNotFound(key, context):
                return "missing key \(path(context.codingPath + [key]))"
            case let .typeMismatch(_, context), let .valueNotFound(_, context), let .dataCorrupted(context):
                return "\(context.debugDescription) at \(path(context.codingPath))"
            @unknown default:
                return String(describing: decoding)
            }
        }
        return String(describing: error)
    }

    private static func path(_ codingPath: [any CodingKey]) -> String {
        codingPath.isEmpty ? "<root>" : codingPath.map(\.stringValue).joined(separator: ".")
    }
}
