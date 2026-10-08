// swift-tools-version: 6.2
// FluxKit：iOS App 的本地包（与 Android 的 :core / :bridge / :fluxui 模块一一对应）。
//
//   FluxDomain  = Android :core   无 UI：主机端口（HostSession）+ 状态仓库（HostStore）+ 模型 / 格式化 / 文案
//   FluxBridge  = Android :bridge UniFFI `native/mobile`（crate fluxdown_mobile）的 Swift 侧：RustHostSession 适配 + DTO 映射
//   FluxUI      = Android :fluxui 纯 SwiftUI 组件库（不依赖业务模型）：色彩令牌、品牌签名（分段图谱 / 速度波形）…
//   FluxRustBindings（内部）UniFFI 生成的 Swift；只被 FluxBridge `internal import`，生成类型（HostSession / FluxCore /
//   *Dto）不外泄到 App，避免与 FluxDomain 同名类型冲突。
// （领域模块不叫 FluxCore：生成代码里已有同名类 `FluxCore`。）
//
// `Artifacts/fluxdown_mobileFFI.xcframework` 与 `Sources/FluxRustBindings/` 由
// `../scripts/build-core.sh` 生成（gitignore），改 Rust 后重跑。
import PackageDescription

let package = Package(
    name: "FluxKit",
    defaultLocalization: "en",
    platforms: [.iOS("26.1")],
    products: [
        .library(name: "FluxDomain", targets: ["FluxDomain"]),
        .library(name: "FluxBridge", targets: ["FluxBridge"]),
        .library(name: "FluxUI", targets: ["FluxUI"]),
    ],
    targets: [
        .target(
            name: "FluxDomain",
            resources: [.copy("Resources/i18n")]
        ),
        .binaryTarget(
            name: "fluxdown_mobileFFI",
            path: "Artifacts/fluxdown_mobileFFI.xcframework"
        ),
        .target(
            name: "FluxRustBindings",
            dependencies: ["fluxdown_mobileFFI"],
            // UniFFI 生成代码按 Swift 5 语言模式编译（其并发注解不满足 Swift 6 严格检查）。
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [
                .linkedFramework("Security"),
                .linkedFramework("SystemConfiguration"),
                .linkedFramework("CoreFoundation"),
                .linkedLibrary("resolv"),
            ]
        ),
        .target(
            name: "FluxBridge",
            dependencies: ["FluxDomain", "FluxRustBindings"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .target(
            name: "FluxUI",
            swiftSettings: [.defaultIsolation(MainActor.self)]
        ),
        .testTarget(name: "FluxDomainTests", dependencies: ["FluxDomain"]),
        .testTarget(
            name: "FluxBridgeTests",
            dependencies: ["FluxBridge", "FluxDomain", "FluxRustBindings"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
