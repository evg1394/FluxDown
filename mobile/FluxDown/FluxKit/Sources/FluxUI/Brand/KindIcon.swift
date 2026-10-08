import SwiftUI

/// 文件类别（图标 + 类别色，§3.C / §6.2 / §6.4）。
public nonisolated enum FileKind: Sendable, Hashable, CaseIterable {
    case video, audio, document, image, program, archive, ebook, diskImage, application, torrent, other

    /// SF Symbol：行内类别图标（§6.4）。只用当前名称、不用受限符号（`video` 仅指 FaceTime，故视频用 `film`）；
    /// 种子与 BT 设置 / 协议同用 `FluxSymbol.bitTorrent`。
    public var symbolName: String {
        switch self {
        case .video: "film"
        case .audio: "music.note"
        case .document: "text.document"
        case .image: "photo"
        case .program: "shippingbox"
        case .archive: "zipper.page"
        case .ebook: "book.closed"
        case .diskImage: "opticaldisc"
        case .application: "app"
        case .torrent: FluxSymbol.bitTorrent
        case .other: "document"
        }
    }

    /// 类别色（§3.C；磁盘镜像随压缩包、应用 / 种子随程序）。
    public var tint: Color {
        switch self {
        case .video: .fdKindVideo
        case .audio: .fdKindAudio
        case .document: .fdKindDocument
        case .image: .fdKindImage
        case .program, .application, .torrent: .fdKindProgram
        case .archive, .diskImage: .fdKindArchive
        case .ebook: .fdKindEbook
        case .other: .fdKindOther
        }
    }

    /// 按文件扩展名推断类别（纯函数，大小写不敏感；无扩展名 / 未知 → `.other`）。
    public nonisolated static func from(fileName: String) -> FileKind {
        guard let dot = fileName.lastIndex(of: "."), dot != fileName.startIndex else { return .other }
        let ext = fileName[fileName.index(after: dot)...].lowercased()
        return extensionTable[ext] ?? .other
    }

    private nonisolated static let extensionTable: [String: FileKind] = {
        let groups: [(FileKind, [String])] = [
            (.video, ["mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m3u8", "mpg", "mpeg", "3gp", "rmvb", "mts", "m2ts"]),
            (.audio, ["mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "aiff", "ape", "mid", "midi"]),
            (.document, ["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv", "odt", "ods", "odp", "pages", "numbers", "key"]),
            (.image, ["jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "tif", "tiff", "ico", "raw", "psd"]),
            (.program, ["exe", "msi", "pkg", "deb", "rpm", "apk", "ipa", "appimage", "bat", "sh", "jar"]),
            (.archive, ["zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz", "z"]),
            (.ebook, ["epub", "mobi", "azw", "azw3", "fb2"]),
            (.diskImage, ["iso", "img", "dmg", "vhd", "vhdx", "vmdk", "bin", "cue"]),
            (.application, ["app"]),
            (.torrent, ["torrent"]),
        ]
        var table: [String: FileKind] = [:]
        for (kind, exts) in groups {
            for ext in exts { table[ext] = kind }
        }
        return table
    }()
}

/// 任务图标右下角的状态角标（§3.C、§11.5：形状 + 对钩 / 三角 / 叹号，不只靠颜色）。
public nonisolated enum KindBadge: Sendable, Hashable, CaseIterable {
    case none, completed, warning, failed

    fileprivate var symbolName: String? {
        switch self {
        case .none: nil
        case .completed: "checkmark.circle.fill"
        case .warning: "exclamationmark.triangle.fill"
        case .failed: "exclamationmark.circle.fill"
        }
    }

    fileprivate var color: Color {
        switch self {
        case .none, .completed: .fdToneSolidSuccess
        case .warning: .fdToneSolidWarning
        case .failed: .fdToneSolidError
        }
    }
}

/// 任务图标方块（§3.C）：连续圆角（边长 × 0.28）squircle，类别色淡底 + 类别色 SF Symbol（扁平：无渐变 / 高光 / 投影）。
///
/// 列表里几十行同时出现，饱和实心色块会把注意力从文件名抢走；淡底只保留「类别」这一层信息。
/// 增强对比度下淡底加深、符号加粗。
///
/// - `size` 为 Large 档基准边长（舒适 44 / 紧凑 32 / 详情英雄 72），随 Dynamic Type 缩放，上限 `max(size, 72)`。
/// - `dimmed`：文件已缺失（去饱和 + 降不透明度）。
/// - `backdrop`：角标镂空圈的底色，须与所在行 / 卡片底色一致。
/// - 纯装饰，对 VoiceOver 隐藏（状态由相邻文字承载）。
public struct KindIcon: View {
    public let kind: FileKind
    public let dimmed: Bool
    public let badge: KindBadge
    public let backdrop: Color

    private let baseSize: CGFloat
    @ScaledMetric private var scaledSize: CGFloat
    @Environment(\.colorSchemeContrast) private var contrast

    public init(
        kind: FileKind,
        size: CGFloat = 44,
        dimmed: Bool = false,
        badge: KindBadge = .none,
        backdrop: Color = Color(uiColor: .secondarySystemGroupedBackground)
    ) {
        self.kind = kind
        self.dimmed = dimmed
        self.badge = badge
        self.backdrop = backdrop
        baseSize = size
        _scaledSize = ScaledMetric(wrappedValue: size, relativeTo: .body)
    }

    private var side: CGFloat { min(scaledSize, max(baseSize, 72)) }

    public var body: some View {
        let tint = kind.tint
        let increased = contrast == .increased
        Image(systemName: kind.symbolName)
            .font(.system(size: side * 0.46, weight: increased ? .semibold : .regular))
            .foregroundStyle(tint)
            .frame(width: side, height: side)
            .background(tint.opacity(increased ? 0.26 : 0.15), in: .rect(cornerRadius: side * 0.28, style: .continuous))
            .saturation(dimmed ? 0 : 1)
            .opacity(dimmed ? 0.6 : 1)
            .overlay(alignment: .bottomTrailing) { badgeView }
            .accessibilityHidden(true)
    }

    @ViewBuilder private var badgeView: some View {
        if let symbol = badge.symbolName {
            let diameter = max(side * 0.4, 14)
            Image(systemName: symbol)
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, badge.color)
                .font(.system(size: diameter, weight: .bold))
                .background(backdrop, in: .circle.inset(by: -1.5))
                .offset(x: diameter * 0.22, y: diameter * 0.22)
        }
    }
}

#Preview("KindIcon · 类别") {
    ScrollView {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 80))], spacing: 16) {
            ForEach(FileKind.allCases, id: \.self) { kind in
                VStack(spacing: 6) {
                    KindIcon(kind: kind)
                    Text(String(describing: kind)).font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .padding()
    }
}

#Preview("KindIcon · 角标 / 缺失 / 深色") {
    HStack(spacing: 20) {
        KindIcon(kind: .video, badge: .completed)
        KindIcon(kind: .archive, badge: .warning)
        KindIcon(kind: .audio, badge: .failed)
        KindIcon(kind: .image, dimmed: true, badge: .warning)
        KindIcon(kind: .document, size: 32)
        KindIcon(kind: .program, size: 72)
    }
    .padding(24)
    .background(Color(uiColor: .secondarySystemGroupedBackground))
    .preferredColorScheme(.dark)
}
