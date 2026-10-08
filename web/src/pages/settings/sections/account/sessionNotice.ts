// 会话被撤销（`sessionRevoked` 事件携带 reason）→ 一次性提示文案键。

import type { ErrorReason } from '../../../../lib/rpc'

/** reason → 提示文案键；账号被停用 / 设备被移出信任各有专属文案，其余一律按会话失效。 */
export function sessionRevokedKey(reason: ErrorReason): string {
  switch (reason) {
    case 'deviceUntrusted':
      return 'accountSessionRevokedUntrusted'
    case 'passwordChanged':
      return 'accountSessionRevokedPasswordChanged'
    case 'accountDisabled':
      return 'accountErrorAccountDisabled'
    default:
      return 'accountSessionRevokedExpired'
  }
}
