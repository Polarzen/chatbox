package dev.polarzen.chatbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

internal data class SharedBinding(val address: String, val room: String, val instance: String, val token: String, val owner: Boolean) {
    init {
        require(room.isNotBlank() && instance.isNotBlank() && token.isNotEmpty() && token.all { it.code in 33..126 })
    }
}

internal data class SharedPending(val id: String, val question: String, val draft: String = question)
internal data class SharedInvite(val code: String, val expiresAt: Long)

internal data class SharedLocal(
    val binding: SharedBinding? = null,
    val draft: String = "",
    val pending: SharedPending? = null,
    val invite: SharedInvite? = null,
    val revoked: Boolean = false
) {
    fun encode(): String? {
        if (revoked) return JSONObject().put("revoked", true).toString()
        val b = binding ?: return null
        val value = JSONObject().put("address", b.address).put("room", b.room).put("instance", b.instance)
            .put("token", b.token).put("owner", b.owner).put("draft", draft)
        pending?.let {
            val row = JSONObject().put("id", it.id).put("question", it.question)
            if (it.draft != it.question) row.put("draft", it.draft)
            value.put("pending", row)
        }
        invite?.let { value.put("invite", JSONObject().put("code", it.code).put("expiresAt", it.expiresAt)) }
        return value.toString()
    }

    companion object {
        fun decode(raw: String?, allowLoopback: Boolean): SharedLocal {
            if (raw == null) return SharedLocal()
            val value = JSONObject(raw)
            if (value.optBoolean("revoked")) return SharedLocal(revoked = true)
            return SharedLocal(
                binding = SharedBinding(sharedAddress(value.getString("address"), allowLoopback),
                    value.getString("room"), value.getString("instance"), value.getString("token"), value.getBoolean("owner")),
                draft = value.optString("draft"),
                pending = value.optJSONObject("pending")?.let {
                    val question = it.getString("question")
                    SharedPending(it.getString("id").also(UUID::fromString), question, it.optString("draft", question))
                },
                invite = value.optJSONObject("invite")?.let {
                    SharedInvite(it.getString("code"), it.getLong("expiresAt"))
                }
            )
        }
    }
}

/** Accessed from the composition's main-thread scope. Serialize HTTP reads and mutations so
 * a delayed page can never replace a newer snapshot or outlive its authorization. */
