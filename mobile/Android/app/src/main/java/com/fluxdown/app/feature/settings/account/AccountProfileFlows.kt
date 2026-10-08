package com.fluxdown.app.feature.settings.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fluxdown.core.host.HostException
import com.fluxdown.core.protocol.AccountErrorContext
import com.fluxdown.core.protocol.boolOrNull
import com.fluxdown.core.protocol.AccountRules
import com.fluxdown.core.protocol.AgentSessionDto
import com.fluxdown.core.protocol.ChangeEmailParams
import com.fluxdown.core.protocol.ChangePasswordParams
import com.fluxdown.core.protocol.CodeClock
import com.fluxdown.core.protocol.SendNewEmailCodeParams
import kotlinx.coroutines.delay

/*
 * 资料 / 安全编辑的状态机：Origin ID（一次性）、修改邮箱（原邮箱 → 新邮箱两步验证码）、修改 / 设置密码。
 * 镜像 Web `account/profileEditing.ts::OriginIdEditor`、`SecurityCard.tsx::EmailChangeDialog`、
 * `PasswordDialogs.tsx::ChangePasswordDialog`（同 iOS `AccountProfileFlows`）；纯规则在 [AccountRules]。
 */

private fun trimmed(value: String): String = AccountRules.trimmed(value)

// ───────────────────────────── 验证码记录 ─────────────────────────────

/**
 * 跨对话框实例记住已发出的验证码：关掉再打开对话框时恢复倒计时，不重复发码（云端 60s 限频、码仍有效）。
 * 键里带主机 + 账号（[AccountEnv.scope]），切换主机 / 账号后不会续用旧记录。仅在主线程访问。
 */
internal object SentCodeStore {
    private val records = HashMap<String, AccountRules.SentCode>()

    /** 修改邮箱：原邮箱验证码。 */
    fun emailOldKey(scope: String): String = "email-old:$scope"

    /** 修改 / 设置密码：绑定邮箱验证码。 */
    fun passwordKey(scope: String): String = "password:$scope"

    fun remember(key: String, ttlSeconds: Long, nowMs: Long) {
        records[key] = AccountRules.SentCode(sentAtMs = nowMs, ttlSeconds = ttlSeconds)
    }

    /** 取出仍可恢复的倒计时；已失效的顺手清掉。 */
    fun recall(key: String, nowMs: Long): CodeClock? {
        val record = records[key] ?: return null
        if (AccountRules.restore(record, nowMs) == null) {
            records.remove(key)
            return null
        }
        return CodeClock(record.ttlSeconds, record.sentAtMs)
    }

    fun forget(key: String) {
        records.remove(key)
    }
}

// ───────────────────────────── 乐观开关 ─────────────────────────────

/**
 * 开关的乐观显示：点按后立即显示目标值，请求成功后再保持一小段时间等状态推送追上（响应与事件不保证先后，
 * 立即放手会让开关先弹回旧值再跳到新值），失败立即回滚。状态推送到达（[settle]）也会放手。
 */
internal class OptimisticValues<K : Any, V : Any> {
    private val values = mutableStateMapOf<K, V>()
    private val tokens = HashMap<K, Int>()

    /** 只增不减：放手（[release]）不重置，旧提交的收尾永远不会误放新提交的乐观值。 */
    private var nextToken = 0

    fun value(key: K, actual: V): V = values[key] ?: actual

    fun isPending(key: K): Boolean = key in values

    /** @return 请求失败时的异常（已回滚）；成功为 null。 */
    suspend fun commit(key: K, value: V, holdMs: Long = 2000, work: suspend () -> Unit): HostException? {
        val token = ++nextToken
        tokens[key] = token
        values[key] = value
        try {
            work()
        } catch (e: HostException) {
            if (tokens[key] == token) release(key)
            return e
        } catch (c: kotlinx.coroutines.CancellationException) {
            if (tokens[key] == token) release(key)
            throw c
        }
        try {
            delay(holdMs)
        } finally {
            if (tokens[key] == token) release(key)
        }
        return null
    }

