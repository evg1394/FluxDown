// 活动栏（桌面）：48px 宽、chrome 底；路由在上、动作（账户 / 设置）在下。
// 选中项：中性底 `navSelected` + 图标 `navSelectedIcon`（强调色只落在图标上）。

import { Link, useLocation } from '@tanstack/react-router'
import { useT } from '../i18n'
import { cn } from '../lib/cn'
import { Icon, Tooltip } from '../ui'
import { AccountAvatar } from './AccountAvatar'
import { useAccountAvatar } from './accountAvatarState'
import { AccountMenu } from './AccountMenu'
import { isActivityActive } from './activity'
import type { ActivityEntry } from './activity'
import { useVisibleActivityEntries } from './useVisibleActivityEntries'

const BUTTON =
  'inline-flex size-8 coarse:size-touch items-center justify-center rounded-[var(--fx-components-nav-item-radius)] text-muted-foreground transition-colors hover:bg-nav-hover hover:text-foreground'
/** 选中项悬停不变色（GPUI `activity_button`：选中态 hover 与常态一致）。 */
const BUTTON_ACTIVE = 'bg-nav-selected text-nav-selected-foreground hover:bg-nav-selected hover:text-nav-selected-foreground'

export function ActivityRail() {
  const t = useT()
  const location = useLocation()
  const account = useAccountAvatar()
  const entries = useVisibleActivityEntries()
  const top = entries.filter((entry) => !entry.bottom)
  const bottom = entries.filter((entry) => entry.bottom)

  const renderEntry = (entry: ActivityEntry) => {
    const active = isActivityActive(entry, location.pathname)
    const className = cn(BUTTON, active && BUTTON_ACTIVE)
    if (entry.id === 'account') {
      const label = account.label ?? t('accountLogin')
      return (
        <AccountMenu
          key={entry.id}
          avatar={account}
          trigger={(open) => (
            <Tooltip content={label} side="right">
              <button type="button" aria-label={label} className={cn(BUTTON, (active || open) && BUTTON_ACTIVE)}>
                <AccountAvatar state={account} />
              </button>
            </Tooltip>
          )}
        />
      )
    }
    const label = t(entry.labelKey)
    return (
      <Tooltip key={entry.id} content={label} side="right">
        <Link to={entry.to ?? '/'} aria-label={label} aria-current={active ? 'page' : undefined} className={className}>
          <Icon icon={entry.icon} size="xl" className={cn(active && 'text-nav-selected-icon')} />
        </Link>
      </Tooltip>
    )
  }

  return (
    <nav aria-label="Activity" className="flex w-rail shrink-0 flex-col items-center justify-between gap-1 bg-chrome py-2 pl-safe mobile:hidden">
      <div className="flex flex-col items-center gap-1">{top.map(renderEntry)}</div>
      <div className="flex flex-col items-center gap-1">{bottom.map(renderEntry)}</div>
    </nav>
  )
}
