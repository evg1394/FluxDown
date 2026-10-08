import Foundation

/// 本机主机的目录约定。
///
/// - 引擎数据（DB、agent 状态）：`Library/Application Support/fluxdown`，不对用户可见、随 iCloud 备份排除。
/// - 默认保存目录：`Documents/`，配合 Info.plist `UIFileSharingEnabled` 出现在「文件」App 的
///   「我的 iPhone › FluxDown」。
///
/// iOS 可能在 App 更新后更换沙盒容器路径（`…/Data/Application/<UUID>/`），库里存的绝对路径随之失效：
/// `rebased(_:)` 把旧容器下的 `Documents/…` 路径改写到当前容器。
enum LocalPaths {
    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    static var dataDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("fluxdown", isDirectory: true)
    }

    /// 创建目录并把引擎数据目录排除出 iCloud 备份（下载数据库可重建，不应占用户备份配额）。
    static func prepare() throws {
        let fm = FileManager.default
        try fm.createDirectory(at: documents, withIntermediateDirectories: true)
        var data = dataDirectory
        try fm.createDirectory(at: data, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try data.setResourceValues(values)
    }

    /// 旧容器下 `…/Documents[/sub]` → 当前容器的同名相对路径；本就存在或不在 Documents 下则原样返回。
    static func rebased(_ path: String) -> String {
        if FileManager.default.fileExists(atPath: path) { return path }
        guard let range = path.range(of: "/Documents", options: .backwards) else { return path }
        let tail = path[range.upperBound...]
        guard tail.isEmpty || tail.hasPrefix("/") else { return path }
        let current = documents.path + tail
        return current == path ? path : current
    }

    /// 已完成任务的本机文件 URL（容器路径变化后自动改写）。
    static func fileURL(saveDir: String, fileName: String) -> URL {
        URL(fileURLWithPath: rebased(saveDir), isDirectory: true).appendingPathComponent(fileName)
    }

    /// 「文件」App 中显示目录：`shareddocuments://` 打开 App 自己 Documents 下的路径。
    static func filesAppURL(forDirectory path: String) -> URL? {
        let local = rebased(path)
        let encoded = local.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? local
        return URL(string: "shareddocuments://" + encoded)
    }
}
