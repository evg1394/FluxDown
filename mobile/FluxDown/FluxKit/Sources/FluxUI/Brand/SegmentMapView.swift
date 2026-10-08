import SwiftUI

/// 分段图谱 / 进度条的着色语气（§3.B）。
public nonisolated enum SegmentTone: Sendable, Hashable, CaseIterable {
    case downloading, paused, failed, queued, seeding, completed

    /// 填充色：下载中 = 强调色；已暂停 = `fdStatusPaused` 55%；失败 = `fdStatusFailed`；
    /// 排队 / 准备 = `fdStatusQueued`；做种 / 已完成 = `fdStatusSeeding`。
    public var color: Color {
        switch self {
        case .downloading: .fdStatusDownloading
        case .paused: Color.fdStatusPaused.opacity(0.55)
        case .failed: .fdStatusFailed
        case .queued: .fdStatusQueued
        case .seeding, .completed: .fdStatusSeeding
        }
    }
}

/// 一段真实字节区间（对应 `TaskSegmentDto`）。
public nonisolated struct SegmentSpan: Sendable, Hashable {
    /// 区间起点（含）。
    public var startByte: Int64
    /// 区间终点（不含）。
    public var endByte: Int64
    /// 该区间已下载字节数。
    public var downloadedBytes: Int64
    /// 是否正在传输；`nil` = 未知（不呼吸、不铺未下载尾部，也不推断为空闲）。
    public var active: Bool?

    public init(startByte: Int64, endByte: Int64, downloadedBytes: Int64, active: Bool? = nil) {
        self.startByte = startByte
        self.endByte = endByte
        self.downloadedBytes = downloadedBytes
        self.active = active
    }

    var length: Int64 { max(endByte - startByte, 0) }
}

/// 分段图谱（品牌签名 ①，§9.2）：按真实字节区间投影的任务条带。
///
/// - `spans` 非空且 `totalBytes > 0`：每段按 `startByte / totalBytes` 定位，宽度按真实长度（不是等分格）；
///   段间留 1pt 缝（段宽 ≤ 3pt 时取消缝，避免细段消失）；活跃且未完成的段整体呼吸（周期 2.4s，opacity .55↔1，
///   所有段同相），其未下载尾部以 38% 透明度铺色。
/// - 否则回退为由 `progress`（0…1）绘制的单条胶囊。
/// - `progress == nil` 且 `tone == .downloading` 且没有分段：不定进度（38% 宽条 1.2s 横扫）。
/// - 减弱动态效果：呼吸停止，活跃段 opacity = 1；不定进度保留（加载指示是信息）。
/// - 增强对比度：轨道 α 提高到 .3。
///
/// 默认对 VoiceOver 隐藏（进度已在任务行的 value 中）；传入 `accessibilityLabel` 时（详情页）作为独立元素朗读百分比。
public struct SegmentMapView: View {
    /// 任务行高度 5。
    public static let rowHeight: CGFloat = 5
    /// 紧凑行高度 3。
    public static let compactHeight: CGFloat = 3
    /// 详情英雄高度 12。
    public static let heroHeight: CGFloat = 12

    public let spans: [SegmentSpan]
    public let totalBytes: Int64
    public let progress: Double?
    public let tone: SegmentTone
    public let height: CGFloat
    public let accessibilityLabel: String?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var contrast

    public init(
        spans: [SegmentSpan] = [],
        totalBytes: Int64 = 0,
        progress: Double? = nil,
        tone: SegmentTone = .downloading,
        height: CGFloat = SegmentMapView.rowHeight,
        accessibilityLabel: String? = nil
    ) {
        self.spans = spans
        self.totalBytes = totalBytes
        self.progress = progress
        self.tone = tone
        self.height = height
        self.accessibilityLabel = accessibilityLabel
    }

    private var usesSegments: Bool { totalBytes > 0 && !spans.isEmpty }
    private var isIndeterminate: Bool { !usesSegments && progress == nil && tone == .downloading }
    private var hasActiveSpan: Bool { usesSegments && spans.contains { $0.active == true } }
    private var animates: Bool { isIndeterminate || (hasActiveSpan && !reduceMotion) }

    /// 0…1 的总完成度（用于 VoiceOver value）。
    private var fraction: Double {
        if usesSegments {
            let done = spans.reduce(Int64(0)) { $0 + min(max($1.downloadedBytes, 0), $1.length) }
            return min(max(Double(done) / Double(totalBytes), 0), 1)
        }
        return min(max(progress ?? 0, 0), 1)
    }

    public var body: some View {
        // Canvas 的渲染闭包由 SwiftUI 渲染线程（`com.apple.SwiftUI.AsyncRenderer`）在非主线程调用：
        // 默认 MainActor 隔离下，直接在 body 里写的闭包会被推断为 MainActor 隔离，渲染线程一进去就触发
        // `dispatch_assert_queue(main)` 陷入断点。所以这里先在主线程把绘制输入算成 Sendable 值，
        // 闭包标 `@Sendable`（nonisolated），只调用 nonisolated 的 `SegmentScene.draw`，不碰 `self`。
        let scene = SegmentScene(
            mode: usesSegments ? .segments : (isIndeterminate ? .indeterminate : .plain),
            spans: spans,
            totalBytes: totalBytes,
            fraction: fraction,
            reduceMotion: reduceMotion,
            track: contrast == .increased ? .fdProgressTrackIncreased : .fdProgressTrack,
            color: tone.color
        )
        return TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !animates)) { context in
            let time = context.date.timeIntervalSinceReferenceDate
            Canvas { @Sendable gc, size in
                scene.draw(into: &gc, size: size, time: time)
            }
        }
        .frame(height: height)
        .clipShape(.capsule)
        .modifier(SegmentAccessibility(label: accessibilityLabel, fraction: fraction, isIndeterminate: isIndeterminate))
    }
}

