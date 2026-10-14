// 活动栏注册表：镜像 `crates/app/src/activity.rs`（条目顺序、文案键、可选入口偏好键）。
// 路由在上、动作在下；可选入口偏好缺省视为显示。

import { BellRing, CircleUser, Download, Rss, Settings } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export type ActivityId = 'downloads' | 'rss' | 'webhooks' | 'account' | 'settings'

export interface ActivityEntry {
  id: ActivityId
  labelKey: string
  icon: LucideIcon
  /** 路由条目的目标路径；账户条目由 `AccountAvatar` 自绘并链到设置的账户分类。 */
  to?: '/' | '/rss' | '/webhooks' | '/settings'
  /** 可选入口的可见性偏好键；undefined = 固定显示。 */
  prefKey?: string
  /** 是否在底部对齐（动作区）。 */
  bottom: boolean
}

export const ACTIVITY_ENTRIES: readonly ActivityEntry[] = [
  { id: 'downloads', labelKey: 'mobileNavDownloads', icon: Download, to: '/', bottom: false },
  { id: 'rss', labelKey: 'sidebarRss', icon: Rss, to: '/rss', prefKey: 'ui.show_activity_rss', bottom: false },
  { id: 'webhooks', labelKey: 'pushNavTitle', icon: BellRing, to: '/webhooks', prefKey: 'ui.show_activity_webhooks', bottom: false },
  { id: 'account', labelKey: 'settingsCatAccount', icon: CircleUser, prefKey: 'ui.show_activity_account', bottom: true },
  { id: 'settings', labelKey: 'settings', icon: Settings, to: '/settings', bottom: true },
]

/** 账户入口所在路径（设置的账户分类）。 */
export const ACCOUNT_PATH = '/settings/account'

/** 当前路径是否命中该条目（Downloads 精确匹配 `/`，账户匹配账户分类，其余前缀匹配；账户分类不再点亮设置）。 */
export function isActivityActive(entry: ActivityEntry, pathname: string): boolean {
  if (entry.id === 'account') return pathname === ACCOUNT_PATH
  if (!entry.to) return false
  if (entry.to === '/') return pathname === '/'
  if (pathname === ACCOUNT_PATH) return false
  return pathname === entry.to || pathname.startsWith(`${entry.to}/`)
}
