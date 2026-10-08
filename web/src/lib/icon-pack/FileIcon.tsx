// 文件图标（按偏好 `appearance.file_icon_pack`）：单色图标走 CSS mask + currentColor（随主题文字色），
// 彩色图标走 <img>。用户 SVG 只进 data URL，不内联进 DOM，脚本不会执行。

import type { CSSProperties } from 'react'
import { useTheme } from '../../theme'
import { cn } from '../cn'
import { iconVariant } from './pack'
import type { FileKind, PackIcon } from './pack'
import { resolveFileIcon } from './registry'
import { useIconPackState } from './useIconPackState'

export function PackIconGlyph({ icon, size, className }: { icon: PackIcon; size: string; className?: string }) {
  const { mode } = useTheme()
  const svg = iconVariant(icon, mode === 'dark')
  if (icon.mode === 'color') {
    return <img aria-hidden alt="" src={svg.dataUrl} draggable={false} className={cn('shrink-0', className)} style={{ width: size, height: size }} />
  }
  const mask = `url("${svg.dataUrl}") center / contain no-repeat`
  const style: CSSProperties = { width: size, height: size, mask, WebkitMask: mask, backgroundColor: 'currentColor' }
  return <span aria-hidden className={cn('inline-block shrink-0', className)} style={style} />
}

/** 任务文件图标；`name` 为原始文件名。 */
export function FileIcon({ name, kind, size, className }: { name: string; kind: FileKind; size: string; className?: string }) {
  const state = useIconPackState()
  const icon = resolveFileIcon(state, name.toLowerCase(), kind)
  return icon ? <PackIconGlyph icon={icon} size={size} className={className} /> : <span aria-hidden className="shrink-0" style={{ width: size, height: size }} />
}
