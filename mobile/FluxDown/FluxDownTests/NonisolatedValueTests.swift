import FluxDomain
import FluxUI
import Foundation
import Testing
@testable import FluxDown

/// 回归：SwiftUI 的异步渲染线程（`com.apple.SwiftUI.AsyncRenderer`）会在**非主线程**比较
/// `.animation(_:value:)` / `.fluxAnimation(_:value:)` / `.sensoryFeedback(_:trigger:)` 的值。
/// App 与 FluxUI 默认 MainActor 隔离，纯数据类型的 `Equatable` / `Hashable` 一致性若被推断为 MainActor 隔离，
/// 渲染线程一比较就 `_dispatch_assert_queue_fail` 陷入断点（诊断页「主机诊断完成后」崩溃）。
///
/// `Task.detached { a == b }` 的闭包是 nonisolated 的：只有 `==` 本身是 nonisolated 才能编译、才不会运行时陷入。
/// 所以这里**编译通过 = 一致性仍是 nonisolated**；谁给这些类型加回 MainActor 隔离，这个文件先编译失败。
struct NonisolatedValueTests {
    @Test func hostReportEqualityRunsOffMain() async {
        let check = DiagnosticCheckDto(id: "disk", target: "/data", level: .warn, detail: "low", hint: "check_disk")
        let a = DiagnosticsModel.HostReport(
            report: DiagnosticsReportDto(appVersion: "1.0", checks: [check]), checks: [check], env: DaemonDiagnosticsDescribe()
        )
        let same = a
        let other = DiagnosticsModel.HostReport(report: a.report, checks: [], env: nil)
        #expect(await Task.detached { a == same }.value)
        #expect(await Task.detached { a != other }.value)
    }

    @Test func toastItemEqualityRunsOffMain() async {
        let id = UUID()
        let a = ToastItem(id: id, text: "A", tone: .success)
        let sameId = ToastItem(id: id, text: "B", tone: .error, action: {})
        let otherId = ToastItem(text: "A", tone: .success)
        #expect(await Task.detached { a == sameId }.value)
        #expect(await Task.detached { a != otherId }.value)
        // `.animation(value: center.current)` 比较的是 `ToastItem?`。
        let optional: ToastItem? = a
        #expect(await Task.detached { optional == sameId }.value)
        #expect(await Task.detached { optional != nil }.value)
    }

    @Test func themeModeEqualityRunsOffMain() async {
        let mode = ThemeMode.dark
        #expect(await Task.detached { mode == .dark }.value)
        #expect(await Task.detached { mode != ThemeMode.light }.value)
        #expect(await Task.detached { mode.id == "dark" }.value)
    }

    @Test func routesAndTokensRunOffMain() async {
        let folder = StatusFolder.paused
        let tone = SegmentTone.allCases.first
        let glyph = RingGlyph.allCases.first
        let accent = FluxAccent.presets.first
        let route = SettingsRoute.diagnostics
        let tab = AppTab.downloads
        #expect(await Task.detached { folder == .paused && folder.hashValue == StatusFolder.paused.hashValue }.value)
        #expect(await Task.detached { tone == SegmentTone.allCases.first }.value)
        #expect(await Task.detached { glyph == RingGlyph.allCases.first }.value)
        #expect(await Task.detached { accent == FluxAccent.presets.first }.value)
        #expect(await Task.detached { route == .diagnostics }.value)
        #expect(await Task.detached { tab == .downloads }.value)
    }
}
