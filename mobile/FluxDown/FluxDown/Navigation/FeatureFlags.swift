import Foundation

/// 集中功能开关：关闭的功能不暴露任何入口（页面代码保留，重新开放只改这里）。
nonisolated enum FeatureFlags {
    /// Webhook / 推送通知：移动端暂不支持，隐藏设置分类、搜索条目、通知页入口与通用页「显示 Webhook 活动」开关。
    static let webhooks = false
}
