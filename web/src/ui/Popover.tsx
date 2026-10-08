// 轻量 Popover（项目未引入 @radix-ui/react-popover，不新增依赖）：
// 触发器旁定位、视口内夹取/上下翻转、点外部/Esc 关闭、关闭后焦点回到触发器。
// 移动端（<=820px）默认改为底部 Sheet，以获得可触达的大面积面板。

import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { cn } from '../lib/cn'
import { useIsMobile } from './hooks'
import { Sheet } from './Sheet'

export type PopoverAlign = 'start' | 'center' | 'end'

const MARGIN = 8
const GAP = 4

export function Popover({
  trigger,
  children,
  title,
  side = 'bottom',
  align = 'start',
  open: controlledOpen,
  onOpenChange,
  sheetOnMobile = true,
  className,
}: {
  /** 触发器元素；点击切换开合。 */
  trigger: ReactNode
  children: ReactNode | ((close: () => void) => ReactNode)
  /** 移动端 Sheet 标题（无障碍必需）。 */
  title: string
  /** 面板在触发器的哪一侧：`bottom` 放下方（放不下翻到上方）；`right` 放右侧，`align` 指纵向对齐。 */
  side?: 'bottom' | 'right'
  align?: PopoverAlign
  open?: boolean
  onOpenChange?: (open: boolean) => void
  sheetOnMobile?: boolean
  className?: string
}) {
  const mobile = useIsMobile()
  const [innerOpen, setInnerOpen] = useState(false)
  const open = controlledOpen ?? innerOpen
  const setOpen = useCallback(
    (next: boolean) => {
      setInnerOpen(next)
      onOpenChange?.(next)
    },
    [onOpenChange],
  )
  const anchorRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null)
  const id = useId()
  const close = useCallback(() => setOpen(false), [setOpen])
  const asSheet = mobile && sheetOnMobile

  useLayoutEffect(() => {
    if (!open || asSheet) return
    const anchor = anchorRef.current?.getBoundingClientRect()
    const panel = panelRef.current?.getBoundingClientRect()
    if (!anchor || !panel) return
    const clampLeft = (value: number) => Math.min(Math.max(value, MARGIN), window.innerWidth - panel.width - MARGIN)
    if (side === 'right') {
      const top = align === 'start' ? anchor.top : align === 'end' ? anchor.bottom - panel.height : anchor.top + (anchor.height - panel.height) / 2
      setPosition({ top: Math.min(Math.max(top, MARGIN), window.innerHeight - panel.height - MARGIN), left: clampLeft(anchor.right + GAP) })
      return
    }
    const left = clampLeft(align === 'start' ? anchor.left : align === 'end' ? anchor.right - panel.width : anchor.left + (anchor.width - panel.width) / 2)
    const below = anchor.bottom + GAP
    const fitsBelow = below + panel.height <= window.innerHeight - MARGIN
    const top = fitsBelow ? below : Math.max(MARGIN, anchor.top - GAP - panel.height)
    setPosition({ top, left })
  }, [open, asSheet, side, align])

  useEffect(() => {
    if (!open || asSheet) return
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node
      if (panelRef.current?.contains(target) || anchorRef.current?.contains(target)) return
      close()
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close()
        anchorRef.current?.querySelector<HTMLElement>('button, [tabindex]')?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('keydown', onKey)
    window.addEventListener('resize', close)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('resize', close)
    }
  }, [open, asSheet, close])

  useEffect(() => {
    if (open && !asSheet) panelRef.current?.focus()
  }, [open, asSheet])

  const body = typeof children === 'function' ? children(close) : children
  return (
    <>
      <div
        ref={anchorRef}
        className="inline-flex"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen(!open)}
      >
        {trigger}
      </div>
      {open && asSheet ? (
        <Sheet open onOpenChange={setOpen} side="bottom" title={title} bodyClassName="p-4">
          {body}
        </Sheet>
      ) : null}
      {open && !asSheet
        ? createPortal(
            <div
              ref={panelRef}
              id={id}
              role="dialog"
              aria-label={title}
              tabIndex={-1}
              style={{ top: position?.top ?? 0, left: position?.left ?? 0, visibility: position ? 'visible' : 'hidden' }}
              className={cn(
                'animate-fx-pop fixed z-50 max-h-[calc(100dvh-16px)] max-w-[calc(100vw-16px)] overflow-y-auto rounded-md border border-hairline bg-surface text-surface-foreground shadow-md outline-none',
                className,
              )}
            >
              {body}
            </div>,
            document.body,
          )
        : null}
    </>
  )
}
