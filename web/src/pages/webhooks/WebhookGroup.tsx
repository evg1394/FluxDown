// 页面内分组（GPUI `SettingsSection`）：标题 + 副标题 + 卡片承载内容。

import type { ReactNode } from 'react'
import { Card } from '../../ui'

export function WebhookGroup({ title, subtitle, children }: { title?: string; subtitle: string; children: ReactNode }) {
  return (
    <section className="flex w-full min-w-0 flex-col gap-2">
      <div className="flex flex-col gap-0.5 px-1">
        {title ? <h2 className="text-sm font-semibold text-foreground">{title}</h2> : null}
        <p className="text-xs text-muted-foreground">{subtitle}</p>
      </div>
      <Card className="w-full min-w-0 p-2">{children}</Card>
    </section>
  )
}
