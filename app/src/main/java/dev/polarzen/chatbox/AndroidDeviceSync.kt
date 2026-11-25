package dev.polarzen.chatbox

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.net.URI
import android.os.SystemClock

internal data class AndroidDeviceRow(val profile: String, val sid: String, val title: String,
    val activity: Long, val source: String, val username: String, val nativeBase: String?, val reason: String)

private val AndroidDeviceRow.key get() = DeviceRoomKey(profile,sid)
private val DeviceSession.key get() = DeviceRoomKey(profile,sid)
private fun localProfile(base: String) = "local:$base"
private const val RETIRED_REASON = "电脑已移除此会话。手机上的原记录及草稿仍可查看。"

internal fun deviceDraftRow(saved: DeviceRoomDraft, profiles: List<DeviceProfile>, retired: Set<DeviceRoomKey>): AndroidDeviceRow? {
    val key = saved.key
    if (saved.text.isEmpty() && key in retired) return null
    val profile = profiles.find { it.profile == key.profile } ?: return null
    return AndroidDeviceRow(key.profile,key.sid,"新会话",saved.created,profile.name,profile.username,null,
        if (key in retired) RETIRED_REASON else "")
}

private fun wireBase(base: String): String {
    val uri = URI(base)
    val port = if (uri.port == -1 || uri.scheme == "https" && uri.port == 443 || uri.scheme == "http" && uri.port == 80)
        "" else ":${uri.port}"
    return URI("${uri.scheme}://${uri.host}$port${uri.rawPath.orEmpty()}").toASCIIString().trimEnd('/')
}
private fun sourceId(base: String) = "android:${wireBase(base)}"

internal fun deviceMediaText(text: String, media: List<MobileAsset>): String = buildList {
    if (text.isNotEmpty()) add(text)
    media.forEach { asset ->
        val kind = when (asset.kind) { "image" -> "图片"; "audio" -> "语音"; else -> "文件" }
        add("[${kind}附件 · 共享记录无法打开]")
    }
}.joinToString("\n")

internal fun legacyOwnerProof(state: SharedLocal?, instance: String, trusted: Set<String>): String? {
    if (state == null || state.revoked || state.pending != null) return null
    return state.binding?.takeIf { it.owner && it.instance == instance && it.address in trusted }?.token
}

internal fun deviceQuestionTooLarge(question: String): Boolean {
    val maxBytes = 64 * 1024
    return question.length > maxBytes || question.toByteArray(Charsets.UTF_8).size > maxBytes
}

internal fun deviceImport(turn: MobileTurn): ProfileImportTurn? {
    val status = when {
        turn.status == "已完成" -> "complete"
        turn.status == "上次回复未确认" || turn.status.startsWith("回复状态未确认") ||
            turn.status.startsWith("发送失败") -> "unknown"
        else -> return null
    }
    return ProfileImportTurn(turn.id, deviceMediaText(turn.question,turn.sent), deviceMediaText(turn.answer,turn.replies), status)
}

internal fun deviceImportBatch(records: List<ProfileImportTurn>, start: Int): List<ProfileImportTurn> {
    var bytes = 0L
    var end = start
    while (end < records.size && end - start < 25) {
        val turn = records[end]
        // JSON may escape each UTF-8 byte as six ASCII bytes. Leave room for the envelope.
        val estimate = 256L + 6L * (turn.question.toByteArray(Charsets.UTF_8).size.toLong() +
            turn.answer.toByteArray(Charsets.UTF_8).size + turn.id.length)
        if (end > start && bytes + estimate > 7L * 1024 * 1024) break
        bytes += estimate
        end++
    }
    return records.subList(start,end)
}

internal enum class DeviceSendFailure { KEEP_PENDING, WAIT_FOR_ROOM, REJECTED }

internal fun deviceSendFailure(error: SharedHttpFailure, alreadyAccepted: Boolean): DeviceSendFailure = when {
    alreadyAccepted || error.status !in listOf(400,403,404,409,413,422) -> DeviceSendFailure.KEEP_PENDING
    error.status == 409 && error.conflict == SharedConflict.RUNNING -> DeviceSendFailure.WAIT_FOR_ROOM
    else -> DeviceSendFailure.REJECTED
}

private fun SharedTurn.mobile() = MobileTurn(id, question, answer, when (status) {
    "complete" -> "已完成"
    "running" -> "回复中"
    else -> "上次回复未确认"
}, createdAt = 0)

internal fun deviceMerge(native: MobileSession?, row: AndroidDeviceRow, turns: List<SharedTurn>,
                         draft: String, legacyAlias: Boolean = false): MobileSession {
    if (native == null) return MobileSession(row.sid, manualName = row.title, turns = turns.map { it.mobile() },
        draft = MobileDraft(draft))
    if (turns.isEmpty()) return native
    val local = native.turns.associateBy { it.id }
    val prefix by lazy { native.turns.mapNotNull { turn -> deviceImport(turn)?.let { turn to it } } }
    val used = mutableSetOf<String>()
    val body = turns.map { turn ->
        // A legacy room may own different IDs for its already-imported prefix. Match only that
        // exact sequence and both bodies, never two equal questions at arbitrary positions.
        val original = local[turn.id] ?: (if (legacyAlias) prefix.getOrNull((turn.seq - 1).toInt()) else null)?.takeIf { (candidate, imported) ->
            candidate.id !in used && imported.question == turn.question && imported.answer == turn.answer &&
                imported.status == turn.status
        }?.first
        if (original == null) turn.mobile() else {
            used += original.id
            original
        }
    }
    return native.copy(turns = body + native.turns.filterNot { it.id in used })
}

