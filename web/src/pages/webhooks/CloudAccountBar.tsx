// 云端推送页 · 账号条：登录态 / 隐私摘要 / 本设备上报开关 / 额度进度。
// Web 是 headless 形态，云账户能力在「设置 → 账户」；「登录」「升级套餐」都跳到那里。

import { useNavigate } from '@tanstack/react-router'
import { CloudOff, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { useT } from '../../i18n'
import { cn } from '../../lib/cn'
import { rpc } from '../../lib/rpc'
import type { AgentSessionDto, CloudNotifyOverviewDto, CloudNotifyStateDto } from '../../lib/rpc'
import { Button, Card, ConfirmFooter, Dialog, Icon, OptionGroup, OptionRow, ProgressBar, Switch, toast } from '../../ui'
import { formatShortTime, privacyFieldKeys, quotaStatus } from './cloudLogic'
import type { UsageMeter } from './cloudLogic'
import { cloudActionError } from './useCloudNotify'

function useGoAccount() {
  const navigate = useNavigate()
  return () => void navigate({ to: '/settings/$category', params: { category: 'account' } })
}

/** 未登录：说明 + 登录按钮。 */
export function CloudSignedOut() {
  const t = useT()
  const goAccount = useGoAccount()
  return (
    <Card className="flex w-full min-w-0 items-center gap-4 p-4 narrow:flex-col narrow:items-stretch">
      <div className="flex size-10 shrink-0 items-center justify-center rounded-full bg-nav-hover text-muted-foreground narrow:self-center">
        <Icon icon={CloudOff} size="lg" />
      </div>
      <div className="min-w-0 flex-1 narrow:text-center">
        <div className="text-sm font-semibold text-foreground">{t('cloudNotifySignedOutTitle')}</div>
        <div className="mt-0.5 text-xs text-muted-foreground">{t('cloudNotifySignedOutDesc')}</div>
      </div>
      <Button variant="primary" onClick={goAccount}>
        {t('cloudNotifySignIn')}
      </Button>
    </Card>
  )
}

function Meter({ label, meter, resetAt }: { label: string; meter: UsageMeter; resetAt: string }) {
  const t = useT()
  const reset = meter.unlimited ? '' : formatShortTime(resetAt)
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <div className="flex items-baseline justify-between gap-2 text-xs">
        <span className={cn('tabular', meter.exhausted ? 'font-medium text-destructive' : 'text-foreground')}>{label}</span>
        {reset ? <span className="truncate text-caption text-text-tertiary">{t('cloudNotifyUsageResetAt', { time: reset })}</span> : null}
      </div>
      <ProgressBar
        value={meter.unlimited ? 0 : meter.ratio}
        className={cn(meter.exhausted && '[&>div]:!bg-destructive')}
      />
    </div>
  )
}

