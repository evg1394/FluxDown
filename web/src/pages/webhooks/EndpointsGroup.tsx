// 端点分组：列表（读取 daemon 配置 `webhook.endpoints`）+ 新增按钮。
// 行内「测试」结果只显示在发起的那一行；端点被保存或删除后作废（与 GPUI 一致）。

import { Webhook } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useT } from '../../i18n'
import { rpc, useConfigValue, useDaemon } from '../../lib/rpc'
import { Button, EmptyState, confirmDialog } from '../../ui'
import { EndpointDialog } from './EndpointDialog'
import { EndpointRow } from './EndpointRow'
import { ENDPOINTS_KEY, NO_DELIVERIES, parseEndpoints, removeEndpoint, selectDeliveries, setEndpointEnabled, testReport } from './endpoints'
import type { EndpointSpec, TestReport } from './endpoints'
import { WebhookGroup } from './WebhookGroup'
import type { RunWrite } from './write'

export function EndpointsGroup({ disabled, runWrite, onTryCloud }: { disabled: boolean; runWrite: RunWrite; onTryCloud: (() => void) | null }) {
  const t = useT()
  const raw = useConfigValue(ENDPOINTS_KEY)
  const endpoints = useMemo(() => parseEndpoints(raw), [raw])
  const deliveries = useDaemon(selectDeliveries, NO_DELIVERIES)

  // `null` = 关闭；`'new'` = 新增；否则编辑该端点。
  const [editing, setEditing] = useState<EndpointSpec | 'new' | null>(null)
  const [testingId, setTestingId] = useState<string | null>(null)
  const [testResult, setTestResult] = useState<(TestReport & { endpointId: string }) | null>(null)

  const dropTestResult = (endpointId: string) =>
    setTestResult((current) => (current?.endpointId === endpointId ? null : current))

  const test = async (endpoint: EndpointSpec) => {
    if (testingId !== null) return
    setTestingId(endpoint.id)
    setTestResult(null)
    try {
      const response = await rpc.daemon.webhook.test({ ...endpoint })
      setTestResult({ endpointId: endpoint.id, ...testReport(t, { response }) })
    } catch (error) {
      setTestResult({ endpointId: endpoint.id, ...testReport(t, { error }) })
    } finally {
      setTestingId(null)
    }
  }

  const remove = async (endpoint: EndpointSpec) => {
    const ok = await confirmDialog({
      title: t('webhookRowDeleteConfirm'),
      description: endpoint.name,
      intent: 'destructive',
      okLabel: t('webhookRowDelete'),
    })
    if (!ok) return
    dropTestResult(endpoint.id)
    await runWrite(() => removeEndpoint(endpoint.id))
  }

  return (
    <WebhookGroup subtitle={t('webhookSemantics')}>
      <div className="flex w-full flex-col gap-0.5">
        {endpoints.length === 0 ? (
          <EmptyState
            icon={Webhook}
            title={t('webhookEmptyTitle')}
            description={t('webhookEmptyDescSelfHosted')}
            action={
              onTryCloud ? (
                <button
                  type="button"
                  className="text-xs text-primary underline-offset-2 hover:underline focus-visible:underline focus-visible:outline-none"
                  onClick={onTryCloud}
                >
                  {t('webhookTryCloud')}
                </button>
              ) : undefined
            }
          />
        ) : null}
        {endpoints.map((endpoint) => (
          <EndpointRow
            key={endpoint.id}
            endpoint={endpoint}
            deliveries={deliveries}
            disabled={disabled}
            testing={testingId === endpoint.id}
            testBusy={testingId !== null}
            testResult={testResult?.endpointId === endpoint.id ? testResult : null}
            onToggle={(enabled) => void runWrite(() => setEndpointEnabled(endpoint.id, enabled))}
            onEdit={() => setEditing(endpoint)}
            onTest={() => void test(endpoint)}
            onDelete={() => void remove(endpoint)}
          />
        ))}
        <div className="flex w-full justify-end pt-2">
          <Button variant="primary" disabled={disabled} onClick={() => setEditing('new')}>
            {t('webhookAddEndpoint')}
          </Button>
        </div>
      </div>
      {editing !== null ? (
        <EndpointDialog
          key={editing === 'new' ? 'new' : editing.id}
          existing={editing === 'new' ? null : editing}
          runWrite={runWrite}
          onSaved={dropTestResult}
          onClose={() => setEditing(null)}
        />
      ) : null}
    </WebhookGroup>
  )
}