internal class AndroidDeviceSync(private val context: Context, private val app: AndroidAppState, private val scope: CoroutineScope) {
    private val store = AndroidDeviceStore(context)
    private val api = SharedDeviceTransport(false)
    private val lock = Mutex()
    private val history get() = app.history
    private var binding by mutableStateOf<DeviceBinding?>(null)
    private var confirmed by mutableStateOf(false)
    val bound get() = binding != null && confirmed
    val pendingJoin get() = binding != null && !confirmed
    val address get() = binding?.address.orEmpty()
    var online by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set
    var syncing by mutableStateOf(false)
        private set
    var preview by mutableStateOf<DevicePreview?>(null)
        private set
    var phoneProfiles by mutableStateOf(0)
        private set
    var phoneSessions by mutableStateOf(0)
        private set
    private var invitation: DevicePairCode? = null
    private var previewGeneration = 0L
    var profiles by mutableStateOf(emptyList<DeviceProfile>())
        private set
    private var directory by mutableStateOf(emptyList<DeviceSession>())
    private var links by mutableStateOf(emptyList<DeviceImportLink>())
    private var legacyReady by mutableStateOf(emptySet<DeviceLocalKey>())
    private var pending by mutableStateOf(emptyList<DeviceQueued>())
    private var drafts by mutableStateOf(emptyMap<DeviceRoomKey,DeviceRoomDraft>())
    private val draftEdits = mutableMapOf<DeviceRoomKey,DeviceRoomDraft>()
    private var hidden by mutableStateOf(emptySet<DeviceRoomKey>())
    private var retired by mutableStateOf(emptySet<DeviceRoomKey>())
    private var selection by mutableStateOf<AndroidDeviceRow?>(null)
    private var focused by mutableStateOf<DeviceLocalKey?>(null)
    private var bodies by mutableStateOf(emptyMap<DeviceRoomKey,List<SharedTurn>>())
    private var bodyEpoch = 0L
    private var trustedAddresses = emptySet<String>()
    private var savedProfiles = emptyList<DeviceProfile>()
    private val lastProbe = mutableMapOf<DeviceLocalKey,Long>()
    var draftEvent by mutableStateOf(0L)
        private set
    private var lastRevision: String? = null
    private var storageIssue = false
    private var preferenceRevision by mutableStateOf(0L)

    fun preferences(base: String, sid: String): ChatPreferenceHandle =
        preferenceHandleFor(DeviceLocalKey(base, sid), null)

    fun preferences(row: AndroidDeviceRow): ChatPreferenceHandle = preferenceHandleFor(localKey(row), row)

    private fun preferenceHandleFor(local: DeviceLocalKey?, row: AndroidDeviceRow?): ChatPreferenceHandle {
        preferenceRevision
        val grant = binding
        val paired = row != null || (bound || pendingJoin) && !local?.base.isNullOrBlank()
        fun room(): PreferenceRoom? {
            if (!bound || !paired) return null
            val key = if (local != null) links.singleOrNull { it.local == local }?.remote else row?.key
            return key?.takeIf { k -> k !in retired && directory.any { it.key == k } }?.let { PreferenceRoom(it.profile,it.sid) }
        }
        val target = room()
        val native = local?.let(::localSession)
        val ready = if (paired) target != null else native != null
        fun valid() = ready && if (paired) binding?.instance == grant?.instance && binding?.device == grant?.device && room() == target
            else local != null && localSession(local) != null && (local.base.isEmpty() || !bound && !pendingJoin)
        fun update(edit: (PreferenceJournal) -> PreferenceJournal): PreferenceJournal {
            val result = if (target != null) store.preferenceJournal(target, edit)
                else history.preferenceJournal(checkNotNull(local).base, local.sid, edit)
            preferenceRevision++
            return result
        }
        val snapshot = when {
            !ready -> PreferenceJournal()
            target != null -> store.preferenceJournal(target)
            else -> history.preferenceJournal(checkNotNull(local).base, local.sid)
        }
        val identity = org.json.JSONArray(listOf(if (paired) "device" else "direct", if (paired) grant?.instance else null,
            target?.profile ?: local?.base ?: row?.profile, target?.sid ?: local?.sid ?: row?.sid)).toString()
        val verified = target?.let { bodies[DeviceRoomKey(it.profile, it.sid)] }.orEmpty()
        return preferenceHandle(identity, snapshot, paired, ready, { index ->
            if (paired) verified.getOrNull(index)?.takeIf { it.status == "complete" }?.seq
            else native?.turns?.getOrNull(index)?.takeIf { it.status == "已完成" }?.let { index.toLong() + 1 }
        }, ::valid, ::update, { threshold ->
            var claimed = false
            if (target != null) action {
                if (valid()) {
                    val b = checkNotNull(binding)
                    synchronizePreferences(b,target)
                    if (store.preferenceJournal(target).value.remindAfter == threshold)
                        claimed = api.claimContextReminder(b,target.profile,target.sid,threshold)
                }
            }
            claimed
        }, { kind, through, active ->
            var claimed = false
            if (target != null) action {
                if (valid()) {
                    val current = store.preferenceJournal(target)
                    if (!current.contextPending && current.value.recentTurns == snapshot.value.recentTurns &&
                        current.value.pins == snapshot.value.pins)
                        claimed = api.observeContextNotice(checkNotNull(binding),target.profile,target.sid,kind,through,active)
                }
            }
            claimed
        }, { local?.let(::localSession)?.turns?.indexOfLast { it.status == "已完成" }?.toLong()?.plus(1) ?: 0L })
    }

