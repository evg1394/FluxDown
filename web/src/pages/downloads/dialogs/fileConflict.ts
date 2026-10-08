// 「文件已存在」待选的聚合模型：把 `pendingSelections` 里所有 `fileExists` 请求归为一组，
// 并维护「稍后决定」的本地隐藏状态（关闭对话框不发 RPC，请求照常等待超时）。

import { useMemo, useSyncExternalStore } from 'react'
import { useDaemon } from '../../../lib/rpc'
import type { FileExistsAction, SelectionKind, SelectionRequestDto } from '../../../lib/rpc'

export type FileExistsKind = Extract<SelectionKind, { type: 'fileExists' }>

export interface FileConflict {
  requestId: string
  taskId: string
  kind: FileExistsKind
  deadlineUnixMs: number
}

/** 提取 `fileExists` 请求，保持快照顺序。 */
export function collectFileConflicts(selections: readonly SelectionRequestDto[]): FileConflict[] {
  const out: FileConflict[] = []
  for (const request of selections) {
    if (request.kind.type === 'fileExists') {
      out.push({ requestId: request.requestId, taskId: request.taskId, kind: request.kind, deadlineUnixMs: request.deadlineUnixMs })
    }
  }
  return out
}

/** 有待确认冲突的任务 id。 */
export function conflictTaskIds(conflicts: readonly FileConflict[]): ReadonlySet<string> {
  return new Set(conflicts.map((conflict) => conflict.taskId))
}

/** 批量按钮可用的动作：重命名 / 覆盖恒可用，跳过仅当每一项都允许。 */
export function bulkActions(conflicts: readonly FileConflict[]): FileExistsAction[] {
  const actions: FileExistsAction[] = ['rename', 'overwrite']
  if (conflicts.length > 0 && conflicts.every((conflict) => conflict.kind.actions.includes('skip'))) actions.push('skip')
  return actions
}

/** 最早的超时时刻（服务端到点按默认「重命名」）；无请求为 null。 */
export function earliestDeadline(conflicts: readonly FileConflict[]): number | null {
  let min: number | null = null
  for (const conflict of conflicts) {
    if (min === null || conflict.deadlineUnixMs < min) min = conflict.deadlineUnixMs
  }
  return min
}

/** 对话框是否应显示：存在尚未被「稍后决定」隐藏的请求（新请求 id 出现即重新弹出）。 */
export function hasUndismissed(conflicts: readonly FileConflict[], dismissed: ReadonlySet<string>): boolean {
  return conflicts.some((conflict) => !dismissed.has(conflict.requestId))
}

// ── 本地隐藏状态 ──

let dismissed: ReadonlySet<string> = new Set()
const listeners = new Set<() => void>()

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function publish(next: ReadonlySet<string>): void {
  dismissed = next
  for (const listener of listeners) listener()
}

/** 「稍后决定」：隐藏当前全部请求；顺带丢弃已不存在的旧 id。 */
export function dismissFileConflicts(conflicts: readonly FileConflict[]): void {
  publish(new Set(conflicts.map((conflict) => conflict.requestId)))
}

/** 点任务行角标：重新打开对话框。 */
export function reopenFileConflicts(): void {
  if (dismissed.size > 0) publish(new Set())
}

export function useDismissedFileConflicts(): ReadonlySet<string> {
  return useSyncExternalStore(subscribe, () => dismissed)
}

const EMPTY_SELECTIONS: readonly SelectionRequestDto[] = []

/** 所有待确认的 `fileExists` 请求（`pendingSelections` 变化时才重算）。 */
export function useFileConflicts(): readonly FileConflict[] {
  const selections = useDaemon((daemon) => daemon.pendingSelections, EMPTY_SELECTIONS)
  return useMemo(() => collectFileConflicts(selections), [selections])
}

/** 任务列表用：有待确认冲突的任务 id 集合。 */
export function useConflictTaskIds(): ReadonlySet<string> {
  const conflicts = useFileConflicts()
  return useMemo(() => conflictTaskIds(conflicts), [conflicts])
}
