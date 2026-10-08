// 套餐徽标的文字与颜色（GPUI profile.rs `plan_badge_text` / `parse_hex_color`）：资料卡徽标与活动栏头像共用。

import type { CloudPlan } from '../../../../lib/rpc'

/** 接受可选 `#` 前缀的 6 位 `RRGGBB` 或 8 位 `AARRGGBB`，返回 CSS 颜色；非法返回 null。 */
export function parseBadgeColor(value: string): string | null {
  const hex = value.trim().replace(/^#/, '')
  if (!/^[0-9a-fA-F]+$/.test(hex)) return null
  if (hex.length === 6) return `#${hex}`
  if (hex.length === 8) {
    const alpha = Number.parseInt(hex.slice(0, 2), 16) / 255
    const r = Number.parseInt(hex.slice(2, 4), 16)
    const g = Number.parseInt(hex.slice(4, 6), 16)
    const b = Number.parseInt(hex.slice(6, 8), 16)
    return `rgb(${r} ${g} ${b} / ${alpha.toFixed(3)})`
  }
  return null
}

/** `{badge} No.{ordinal 补零到 digits(1..=6)}`；`badge` 为空返回 null。 */
export function badgeText(plan: CloudPlan, ordinal: number | null): string | null {
  const base = plan.badge?.trim()
  if (!base) return null
  if (plan.badgeNumbered && ordinal !== null) {
    const width = Math.min(6, Math.max(1, plan.badgeNumberDigits))
    return `${base} No.${String(ordinal).padStart(width, '0')}`
  }
  return base
}