    /** 真实状态已推送到：放手。 */
    fun settle(key: K) = release(key)

    private fun release(key: K) {
        values.remove(key)
        tokens.remove(key)
    }
}

// ───────────────────────────── Origin ID ─────────────────────────────

internal enum class OriginIdCheck { Idle, Checking, Available, Taken, Invalid }

/**
 * 一个 `revision` 拥有全部异步结果（含随机建议与保存）：任何输入变化 / 失效都会让在途的检查 / 随机结果作废。
 * 入口严格要求权益 `originIdEdit == true` 且尚未改过（[AccountRules.canEditOriginId] / [acceptsSession]）。
 */
internal class OriginIdEditor(private val env: AccountEnv, val current: Long?) {
    var value by mutableStateOf("")
        private set
    var check by mutableStateOf(OriginIdCheck.Idle)
        private set
    var busy by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var confirmed by mutableStateOf(false)
        private set

    /** 失效 / 重置次数：界面据此重新安排可用性检查（断线重连后旧的检查结果不可信）。 */
    var epoch by mutableIntStateOf(0)
        private set

    private var revision = 0
    private var submittingId: Long? = null

    val parsed: Long? get() = AccountRules.parseOriginId(value)

    /** 必须：已勾选一次性修改确认、可用性检查通过、值合法且不同于当前值。 */
    val canSubmit: Boolean
        get() = !busy && confirmed && check == OriginIdCheck.Available && parsed != null && parsed != current

    /** 界面离开 / 会话变化 / 断线时让所有在途结果作废，并按当前输入重新安排检查。 */
    fun invalidate() {
        revision += 1
        busy = false
        submittingId = null
        confirmed = false
        epoch += 1
        check = initialCheck(value)
    }

    private fun initialCheck(text: String): OriginIdCheck {
        val parsedValue = AccountRules.parseOriginId(text)
        return when {
            trimmed(text).isEmpty() || parsedValue == current -> OriginIdCheck.Idle
            parsedValue == null -> OriginIdCheck.Invalid
            else -> OriginIdCheck.Checking
        }
    }

    fun onInput(newValue: String) {
        if (busy) return
        revision += 1
        value = newValue
        confirmed = false
        errorText = null
        check = initialCheck(newValue)
    }

    fun acknowledge(newValue: Boolean) {
        if (!busy) confirmed = newValue
    }

    /** 保存成功后会话里 `originIdChanged` 先于结果到达：只有「正在提交的那个 id」才允许会话已不可改。 */
    fun acceptsSession(session: AgentSessionDto): Boolean {
        if (session.entitlements["originIdEdit"].boolOrNull != true) return false
        if (!session.user.originIdChanged) return true
        return submittingId != null && submittingId == session.user.originId
    }

    /** 防抖后由界面调用：只检查仍处于 [OriginIdCheck.Checking] 的当前值。 */
    suspend fun runCheck() {
        val target = parsed
        if (busy || check != OriginIdCheck.Checking || target == null || target == current) return
        revision += 1
        val mine = revision
        errorText = null
        try {
            val result = env.api().checkOriginId(target)
            if (mine != revision) return
            check = when {
                result.available -> OriginIdCheck.Available
                result.reason == "invalid" -> OriginIdCheck.Invalid
                else -> OriginIdCheck.Taken
            }
        } catch (e: HostException) {
            if (mine != revision) return
            check = OriginIdCheck.Idle
            errorText = env.error(e)
        }
    }

    suspend fun roll() {
        if (busy) return
        revision += 1
        val mine = revision
        busy = true
        confirmed = false
        check = OriginIdCheck.Idle
        errorText = null
        try {
            val random = env.api().randomOriginId()
            if (mine != revision) return
            busy = false
            onInput(random.originId.toString())
        } catch (e: HostException) {
            if (mine != revision) return
            busy = false
            errorText = env.error(e)
        }
    }

