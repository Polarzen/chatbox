package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class Entry(
    val question: String,
    val answer: String,
    val status: String,
    val attachmentReference: String? = null,
    val attachmentDisplayName: String? = null,
    val replyAttachments: List<ReplyAttachment> = emptyList(),
    val attachmentReferences: List<String> = attachmentReference?.let(::listOf) ?: emptyList(),
)

internal data class Chat(val session: String, val entries: List<Entry> = emptyList())

internal data class SessionKey(val profileId: String, val sessionId: String)

internal data class ReplyDestination(val key: SessionKey, val entryIndex: Int)

internal data class ComposerDraft(val text: String = "", val attachments: List<Path> = emptyList())

internal class DesktopHistoryCorruption(message: String, cause: Exception? = null) : IllegalArgumentException(message, cause)

internal data class Profile(
    val id: String,
    val address: String,
    val kind: String,
    val offlineOnly: Boolean,
    val currentSession: String,
)

internal data class DesktopHistoryPosition(val prefix: Int, val after: Long, val pending: SharedPending?)

internal data class SessionInfo(
    val id: String,
    val entryCount: Int,
    val selected: Boolean,
    val title: String,
    val manualName: String? = null,
    val activity: Long = 0,
)

/** Durable local chat storage. Profile IDs, endpoint addresses, and server session IDs are distinct. */
internal class DesktopStore(private val file: Path) {
    private var state: JSONObject
    val profileRequests = kotlinx.coroutines.sync.Mutex()
    private val draftClock = AtomicLong()
    private val appliedDraftRevisions = mutableMapOf<SessionKey, Long>()
    val managedMediaDirectory: Path = file.toAbsolutePath().normalize().parent.resolve("composer-media")

    fun nextDraftRevision(): Long = draftClock.incrementAndGet()

    init {
        state = if (!Files.exists(file)) {
            emptyState()
        } else {
            val loaded = JSONObject(Files.readString(file))
            when {
                loaded.optInt("schemaVersion") == SCHEMA_VERSION -> {
                    validateCurrentState(loaded)
                    if (loaded.optInt("sessionOrderVersion") < SESSION_ORDER_VERSION) {
                        normalizeLegacySessionOrder(loaded)
                        loaded.put("sessionOrderVersion", SESSION_ORDER_VERSION)
                        writeAtomic(loaded)
                    }
                    normalizePendingStatuses(loaded)
                    loaded
                }
                loaded.has("servers") && !loaded.has("schemaVersion") -> {
                    val migrated = migrateLegacy(loaded)
                    normalizeLegacySessionOrder(migrated)
                    migrated.put("sessionOrderVersion", SESSION_ORDER_VERSION)
                    backupPriorHistory()
                    writeAtomic(migrated)
                    migrated
                }
                else -> error("Unsupported or invalid local history format")
            }
        }
    }

    val username: String = state.optString("username").ifBlank { newUsername() }

    val activeProfileId: String
        @Synchronized get() = state.optString("activeProfileId")

    /** The compatibility address for the active sendable profile; offline legacy profiles yield blank. */
    val lastServer: String
        @Synchronized get() = findProfile(state, state.optString("activeProfileId"))
            ?.takeUnless { it.optBoolean("offlineOnly") }
            ?.optString("address")
            .orEmpty()

    val profiles: List<Profile>
        @Synchronized get() = profileArray(state).let { rows ->
            (0 until rows.length()).map { profileFrom(rows.getJSONObject(it)) }
        }

    @Synchronized
    fun profile(profileId: String): Profile? = findProfile(state, profileId)?.let(::profileFrom)

