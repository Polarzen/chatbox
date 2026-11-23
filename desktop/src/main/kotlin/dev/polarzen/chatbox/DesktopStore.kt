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

internal data class Profile(
    val id: String,
    val address: String,
    val kind: String,
    val offlineOnly: Boolean,
    val currentSession: String,
)

internal data class SessionInfo(
    val id: String,
    val entryCount: Int,
    val selected: Boolean,
    val title: String,
    val manualName: String? = null,
)

/** Durable local chat storage. Profile IDs, endpoint addresses, and server session IDs are distinct. */
internal class DesktopStore(private val file: Path) {
    private var state: JSONObject
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
                manualName ?: defaultSessionTitle(row),
                manualName,
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
                val title = manualSessionName(row) ?: defaultSessionTitle(row)
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
    fun saveDraft(key: SessionKey, revision: Long, draft: ComposerDraft) {
        if (revision <= (appliedDraftRevisions[key] ?: Long.MIN_VALUE)) return
        val profile = findProfile(state, key.profileId) ?: return
        if (findSession(profile, key.sessionId) == null) return
        persist(requireAtomic = true) { copy ->
            val session = checkNotNull(findSession(requireProfile(copy, key.profileId), key.sessionId))
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
                val entries = session.getJSONArray("entries")
                for (k in 0 until entries.length()) {
                    val entry = entries.getJSONObject(k)
                    (entry.opt("attachmentReference") as? String)?.takeIf { it.isNotEmpty() }?.let(::keep)
                    attachmentReferencesFrom(entry).forEach(::keep)
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
            require(!profile.optBoolean("offlineOnly")) { "Legacy offline profiles cannot start sessions" }
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
                if (!source.optBoolean("offlineOnly")) continue
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
                if (profile.optBoolean("offlineOnly") && profile.getJSONArray("sessions").length() == 0) rows.remove(index)
            }
            adopted = moved
        }
        return adopted
    }

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
        val entries = JSONArray()
        chat.entries.forEach { entry ->
            val row = JSONObject()
                .put("question", entry.question)
                .put("answer", entry.answer)
                .put("status", entry.status)
            val references = entry.attachmentReferences.ifEmpty {
                entry.attachmentReference?.let(::listOf) ?: emptyList()
            }
            (entry.attachmentReference ?: references.firstOrNull())?.let { row.put("attachmentReference", it) }
            entry.attachmentDisplayName?.let { row.put("attachmentDisplayName", it) }
            row.put("attachmentReferences", JSONArray(references))
            row.put("replyImages", replyAttachmentsToJson(entry.replyAttachments, entry.answer))
            entries.put(row)
        }
        session.put("entries", entries)
    }

    private fun newSession(id: String): JSONObject = JSONObject()
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
            val oldEntries = source.optJSONArray("entries") ?: JSONArray()
            val entries = JSONArray()
            for (index in 0 until oldEntries.length()) {
                val row = oldEntries.optJSONObject(index) ?: error("Invalid legacy entry")
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
                val entries = session.getJSONArray("entries")
                for (entryIndex in 0 until entries.length()) chatEntryFrom(entries.getJSONObject(entryIndex))
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
            .put("sessions", JSONArray().put(JSONObject().put("id", sessionId).put("entries", JSONArray())))
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
