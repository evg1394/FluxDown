import UIKit

/// 本机在云端设备列表里的默认名（Rust `LocalHostConfig.deviceName`）。
///
/// 沙盒里读不到主机名，只能用 `UIDevice.name`：iOS 16 起未获
/// `com.apple.developer.device-information.user-assigned-device-name` 授权时返回通用名
/// （「iPhone」/「iPad」），获授权后才是用户在「设置 › 通用 › 关于本机」里取的名字。
/// 只在本机设备名缺失或仍是占位名 `FluxDown` 时生效，用户改过的名字不受影响。
@MainActor
enum LocalDevice {
    static var name: String {
        UIDevice.current.name
    }
}
