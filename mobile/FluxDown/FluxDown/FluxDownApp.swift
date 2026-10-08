import FluxDomain
import FluxUI
import SwiftUI

@main
struct FluxDownApp: App {
    @State private var container = AppContainer()

    init() {
        // 冷启动即设置通知中心代理：点按通知启动 App 时的回调不会丢。
        _ = NotificationService.shared
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .backgroundTransfers()
                .settingsEffects()
                .environment(container)
                .environment(container.store)
                .environment(container.router)
                .environment(container.appearance)
                .environment(container.viewPrefs)
                .environment(container.toasts)
                .tint(container.appearance.accent.color)
                .environment(\.fluxAccent, container.appearance.accent)
                .preferredColorScheme(container.appearance.mode.colorScheme)
        }
    }
}
