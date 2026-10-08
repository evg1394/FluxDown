package com.fluxdown.core.model

/**
 * `file_exists_behavior = ask` 的待选请求聚合规则（纯函数，与 GPUI / iOS 的聚合对话框语义一致）：
 * 所有待选 fileExists 合并成一个对话框；「稍后决定」只是把当前这批请求隐藏，不向主机答复。
 */
object FileConflicts {
    /** 待选的 fileExists 请求（保持主机给出的顺序）。 */
    fun pending(selections: List<SelectionRequest>): List<SelectionRequest> =
        selections.filter { it.kind is SelectionKind.FileExists }

    /** 有待选 fileExists 的任务 id（任务行「待确认」角标）。 */
    fun taskIds(selections: List<SelectionRequest>): Set<String> {
        val ids = HashSet<String>()
        for (r in selections) if (r.kind is SelectionKind.FileExists) ids.add(r.taskId)
        return ids
    }

    /**
     * 是否弹出对话框：存在没被「稍后决定」过的请求（新请求到达会重新弹出，并连同已延后的一起显示）。
     * [deferred] 是用户上次延后时的请求 id 集合。
     */
    fun shouldShow(pending: List<SelectionRequest>, deferred: Set<String>): Boolean =
        pending.any { it.requestId !in deferred }

    /** [action] 在该请求里是否可选（重命名恒可用；跳过 / 覆盖只在主机列出时可用）。 */
    fun allows(request: SelectionRequest, action: FileExistsAction): Boolean {
        val kind = request.kind as? SelectionKind.FileExists ?: return false
        return action == FileExistsAction.Rename || action in kind.actions
    }

    /** 批量动作按钮是否出现：必须**每一条**待选都允许该动作（「全部跳过」不会强加给不支持跳过的请求；同 Web / GPUI / iOS）。 */
    fun canBulk(pending: List<SelectionRequest>, action: FileExistsAction): Boolean =
        pending.isNotEmpty() && pending.all { allows(it, action) }

    /** 最早到期的请求截止时间（对话框页脚倒计时）；没有请求为 null。 */
    fun nearestDeadline(pending: List<SelectionRequest>): Long? = pending.minOfOrNull { it.deadlineUnixMs }
}
