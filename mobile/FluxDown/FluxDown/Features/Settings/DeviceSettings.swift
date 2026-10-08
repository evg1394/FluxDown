import Foundation
import Observation

/// 设备本地设置（`UserDefaults`；永远是这台手机，不随主机切换、不上云）。
///
/// 键名同 03-settings §14（`mobile.*`）。`mobile.bg_continue` 由 `BackgroundTransfers` 直接按键读取，
/// 因此这里与它共用同一个常量。
@MainActor
@Observable
final class DeviceSettings {
    static let shared = DeviceSettings()

    enum Key {
        /// 离开 App 时继续下载（默认开）。
        static let continueInBackground = BackgroundTransfers.continueInBackgroundKey
        /// 任务失败时通知（默认开）。
        static let notifyOnFailure = "mobile.notify_on_fail"
        /// 完成通知附「打开」「分享」操作按钮（默认开）。
        static let notifyActions = "mobile.notify_actions"
    }

    @ObservationIgnored private let defaults: UserDefaults

    var continueInBackground: Bool { didSet { defaults.set(continueInBackground, forKey: Key.continueInBackground) } }
    var notifyOnFailure: Bool { didSet { defaults.set(notifyOnFailure, forKey: Key.notifyOnFailure) } }
    var notifyActions: Bool { didSet { defaults.set(notifyActions, forKey: Key.notifyActions) } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        continueInBackground = defaults.object(forKey: Key.continueInBackground) as? Bool ?? true
        notifyOnFailure = defaults.object(forKey: Key.notifyOnFailure) as? Bool ?? true
        notifyActions = defaults.object(forKey: Key.notifyActions) as? Bool ?? true
    }
}
