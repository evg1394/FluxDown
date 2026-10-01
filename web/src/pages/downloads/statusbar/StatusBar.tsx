import { ArrowDown, ArrowUp, HardDrive, Pause, Play } from 'lucide-react'
import { useT } from '../../../i18n'
import { Icon, Tooltip } from '../../../ui'
import { pauseAll, resumeAll } from '../model/actions'
import { formatBytes } from '../model/task'
import { useDownloads } from '../state'
import { ProxyControl } from './ProxyControl'
import { SpeedLimitControl } from './SpeedLimitControl'

const ACTION =
  'inline-flex h-status-control min-w-status-control shrink-0 items-center justify-center rounded-sm px-1 text-muted-foreground hover:bg-nav-hover hover:text-foreground disabled:pointer-events-none disabled:opacity-50 coarse:min-h-touch coarse:min-w-touch'

function IconCell({ icon, text, className }: { icon: typeof ArrowDown; text: string; className?: string }) {
  return (
    <span className={`inline-flex shrink-0 items-center gap-0.5 whitespace-nowrap ${className ?? ''}`}>
      <Icon icon={icon} />
      {text}
    </span>
  )
}

/** 状态栏：左 = 全局速度 + 全部暂停 / 全部开始；右 = 下行 / 上行限速、代理模式、剩余空间。 */
export function StatusBar() {
  const t = useT()
  const { connected, runtimeStats } = useDownloads()
  const down = `${formatBytes(Math.max(0, runtimeStats.totalDownloadBps))}/s`
  const up = `${formatBytes(Math.max(0, runtimeStats.totalUploadBps))}/s`
  const disabled = !connected
  return (
    <div className="flex h-status-bar shrink-0 items-center justify-between gap-3 overflow-x-auto border-t border-hairline bg-chrome px-2 text-caption tabular-nums text-muted-foreground mobile:pb-safe coarse:h-auto coarse:min-h-touch narrow:gap-2 [scrollbar-width:none]">
      <div className="flex min-w-0 shrink-0 items-center gap-3 narrow:gap-2">
        <IconCell icon={ArrowDown} text={down} />
        <IconCell icon={ArrowUp} text={up} />
        <div className="flex items-center gap-0.5">
          <Tooltip content={t('pauseAll')} side="top">
            <button type="button" aria-label={t('pauseAll')} disabled={disabled} onClick={() => void pauseAll()} className={ACTION}>
              <Icon icon={Pause} />
            </button>
          </Tooltip>
          <Tooltip content={t('resumeAll')} side="top">
            <button type="button" aria-label={t('resumeAll')} disabled={disabled} onClick={() => void resumeAll()} className={ACTION}>
              <Icon icon={Play} />
            </button>
          </Tooltip>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <SpeedLimitControl icon={ArrowDown} titleKey="speedLimitTitle" configKey="speed_limit_bytes" disabled={disabled} />
        <SpeedLimitControl icon={ArrowUp} titleKey="uploadLimit" configKey="upload_limit_bytes" disabled={disabled} />
        <ProxyControl disabled={disabled} />
        {runtimeStats.diskFreeBytes != null && (
          <IconCell icon={HardDrive} className="px-1 narrow:hidden" text={t('diskSpaceFreeLabel', { size: formatBytes(runtimeStats.diskFreeBytes) })} />
        )}
      </div>
    </div>
  )
}
