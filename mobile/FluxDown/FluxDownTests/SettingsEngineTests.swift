import FluxDomain
import FluxUI
import Foundation
import Testing
@testable import FluxDown

// 外观偏好线上形态映射、保持常亮规则、设置搜索聚合。

@MainActor
struct AppearanceWireTests {
    @Test func sharedPresetsWriteOnlyTheSchemeKey() {
        #expect(AppearanceWire.encode(scheme: "green", customRGB: 0x123456) == ["appearance.color_scheme": "green"])
        #expect(AppearanceWire.encode(scheme: "blue", customRGB: 0x123456) == ["appearance.color_scheme": "blue"])
    }

    @Test func customWritesSchemeAndArgbTogether() {
        let wire = AppearanceWire.encode(scheme: "custom", customRGB: 0x112233)
        #expect(wire["appearance.color_scheme"] == "custom")
        #expect(wire["appearance.custom_color"] == String(0xFF11_2233))
        #expect(wire.count == 2)
    }

    @Test func iosOnlyPresetsTravelAsCustomColors() {
        let orange = AppearanceWire.encode(scheme: "orange", customRGB: 0x000000)
        #expect(orange["appearance.color_scheme"] == "custom")
        #expect(orange["appearance.custom_color"] == String(AppearanceWire.argb(fromRGB: FluxAccent.preset("orange").rgb)))
    }

    @Test func encodedValuesPassCatalogValidation() throws {
        for scheme in ["blue", "custom", "orange", "indigo"] {
            let wire = AppearanceWire.encode(scheme: scheme, customRGB: 0xABCDEF)
            #expect(throws: Never.self) { _ = try SettingsWritePlan.make(wire).get() }
            let plan = try SettingsWritePlan.make(wire).get()
            #expect(plan.localPreferences.isEmpty)
            #expect(!plan.syncedPreferences.isEmpty)
        }
    }

    @Test func argbRoundTrips() {
        #expect(AppearanceWire.rgb(fromARGB: 0xFF11_2233) == 0x112233)
        #expect(AppearanceWire.argb(fromRGB: 0x112233) == 0xFF11_2233)
        #expect(AppearanceWire.rgb(fromARGB: 0x0011_2233) == 0x112233) // alpha 被忽略
    }

    @Test func decodeMapsHostValuesToLocalAppearance() {
        let dark = AppearanceWire.decode(.init(mode: "dark", scheme: "rose", customARGB: nil))
        #expect(dark.mode == .dark && dark.scheme == "rose" && dark.customRGB == nil)

        let custom = AppearanceWire.decode(.init(mode: nil, scheme: "custom", customARGB: 0xFF11_2233))
        #expect(custom.scheme == "custom" && custom.customRGB == 0x112233 && custom.mode == nil)

        // 线上的 custom + 预设色值 → 认回 iOS 预设（PC 同步下来的橙色仍是橙色）。
        let orangeWire = AppearanceWire.encode(scheme: "orange", customRGB: 0)
        let orangeHost = AppearanceWire.Host(
            mode: nil, scheme: orangeWire["appearance.color_scheme"],
            customARGB: orangeWire["appearance.custom_color"].flatMap { Int64($0) }
        )
        #expect(AppearanceWire.decode(orangeHost).scheme == "orange")
    }

    @Test func decodeIgnoresMissingOrUnknownValues() {
        #expect(AppearanceWire.decode(.init()) == AppearanceWire.Local())
        #expect(AppearanceWire.decode(.init(mode: "auto", scheme: "teal", customARGB: 5)) == AppearanceWire.Local())
        #expect(AppearanceWire.Host().isEmpty)
    }

    @Test func hostValuesComeFromThePreferencesSection() {
        let prefs = AgentPreferencesDto(values: [
            "appearance.theme_mode": .string("light"),
            "appearance.color_scheme": .string("custom"),
            "appearance.custom_color": .int(0xFF00_FF00),
        ])
        let host = AppearanceWire.Host(prefs)
        #expect(host.mode == "light" && host.scheme == "custom" && host.customARGB == 0xFF00_FF00)
    }
}

@MainActor
struct SettingsEffectsTests {
    private func prefs(_ keepAwake: Bool?) -> AgentPreferencesDto {
        AgentPreferencesDto(values: keepAwake.map { ["download.keep_awake": .bool($0)] } ?? [:])
    }

    @Test func keepAwakeNeedsPreferenceLocalHostForegroundAndActiveDownloads() {
        func wanted(_ keep: Bool?, local: Bool = true, active: Bool = true, foreground: Bool = true) -> Bool {
            SettingsEffects.keepAwakeWanted(
                preferences: prefs(keep), isLocalHost: local, hasActiveDownloads: active, isForeground: foreground
            )
        }
        #expect(wanted(true))
        #expect(!wanted(nil)) // 默认关
        #expect(!wanted(false))
        #expect(!wanted(true, local: false))
        #expect(!wanted(true, active: false))
        #expect(!wanted(true, foreground: false))
    }
}
