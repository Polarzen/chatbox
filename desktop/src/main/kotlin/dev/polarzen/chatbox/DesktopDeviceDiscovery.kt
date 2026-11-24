package dev.polarzen.chatbox

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener

internal data class DesktopSourceChoice(val id: String, val address: String, val name: String)
internal data class DesktopDiscovery(val names: Map<String, String>, val deployment: DesktopSourceChoice?, val database: String?)

/** Labels are display hints only. Live Launcher process ownership and the existing key probe establish identity. */
internal fun launcherDisplayNames(file: Path, instances: List<LauncherInstance>): Map<String, String> {
    if (instances.isEmpty() || !Files.isRegularFile(file)) return emptyMap()
    // The Launcher writes a serde JSON AppManifest in its Redb registry. Ignore old revisions with
    // conflicting labels. Never use a registry address to authenticate a process or bind a credential.
    return runCatching {
        if (Files.size(file) > 16 * 1024 * 1024) return emptyMap()
        val registry = Files.readAllBytes(file).toString(Charsets.UTF_8)
        val names = mutableMapOf<String, MutableSet<String>>()
        var start = registry.indexOf("{\"instances\":{")
        while (start >= 0) {
            val tokener = JSONTokener(registry.reader().apply { skip(start.toLong()) })
            val rows = runCatching { JSONObject(tokener).getJSONObject("instances") }.getOrNull()
            for ((id, _) in instances) {
                val name = rows?.optJSONObject(id)?.optString("name")?.trim() ?: continue
                if (name.length in 1..100 && name.none(Char::isISOControl)) names.getOrPut(id) { mutableSetOf() }.add(name)
            }
            start = registry.indexOf("{\"instances\":{", start + 1)
        }
        names.mapNotNull { (id, labels) -> labels.singleOrNull()?.let { id to it } }.toMap()
    }.getOrDefault(emptyMap())
}

private interface DeploymentSqlite : Library {
    fun sqlite3_open_v2(filename: String, handle: PointerByReference, flags: Int, vfs: Pointer?): Int
    fun sqlite3_prepare_v2(db: Pointer, sql: String, size: Int, out: PointerByReference, tail: Pointer?): Int
    fun sqlite3_step(statement: Pointer): Int
    fun sqlite3_column_text(statement: Pointer, col: Int): Pointer?
    fun sqlite3_finalize(statement: Pointer): Int
    fun sqlite3_close(db: Pointer): Int
}

/** Read only the public upstream identity from the documented 0.1.18 deployment. */
private fun deployedSource(path: Path): DesktopSourceChoice? {
    if (!Files.isRegularFile(path)) return null
    return runCatching {
        val dll = Path.of(System.getenv("SystemRoot") ?: return null, "System32", "winsqlite3.dll")
        val sqlite = Native.load(dll.toString(), DeploymentSqlite::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        val handle = PointerByReference()
        val rc = sqlite.sqlite3_open_v2(path.toString(), handle, 1, null) // SQLITE_OPEN_READONLY
        val db = handle.value ?: return null
        try {
            if (rc != 0) return null
            val output = PointerByReference()
            if (sqlite.sqlite3_prepare_v2(db, "SELECT upstream,launcher_id FROM config LIMIT 1", -1, output, null) != 0) return null
            val statement = output.value ?: return null
            try {
                if (sqlite.sqlite3_step(statement) != 100) return null // SQLITE_ROW
                val base = sqlite.sqlite3_column_text(statement, 0)?.getString(0, "UTF-8") ?: return null
                val url = sharedAddress(base, allowLoopback = true)
                val id = sqlite.sqlite3_column_text(statement, 1)?.getString(0, "UTF-8")?.takeIf { it.isNotBlank() }
                val source = if (id == null) "manual:$url" else "launcher:" + UUID.fromString(id).toString()
                DesktopSourceChoice(source, url, "已部署的 AstrBot")
            } finally { sqlite.sqlite3_finalize(statement) }
        } finally { sqlite.sqlite3_close(db) }
    }.getOrNull()
}

internal fun discoverDesktopSources(instances: List<LauncherInstance>): DesktopDiscovery {
    val names = launcherDisplayNames(Path.of(System.getProperty("user.home"), ".astrbot_launcher", "data.redb"), instances)
    val db = System.getenv("LOCALAPPDATA")?.let { Path.of(it, "ChatboxShared", "history.sqlite3") }
    val deployment = db?.let(::deployedSource)
    return DesktopDiscovery(names, deployment, db?.takeIf { deployment != null }?.toString())
}

internal fun desktopSourceChoices(
    instances: List<LauncherInstance>, profiles: List<Profile>, discovered: DesktopDiscovery?,
): List<DesktopSourceChoice> {
    val rows = linkedMapOf<String, DesktopSourceChoice>()
    for ((id, address) in instances) {
        val name = discovered?.names?.get(id) ?: "AstrBot Launcher · " + id.take(8)
        rows["launcher:$id"] = DesktopSourceChoice("launcher:$id", address, name)
    }
    for (p in profiles.filter { !it.offlineOnly && it.kind != "device" && it.kind != "shared" && it.address.isNotBlank() }) {
        rows.putIfAbsent(p.id, DesktopSourceChoice(p.id, p.address, profileDisplayName(p)))
    }
    discovered?.deployment?.let { deployed ->
        if (rows.values.none { it.address == deployed.address }) rows.putIfAbsent(deployed.id, deployed)
    }
    return rows.values.toList()
}
