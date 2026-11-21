package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

internal data class Entry(
    val question: String,
    val answer: String,
    val status: String,
    val attachmentReference: String? = null,
    val attachmentDisplayName: String? = null,
)

internal data class Chat(val session: String, val entries: List<Entry> = emptyList())

internal data class Profile(
    val id: String,
    val address: String,
    val kind: String,
    val offlineOnly: Boolean,
    val currentSession: String,
)

internal data class SessionInfo(val id: String, val entryCount: Int, val selected: Boolean)

/** Durable local chat storage. Profile IDs, endpoint addresses, and server session IDs are distinct. */
internal class DesktopStore(private val file: Path) {
    private var state: JSONObject

    init {
        state = if (!Files.exists(file)) {
            emptyState()
        } else {
            val loaded = JSONObject(Files.readString(file))
            when {
                loaded.optInt("schemaVersion") == SCHEMA_VERSION -> {
                    validateCurrentState(loaded)
                    loaded
                }
                loaded.has("servers") && !loaded.has("schemaVersion") -> {
                    val migrated = migrateLegacy(loaded)
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
            SessionInfo(row.getString("id"), row.getJSONArray("entries").length(), row.getString("id") == current)
        }
    }

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
                profileArray(copy).put(JSONObject()
                    .put("id", profileId)
                    .put("address", normalized)
                    .put("kind", kind)
                    .put("offlineOnly", false)
                    .put("currentSession", sessionId)
                    .put("sessions", JSONArray().put(JSONObject().put("id", sessionId).put("entries", JSONArray()))))
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
        persist { it.put("activeProfileId", profileId) }
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
    fun createSession(profileId: String): Chat {
        var chat: Chat? = null
        persist { copy ->
            val profile = requireProfile(copy, profileId)
            require(!profile.optBoolean("offlineOnly")) { "Legacy offline profiles cannot start sessions" }
            val session = JSONObject().put("id", UUID.randomUUID().toString()).put("entries", JSONArray())
            profile.getJSONArray("sessions").put(session)
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
        val sessions = profile.getJSONArray("sessions")
        var sessionId = requestedSession?.also { require(findSession(profile, it) != null) { "Unknown session" } }
            ?: profile.optString("currentSession").takeIf { findSession(profile, it) != null }
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString()
            sessions.put(JSONObject().put("id", sessionId).put("entries", JSONArray()))
        }
        profile.put("currentSession", sessionId)
        return chatFrom(findSession(profile, sessionId)!!)
    }

    private fun saveChat(profile: JSONObject, chat: Chat) {
        var session = findSession(profile, chat.session)
        if (session == null) {
            session = JSONObject().put("id", chat.session).put("entries", JSONArray())
            profile.getJSONArray("sessions").put(session)
        }
        val entries = JSONArray()
        chat.entries.forEach { entry ->
            val row = JSONObject()
                .put("question", entry.question)
                .put("answer", entry.answer)
                .put("status", entry.status)
            entry.attachmentReference?.let { row.put("attachmentReference", it) }
            entry.attachmentDisplayName?.let { row.put("attachmentDisplayName", it) }
            entries.put(row)
        }
        session.put("entries", entries)
        profile.put("currentSession", chat.session)
    }

    private fun persist(change: (JSONObject) -> Unit) {
        val copy = JSONObject(state.toString())
        change(copy)
        copy.put("schemaVersion", SCHEMA_VERSION).put("username", username)
        if (copy.similar(state)) return
        writeAtomic(copy)
        state = copy
    }

    private fun writeAtomic(contents: JSONObject) {
        val dir = file.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "history-", ".tmp")
        try {
            Files.writeString(tmp, contents.toString())
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
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
                entries.put(JSONObject()
                    .put("question", row.getString("question"))
                    .put("answer", row.getString("answer"))
                    .put("status", status)
                    .also { copyAttachmentFields(row, it) })
            }
            group.sessions += JSONObject().put("id", sessionId).put("entries", entries)
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
        val status = row.getString("status").let { if (it == "发送中") "上次回复未确认" else it }
        val reference = row.optString("attachmentReference").takeIf { it.isNotEmpty() }
        val displayName = row.optString("attachmentDisplayName").takeIf { it.isNotEmpty() }
        return Entry(row.getString("question"), row.getString("answer"), status, reference, displayName)
    }

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

        private fun emptyState() = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
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
        }
    }
}
