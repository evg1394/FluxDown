// 账户头像（GPUI `crates/account/src/rail.rs`）：未登录为人形图标；已登录为首字母头像，
// 套餐带徽标（如创始会员）时叠加徽标色环与皇冠角标。显示的是主机（agent）登录的账户。

import { CircleUser, Crown } from 'lucide-react'
import { cn } from '../lib/cn'
import type { AccountAvatarState } from './accountAvatarState'

/** 头像本体：活动栏 `size-6`，卡片 `size-10`；色环与角标只在有徽标时出现。 */
export function AccountAvatar({ state, large = false }: { state: AccountAvatarState; large?: boolean }) {
  if (state.label === null) return <CircleUser className={large ? 'size-8' : 'size-5'} strokeWidth={1.75} />
  const face = (
    <span
      className={cn(
        'flex items-center justify-center rounded-full bg-accent-text/12 font-semibold text-accent-text',
        large ? 'size-10 text-title' : 'size-6 text-caption',
      )}
    >
      {state.initial ?? <CircleUser className={large ? 'size-6' : 'size-4'} strokeWidth={1.75} />}
    </span>
  )
  if (state.badgeColor === null) return face
  return (
    <span className="relative flex shrink-0 items-center justify-center rounded-full border-2 p-px" style={{ borderColor: state.badgeColor }}>
      {face}
      <span
        className={cn(
          'absolute flex items-center justify-center rounded-full text-white',
          large ? '-right-1 -top-1 size-4' : '-right-1.5 -top-1.5 size-3.5',
        )}
        style={{ background: state.badgeColor }}
      >
        <Crown className={large ? 'size-3' : 'size-2.5'} strokeWidth={2} />
      </span>
    </span>
  )
}