function UsageBlock({ overview, onUpgrade }: { overview: CloudNotifyOverviewDto; onUpgrade: () => void }) {
  const t = useT()
  const status = quotaStatus(overview.usage)
  const unlimited = t('cloudNotifyUsageUnlimited')
  const limitText = (limit: number) => (limit === 0 ? unlimited : String(limit))
  const { usage } = overview
  return (
    <div className="flex w-full min-w-0 flex-col gap-2">
      <div className="grid grid-cols-2 gap-3 narrow:grid-cols-1">
        <Meter
          label={t('cloudNotifyUsageToday', { used: usage.dailyUsed, limit: limitText(usage.dailyLimit) })}
          meter={status.daily}
          resetAt={usage.dailyResetAt}
        />
        <Meter
          label={t('cloudNotifyUsageMonth', { used: usage.monthlyUsed, limit: limitText(usage.monthlyLimit) })}
          meter={status.monthly}
          resetAt={usage.monthlyResetAt}
        />
      </div>
      {status.exhausted ? (
        <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-destructive">
          <span>{t('cloudNotifyQuotaExhausted', { time: formatShortTime(status.recoverAt) })}</span>
          <Button variant="ghost" className="h-7 px-2 text-xs coarse:h-auto" onClick={onUpgrade}>
            {t('cloudNotifyUpgrade')}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

/** 首次开启上报的同意对话框。 */
function ConsentDialog({ onConfirm, onClose }: { onConfirm: () => void; onClose: () => void }) {
  const t = useT()
  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      size="sm"
      title={t('cloudNotifyConsentTitle')}
      description={t('cloudNotifyConsentDesc')}
      footer={<ConfirmFooter okLabel={t('cloudNotifyConsentConfirm')} onCancel={onClose} onOk={onConfirm} />}
    />
  )
}

/** 隐私设置：两个开关 + 说明。 */
function PrivacyDialog({ state, disabled, onClose }: { state: CloudNotifyStateDto; disabled: boolean; onClose: () => void }) {
  const t = useT()
  const update = async (next: { includeUrl: boolean; includeSaveDir: boolean }) => {
    try {
      await rpc.agent.cloudNotify.setPrivacy(next)
    } catch (error) {
      toast.error(cloudActionError(error, t, 0))
    }
  }
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()} size="sm" title={t('cloudNotifyPrivacyTitle')} description={t('cloudNotifyPrivacyDesc')}>
      <div className="pb-2">
        <OptionGroup>
          <OptionRow
            title={t('cloudNotifyIncludeUrl')}
            control={
              <Switch
                aria-label={t('cloudNotifyIncludeUrl')}
                disabled={disabled}
                checked={state.includeUrl}
                onCheckedChange={(checked) => void update({ includeUrl: checked, includeSaveDir: state.includeSaveDir })}
              />
            }
          />
          <OptionRow
            title={t('cloudNotifyIncludeSaveDir')}
            control={
              <Switch
                aria-label={t('cloudNotifyIncludeSaveDir')}
                disabled={disabled}
                checked={state.includeSaveDir}
                onCheckedChange={(checked) => void update({ includeUrl: state.includeUrl, includeSaveDir: checked })}
              />
            }
          />
        </OptionGroup>
      </div>
    </Dialog>
  )
}

export function CloudAccountBar({
  session,
  state,
  disabled,
}: {
  session: AgentSessionDto
  state: CloudNotifyStateDto
  disabled: boolean
}) {
  const t = useT()
  const goAccount = useGoAccount()
  const [consent, setConsent] = useState(false)
  const [privacy, setPrivacy] = useState(false)
  const [busy, setBusy] = useState(false)

  const overview = state.overview ?? null
  const email = overview?.accountEmail || session.user.email
  const planName = session.currentPlan?.name ?? session.user.plan
  const fields = privacyFieldKeys(state.includeUrl, state.includeSaveDir)
    .map((key) => t(key))
    .join(' · ')

  const setReporting = async (enabled: boolean) => {
    setBusy(true)
    try {
      await rpc.agent.cloudNotify.setReporting({ enabled })
    } catch (error) {
      toast.error(cloudActionError(error, t, overview?.maxChannels ?? 0))
    } finally {
      setBusy(false)
    }
  }

  const onToggle = (checked: boolean) => {
    if (checked) setConsent(true)
    else void setReporting(false)
  }

  return (
    <Card className="flex w-full min-w-0 flex-col gap-4 p-4">
      <div className="flex w-full min-w-0 items-start gap-4 narrow:flex-col">
        <div className="flex min-w-0 flex-1 items-start gap-3">
          <div
            aria-hidden
            className="flex size-10 shrink-0 items-center justify-center rounded-full bg-accent text-base font-semibold text-accent-text"
          >
            {(email.trim().charAt(0) || '?').toUpperCase()}
          </div>
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
              <span className="truncate text-sm font-semibold text-foreground">{email}</span>
              {planName ? <span className="truncate text-xs text-muted-foreground">{planName}</span> : null}
            </div>
            <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
              <Icon icon={ShieldCheck} size="sm" className="text-text-tertiary" />
              <span className="min-w-0 break-words">{t('cloudNotifyPrivacySummary', { fields })}</span>
              <button
                type="button"
                className="text-xs text-primary underline-offset-2 hover:underline focus-visible:underline focus-visible:outline-none"
                onClick={() => setPrivacy(true)}
              >
                {t('cloudNotifyPrivacySettings')}
              </button>
            </div>
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-3 narrow:w-full narrow:justify-between">
          <div className="flex min-w-0 flex-col">
            <span className="text-sm text-foreground">{t('cloudNotifyReportThisDevice')}</span>
          </div>
          <Switch
            aria-label={t('cloudNotifyReportThisDevice')}
            checked={state.reporting}
            disabled={disabled || busy}
            onCheckedChange={onToggle}
          />
        </div>
      </div>
      <div className="text-xs text-muted-foreground">{t('cloudNotifyReportDesc')}</div>
      {overview ? (
        overview.enabled ? (
          <UsageBlock overview={overview} onUpgrade={goAccount} />
        ) : (
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-warning/10 px-3 py-2 text-xs text-warning">
            <span>{t('cloudNotifyPlanDisabled')}</span>
            <Button variant="ghost" className="h-7 px-2 text-xs coarse:h-auto" onClick={goAccount}>
              {t('cloudNotifyUpgrade')}
            </Button>
          </div>
        )
      ) : null}
      {consent ? (
        <ConsentDialog
          onClose={() => setConsent(false)}
          onConfirm={() => {
            setConsent(false)
            void setReporting(true)
          }}
        />
      ) : null}
      {privacy ? <PrivacyDialog state={state} disabled={disabled} onClose={() => setPrivacy(false)} /> : null}
    </Card>
  )
}
