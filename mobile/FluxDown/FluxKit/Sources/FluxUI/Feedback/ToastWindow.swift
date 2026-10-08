import Accessibility
import Observation
import SwiftUI
import UIKit

// Toast 的独立窗口宿主：把胶囊渲染在每个 `UIWindowScene` 上方的一块透传 `UIWindow` 里，
// 这样 Sheet / fullScreenCover / alert（都在 App 主窗口内展示）都盖不住它。入口只有 `View.toastHost`。

/// 宿主视图环境的快照：overlay 窗口在 App 视图树之外，必须显式带过去。
nonisolated struct ToastOverlayConfiguration: Equatable {
    var dismissLabel: String
    var accent: FluxAccent
    var colorScheme: ColorScheme
    var dynamicTypeSize: DynamicTypeSize
    var layoutDirection: LayoutDirection
    var locale: Locale
}

/// overlay 根视图读取的状态；管理器在宿主更新时改写。
@Observable
@MainActor
final class ToastOverlayModel {
    var center: ToastCenter
    var configuration: ToastOverlayConfiguration
    /// 胶囊位置上报（窗口坐标）。
    @ObservationIgnored var onFrameChange: (CGRect) -> Void = { _ in }

    init(center: ToastCenter, configuration: ToastOverlayConfiguration) {
        self.center = center
        self.configuration = configuration
    }
}

/// overlay 窗口的根视图：顶部居中的 Toast 胶囊 + 触感 + VoiceOver 播报。
private struct ToastOverlayRoot: View {
    let model: ToastOverlayModel

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let center = model.center
        let configuration = model.configuration
        GlassEffectContainer {
            if let item = center.current {
                ToastView(
                    item: item,
                    dismissLabel: configuration.dismissLabel,
                    onDismiss: center.dismiss,
                    onFrameChange: model.onFrameChange
                )
                .id(item.id)
                .padding(.top, 8)
                .padding(.horizontal, 16)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .animation(FluxMotion.bouncy.animation(reduceMotion: reduceMotion), value: center.current)
        .sensoryFeedback(trigger: center.current) { @Sendable _, new in new?.tone.sensoryFeedback }
        .onChange(of: center.current) { _, new in
            if let new { AccessibilityNotification.Announcement(new.text).post() }
        }
        .tint(configuration.accent.color)
        .environment(\.fluxAccent, configuration.accent)
        .environment(\.dynamicTypeSize, configuration.dynamicTypeSize)
        .environment(\.layoutDirection, configuration.layoutDirection)
        .environment(\.locale, configuration.locale)
    }
}

/// 透传窗口：只有 Toast 胶囊所在矩形接收触摸，其余一律交还给下层窗口。
final class ToastPassthroughWindow: UIWindow {
    /// 胶囊在本窗口坐标系中的矩形；`.zero` = 当前没有可交互内容。
    var interactiveFrame: CGRect = .zero

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        !interactiveFrame.isEmpty && interactiveFrame.insetBy(dx: -6, dy: -6).contains(point)
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard self.point(inside: point, with: event) else { return nil }
        return super.hitTest(point, with: event)
    }
}

/// 每个场景一块窗口；多个 `toastHost`（根 + Sheet 内页面）共用同一块，按 token 引用计数。
@MainActor
final class ToastWindowManager {
    static let shared = ToastWindowManager()

    private final class Entry {
        let window: ToastPassthroughWindow
        let model: ToastOverlayModel
        var tokens: Set<UUID> = []

        init(window: ToastPassthroughWindow, model: ToastOverlayModel) {
            self.window = window
            self.model = model
        }

        func tearDown() {
            window.isHidden = true
            window.rootViewController = nil
        }
    }

    private var entries: [ObjectIdentifier: Entry] = [:]

    private init() {}

    /// 注册 / 更新一个宿主。同一 `token` 重复调用即更新配置。
    func attach(token: UUID, scene: UIWindowScene, center: ToastCenter, configuration: ToastOverlayConfiguration) {
        let key = ObjectIdentifier(scene)
        let entry: Entry
        if let existing = entries[key] {
            entry = existing
            if existing.model.center !== center { existing.model.center = center }
            if existing.model.configuration != configuration { existing.model.configuration = configuration }
        } else {
            entry = makeEntry(scene: scene, center: center, configuration: configuration)
            entries[key] = entry
        }
        entry.tokens.insert(token)
        entry.window.overrideUserInterfaceStyle = configuration.colorScheme == .dark ? .dark : .light
    }

    func detach(token: UUID, scene: UIWindowScene) {
        let key = ObjectIdentifier(scene)
        guard let entry = entries[key] else { return }
        entry.tokens.remove(token)
        if entry.tokens.isEmpty {
            entry.tearDown()
            entries[key] = nil
        }
    }

    private func makeEntry(scene: UIWindowScene, center: ToastCenter, configuration: ToastOverlayConfiguration) -> Entry {
        let model = ToastOverlayModel(center: center, configuration: configuration)
        let window = ToastPassthroughWindow(windowScene: scene)
        window.windowLevel = .normal + 5
        window.backgroundColor = .clear
        let host = UIHostingController(rootView: ToastOverlayRoot(model: model))
        host.view.backgroundColor = .clear
        window.rootViewController = host
        // 常驻可见但全透传（`point(inside:)` 在没有 Toast 时恒为 false，不拦截任何触摸，也不成为 key window）；
        // 不隐藏窗口，保证 SwiftUI 的触感 / 播报 / 退出动画始终在活动视图层级里执行。
        window.isHidden = false
        model.onFrameChange = { [weak window] frame in window?.interactiveFrame = frame }
        return Entry(window: window, model: model)
    }
}

/// 零尺寸锚点：拿到宿主所在的 `UIWindowScene`，并把配置同步给 `ToastWindowManager`。
struct ToastWindowAnchor: UIViewRepresentable {
    let center: ToastCenter
    let configuration: ToastOverlayConfiguration

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> AnchorView {
        let view = AnchorView()
        view.isUserInteractionEnabled = false
        view.onSceneChange = { [coordinator = context.coordinator] scene in
            coordinator.setScene(scene)
        }
        return view
    }

    func updateUIView(_ uiView: AnchorView, context: Context) {
        context.coordinator.update(center: center, configuration: configuration)
    }

    static func dismantleUIView(_ uiView: AnchorView, coordinator: Coordinator) {
        uiView.onSceneChange = nil
        coordinator.setScene(nil)
    }

    final class AnchorView: UIView {
        var onSceneChange: ((UIWindowScene?) -> Void)?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            onSceneChange?(window?.windowScene)
        }
    }

    @MainActor
    final class Coordinator {
        private let token = UUID()
        private weak var scene: UIWindowScene?
        private var center: ToastCenter?
        private var configuration: ToastOverlayConfiguration?

        func update(center: ToastCenter, configuration: ToastOverlayConfiguration) {
            self.center = center
            self.configuration = configuration
            sync()
        }

        func setScene(_ newScene: UIWindowScene?) {
            guard newScene !== scene else { return }
            if let scene {
                ToastWindowManager.shared.detach(token: token, scene: scene)
            }
            scene = newScene
            sync()
        }

        private func sync() {
            guard let scene, let center, let configuration else { return }
            ToastWindowManager.shared.attach(token: token, scene: scene, center: center, configuration: configuration)
        }
    }
}
