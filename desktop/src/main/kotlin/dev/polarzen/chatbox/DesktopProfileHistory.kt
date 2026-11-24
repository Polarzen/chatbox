package dev.polarzen.chatbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal data class DesktopHistoryPosition(val prefix: Int, val after: Long, val pending: SharedPending?)

private data class ProfileOwner(val source: String, val binding: ProfileBinding)

/** Entered from the native profile's ordinary composer and its profile authorization panel. */
internal class DesktopProfileHistory(private val store: DesktopStore, private val profileId: String) {
    private val api = SharedProfileTransport(allowLoopback = true)
    private val entropy = MessageDigest.getInstance("SHA-256").digest(
        JSONArray(listOf("desktop-profile-owner-v1", profileId, store.username)).toString().toByteArray(Charsets.UTF_8)
    )
    private var saved = store.profileAuthorization(profileId)
    private var owner: ProfileOwner? = null
    private var ownerAwaitingSave = false
    private var reasons by mutableStateOf<Map<String, String>>(emptyMap())
    private var pendingSessions by mutableStateOf<Set<String>>(emptySet())
    private var connectionIssue by mutableStateOf("正在核对档案授权与原会话。")

    var bound by mutableStateOf(saved != null)
        private set
    var needsRecovery by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set
    var revision by mutableStateOf(0L)
        private set
    var devices by mutableStateOf<List<ProfileDevice>>(emptyList())
        private set
    var invite by mutableStateOf<ProfileInvite?>(null)
        private set

    fun reason(sid: String): String = connectionIssue.ifEmpty { reasons[sid] ?: "正在核对原会话。" }
    fun hasPending(sid: String): Boolean = sid in pendingSessions

    private fun source(): String {
        val profile = checkNotNull(store.profile(profileId)) { "找不到本机档案。" }
        check(!profile.offlineOnly && profile.kind != "shared") { "离线档案无法授权。" }
        return DesktopStore.normalizeAddress(sharedAddress(profile.address, allowLoopback = true))
    }

    private fun sameOrigin(original: String, current: String): Boolean {
        fun launcherLoopback(base: String): Boolean = URI(base).let {
            it.scheme == "http" && it.host == "127.0.0.1" && it.rawPath.isNullOrEmpty()
        }
        return original == current || profileId.startsWith("launcher:") && launcherLoopback(original) && launcherLoopback(current)
    }

    private fun persistOwner(value: ProfileOwner) {
        val b = value.binding
        val raw = JSONObject().put("source", value.source).put("address", b.address).put("profile", b.profile)
            .put("instance", b.instance).put("token", b.token).put("name", b.name).toString().toByteArray(Charsets.UTF_8)
        val ciphertext = try { WindowsDpapi.transform(entropy, raw, protect = true) } finally { raw.fill(0) }
        val record = Base64.getEncoder().encodeToString(ciphertext)
        store.saveProfileAuthorization(profileId, saved, record, b.profile)
        saved = record
        ownerAwaitingSave = false
    }