    private suspend fun synchronizePreferences(b: DeviceBinding, room: PreferenceRoom) {
        var changed = false
        try {
            withContext(Dispatchers.IO) {
                syncPreferenceRoom(api,b,room) { edit ->
                    store.preferenceJournal(room) { old -> edit(old).also { if (it != old) changed = true } }
                }
            }
        } finally { if (changed) preferenceRevision++ }
    }

    init {
        try {
            store.grant()?.let { (b, ready) ->
                binding = b
                confirmed = ready
                if (ready) loadCache()
            }
            store.restoreDrafts(history)
            history.recoverUnqueuedMedia(pending.filter { it.media }.map { it.id }.toSet())
        } catch (_: Exception) {
            storageIssue = true
            notice = "设备绑定或缓存无法读取。请在设备设置中清理后重新配对。"
        }
    }

    private fun loadCache() {
        profiles = store.profiles()
        savedProfiles = profiles
        trustedAddresses = store.trustedAddresses() + listOfNotNull(binding?.address)
        directory = store.directory()
        links = store.links()
        binding?.let { b ->
            legacyReady = links.filter { it.legacyAlias }.mapNotNull { link ->
                val s = localSession(link.local)
                link.local.takeIf { s != null && legacy(b,s,trustedAddresses).idle }
            }.toSet()
        }
        pending = store.pending()
        drafts = store.drafts().associateBy { it.key }
        hidden = store.hidden()
        retired = store.retired()
    }

    private fun bases() = (app.connections.services.map { it.base } + history.legacyBases()).distinct().filter { it.isNotBlank() }
    private fun publishableBases() = bases().filter { runCatching { sharedAddress(it,false); wireBase(it) }.isSuccess }
    private fun name(base: String) = app.connections.services.find { it.base == base }?.name ?: base
    private fun own(base: String): DeviceProfile? {
        val wire = runCatching { wireBase(base) }.getOrNull() ?: return null
        return profiles.singleOrNull { it.originDevice == binding?.device &&
            it.username == history.user && it.sourceId == "android:$wire" && it.base == wire }
    }
    private fun localSession(key: DeviceLocalKey) = history.sessions(key.base).firstOrNull { it.id == key.sid }
    private fun canonical(key: DeviceLocalKey) = links.find { it.local == key }?.remote ?:
        DeviceRoomKey(own(key.base)?.profile ?: localProfile(key.base),key.sid)

    private fun nativeRow(base: String, s: MobileSession): AndroidDeviceRow {
        val remote = canonical(DeviceLocalKey(base,s.id))
        val entry = directory.find { it.key == remote }
        return AndroidDeviceRow(remote.profile, remote.sid, s.title,
            maxOf(s.turns.lastOrNull()?.createdAt ?: 0, entry?.activity ?: 0),
            name(base), history.user, base, if (remote in retired) RETIRED_REASON else entry?.reason.orEmpty())
    }

    val rows: List<AndroidDeviceRow> get() {
        val entries = linkedMapOf<DeviceRoomKey,AndroidDeviceRow>()
        for (base in bases()) {
            for (session in history.sessions(base)) {
                val row = nativeRow(base,session)
                val placeholder = drafts[row.key]?.takeIf { row.key !in retired }
                if (session.turns.isNotEmpty() || directory.any { it.key == row.key } || placeholder != null)
                    entries[row.key] = row.copy(activity = maxOf(row.activity,placeholder?.created ?: 0L))
            }
        }
        for (session in directory) {
            val p = profiles.find { it.profile == session.profile } ?: continue
            entries.putIfAbsent(session.key, AndroidDeviceRow(p.profile,session.sid,session.title,session.activity,
                p.name,p.username,null,session.reason))
        }
        for ((key, saved) in drafts) {
            if (key !in entries) deviceDraftRow(saved,profiles,retired)?.let { entries[key] = it }
        }
        return entries.values.filter { it.key !in hidden }.sortedWith(compareByDescending<AndroidDeviceRow> { it.activity }
            .thenBy { it.profile }.thenBy { it.sid })
    }

    val current: AndroidDeviceRow? get() = selection?.let { selected ->
        if (selected.nativeBase != null) {
            val k = localKey(selected)!!
            localSession(k)?.let { nativeRow(k.base,it) }
        } else {
            val entry = directory.find { it.key == selected.key }
            if (entry != null) selected.copy(title = entry.title,activity = entry.activity,reason = entry.reason)
            else if (selected.key in retired) selected.copy(reason = RETIRED_REASON)
                .takeIf { !(draftEdits[it.key] ?: drafts[it.key])?.text.isNullOrEmpty() }
            else selected.takeIf { drafts.containsKey(it.key) }
        }
    }
    private fun localKey(row: AndroidDeviceRow): DeviceLocalKey? = row.nativeBase?.let { base ->
        links.find { it.remote == row.key && it.local.base == base }?.local ?: DeviceLocalKey(base,row.sid)
    }
    val chat: MobileSession? get() = current?.let { row ->
        deviceMerge(localKey(row)?.let(::localSession), row, bodies[row.key].orEmpty(),
            (draftEdits[row.key] ?: drafts[row.key])?.text.orEmpty(),links.any { it.remote == row.key && it.legacyAlias })
    }
    val busy get() = current?.let { isBusy(it) } ?: false
    private fun isBusy(row: AndroidDeviceRow): Boolean = pending.any { it.key == row.key || (it.local != null && it.local == localKey(row)) } ||
        bodies[row.key]?.lastOrNull()?.status == "running" || localKey(row)?.let { app.delivery.busy(it.base,it.sid) } == true
    fun nativeBusy(base: String, sid: String) = localSession(DeviceLocalKey(base,sid))?.let { isBusy(nativeRow(base,it)) } == true
    fun managesLegacyNative(base: String, sid: String): Boolean {
        val k = DeviceLocalKey(base,sid)
        return bound && k in legacyReady &&
            links.any { it.local == k && it.legacyAlias && it.remote !in retired }
    }
    fun nativeChat(base: String, session: MobileSession): MobileSession {
        if (!bound) return session
        val row = nativeRow(base,session)
        return deviceMerge(session,row,bodies[row.key].orEmpty(),"",links.any { it.remote == row.key && it.legacyAlias })
    }

