import SwiftUI

/// 速度波形（品牌签名 ②，§9.3）：Catmull-Rom 平滑曲线 + 渐变面积。
///
/// - `samples`：主序列（下载），按时间从旧到新；`secondary`：可选第二序列（上传，`fdStatusSeeding` 色，仅描线）。
/// - 两条序列共用同一纵向上限（取二者最大值），上下各留 2pt。
/// - 全部为 0（或没有数据）视为空闲：只画一条平静的底线，不画面积。
/// - 没有滚动插值：每个新采样整体刷新一次（也即「减弱动态效果」下的规定行为）。
/// - 纯装饰，对 VoiceOver 隐藏（数值在相邻文字里）。
public struct SpeedWaveform: View {
    public let samples: [Double]
    public let secondary: [Double]?
    public let color: Color
    public let showsGrid: Bool

    public init(
        samples: [Double],
        secondary: [Double]? = nil,
        color: Color = .accentColor,
        showsGrid: Bool = false
    ) {
        self.samples = samples
        self.secondary = secondary
        self.color = color
        self.showsGrid = showsGrid
    }

    public var body: some View {
        // Canvas 渲染闭包在 SwiftUI 渲染线程（`com.apple.SwiftUI.AsyncRenderer`）上执行：不能是 MainActor 隔离的。
        // 绘制输入先在主线程取成 Sendable 值，闭包标 `@Sendable`，只调用 nonisolated 的 `WaveformScene.draw`。
        let scene = WaveformScene(
            samples: samples, secondary: secondary, color: color, showsGrid: showsGrid,
            seedingColor: .fdStatusSeeding, separatorColor: Color(uiColor: .separator)
        )
        return Canvas { @Sendable gc, size in
            scene.draw(into: &gc, size: size)
        }
        .accessibilityHidden(true)
    }
}

/// `SpeedWaveform` 的绘制输入（主线程取好的纯值）与绘制实现；`nonisolated` 以便在渲染线程调用。
private nonisolated struct WaveformScene: Sendable {
    let samples: [Double]
    let secondary: [Double]?
    let color: Color
    let showsGrid: Bool
    let seedingColor: Color
    let separatorColor: Color

    private static let inset: CGFloat = 2

    func draw(into gc: inout GraphicsContext, size: CGSize) {
        let ceiling = max(samples.max() ?? 0, secondary?.max() ?? 0)
        if showsGrid { drawGrid(into: &gc, size: size) }
        guard ceiling > 0 else {
            drawBaseline(into: &gc, size: size)
            return
        }
        if let secondary, secondary.count > 1 {
            let line = Self.smoothPath(Self.points(secondary, ceiling: ceiling, size: size), bottom: size.height - Self.inset)
            gc.stroke(line, with: .color(seedingColor), style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
        }
        if samples.count > 1 {
            let line = Self.smoothPath(Self.points(samples, ceiling: ceiling, size: size), bottom: size.height - Self.inset)
            var area = line
            area.addLine(to: CGPoint(x: size.width, y: size.height))
            area.addLine(to: CGPoint(x: 0, y: size.height))
            area.closeSubpath()
            gc.fill(
                area,
                with: .linearGradient(
                    Gradient(colors: [color.opacity(0.45), color.opacity(0)]),
                    startPoint: .zero,
                    endPoint: CGPoint(x: 0, y: size.height)
                )
            )
            gc.stroke(line, with: .color(color), style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
        } else {
            drawBaseline(into: &gc, size: size)
        }
    }

    private static func points(_ values: [Double], ceiling: Double, size: CGSize) -> [CGPoint] {
        let usable = max(size.height - 2 * inset, 0)
        let lastIndex = CGFloat(max(values.count - 1, 1))
        return values.enumerated().map { index, value in
            CGPoint(
                x: size.width * CGFloat(index) / lastIndex,
                y: size.height - inset - CGFloat(min(max(value, 0) / ceiling, 1)) * usable
            )
        }
    }

    /// Catmull-Rom → 三次贝塞尔：`c1 = p1 + (p2 − p0)/6`，`c2 = p2 − (p3 − p1)/6`；
    /// 控制点纵向夹在 [顶, 底] 内，避免曲线冲出底线。
    private static func smoothPath(_ pts: [CGPoint], bottom: CGFloat) -> Path {
        var path = Path()
        guard let first = pts.first else { return path }
        path.move(to: first)
        let top = pts.map(\.y).min() ?? 0
        func clamp(_ y: CGFloat) -> CGFloat { min(max(y, top), bottom) }
        for i in 0..<(pts.count - 1) {
            let p0 = pts[max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[min(pts.count - 1, i + 2)]
            path.addCurve(
                to: p2,
                control1: CGPoint(x: p1.x + (p2.x - p0.x) / 6, y: clamp(p1.y + (p2.y - p0.y) / 6)),
                control2: CGPoint(x: p2.x - (p3.x - p1.x) / 6, y: clamp(p2.y - (p3.y - p1.y) / 6))
            )
        }
        return path
    }

    private func drawBaseline(into gc: inout GraphicsContext, size: CGSize) {
        var line = Path()
        let y = size.height - Self.inset
        line.move(to: CGPoint(x: 0, y: y))
        line.addLine(to: CGPoint(x: size.width, y: y))
        gc.stroke(line, with: .color(color.opacity(0.35)), style: StrokeStyle(lineWidth: 1.8, lineCap: .round))
    }

    private func drawGrid(into gc: inout GraphicsContext, size: CGSize) {
        let usable = max(size.height - 2 * Self.inset, 0)
        for fraction in [0.0, 0.5, 1.0] {
            var line = Path()
            let y = size.height - Self.inset - usable * fraction
            line.move(to: CGPoint(x: 0, y: y))
            line.addLine(to: CGPoint(x: size.width, y: y))
            gc.stroke(line, with: .color(separatorColor), style: StrokeStyle(lineWidth: 0.5, dash: [3, 4]))
        }
    }
}

#Preview("SpeedWaveform") {
    let down: [Double] = [0, 2, 5, 9, 14, 12, 18, 22, 19, 25, 21, 16, 20, 27, 30, 24, 18, 22, 26, 28]
    let up: [Double] = [0, 1, 1, 2, 3, 2, 4, 5, 4, 6, 5, 3, 4, 6, 7, 5, 3, 4, 5, 6]
    VStack(alignment: .leading, spacing: 18) {
        Text("活动条 58×24").font(.caption).foregroundStyle(.secondary)
        SpeedWaveform(samples: down).frame(width: 58, height: 24)
        Text("下载 + 上传 + 网格").font(.caption).foregroundStyle(.secondary)
        SpeedWaveform(samples: down, secondary: up, showsGrid: true).frame(height: 80)
        Text("空闲底线").font(.caption).foregroundStyle(.secondary)
        SpeedWaveform(samples: Array(repeating: 0, count: 30)).frame(height: 40)
    }
    .padding()
}

#Preview("SpeedWaveform · 深色") {
    SpeedWaveform(samples: [1, 4, 3, 8, 6, 10, 7, 12], secondary: [0, 1, 1, 2, 2, 3, 2, 3], showsGrid: true)
        .frame(height: 80)
        .padding()
        .preferredColorScheme(.dark)
}
