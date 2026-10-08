// 活动栏账户头像的展示数据：来自主机（agent）快照的会话，与 GPUI `rail.rs::identity` 同规则。

import { useAgent } from '../lib/rpc'
import { badgeText, parseBadgeColor } from '../pages/settings/sections/account/planBadgeText'

export interface AccountAvatarState {
  /** 悬浮提示 / 无障碍标签：展示名（· 完整徽标文字）；未登录为 null。 */
  label: string | null
  initial: string | null
  badgeColor: string | null
}

export function useAccountAvatar(): AccountAvatarState {
  const session = useAgent((snapshot) => snapshot.session, null)
  if (!session) return { label: null, initial: null, badgeColor: null }
  const nickname = session.user.nickname.trim()
  const name = nickname || (session.user.email.split('@')[0] ?? session.user.email)
  const plan = session.currentPlan
  const text = plan ? badgeText(plan, session.user.membershipOrdinal) : null
  const badgeColor = plan && text ? (parseBadgeColor(plan.badgeColor) ?? 'var(--fx-colors-accent-foreground, currentColor)') : null
  return {
    label: text ? `${name} · ${text}` : name,
    initial: [...name.trim()][0]?.toUpperCase() ?? null,
    badgeColor,
  }
}
