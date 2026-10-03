// 已配对设备（局域网直连）：列表 + 在线探测 + 解除配对 + 添加设备入口。

import { Plus, RefreshCw, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useT } from '../../../../i18n'
import { rpc } from '../../../../lib/rpc'
import type { LinkDeviceInfo } from '../../../../lib/rpc'
import { Badge, Button, Card, Icon, confirmDialog, toast } from '../../../../ui'
import { AddDeviceDialog } from './AddDeviceDialog'
import { accountErrorKey } from './errorText'

function PairedRow({ device, disabled }: { device: LinkDeviceInfo; disabled: boolean }) {
  const t = useT()
  const seen = device.lastSeenAt > 0 ? new Date(device.lastSeenAt * (device.lastSeenAt < 1e12 ? 1000 : 1)).toLocaleString() : ''
  const meta = [device.platform, device.defaultSaveDir, seen].filter((part): part is string => Boolean(part)).join(' · ')

  const remove = async () => {
    const ok = await confirmDialog({
      title: t('linkedDeviceRemoveTitle'),
      description: t('linkedDeviceRemoveDesc', { name: device.name }),
      intent: 'destructive',
      okLabel: t('linkedDeviceRemove'),
    })
    if (!ok) return
    try {
      await rpc.agent.link.remove({ fingerprint: device.fingerprint })
    } catch (error) {
      toast.key(accountErrorKey(error), 'error')
    }
  }

  return (
    <div className="flex items-center gap-2 px-3 py-2 coarse:min-h-touch">
      <span className={`size-2 shrink-0 rounded-full ${!disabled && device.online ? 'bg-success' : 'bg-text-tertiary/50'}`} aria-hidden />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <div className="flex min-w-0 items-center gap-2">
          <span className="min-w-0 truncate text-sm font-medium text-foreground">{device.name}</span>
          <Badge>{t('deviceLocalTag')}</Badge>
          <span className="text-xs text-muted-foreground">{t(disabled ? 'devicePresenceUnknown' : device.online ? 'deviceOnline' : 'deviceOffline')}</span>
        </div>
        {meta ? <div className="truncate text-xs text-muted-foreground">{meta}</div> : null}
      </div>
      <Button variant="ghost" iconOnly aria-label={t('linkedDeviceRemove')} title={t('linkedDeviceRemove')} className="text-muted-foreground hover:text-destructive" disabled={disabled} onClick={() => void remove()}>
        <Icon icon={Trash2} size="md" />
      </Button>
    </div>
  )
}

export function PairedDevicesCard({ devices, disabled }: { devices: readonly LinkDeviceInfo[]; disabled: boolean }) {
  const t = useT()
  const [adding, setAdding] = useState(false)
  const [refreshing, setRefreshing] = useState(false)

  const refresh = async () => {
    if (refreshing) return
    setRefreshing(true)
    try {
      await rpc.agent.link.refresh()
    } catch (error) {
      toast.key(accountErrorKey(error), 'error')
    } finally {
      setRefreshing(false)
    }
  }

  return (
    <section className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <div className="text-sm font-medium text-foreground">{t('linkedDevicesTitle')}</div>
          <div className="text-xs text-muted-foreground">{t('linkedDevicesDesc')}</div>
        </div>
        <div className="flex shrink-0 gap-2">
          <Button icon={RefreshCw} disabled={disabled} loading={refreshing} onClick={() => void refresh()}>
            {t('accountDevicesRetry')}
          </Button>
          <Button variant="primary" icon={Plus} disabled={disabled} onClick={() => setAdding(true)}>
            {t('addDeviceEntry')}
          </Button>
        </div>
      </div>
      <Card className="w-full overflow-hidden [&>*+*]:border-t [&>*+*]:border-hairline">
        {devices.length === 0 ? (
          <div className="flex justify-center p-4 text-xs text-muted-foreground">{t('linkedDevicesEmpty')}</div>
        ) : (
          devices.map((device) => <PairedRow key={device.fingerprint} device={device} disabled={disabled} />)
        )}
      </Card>
      {adding ? <AddDeviceDialog onClose={() => setAdding(false)} /> : null}
    </section>
  )
}
