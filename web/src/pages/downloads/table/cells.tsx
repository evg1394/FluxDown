// 任务表 / 卡片共用的单元格与文案（移植 task_table.rs 的 status_label / status_detail / render_*_cell）。

import { LoaderCircle } from 'lucide-react'
import { cn } from '../../../lib/cn'
import { Icon } from '../../../ui'
import { percentLabel, sourceSite } from '../model/task'
import type { DownloadTaskView } from '../model/task'
import type { ViewDensity } from '../model/viewPrefs'
import { SegmentProgress } from './SegmentProgress'
import { KIND_ICON, kindLabel, statusDetail, statusLabel, STATUS_TEXT } from './text'
import type { Translate } from './text'

export function KindGlyph({ view, className }: { view: DownloadTaskView; className?: string }) {
  return view.metadataPending ? (
    <Icon icon={LoaderCircle} size="md" className={cn('animate-spin text-muted-foreground', className)} />
  ) : (
    <Icon icon={KIND_ICON[view.kind]} size="lg" className={cn('text-muted-foreground', className)} />
  )
}

export function FileCell({ t, view, density }: { t: Translate; view: DownloadTaskView; density: ViewDensity }) {
  const twoLine = density !== 'compact'
  const site = sourceSite(view)
  const category = kindLabel(t, view.kind)
  return (
    <div className={cn('flex min-w-0 flex-col justify-center', density === 'relaxed' && 'gap-[var(--fx-spacing-xs)]')}>
      <div
        className={cn('truncate text-sm', view.metadataPending ? 'text-muted-foreground' : 'text-foreground')}
        title={view.metadataPending ? undefined : view.name}
      >
        {view.metadataPending ? t('statusPreparing') : view.name}
      </div>
      {twoLine && !view.metadataPending ? (
        <div className="truncate text-xs text-text-tertiary">{site === '' ? category : `${category} · ${site}`}</div>
      ) : null}
    </div>
  )
}

export function StatusCell({ t, view, density }: { t: Translate; view: DownloadTaskView; density: ViewDensity }) {
  const twoLine = density !== 'compact'
  const detail = statusDetail(t, view)
  const failedError = view.state === 'failed' && view.errorMessage.trim() !== ''
  const tooltip = failedError ? view.errorMessage : twoLine ? undefined : (detail ?? undefined)
  return (
    <div className={cn('tabular flex min-w-0 flex-col justify-center text-xs', density === 'relaxed' && 'gap-[var(--fx-spacing-xs)]')} title={tooltip}>
      <div className={cn('truncate', STATUS_TEXT[view.state])}>{statusLabel(t, view)}</div>
      {twoLine && detail ? <div className="truncate text-text-tertiary">{detail}</div> : null}
    </div>
  )
}

/** 进度单元格：分段条 + 整数百分比；完成态整格留空。 */
export function ProgressCell({ view, barWidth }: { view: DownloadTaskView; barWidth: number }) {
  if (view.state === 'completed') return null
  return (
    <div className="flex min-w-0 items-center gap-2">
      <SegmentProgress runtime={view.runtime} progress={view.progress} state={view.state} width={barWidth} />
      <span className="tabular w-8 shrink-0 text-right text-xs text-muted-foreground">{percentLabel(view.progress)}</span>
    </div>
  )
}
