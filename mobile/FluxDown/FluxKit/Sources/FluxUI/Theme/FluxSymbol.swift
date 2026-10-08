import SwiftUI
import UIKit

/// SF Symbols 语义注册表：**同一概念全 App 只用一个符号**（标签栏、工具栏、菜单、滑动操作、Toast、设置行同名同形）。
///
/// 选型规则（Apple HIG · SF Symbols）：
/// - 只用当前名称（SF Symbols 6+ 已改名的 `doc.on.doc` → `document.on.document` 等不再使用旧别名），且最低可用版本 ≤ 部署目标 iOS 26.1。
/// - 不用受限符号：`icloud*`（仅指 Apple iCloud；FluxCloud 用 `cloud*`）、`message`、`safari`、`video`（FaceTime）、
///   `person.badge.key`（仅通行密钥）、`macbook.and.iphone` 等 Apple 产品图形。`iphone` / `ipad` 只用来指本机（`thisDevice`）。
/// - 填充变体只给「状态 / 主动作」：播放 / 暂停 / 加速与语气角标；菜单、工具栏、行图标用描边形，由系统按场景渲染（标签栏自动填充）。
/// - 单次出现、与上下文强绑定的符号可在调用处直接写字面量；**出现在两个及以上界面的概念必须经这里**。
public nonisolated enum FluxSymbol {
    // MARK: 导航 / 功能入口

    public static let downloads = "arrow.down.circle"
    public static let subscriptions = "dot.radiowaves.up.forward"
    /// 设备标签（主机 + 已配对设备）。`macbook.and.iphone` 是受限的 Apple 产品图形，不用。
    public static let devices = "network"
    public static let settings = "gearshape"
    public static let search = "magnifyingglass"
    public static let newDownload = "plus"
    public static let add = "plus"
    /// 工具栏「更多」菜单（iOS 26 工具栏不带圆圈）。
    public static let more = "ellipsis"
    /// 进入多选。
    public static let select = "checkmark.circle"
    /// 视图选项：排序 / 分组 / 筛选。
    public static let viewOptions = "line.3.horizontal.decrease"
    public static let activity = "waveform.path.ecg"
    public static let queue = "list.number"
    public static let taskGroup = "square.stack"
    public static let category = "tag"
    public static let folder = "folder"
    public static let account = "person.crop.circle"
    public static let extensions = "puzzlepiece.extension"
    public static let webhook = "bolt.horizontal"
    public static let api = "curlybraces"
    public static let diagnostics = "stethoscope"
    public static let about = "info.circle"
    public static let appearance = "paintpalette"
    public static let notifications = "bell"
    public static let network = "globe"
    public static let bitTorrent = "point.3.connected.trianglepath.dotted"
    public static let ed2k = "server.rack"
    public static let log = "list.bullet.rectangle"

    // MARK: 任务动作

    public static let resume = "play.fill"
    public static let pause = "pause.fill"
    public static let retry = "arrow.clockwise"
    public static let delete = "trash"
    public static let copy = "document.on.document"
    public static let share = "square.and.arrow.up"
    public static let openFile = "arrow.up.forward.app"
    public static let showInFiles = "folder"
    public static let rename = "pencil"
    public static let changeSource = "link"
    public static let link = "link"
    public static let boost = "bolt.fill"
    public static let cancelBoost = "bolt.slash.fill"
    public static let detail = "info.circle"
    public static let edit = "pencil"
    public static let close = "xmark"
    public static let done = "checkmark"
    public static let importFile = "square.and.arrow.down"
    public static let externalLink = "arrow.up.right"

    // MARK: 文件已存在（询问对话框 / 批量菜单）

    /// 另存为新文件名（编号重命名）。
    public static let conflictRename = "doc.badge.plus"
    /// 完成后替换旧文件。
    public static let conflictOverwrite = "arrow.triangle.2.circlepath"
    /// 跳过下载并沿用已有文件。
    public static let conflictSkip = "forward.end"
    /// 取消下载（任务保持暂停）。
    public static let conflictCancel = "xmark.circle"

    // MARK: 主机 / 连接 / 同步

    /// 远端主机（NAS / 服务器上的 `fluxdown-agent --server`）；`server.rack` 留给 eD2K 服务器设置，避免同屏重名。
    public static let remoteHost = "externaldrive.connected.to.line.below"
    public static let switchHost = "arrow.left.arrow.right"
    /// 进行中的同步 / 重连。
    public static let syncing = "arrow.trianglehead.2.clockwise.rotate.90"
    public static let offline = "wifi.slash"
    /// FluxCloud（第三方云服务，不得用 `icloud*`）。
    public static let cloud = "cloud"
    public static let cloudFilled = "cloud.fill"
    public static let lock = "lock"
    public static let key = "key"

    /// 本机：iPhone / iPad 各用自己的设备图形（Apple 产品符号只能指真实设备）。
    @MainActor public static var thisDevice: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
    }

    // MARK: 语气 / 状态（角标、横幅、Toast、状态行）

    public static let success = "checkmark.circle.fill"
    public static let warning = "exclamationmark.triangle.fill"
    public static let failure = "exclamationmark.circle.fill"
    public static let info = "info.circle.fill"
    public static let pending = "clock"
}
