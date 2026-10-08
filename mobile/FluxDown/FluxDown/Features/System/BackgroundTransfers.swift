import BackgroundTasks
import FluxDomain
import Foundation
import SwiftUI
import UIKit
import os

/// 后台下载（同 Android `DownloadService` + `DownloadServiceEffect`）：本机引擎有活跃 / 排队 / 待重试任务时，
/// 让进程在离开前台后继续下载；本机空闲后自行结束。与当前选中的主机无关（见 `LocalActivityMonitor`）。
///
/// iOS 没有前台服务；承载方式 = `BGContinuedProcessingTask`（iOS 26+，系统在锁屏 / 灵动岛以 Live Activity 显示进度，
/// 用户可随时停止）。已核实的 SDK 事实：
/// - 请求必须在**前台**创建 / 提交（“on behalf of the currently foregrounded app”，`BGTaskRequest.h`）：
///   因此在本机“由闲转忙”的前台时刻立即提交（任务在前台即开始运行，进入后台时系统才呈现进度 UI），
///   并在 `willResignActive` 兜底再提交一次；后台里由闲转忙无法再提交，只能走下面的宽限兜底。
/// - 标识符须以 Bundle ID 为前缀，Info.plist `BGTaskSchedulerPermittedIdentifiers` 放通配 `<bundle>.download.*`，
///   登记 / 提交使用补全后缀的完整标识（每次提交一个新后缀）；`BGContinuedProcessingTask` 的登记不要求在启动完成前。
/// - 必须用 `task.progress` 汇报进度（约 30 s 无进展、CPU 过高、过热会被系统终止）；必须设置 `expirationHandler`
///   并最终调用 `setTaskCompleted`。无需 UIBackgroundModes / Capabilities（仅后台 GPU 需要权限，本 App 不用）；模拟器不支持。
///
/// 失败兜底：提交 / 登记失败、或任务仍在排队（尚未运行）时，进入后台用 `beginBackgroundTask` 申请系统给的短暂宽限。
/// 终止语义：系统过期任务（用户点“停止”、资源紧张）→ 仅结束任务；引擎状态已持久化，回到前台时若仍在忙则重新提交。
/// 设备本地开关 `mobile.bg_continue`（03-settings §4.1，默认开）关闭时既不提交也不申请宽限。
///
/// 事件驱动：只响应活动量变化与应用生命周期通知；进度 / 文案汇报按 1 Hz 节流（含尾沿），
/// 空闲宽限（队列接力瞬间 active 归零）用单次延迟任务，没有周期轮询。
@MainActor
final class BackgroundTransfers {
    static let shared = BackgroundTransfers()

    /// 设备本地偏好键（Bool，缺省 = true）；设置页的「离开 App 时继续下载」开关写它。
    static let continueInBackgroundKey = "mobile.bg_continue"

    private static let reportInterval: Duration = .seconds(1)
    /// 空闲后保留任务的宽限（同 Android `IDLE_GRACE_MS`）：队列接力时 active 会瞬间归零，
    /// 而后台里无法重新提交，过早结束会让下一个任务失去后台时间。
    private static let idleGrace: Duration = .seconds(3)

    private nonisolated enum AppPhase {
        case active, inactive, background
    }

    private enum Continued {
        case idle
        /// 已登记、提交请求在途。
        case submitting(String)
        /// 已提交，等待系统启动（排队中）。
        case queued(String)
        case running(String, BGContinuedProcessingTask)

        var pendingID: String? {
            switch self {
            case let .submitting(id), let .queued(id): id
            case .idle, .running: nil
            }
        }

        var isRunning: Bool {
            if case .running = self { true } else { false }
        }
    }

    private let log = Logger(subsystem: "com.fluxdown.FluxDown", category: "background")

    private var monitor: LocalActivityMonitor?
    private var observers: [any NSObjectProtocol] = []
    private var phase = AppPhase.inactive
    private var activity = LocalActivity.idle

    private var continued = Continued.idle
    private var graceID = UIBackgroundTaskIdentifier.invalid
    /// 本轮后台时间已用尽（续跑任务被过期 / 宽限到期）：回到前台前不再申请宽限。
    private var backgroundTimeSpent = false

    private var idleTask: Task<Void, Never>?
    private var reportTask: Task<Void, Never>?
    private var lastReport: ContinuousClock.Instant?
    private var lastTitle = ""
    private var lastSubtitle = ""

    private init() {}

    /// 接入应用容器（幂等）。由根视图的 `.backgroundTransfers()` 调用。
    func attach(container: AppContainer) {
        guard monitor == nil else { return }
        phase = Self.currentPhase()
        observeLifecycle()
        let monitor = LocalActivityMonitor(container: container) { [weak self] activity in
            self?.activityChanged(activity)
        }
        self.monitor = monitor
        monitor.start()
    }

