import FluxDomain
import FluxUI
import SwiftUI

/// 套餐徽标（只读；GPUI `profile.rs::plan_tag` / Web `PlanBadge.tsx`）：outline | solid | medal | ribbon | plain，纯色无渐变。
/// 仅当 `CloudPlan.badge` 非空时显示。**不含任何购买入口**（App Store 规则）。
struct PlanBadgeView: View {
    let plan: CloudPlan
    let ordinal: Int64?

    var body: some View {
        if let text = AccountRules.planBadgeText(plan, ordinal: ordinal) {
            badge(text: text, color: color)
                .font(.fluxBadge)
                .lineLimit(1)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(text)
        }
    }

    /// 云端下发的徽标色（`RRGGBB` / `AARRGGBB`）；非法回退强调色。
    private var color: Color {
        guard let parsed = AccountRules.parseBadgeColor(plan.badgeColor) else { return .accentColor }
        return Color(.sRGB, red: parsed.red, green: parsed.green, blue: parsed.blue, opacity: parsed.alpha)
    }

    @ViewBuilder
    private func badge(text: String, color: Color) -> some View {
        switch plan.badgeStyle {
        case "outline":
            HStack(spacing: 3) {
                Image(systemName: "crown.fill").imageScale(.small)
                Text(text)
            }
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .frame(minHeight: 20)
            .background(color.opacity(0.08), in: .capsule)
            .overlay(Capsule().strokeBorder(color, lineWidth: 1))
        case "solid":
            Text(text)
                .foregroundStyle(.white)
                .padding(.horizontal, 8)
                .frame(minHeight: 20)
                .background(color, in: .capsule)
        case "medal":
            HStack(spacing: 0) {
                Image(systemName: "crown.fill")
                    .imageScale(.small)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 5)
                    .frame(maxHeight: .infinity)
                    .background(color)
                Text(text)
                    .foregroundStyle(color)
                    .padding(.horizontal, 6)
            }
            .frame(minHeight: 20)
            .clipShape(.capsule)
            .overlay(Capsule().strokeBorder(color, lineWidth: 1))
        case "ribbon":
            Text(text)
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .frame(minHeight: 20)
                .background(color, in: RibbonShape())
        default:
            Text(text)
                .foregroundStyle(color)
                .padding(.horizontal, 6)
                .frame(minHeight: 20)
                .background(color.opacity(0.12), in: .capsule)
        }
    }
}

/// 左右两端内凹的丝带。
private nonisolated struct RibbonShape: Shape {
    func path(in rect: CGRect) -> Path {
        let notch = rect.height * 0.35
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - notch, y: rect.midY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + notch, y: rect.midY))
        path.closeSubpath()
        return path
    }
}
