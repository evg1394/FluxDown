import Foundation

/// 自定义分类（`CustomCategoryDto`，偏好键 `custom_categories`）。
/// 列表由主机快照下发（Rust `CustomCategoryDto::from_preference`：空 / 损坏回退内置基线），
/// Swift 只做匹配，不另维护分类规则来源。
public struct TaskCategory: Sendable, Hashable, Identifiable {
    public var id: String
    /// 内置分类为空串：显示名走 i18n（`categoryVideo` …）。
    public var name: String
    public var icon: String
    public var extensions: [String]
    /// 非空 = 正则模式（`matchMode = regex`）。
    public var regexPattern: String
    public var position: Int32
    public var visible: Bool
    /// all / video / audio / document / image / program / archive / other；自定义为 nil。
    public var builtinType: String?

    public init(
        id: String,
        name: String,
        icon: String,
        extensions: [String],
        regexPattern: String = "",
        position: Int32,
        visible: Bool = true,
        builtinType: String? = nil
    ) {
        self.id = id
        self.name = name
        self.icon = icon
        self.extensions = extensions
        self.regexPattern = regexPattern
        self.position = position
        self.visible = visible
        self.builtinType = builtinType
    }

    public var isAll: Bool { builtinType == "all" }
    public var isOther: Bool { builtinType == "other" }

    /// 与 `native/protocol/src/agent.rs::CustomCategoryDto::builtin_defaults` 同序同扩展名
    /// （主机未下发时的展示基线）。
    public static let builtin: [TaskCategory] = [
        make("all", "folders", [], 0),
        make("video", "film", ["mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m3u8"], 1),
        make("audio", "music", ["mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus"], 2),
        make("document", "fileText", ["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "md"], 3),
        make("image", "image", ["jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif"], 4),
        make("program", "cpu", ["exe", "msi", "dmg", "pkg", "deb", "rpm", "apk", "appimage"], 5),
        make("archive", "archive", ["zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso"], 6),
        make("other", "file", [], 7),
    ]

    private static func make(_ type: String, _ icon: String, _ exts: [String], _ position: Int32) -> TaskCategory {
        TaskCategory(id: "builtin_\(type)", name: "", icon: icon, extensions: exts, position: position, builtinType: type)
    }
}

/// 按 position 排序的匹配索引（语义同 GPUI `CategoryIndex` / Android `CategoryIndex`）：
/// 首个命中的具体分类；都不命中 → other。
public struct CategoryIndex: Sendable {
    public let ordered: [TaskCategory]
    private let other: TaskCategory?
    private let rules: [Rule]

    private struct Rule: Sendable {
        let category: TaskCategory
        let regex: NSRegularExpression?
        let extensions: Set<String>

        func matches(_ fileName: String) -> Bool {
            if !category.regexPattern.isEmpty {
                guard let regex else { return false }
                let range = NSRange(fileName.startIndex..., in: fileName)
                return regex.firstMatch(in: fileName, range: range) != nil
            }
            return extensions.contains(Self.extensionOf(fileName))
        }

        static func extensionOf(_ name: String) -> String {
            guard let dot = name.lastIndex(of: ".") else { return "" }
            return name[name.index(after: dot)...].lowercased()
        }
    }

    public init(_ categories: [TaskCategory]) {
        ordered = categories.sorted { $0.position < $1.position }
        other = ordered.first { $0.isOther }
        rules = ordered.filter { !$0.isAll && !$0.isOther }.map { category in
            let regex: NSRegularExpression? = category.regexPattern.isEmpty
                ? nil
                : try? NSRegularExpression(pattern: category.regexPattern, options: [.caseInsensitive])
            let exts = Set(category.extensions.map { ext in
                let lower = ext.lowercased()
                return lower.hasPrefix(".") ? String(lower.dropFirst()) : lower
            })
            return Rule(category: category, regex: regex, extensions: exts)
        }
    }

    /// 文件命中的首个具体分类；都不命中 → other（无 other 时 nil）。
    public func categoryOf(_ fileName: String) -> TaskCategory? {
        rules.first { $0.matches(fileName) }?.category ?? other
    }

    /// 文件是否属于分类（all 恒真；other = 未命中任何具体分类）。
    public func matches(_ category: TaskCategory, _ fileName: String) -> Bool {
        if category.isAll { return true }
        if category.isOther { return !rules.contains { $0.matches(fileName) } }
        return rules.first { $0.category.id == category.id }?.matches(fileName) ?? false
    }
}
