import SwiftUI
import UIKit

/// 启动揭幕「箭头开窗」（与 Android `FluxLaunchReveal` 同一编排）：页面底色 + 强调色下载箭头 →
/// 箭头吸气般微缩并下沉（「按下下载」）→ 箭头本身成为窗口，以箭头头部为锚点指数放大直到盖满屏幕，
/// 窗口内的强调色逐渐褪去露出界面，界面同时从 1.08 收回原尺寸。
/// 思路取自 Twitter 启动遮罩揭幕（开源复刻：PiXeL16/RevealingSplashView）：只用一个品牌形状作遮罩，不叠加其它元素。
///
/// **跟随主题**：终态底色 = `systemGroupedBackground`（按 App 外观解析），箭头 = 环境 `fluxAccent`。
/// 启动画面是静态资源（`LaunchBackground` 随**系统**明暗 + 品牌蓝 `LaunchMark`）：首帧按系统外观与品牌蓝绘制以无缝接续，
/// 吸气段再平滑过渡到 App 外观与强调色；默认外观（跟随系统 + 品牌蓝）下无过渡。须挂在注入外观 / 强调色的环境之内。
///
/// **不拖慢启动**：内容与幕布在同一首帧渲染，揭幕只决定何时看见；主线程稳定后即开始（至多等 0.6s），全程 0.65s；
/// 幕布不参与命中测试（点按直达下层）。结束后幕布移除、内容缩放回到 1。
/// `markSide` = `LaunchMark` 的点尺寸（logo 400 单位画框，居中于屏幕）。
/// 每个进程只播放一次（iPad 多窗口的后续场景直接呈现）；减弱动态效果时退化为 0.2s 淡出。
extension View {
    public func launchReveal(markSide: CGFloat) -> some View {
        modifier(LaunchRevealModifier(markSide: markSide))
    }
}

/// 进程内只播放一次。
private enum LaunchRevealGate {
    static var played = false
}

