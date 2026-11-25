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
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

internal data class DesktopDeviceRecent(val key: SessionKey, val title: String, val source: String, val activity: Long)
internal data class DesktopDeviceUpstream(val profileId: String, val name: String, val base: String)
internal data class DesktopDeviceSessionState(val sid: String, val remote: Boolean, val reason: String, val rename: Boolean, val pending: Int,
    val authorityProfile: String = "")
internal data class DesktopDevicePending(val id: String, val question: String, val method: String, val phase: String, val entry: Entry?)

internal class DesktopDeviceMediaTarget(val address: String, val token: String, val username: String,
    val sid: String, val allowLoopbackHttp: Boolean)

private class DeviceHostFailure(val code: String) : Exception()
private data class VerifiedRoute(val base: String, val keyId: String, val availableKeys: KeyList, val upstream: String)

/** Owner of device sharing for one Windows history. The host owns the secret; history stores
 * only the explicitly approved instance/device identity and the immutable directory bindings. */
internal class DesktopDeviceSync(
    private val store: DesktopStore,
    private val keys: NamedKeys,
    private val runtime: (JSONObject) -> JSONObject = DesktopDeviceRuntime::call,
) {
    private val api = SharedDeviceTransport(allowLoopback = true)
    private val routes = mutableMapOf<String, VerifiedRoute>()
    private var issues by mutableStateOf<Map<String, String>>(emptyMap())
    private var sessionIssues by mutableStateOf<Map<SessionKey, String>>(emptyMap())
    var enabled by mutableStateOf(store.deviceOwner() != null)
        private set
    var revision by mutableStateOf(0L)
        private set
    var notice by mutableStateOf("")
        private set
    var phoneUrl by mutableStateOf("")
        private set
    var autoStart by mutableStateOf(false)
        private set
    var devices by mutableStateOf<List<ProfileDevice>>(emptyList())
        private set
    var invite by mutableStateOf<ProfileInvite?>(null)
        private set
    var unmatchedProfiles by mutableStateOf<List<DeviceProfile>>(emptyList())
        private set
    var upstreamCandidates by mutableStateOf<List<DesktopDeviceUpstream>>(emptyList())
        private set

    private fun native(p: Profile) = !p.offlineOnly && p.kind != "shared" && p.kind != "device"

    fun manages(profileId: String): Boolean = synchronized(store) {
        val p = store.profile(profileId) ?: return false
        if (!enabled || !native(p) && p.kind != "device") return false
        if (store.profileAuthorization(p.id) != null && store.deviceProfile(p.id) == null) return false
        if (p.currentSession.isNotBlank()) {
            val key = SessionKey(p.id, p.currentSession)
            if (store.hasLegacySharedRecord(key)) {
                val state = store.deviceSessionState(key)
                if (state.authorityProfile.isEmpty() && (state.sid.isBlank() || state.reason.isNotBlank() || key in sessionIssues)) return false
            }
        }
        true
    }

    fun reason(key: SessionKey): String {
        if (!enabled) return ""
        val unavailable = issues[key.profileId] ?: if (store.deviceProfile(key.profileId)?.available == false)
            "此来源还没有经过电脑验证的上游，只能阅读。" else ""
        if (key.sessionId.isEmpty()) return unavailable
        return sessionIssues[key] ?: store.deviceSessionState(key).reason.ifEmpty { unavailable }
    }

    fun pending(key: SessionKey): Int = if (key.sessionId.isBlank() || !manages(key.profileId)) 0 else
        store.deviceSessionState(key).pending

    fun recent(): List<DesktopDeviceRecent> = synchronized(store) {
        val rows = store.profiles.flatMap { p ->
            val source = store.deviceProfile(p.id)
            val label = if (source == null) profileDisplayName(p) else {
                val origin = if (source.originDevice == store.deviceOwner()?.second) "电脑" else "手机"
                "${source.name} · $origin · ${source.username} · ${source.profile.take(8)}"
            }
            store.sessions(p.id).mapNotNull { session ->
                val key = SessionKey(p.id, session.id)
                val meta = store.deviceSessionState(key)
                if (session.entryCount == 0 && meta.sid.isEmpty() && meta.pending == 0) null
                else DesktopDeviceRecent(key, session.title, label, session.activity)
            }
        }.sortedWith(compareByDescending<DesktopDeviceRecent> { it.activity }
            .thenBy { it.key.profileId }.thenBy { it.key.sessionId })
        val active = store.profile(store.activeProfileId)
        val keep = store.deviceRecentRepresentatives(SessionKey(store.activeProfileId, active?.currentSession.orEmpty()), rows.map { it.key })
        rows.filter { it.key in keep }
    }

    private fun host(action: String, args: JSONObject = JSONObject()): JSONObject {
        val result = runtime(args.put("action", action))
        if (!result.optBoolean("ok")) throw DeviceHostFailure(result.optString("error", "service_offline"))
        return result
    }

    private fun owner(result: JSONObject, authorize: Boolean = false): DeviceBinding {
        val address = result.getString("localUrl")
        val uri = URI(sharedAddress(address, true))
        check(uri.scheme == "http" && uri.host == "127.0.0.1") { "设备主人的服务地址必须是电脑本机地址。" }
        val b = DeviceBinding(address, result.getString("instance"), result.getString("device"), result.getString("token"), true)
        if (authorize) store.saveDeviceOwner(b.instance, b.device)
        check(store.deviceOwner() == (b.instance to b.device)) { "此服务实例与电脑保存的主人身份不一致。" }
        enabled = true
        if (phoneUrl != result.optString("phoneUrl")) invite = null
        phoneUrl = result.optString("phoneUrl")
        autoStart = result.optBoolean("autostart")
        return b
    }

    private suspend fun action(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        store.profileRequests.withLock {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is DeviceHostFailure || e is SharedHttpFailure && e.status == 401) invite = null
                notice = explain(e)
            } finally {
                revision++
            }
        }
    }

    suspend fun authorize(options: JSONObject, target: DesktopSendTarget) = action {
        target.checkTarget()
        val p = checkNotNull(store.profile(target.profileId))
        check(native(p) && DesktopStore.normalizeAddress(p.address) == DesktopStore.normalizeAddress(target.address)) {
            "请先验证要用于共享的本机档案。"
        }
        val args = JSONObject(options.toString()).put("base", target.address).put("key", target.token)
        args.remove("launcherId")
        if (p.id.startsWith("launcher:")) args.put("launcherId", p.id.removePrefix("launcher:"))
        invite = null
        val b = owner(host("configure", args), authorize = true)
        synchronize(b)
    }

    suspend fun refresh() {
        if (enabled) action { synchronize(owner(host("local"))) }
    }

    suspend fun createInvite() = action {
        invite = null
        check(enabled) { "请先在电脑确认开启设备共享。" }
        val b = owner(host("status"))
        synchronize(b)
        val sources = store.profiles.filter(::native)
        val count = sources.sumOf { store.sessions(it.id).count { row -> store.deviceSessionState(SessionKey(it.id, row.id)).sid.isNotEmpty() } }
        invite = api.invite(b, sources.size, count)
        notice = "已生成一次性手机配对码。"
    }

    suspend fun revoke(device: String) = action {
        val b = owner(host("local"))
        check(device != b.device)
        api.revoke(b, device)
        devices = api.devices(b)
        notice = "已撤销该设备的访问权限。"
    }

    suspend fun linkSource(remoteProfile: String, localProfileId: String) {
        val reviewed = unmatchedProfiles.singleOrNull { it.profile == remoteProfile }
        val target = upstreamCandidates.singleOrNull { it.profileId == localProfileId }
        action {
            check(reviewed != null && target != null) { "来源或上游已变更，请重新选择。" }
            val b = owner(host("local"))
            val fresh = api.profiles(b).singleOrNull { it.profile == reviewed.profile }
            check(fresh == reviewed && !reviewed.available && reviewed.originDevice != b.device) { "手机来源已变更，请重新确认。" }
            val alias = sharedAddress(reviewed.base, false)
            val p = checkNotNull(store.profile(target.profileId))
            // Re-probe the selected native upstream only. The phone-supplied URL is data for
            // the local alias registry and never a network destination for Windows credentials.
            val route = checkNotNull(routes[p.id]) { "本机上游尚未通过验证。" }
            check(route.base == target.base && currentBase(p) == target.base && route.availableKeys == keys.list(p.id)) {
                "上游地址或密钥已变更，请重新确认。"
            }
            val secret = keys.secret(p.id, route.keyId)
            fetchDesktopSessions(route.base, secret, store.username)
            check(currentBase(p) == route.base && keys.list(p.id) == route.availableKeys) { "上游地址或密钥已变更，请重新确认。" }
            val args = routeRequest(p, route.base, secret).put("addAlias", alias)
            check(host("upstream", args).getString("upstream") == route.upstream) { "本机上游标识已变更。" }
            synchronize(b)
            notice = "已登记手机来源地址。手机下次同步时会核实其原会话。"
        }
    }

    suspend fun setAutostart(enabled: Boolean) = action {
        check(this.enabled)
        autoStart = host("autostart", JSONObject().put("enabled", enabled)).getBoolean("autostart")
    }

    suspend fun stopHost() = action {
        check(enabled)
        host("stop")
        invite = null
        autoStart = false
        notice = "共享服务已停止，待发文字保存在本机。"
    }

    suspend fun startHost() = action {
        check(enabled)
        synchronize(owner(host("start")))
    }

    private fun currentBase(p: Profile): String {
        val base = if (p.id.startsWith("launcher:")) LauncherInstanceResolver.currentUrl(p.id.removePrefix("launcher:"))
            ?: error("Launcher 实例当前离线。") else p.address
        return DesktopStore.normalizeAddress(sharedAddress(base, true))
    }

    private fun routeRequest(p: Profile, base: String, key: String): JSONObject =
        JSONObject().put("sourceId", p.id).put("base", base).put("key", key).also {
            if (p.id.startsWith("launcher:")) it.put("launcherId", p.id.removePrefix("launcher:"))
        }

    /** Read saved credentials only from a native Windows profile. A phone-reported URL is never
     * a destination for key verification. Cache successful verification until the key list or route changes. */
    private fun verifiedRoute(p: Profile): VerifiedRoute? {
        check(native(p))
        val base = currentBase(p)
        val list = keys.list(p.id)
        routes[p.id]?.takeIf { it.base == base && it.availableKeys == list }?.let { return it }
        routes.remove(p.id)
        val ids = (listOfNotNull(list.activeId) + list.keys.map { it.id }).distinct()
        for (id in ids) {
            val key = try {
                keys.secret(p.id, id).also { fetchDesktopSessions(base, it, store.username) }
            } catch (_: IllegalStateException) { continue }
              catch (_: IllegalArgumentException) { continue }
            check(currentBase(p) == base && keys.list(p.id) == list) { "来源或凭据已更改，请重新同步。" }
            return VerifiedRoute(base, id, list, host("upstream", routeRequest(p, base, key)).getString("upstream")).also { routes[p.id] = it }
        }
        return null
    }

    private fun oldProfileProof(p: Profile, b: DeviceBinding, base: String): String? {
        val saved = store.profileAuthorization(p.id) ?: return null
        val entropy = MessageDigest.getInstance("SHA-256").digest(
            JSONArray(listOf("desktop-profile-owner-v1", p.id, store.username)).toString().toByteArray(Charsets.UTF_8))
        val value = try {
            val bytes = WindowsDpapi.transform(entropy, Base64.getDecoder().decode(saved), false)
            try { JSONObject(bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
        } catch (_: Exception) {
            error("原档案主人凭据无法读取，原记录已保留。")
        }
        check(value.getString("instance") == b.instance && (value.getString("source") == base ||
            p.id.startsWith("launcher:") && URI(value.getString("source")).host == "127.0.0.1" && URI(base).host == "127.0.0.1")) {
            "原档案属于另一共享实例或上游，原记录已保留。"
        }
        return value.getString("token")
    }

    private fun roomProof(p: Profile, key: SessionKey, b: DeviceBinding): String? {
        if (!store.hasLegacySharedRecord(key)) return null
        val old = try {
            SharedLocal.decode(readLegacyRoomRecord(store, key, p.address), true)
        } catch (_: Exception) { return "unverified" }
        check(old.pending == null) { "原共享房间有一轮发送尚未确认，原记录已保留。" }
        return old.binding?.takeIf { it.owner && it.instance == b.instance }?.token ?: "unverified"
    }

    private suspend fun directory(b: DeviceBinding): List<DeviceSession> {
        val first = api.sessions(b)
        val rows = first.sessions.toMutableList()
        var page = first
        while (page.more) {
            check(page.sessions.isNotEmpty())
            page = api.sessions(b, rows.size, revision = first.revision)
            check(page.revision == first.revision) { "目录已变更，请重新同步。" }
            rows.addAll(page.sessions)
        }
        return rows
    }

    private suspend fun synchronize(b: DeviceBinding, mediaId: String? = null, execute: (suspend (DesktopDeviceMediaTarget) -> Entry)? = null) {
        val known = api.profiles(b).associateBy { it.profile }.toMutableMap()
        val byRemote = mutableMapOf<String, String>()
        val profileErrors = mutableMapOf<String, String>()
        val candidates = mutableListOf<DesktopDeviceUpstream>()
        val localProfiles = store.profiles.filter(::native)
        for (p in localProfiles) {
            try {
                val route = try { verifiedRoute(p) } catch (e: DeviceHostFailure) { throw e } catch (_: Exception) { null }
                val base = route?.base ?: p.address
                val cached = store.deviceProfile(p.id)
                val existing = known[cached?.profile]
                val remote = if (existing != null && existing.base == base && route?.upstream == existing.upstream &&
                    existing.name == profileDisplayName(p)) existing else {
                    val proof = oldProfileProof(p, b, base)
                    val value = api.profile(b, p.id, base, store.username, profileDisplayName(p), route?.upstream, proof)
                    val previous = store.recordedRemoteProfile(p.id)
                    check(previous == null || previous == value.profile) { "无法核实原档案的主人权限，原历史已保留。" }
                    value
                }
                byRemote[remote.profile] = store.cacheDeviceProfile(remote, p.id)
                known[remote.profile] = remote
                if (route != null && route.upstream == remote.upstream) candidates += DesktopDeviceUpstream(p.id, profileDisplayName(p), route.base)
                if (!remote.available) profileErrors[p.id] = "此档案尚无可验证的上游密钥，已提交的历史可读。"
            } catch (e: Exception) {
                propagate(e)
                profileErrors[p.id] = explain(e)
            }
        }
        for (remote in known.values) {
            if (remote.profile in byRemote) continue
            // On owner restart all local registrations are revisited above. Do not turn an
            // unavailable original profile into an unrelated mirror of the same history.
            if (remote.originDevice == b.device && remote.username == store.username &&
                localProfiles.any { it.id == remote.sourceId }) continue
            byRemote[remote.profile] = store.cacheDeviceProfile(remote)
        }
        issues = profileErrors
        unmatchedProfiles = known.values.filter { !it.available && it.originDevice != b.device &&
            runCatching { sharedAddress(it.base, false) }.isSuccess }
        upstreamCandidates = candidates
        val removed = mutableSetOf<Pair<String, String>>()
        for ((profile, id) in byRemote) {
            for (sid in store.deviceRemoved(id)) {
                api.remove(b, profile, sid)
                removed.add(profile to sid)
                store.acknowledgeDeviceRemoval(id, sid)
            }
            // Previously deleted local rows must stay hidden without deleting the remote session.
            for (sid in store.legacyHiddenSessions(id)) removed.add(profile to sid)
        }
        val remoteRows = directory(b).associateBy { it.profile to it.sid }
        val errors = mutableMapOf<SessionKey, String>()
        var pendingIssue = ""
        val touched = mutableSetOf<Pair<String, String>>()
        for ((profile, id) in byRemote) {
            val source = checkNotNull(store.profile(id))
            for (session in store.sessions(id)) {
                val key = SessionKey(id, session.id)
                try {
                    val meta = store.deviceSessionState(key)
                    var targetProfile = meta.authorityProfile.ifEmpty { profile }
                    var sid = meta.sid.ifBlank { key.sessionId }
                    var linked = meta.authorityProfile.isNotEmpty()
                    val targetId = byRemote[targetProfile] ?: error("找不到已证明的权威档案。")
                    if ((targetProfile to sid) in removed || sid in store.deviceRemoved(targetId)) continue
                    var row = remoteRows[targetProfile to sid]
                    if (meta.sid.isNotEmpty() && row == null) {
                        errors[key] = "服务目录已移除此会话，电脑上的历史和草稿已保留。"
                        continue
                    }
                    val proofNeeded = native(source) && !meta.remote && meta.sid.isEmpty() && store.hasLegacySharedRecord(key)
                    val legacyPending = store.hasLegacyProfilePending(key)
                    if (session.entryCount == 0 && meta.pending == 0 && row == null && !proofNeeded && !legacyPending) continue
                    store.prepareDeviceHistory(key, profile)
                    var position = store.profileHistoryPosition(key, profile)
                    if (!linked && native(source) && !meta.remote && (row == null && (position.prefix > 0 || proofNeeded) ||
                            row != null && row.originDevice == b.device && (meta.rename || row.reason.isNotBlank() ||
                            position.prefix > row.total))) {
                        val proof = roomProof(source, key, b)
                        val published = api.session(b, profile, sid, session.title, session.activity, position.prefix, proof)
                        if (published.profile != profile || published.sid != sid) {
                            val authority = known[published.profile]
                            check(proof != null && proof != "unverified" && authority != null && authority.username == store.username &&
                                authority.upstream.isNotEmpty() && authority.upstream == known[profile]?.upstream &&
                                published.profile in byRemote) { "无法核实服务返回的房间来源，原文已保留。" }
                            val snapshot = store.sessionContent(id, key.sessionId)
                            val aligned = alignedWithAuthority(b, published, snapshot, store.recordedTurnIds(key))
                            store.bindProvenRoom(key, profile, published, snapshot, aligned)
                            position = store.profileHistoryPosition(key, profile)
                            targetProfile = published.profile
                            sid = published.sid
                            linked = true
                        }
                        row = published
                        if (!linked) store.cacheDeviceSession(id, published, key, session.title)
                    }
                    if (row != null) {
                        store.cacheDeviceSession(id, row, key)
                        if (!linked && !meta.remote && row.originDevice == b.device && position.prefix > row.imported) {
                            upload(b, key, profile, sid, position.prefix, row.imported)
                        }
                        if (position.after < row.imported && (store.nextDeviceSend(key) == null ||
                                row.reason.isNotEmpty() || known[targetProfile]?.available != true)) {
                            pull(b, key, targetProfile, sid, row.activity)
                        }
                        if (!linked) touched += profile to sid
                    }
                    val previous = store.profileHistoryPosition(key, profile).pending
                    if (previous != null) {
                        check(row != null && native(source)) { "原档案有一轮发送尚未确认，原发送编号已保留。" }
                        try {
                            api.turn(b, targetProfile, sid, previous.id)
                        } catch (e: SharedHttpFailure) {
                            if (e.status != 404) throw e
                            errors[key] = "原档案的发送尚未确认，原发送编号已保留。"
                            continue
                        }
                        pull(b, key, targetProfile, sid, row.activity)
                        if (store.hasLegacyProfilePending(key)) {
                            errors[key] = "原档案的发送尚未确认，原发送编号已保留。"
                            continue
                        }
                    }
                    val mediaInProgress = store.nextDeviceSend(key)?.let { it.method == "media" && it.phase.isNotEmpty() } == true
                    if (mediaInProgress || row?.reason.isNullOrEmpty() && known[targetProfile]?.available == true) {
                        deliver(b, key, targetProfile, sid, row != null, mediaId, execute)
                    }
                } catch (e: Exception) {
                    propagate(e)
                    if (e is SharedHttpFailure && (e.status == 409 || e.status >= 500) && store.deviceSessionState(key).pending > 0) {
                        pendingIssue = explain(e)
                    } else if (store.sessions(id).any { it.id == session.id }) errors[key] = explain(e)
                }
            }
        }
        for ((address, row) in remoteRows) {
            if (address in touched || address in removed) continue
            val id = byRemote[row.profile] ?: continue
            if (row.sid in store.deviceRemoved(id)) continue
            val key = SessionKey(id, row.sid)
            if (key in errors) continue
            try {
                val actual = store.cacheDeviceSession(id, row)
                store.prepareDeviceHistory(actual, row.profile)
                pull(b, actual, row.profile, row.sid, row.activity)
            } catch (e: Exception) {
                propagate(e)
                errors[key] = explain(e)
            }
        }
        sessionIssues = errors
        devices = api.devices(b)
        val outstanding = store.profiles.sumOf { p -> store.sessions(p.id).sumOf { store.deviceSessionState(SessionKey(p.id, it.id)).pending } }
        notice = (profileErrors.values + errors.values + listOf(pendingIssue).filter(String::isNotEmpty)).distinct().take(3).joinToString(" ").ifEmpty {
            if (outstanding > 0) "有 ${outstanding} 轮文字或媒体正在排队或核对。" else "已同步设备会话。"
        }
    }

    private suspend fun upload(b: DeviceBinding, key: SessionKey, profile: String, sid: String, total: Int, from: Int) {
        var start = from
        var limit = 50
        while (start < total) {
            val page = store.profileSeedPage(key, profile, start, limit)
            try {
                val next = api.importTurns(b, profile, sid, start, page)
                check(next in (start + page.size)..total) { "服务端返回的历史进度无效。" }
                start = next
            } catch (e: SharedHttpFailure) {
                if (e.status !in listOf(400, 413) || page.size <= 1) throw e
                limit = (page.size + 1) / 2
            }
        }
    }

    private suspend fun pull(b: DeviceBinding, key: SessionKey, profile: String, sid: String, activity: Long) {
        val localScope = checkNotNull(store.deviceProfile(key.profileId)).profile
        var after = store.profileHistoryPosition(key, localScope).after
        do {
            val page = api.turns(b, profile, sid, after)
            store.mergeProfilePage(key, localScope, page.turns, activity)
            if (page.turns.any { it.status == "running" }) return
            if (page.more) check(page.turns.isNotEmpty() && page.turns.last().seq > after)
            after = store.profileHistoryPosition(key, localScope).after
            if (after < (page.turns.lastOrNull()?.seq ?: after)) return
        } while (page.more)
    }

    private suspend fun alignedWithAuthority(b: DeviceBinding, remote: DeviceSession, snapshot: Chat, ids: List<String?>): Boolean {
        if (snapshot.entries.size > remote.imported) return false
        var count = 0
        while (count < snapshot.entries.size) {
            val page = api.turns(b, remote.profile, remote.sid, count.toLong(), minOf(snapshot.entries.size - count, 50))
            if (page.turns.isEmpty()) return false
            for (turn in page.turns) {
                if (count == snapshot.entries.size) break
                check(turn.seq == count.toLong() + 1) { "权威历史轮次不连续，请重新同步。" }
                if (ids[count] != null && ids[count] != turn.id) return false
                val local = desktopSharedSeedTurn(snapshot.entries[count++])
                if (turn.question != local.question || turn.answer != local.answer || turn.status != local.status) return false
            }
        }
        return true
    }

    suspend fun send(key: SessionKey, question: String, draftRevision: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            check(manages(key.profileId) && reason(key).isEmpty()) { reason(key).ifBlank { "此档案尚未启用设备共享。" } }
            check(question.isNotBlank() && question.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "请输入不超过 64 KiB 的文字。" }
            check(!Regex("^/task(?:\\s|$)").containsMatchIn(question.trimStart())) { "鲸鱼任务需在本机专用入口执行。" }
            store.queueDeviceSend(key, question, draftRevision)
            notice = "消息已保存，正在等待同步。"
            revision++
            true
        } catch (e: Exception) {
            propagate(e)
            notice = explain(e)
            false
        }
    }

    suspend fun sendMedia(key: SessionKey, entry: Entry, draftRevision: Long, execute: suspend (DesktopDeviceMediaTarget) -> Entry): Boolean {
        var consumed = false
        action {
            val p = checkNotNull(store.profile(key.profileId))
            check(manages(p.id) && native(p)) { "请在原设备上发送此档案的媒体。" }
            check(reason(key).isEmpty()) { reason(key) }
            val question = desktopSharedSeedTurn(entry).question
            check(entry.attachmentReferences.isNotEmpty() && question.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
            val id = store.queueDeviceSend(key, question, draftRevision, entry)
            consumed = true
            synchronize(owner(host("local")), id, execute)
        }
        return consumed
    }

    private suspend fun existing(b: DeviceBinding, id: String): DeviceOperation? = try {
        api.operation(b, id)
    } catch (e: SharedHttpFailure) {
        if (e.status != 404) throw e
        null
    }

    private suspend fun deliver(b: DeviceBinding, key: SessionKey, profile: String, sid: String, listed: Boolean,
        mediaId: String?, execute: (suspend (DesktopDeviceMediaTarget) -> Entry)?) {
        var created = listed
        while (true) {
            val pending = store.nextDeviceSend(key) ?: return
            val method = pending.method.ifEmpty {
                when {
                    pending.entry != null -> "media"
                    created -> "send"
                    else -> "start"
                }.also { store.markDeviceSend(key, pending.id, it) }
            }
            val previous = if (pending.method.isNotEmpty()) existing(b, pending.id) else null
            val op = if (method == "media" && pending.phase.isEmpty()) {
                // A repeat verifies the reservation type and destination. Its dispatch flag reports
                // reservation creation only. The durable source-device claim gates native execution.
                api.beginMedia(b, profile, sid, pending.id, pending.question)
            } else previous ?: when (method) {
                "media" -> error("找不到原媒体操作，已保存本机结果和原编号。")
                "start" -> api.start(b, profile, sid, pending.id, pending.question)
                "send" -> api.send(b, profile, sid, pending.id, pending.question)
                else -> error("本机待发操作类型无效。")
            }
            check(op.profile == profile && op.sid == sid && op.turn.id == pending.id && op.turn.question == pending.question) {
                "设备发送回执与本机操作不一致。"
            }
            if (method == "media" && pending.phase != "confirmed") {
                finishMedia(b, key, op, pending, if (pending.id == mediaId) execute else null)
            }
            pull(b, key, profile, sid, 0L)
            if (store.nextDeviceSend(key)?.id == pending.id) return
            created = true
        }
    }

    private suspend fun finishMedia(b: DeviceBinding, key: SessionKey, op: DeviceOperation, pending: DesktopDevicePending,
        execute: (suspend (DesktopDeviceMediaTarget) -> Entry)?) {
        val entry = checkNotNull(pending.entry)
        val completed = when (pending.phase) {
            "finished" -> entry
            "", "dispatch" -> {
                val result = if (pending.phase == "dispatch") entry.copy(status = "上次回复未确认") else {
                    check(op.turn.status == "running") { "媒体操作已在服务端结束，请核对原编号。本机原件已保留。" }
                    val target = mediaTarget(b, key, op, entry)
                    check(store.claimDeviceMedia(key, pending.id)) { "原媒体操作的本机状态已变更，等待核对。" }
                    try {
                        val reply = if (execute != null) execute(target) else uploadNative(target, entry)
                        check(reply.question == entry.question && reply.attachmentReferences == entry.attachmentReferences)
                        reply
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { entry.copy(status = "上次回复未确认") }
                }
                store.markDeviceSend(key, pending.id, "media", "finished", result)
                result
            }
            else -> error("本机媒体操作的阶段无效，原件已保留。")
        }
        val projection = desktopSharedSeedTurn(completed)
        val receipt = api.endMedia(b, op.profile, op.sid, pending.id, projection.answer, projection.status)
        check(receipt.turn.question == pending.question && receipt.turn.seq == op.turn.seq) { "媒体完成回执与原保留轮次不一致。" }
        store.markDeviceSend(key, pending.id, "media", "confirmed", completed)
    }

    private fun mediaTarget(b: DeviceBinding, key: SessionKey, op: DeviceOperation, entry: Entry): DesktopDeviceMediaTarget {
        val local = checkNotNull(store.profile(key.profileId))
        val source = checkNotNull(store.deviceProfile(local.id))
        val authority = store.profiles.mapNotNull { store.deviceProfile(it.id) }.singleOrNull { it.profile == op.profile }
        check(native(local) && authority != null && authority.originDevice == b.device && authority.username == store.username &&
            source.upstream == authority.upstream) { "请在原设备上发送此档案的媒体。" }
        val files = nativeFiles(entry)
        check(files.isNotEmpty() && files.all { Files.isRegularFile(it) && Files.isReadable(it) && Files.size(it) > 0 }) {
            "本机媒体原件暂时无法读取，已保留待发操作。"
        }
        val route = checkNotNull(verifiedRoute(local)) { "本机上游密钥暂时无法验证，已保留媒体操作。" }
        check(route.upstream == authority.upstream)
        val token = keys.secret(local.id, route.keyId)
        fetchDesktopSessions(route.base, token, authority.username)
        check(currentBase(local) == route.base) { "来源地址已变更，等待重新验证。" }
        return DesktopDeviceMediaTarget(route.base, token, authority.username, op.sid, URI(route.base).scheme == "http")
    }

    private fun nativeFiles(entry: Entry): List<Path> =
        entry.attachmentReferences.map { if (it.startsWith("file:", true)) Path.of(URI(it)) else Path.of(it) }

    private fun uploadNative(target: DesktopDeviceMediaTarget, entry: Entry): Entry {
        var answer = ""
        val replies = mutableListOf<ReplyAttachment>()
        val files = nativeFiles(entry)
        sendAttachmentsToAstrBot(target.address, target.token, target.username, target.sid, files, entry.question,
            allowLoopbackHttp = target.allowLoopbackHttp,
            onReplyImage = { id, offset -> replies += ReplyAttachment(id, offset, ReplyAttachmentType.IMAGE) },
            onReplyAudio = { id, offset -> replies += ReplyAttachment(id, offset, ReplyAttachmentType.AUDIO) },
            onText = { answer = it })
        return entry.copy(answer = answer, replyAttachments = replies, status = "已完成")
    }

    private fun propagate(e: Exception) {
        if (e is CancellationException || e is IOException || e is SharedHttpFailure && e.status == 401) throw e
    }

    private fun explain(e: Exception): String = when (e) {
        is DeviceHostFailure -> when (e.code) {
            "service_offline", "not_configured" -> "共享服务离线，待发内容已保存在电脑。"
            "gateway_unreachable", "gateway_identity_mismatch" -> "无法验证手机访问地址，请检查 HTTPS 地址与网关。"
            "ngrok_api_unavailable" -> hostGuidance(e.code)
            "ngrok_auth_required", "ngrok_missing" -> "请检查 ngrok 程序及其授权。"
            "db_in_use" -> "原共享数据库正在使用，请先停止占用它的服务。"
            "gateway_port_in_use" -> "手机网关端口已被占用。"
            else -> "设备服务操作失败（${e.code}）。"
        }
        is SharedHttpFailure -> when (e.status) {
            401, 403 -> "服务未确认此操作的授权，原记录及待发编号已保留。"
            409 -> when (e.conflict) {
                SharedConflict.RUNNING -> "另一台设备正在生成回复。完成后会按原发送编号继续。"
                SharedConflict.UNRESOLVED -> "上一轮的上游执行结果未知，请先在 AstrBot 核对，待发编号已保留。"
                SharedConflict.OTHER -> "服务未确认会话来源或操作状态，待发编号已保留。"
            }
            else -> "设备服务返回 HTTP ${e.status}。"
        }
        is IOException -> "无法连接设备服务，待发内容已保存在电脑。"
        else -> if (e is IllegalStateException || e is IllegalArgumentException) e.message?.take(240) ?: "设备同步失败。" else "设备同步失败。"
    }
}
