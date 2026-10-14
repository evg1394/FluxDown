// 带进出场动效的内联提示条（GPUI `fluxdown_ui_components::RevealCallout` 的 Web 对应）。
// - 出现 / 消失：grid 行高 0fr↔1fr 过渡展开 + 淡入 + 轻微下滑，首次挂载经 @starting-style 同样展开；
// - 语气切换（提醒 → 危险）：底色、描边、文字色过渡；
// - 每次出现或语气变化，警示图标做一次「放大回弹」；
// - 退场期间保留最后一次可见时的内容，避免淡出时文字跳成已不成立的值。
// 必须常驻渲染（`visible=false` 也挂着），退场动画才能播放；隐藏时高度为 0。

import { TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { cn } from '../lib/cn'
import { Icon } from './Icon'

export type CalloutTone = 'warning' | 'danger'

const TONE: Record<CalloutTone, string> = {
  warning: 'border-warning/30 bg-warning/8 text-warning',
  danger: 'border-destructive/30 bg-destructive/8 text-destructive',
}

interface Shown {
  tone: CalloutTone
  title: string
  body: string
  /** 每次由隐藏变为可见时递增，用来重播图标回弹。 */
  appearance: number
  visible: boolean
}

export function RevealCallout({
  visible,
  tone,
  title,
  body,
  className,
  contentClassName = 'pt-2',
}: {
  visible: boolean
  tone: CalloutTone
  title: string
  body: string
  className?: string
  /** 折叠区内的留白（随高度一起收起，隐藏时不占位）。 */
  contentClassName?: string
}) {
  const [shown, setShown] = useState<Shown>({ tone, title, body, appearance: 0, visible })
  if (visible !== shown.visible || (visible && (tone !== shown.tone || title !== shown.title || body !== shown.body))) {
    setShown(
      visible
        ? { tone, title, body, appearance: shown.visible ? shown.appearance : shown.appearance + 1, visible }
        : { ...shown, visible },
    )
  }

  return (
    <div
      aria-hidden={!visible}
      className={cn(
        'grid transition-[grid-template-rows,opacity] duration-300 ease-[cubic-bezier(0.16,1,0.3,1)] motion-reduce:transition-none',
        'starting:grid-rows-[0fr] starting:opacity-0',
        visible ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0',
        className,
      )}
    >
      <div className="min-h-0 overflow-hidden">
        <div className={contentClassName}>
          <div
            role="status"
            aria-live="polite"
            className={cn(
              'flex items-start gap-2 rounded-md border border-l-[3px] px-3 py-2',
              'transition-[color,background-color,border-color,translate] duration-300 ease-[cubic-bezier(0.16,1,0.3,1)] motion-reduce:transition-none',
              'starting:-translate-y-1.5',
              visible ? 'translate-y-0' : '-translate-y-1.5',
              TONE[shown.tone],
            )}
          >
            <span key={`${shown.tone}-${shown.appearance}`} className="flex size-[18px] shrink-0 items-center justify-center animate-fx-icon-pop">
              <Icon icon={TriangleAlert} size="md" />
            </span>
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              <div className="text-sm font-semibold">{shown.title}</div>
              <div className="text-xs text-muted-foreground">{shown.body}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
