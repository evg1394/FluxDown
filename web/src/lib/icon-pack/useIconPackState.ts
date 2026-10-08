import { useMemo } from 'react'
import { usePref } from '../rpc/hooks'
import { FILE_ICON_PACK_KEY, iconPackState, selectionOf } from './registry'
import type { IconPackState } from './registry'

/** 偏好 `appearance.file_icon_pack` → 当前图标包链（选择不变时复用）。 */
export function useIconPackState(): IconPackState {
  const selection = selectionOf(usePref<unknown>(FILE_ICON_PACK_KEY))
  return useMemo(() => iconPackState(selection), [selection])
}
