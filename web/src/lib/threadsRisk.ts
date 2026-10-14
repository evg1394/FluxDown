// 线程数上限与风险档位（镜像 `fluxdown_protocol` 的 MAX_TASK_SEGMENTS /
// HIGH_SEGMENTS_WARN_ABOVE / SEVERE_SEGMENTS_WARN_ABOVE，改一处须同步另一处）。

/** 单任务连接（线程）数上限：设置、队列默认值与新建任务共用。 */
export const MAX_TASK_SEGMENTS = 512
/** 超过该值提示可能触发服务器限速或安全风控封禁 IP。 */
export const HIGH_SEGMENTS_WARN_ABOVE = 64
/** 超过该值提示升级为高风险档。 */
export const SEVERE_SEGMENTS_WARN_ABOVE = 256

export type ThreadsRisk = 'none' | 'warning' | 'danger'

export function threadsRisk(segments: number): ThreadsRisk {
  if (segments > SEVERE_SEGMENTS_WARN_ABOVE) return 'danger'
  if (segments > HIGH_SEGMENTS_WARN_ABOVE) return 'warning'
  return 'none'
}