    @Synchronized
    fun sessions(profileId: String): List<SessionInfo> {
        val profile = requireProfile(state, profileId)
        val current = profile.optString("currentSession")
        val rows = profile.getJSONArray("sessions")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val manualName = manualSessionName(row)
            SessionInfo(
                row.getString("id"),
                row.getJSONArray("entries").length(),
                row.getString("id") == current,
                manualName ?: sessionTitle(row),
                manualName,
                sessionTimestamp(row, "lastActivityAt") ?: sessionTimestamp(row, "lastUsedAt") ?: sessionTimestamp(row, "createdAt") ?: 0L,
            )
        }
    }

    /** Only persist() publishes new state, so the captured session rows can be read off-lock. */
    fun historySearchSnapshot(
        profileId: String,
        cancelled: () -> Boolean = { false },
    ): HistorySearchSnapshot {
        val rows = synchronized(this) { requireProfile(state, profileId).getJSONArray("sessions") }
        return HistorySearchSnapshot(profileId, buildList {
            for (index in 0 until rows.length()) {
                checkHistorySearchCancellation(cancelled)
                val row = rows.getJSONObject(index)
                val title = manualSessionName(row) ?: sessionTitle(row)
                val entries = row.getJSONArray("entries")
                val textEntries = buildList {
                    for (entryIndex in 0 until entries.length()) {
                        checkHistorySearchCancellation(cancelled)
                        val entry = entries.getJSONObject(entryIndex)
                        add(SearchEntry(entry.getString("question"), entry.getString("answer")))
                    }
                }
                add(SearchSession(row.getString("id"), title, textEntries))
            }
        })
    }

    private fun manualSessionName(session: JSONObject): String? =
        (session.opt("manualName") as? String)?.takeIf { it.isNotBlank() }

    private fun sessionTitle(session: JSONObject): String =
        session.optString("deviceTitle").takeIf { it.isNotBlank() } ?: defaultSessionTitle(session)

    /** Finds or creates a manual profile by normalized address and selects its current session. */
    @Synchronized
    fun read(server: String): Chat {
        if (server.isBlank()) return Chat(UUID.randomUUID().toString())
        val address = normalizeAddress(server)
        var chat: Chat? = null
        persist { copy ->
            val profile = findManualProfileByAddress(copy, address)
                ?: createProfileInState(copy, address, "manual:$address")
            chat = readProfileInternal(profile, null)
            copy.put("activeProfileId", profile.getString("id"))
        }
        return checkNotNull(chat)
    }

    @Synchronized
    fun readProfile(profileId: String, sessionId: String? = null): Chat {
        var chat: Chat? = null
        persist { copy ->
            val profile = requireProfile(copy, profileId)
            chat = readProfileInternal(profile, sessionId)
            copy.put("activeProfileId", profileId)
        }
        return checkNotNull(chat)
    }

    @Synchronized
    fun createProfile(address: String): Profile {
        val normalized = normalizeAddress(address)
        return ensureProfile("manual:${UUID.randomUUID()}", normalized)
    }

    @Synchronized
    fun ensureProfile(profileId: String, address: String): Profile {
        require(profileId.isNotBlank()) { "Profile identity is required" }
        val normalized = normalizeAddress(address)
        persist { copy ->
            val existing = findProfile(copy, profileId)
            if (existing == null) {
                val kind = if (profileId.startsWith("launcher:")) "launcher" else "manual"
                val sessionId = UUID.randomUUID().toString()
                val session = newSession(sessionId)
                profileArray(copy).put(JSONObject()
                    .put("id", profileId)
                    .put("address", normalized)
                    .put("kind", kind)
                    .put("offlineOnly", false)
                    .put("currentSession", sessionId)
                    .put("sessions", JSONArray().put(session)))
            } else {
                require(!existing.optBoolean("offlineOnly")) { "Legacy offline profiles must be explicitly adopted first" }
                existing.put("address", normalized)
            }
            copy.put("activeProfileId", profileId)
        }
        return profile(profileId)!!
    }

    @Synchronized
    fun updateProfileAddress(profileId: String, address: String): Profile {
        val normalized = normalizeAddress(address)
        persist { copy ->
            val profile = requireProfile(copy, profileId)
            require(!profile.optBoolean("offlineOnly")) { "Legacy offline profiles must be explicitly adopted first" }
            profile.put("address", normalized)
            copy.put("activeProfileId", profileId)
        }
        return profile(profileId)!!
    }

    @Synchronized
    fun selectProfile(profileId: String) {
        requireProfile(state, profileId)
        persist { copy ->
            requireProfile(copy, profileId)
            copy.put("activeProfileId", profileId)
        }
    }

    @Synchronized
    fun selectSession(profileId: String, sessionId: String) {
        persist { copy ->
            val profile = requireProfile(copy, profileId)
            require(findSession(profile, sessionId) != null) { "Unknown session" }
            profile.put("currentSession", sessionId)
            copy.put("activeProfileId", profileId)
        }
    }

    @Synchronized
    fun selectReplyIfPresent(target: ReplyDestination): Boolean {
        val (profileId, sessionId) = target.key
        val profile = findProfile(state, profileId) ?: return false
        val session = findSession(profile, sessionId) ?: return false
        val entry = session.getJSONArray("entries").optJSONObject(target.entryIndex) ?: return false
        if (entry.getString("status") != "已完成") return false
        if (activeProfileId != profileId || profile.getString("currentSession") != sessionId) {
            persist(requireAtomic = true) { copy ->
                requireProfile(copy, profileId).put("currentSession", sessionId)
                copy.put("activeProfileId", profileId)
            }
        }
        return true
    }

    /** Sets the local display name, or restores the original title when name is null. */
    @Synchronized
    fun renameSession(profileId: String, sessionId: String, name: String?) {
        val trimmed = name?.trim()
        require(trimmed == null || trimmed.isNotEmpty()) { "Session name cannot be blank" }
        persist { copy ->
            val profile = requireProfile(copy, profileId)
            val session = findSession(profile, sessionId) ?: error("Unknown session")
            if (trimmed == null) session.remove("manualName") else session.put("manualName", trimmed)
            if (profile.has("deviceProfile") && profile.optString("kind") != "device" && !session.optBoolean("deviceRemote") &&
                !session.has("deviceAuthority")) {
                session.remove("deviceTitle")
                session.put("deviceRename", true)
            }
        }
    }

    /** Deletes only the requested local session and returns the selected session after deletion. */
    @Synchronized
    fun deleteSession(profileId: String, sessionId: String): Chat {
        var selected: Chat? = null
        persist { copy ->
            val profile = requireProfile(copy, profileId)
            val sessions = profile.getJSONArray("sessions")
            val deletedIndex = indexOfSession(profile, sessionId)
            val wasCurrent = profile.optString("currentSession") == sessionId
            require(deletedIndex >= 0) { "Unknown session" }
            val row = sessions.getJSONObject(deletedIndex)
            val unverifiedRoom = hasLegacyRoomField(row) && !row.has("deviceAuthority") &&
                (!row.has("deviceSid") || row.optString("deviceReason").isNotEmpty())
            if (profile.has("deviceProfile") && !unverifiedRoom) {
                val sid = row.optString("deviceSid", sessionId)
                if (row.has("deviceSid") || row.optJSONArray("deviceQueue")?.length()?.let { it > 0 } == true ||
                    row.getJSONArray("entries").length() > 0) {
                    val owner = if (!row.has("deviceAuthority")) profile else {
                        val all = profileArray(copy)
                        (0 until all.length()).map { all.getJSONObject(it) }.single {
                            it.optJSONObject("deviceProfile")?.optString("profile") == row.getString("deviceAuthority")
                        }
                    }
                    val removed = owner.optJSONObject("deviceRemoved") ?: JSONObject().also { owner.put("deviceRemoved", it) }
                    removed.put(sid, true)
                }
            } else if (profile.has("profileAuthorization") || hasLegacyRoomField(row)) {
                // Keep a local deletion hidden if this database is later paired through the device wizard.
                val hidden = profile.optJSONObject("profileRemoved") ?: JSONObject().also { profile.put("profileRemoved", it) }
                hidden.put(sessionId, true)
            }
            sessions.remove(deletedIndex)

            if (wasCurrent) {
                if (sessions.length() == 0) {
                    profile.put("currentSession", "")
                    selected = Chat("")
                } else {
                    // The item now at the removed index is the next displayed session; at the end,
                    // select the previous one.
                    val replacement = sessions.getJSONObject(deletedIndex.coerceAtMost(sessions.length() - 1))
                    profile.put("currentSession", replacement.getString("id"))
                    selected = chatFrom(replacement)
                }
            } else {
                val current = findSession(profile, profile.optString("currentSession"))
                if (current == null) {
                    if (sessions.length() == 0) {
                        profile.put("currentSession", "")
                        selected = Chat("")
                    } else {
                        val replacement = sessions.getJSONObject(0)
                        profile.put("currentSession", replacement.getString("id"))
                        selected = chatFrom(replacement)
                    }
                } else {
                    selected = chatFrom(current)
                }
            }
        }
        appliedDraftRevisions.remove(SessionKey(profileId, sessionId))
        return checkNotNull(selected)
    }

    @Synchronized
    fun readDraft(key: SessionKey): ComposerDraft {
        val profile = findProfile(state, key.profileId) ?: return ComposerDraft()
        val draft = findSession(profile, key.sessionId)?.optJSONObject("draft") ?: return ComposerDraft()
        val attachments = draft.getJSONArray("attachments")
        return ComposerDraft(draft.getString("text"), (0 until attachments.length()).map { Path.of(attachments.getString(it)) })
    }

    @Synchronized
    fun sharedRecord(key: SessionKey, scope: String): String? {
        val session = findProfile(state, key.profileId)?.let { findSession(it, key.sessionId) } ?: return null
        return if (session.has(scope)) session.getString(scope) else null
    }

    @Synchronized
    fun profileAuthorization(profileId: String): String? =
        requireProfile(state, profileId).optString("profileAuthorization").takeIf(String::isNotEmpty)

    @Synchronized
    fun recordedRemoteProfile(profileId: String): String? {
        val profile = requireProfile(state, profileId)
        val sessions = profile.getJSONArray("sessions")
        val scopes = ((0 until sessions.length()).mapNotNull {
            sessions.getJSONObject(it).optJSONObject("profileHistory")?.getString("profile")
        } + listOfNotNull(profile.optString("remoteProfileId").takeIf(String::isNotEmpty))).distinct()
        check(scopes.size <= 1) { "本机保存的历史来自不同档案，无法更换主人凭据。" }
        return scopes.singleOrNull()
    }

    private fun historySession(source: JSONObject, key: SessionKey) =
        findSession(requireProfile(source, key.profileId), key.sessionId) ?: error("本机已删除此会话。")

    @Synchronized
    fun preferenceAuthority(key: SessionKey): PreferenceRoom? {
        val p = findProfile(state, key.profileId) ?: return null
        val scope = p.optJSONObject("deviceProfile")?.getString("profile") ?: return null
        val row = findSession(p, key.sessionId) ?: return null
        val sid = row.optString("deviceSid").ifEmpty { return null }
        return PreferenceRoom(row.optString("deviceAuthority", scope), sid)
    }

    @Synchronized
    fun hasSession(key: SessionKey) = findProfile(state, key.profileId)?.let { findSession(it,key.sessionId) } != null

    @Synchronized
    fun directJournal(key: SessionKey, edit: (PreferenceJournal) -> PreferenceJournal = { it }): PreferenceJournal {
        val profile = requireProfile(state, key.profileId)
        val row = historySession(state, key)
        val old = PreferenceJournal.decode(profile.opt("chatPreferences") as? String, row.opt("chatPreferences") as? String)
        return edit(old).also { next ->
            if (next != old) persist(requireAtomic = true) { copy ->
                requireProfile(copy, key.profileId).put("chatPreferences", next.encode(true))
                historySession(copy, key).put("chatPreferences", next.encode(false))
            }
        }
    }

    @Synchronized
    fun deviceJournal(room: PreferenceRoom, edit: (PreferenceJournal) -> PreferenceJournal = { it }): PreferenceJournal {
        val profile = state.optJSONObject("deviceChatPreferences")?.optJSONObject(room.profile)
        val old = PreferenceJournal.decode(profile?.opt("profile") as? String,
            profile?.optJSONObject("rooms")?.opt(room.sid) as? String)
        return edit(old).also { next ->
            if (next != old) persist(requireAtomic = true) { copy ->
                fun child(p: JSONObject, k: String) = p.optJSONObject(k) ?: JSONObject().also { p.put(k, it) }
                val p = child(child(copy, "deviceChatPreferences"), room.profile)
                p.put("profile", next.encode(true))
                child(p, "rooms").put(room.sid, next.encode(false))
            }
        }
    }

    @Synchronized
    fun preferenceSequence(key: SessionKey, index: Int, paired: Boolean): Long? {
        val row = findProfile(state, key.profileId)?.let { findSession(it, key.sessionId) } ?: return null
        val entry = row.getJSONArray("entries").optJSONObject(index) ?: return null
        if (entry.optString("status") != "已完成") return null
        val seq = index.toLong() + 1
        return seq.takeIf { !paired || index < row.optInt("preferenceVerified") || entry.optLong("profileTurnSeq") == seq }
    }

    @Synchronized
    fun preferenceVerified(key: SessionKey) = historySession(state, key).optInt("preferenceVerified")

    @Synchronized
    fun lastCompletedSeq(key: SessionKey): Long {
        val rows = historySession(state,key).getJSONArray("entries")
        for (i in rows.length() - 1 downTo 0) if (rows.getJSONObject(i).getString("status") == "已完成") return i.toLong() + 1
        return 0
    }

    @Synchronized
    fun markPreferenceVerified(key: SessionKey, room: PreferenceRoom, count: Int) {
        check(preferenceAuthority(key) == room)
        if (count > preferenceVerified(key)) persist(requireAtomic = true) { historySession(it, key).put("preferenceVerified", count) }
    }

    private fun profileMetadata(session: JSONObject, remoteProfile: String): JSONObject =
        session.getJSONObject("profileHistory").also {
            check(it.getString("profile") == remoteProfile) { "此会话属于另一份档案授权。" }
        }

    @Synchronized
    fun profileHistoryPosition(key: SessionKey, remoteProfile: String): DesktopHistoryPosition {
        val session = historySession(state, key)
        val meta = profileMetadata(session, remoteProfile)
        val prefix = meta.getInt("prefix")
        val count = session.getJSONArray("entries").length()
        check(prefix in 0..count) { "会话 ${key.sessionId} 的本地历史前缀缺失（应有 ${prefix} 轮，实际 ${count} 轮）。" }
        return DesktopHistoryPosition(prefix, count.toLong(), meta.optJSONObject("pending")?.let {
            SharedPending(it.getString("id"), it.getString("question"))
        })
    }

    @Synchronized
    fun profileSeedPage(key: SessionKey, remoteProfile: String, start: Int, limit: Int): List<ProfileImportTurn> {
        val session = historySession(state, key)
        val prefix = profileMetadata(session, remoteProfile).getInt("prefix")
        val entries = session.getJSONArray("entries")
        require(start in 0..prefix && limit in 1..100 && prefix <= entries.length())
        // A source position is immutable, including when two consecutive turns are identical.
        val envelope = JSONObject().put("sid", key.sessionId).put("start", start).put("turns", JSONArray())
        var bytes = envelope.toString().toByteArray(Charsets.UTF_8).size.toLong()
        return buildList {
            for (index in start until minOf(prefix.toLong(), start.toLong() + limit).toInt()) {
                val id = UUID.nameUUIDFromBytes(JSONArray(listOf("profile-history-v1", username, key.profileId, key.sessionId, index))
                    .toString().toByteArray(Charsets.UTF_8)).toString()
                val turn = desktopSharedSeedTurn(chatEntryFrom(entries.getJSONObject(index)))
                val size = if (turn.question.length.toLong() + turn.answer.length > MAX_IMPORT_BYTES) MAX_IMPORT_BYTES + 1
                    else JSONObject().put("id", id).put("question", turn.question).put("answer", turn.answer)
                        .put("status", turn.status).toString().toByteArray(Charsets.UTF_8).size
                if (bytes + size + (if (isEmpty()) 0 else 1) > MAX_IMPORT_BYTES) {
                    check(isNotEmpty()) { "第 ${index + 1} 轮超过单次 8 MiB 上限，第 ${index + 1}–${prefix} 轮仍保存在电脑。" }
                    break
                }
                bytes += size + (if (isEmpty()) 0 else 1)
                add(ProfileImportTurn(id, turn.question, turn.answer, turn.status))
            }
        }
    }

    /** Append final authoritative rows in order. Never reconstruct a native entry from its
     * textual shared projection, which intentionally lacks its original attachment references. */
    @Synchronized
    fun mergeProfilePage(key: SessionKey, remoteProfile: String, page: List<SharedTurn>, activity: Long = 0): Boolean {
        val finished = page.takeWhile { it.status != "running" }
        check(page.drop(finished.size).all { it.status == "running" }) { "共享服务返回了异常的轮次次序。" }
        val waitingMedia = nextDeviceSend(key)?.takeIf { it.entry != null && it.phase != "confirmed" }
        val terminal = finished.takeWhile { it.id != waitingMedia?.id }
        if (terminal.isEmpty()) return false
        var changed = false
        persist(requireAtomic = true) { copy ->
            val session = historySession(copy, key)
            val meta = profileMetadata(session, remoteProfile)
            val prefix = meta.getInt("prefix")
            val entries = session.getJSONArray("entries")
            val pending = meta.optJSONObject("pending")
            for (row in terminal) {
                check(row.seq > prefix && row.seq <= entries.length().toLong() + 1) { "会话 ${key.sessionId} 在第 ${row.seq} 轮前存在缺口。" }
                if (row.seq <= entries.length()) {
                    check(entries.getJSONObject(row.seq.toInt() - 1).getString("profileTurnId") == row.id) { "共享轮次编号与本机不同。" }
                } else {
                    require(row.status == "complete" || row.status == "unknown")
                    val operation = session.optJSONArray("deviceQueue")?.optJSONObject(0)
                        ?.takeIf { it.getString("id") == row.id }
                    val status = if (row.status == "complete") "已完成" else "上次回复未确认"
                    val local = operation?.optJSONObject("entry")
                    val result = if (local != null) {
                        val native = chatEntryFrom(local)
                        check(desktopSharedSeedTurn(native).question == row.question) { "媒体操作与服务返回的消息不同。" }
                        val projection = desktopSharedSeedTurn(native)
                        check(projection.answer == row.answer && projection.status == row.status) { "媒体回执与本机结果不同。" }
                        entryToJson(native.copy(status = status))
                    } else JSONObject().put("question", row.question).put("answer", row.answer).put("status", status)
                    entries.put(result.put("profileTurnId", row.id).put("profileTurnSeq", row.seq))
                    if (activity > session.optLong("lastActivityAt")) session.put("lastActivityAt", activity)
                    changed = true
                }
                if (pending?.getString("id") == row.id) {
                    check(pending.getString("question") == row.question)
                    meta.remove("pending")
                }
                val queue = session.optJSONArray("deviceQueue")
                val op = queue?.optJSONObject(0)
                if (op?.getString("id") == row.id) {
                    check(op.getString("question") == row.question) { "设备发送回执正文不一致。" }
                    queue.remove(0)
                    changed = true
                }
            }
        }
        return changed
    }

    @Synchronized
    fun legacyHiddenSessions(profileId: String): Set<String> =
        requireProfile(state, profileId).optJSONObject("profileRemoved")?.keySet()?.toSet().orEmpty()

    @Synchronized
    fun hasSyncedHistory(key: SessionKey): Boolean =
        findProfile(state, key.profileId)?.let { findSession(it, key.sessionId) }?.let(::hasSyncedHistory) == true
    private fun hasSyncedHistory(row: JSONObject) = row.has("profileHistory") || hasLegacyRoomField(row)

    @Synchronized
    fun hasLegacySharedRecord(key: SessionKey): Boolean = hasLegacyRoomField(historySession(state, key))

    private fun hasLegacyRoomField(row: JSONObject) = row.keySet().any { it.startsWith("shared-") }

    @Synchronized
    fun deviceOwner(): Pair<String, String>? = state.optJSONObject("deviceOwner")?.let {
        it.getString("instance") to it.getString("device")
    }

    @Synchronized
    fun saveDeviceOwner(instance: String, device: String) {
        val existing = deviceOwner()
        if (existing == instance to device) return
        check(existing == null) { "此电脑历史已绑定另一共享服务实例，请选择原数据库。" }
        persist(requireAtomic = true) { it.put("deviceOwner", JSONObject().put("instance", instance).put("device", device)) }
    }

    @Synchronized
    fun deviceProfile(profileId: String): DeviceProfile? = findProfile(state, profileId)?.optJSONObject("deviceProfile")?.let {
        DeviceProfile(it.getString("profile"), it.getString("sourceId"), it.getString("base"), it.getString("username"),
            it.getString("name"), it.getString("originDevice"), it.getString("upstream"), it.getBoolean("available"))
    }

    /** Remote source ids belong to their originating installation. Only the publisher may associate
     * a local native profile. All other profiles get a separate, stable device namespace. */
    @Synchronized
    fun cacheDeviceProfile(remote: DeviceProfile, nativeId: String? = null): String {
        val (instance, owner) = checkNotNull(deviceOwner())
        if (nativeId != null) check(remote.originDevice == owner && remote.username == username && remote.sourceId == nativeId)
        val mapped = profiles.firstOrNull { deviceProfile(it.id)?.profile == remote.profile }?.id
        val id = nativeId ?: mapped ?: "device:$instance:${remote.profile}"
        check(mapped == null || mapped == id) { "设备目录与本机来源映射不一致。" }
        val old = deviceProfile(id)
        if (old == remote) return id
        check(old == null || old.profile == remote.profile && old.username == remote.username && old.originDevice == remote.originDevice)
        persist(requireAtomic = true) { copy ->
            val profile = findProfile(copy, id) ?: JSONObject().put("id", id).put("address", "")
                .put("kind", "device").put("offlineOnly", true).put("currentSession", "").put("sessions", JSONArray())
                .also { profileArray(copy).put(it) }
            profile.put("deviceProfile", JSONObject().put("profile", remote.profile).put("sourceId", remote.sourceId)
                .put("base", remote.base).put("username", remote.username).put("name", remote.name)
                .put("originDevice", remote.originDevice).put("upstream", remote.upstream).put("available", remote.available))
        }
        return id
    }

    @Synchronized
    fun prepareDeviceHistory(key: SessionKey, remote: String) {
        val row = historySession(state, key)
        if (row.has("profileHistory")) {
            profileMetadata(row, remote)
            return
        }
        val entries = row.getJSONArray("entries")
        check((0 until entries.length()).none { entries.getJSONObject(it).getString("status") == "发送中" }) { "本机会话仍在发送，请稍后同步。" }
        persist(requireAtomic = true) {
            historySession(it, key).put("profileHistory", JSONObject().put("profile", remote).put("prefix", entries.length()))
        }
    }

    @Synchronized
    fun deviceSessionState(key: SessionKey): DesktopDeviceSessionState {
        val row = findProfile(state, key.profileId)?.let { findSession(it, key.sessionId) }
            ?: return DesktopDeviceSessionState("", false, "本机会话已删除。", false, 0)
        return DesktopDeviceSessionState(row.optString("deviceSid"), row.optBoolean("deviceRemote"),
            row.optString("deviceReason"), row.optBoolean("deviceRename"), row.optJSONArray("deviceQueue")?.length() ?: 0,
            row.optString("deviceAuthority"))
    }

    /** Called only for a server-returned canonical key after presentation of the original owner proof. */
    @Synchronized
    fun bindProvenRoom(key: SessionKey, localScope: String, remote: DeviceSession, snapshot: Chat, aligned: Boolean) {
        val before = historySession(state, key)
        check(chatFrom(before) == snapshot && (!before.has("deviceAuthority") ||
            before.getString("deviceAuthority") == remote.profile && before.getString("deviceSid") == remote.sid))
        persist(requireAtomic = true) { copy ->
            val row = historySession(copy, key)
            val meta = profileMetadata(row, localScope)
            if (!aligned) {
                check(!row.has("deviceOriginal"))
                row.put("deviceOriginal", JSONObject().put("id", key.sessionId).put("entries", row.getJSONArray("entries")))
                row.put("entries", JSONArray())
                meta.put("prefix", 0)
            }
            row.put("deviceAuthority", remote.profile).put("deviceSid", remote.sid)
            row.put("deviceTitle", remote.title).put("deviceReason", remote.reason)
            row.remove("deviceRename")
        }
    }

    @Synchronized
    fun deviceOriginal(key: SessionKey): Chat? =
        findProfile(state, key.profileId)?.let { findSession(it, key.sessionId) }?.optJSONObject("deviceOriginal")?.let(::chatFrom)

    @Synchronized
    fun recordedTurnIds(key: SessionKey): List<String?> {
        val entries = historySession(state, key).getJSONArray("entries")
        return List(entries.length()) { entries.getJSONObject(it).opt("profileTurnId") as? String }
    }

    private fun authorityKeys(): Map<SessionKey, SessionKey> = buildMap {
        val profiles = profileArray(state)
        for (i in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(i)
            val scope = profile.optJSONObject("deviceProfile")?.getString("profile") ?: continue
            val rows = profile.getJSONArray("sessions")
            for (j in 0 until rows.length()) {
                val row = rows.getJSONObject(j)
                val sid = row.optString("deviceSid")
                if (sid.isNotEmpty()) put(SessionKey(profile.getString("id"), row.getString("id")),
                    SessionKey(row.optString("deviceAuthority", scope), sid))
            }
        }
    }

    @Synchronized
    fun deviceRelatedKeys(key: SessionKey): List<SessionKey> {
        val authorities = authorityKeys()
        val authority = authorities[key] ?: return listOf(key)
        return authorities.filterValues { it == authority }.keys.toList()
    }

    @Synchronized
    fun deviceRecentRepresentatives(selected: SessionKey, keys: List<SessionKey>): Set<SessionKey> {
        val groups = keys.groupBy(authorityKeys()::get)
        return buildSet {
            addAll(groups[null].orEmpty())
            for ((authority, members) in groups) if (authority != null) {
                add(members.firstOrNull { it == selected } ?: members.firstOrNull { it.profileId == selected.profileId } ?: members.first())
            }
        }
    }

    /** The directory may contain sessions created by a different device even in our own profile. */
    @Synchronized
    fun cacheDeviceSession(profileId: String, remote: DeviceSession, native: SessionKey? = null, publishedTitle: String? = null): SessionKey {
        val localScope = checkNotNull(deviceProfile(profileId)).profile
        check(localScope == remote.profile || native != null && historySession(state, native).optString("deviceAuthority") == remote.profile)
        val profile = requireProfile(state, profileId)
        val rows = profile.getJSONArray("sessions")
        val match = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull {
            it.optString("deviceSid") == remote.sid && it.optString("deviceAuthority", localScope) == remote.profile
        }
        val key = native ?: SessionKey(profileId, match?.getString("id") ?: remote.sid)
        check(key.profileId == profileId && remote.sid !in deviceRemoved(profileId))
        val old = findSession(profile, key.sessionId)
        check(old == null || !old.has("deviceSid") || old.getString("deviceSid") == remote.sid)
        val renameAck = publishedTitle != null && old != null && (manualSessionName(old) ?: sessionTitle(old)) == publishedTitle
        val dirty = old?.optBoolean("deviceRename") == true && !renameAck
        if (old != null && old.optString("deviceSid") == remote.sid && old.optString("deviceReason") == remote.reason &&
            old.optLong("lastActivityAt") >= remote.activity && (dirty || old.optString("deviceTitle") == remote.title) &&
            (!renameAck || !old.optBoolean("deviceRename"))) return key
        persist(requireAtomic = true) { copy ->
            val target = requireProfile(copy, profileId)
            val row = findSession(target, key.sessionId) ?: newSession(key.sessionId).put("deviceRemote", true)
                .put("profileHistory", JSONObject().put("profile", remote.profile).put("prefix", 0)).also {
                    target.getJSONArray("sessions").put(it)
                }
            row.put("deviceSid", remote.sid).put("deviceReason", remote.reason)
            if (!dirty) row.put("deviceTitle", remote.title)
            if (renameAck) row.remove("deviceRename")
            if (remote.activity > row.optLong("lastActivityAt")) row.put("lastActivityAt", remote.activity)
        }
        return key
    }

    @Synchronized
    fun deviceRemoved(profileId: String): Set<String> =
        requireProfile(state, profileId).optJSONObject("deviceRemoved")?.keySet()?.toSet().orEmpty()

    @Synchronized
    fun acknowledgeDeviceRemoval(profileId: String, sid: String) {
        persist(requireAtomic = true) { requireProfile(it, profileId).optJSONObject("deviceRemoved")?.remove(sid) }
    }

    @Synchronized
    fun queueDeviceSend(key: SessionKey, question: String, revision: Long, entry: Entry? = null,
                        contextRevision: String? = null): String {
        check(revision >= (appliedDraftRevisions[key] ?: Long.MIN_VALUE)) { "草稿已更新，请重新发送。" }
        val id = UUID.randomUUID().toString()
        persist(requireAtomic = true) { copy ->
            val row = historySession(copy, key)
            check(revision != appliedDraftRevisions[key] || row.has("draft")) { "此草稿已提交。" }
            check(revision != appliedDraftRevisions[key] || entry != null || readDraft(key).attachments.isEmpty())
            val queue = row.optJSONArray("deviceQueue") ?: JSONArray().also { row.put("deviceQueue", it) }
            val op = JSONObject().put("id", id).put("question", question)
            val authored = entry?.question ?: question
            val attachments = entry?.attachmentReferences.orEmpty().map { ref ->
                if (ref.startsWith("file:", true)) Path.of(URI(ref)).toString() else ref
            }
            val saved = readDraft(key)
            val text = saved.text.takeIf { it.trim() == authored.trim() && saved.attachments.map(Path::toString) == attachments } ?: authored
            op.put("originalDraft", JSONObject().put("text", text).put("attachments", JSONArray(attachments)))
            if (contextRevision != null) op.put("contextRevision", contextRevision)
            entry?.let { op.put("entry", entryToJson(it)) }
            queue.put(op)
            row.remove("draft")
        }
        appliedDraftRevisions[key] = revision
        return id
    }

    @Synchronized
    fun nextDeviceSend(key: SessionKey): DesktopDevicePending? =
        historySession(state, key).optJSONArray("deviceQueue")?.optJSONObject(0)?.let {
            DesktopDevicePending(it.getString("id"), it.getString("question"), it.optString("method"),
                it.optString("phase"), it.optJSONObject("entry")?.let(::chatEntryFrom), it.opt("contextRevision") as? String)
        }

    @Synchronized
    fun restoreRejectedDeviceDraft(key: SessionKey, id: String, status: Int) {
        persist(requireAtomic = true) { copy ->
            val row = historySession(copy, key)
            val queue = row.getJSONArray("deviceQueue")
            val op = queue.getJSONObject(0)
            check(op.getString("id") == id && op.optString("phase").isEmpty())
            val entry = op.optJSONObject("entry")?.let(::chatEntryFrom)
            val original = op.optJSONObject("originalDraft") ?: JSONObject()
                .put("text", entry?.question ?: op.getString("question")).put("attachments", JSONArray(
                    entry?.attachmentReferences.orEmpty().map { if (it.startsWith("file:", true)) Path.of(URI(it)).toString() else it }))
            val existing = row.optJSONObject("draft")
            val destination = if (existing == null || existing.similar(original)) row
                else newSession(UUID.randomUUID().toString()).put("manualName", "未发出的草稿").also {
                    insertSessionAtHead(requireProfile(copy, key.profileId), it)
                }
            destination.put("draft", original.put("rejectedRequestId", id).put("rejectedStatus", status))
            val receipts = row.optJSONObject("deviceRejections") ?: JSONObject().also { row.put("deviceRejections",it) }
            receipts.put(id,JSONObject().put("status",status).put("draftSession",destination.getString("id")))
            queue.remove(0)
        }
    }

    @Synchronized
    fun restoredDeviceDraft(key: SessionKey): Pair<String,ComposerDraft>? {
        val row = findProfile(state,key.profileId)?.let { findSession(it,key.sessionId) } ?: return null
        return (row.optJSONObject("draft")?.opt("rejectedRequestId") as? String)?.let { it to readDraft(key) }
    }

    @Synchronized
    fun acknowledgeRestoredDraft(key: SessionKey, requestId: String) {
        if (restoredDeviceDraft(key)?.first == requestId) persist(requireAtomic = true) {
            historySession(it,key).getJSONObject("draft").remove("rejectedRequestId")
        }
    }

    @Synchronized
    fun hasLegacyProfilePending(key: SessionKey): Boolean =
        historySession(state, key).optJSONObject("profileHistory")?.has("pending") == true

    /** Desktop's process-wide history lock and this atomic comparison grant one native upload. */
    @Synchronized
    fun claimDeviceMedia(key: SessionKey, id: String): Boolean {
        val pending = nextDeviceSend(key) ?: return false
        if (pending.id != id || pending.entry == null || pending.method != "media" || pending.phase.isNotEmpty()) return false
        markDeviceSend(key, id, "media", "dispatch")
        return true
    }

    /** The method and native execution boundary must survive loss of an HTTP receipt or process exit. */
    @Synchronized
    fun markDeviceSend(key: SessionKey, id: String, method: String, phase: String = "", entry: Entry? = null) {
        persist(requireAtomic = true) { copy ->
            val op = historySession(copy, key).getJSONArray("deviceQueue").getJSONObject(0)
            check(op.getString("id") == id && (!op.has("method") || op.getString("method") == method))
            op.put("method", method).put("phase", phase)
            entry?.let { op.put("entry", entryToJson(it)) }
        }
    }

    @Synchronized
    fun saveDraft(key: SessionKey, revision: Long, draft: ComposerDraft) {
        if (revision <= (appliedDraftRevisions[key] ?: Long.MIN_VALUE)) return
        val profile = findProfile(state, key.profileId) ?: return
        if (findSession(profile, key.sessionId) == null) return
        persist(requireAtomic = true) { copy ->
            val profile = requireProfile(copy,key.profileId)
            val session = checkNotNull(findSession(profile, key.sessionId))
            // An IME edit can predate the recovery but reach disk after it. Keep the
            // unsent original separately until the editor acknowledges having loaded it.
            val recovered = session.optJSONObject("draft")
            val rejectedId = recovered?.opt("rejectedRequestId") as? String
            if (rejectedId != null && draft != readDraft(key)) {
                val backup = newSession(UUID.randomUUID().toString()).put("manualName","未发出的草稿").put("draft",recovered)
                insertSessionAtHead(profile,backup)
                session.optJSONObject("deviceRejections")?.optJSONObject(rejectedId)?.put("draftSession",backup.getString("id"))
            }
            if (draft.text.isEmpty() && draft.attachments.isEmpty()) {
                session.remove("draft")
            } else {
                session.put("draft", JSONObject().put("text", draft.text)
                    .put("attachments", JSONArray(draft.attachments.map(Path::toString))))
            }
        }
        appliedDraftRevisions[key] = revision
    }

    @Synchronized
    fun commitDraft(key: SessionKey, revision: Long, pendingChat: Chat) {
        require(pendingChat.session == key.sessionId) { "消息与草稿来自不同会话" }
        check(revision >= (appliedDraftRevisions[key] ?: Long.MIN_VALUE)) { "草稿已更新，请重新发送" }
        persist(requireAtomic = true) { copy ->
            val profile = requireProfile(copy, key.profileId)
            require(!profile.optBoolean("offlineOnly")) { "Legacy offline profiles are read-only" }
            val session = findSession(profile, key.sessionId) ?: error("Unknown session")
            writeSessionContent(session, pendingChat)
            session.remove("draft")
        }
        appliedDraftRevisions[key] = revision
    }

    /** Run once under the process lock at cold start, before any in-memory attachment or send exists. */
    @Synchronized
    fun cleanupUnreferencedManagedMedia() {
        val directory = managedMediaDirectory
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return
        for (ancestor in generateSequence(directory) { it.parent }) {
            val attrs = Files.readAttributes(ancestor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attrs.isDirectory || attrs.isOther || attrs.isSymbolicLink) return
        }
        val candidates = Files.newDirectoryStream(directory).use { files ->
            files.filter { path ->
                MANAGED_MEDIA_NAME.matches(path.fileName.toString()) && Files.isRegularFile(path, NOFOLLOW_LINKS)
            }.toMutableSet()
        }
        if (candidates.isEmpty()) return

        fun keep(reference: String) {
            if (candidates.isEmpty()) return
            val path = try {
                if (reference.startsWith("file:", ignoreCase = true)) Path.of(URI(reference)) else Path.of(reference)
            } catch (_: IllegalArgumentException) {
                return // Historical non-local reference.
            } catch (_: java.net.URISyntaxException) {
                return
            }
            candidates.remove(path.toAbsolutePath().normalize())
            // A user can also select a path through a linked directory or an NTFS short name.
            try {
                candidates.remove(path.toRealPath())
            } catch (_: java.nio.file.NoSuchFileException) {
                // Moved external files are retained in the draft for the user to resolve.
            }
        }
        val profiles = profileArray(state)
        for (i in 0 until profiles.length()) {
            val sessions = profiles.getJSONObject(i).getJSONArray("sessions")
            for (j in 0 until sessions.length()) {
                val session = sessions.getJSONObject(j)
                val attachments = session.optJSONObject("draft")?.getJSONArray("attachments")
                if (attachments != null) for (k in 0 until attachments.length()) keep(attachments.getString(k))
                val queue = session.optJSONArray("deviceQueue")
                if (queue != null) for (k in 0 until queue.length()) {
                    val media = queue.getJSONObject(k).optJSONObject("entry") ?: continue
                    attachmentReferencesFrom(media).forEach(::keep)
                }
                for (entries in listOfNotNull(session.getJSONArray("entries"), session.optJSONObject("deviceOriginal")?.getJSONArray("entries"))) {
                    for (k in 0 until entries.length()) {
                        val entry = entries.getJSONObject(k)
                        (entry.opt("attachmentReference") as? String)?.takeIf { it.isNotEmpty() }?.let(::keep)
                        attachmentReferencesFrom(entry).forEach(::keep)
                    }
                }
            }
        }
        candidates.forEach { Files.deleteIfExists(it) }
    }

    @Synchronized
    fun createSession(profileId: String): Chat {
        var chat: Chat? = null
        persist { copy ->
            val profile = requireProfile(copy, profileId)
            require(!profile.optBoolean("offlineOnly") || profile.optString("kind") in setOf("shared", "device")) { "Legacy offline profiles cannot start sessions" }
            val session = newSession(UUID.randomUUID().toString())
            insertSessionAtHead(profile, session)
            profile.put("currentSession", session.getString("id"))
            copy.put("activeProfileId", profileId)
            chat = Chat(session.getString("id"))
        }
        return checkNotNull(chat)
    }

    @Synchronized
    fun saveProfile(profileId: String, chat: Chat) {
        persist { copy ->
            val profile = requireProfile(copy, profileId)
            require(!profile.optBoolean("offlineOnly")) { "Legacy offline profiles are read-only" }
            saveChat(profile, chat)
            copy.put("activeProfileId", profileId)
        }
    }

    /** Persists a send or reply update without changing the active profile or selected session. */
    @Synchronized
    fun saveSessionContent(profileId: String, chat: Chat) {
        persist { copy ->
            val profile = requireProfile(copy, profileId)
            require(!profile.optBoolean("offlineOnly")) { "Legacy offline profiles are read-only" }
            val session = findSession(profile, chat.session) ?: error("Unknown session")
            writeSessionContent(session, chat)
        }
    }

    /** Returns a session snapshot without changing selection or recency. */
    @Synchronized
    fun sessionContent(profileId: String, sessionId: String): Chat {
        val profile = requireProfile(state, profileId)
        val session = findSession(profile, sessionId) ?: error("Unknown session")
        return chatFrom(session)
    }

    /** Compatibility API used by the original single-address UI and tests. */
    @Synchronized
    fun save(server: String, chat: Chat) {
        val address = normalizeAddress(server)
        persist { copy ->
            val profile = findManualProfileByAddress(copy, address) ?: createProfileInState(copy, address)
            saveChat(profile, chat)
            copy.put("activeProfileId", profile.getString("id"))
        }
    }

    /**
     * Explicitly binds unclaimed loopback history to a user-verified profile address only when its
     * local server session ID uniquely appears in the supplied server session set.
     */
    @Synchronized
    fun adoptVerifiedSessions(profileId: String, address: String, sessions: Set<String>): Set<String> {
        val normalized = normalizeAddress(address)
        var adopted = emptySet<String>()
        persist { copy ->
            val target = requireProfile(copy, profileId)
            require(!target.optBoolean("offlineOnly")) { "Create a manual profile before adoption" }
            target.put("address", normalized)

            val activeProfile = findProfile(copy, copy.optString("activeProfileId"))
            val activeProfileIdBeforeMove = activeProfile?.optString("id").orEmpty()
            val activeSessionBeforeMove = activeProfile?.optString("currentSession").orEmpty()

            val candidates = mutableListOf<Pair<JSONObject, JSONObject>>()
            val rows = profileArray(copy)
            for (index in 0 until rows.length()) {
                val source = rows.getJSONObject(index)
                if (!isAdoptableLegacy(source)) continue
                val sourceSessions = source.getJSONArray("sessions")
                for (sessionIndex in 0 until sourceSessions.length()) {
                    val session = sourceSessions.getJSONObject(sessionIndex)
                    if (session.optString("id") in sessions) candidates += source to session
                }
            }
            val counts = candidates.groupingBy { it.second.getString("id") }.eachCount()
            val targetSessions = target.getJSONArray("sessions")
            val targetIds = (0 until targetSessions.length()).map { targetSessions.getJSONObject(it).getString("id") }.toMutableSet()
            val moved = linkedSetOf<String>()
            candidates.forEach { (source, session) ->
                val id = session.getString("id")
                if (counts[id] != 1 || id in targetIds) return@forEach
                targetSessions.put(JSONObject(session.toString()))
                targetIds += id
                source.getJSONArray("sessions").remove(indexOfSession(source, id))
                moved += id
            }

            for (index in 0 until rows.length()) {
                val profile = rows.getJSONObject(index)
                if (profile.optString("kind") == "device") continue
                val sessionsArray = profile.getJSONArray("sessions")
                val selected = profile.optString("currentSession")
                if ((0 until sessionsArray.length()).none { sessionsArray.getJSONObject(it).getString("id") == selected }) {
                    profile.put("currentSession", sessionsArray.optJSONObject(0)?.optString("id").orEmpty())
                }
            }
            val activeWasMoved = candidates.any { (source, session) ->
                source.getString("id") == activeProfileIdBeforeMove &&
                    session.getString("id") == activeSessionBeforeMove && session.getString("id") in moved
            }
            if (activeWasMoved) {
                target.put("currentSession", activeSessionBeforeMove)
                copy.put("activeProfileId", profileId)
            }
            for (index in rows.length() - 1 downTo 0) {
                val profile = rows.getJSONObject(index)
                if (isAdoptableLegacy(profile) && profile.getJSONArray("sessions").length() == 0) rows.remove(index)
            }
            adopted = moved
        }
        return adopted
    }

    private fun isAdoptableLegacy(profile: JSONObject): Boolean = profile.optBoolean("offlineOnly") &&
        profile.optString("kind") != "shared" && profile.optString("kind") != "device"

    private fun readProfileInternal(profile: JSONObject, requestedSession: String?): Chat {
        val sessionId = requestedSession?.takeIf { it.isNotBlank() }
            ?.also { require(findSession(profile, it) != null) { "Unknown session" } }
            ?: findSession(profile, profile.optString("currentSession"))?.getString("id")
            ?: profile.getJSONArray("sessions").optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
        if (sessionId == null) {
            profile.put("currentSession", "")
            return Chat("")
        }
        profile.put("currentSession", sessionId)
        return chatFrom(findSession(profile, sessionId)!!)
    }

    private fun saveChat(profile: JSONObject, chat: Chat) {
        require(chat.session.isNotBlank()) { "Session identity is required" }
        var session = findSession(profile, chat.session)
        if (session == null) {
            session = newSession(chat.session)
            insertSessionAtHead(profile, session)
        }
        writeSessionContent(session, chat)
        profile.put("currentSession", chat.session)
    }

    private fun writeSessionContent(session: JSONObject, chat: Chat) {
        check(!hasSyncedHistory(session)) { "此会话保留了已同步的历史。请新建本机会话，或在设备共享设置中连接原数据库。" }
        val entries = JSONArray()
        chat.entries.forEach { entries.put(entryToJson(it)) }
        if (!entries.similar(session.optJSONArray("entries"))) session.put("lastActivityAt", System.currentTimeMillis())
        session.put("entries", entries)
    }

    private fun entryToJson(entry: Entry): JSONObject = JSONObject()
        .put("question", entry.question).put("answer", entry.answer).put("status", entry.status).also { row ->
            val references = entry.attachmentReferences.ifEmpty { listOfNotNull(entry.attachmentReference) }
            (entry.attachmentReference ?: references.firstOrNull())?.let { row.put("attachmentReference", it) }
            entry.attachmentDisplayName?.let { row.put("attachmentDisplayName", it) }
            row.put("attachmentReferences", JSONArray(references))
            row.put("replyImages", replyAttachmentsToJson(entry.replyAttachments, entry.answer))
        }

    private fun newSession(id: String): JSONObject = JSONObject()
        .put("createdAt", System.currentTimeMillis())
        .put("id", id)
        .put("entries", JSONArray())

    private fun insertSessionAtHead(profile: JSONObject, session: JSONObject) {
        val rows = profile.getJSONArray("sessions")
        val reordered = JSONArray().put(session)
        for (index in 0 until rows.length()) reordered.put(rows.getJSONObject(index))
        profile.put("sessions", reordered)
    }

    /** Convert the old timestamp-based view into stored display order exactly once. */
    private fun normalizeLegacySessionOrder(candidate: JSONObject) {
        val profiles = profileArray(candidate)
        for (profileIndex in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(profileIndex)
            val rows = profile.getJSONArray("sessions")
            val orderedIndices = (0 until rows.length()).sortedWith(
                compareByDescending<Int> { index ->
                    val row = rows.getJSONObject(index)
                    sessionTimestamp(row, "lastUsedAt") ?: sessionTimestamp(row, "createdAt")
                }.thenByDescending { it }
            )
            if (orderedIndices.withIndex().any { (newIndex, oldIndex) -> newIndex != oldIndex }) {
                profile.put("sessions", JSONArray().also { ordered ->
                    orderedIndices.forEach { ordered.put(rows.getJSONObject(it)) }
                })
            }
        }
    }

    private fun sessionTimestamp(session: JSONObject, key: String): Long? {
        val raw = session.opt(key) as? Number ?: return null
        val numeric = raw.toDouble()
        return numeric.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Long.MIN_VALUE.toDouble() && it <= Long.MAX_VALUE.toDouble() }
            ?.toLong()
    }

    private fun defaultSessionTitle(session: JSONObject): String {
        session.optString("title").trim().takeIf { it.isNotEmpty() }?.let { return shortTitle(it) }
        val entries = session.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            entry.optString("question").trim().takeIf { it.isNotEmpty() }?.let { return shortTitle(it) }
        }
        val imageOnly = (0 until entries.length()).any { index ->
            val entry = entries.getJSONObject(index)
            isImageAttachment(entry.optString("attachmentDisplayName")) ||
                isImageAttachment(entry.optString("attachmentReference")) ||
                attachmentReferencesFrom(entry).any(::isImageAttachment)
        }
        return if (imageOnly) IMAGE_SESSION_TITLE else NEW_SESSION_TITLE
    }

    private fun shortTitle(value: String): String {
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        val count = normalized.codePointCount(0, normalized.length)
        if (count <= MAX_TITLE_CODE_POINTS) return normalized
        val end = normalized.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS - 1)
        return normalized.substring(0, end).trimEnd() + "…"
    }

    private fun isImageAttachment(value: String): Boolean {
        val path = value.substringBefore('?').substringBefore('#').trim().lowercase(Locale.ROOT)
        return IMAGE_EXTENSIONS.any { extension -> path.endsWith(extension) }
    }

    private fun persist(requireAtomic: Boolean = false, change: (JSONObject) -> Unit) {
        val copy = JSONObject(state.toString())
        change(copy)
        copy.put("schemaVersion", SCHEMA_VERSION).put("username", username)
        if (copy.similar(state)) return
        writeAtomic(copy, requireAtomic)
        state = copy
    }

    private fun writeAtomic(contents: JSONObject, requireAtomic: Boolean = false) {
        val dir = file.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "history-", ".tmp")
        try {
            Files.writeString(tmp, contents.toString())
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                if (requireAtomic) throw e
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun backupPriorHistory() {
        val backup = file.resolveSibling(file.fileName.toString() + ".pre-migration.bak")
        if (!Files.exists(backup)) {
            Files.copy(file, backup)
        } else if (Files.mismatch(file, backup) != -1L) {
            Files.copy(file, file.resolveSibling("${file.fileName}.${UUID.randomUUID()}.pre-migration.bak"))
        }
    }

    private fun migrateLegacy(legacy: JSONObject): JSONObject {
        val legacyServers = legacy.optJSONObject("servers") ?: error("Invalid legacy history")
        val groups = mutableListOf<LegacyGroup>()
        val lastServer = legacy.optString("lastServer")
        val lastCanonical = lastServer.takeIf { it.isNotBlank() }?.let(::normalizeAddress)
        var activeGroup: LegacyGroup? = null
        for (addressKey in legacyServers.keySet()) {
            val address = normalizeAddress(addressKey)
            val source = legacyServers.optJSONObject(addressKey) ?: error("Invalid legacy server record")
            val sessionId = source.optString("session").ifBlank { UUID.randomUUID().toString() }
            val group = groups.firstOrNull { candidate ->
                candidate.address == address && candidate.sessions.none { it.getString("id") == sessionId }
            } ?: LegacyGroup(address, isLoopback(address)).also(groups::add)
            val oldEntries = if (source.has("entries")) source.optJSONArray("entries")
                ?: throw DesktopHistoryCorruption("旧版会话 ${sessionId} 的历史轮次列表损坏。") else JSONArray()
            val entries = JSONArray()
            for (index in 0 until oldEntries.length()) {
                val row = oldEntries.optJSONObject(index)
                if (row == null || listOf("status", "answer", "question").any { row.opt(it) !is String }) {
                    throw DesktopHistoryCorruption("旧版会话 ${sessionId} 的第 ${index + 1} 轮正文损坏。")
                }
                val status = row.getString("status").let { if (it == "发送中") "上次回复未确认" else it }
                val answer = row.getString("answer")
                entries.put(JSONObject()
                    .put("question", row.getString("question"))
                    .put("answer", answer)
                    .put("status", status)
                    .also {
                        copyAttachmentFields(row, it)
                        copyReplyAttachments(row, it, answer)
                    })
            }
            val session = JSONObject().put("id", sessionId).put("entries", entries)
            source.optJSONObject("draft")?.let { session.put("draft", it) }
            group.sessions += session
            if (addressKey == lastServer) {
                group.lastSession = sessionId
                activeGroup = group
            }
        }

        if (activeGroup == null && lastCanonical != null) {
            activeGroup = groups.firstOrNull { it.address == lastCanonical }
            activeGroup?.let { group -> group.lastSession = group.sessions.firstOrNull()?.getString("id") }
        }

        val profiles = JSONArray()
        var activeProfileId = ""
        groups.forEach { group ->
            val id = UUID.randomUUID().toString()
            val currentSession = group.lastSession ?: group.sessions.firstOrNull()?.getString("id").orEmpty()
            val profile = JSONObject()
                .put("id", id)
                .put("address", group.address)
                .put("kind", if (group.offlineOnly) "legacy" else "manual")
                .put("offlineOnly", group.offlineOnly)
                .put("currentSession", currentSession)
                .put("sessions", JSONArray(group.sessions))
            profiles.put(profile)
            if (group === activeGroup) activeProfileId = id
        }
        val username = legacy.optString("username").ifBlank { newUsername() }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("sessionOrderVersion", SESSION_ORDER_VERSION)
            .put("username", username)
            .put("activeProfileId", activeProfileId)
            .put("profiles", profiles)
    }

    private fun validateCurrentState(candidate: JSONObject) {
        require(candidate.optJSONObject("profiles") == null && candidate.optJSONArray("profiles") != null) {
            "Invalid profile list"
        }
        require(candidate.optString("username").isNotBlank()) { "Invalid user identity" }
        val rows = candidate.getJSONArray("profiles")
        for (profileIndex in 0 until rows.length()) {
            val profile = rows.getJSONObject(profileIndex)
            require(profile.getString("id").isNotBlank())
            require(profile.getString("address").isNotBlank() || profile.optBoolean("offlineOnly"))
            val sessions = profile.getJSONArray("sessions")
            val ids = mutableSetOf<String>()
            for (sessionIndex in 0 until sessions.length()) {
                val session = sessions.getJSONObject(sessionIndex)
                require(session.getString("id").isNotBlank() && ids.add(session.getString("id"))) {
                    "Invalid or duplicate session ID"
                }
                val sid = session.getString("id")
                val reference = "档案 ${profile.getString("id")} 的会话 ${sid}"
                val entries = session.optJSONArray("entries") ?: throw DesktopHistoryCorruption("${reference} 缺少历史轮次列表。")
                val prefix = session.optJSONObject("profileHistory")?.optInt("prefix", -1)
                if (prefix != null && prefix !in 0..entries.length()) throw DesktopHistoryCorruption("${reference} 的历史前缀范围无效，预计 ${prefix} 轮，现存 ${entries.length()} 轮。")
                val remoteIds = mutableSetOf<String>()
                for (entryIndex in 0 until entries.length()) {
                    try {
                        val entry = entries.getJSONObject(entryIndex)
                        chatEntryFrom(entry)
                        if (prefix != null && entryIndex >= prefix) {
                            check(entry.getLong("profileTurnSeq") == entryIndex.toLong() + 1)
                            check(remoteIds.add(entry.getString("profileTurnId")))
                        }
                    } catch (e: Exception) {
                        throw DesktopHistoryCorruption("${reference} 第 ${entryIndex + 1} 轮的正文或序号损坏。", e)
                    }
                }
            }
        }
    }

    private fun normalizePendingStatuses(candidate: JSONObject) {
        val profiles = profileArray(candidate)
        for (profileIndex in 0 until profiles.length()) {
            val sessions = profiles.getJSONObject(profileIndex).getJSONArray("sessions")
            for (sessionIndex in 0 until sessions.length()) {
                val entries = sessions.getJSONObject(sessionIndex).getJSONArray("entries")
                for (entryIndex in 0 until entries.length()) {
                    val entry = entries.getJSONObject(entryIndex)
                    if (entry.optString("status") == "发送中") entry.put("status", "上次回复未确认")
                }
            }
        }
    }

    private fun profileArray(source: JSONObject): JSONArray = source.getJSONArray("profiles")

    private fun findProfile(source: JSONObject, profileId: String): JSONObject? {
        if (profileId.isBlank()) return null
        val rows = profileArray(source)
        return (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == profileId }
    }

    private fun requireProfile(source: JSONObject, profileId: String): JSONObject =
        findProfile(source, profileId) ?: error("Unknown profile")

    private fun findSession(profile: JSONObject, sessionId: String): JSONObject? {
        if (sessionId.isBlank()) return null
        val rows = profile.getJSONArray("sessions")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == sessionId }
    }

    private fun indexOfSession(profile: JSONObject, sessionId: String): Int {
        val rows = profile.getJSONArray("sessions")
        return (0 until rows.length()).firstOrNull { rows.getJSONObject(it).optString("id") == sessionId }
            ?: error("Unknown session")
    }

    private fun profileFrom(row: JSONObject): Profile = Profile(
        id = row.getString("id"),
        address = row.optString("address"),
        kind = row.optString("kind", if (row.optBoolean("offlineOnly")) "legacy" else "manual"),
        offlineOnly = row.optBoolean("offlineOnly"),
        currentSession = row.optString("currentSession"),
    )

    private fun chatFrom(session: JSONObject): Chat {
        val rows = session.getJSONArray("entries")
        return Chat(session.getString("id"), (0 until rows.length()).map { chatEntryFrom(rows.getJSONObject(it)) })
    }

    private fun chatEntryFrom(row: JSONObject): Entry {
        val status = row.getString("status")
        val oldReference = row.optString("attachmentReference").takeIf { it.isNotEmpty() }
        val references = attachmentReferencesFrom(row).ifEmpty { oldReference?.let(::listOf) ?: emptyList() }
        val reference = oldReference ?: references.firstOrNull()
        val displayName = row.optString("attachmentDisplayName").takeIf { it.isNotEmpty() }
            ?: reference?.let(::attachmentNameFromReference)
        val answer = row.getString("answer")
        return Entry(row.getString("question"), answer, status, reference, displayName,
            replyAttachmentsFrom(row, answer), references)
    }

    private fun attachmentReferencesFrom(row: JSONObject): List<String> {
        val values = row.optJSONArray("attachmentReferences") ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                val value = values.opt(index) as? String ?: continue
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    private fun attachmentNameFromReference(reference: String): String? = runCatching {
        val uri = URI(reference)
        val name = if (uri.scheme.equals("file", ignoreCase = true)) {
            Path.of(uri).fileName?.toString()
        } else {
            uri.path?.substringAfterLast('/') ?: Path.of(reference).fileName?.toString()
        }
        name?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun findManualProfileByAddress(source: JSONObject, address: String): JSONObject? {
        val rows = profileArray(source)
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .firstOrNull { it.optString("kind") == "manual" && !it.optBoolean("offlineOnly") && it.optString("address") == address }
    }

    private fun createProfileInState(source: JSONObject, address: String, profileId: String = UUID.randomUUID().toString()): JSONObject {
        val sessionId = UUID.randomUUID().toString()
        val profile = JSONObject()
            .put("id", profileId)
            .put("address", address)
            .put("kind", "manual")
            .put("offlineOnly", false)
            .put("currentSession", sessionId)
            .put("sessions", JSONArray().put(newSession(sessionId)))
        profileArray(source).put(profile)
        return profile
    }

    private data class LegacyGroup(
        val address: String,
        val offlineOnly: Boolean,
        val sessions: MutableList<JSONObject> = mutableListOf(),
        var lastSession: String? = null,
    )

    companion object {
        private const val SCHEMA_VERSION = 2
        private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        private val MANAGED_MEDIA_NAME = Regex("""screenshot-[0-9]+\.png|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\.wav|recording-[0-9]+\.part""")
        private const val SESSION_ORDER_VERSION = 1
        private const val MAX_TITLE_CODE_POINTS = 40
        private const val IMAGE_SESSION_TITLE = "图片会话"
        private const val NEW_SESSION_TITLE = "新会话"
        private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg", ".heic", ".heif", ".avif")

        private fun emptyState() = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("sessionOrderVersion", SESSION_ORDER_VERSION)
            .put("username", newUsername())
            .put("activeProfileId", "")
            .put("profiles", JSONArray())

        private fun newUsername() = "chatbox_" + UUID.randomUUID().toString().replace("-", "")

        /** Normalize only scheme and host casing. In particular, path casing is retained. */
        internal fun normalizeAddress(value: String): String {
            val uri = URI(value.trim())
            require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Invalid server address"
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: error("Invalid server address")
            require(scheme == "http" || scheme == "https") { "Invalid server address scheme" }
            val host = uri.host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
            val authorityHost = if (host.contains(':')) "[$host]" else host
            val authority = authorityHost + if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return "$scheme://$authority$path"
        }

        private fun isLoopback(address: String): Boolean {
            val host = URI(address).host?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT)
            return host == "localhost" || host == "::1" || host?.startsWith("127.") == true
        }

        private fun copyAttachmentFields(source: JSONObject, target: JSONObject) {
            source.optString("attachmentReference").takeIf { it.isNotEmpty() }?.let { target.put("attachmentReference", it) }
            source.optString("attachmentDisplayName").takeIf { it.isNotEmpty() }?.let { target.put("attachmentDisplayName", it) }
            source.optJSONArray("attachmentReferences")?.let { target.put("attachmentReferences", JSONArray(it.toString())) }
        }

        private fun copyReplyAttachments(source: JSONObject, target: JSONObject, answer: String) {
            if (source.has("replyImages")) {
                target.put("replyImages", replyAttachmentsToJson(replyAttachmentsFrom(source, answer), answer))
            }
        }

        private fun replyAttachmentsFrom(source: JSONObject, answer: String): List<ReplyAttachment> {
            val rows = source.optJSONArray("replyImages") ?: return emptyList()
            return buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    // Older histories stored only images and had no type field.
                    val type = when (row.opt("type")) {
                        null, ReplyAttachmentType.IMAGE.wireName -> ReplyAttachmentType.IMAGE
                        ReplyAttachmentType.AUDIO.wireName -> ReplyAttachmentType.AUDIO
                        else -> continue
                    }
                    val offset = row.opt("textOffset") as? Number ?: continue
                    val numericOffset = offset.toDouble()
                    if (!numericOffset.isFinite() || numericOffset % 1.0 != 0.0 ||
                        numericOffset < Int.MIN_VALUE || numericOffset > Int.MAX_VALUE) continue
                    val attachmentId = when (val raw = row.opt("attachmentId")) {
                        null, JSONObject.NULL -> null
                        is String -> raw.takeIf(String::isNotBlank)
                        else -> continue
                    }
                    add(ReplyAttachment(attachmentId, numericOffset.toInt().coerceIn(0, answer.length), type))
                }
            }
        }

        private fun replyAttachmentsToJson(attachments: List<ReplyAttachment>, answer: String): JSONArray = JSONArray().also { rows ->
            attachments.forEach { attachment ->
                rows.put(JSONObject()
                    .put("type", attachment.type.wireName)
                    .put("attachmentId", attachment.attachmentId ?: JSONObject.NULL)
                    .put("textOffset", attachment.textOffset.coerceIn(0, answer.length)))
            }
        }
    }
}