    private var isEnabled: Bool {
        UserDefaults.standard.object(forKey: Self.continueInBackgroundKey) as? Bool ?? true
    }

    // MARK: 生命周期

    private static func currentPhase() -> AppPhase {
        switch UIApplication.shared.applicationState {
        case .active: .active
        case .inactive: .inactive
        case .background: .background
        @unknown default: .inactive
        }
    }

    /// 用应用级通知而非 `scenePhase`：iPad 多窗口时，只有全部场景都进入后台才算应用进入后台。
    private func observeLifecycle() {
        let transitions: [(Notification.Name, AppPhase)] = [
            (UIApplication.didBecomeActiveNotification, .active),
            (UIApplication.willResignActiveNotification, .inactive),
            (UIApplication.willEnterForegroundNotification, .inactive),
            (UIApplication.didEnterBackgroundNotification, .background),
        ]
        for (name, next) in transitions {
            let token = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.lifecycleChanged(to: next)
                }
            }
            observers.append(token)
        }
    }

    private func lifecycleChanged(to next: AppPhase) {
        phase = next
        switch next {
        case .active:
            backgroundTimeSpent = false
            endGrace()
            submitIfNeeded()
        case .inactive:
            // `willResignActive` 时仍算前台：上一次提交失败 / 被过期后的兜底重试窗口。
            submitIfNeeded()
        case .background:
            beginGraceIfNeeded()
        }
    }

    // MARK: 活动量

    private func activityChanged(_ next: LocalActivity) {
        let wasBusy = activity.busy
        activity = next
        if next.busy {
            cancelIdleFinish()
            if !wasBusy {
                submitIfNeeded()
                beginGraceIfNeeded()
            }
            scheduleReport()
        } else if wasBusy {
            scheduleIdleFinish()
        }
    }

    private func scheduleIdleFinish() {
        idleTask?.cancel()
        idleTask = Task { [weak self] in
            do {
                try await Task.sleep(for: Self.idleGrace)
            } catch {
                return
            }
            self?.idleTask = nil
            self?.finishContinued()
        }
    }

    private func cancelIdleFinish() {
        idleTask?.cancel()
        idleTask = nil
    }

    // MARK: 续跑任务

    /// 在前台（含 `willResignActive`）、本机忙、尚无任务时登记并提交。只由“边沿”触发，
    /// 不随进度更新调用：提交被系统拒绝（模拟器、用户关闭后台刷新）时不会反复重试。
    private func submitIfNeeded() {
        guard activity.busy, phase != .background, isEnabled, case .idle = continued else { return }
        guard let bundleID = Bundle.main.bundleIdentifier else {
            log.error("bundle identifier unavailable; cannot build continued task identifier")
            return
        }
        let id = "\(bundleID).download.\(UUID().uuidString.lowercased().prefix(8))"
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: .main) { [weak self] task in
            self?.launched(task)
        }
        guard registered else {
            log.error("continued task identifier not permitted (Info.plist BGTaskSchedulerPermittedIdentifiers): \(id, privacy: .public)")
            beginGraceIfNeeded()
            return
        }
        continued = .submitting(id)
        let title = activity.title
        let subtitle = activity.subtitle
        Task { [weak self] in
            await self?.submit(id: id, title: title, subtitle: subtitle)
        }
    }

    private func submit(id: String, title: String, subtitle: String) async {
        let request = BGContinuedProcessingTaskRequest(identifier: id, title: title, subtitle: subtitle)
        request.strategy = .queue
        do {
            if #available(iOS 27, *) {
                try await Self.submitOffMain(request)
            } else {
                try BGTaskScheduler.shared.submit(request)
            }
        } catch {
            submissionFailed(id: id, error: error)
            return
        }
        submissionSucceeded(id: id)
    }

    /// iOS 27 的异步提交（文档：不要在主线程调用，完成回调可能延迟）。
    @available(iOS 27, *)
    @concurrent
    private static func submitOffMain(_ request: sending BGContinuedProcessingTaskRequest) async throws {
        try await BGTaskScheduler.shared.submitTaskRequest(request)
    }

    private func submissionSucceeded(id: String) {
        if case let .submitting(current) = continued, current == id {
            continued = .queued(id)
        } else if continued.pendingID != id, !isRunning(id) {
            // 提交在途期间已经空闲而取消：撤销系统里残留的请求。
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: id)
        }
    }

    private func submissionFailed(id: String, error: any Error) {
        log.notice("continued task submission failed: \(String(describing: error), privacy: .public)")
        if case let .submitting(current) = continued, current == id {
            continued = .idle
        }
        beginGraceIfNeeded()
    }

    private func isRunning(_ id: String) -> Bool {
        if case let .running(current, _) = continued { current == id } else { false }
    }

    /// 系统启动任务（主队列）：占用并开始汇报进度。
    private func launched(_ task: BGTask) {
        guard let task = task as? BGContinuedProcessingTask else {
            task.setTaskCompleted(success: false)
            return
        }
        let id = task.identifier
        guard continued.pendingID == id, activity.busy else {
            // 过期 / 已撤销的请求，或启动时本机已空闲。
            if continued.pendingID == id { continued = .idle }
            task.setTaskCompleted(success: true)
            return
        }
        task.expirationHandler = { @Sendable [weak self] in
            Task { @MainActor [weak self] in self?.expired(id: id) }
        }
        continued = .running(id, task)
        lastTitle = task.title
        lastSubtitle = task.subtitle
        endGrace()
        flushReport()
        log.info("continued processing task started: \(id, privacy: .public)")
    }

    /// 系统要求停止（用户在系统界面点“停止”、资源紧张、停滞）：只结束任务，不改动引擎状态。
    private func expired(id: String) {
        guard case let .running(current, task) = continued, current == id else { return }
        log.notice("continued processing task expired: \(id, privacy: .public)")
        reportTask?.cancel()
        reportTask = nil
        continued = .idle
        if phase == .background { backgroundTimeSpent = true }
        task.setTaskCompleted(success: false)
    }

    /// 本机空闲：结束运行中的任务 / 撤销排队中的请求，并释放宽限。
    private func finishContinued() {
        endGrace()
        reportTask?.cancel()
        reportTask = nil
        switch continued {
        case .idle:
            break
        case let .submitting(id), let .queued(id):
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: id)
        case let .running(_, task):
            task.setTaskCompleted(success: true)
        }
        continued = .idle
    }

    // MARK: 进度汇报（≤ 1 Hz，含尾沿）

    private func scheduleReport() {
        guard continued.isRunning else { return }
        let now = ContinuousClock.now
        if let last = lastReport, now - last < Self.reportInterval {
            guard reportTask == nil else { return }
            let wait = Self.reportInterval - (now - last)
            reportTask = Task { [weak self] in
                do {
                    try await Task.sleep(for: wait)
                } catch {
                    return
                }
                guard let self else { return }
                reportTask = nil
                flushReport()
            }
            return
        }
        flushReport()
    }

    private func flushReport() {
        guard case let .running(_, task) = continued, activity.busy else { return }
        lastReport = .now
        let progress = activity.progress
        // 先扩后缩的顺序保证 completed 始终 < total：系统不会在两次赋值之间看到“已完成”。
        if progress.total >= task.progress.totalUnitCount {
            task.progress.totalUnitCount = progress.total
            task.progress.completedUnitCount = progress.completed
        } else {
            task.progress.completedUnitCount = progress.completed
            task.progress.totalUnitCount = progress.total
        }
        let title = activity.title
        let subtitle = activity.subtitle
        if title != lastTitle || subtitle != lastSubtitle {
            task.updateTitle(title, subtitle: subtitle)
            lastTitle = title
            lastSubtitle = subtitle
        }
    }

    // MARK: 宽限兜底（beginBackgroundTask）

    /// 进入后台、本机仍在忙、而续跑任务不在运行（提交失败 / 仍在排队 / 开关关闭除外）时，申请系统的短暂后台时间。
    private func beginGraceIfNeeded() {
        guard phase == .background, activity.busy, isEnabled, !backgroundTimeSpent, graceID == .invalid,
              !continued.isRunning
        else { return }
        graceID = UIApplication.shared.beginBackgroundTask(withName: "com.fluxdown.local-downloads") { [weak self] in
            guard let self else { return }
            backgroundTimeSpent = true
            endGrace()
        }
        if graceID == .invalid {
            log.notice("background grace refused by the system")
        }
    }

    private func endGrace() {
        guard graceID != .invalid else { return }
        UIApplication.shared.endBackgroundTask(graceID)
        graceID = .invalid
    }
}

extension View {
    /// 挂到根视图（需处于 `.environment(container)` 之内）：把本机下载活动接入系统后台续跑，见 `BackgroundTransfers`。
    func backgroundTransfers() -> some View {
        modifier(BackgroundTransfersModifier())
    }
}

private struct BackgroundTransfersModifier: ViewModifier {
    @Environment(AppContainer.self) private var container

    func body(content: Content) -> some View {
        content.onAppear {
            BackgroundTransfers.shared.attach(container: container)
        }
    }
}