    /** @return 保存成功。 */
    suspend fun submit(): Boolean {
        val target = parsed
        if (!canSubmit || target == null) return false
        revision += 1
        val mine = revision
        busy = true
        submittingId = target
        errorText = null
        try {
            env.api().changeOriginId(target)
            return mine == revision
        } catch (e: HostException) {
            if (mine != revision) return false
            busy = false
            submittingId = null
            check = OriginIdCheck.Idle
            confirmed = false
            errorText = env.error(e)
            return false
        }
    }
}

// ───────────────────────────── 修改邮箱 ─────────────────────────────

internal enum class EmailStep { Old, New }

internal enum class EmailInputError { None, Invalid, Same }

internal class EmailChangeFlow(private val env: AccountEnv, userId: String, val currentEmail: String) {
    private val oldKey = SentCodeStore.emailOldKey(env.scope(userId))

    var oldCode by mutableStateOf("")
    var newEmail by mutableStateOf("")
    var newCode by mutableStateOf("")

    var step by mutableStateOf(EmailStep.Old)
        private set
    var oldClock by mutableStateOf<CodeClock?>(null)
        private set
    var newClock by mutableStateOf<CodeClock?>(null)
        private set

    /** 新邮箱验证码发往的地址：改了地址后旧码与冷却不再适用。 */
    var sentEmail by mutableStateOf<String?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var submitting by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set

    private var started = false

    val email: String get() = trimmed(newEmail)

    val emailError: EmailInputError
        get() = when {
            email.isEmpty() -> EmailInputError.None
            !AccountRules.isValidEmail(email) -> EmailInputError.Invalid
            email.lowercase() == currentEmail.lowercase() -> EmailInputError.Same
            else -> EmailInputError.None
        }

    /** 当前新邮箱对应的验证码倒计时。 */
    val activeNewClock: CodeClock? get() = if (sentEmail == email) newClock else null

    val canNext: Boolean
        get() = !sending && oldClock != null && trimmed(oldCode).isNotEmpty() && email.isNotEmpty() && emailError == EmailInputError.None

    val canConfirm: Boolean get() = canNext && activeNewClock != null && trimmed(newCode).isNotEmpty()

    /** 打开时：此前发出的原邮箱验证码仍在有效期 / 冷却内则恢复倒计时，否则自动发码。 */
    suspend fun start() {
        if (started) return
        started = true
        SentCodeStore.recall(oldKey, env.nowMs())?.let {
            oldClock = it
            return
        }
        sendOldCode(force = true)
    }

    suspend fun sendOldCode(force: Boolean = false) {
        if (sending || submitting) return
        val clock = oldClock
        if (!force && clock != null && clock.cooldown(env.nowMs()) > 0) return
        sending = true
        errorText = null
        try {
            val ttl = env.api().sendEmailCode()
            val now = env.nowMs()
            SentCodeStore.remember(oldKey, ttl.ttlSeconds, now)
            oldClock = CodeClock(ttl.ttlSeconds, now)
            oldCode = ""
            newCode = ""
        } catch (e: HostException) {
            errorText = env.error(e, AccountErrorContext.Code)
        } finally {
            sending = false
        }
    }

    /** 第一步主按钮：新邮箱已发过且仍有效就直接进入第二步，否则向新邮箱发码（锁表单）。 */
    suspend fun advance() {
        if (!canNext || submitting) return
        val clock = activeNewClock
        if (clock != null && clock.remaining(env.nowMs()) > 0) {
            step = EmailStep.New
            errorText = null
            return
        }
        sendNewCode(locking = true)
    }

