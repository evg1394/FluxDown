import FluxDomain
import FluxUI
import SwiftUI

/// D8 每日定时：开关 + 启动 / 停止时间（行点击展开内联滚轮）+ 7 个星期圆钮（02-downloads §9.2）。
/// 启停时间的「时」列含「不定时」，选它则「分」列显示 `--` 且不可选；两端都不定时时保存会被 `QueueDraft` 拒绝。
struct QueueScheduleSection: View {
    @Binding var draft: QueueDraft
    let issue: QueueDraft.Issue?

    private nonisolated enum TimeField: Hashable {
        case start, stop
    }

    @State private var expanded: TimeField?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Section {
            Toggle(isOn: $draft.scheduleEnabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("queueScheduleEnable"))
                    Text(L("queueScheduleDesc"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            if draft.scheduleEnabled {
                timeRow(.start, title: L("queueScheduleStartLabel"), keyPath: \.scheduleStart)
                timeRow(.stop, title: L("queueScheduleStopLabel"), keyPath: \.scheduleStop)
                QueueWeekdayRow(days: $draft.days)
            }
        } footer: {
            if draft.scheduleEnabled {
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("queueScheduleTimePickHint"))
                    if issue == .scheduleNeedsOneTime {
                        QueueIssueText(text: L("scheduleNeedOneTime"))
                    }
                }
            }
        }
        .onChange(of: draft.scheduleEnabled) { _, enabled in
            if !enabled { expanded = nil }
        }
    }

    // MARK: 时间行

    @ViewBuilder
    private func timeRow(_ field: TimeField, title: String, keyPath: WritableKeyPath<QueueDraft, Int?>) -> some View {
        let value = draft[keyPath: keyPath]
        let isOpen = expanded == field
        QueueTimeRowButton(title: title, value: Self.display(value), isOpen: isOpen) {
            withFluxAnimation(.snappy, reduceMotion: reduceMotion) {
                expanded = isOpen ? nil : field
            }
        }
        if isOpen {
            QueueTimeWheel(
                value: Binding(get: { draft[keyPath: keyPath] }, set: { draft[keyPath: keyPath] = $0 })
            )
            .transition(.opacity)
        }
    }

    private static func display(_ minutes: Int?) -> String {
        minutes.map { ScheduleTime.format($0) } ?? L("queueScheduleTimeUnset")
    }
}

/// 时间行：标题 + 当前值；展开时值用强调色（AX 字号纵排）。
private struct QueueTimeRowButton: View {
    let title: String
    let value: String
    let isOpen: Bool
    let action: () -> Void

    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.fluxAccent) private var accent

    var body: some View {
        Button(action: action) {
            let layout = typeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
                : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 12))
            layout {
                Text(title).foregroundStyle(.primary)
                if !typeSize.isAccessibilitySize { Spacer(minLength: 0) }
                Text(value)
                    .monospacedDigit()
                    .foregroundStyle(isOpen ? accent.text : Color.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// 内联滚轮：时（含「不定时」）+ 分（步长 5，外加当前值）。
private struct QueueTimeWheel: View {
    @Binding var value: Int?

    @ScaledMetric(relativeTo: .body) private var wheelHeight: CGFloat = 150

    var body: some View {
        let hour = value.map { $0 / 60 }
        let minute = value.map { $0 % 60 }
        HStack(spacing: 0) {
            Picker(L("queueScheduleHourLabel"), selection: hourBinding) {
                Text(L("queueScheduleTimeUnset")).tag(Int?.none)
                ForEach(0 ..< 24, id: \.self) { h in
                    Text(String(format: "%02d", h)).monospacedDigit().tag(Int?.some(h))
                }
            }
            .pickerStyle(.wheel)
            .frame(maxWidth: .infinity)

            Picker(L("queueScheduleMinuteLabel"), selection: minuteBinding) {
                if hour == nil {
                    Text(verbatim: "--").tag(-1)
                } else {
                    ForEach(ScheduleTime.minuteChoices(current: minute ?? 0), id: \.self) { m in
                        Text(String(format: "%02d", m)).monospacedDigit().tag(m)
                    }
                }
            }
            .pickerStyle(.wheel)
            .frame(maxWidth: .infinity)
            .disabled(hour == nil)
        }
        .labelsHidden()
        .frame(height: min(wheelHeight, 300))
    }

    /// 选「不定时」清空；选具体小时保留当前分钟（没有则 00）。
    private var hourBinding: Binding<Int?> {
        Binding(
            get: { value.map { $0 / 60 } },
            set: { hour in
                if let hour {
                    value = hour * 60 + (value.map { $0 % 60 } ?? 0)
                } else {
                    value = nil
                }
            }
        )
    }

    private var minuteBinding: Binding<Int> {
        Binding(
            get: { value.map { $0 % 60 } ?? -1 },
            set: { minute in
                guard minute >= 0, let hour = value.map({ $0 / 60 }) else { return }
                value = hour * 60 + minute
            }
        )
    }
}

/// 7 个圆形星期钮（`weekdaysShort`；位掩码 bit0 = 周一）。至少保留一天（`WeekdayMask.toggling`）。
/// 一行放得下就等分排开，放不下（AX 字号）自动换行成网格；触控目标 ≥ 44 pt。
private struct QueueWeekdayRow: View {
    @Binding var days: Int32

    @ScaledMetric(relativeTo: .subheadline) private var circle: CGFloat = 36
    @Environment(\.fluxAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let labels = QueueText.weekdayLabels()
        VStack(alignment: .leading, spacing: 8) {
            Text(L("queueScheduleDays"))
            if !labels.isEmpty {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 0) {
                        ForEach(labels.indices, id: \.self) { dayButton($0, label: labels[$0]) }
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: max(44, circle + 8)), spacing: 4)], alignment: .leading, spacing: 4) {
                        ForEach(labels.indices, id: \.self) { dayButton($0, label: labels[$0]) }
                    }
                }
            }
        }
        .padding(.vertical, 2)
    }

    private func dayButton(_ day: Int, label: String) -> some View {
        let selected = WeekdayMask.contains(days, day: day)
        return Button {
            withFluxAnimation(.bouncy, reduceMotion: reduceMotion) {
                days = WeekdayMask.toggling(days, day: day)
            }
        } label: {
            Text(label)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(selected ? accent.onAccent : Color.primary)
                .minimumScaleFactor(0.7)
                .lineLimit(1)
                .frame(width: circle, height: circle)
                .background(selected ? accent.color : Color(uiColor: .tertiarySystemFill), in: .circle)
                .frame(minWidth: 44, maxWidth: .infinity, minHeight: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .sensoryFeedback(.selection, trigger: selected)
    }
}