/// `SegmentMapView` 一帧的绘制输入（主线程算好的纯值）与绘制实现；`nonisolated` 以便在渲染线程调用。
private nonisolated struct SegmentScene: Sendable {
    enum Mode: Sendable { case segments, indeterminate, plain }

    let mode: Mode
    let spans: [SegmentSpan]
    let totalBytes: Int64
    /// 0…1 总完成度（仅 `.plain` 使用）。
    let fraction: Double
    let reduceMotion: Bool
    let track: Color
    let color: Color

    func draw(into gc: inout GraphicsContext, size: CGSize, time: TimeInterval) {
        gc.fill(Path(CGRect(origin: .zero, size: size)), with: .color(track))

        switch mode {
        case .segments: drawSegments(into: &gc, size: size, time: time)
        case .indeterminate: drawIndeterminate(into: &gc, size: size, time: time)
        case .plain: drawPlain(into: &gc, size: size)
        }
    }

    private func drawPlain(into gc: inout GraphicsContext, size: CGSize) {
        let width = size.width * fraction
        guard width > 0 else { return }
        let rect = CGRect(x: 0, y: 0, width: max(width, size.height), height: size.height)
        gc.fill(Path(roundedRect: rect, cornerRadius: size.height / 2), with: .color(color))
    }

    private func drawIndeterminate(into gc: inout GraphicsContext, size: CGSize, time: TimeInterval) {
        let barWidth = size.width * 0.38
        let phase = time.truncatingRemainder(dividingBy: 1.2) / 1.2
        // 平滑缓动横扫（循环加载指示，非交互动画）：-100% → 270% 条宽。
        let eased = phase < 0.5 ? 4 * phase * phase * phase : 1 - pow(-2 * phase + 2, 3) / 2
        let x = -barWidth + eased * barWidth * 3.7
        let rect = CGRect(x: x, y: 0, width: barWidth, height: size.height)
        gc.fill(Path(roundedRect: rect, cornerRadius: size.height / 2), with: .color(color))
    }

    private func drawSegments(into gc: inout GraphicsContext, size: CGSize, time: TimeInterval) {
        let total = CGFloat(totalBytes)
        let breath = reduceMotion ? 1 : 0.775 + 0.225 * sin(time * 2 * .pi / 2.4)
        for span in spans where span.length > 0 {
            let length = CGFloat(span.length)
            let rawWidth = length / total * size.width
            let gap: CGFloat = rawWidth > 3 ? 1 : 0
            let x = CGFloat(span.startByte) / total * size.width + gap / 2
            let width = rawWidth - gap
            guard width > 0 else { continue }

            let doneFraction = min(max(CGFloat(span.downloadedBytes) / length, 0), 1)
            let doneWidth = width * doneFraction
            let isActive = span.active == true
            let opacity: Double = isActive ? breath : 1

            if doneWidth > 0 {
                gc.fill(
                    Path(roundedRect: CGRect(x: x, y: 0, width: doneWidth, height: size.height), cornerRadius: 1),
                    with: .color(color.opacity(opacity))
                )
            }
            if isActive, doneFraction < 1 {
                gc.fill(
                    Path(CGRect(x: x + doneWidth, y: 0, width: width - doneWidth, height: size.height)),
                    with: .color(color.opacity(0.38 * opacity))
                )
            }
        }
    }
}

private struct SegmentAccessibility: ViewModifier {
    let label: String?
    let fraction: Double
    let isIndeterminate: Bool

    func body(content: Content) -> some View {
        if let label {
            content
                .accessibilityElement()
                .accessibilityLabel(label)
                .accessibilityValue(isIndeterminate ? "" : fraction.formatted(.percent.precision(.fractionLength(0))))
        } else {
            content.accessibilityHidden(true)
        }
    }
}

#Preview("SegmentMapView · 状态") {
    let spans: [SegmentSpan] = [
        SegmentSpan(startByte: 0, endByte: 250, downloadedBytes: 250, active: false),
        SegmentSpan(startByte: 250, endByte: 400, downloadedBytes: 90, active: true),
        SegmentSpan(startByte: 400, endByte: 700, downloadedBytes: 120, active: true),
        SegmentSpan(startByte: 700, endByte: 1000, downloadedBytes: 0, active: false),
    ]
    VStack(alignment: .leading, spacing: 16) {
        ForEach(SegmentTone.allCases, id: \.self) { tone in
            VStack(alignment: .leading, spacing: 6) {
                Text(String(describing: tone)).font(.caption).foregroundStyle(.secondary)
                SegmentMapView(spans: spans, totalBytes: 1000, tone: tone)
            }
        }
        Text("hero 12 · 回退单条 62%").font(.caption).foregroundStyle(.secondary)
        SegmentMapView(progress: 0.62, tone: .downloading, height: SegmentMapView.heroHeight)
        Text("compact 3 · 不定进度").font(.caption).foregroundStyle(.secondary)
        SegmentMapView(progress: nil, tone: .downloading, height: SegmentMapView.compactHeight)
    }
    .padding()
}

#Preview("SegmentMapView · 深色") {
    VStack(spacing: 14) {
        SegmentMapView(
            spans: [
                SegmentSpan(startByte: 0, endByte: 500, downloadedBytes: 500, active: false),
                SegmentSpan(startByte: 500, endByte: 1000, downloadedBytes: 200, active: true),
            ],
            totalBytes: 1000
        )
        SegmentMapView(progress: 0.3, tone: .failed)
        SegmentMapView(progress: 1, tone: .completed)
    }
    .padding()
    .preferredColorScheme(.dark)
}
