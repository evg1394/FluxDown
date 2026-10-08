// 更新重启后的页面刷新判定（纯函数，client.ts 负责接线）。
//
// 新版 SPA 内嵌在新二进制里：服务重启后 serviceVersion 变化即刷新页面；
// Docker / NAS 升级跨协议版本时握手会被 `protocolIncompatible` 拒绝，同样刷新取新资源，
// 以 sessionStorage 时间戳限频，避免新旧资源仍不匹配时无限刷新。

/** 两次「协议不兼容」刷新的最短间隔。 */
export const INCOMPATIBLE_RELOAD_GUARD_MS = 60_000
/** 记录上次「协议不兼容」刷新时间（ms）的 sessionStorage 键。 */
export const INCOMPATIBLE_RELOAD_KEY = 'fluxdown.incompatibleReloadAt'

/** 本次握手的 serviceVersion 相对上次已知版本发生变化（首次握手不算）。 */
export function shouldReloadForVersionChange(previous: string | null, next: string): boolean {
  return previous !== null && previous !== '' && next !== '' && previous !== next
}

/** `lastReloadAt` 为空或距今已满守卫窗口才允许再刷新；时钟回拨（未来时间戳）一律拒绝。 */
export function shouldReloadOnIncompatible(lastReloadAt: number | null, now: number): boolean {
  return lastReloadAt === null || now - lastReloadAt >= INCOMPATIBLE_RELOAD_GUARD_MS
}

function readStamp(): number | null {
  try {
    const raw = sessionStorage.getItem(INCOMPATIBLE_RELOAD_KEY)
    if (raw === null) return null
    const value = Number(raw)
    return Number.isFinite(value) ? value : null
  } catch {
    return null
  }
}

/** 判定并登记一次「协议不兼容」刷新；返回 true 表示调用方应执行刷新。存储不可用时不刷新（无法限频）。 */
export function claimIncompatibleReload(now: number = Date.now()): boolean {
  if (!shouldReloadOnIncompatible(readStamp(), now)) return false
  try {
    sessionStorage.setItem(INCOMPATIBLE_RELOAD_KEY, String(now))
  } catch {
    return false
  }
  return true
}