    /** 向新邮箱发码；第一步走 [locking]（锁表单，主按钮转圈），第二步「重新发送」只让发码按钮转圈。 */
    suspend fun sendNewCode(locking: Boolean) {
        if (!canNext || sending || submitting) return
        val target = email
        if (locking) submitting = true else sending = true
        errorText = null
        try {
            val ttl = env.api().sendNewEmailCode(SendNewEmailCodeParams(target, trimmed(oldCode)))
            newClock = CodeClock(ttl.ttlSeconds, env.nowMs())
            sentEmail = target
            newCode = ""
            step = EmailStep.New
        } catch (e: HostException) {
            errorText = env.error(e, AccountErrorContext.Code)
        } finally {
            submitting = false
            sending = false
        }
    }

    fun back() {
        step = EmailStep.Old
        newCode = ""
        errorText = null
    }

    /** @return 修改成功。 */
    suspend fun confirm(): Boolean {
        if (!canConfirm || submitting) return false
        submitting = true
        errorText = null
        try {
            env.api().changeEmail(ChangeEmailParams(email, trimmed(oldCode), trimmed(newCode)))
            SentCodeStore.forget(oldKey)
            return true
        } catch (e: HostException) {
            errorText = env.error(e, AccountErrorContext.Code)
            return false
        } finally {
            submitting = false
        }
    }
}

// ───────────────────────────── 修改 / 设置密码 ─────────────────────────────

internal enum class PasswordMode { Password, Code }

internal class PasswordChangeFlow(
    private val env: AccountEnv,
    userId: String,
    val email: String,
    /** `null`（旧云端未知）按已设置处理。 */
    val hasPassword: Boolean?,
) {
    private val key = SentCodeStore.passwordKey(env.scope(userId))

    var mode by mutableStateOf(if (hasPassword == false) PasswordMode.Code else PasswordMode.Password)
        private set
    var current by mutableStateOf("")
    var code by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var confirm by mutableStateOf("")

    var clock by mutableStateOf<CodeClock?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var submitting by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set

    private var restored = false

    val canUsePassword: Boolean get() = hasPassword != false
    val byCode: Boolean get() = mode == PasswordMode.Code

    val ruleKey: String?
        get() = AccountRules.newPasswordErrorKey(newPassword, confirm, current = if (byCode) null else current)

    /** 规则提示只在用户已输入且（已填确认或已太短）时出现。 */
    val visibleRuleKey: String?
        get() {
            val rule = ruleKey
            if (rule == null || newPassword.isEmpty()) return null
            return if (confirm.isNotEmpty() || rule == "accountErrorPasswordTooShort") rule else null
        }

    val canConfirm: Boolean
        get() {
            if (sending || submitting || ruleKey != null) return false
            return if (byCode) clock != null && trimmed(code).isNotEmpty() else current.isNotEmpty()
        }

    fun switchMode(next: PasswordMode) {
        if (submitting) return
        mode = next
        errorText = null
    }

    /** 重开对话框时恢复此前发出的验证码倒计时。 */
    fun restoreIfNeeded() {
        if (restored) return
        restored = true
        clock = SentCodeStore.recall(key, env.nowMs())
    }

    suspend fun sendCode() {
        if (sending || submitting) return
        sending = true
        errorText = null
        try {
            val ttl = env.api().sendPasswordCode()
            val now = env.nowMs()
            SentCodeStore.remember(key, ttl.ttlSeconds, now)
            clock = CodeClock(ttl.ttlSeconds, now)
            code = ""
        } catch (e: HostException) {
            errorText = env.error(e, AccountErrorContext.Code)
        } finally {
            sending = false
        }
    }

    /** @return 修改成功（其它设备已退出）。 */
    suspend fun submit(): Boolean {
        if (!canConfirm) return false
        submitting = true
        errorText = null
        val params = if (byCode) {
            ChangePasswordParams(newPassword, code = trimmed(code))
        } else {
            ChangePasswordParams(newPassword, currentPassword = current)
        }
        try {
            env.api().changePassword(params)
            SentCodeStore.forget(key)
            return true
        } catch (e: HostException) {
            errorText = env.error(e, AccountErrorContext.Code)
            return false
        } finally {
            submitting = false
        }
    }
}
