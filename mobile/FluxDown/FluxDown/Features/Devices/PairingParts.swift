import FluxDomain
import FluxUI
import SwiftUI

/// SAS 短认证串（V4 核对页 / V5 入站确认页共用）：3+3 分组，圆体等宽大数字，两组依次淡入放大（间隔 80 ms，减弱动态效果下直接显示）。
/// 字号随 Dynamic Type 缩放并封顶；放不下时两组纵排。VoiceOver 逐位朗读，数字之间空格分隔。
struct SasDigitsView: View {
    let sas: String
    let size: CGFloat

    @ScaledMetric private var points: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var revealed = 0

    init(sas: String, size: CGFloat) {
        self.sas = sas
        self.size = size
        _points = ScaledMetric(wrappedValue: size, relativeTo: .largeTitle)
    }

    var body: some View {
        let groups = LinkRules.groupSAS(sas).split(separator: " ").map(String.init)
        let font = Font.system(size: min(points, size * 1.5), weight: .semibold, design: .rounded)
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 20) { digitGroups(groups, font: font) }
            VStack(spacing: 8) { digitGroups(groups, font: font) }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("localPairingSasTitle"))
        .accessibilityValue(sas.filter(\.isNumber).map(String.init).joined(separator: " "))
        .task(id: sas) { await reveal(count: groups.count) }
    }

    @ViewBuilder
    private func digitGroups(_ groups: [String], font: Font) -> some View {
        ForEach(Array(groups.enumerated()), id: \.offset) { index, group in
            Text(group)
                .font(font)
                .monospacedDigit()
                .tracking(2)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .opacity(revealed > index ? 1 : 0)
                .scaleEffect(revealed > index ? 1 : 0.85)
        }
    }

    private func reveal(count: Int) async {
        guard count > 0 else { return }
        if reduceMotion {
            revealed = count
            return
        }
        revealed = 0
        for index in 1 ... count {
            do {
                try await Task.sleep(for: .milliseconds(80))
            } catch {
                return
            }
            withFluxAnimation(.smooth, reduceMotion: reduceMotion) { revealed = index }
        }
    }
}
