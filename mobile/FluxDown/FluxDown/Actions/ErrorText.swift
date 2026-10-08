import FluxDomain

/// 错误文案：先按 `reason`（`ErrorReason` wire 名 → `errReason<Name>` 键）本地化，再按 code 回退
/// （README §5；GPUI `errors.rs` / Android `TaskActions.errorText` 同规则）。
enum ErrorText {
    static func describe(_ error: HostError) -> String {
        if let reason = error.reason, !reason.isEmpty {
            let key = "errReason" + reason.prefix(1).uppercased() + reason.dropFirst()
            if L10n.shared.has(key) { return L(key) }
        }
        switch error.code {
        case .conflict: return L("localServiceConflict")
        case .invalidArgument: return L("localServiceInvalidArgument")
        case .unavailable, .timeout: return L("localServiceDisconnected")
        case .protocolIncompatible: return L("mobileHostErrIncompatible")
        default: return L("localServiceActionFailed")
        }
    }
}
