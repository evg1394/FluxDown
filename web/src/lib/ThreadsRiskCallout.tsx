// 线程数风险提示（GPUI settings `threads_risk_callout` / downloads `components/threads_risk.rs`）：
// 设置「最大连接数」、队列默认线程数、新建下载线程数共用。须常驻渲染以播放退场动画。

import { useT } from '../i18n'
import { RevealCallout } from '../ui'
import { HIGH_SEGMENTS_WARN_ABOVE, threadsRisk } from './threadsRisk'

export function ThreadsRiskCallout({ segments, className, contentClassName }: { segments: number; className?: string; contentClassName?: string }) {
  const t = useT()
  const risk = threadsRisk(segments)
  const severe = risk === 'danger'
  const params = { count: segments, limit: HIGH_SEGMENTS_WARN_ABOVE }
  return (
    <RevealCallout
      visible={risk !== 'none'}
      tone={severe ? 'danger' : 'warning'}
      title={t(severe ? 'threadsRiskSevereTitle' : 'threadsRiskTitle')}
      body={t(severe ? 'threadsRiskSevereDesc' : 'threadsRiskDesc', params)}
      {...(className ? { className } : {})}
      {...(contentClassName ? { contentClassName } : {})}
    />
  )
}
