import { usePrefBool } from '../lib/rpc/hooks'
import { ACTIVITY_ENTRIES } from './activity'
import type { ActivityEntry } from './activity'

/** 各可选入口的可见性（偏好缺省 = 显示）。 */
export function useVisibleActivityEntries(): ActivityEntry[] {
  const rss = usePrefBool('ui.show_activity_rss', true)
  const webhooks = usePrefBool('ui.show_activity_webhooks', true)
  const account = usePrefBool('ui.show_activity_account', true)
  const visible: Record<string, boolean> = {
    'ui.show_activity_rss': rss,
    'ui.show_activity_webhooks': webhooks,
    'ui.show_activity_account': account,
  }
  return ACTIVITY_ENTRIES.filter((entry) => entry.prefKey === undefined || visible[entry.prefKey] !== false)
}