    fun focusNative(base: String, sid: String) {
        focused = DeviceLocalKey(base,sid)
        if (bound) { readBodies(); scope.launch { refresh() } }
    }
    private fun visibleKeys() = listOfNotNull(current?.key,focused?.let(::canonical)).distinct()
    private fun readBodies() {
        val keys = visibleKeys()
        val grant = binding ?: return
        val epoch = ++bodyEpoch
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { keys.associateWith(store::turns) }
                if (binding == grant && bodyEpoch == epoch) bodies = loaded
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (binding == grant) notice = "无法读取本机设备会话缓存。" }
        }
    }

    fun open(row: AndroidDeviceRow) {
        flushDrafts()
        selection = row
        localKey(row)?.let { key ->
            if (row.reason.isNotEmpty()) lastProbe.remove(key)
            app.connections.selectOffline(key.base)
            history.select(key.base,key.sid)
        }
        if (bound) { readBodies(); scope.launch { refresh() } }
    }
    fun newChat(profile: DeviceProfile) {
        if (!bound || profiles.none { it.profile == profile.profile }) return
        val nativeBase = bases().find { own(it)?.profile == profile.profile }
        val now = System.currentTimeMillis()
        val row = if (nativeBase != null) nativeRow(nativeBase,history.create(nativeBase))
            else AndroidDeviceRow(profile.profile,UUID.randomUUID().toString(),"新会话",now,profile.name,profile.username,null,"")
        store.draft(row.key,"",now)
        drafts = drafts + (row.key to DeviceRoomDraft(row.key,"",now))
        open(row)
    }
    fun closeChat() { flushDrafts(); selection = null }
    fun hide(row: AndroidDeviceRow) {
        if (!bound) return
        store.hide(row.key)
        hidden = hidden + row.key
        if (current?.key == row.key) closeChat()
    }

    fun editDraft(text: String) {
        val row = current ?: return
        localKey(row)?.let { history.draftText(it.base,it.sid,text); return }
        val now = drafts[row.key]?.created ?: System.currentTimeMillis()
        store.stageDraft(row.key,text,now)
        draftEdits[row.key] = DeviceRoomDraft(row.key,text,now)
    }
    fun flushDrafts() {
        if (draftEdits.isEmpty()) return
        store.flushDrafts()
        drafts = drafts + draftEdits
        draftEdits.clear()
    }
    fun addAssets(assets: List<MobileAsset>) { current?.let(::localKey)?.let { history.addAssets(it.base,it.sid,assets) } }
    fun removeAsset(asset: MobileAsset) { current?.let(::localKey)?.let { history.removeAsset(it.base,it.sid,asset) } }
    fun retryMedia(turn: MobileTurn, asset: MobileAsset) { current?.let(::localKey)?.let { app.retryMedia(it.base,it.sid,turn,asset) } }

    suspend fun preview(address: String, code: String) {
        val epoch = ++previewGeneration
        invitation = null
        preview = null
        action {
            if (binding != null || storageIssue) return@action
            val input = DevicePairCode(sharedAddress(address,false),code.trim())
            val result = api.preview(input.address,input.code)
            if (epoch != previewGeneration) return@action
            val counts = withContext(Dispatchers.IO) {
                val bases = publishableBases()
                bases.size to bases.sumOf { base -> history.sessions(base).count { s -> s.turns.any { deviceImport(it) != null } } }
            }
            if (epoch != previewGeneration) return@action
            phoneProfiles = counts.first
            phoneSessions = counts.second
            invitation = input
            preview = result
        }
    }
    fun cancelPreview() { previewGeneration++; preview = null; invitation = null }

    suspend fun join() = action {
        if (binding != null || storageIssue) return@action
        val input = invitation ?: return@action
        val approval = preview ?: return@action
        val activeNative = publishableBases().any { base -> history.sessions(base).any { app.delivery.busy(base,it.id) } }
        if (activeNative) { notice = "请等待当前本机发送完成后确认配对。"; return@action }
        val b = DeviceBinding.create(input.address,approval.instance)
        store.saveGrant(b,false)
        binding = b
        cancelPreview()
        try {
            api.join(b,input.code,history.user,phoneProfiles,phoneSessions)
        } catch (e: SharedHttpFailure) {
            if (e.status in listOf(400,403,410,422)) { clear(); notice = "邀请无效或已过期，请在电脑上重新生成。"; return@action }
            throw e
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // The one-use code may already be consumed. Only query with the identity saved before join.
            api.me(b)
        }
        confirm(b)
        synchronize(b)
    }
    private fun confirm(b: DeviceBinding) {
        store.saveGrant(b,true)
        trustedAddresses = trustedAddresses + b.address
        confirmed = true
        online = true
    }
    suspend fun changeAddress(address: String) = action {
        val b = binding ?: return@action
        val verified = api.verifyAddress(b,address)
        store.saveGrant(verified,confirmed)
        if (confirmed) trustedAddresses = trustedAddresses + verified.address
        binding = verified
        online = true
    }
    suspend fun disconnect() = lock.withLock { clear("已断开此设备。") }
    private fun clear(message: String = "") {
        val held = pending.filter { it.media }
        binding = null; confirmed = false; online = false; selection = null; profiles = emptyList()
        directory = emptyList(); pending = emptyList(); links = emptyList(); drafts = emptyMap()
        hidden = emptySet(); retired = emptySet(); bodies = emptyMap(); draftEdits.clear(); lastRevision = null
        bodyEpoch++; trustedAddresses = emptySet(); savedProfiles = emptyList(); lastProbe.clear(); legacyReady = emptySet()
        cancelPreview()
        storageIssue = true
        try {
            store.forget(history)
            store.restoreDrafts(history)
            storageIssue = false
            notice = message + "已清理远端缓存。未发送文字可在最近对话的本机草稿中查看。"
        } catch (_: Exception) {
            notice = "设备访问已关闭。本机清理或草稿恢复尚未完成，请检查存储空间并在设备设置中再次清理。"
        }
        held.forEach { runCatching { finishUnsentMedia(it,"设备绑定已结束。") } }
        draftEvent++
    }

    private suspend fun action(block: suspend () -> Unit) = lock.withLock {
        syncing = true
        try {
            notice = ""
            block()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            online = false
            if (e is SharedHttpFailure && e.status == 401 && confirmed) {
                clear("电脑已撤销此设备。")
            } else notice = when {
                pendingJoin -> "正在按原设备身份核对配对结果。请连接原服务，或在设备设置中清理后重新配对。"
                e is SharedHttpFailure -> "设备服务返回 HTTP ${e.status}，请检查连接和授权。"
                else -> "设备同步暂未完成，请检查网络、服务地址和证书。"
            }
        } finally { syncing = false }
    }

    suspend fun refresh() {
        if (binding == null || lock.isLocked) return
        action {
            val b = binding ?: return@action
            if (!confirmed) { api.me(b); confirm(b) }
            val fresh = api.refreshAddress(b)
            if (fresh != b) { store.saveGrant(fresh,true); trustedAddresses = trustedAddresses + fresh.address; binding = fresh }
            synchronize(fresh)
        }
    }

    private suspend fun synchronize(b: DeviceBinding) {
        var ps = api.profiles(b)
        val published = ps.toMutableList()
        for (base in withContext(Dispatchers.IO) { publishableBases() }) {
            val current = published.singleOrNull { it.originDevice == b.device && it.sourceId == sourceId(base) &&
                it.username == history.user && it.base == wireBase(base) }
            val p = if (current == null || current.name != name(base) || !current.available) {
                api.profile(b,sourceId(base),wireBase(base),history.user,name(base)).also { next ->
                    published.removeAll { it.profile == next.profile }
                    published.add(next)
                }
            } else current
            for (s in withContext(Dispatchers.IO) { history.sessions(base) }) {
                if (app.delivery.busy(base,s.id)) continue
                val local = DeviceLocalKey(base,s.id)
                val prev = links.find { it.local == local }
                if (prev?.remote in retired) continue
                val trusted = trustedAddresses
                val legacy = withContext(Dispatchers.IO) { legacy(b,s,trusted) }
                if (legacy.idle) {
                    if (local !in legacyReady) legacyReady = legacyReady + local
                } else if (local in legacyReady) legacyReady = legacyReady - local
                val records = withContext(Dispatchers.IO) {
                    val owned = store.owned(base,s.id)
                    s.turns.filterNot { it.id in owned }.mapNotNull(::deviceImport)
                }
                val priorEntry = directory.find { it.key == prev?.remote }
                val now = SystemClock.elapsedRealtime()
                val recoverAuthority = legacy.idle && prev?.legacyAlias == false
                val recheck = p.available && (!priorEntry?.reason.isNullOrEmpty() || recoverAuthority) &&
                    (local !in lastProbe || now - lastProbe.getValue(local) >= 60_000L)
                if (!recheck && prev?.let { it.count == records.size && it.title == s.title } == true) continue
                val proof = legacy.token
                if (records.isEmpty() && (proof == null || proof == "unverified")) continue
                try {
                    lastProbe[local] = now
                    val receipt = api.session(b,p.profile,s.id,s.title,s.turns.maxOfOrNull { it.createdAt } ?: 0,
                        records.size,proof)
                    val key = receipt.key
                    // Verify any prefix whose local acknowledgement was lost. The server can
                    // already be farther ahead than this batch; each replay must still match.
                    var offset = minOf(receipt.imported,prev?.count ?: 0)
                    while (key.profile == p.profile && key.sid == s.id && offset < records.size) {
                        val batch = deviceImportBatch(records,offset)
                        val end = api.importTurns(b,key.profile,key.sid,offset,batch)
                        offset += batch.size
                        check(end >= offset)
                    }
                    saveLink(DeviceImportLink(local,key,records.size,s.title,
                        legacyAlias = proof != null && proof != "unverified" && receipt.reason.isEmpty()))
                } catch (e: SharedHttpFailure) {
                    if (e.status !in listOf(400,403,409,413,422)) throw e
                    notice = "部分本机历史未获接纳（HTTP ${e.status}），原记录仍保留。"
                }
            }
        }
        ps = published
        profiles = ps
        for (op in pending.toList()) process(b,op)
        val all = mutableListOf<DeviceSession>()
        var revision: String? = null
        var directoryChanged = false
        var more: Boolean
        do {
            val page = api.sessions(b,all.size,100,revision)
            if (revision == null && page.revision == lastRevision) {
                all.addAll(directory)
                revision = page.revision
                break
            }
            if (revision == null) revision = page.revision else check(revision == page.revision)
            all.addAll(page.sessions)
            more = page.more
        } while (more)
        if (lastRevision != revision || ps != savedProfiles || all != directory) {
            withContext(Dispatchers.IO) { store.replaceDirectory(ps,all) }
            retired = withContext(Dispatchers.IO) { store.retired() }
            directory = all
            lastRevision = revision
            savedProfiles = ps
            directoryChanged = true
        }
        var changed = directoryChanged
        val running = withContext(Dispatchers.IO) { store.running() }
        for (key in (visibleKeys() + running).distinct()) if (all.any { it.key == key }) {
            if (syncBody(b,key)) changed = true
        }
        if (changed) readBodies()
        val visible = visibleKeys().toSet()
        val dirty = withContext(Dispatchers.IO) { store.pendingPreferenceRooms() }.toMutableSet()
        for (entry in all) {
            val room = PreferenceRoom(entry.profile, entry.sid)
            val source = PreferenceRoom(entry.profile,"")
            if (entry.key !in visible && room !in dirty && source !in dirty) continue
            try {
                synchronizePreferences(b, room)
                dirty.remove(source)
            } catch (e: SharedHttpFailure) {
                if (e.status == 401 || e.status >= 500) throw e
                notice = "对话设置暂未同步（HTTP ${e.status}）。"
            }
        }
        online = true
    }

    private data class LegacyRoom(val token: String?,val idle: Boolean)
    private fun legacy(b: DeviceBinding, session: MobileSession, trusted: Set<String>): LegacyRoom {
        val record = AndroidSharedStorage(context,session.sharedBase,session.id)
        if (!record.hasState()) return LegacyRoom(null,false)
        val state = runCatching { SharedLocal.decode(record.load(),false) }.getOrNull()
        val proof = legacyOwnerProof(state,b.instance,trusted)
        return LegacyRoom(proof ?: "unverified",proof != null)
    }

    private suspend fun saveLink(link: DeviceImportLink) {
        withContext(Dispatchers.IO) { store.link(link) }
        links = links.filterNot { it.local == link.local } + link
    }

    private suspend fun syncBody(b: DeviceBinding, key: DeviceRoomKey): Boolean {
        var changed = false
        var after = withContext(Dispatchers.IO) { store.cursor(key) }
        var prefix = after
        var terminal = true
        do {
            val page = api.turns(b,key.profile,key.sid,after)
            for (turn in page.turns) {
                after = turn.seq
                if (turn.status == "running") terminal = false
                if (terminal) prefix = after
            }
            if (page.turns.isNotEmpty()) {
                changed = true
                links.firstOrNull { it.remote == key }?.local?.let { local ->
                    withContext(Dispatchers.IO) {
                        val owned = store.owned(local.base,local.sid)
                        page.turns.filter { it.id in owned }.forEach { updateNative(local,it) }
                    }
                }
                withContext(Dispatchers.IO) { store.saveTurns(key,page.turns,prefix) }
            }
        } while (page.more)
        return changed
    }

    private fun updateNative(key: DeviceLocalKey, turn: SharedTurn) {
        val s = localSession(key) ?: return
        val old = s.turns.find { it.id == turn.id } ?: return
        if (old.sent.isNotEmpty() || old.replies.isNotEmpty()) return
        val updated = turn.mobile().copy(question = old.question,createdAt = old.createdAt)
        if (old != updated) history.update(key.base,key.sid,updated)
    }

    suspend fun previewContext(row: AndroidDeviceRow, question: String): AstrBotContextPreview {
        val grant = binding ?: return AstrBotContextPreview.unavailable(CONTEXT_UNAVAILABLE)
        val handle = preferences(row)
        if (!bound || !handle.ready || handle.contextPending)
            return AstrBotContextPreview.unavailable("请先同步并核对这段会话的上下文设置。")
        val preview = api.contextPreview(grant, row.profile, row.sid, question)
        return if (binding == grant && preferences(row).identity == handle.identity) preview
               else AstrBotContextPreview.unavailable(CONTEXT_CHANGED)
    }

    suspend fun previewContext(base: String, session: MobileSession, question: String) =
        previewContext(nativeRow(base, session), question)

    fun send(heart: LiveHeartRate?): Boolean {
        val row = current ?: return false
        val s = chat ?: return false
        return enqueue(row,s.draft,heart,true)
    }
    fun sendNative(base: String, session: MobileSession, draft: MobileDraft, heart: LiveHeartRate?, consume: Boolean,
                   preview: AstrBotContextPreview? = null): Boolean =
        enqueue(nativeRow(base,session),draft,heart,consume,preview)

    fun sendReviewed(row: AndroidDeviceRow, draft: MobileDraft, prefs: ConversationPreferences,
                     preview: AstrBotContextPreview, consume: Boolean = true): Boolean {
        if (current?.key != row.key || !preferences(row).value.sameContext(prefs) ||
            consume && chat?.draft != draft) {
            notice = CONTEXT_CHANGED
            return false
        }
        return enqueue(row, draft, null, consume, preview)
    }

    private fun enqueue(row: AndroidDeviceRow, draft: MobileDraft, heart: LiveHeartRate?, consume: Boolean,
                        preview: AstrBotContextPreview? = null): Boolean {
        if (!bound || isBusy(row)) return false
        flushDrafts()
        history.flushDrafts()
        fun reject(text: String): Boolean {
            notice = text
            localKey(row)?.let { app.showNote(it.base,it.sid,text) }
            return false
        }
        if (row.reason.isNotBlank()) return reject(row.reason)
        val local = localKey(row)
        val native = local?.let(::localSession)
        if (local != null && native == null) return false
        if (native != null && app.legacyStorage(native).hasState() && !managesLegacyNative(local.base,local.sid))
            return reject("旧版共享记录为只读，可新建会话继续聊天。")
        val text = draft.text.trim()
        if (text.startsWith("/task") && (text.length == 5 || text[5].isWhitespace()))
            return reject("请在电脑 Launcher 档案中执行 /task。")
        val hasMedia = draft.attachments.isNotEmpty()
        if (text.isBlank() && !hasMedia) return false
        val prefs = preferences(row)
        if (prefs.contextPending)
            return reject("上下文设置尚未同步，请先核对。")
        val custom = prefs.value.recentTurns != null
        if (custom && hasMedia) return reject(CUSTOM_CONTEXT_MEDIA_NOTICE)
        if (custom != (preview != null) || preview != null && (!preview.canSend || preview.networkMessage == null ||
                !prefs.ready)) return reject(CONTEXT_CHANGED)
        val prompt = preview?.networkMessage ?: heart?.appendTo(text) ?: text
        val question = if (hasMedia) deviceMediaText(prompt,draft.attachments) else prompt
        if (deviceQuestionTooLarge(question) || deviceQuestionTooLarge(draft.text)) return reject("提问超过 64 KiB，请缩短后发送。")
        val p = profiles.find { it.profile == row.profile }
        if (p != null && !p.available) return reject("此来源尚未在电脑上配置上游，当前可阅读历史。")
        val token = app.connections.token().orEmpty()
        if (hasMedia) {
            if (local == null || local.base != app.connections.base || (p != null && p.originDevice != binding?.device))
                return reject("请在会话所属档案的来源设备发送附件。")
            mobileSendIssue(local.base,local.sid,token,history.user,text,draft.attachments)?.let { return reject(it) }
        }
        val exists = directory.any { it.key == row.key } || bodies[row.key]?.isNotEmpty() == true ||
            native?.turns?.any { deviceImport(it) != null } == true ||
            links.any { it.remote == row.key }
        val id = UUID.randomUUID().toString()
        val op = DeviceQueued(row.key,id,question,draft.text.takeIf { consume },local,!exists,hasMedia,native?.draftRevision ?: 0L,
            authored = draft.text,mediaKeyId = app.connections.keyId.takeIf { hasMedia },nativePrompt = prompt.takeIf { hasMedia },
            contextRevision = preview?.revision)
        try {
            if (hasMedia && local != null) {
                // The native row holds the original attachment references while the reservation
                // is outstanding, even if the user edits/removes the same assets in the draft.
                history.begin(local.base,local.sid,MobileTurn(id,text,status = DEVICE_MEDIA_WAITING,sent = draft.attachments),
                    consumeDraft = false)
            }
            store.begin(op)
        } catch (_: Exception) {
            store.release(id)
            if (hasMedia) finishUnsentMedia(op,"无法保存附件的设备发送编号。")
            return reject("无法保存本次发送，消息未发出。")
        }
        pending = pending + op
        scope.launch { action {
            val b = binding ?: return@action
            if (op.key.profile.startsWith("local:")) synchronize(b) else process(b,op)
        } }
        return true
    }

    private suspend fun process(b: DeviceBinding, queued: DeviceQueued) {
        if (pending.none { it.id == queued.id }) return
        var op = withContext(Dispatchers.IO) { store.pending().firstOrNull { it.id == queued.id } } ?: return
        if (op.key.profile.startsWith("local:")) {
            val local = op.local ?: return
            val key = canonical(local)
            if (key.profile.startsWith("local:")) return
            store.move(op.id,key)
            op = op.copy(key = key)
            pending = store.pending()
        }
        var found: DeviceOperation? = null
        var checked = false
        try {
            found = try { api.operation(b,op.id) } catch (e: SharedHttpFailure) {
                if (e.status != 404) throw e
                null
            }
            checked = true
            val local = op.local
            val native = local?.let(::localSession)
            if (found == null && native != null && app.legacyStorage(native).hasState() &&
                !managesLegacyNative(local.base,local.sid)) return
            if (op.media) { processMedia(b,op,found); return }
            val receipt = found ?: if (op.start) api.start(b,op.key.profile,op.key.sid,op.id,op.question)
                else api.send(b,op.key.profile,op.key.sid,op.id,op.question,op.contextRevision)
            acknowledge(op,receipt)
        } catch (_: DeviceContextAwaiting) {
            notice = "正在等待 AstrBot 确认本次上下文。"
            return
        } catch (e: SharedHttpFailure) {
            if (!checked && e.conflict != SharedConflict.CONTEXT_REJECTED) throw e
            val accepted = found != null || op.media && store.pending().any { it.id == op.id && it.dispatched }
            when (deviceSendFailure(e,accepted)) {
                DeviceSendFailure.KEEP_PENDING -> throw e
                DeviceSendFailure.WAIT_FOR_ROOM -> {
                    notice = "另一台设备正在回复。本次消息会按原编号继续发送。"
                    return
                }
                DeviceSendFailure.REJECTED -> Unit
            }
            val reason = if (e.conflict == SharedConflict.CONTEXT_REJECTED) "上游未派发本次上下文，请重新预览。"
                else "服务拒绝本次发送（HTTP ${e.status}）。"
            if (op.media) finishUnsentMedia(op,reason)
            val draftText = op.local?.let(::localSession)?.draft?.text ?: (draftEdits[op.key] ?: drafts[op.key])?.text
            store.reject(op,!op.media && draftText != op.authored)
            store.restoreDrafts(history)
            removePending(op)
            notice = "${reason}草稿已保留。"
            op.local?.let { app.showNote(it.base,it.sid,notice) }
        }
    }

    private fun checkReceipt(op: DeviceQueued, received: DeviceOperation) {
        check(received.profile == op.key.profile && received.sid == op.key.sid &&
            received.turn.id == op.id && received.turn.question == op.question)
    }

    private suspend fun acknowledge(op: DeviceQueued, received: DeviceOperation) {
        checkReceipt(op,received)
        flushDrafts()
        val turn = received.turn
        val now = System.currentTimeMillis()
        val sequence = turn.seq.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val previous = directory.find { it.key == op.key }
        val entry = previous?.copy(activity = maxOf(previous.activity,now),total = maxOf(previous.total,sequence),
            imported = maxOf(previous.imported,sequence)) ?: DeviceSession(op.key.profile,op.key.sid,
            turn.question.take(80),now,sequence,sequence,"",binding?.device.orEmpty())
        withContext(Dispatchers.IO) { store.saveAccepted(entry,turn) }
        directory = directory.filterNot { it.key == op.key } + entry
        lastRevision = null
        val local = op.local
        if (local != null) {
            val s = withContext(Dispatchers.IO) {
                val native = localSession(local)
                if (!op.media && native != null && native.turns.none { it.id == op.id }) {
                    val original = op.original ?: turn.question
                    history.begin(local.base,local.sid,turn.mobile().copy(question = original.trim(),createdAt = System.currentTimeMillis()),
                        consumeDraft = op.original != null,expectedDraftRevision = op.revision)
                } else if (!op.media) updateNative(local,turn)
                native
            }
            if (links.none { it.local == local }) saveLink(DeviceImportLink(local,op.key,0,s?.title.orEmpty()))
        } else {
            // Storage and the service both suspended. Flush any later IME edit, including
            // editing back to identical text, immediately before testing the consumed snapshot.
            flushDrafts()
            if (op.original != null && drafts[op.key]?.text == op.original &&
                store.pending().find { it.id == op.id }?.original != null) {
                val created = drafts.getValue(op.key).created
                store.draft(op.key,"",created)
                drafts = drafts + (op.key to DeviceRoomDraft(op.key,"",created))
            }
        }
        release(op)
        readBodies()
        online = true
        draftEvent++
    }
    private fun release(op: DeviceQueued) {
        store.release(op.id)
        removePending(op)
    }
    private fun removePending(op: DeviceQueued) {
        pending = pending.filterNot { it.id == op.id }
    }
    private fun finishUnsentMedia(op: DeviceQueued, explanation: String) {
        val local = op.local ?: return
        val held = localSession(local)?.turns?.firstOrNull { it.id == op.id && it.status == DEVICE_MEDIA_WAITING } ?: return
        history.update(local.base,local.sid,held.copy(status = "未发送：$explanation"))
    }

    private suspend fun processMedia(b: DeviceBinding, op: DeviceQueued, found: DeviceOperation?) {
        val local = checkNotNull(op.local)
        if (app.delivery.busy(local.base,local.sid)) return
        if (!op.dispatched) {
            val native = localSession(local)
            val held = native?.turns?.firstOrNull { it.id == op.id }
            val prompt = op.nativePrompt ?: op.authored.trim()
            if (held == null || held.status != DEVICE_MEDIA_WAITING || held.sent.isEmpty() ||
                held.question != op.authored.trim() || deviceMediaText(prompt,held.sent) != op.question) {
                notice = "正在保留附件待发记录。请核对本机原会话和原附件。"
                return
            }
            val key = op.mediaKeyId
            val token = key?.let { withContext(Dispatchers.IO) { app.connections.savedToken(local.base,it) } }
            if (token.isNullOrBlank()) {
                notice = "附件仍在等待原档案的命名密钥。请恢复发送时使用的那把密钥。"
                return
            }
            val issue = mobileSendIssue(local.base,local.sid,token,history.user,held.question,held.sent)
            if (issue != null) { notice = "附件待发：$issue"; return }
            // Repeating this same action verifies the receipt belongs to a media reservation,
            // including after the initial reply was lost. The durable local CAS owns native POST.
            val reserved = api.beginMedia(b,op.key.profile,op.key.sid,op.id,op.question)
            checkReceipt(op,reserved)
            if (reserved.turn.status != "running") {
                notice = "附件保留记录已有服务终态，原件仍在手机。请先核对该轮记录。"
                return
            }
            // Key removal/profile deletion may have happened while the reservation request suspended.
            if (app.connections.services.none { service -> service.base == local.base && service.keys.any { it.id == key } }) {
                notice = "发送时的档案或命名密钥已移除。原附件与操作编号保留在手机。"
                return
            }
            if (!store.claimMedia(op.id)) return
            app.delivery.send(local.base,local.sid,token,history.user,MobileDraft(op.authored,held.sent),null,
                consumeDraft = op.original != null && localSession(local)?.draftRevision == op.revision,
                turnId = op.id, prepared = true, networkQuestion = prompt,
                onFinished = { scope.launch { action { binding?.let { process(it,op) } } } }) {
                app.showNote(local.base,local.sid,it)
            }
            if (app.delivery.busy(local.base,local.sid)) return
        } else if (found == null) {
            notice = "找不到已派发附件的原服务记录。手机已保留原件，请核对服务数据库。"
            return
        }
        finishUnsentMedia(op,"上次保留的发送许可已结束，请先核对该轮设备状态。")
        val completed = localSession(local)?.turns?.find { it.id == op.id }
        val status = op.finalStatus ?: if (completed?.status == "已完成") "complete" else "unknown"
        val answer = op.finalAnswer ?: deviceMediaText(completed?.answer.orEmpty(),completed?.replies.orEmpty())
        if (op.finalStatus == null) withContext(Dispatchers.IO) { store.mediaResult(op.id,status,answer) }
        val result = api.endMedia(b,op.key.profile,op.key.sid,op.id,answer,status)
        check(result.turn.status == status && result.turn.answer == answer)
        acknowledge(op,result)
    }
}
