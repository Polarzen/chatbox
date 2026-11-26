package dev.polarzen.chatbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One local theme and one paired cache. Dropping a grant also drops its unpublished changes. */
internal class SharedThemeState(dataDir: Path, legacy: ChatTheme) {
    private data class Journal(
        val personal: ChatTheme, val authority: String? = null, val paired: ChatTheme = personal,
        val revision: Long? = null, val pending: Boolean = false,
    ) {
        val displayed get() = if (authority == null) personal else paired
    }

    private val file = dataDir.resolve("chat-theme.json").toFile()
    private var journal = runCatching {
        val json = JSONObject(file.readText())
        val personal = ChatTheme.fromJson(json.getJSONObject("personal"))
        Journal(personal, json.optString("authority").takeIf(String::isNotBlank),
            json.optJSONObject("paired")?.let { ChatTheme.fromJson(it) } ?: personal,
            json.optLong("revision", -1L).takeIf { it >= 0 }, json.optBoolean("pending"))
    }.getOrElse { Journal(legacy) }
    // Reveal a restored paired cache only after the platform has loaded the matching grant.
    var theme by mutableStateOf(journal.personal)
        private set
    var issue by mutableStateOf("")
        private set

    private var needsSave = false

    private fun save(next: Journal) {
        if (next == journal && !needsSave) return
        val json = JSONObject().put("personal", next.personal.toJson())
        if (next.authority != null) {
            json.put("authority", next.authority).put("paired", next.paired.toJson())
                .put("revision", next.revision).put("pending", next.pending)
        }
        val parent = requireNotNull(file.parentFile)
        Files.createDirectories(parent.toPath())
        val tmp = File.createTempFile("chat-theme-", ".tmp", parent)
        try {
            tmp.outputStream().use { stream ->
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
        journal = next
        needsSave = false
        theme = next.displayed
    }

    @Synchronized
    fun applyTheme(value: ChatTheme) {
        if (theme == value) return
        save(if (journal.authority == null) journal.copy(personal = value, paired = value)
             else journal.copy(paired = value, pending = true))
        issue = ""
    }

    private fun identity(instance: String, device: String) = JSONArray(listOf(instance, device)).toString()

    @Synchronized
    fun attach(instance: String?, device: String?) {
        val authority = if (instance != null && device != null) identity(instance, device) else null
        if (authority != journal.authority) {
            // Never display, or send, the old grant's data after an account switch, even on a disk error.
            journal = Journal(journal.personal, authority)
            needsSave = true
            issue = ""
        }
        theme = journal.displayed
        if (needsSave) try {
            save(journal)
        } catch (_: java.io.IOException) {
            issue = "本机外观设置暂未保存，请检查存储空间。"
        }
    }

    private fun current(authority: String) = synchronized(this) { journal.takeIf { it.authority == authority } }

    /** Caller serializes sync requests. User edits can still happen while the HTTP request is in flight. */
    suspend fun synchronize(api: SharedDeviceTransport, binding: DeviceBinding): String =
        synchronize(binding, { since -> api.theme(binding, since) }, { revision, value -> api.updateTheme(binding, revision, value) })

    internal suspend fun synchronize(
        binding: DeviceBinding, fetch: suspend (Long?) -> SharedThemeSnapshot,
        publish: suspend (Long, ChatTheme) -> Long,
    ): String = withContext(Dispatchers.IO) {
        val authority = identity(binding.instance, binding.device)
        val started = current(authority) ?: return@withContext ""
        try {
            val remote = fetch(started.revision)
            var lost = false
            val toPublish = synchronized(this@SharedThemeState) {
                val now = current(authority) ?: return@synchronized null
                if (remote.changed) {
                    // For a newly paired device pull an existing theme. An empty service can be seeded
                    // from its local appearance. Keep edits made while this GET was running.
                    val canonical = remote.theme ?: ChatTheme()
                    val seed = now.revision == null && remote.revision == 0L && remote.theme == null && now.paired != canonical
                    val concurrentEdit = now.pending && (!started.pending || now.paired != started.paired)
                    val keep = now.paired != canonical && (seed || concurrentEdit)
                    lost = now.pending && now.paired != canonical && !keep
                    save(now.copy(revision = remote.revision, paired = if (keep) now.paired else canonical, pending = keep))
                }
                journal.takeIf { it.pending }
            }
            if (toPublish != null) {
                val revision = publish(checkNotNull(toPublish.revision), toPublish.paired)
                synchronized(this@SharedThemeState) {
                    val now = current(authority)
                    if (now != null) save(now.copy(revision = revision, pending = now.paired != toPublish.paired))
                }
            }
            if (lost) CONFLICT else ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SharedHttpFailure) {
            if (failure.status == 401 || failure.status == 403) throw failure
            if (failure.status == 404) "外观已保存在本机。请更新电脑端，并在设备设置中停止后重新启动共享服务。"
            else "外观同步待重试（HTTP ${failure.status}）。"
        } catch (_: Exception) {
            "外观同步待重试。"
        }
    }.also { result ->
        synchronized(this@SharedThemeState) {
            if (journal.authority == identity(binding.instance, binding.device) && (result.isNotEmpty() || issue != CONFLICT)) issue = result
        }
    }

    private companion object {
        const val CONFLICT = "外观已采用另一台设备先保存的版本。"
    }
}
