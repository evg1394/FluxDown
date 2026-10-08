import FluxDomain
import SwiftUI

extension View {
    /// 全局「会话被撤销」一次性提示（04-rss-devices §S2.7）：agent 的 `sessionRevoked` 通知（设备被移出信任 / 账号被封禁 /
    /// 会话过期 / 密码已改）在任意页面之上弹出 `.alert`，按钮「登录」跳到账户页、「好」关闭。
    /// 用户主动退出不会触发该通知。由壳层在根视图上挂一次（`Shell/RootView.swift`）。
    func sessionRevokedAlert() -> some View {
        modifier(SessionRevokedAlert())
    }
}

private struct SessionRevokedAlert: ViewModifier {
    @Environment(AppContainer.self) private var container

    /// 已处理的最大通知序号（`HostStore.notices` 的 `id` 单调递增）。
    @State private var handled = 0
    @State private var revoked: Revoked?
    @State private var warnCount = 0

    private nonisolated struct Revoked: Identifiable {
        let id: Int
        let reason: String
    }

    func body(content: Content) -> some View {
        content
            .onChange(of: container.store.notices.last?.id, initial: true) { process() }
            .alert(
                L("mobileSessionRevokedTitle"),
                isPresented: Binding(get: { revoked != nil }, set: { if !$0 { revoked = nil } }),
                presenting: revoked
            ) { _ in
                Button(L("accountLogin")) { container.router.showSettings(.account) }
                Button(L("confirm"), role: .cancel) {}
            } message: { item in
                Text(L(AccountRules.sessionRevokedKey(reason: item.reason)))
            }
            .sensoryFeedback(.warning, trigger: warnCount)
    }

    private func process() {
        let notices = container.store.notices
        guard let newest = notices.last?.id, newest > handled else { return }
        let fresh = notices.last { $0.id > handled && $0.name == HostNoticeName.sessionRevoked }
        handled = newest
        guard let fresh else { return }
        revoked = Revoked(id: fresh.id, reason: fresh.decode(String.self) ?? "")
        warnCount += 1
    }
}