internal class SharedChatState(
    private val storage: SharedLocalStorage,
    private val allowLoopback: Boolean,
    private val decorateQuestion: (String) -> String = { it },
) {
    private val api = SharedChatTransport(allowLoopback)
    private val requests = Mutex()
    private var cursor = 0L
    val turns = mutableStateListOf<SharedTurn>()
    var local by mutableStateOf(SharedLocal())
        private set
    var unreadable by mutableStateOf(false)
        private set
    var saveFailed by mutableStateOf(false)
        private set
    var authorized by mutableStateOf(false)
        private set
    var verifiedInView by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var pendingMissing by mutableStateOf(false)
        private set
    var connection by mutableStateOf("正在核对共享记录…")
        private set
    var notice by mutableStateOf("")
        private set

    init {
        try {
            local = SharedLocal.decode(storage.load(), allowLoopback)
        } catch (_: Exception) {
            unreadable = true
        }
    }

    private fun save(next: SharedLocal): Boolean {
        local = next
        try {
            storage.save(next.encode())
            saveFailed = false
        } catch (_: Exception) {
            saveFailed = true
        }
        return !saveFailed
    }

    fun retrySave() { save(local) }

    fun editDraft(value: String) {
        if (local.binding != null && local.pending == null) save(local.copy(draft = value))
    }

    suspend fun connect(input: String, code: String, seed: SharedSeed?) = action {
        if (local.binding != null || unreadable || local.revoked) return@action
        val address = try {
            sharedAddress(input, allowLoopback)
        } catch (_: Exception) {
            notice = if (allowLoopback) "请输入完整的 HTTPS 地址，或本机 http://127.0.0.1 地址。"
                else "请输入证书受信任的完整 HTTPS 地址。"
            return@action
        }
        val binding = api.connect(address, code.trim(), seed)
        save(SharedLocal(binding = binding))
        connection = "正在核对共享记录…"
    }

    private suspend fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = ""
        try {
            requests.withLock { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!handleRevocation(e)) notice = friendlyError(e)
        } finally {
            busy = false
        }
    }

    private fun handleRevocation(e: Exception): Boolean {
        if (e !is SharedHttpFailure || e.status != 401 || local.binding == null) return false
        turns.clear()
        cursor = 0L
        authorized = false
        verifiedInView = false
        pendingMissing = false
        connection = ""
        notice = ""
        save(SharedLocal(revoked = true))
        return true
    }

    suspend fun refresh() {
        requests.withLock {
            val binding = local.binding ?: return
            try {
                synchronize(binding)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!handleRevocation(e)) {
                    authorized = false
                    connection = friendlyError(e)
                }
            }
        }
    }

    private suspend fun lookupPending(binding: SharedBinding, pending: SharedPending): Boolean {
        try {
            merge(api.turn(binding, pending.id))
            pendingMissing = false
            return true
        } catch (e: SharedHttpFailure) {
            if (e.status != 404) throw e
            pendingMissing = true
            return false
        }
    }

    private suspend fun synchronize(binding: SharedBinding) {
        local.pending?.let { lookupPending(binding, it) }
        // An in-progress row remains on the next poll, including when read by another device.
        var after = cursor
        var terminalPrefix = true
        do {
            val page = api.page(binding, after)
            verifiedInView = true
            page.turns.forEach { row ->
                merge(row)
                if (row.status == "running") terminalPrefix = false
                if (terminalPrefix) cursor = row.seq
                after = row.seq
            }
        } while (page.more)
        authorized = true
        connection = if (turns.any { it.status == "running" }) "正在同步回复…" else "已同步"
    }

    private fun merge(row: SharedTurn) {
        val index = turns.binarySearchBy(row.seq) { it.seq }
        if (index >= 0) {
            val old = turns[index]
            check(old.id == row.id)
            if (old.status == "running") turns[index] = row
        } else {
            turns.add(-index - 1, row)
        }
        if (local.pending?.id == row.id) {
            pendingMissing = false
            if (row.status != "running") {
                save(local.copy(pending = null))
                notice = if (row.status == "unknown") "服务未能确认回复是否完成。请先核对记录，避免重复发送。" else ""
            }
        }
    }

    suspend fun send() = action {
        val binding = local.binding ?: return@action
        if (!authorized || local.pending != null || local.draft.isBlank() || turns.any { it.status == "running" }) return@action
        val draft = local.draft
        if (blockSharedTask(draft)) return@action
        val question = if (draft.trimStart().startsWith("/")) draft else decorateQuestion(draft)
        val pending = SharedPending(UUID.randomUUID().toString(), question, draft)
        if (!save(local.copy(draft = "", pending = pending))) {
            // Nothing was posted. Keep the editable draft instead of an unresolvable pending id.
            save(local.copy(draft = draft, pending = null))
            notice = "本机未能保存发送编号，提问已保留。"
            return@action
        }
        submit(binding, pending, retry = false)
    }

    suspend fun retryPending(id: String) = action {
        val binding = local.binding ?: return@action
        val pending = local.pending?.takeIf { it.id == id } ?: return@action
        if (!pendingMissing || lookupPending(binding, pending)) return@action
        if (blockSharedTask(pending.question)) return@action
        // The first POST can still arrive after this 404. The bridge deduplicates by room and ID.
        submit(binding, pending, retry = true)
    }

    private fun blockSharedTask(text: String): Boolean {
        val command = text.trimStart()
        if (!command.startsWith("/task") || (command.length > 5 && !command[5].isWhitespace())) return false
        notice = "请在 Windows 客户端选择 Launcher 实例，登录 Dashboard 后，在该实例的本机会话中执行 /task。"
        return true
    }

    private suspend fun submit(binding: SharedBinding, pending: SharedPending, retry: Boolean) {
        pendingMissing = false
        try {
            api.send(binding, pending)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when {
                handleRevocation(e) -> return
                e is SharedHttpFailure && (e.status == 400 || e.status == 413) -> {
                    save(local.copy(draft = pending.draft, pending = null))
                    notice = if (e.status == 413) "内容超过服务限制，提问已恢复为草稿。请缩短后再发送。"
                        else "服务拒绝了提问内容，提问已恢复为草稿。请检查后再发送。"
                    return
                }
                e is SharedHttpFailure && e.status == 409 -> {
                    if (!retry) save(local.copy(draft = pending.draft, pending = null))
                    val conflict = when (e.conflict) {
                        SharedConflict.RUNNING -> "另一台设备正在生成回复，请稍后再发。"
                        SharedConflict.UNRESOLVED -> "上游执行结果未知，请服务管理员确认 AstrBot 已空闲并运行 resolve。"
                        SharedConflict.OTHER -> "服务报告发送冲突，请核对记录。"
                    }
                    notice = conflict + if (retry) "正在按原编号核对最初的提交。" else "提问已留在草稿中。"
                }
                else -> notice = "尚未确认这次发送的结果。正在按发送编号核对。"
            }
        }
        synchronize(binding)
    }

    suspend fun releasePending() = action {
        val pending = local.pending ?: return@action
        if (!pendingMissing) return@action
        save(local.copy(draft = pending.draft, pending = null))
        pendingMissing = false
        notice = "提问已恢复为草稿。再次发送前，请先核对两台设备的记录。"
    }

    suspend fun invite() = action {
        val binding = local.binding?.takeIf { it.owner } ?: return@action
        val reply = api.manage(binding, "invite")
        val seconds = reply.getLong("expiresIn")
        require(seconds > 0)
        save(local.copy(invite = SharedInvite(reply.getString("code"), System.currentTimeMillis() + seconds * 1000L)))
    }

    suspend fun cancelInvite() = action {
        val binding = local.binding?.takeIf { it.owner } ?: return@action
        api.manage(binding, "cancel-invite")
        save(local.copy(invite = null))
        notice = "已取消加入码。"
    }

    suspend fun revokeGuests() = action {
        val binding = local.binding?.takeIf { it.owner } ?: return@action
        api.manage(binding, "revoke-guests")
        notice = "已撤销其他设备的访问权限。"
    }

    suspend fun leave(onClose: () -> Unit) = action {
        // Keep the current binding if removal failed. The host can safely recheck it.
        try {
            storage.save(null)
        } catch (_: Exception) {
            notice = "未能移除本机绑定，请重试。"
            return@action
        }
        local = SharedLocal()
        turns.clear()
        cursor = 0L
        authorized = false
        verifiedInView = false
        pendingMissing = false
        unreadable = false
        saveFailed = false
        onClose()
    }
}

private fun friendlyError(e: Exception): String = when (val code = (e as? SharedHttpFailure)?.status) {
    400, 413, 422 -> "服务未接受提交的内容，请检查连接码、会话及内容大小。"
    401, 403 -> "授权失败，请检查服务地址。连接码可能无效、已过期或已使用。"
    404, 410 -> "找不到共享服务或连接码，连接码可能已使用或过期。"
    409 -> "服务当前有冲突，请核对会话状态后重试。"
    429 -> "请求过于频繁，请稍后再试。"
    in 300..399 -> "共享服务返回了重定向，请使用最终地址。"
    null -> "未能核对共享服务，请检查网络、地址和 HTTPS 证书。"
    else -> "共享服务返回 HTTP $code。"
}
