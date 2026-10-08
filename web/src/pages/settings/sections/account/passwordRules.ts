// 密码表单本地校验（纯函数）：规则与 GPUI / 云端一致，长度按 Unicode 码点计。

export const MIN_PASSWORD_CHARS = 8

/** 码点数（`'😀'.length === 2`，这里算 1）。 */
export function passwordLength(value: string): number {
  return Array.from(value).length
}

/**
 * 校验新密码，返回第一个违反规则的文案键，通过为 `null`。
 * `current` 仅在「当前密码模式」传入，用于拒绝新旧相同；验证码模式传 `null`。
 */
export function newPasswordErrorKey(newPassword: string, confirm: string, current: string | null): string | null {
  if (passwordLength(newPassword) < MIN_PASSWORD_CHARS) return 'accountErrorPasswordTooShort'
  if (newPassword !== confirm) return 'accountPasswordMismatch'
  if (current !== null && newPassword === current) return 'accountPasswordSameAsCurrent'
  return null
}
