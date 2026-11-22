package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class NamedKey(val id: String, val name: String)

internal data class KeyList(val keys: List<NamedKey>, val activeId: String?)

/** Per-profile named credentials. Secret material remains in DesktopCredentials' DPAPI blobs. */
internal class NamedKeys(private val directory: Path, private val credentials: DesktopCredentials) {
    @Synchronized
    fun list(profileId: String): KeyList {
        val state = stateFor(profileId)
        cleanupPending(profileId, state)
        return KeyList(state.keys.toList(), state.activeId)
    }

    @Synchronized
    fun add(profileId: String, name: String, secret: String): NamedKey {
        val normalizedName = normalizeName(name)
        require(secret.isNotBlank()) { "密钥不能为空。" }
        val state = stateFor(profileId)
        cleanupPending(profileId, state)
        requireNameAvailable(state, normalizedName)
        val key = NamedKey(UUID.randomUUID().toString(), normalizedName)

        // Record cleanup intent before creating the secret blob, so a crash cannot orphan it.
        state.pendingDeletes += key.id
        persist(profileId, state)
        try {
            credentials.saveNamed(profileId, key.id, secret)
        } catch (_: Exception) {
            throw storageFailure()
        }

        state.pendingDeletes.remove(key.id)
        state.keys += key
        try {
            persist(profileId, state)
        } catch (_: Exception) {
            // The pending record on disk makes the newly written blob eligible for cleanup.
            throw storageFailure()
        }
        return key
    }

    @Synchronized
    fun rename(profileId: String, id: String, name: String) {
        val state = stateFor(profileId)
        cleanupPending(profileId, state)
        val index = state.keys.indexOfFirst { it.id == id }
        require(index >= 0) { "密钥不存在。" }
        val normalizedName = normalizeName(name)
        requireNameAvailable(state, normalizedName, exceptId = id)
        state.keys[index] = state.keys[index].copy(name = normalizedName)
        persist(profileId, state)
    }

    @Synchronized
    fun remove(profileId: String, id: String) {
        val state = stateFor(profileId)
        cleanupPending(profileId, state)
        require(state.keys.any { it.id == id }) { "密钥不存在。" }
        state.keys.removeAll { it.id == id }
        state.pendingDeletes += id
        if (state.activeId == id) state.activeId = null
        persist(profileId, state)
        cleanupPending(profileId, state)
    }

    @Synchronized
    fun activate(profileId: String, id: String) {
        val state = stateFor(profileId)
        cleanupPending(profileId, state)
        require(state.keys.any { it.id == id }) { "密钥不存在。" }
        state.activeId = id
        persist(profileId, state)
    }

    @Synchronized
    fun secret(profileId: String, id: String): String {
        val state = stateFor(profileId)
        cleanupPending(profileId, state)
        require(state.keys.any { it.id == id }) { "密钥不存在。" }
        val secret = try {
            if (id == LEGACY_ID) credentials.load(profileId) else credentials.loadNamed(profileId, id)
        } catch (_: Exception) {
            throw storageFailure()
        }
        return secret ?: throw storageFailure()
    }

    private fun stateFor(profileId: String): State {
        require(profileId.isNotBlank()) { "配置身份不能为空。" }
        val file = metadataFile(profileId)
        if (Files.exists(file)) return readState(file)

        val hasLegacy = try {
            credentials.contains(profileId)
        } catch (_: Exception) {
            throw storageFailure()
        }
        val state = State(
            migrated = true,
            keys = if (hasLegacy) mutableListOf(NamedKey(LEGACY_ID, LEGACY_NAME)) else mutableListOf(),
            activeId = if (hasLegacy) LEGACY_ID else null,
        )
        persist(profileId, state)
        return state
    }

    private fun readState(file: Path): State = try {
        val json = JSONObject(Files.readString(file))
        require(json.optInt("schemaVersion", -1) == SCHEMA_VERSION && json.optBoolean("migrated"))
        val keysJson = json.getJSONArray("keys")
        val keys = mutableListOf<NamedKey>()
        val ids = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        for (index in 0 until keysJson.length()) {
            val row = keysJson.getJSONObject(index)
            val id = row.getString("id")
            val name = row.getString("name")
            require(validId(id) && ids.add(id))
            require(normalizeName(name) == name && names.add(name.lowercase(Locale.ROOT)))
            keys += NamedKey(id, name)
        }
        val activeId = when (val active = json.opt("activeId")) {
            null, JSONObject.NULL -> null
            is String -> active
            else -> error("invalid")
        }
        require(activeId == null || activeId in ids)
        val pendingJson = json.getJSONArray("pendingDeletes")
        val pending = linkedSetOf<String>()
        for (index in 0 until pendingJson.length()) {
            val id = pendingJson.getString(index)
            require(validId(id) && id !in ids && pending.add(id))
        }
        State(migrated = true, keys = keys, activeId = activeId, pendingDeletes = pending)
    } catch (_: Exception) {
        throw storageFailure()
    }

    private fun cleanupPending(profileId: String, state: State) {
        for (id in state.pendingDeletes.toList()) {
            try {
                if (id == LEGACY_ID) credentials.clear(profileId) else credentials.clearNamed(profileId, id)
                state.pendingDeletes.remove(id)
                persist(profileId, state)
            } catch (_: Exception) {
                throw storageFailure()
            }
        }
    }

    private fun persist(profileId: String, state: State) {
        try {
            Files.createDirectories(directory)
            val json = JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("migrated", state.migrated)
                .put("activeId", state.activeId ?: JSONObject.NULL)
                .put("keys", JSONArray().also { rows ->
                    state.keys.forEach { key -> rows.put(JSONObject().put("id", key.id).put("name", key.name)) }
                })
                .put("pendingDeletes", JSONArray().also { rows -> state.pendingDeletes.sorted().forEach { rows.put(it) } })
            val temporary = Files.createTempFile(directory, "named-keys-", ".tmp")
            try {
                Files.writeString(temporary, json.toString())
                try {
                    Files.move(temporary, metadataFile(profileId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    throw storageFailure()
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (_: Exception) {
            throw storageFailure()
        }
    }

    private fun metadataFile(profileId: String): Path = directory.resolve("named-keys-${profileHash(profileId)}.json")

    private fun profileHash(profileId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(profileId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun requireNameAvailable(state: State, name: String, exceptId: String? = null) {
        val canonical = name.lowercase(Locale.ROOT)
        require(state.keys.none { it.id != exceptId && it.name.lowercase(Locale.ROOT) == canonical }) {
            "密钥名称已存在。"
        }
    }

    private fun normalizeName(name: String): String {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_NAME_LENGTH) { "密钥名称无效。" }
        return normalized
    }

    private fun validId(id: String): Boolean = id == LEGACY_ID || ID_PATTERN.matches(id)

    private class State(
        val migrated: Boolean,
        val keys: MutableList<NamedKey>,
        var activeId: String?,
        val pendingDeletes: MutableSet<String> = linkedSetOf(),
    )

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_NAME_LENGTH = 80
        private const val LEGACY_ID = "legacy"
        private const val LEGACY_NAME = "原有密钥"
        private val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        private fun storageFailure() = IllegalStateException("密钥存储不可用。")
    }
}