private struct LaunchRevealModifier: ViewModifier {
    let markSide: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.fluxAccent) private var accent
    @State private var launchCurtain = LaunchPortal.launchBackground()
    @State private var active = !LaunchRevealGate.played
    @State private var start: Date?
    @State private var contentScale = LaunchPortal.contentScaleFrom
    @State private var fade = 1.0

    func body(content: Content) -> some View {
        content
            .scaleEffect(active && !reduceMotion ? contentScale : 1)
            .overlay {
                if active {
                    curtainLayer
                        .opacity(fade)
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .task { await play() }
    }

    private var curtainLayer: some View {
        TimelineView(.animation(minimumInterval: nil, paused: reduceMotion)) { timeline in
            let elapsed = start.map { timeline.date.timeIntervalSince($0) } ?? 0
            let t = reduceMotion ? 0 : min(max(elapsed / LaunchPortal.total, 0), 1)
            let side = markSide
            let from = launchCurtain
            let ink = accent.color
            Canvas { @Sendable context, size in
                LaunchPortal.draw(&context, size: size, t: t, side: side, fromCurtain: from, ink: ink)
            }
        }
    }

    private func play() async {
        guard active else { return }
        LaunchRevealGate.played = true
        if reduceMotion {
            withAnimation(.easeOut(duration: 0.2)) { fade = 0 }
            try? await Task.sleep(for: .milliseconds(200))
            active = false
            return
        }
        await waitUntilSettled()
        start = .now
        let reveal = LaunchPortal.total - LaunchPortal.inhale
        try? await Task.sleep(for: .seconds(LaunchPortal.inhale))
        // ease-out cubic，与 Android 内容缩放一致。
        withAnimation(.timingCurve(0.33, 1, 0.68, 1, duration: reveal)) { contentScale = 1 }
        try? await Task.sleep(for: .seconds(reveal))
        active = false
    }

    /// 揭幕前等主线程连续 3 帧不卡顿：onAppear 早于首帧提交，冷启动初始化（首帧布局、本机引擎接入）占住主线程时，
    /// 按时间推进的动画会整段跳过，甚至在系统启动缩放过程中就已播完。等待期间幕布停在与启动画面一致的首帧，
    /// 内容此时本就无法响应；至多等 0.6s，不为动画拖慢启动。
    private func waitUntilSettled() async {
        let clock = ContinuousClock()
        let deadline = clock.now + .milliseconds(600)
        var last = clock.now
        var smooth = 0
        while smooth < 3, clock.now < deadline {
            try? await Task.sleep(for: .milliseconds(16))
            let now = clock.now
            smooth = now - last < .milliseconds(34) ? smooth + 1 : 0
            last = now
        }
    }
}

/// 揭幕时间轴与逐帧绘制（纯函数，供异步渲染线程调用）。
private nonisolated enum LaunchPortal {
    static let total = 0.65
    /// 前 0.2s 吸气下沉，其余开窗。
    static let inhale = 0.2
    static let inhaleScale: CGFloat = 0.88
    static let dip: CGFloat = 0.04
    static let contentScaleFrom: CGFloat = 1.08
    static let logoCenter: CGFloat = 256
    static let logoSide: CGFloat = 400
    /// 放大锚点 = 箭头头部三角形内心（logo 单位）；`inradius` 为其内切圆半径，用于算盖满屏幕所需倍率。
    static let anchor = CGPoint(x: 256, y: 318)
    static let inradius: CGFloat = 44

    /// 品牌箭头，坐标同 `assets/logo/fluxdown_logo.svg`（画框 56..456，中心 256）。
    static let arrow: Path = {
        var p = Path()
        p.move(to: CGPoint(x: 226, y: 131))
        p.addQuadCurve(to: CGPoint(x: 238, y: 119), control: CGPoint(x: 226, y: 119))
        p.addLine(to: CGPoint(x: 274, y: 119))
        p.addQuadCurve(to: CGPoint(x: 286, y: 131), control: CGPoint(x: 286, y: 119))
        p.addLine(to: CGPoint(x: 286, y: 296))
        p.addLine(to: CGPoint(x: 331, y: 251))
        p.addQuadCurve(to: CGPoint(x: 349, y: 251), control: CGPoint(x: 340, y: 242))
        p.addLine(to: CGPoint(x: 363, y: 265))
        p.addQuadCurve(to: CGPoint(x: 363, y: 283), control: CGPoint(x: 372, y: 274))
        p.addLine(to: CGPoint(x: 265, y: 381))
        p.addQuadCurve(to: CGPoint(x: 247, y: 381), control: CGPoint(x: 256, y: 390))
        p.addLine(to: CGPoint(x: 149, y: 283))
        p.addQuadCurve(to: CGPoint(x: 149, y: 265), control: CGPoint(x: 140, y: 274))
        p.addLine(to: CGPoint(x: 163, y: 251))
        p.addQuadCurve(to: CGPoint(x: 181, y: 251), control: CGPoint(x: 172, y: 242))
        p.addLine(to: CGPoint(x: 226, y: 296))
        p.closeSubpath()
        return p
    }()

    static func easeInOutCubic(_ x: Double) -> Double {
        x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2
    }

    /// 启动画面底色：`LaunchBackground`（= `systemGroupedBackground` 两值）按**系统**外观解析；
    /// 窗口被 `preferredColorScheme` 覆盖时环境里的动态色会按 App 外观解析，接缝处会闪色。
    @MainActor static func launchBackground() -> Color {
        let scene = UIApplication.shared.connectedScenes.lazy.compactMap { $0 as? UIWindowScene }.first
        let style = scene?.traitCollection.userInterfaceStyle ?? .unspecified
        return Color(uiColor: UIColor.systemGroupedBackground.resolvedColor(with: UITraitCollection(userInterfaceStyle: style)))
    }

    static func draw(_ context: inout GraphicsContext, size: CGSize, t: Double, side: CGFloat, fromCurtain: Color, ink: Color) {
        let unit = side / logoSide
        let breath = CGFloat(easeInOutCubic(min(t / (inhale / total), 1)))
        let u = min(max((t - inhale / total) / (1 - inhale / total), 0), 1)

        // 锚点静止时的屏幕位置 + 吸气下沉（logo 画框居中于屏幕，同启动画面）。
        let ax = size.width / 2 + (anchor.x - logoCenter) * unit
        let ay = size.height / 2 + (anchor.y - logoCenter) * unit + dip * side * breath
        // 内切圆盖住最远屏幕角所需倍率；对数空间插值 = 视觉上匀速放大，ease-in 使起步与吸气末速度衔接。
        let reach = max(hypot(ax, ay), hypot(size.width - ax, ay), hypot(ax, size.height - ay), hypot(size.width - ax, size.height - ay))
        let endScale = reach / (inradius * unit) * 1.08
        let startScale = 1 - (1 - inhaleScale) * breath
        let k = CGFloat(exp(log(Double(startScale)) + (log(Double(endScale)) - log(Double(startScale))) * u * u)) * unit

        let transform = CGAffineTransform(a: k, b: 0, c: 0, d: k, tx: ax - anchor.x * k, ty: ay - anchor.y * k)
        let portal = arrow.applying(transform)

        var curtain = Path(CGRect(origin: .zero, size: size))
        curtain.addPath(portal)
        let curtainColor = fromCurtain.mix(with: Color(uiColor: .systemGroupedBackground), by: Double(breath))
        context.fill(curtain, with: .color(curtainColor), style: FillStyle(eoFill: true))

        // 窗口内的强调色随开窗褪去（前 60%），露出界面：底色与界面相近，箭头靠这段强调色被看见。
        let inkAlpha = 1 - min(u / 0.6, 1)
        if inkAlpha > 0 {
            context.fill(portal, with: .color(Color.fdBrand.mix(with: ink, by: Double(breath)).opacity(inkAlpha)))
        }
    }
}
