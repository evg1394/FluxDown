import { describe, expect, test } from 'bun:test'
import type { FileExistsAction, SelectionRequestDto } from '../../../lib/rpc'
import { bulkActions, collectFileConflicts, conflictTaskIds, earliestDeadline, hasUndismissed } from './fileConflict'

function fileExists(requestId: string, taskId: string, actions: FileExistsAction[], deadlineUnixMs = 1000): SelectionRequestDto {
  return {
    requestId,
    taskId,
    kind: { type: 'fileExists', fileName: 'a.bin', saveDir: '/dl', renamePreview: 'a (1).bin', actions },
    defaultChoice: { kind: 'fileExists', action: 'rename' },
    deadlineUnixMs,
  }
}

const hls: SelectionRequestDto = {
  requestId: 'h',
  taskId: 't-h',
  kind: { type: 'hls', options: [] },
  defaultChoice: { kind: 'hls', index: 0 },
  deadlineUnixMs: 1,
}

describe('文件已存在聚合', () => {
  test('只收集 fileExists 请求并保持顺序，任务 id 去重', () => {
    const conflicts = collectFileConflicts([fileExists('r1', 't1', ['rename']), hls, fileExists('r2', 't1', ['rename'])])
    expect(conflicts.map((conflict) => conflict.requestId)).toEqual(['r1', 'r2'])
    expect([...conflictTaskIds(conflicts)]).toEqual(['t1'])
  })

  test('批量跳过仅当每一项都允许；ED2K / DASH 只给重命名与覆盖', () => {
    const all = collectFileConflicts([fileExists('r1', 't1', ['rename', 'overwrite', 'skip']), fileExists('r2', 't2', ['rename', 'overwrite', 'skip'])])
    expect(bulkActions(all)).toEqual(['rename', 'overwrite', 'skip'])
    const mixed = collectFileConflicts([fileExists('r1', 't1', ['rename', 'overwrite', 'skip']), fileExists('r2', 't2', ['rename', 'overwrite'])])
    expect(bulkActions(mixed)).toEqual(['rename', 'overwrite'])
  })

  test('倒计时取最早的超时时刻', () => {
    expect(earliestDeadline([])).toBeNull()
    const conflicts = collectFileConflicts([fileExists('r1', 't1', [], 5000), fileExists('r2', 't2', [], 3000)])
    expect(earliestDeadline(conflicts)).toBe(3000)
  })

  test('稍后决定：已隐藏的请求不再弹出，新请求 id 出现才重新弹出', () => {
    const first = collectFileConflicts([fileExists('r1', 't1', ['rename'])])
    expect(hasUndismissed(first, new Set(['r1']))).toBe(false)
    const withNew = collectFileConflicts([fileExists('r1', 't1', ['rename']), fileExists('r2', 't2', ['rename'])])
    expect(hasUndismissed(withNew, new Set(['r1']))).toBe(true)
    expect(hasUndismissed(withNew, new Set())).toBe(true)
  })
})
