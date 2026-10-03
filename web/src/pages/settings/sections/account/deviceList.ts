import type { CloudDevice } from '../../../../lib/rpc'

// 与 crates/account/src/device_list.rs::sorted 保持一致；不修改快照数组。
export function sortedDevices(devices: readonly CloudDevice[], presenceKnown: boolean): CloudDevice[] {
  return [...devices].sort((a, b) => {
    const priority = Number(b.isCurrent) - Number(a.isCurrent)
      || Number(presenceKnown && b.isOnline) - Number(presenceKnown && a.isOnline)
    if (priority !== 0) return priority
    const left = a.name.toLowerCase()
    const right = b.name.toLowerCase()
    return left < right ? -1 : left > right ? 1 : 0
  })
}