    private fun authorization(): ProfileOwner {
        check(!needsRecovery) { connectionIssue }
        val existing = owner ?: try {
            val bytes = WindowsDpapi.transform(entropy, Base64.getDecoder().decode(checkNotNull(saved)), protect = false)
            val value = try { JSONObject(bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
            val binding = ProfileBinding(sharedAddress(value.getString("address"), true), value.getString("profile"),
                value.getString("instance"), value.getString("token"), true, value.getString("name"))
            ProfileOwner(value.getString("source"), binding).also { owner = it }
        } catch (e: Exception) {
            requireRecovery("无法读取本机保存的档案主人凭据。")
            throw IllegalStateException(connectionIssue, e)
        }
        check(sameOrigin(existing.source, source())) { "此档案的服务地址与授权来源不同，无法同步或发送。" }
        if (ownerAwaitingSave) persistOwner(existing)
        return existing
    }

    private fun requireRecovery(problem: String) {
        needsRecovery = true
        devices = emptyList()
        invite = null
        connectionIssue = problem + "请在原共享服务主机使用 profiles 查看档案编号，再用 profile-recover 签发主人恢复码。"
        notice = connectionIssue
    }

    private suspend fun action(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        store.profileRequests.withLock {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = message(e)
                if (e is SharedHttpFailure && e.status == 401 && bound && !needsRecovery) requireRecovery("档案主人授权已失效。")
            }
        }
    }

    suspend fun authorize(address: String, code: String, verifiedSourceBase: String, name: String) = action {
        check(!bound && store.profileAuthorization(profileId) == null) { "此档案已经授权。" }
        exchangeOwner(address, code, verifiedSourceBase, name)
    }

    suspend fun recover(address: String, code: String, verifiedSourceBase: String, name: String) = action {
        check(bound && needsRecovery && store.profileAuthorization(profileId) == saved) { "当前档案未处于凭据恢复状态。" }
        exchangeOwner(address, code, verifiedSourceBase, name)
    }

    private suspend fun exchangeOwner(address: String, code: String, verifiedSourceBase: String, name: String) {
        val base = source()
        check(DesktopStore.normalizeAddress(verifiedSourceBase) == base) { "当前服务地址尚未验证。" }
        val previous = owner
        check(previous == null || sameOrigin(previous.source, base)) { "此服务地址与原档案来源不同。" }
        val expected = previous?.binding?.profile ?: store.recordedRemoteProfile(profileId)
        val binding = try {
            api.create(address, code.trim(), base, store.username, profileId, name)
        } catch (e: SharedHttpFailure) {
            if (e.status == 409) error("请核对原档案来源。如已创建但收不到主人凭据，请在服务主机用 profiles 查询编号，再输入 profile-recover 签发的主人恢复码。")
            throw e
        }
        check(expected == null || expected == binding.profile) { "返回的授权不属于原档案，本机凭据及历史已保留。" }
        check(previous == null || previous.binding.instance == binding.instance) { "返回的授权属于另一服务实例。" }
        owner = ProfileOwner(base, binding)
        ownerAwaitingSave = true
        needsRecovery = false
        bound = true
        persistOwner(checkNotNull(owner))
        synchronize(verifiedSourceBase)
    }

    suspend fun createInvite() = action {
        invite = api.invite(authorization().binding)
        notice = "已生成一次性手机配对码。"
    }

    suspend fun loadDevices() = action { devices = api.devices(authorization().binding) }

    suspend fun revoke(deviceId: String) = action {
        val binding = authorization().binding
        api.revoke(binding, deviceId)
        devices = api.devices(binding)
        notice = "该设备的档案访问权限已撤销。"
    }

    suspend fun refresh(verifiedSourceBase: String?) = action {
        if (!bound) return@action
        try {
            synchronize(verifiedSourceBase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connectionIssue = message(e)
            throw e
        }
    }

    private suspend fun directory(binding: ProfileBinding): ProfileDirectory {
        var page = api.sessions(binding)
        if (!page.more) return page
        val first = page
        val rows = page.sessions.toMutableList()
        while (page.more) {
            page = api.sessions(binding, rows.size, revision = first.revision)
            check(first.revision == page.revision) { "目录同步期间发生了变化，请重新同步。" }
            rows.addAll(page.sessions)
        }
        return first.copy(sessions = rows)
    }

    private fun legacyProof(key: SessionKey, binding: ProfileBinding, base: String): String? {
        if (!store.hasLegacySharedRecord(key)) return null
        val legacy = try {
            SharedLocal.decode(DesktopSharedStorage(store, key, base).load(), true).binding
        } catch (_: Exception) {
            // This proof is optional. An unreadable old grant can never authorize a room link.
            null
        }
        // Never send a room token to a different endpoint or disclose a guest's room.
        return legacy?.takeIf { it.owner && it.instance == binding.instance &&
            DesktopStore.normalizeAddress(it.address) == DesktopStore.normalizeAddress(binding.address) }?.token ?: "unverified"
    }

    private suspend fun synchronize(verified: String?) {
        val auth = authorization()
        val base = source()
        if (verified != null) check(DesktopStore.normalizeAddress(verified) == base) { "当前档案的服务地址已变更，请重新验证。" }
        if (base != auth.source) {
            check(verified != null) { "Launcher 实例端口已变更，请先连接并验证当前实例。" }
            val relocated = auth.copy(source = base)
            persistOwner(relocated)
            owner = relocated
        }
        val b = auth.binding
        store.prepareProfileHistory(profileId, b.profile)
        val local = store.sessions(profileId)
        val listing = directory(b)
        val remote = listing.sessions.associateBy { it.sid }
        val removed = store.profileRemoved(profileId)
        if (removed.isNotEmpty() || local.size != listing.expected) api.status(b, false, local.size, "")
        for (sid in removed) {
            api.remove(b, sid)
            store.acknowledgeProfileRemoval(profileId, sid)
        }

        val nextReasons = mutableMapOf<String, String>()
        val issues = mutableListOf<String>()
        val pending = mutableSetOf<String>()
        var published = false
        for ((index, session) in local.withIndex()) {
            val key = SessionKey(profileId, session.id)
            try {
                val position = store.profileHistoryPosition(key, b.profile)
                if (position.pending != null) pending += session.id
                var item = remote[session.id]
                if (item == null || item.title != session.title || item.total != position.prefix ||
                    item.activity < session.activity || item.reason.isNotEmpty()) {
                    notice = "正在发布会话 ${index + 1}/${local.size}。"
                    item = api.upsert(b, session.id, session.title, session.activity.coerceAtLeast(0), position.prefix, legacyProof(key, b, base))
                    published = true
                }
                check(item.total == position.prefix && item.imported in 0..position.prefix) { "服务端历史范围与本机不一致。" }
                if (item.imported < position.prefix) {
                    upload(b, key, position.prefix, item.imported)
                    published = true
                }
            } catch (e: Exception) {
                propagateConnectionError(e)
                val gap = "会话「${session.title}」(${key.sessionId})：${message(e)}"
                issues += gap
                nextReasons[session.id] = gap
            }
        }

        val current = if (published) directory(b).sessions.associateBy { it.sid } else remote
        for (session in local) {
            if (session.id in nextReasons) continue
            val key = SessionKey(profileId, session.id)
            val item = current[session.id]
            if (item == null) {
                val gap = "目录缺少会话 ${session.id}。"
                issues += gap
                nextReasons[session.id] = gap
                continue
            }
            try {
                val running = pull(b, key, item.activity)
                nextReasons[session.id] = item.reason.ifEmpty { if (running) "共享服务正在生成回复。" else "" }
                if (store.profileHistoryPosition(key, b.profile).pending == null) pending -= session.id
            } catch (e: Exception) {
                propagateConnectionError(e)
                val gap = "会话 ${session.id}：${message(e)}"
                issues += gap
                nextReasons[session.id] = gap
            }
        }
        val issue = issues.take(4).joinToString(" ").take(900) + if (issues.size > 4) " 另有 ${issues.size - 4} 段会话待同步。" else ""
        if (listing.complete != issues.isEmpty() || listing.issue != issue || listing.expected != local.size || removed.isNotEmpty()) {
            api.status(b, issues.isEmpty(), local.size, issue)
        }
        pendingSessions = pending
        reasons = nextReasons
        connectionIssue = ""
        notice = issue.ifEmpty { if (pending.isEmpty()) "已同步 ${local.size} 段会话。" else "有 ${pending.size} 个发送编号尚待确认。可在原会话中按原编号核对。" }
    }

    private suspend fun upload(b: ProfileBinding, key: SessionKey, total: Int, from: Int) {
        var start = from
        var limit = 50
        while (start < total) {
            notice = "正在上传会话 ${key.sessionId} 的历史，已确认 ${start}/${total} 轮。"
            val page = store.profileSeedPage(key, b.profile, start, limit)
            try {
                val next = api.importTurns(b, key.sessionId, start, page)
                check(next in (start + page.size)..total) { "服务端返回的历史进度无效。" }
                start = next
            } catch (e: SharedHttpFailure) {
                if (e.status in listOf(400, 413) && page.size > 1) {
                    limit = (page.size + 1) / 2
                    continue
                }
                if (e.status == 401) throw e
                error("第 ${start + 1}–${start + page.size} 轮未获确认（共 ${total} 轮，HTTP ${e.status}）。")
            }
        }
    }

    private suspend fun pull(b: ProfileBinding, key: SessionKey, activity: Long): Boolean {
        var after = store.profileHistoryPosition(key, b.profile).after
        do {
            val page = api.turns(b, key.sessionId, after)
            if (store.mergeProfilePage(key, b.profile, page.turns, activity)) revision++
            if (page.turns.any { it.status == "running" }) return true
            after = page.turns.lastOrNull()?.seq ?: after
        } while (page.more)
        return false
    }

    suspend fun send(sid: String, question: String, draftRevision: Long): Boolean {
        var consumed = false
        action {
            val auth = authorization()
            check(auth.source == source()) { "请先验证 Launcher 实例的新端口。" }
            val b = auth.binding
            val issue = reason(sid)
            check(issue.isEmpty()) { issue }
            check(question.isNotBlank() && question.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "请输入不超过 64 KiB 的文字。" }
            check(!Regex("^/task(?:\\s|$)").containsMatchIn(question.trimStart())) { "鲸鱼任务需在本机专用入口执行。" }
            val pending = SharedPending(UUID.randomUUID().toString(), question)
            val key = SessionKey(profileId, sid)
            store.beginProfileSend(key, b.profile, draftRevision, pending)
            consumed = true
            pendingSessions = pendingSessions + sid
            submit(b, key, pending)
        }
        return consumed
    }

    suspend fun retry(sid: String) = action {
        val auth = authorization()
        check(auth.source == source()) { "请先验证 Launcher 实例的新端口。" }
        val b = auth.binding
        val key = SessionKey(profileId, sid)
        val pending = store.profileHistoryPosition(key, b.profile).pending ?: return@action
        try {
            api.turn(b, sid, pending.id)
        } catch (e: SharedHttpFailure) {
            if (e.status != 404) throw e
            submit(b, key, pending)
            return@action
        }
        finishSend(b, key)
    }

    private suspend fun submit(b: ProfileBinding, key: SessionKey, pending: SharedPending) {
        api.send(b, key.sessionId, pending.id, pending.question)
        finishSend(b, key)
    }

    private suspend fun finishSend(b: ProfileBinding, key: SessionKey) {
        val running = pull(b, key, 0L)
        if (store.profileHistoryPosition(key, b.profile).pending == null) pendingSessions = pendingSessions - key.sessionId
        reasons = reasons + (key.sessionId to if (running) "共享服务正在生成回复。" else "")
        notice = if (running) "正在同步回复…" else "已同步。"
    }

    private fun propagateConnectionError(e: Exception) {
        if (e is CancellationException || e is java.io.IOException || e is SharedHttpFailure && e.status == 401) throw e
    }

    private fun message(e: Exception): String = when (e) {
        is SharedHttpFailure -> when (e.status) {
            401, 403 -> "授权失败，请核对服务地址和配对码。"
            409 -> when (e.conflict) {
                SharedConflict.RUNNING -> "另一台设备正在生成回复。完成后可按原发送编号重试。"
                SharedConflict.UNRESOLVED -> "上游执行结果未知，请先由服务主人核对 AstrBot 状态。"
                SharedConflict.OTHER -> "服务报告目录、会话或授权状态已变化（HTTP 409）。请刷新核对。"
            }
            else -> "共享服务返回 HTTP ${e.status}。"
        }
        is java.io.IOException -> "无法连接共享服务。请检查网络，已保存的发送编号可用于核对。"
        else -> e.message ?: "档案同步失败（${e.javaClass.simpleName}）。"
    }
}
