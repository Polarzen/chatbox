package dev.polarzen.chatbox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class AndroidNamedKey(val id: String, val name: String, val sealedToken: String)
internal data class AndroidService(val base: String, val name: String, val keys: List<AndroidNamedKey> = emptyList())
internal data class AndroidConnectionCatalog(
    val services: List<AndroidService>, val base: String = "", val keyId: String? = null,
)

private fun serviceAddress(value: String): String {
    // Keep the legacy local-history namespace, including the server's case-sensitive path.
    val address = value.trim().trimEnd('/')
    val uri = endpoint(address)
    require(uri.port == -1 || uri.port in 1..65535)
    return AndroidHistory.normalizeBase(address)
}

internal interface AndroidConnectionStorage {
    fun load(): AndroidConnectionCatalog
    fun save(catalog: AndroidConnectionCatalog)
    fun seal(base: String, id: String, token: String): String
    fun open(base: String, key: AndroidNamedKey): String
    fun verify(base: String, token: String)
}

private class DeviceConnectionStorage(context: Context, private val legacyBases: List<String>) : AndroidConnectionStorage {
    private val prefs = context.getSharedPreferences("personal_chat", Context.MODE_PRIVATE)
    private val identity by lazy { AndroidHistory(context).use { it.user } }
    private val file = context.noBackupFilesDir.toPath().resolve("connections.v1.json")

    override fun load(): AndroidConnectionCatalog {
        val saved = try { String(Files.readAllBytes(file), Charsets.UTF_8) } catch (_: NoSuchFileException) { null }
        if (saved == null) {
            val previous = prefs.getString("base", "").orEmpty()
            val addresses = (legacyBases + previous).mapNotNull { runCatching { serviceAddress(it) }.getOrNull() }.distinct()
            return AndroidConnectionCatalog(addresses.map { AndroidService(it, URI(it).host) },
                runCatching { serviceAddress(previous) }.getOrDefault(""))
        }
        val record = JSONObject(saved)
        val profiles = record.getJSONArray("services")
        val services = List(profiles.length()) { index ->
            val service = profiles.getJSONObject(index)
            val address = serviceAddress(service.getString("base"))
            val keys = service.getJSONArray("keys")
            AndroidService(address, service.getString("name"), List(keys.length()) {
                val item = keys.getJSONObject(it)
                AndroidNamedKey(item.getString("id"), item.getString("name"), item.getString("sealed"))
            })
        }
        return AndroidConnectionCatalog(services, record.getString("base"), record.optString("keyId").ifBlank { null })
    }

    override fun save(catalog: AndroidConnectionCatalog) {
        val profiles = JSONArray()
        catalog.services.forEach { service ->
            val keys = JSONArray()
            service.keys.forEach { key ->
                keys.put(JSONObject().put("id", key.id).put("name", key.name).put("sealed", key.sealedToken))
            }
            profiles.put(JSONObject().put("base", service.base).put("name", service.name).put("keys", keys))
        }
        val json = JSONObject().put("services", profiles).put("base", catalog.base).put("keyId", catalog.keyId.orEmpty())
        val temp = Files.createTempFile(file.parent, "connections-", ".tmp")
        try {
            FileOutputStream(temp.toFile()).use {
                it.write(json.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun encryptionKey(create: Boolean): SecretKey {
        val alias = "chatbox.named-keys.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override fun seal(base: String, id: String, token: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(true))
        check(cipher.iv.size == 12)
        cipher.updateAAD("$base\u0000$id".toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
    }

    override fun open(base: String, key: AndroidNamedKey): String {
        val bytes = Base64.getDecoder().decode(key.sealedToken)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(false), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD("$base\u0000${key.id}".toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    override fun verify(base: String, token: String) {
        val username = URLEncoder.encode(identity, "UTF-8")
        val uri = URI("${endpoint(base).toASCIIString()}/sessions?page=1&page_size=1&platform_id=webchat&username=$username")
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            when (val code = connection.responseCode) {
                401, 403 -> throw ConnectionIssue("服务拒绝了这把密钥的会话查询。")
                in 300..399 -> throw ConnectionIssue("请直接填写最终的 HTTPS 服务地址。")
                !in 200..299 -> throw ConnectionIssue("会话查询失败（HTTP $code）。")
            }
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val data = json.getJSONObject("data")
            val sessions = data.getJSONArray("sessions")
            check(json.getString("status") == "ok" && data.getInt("page") == 1 && data.getInt("page_size") == 1)
            val total = data.getInt("total")
            check(total >= 0 && sessions.length() == minOf(total, 1))
            if (sessions.length() > 0) sessions.getJSONObject(0)
        } finally {
            connection.disconnect()
        }
    }
}

private class ConnectionIssue(message: String) : Exception(message)

/** Selection and edits run on the UI thread. Only the read-only candidate probe leaves that thread. */
internal class AndroidConnections internal constructor(private val storage: AndroidConnectionStorage) {
    constructor(context: Context, legacyBases: List<String>) : this(DeviceConnectionStorage(context.applicationContext, legacyBases))

    private var catalog by mutableStateOf(storage.load())
    val services get() = catalog.services
    val base get() = catalog.base
    val keyId get() = catalog.keyId
    private val current get() = services.find { it.base == base }
    private val activeKey get() = current?.keys?.find { it.id == keyId }
    val keyName get() = activeKey?.name.orEmpty()
    private var activeToken by mutableStateOf(activeKey?.let { runCatching { storage.open(base, it) }.getOrNull() })
    private var verifiedNow by mutableStateOf(false)
    val status get() = when {
        verifiedNow -> "已验证"
        activeToken != null -> "已恢复上次验证的连接"
        keyId != null -> "本机无法读取活动密钥"
        else -> "离线"
    }
    fun token(): String? = activeToken

    /** Resolve the credential captured by a queued native upload without selecting its profile. */
    fun savedToken(address: String, id: String): String? {
        val key = services.singleOrNull { it.base == address }?.keys?.singleOrNull { it.id == id } ?: return null
        return try { storage.open(address,key) } catch (_: Exception) { null }
    }
    private var revision = 0L

    private fun save(next: AndroidConnectionCatalog) {
        storage.save(next)
        catalog = next
        revision++
    }

    private fun service(base: String) = services.single { it.base == base }
    private fun replace(service: AndroidService, selectedKey: String? = keyId) {
        save(catalog.copy(services = services.map { if (it.base == service.base) service else it }, keyId = selectedKey))
    }
    private fun name(value: String) = value.trim().also { if (it.isBlank()) throw ConnectionIssue("请填写名称。") }
    private fun keyLabel(service: AndroidService, value: String, except: String? = null): String {
        val label = name(value)
        if (label.length > 80) throw ConnectionIssue("密钥名称最多 80 个字符。")
        if (service.keys.any { it.id != except && it.name.equals(label, ignoreCase = true) }) {
            throw ConnectionIssue("此档案已有同名密钥。")
        }
        return label
    }

    fun addService(label: String, address: String): String {
        val canonical = runCatching { serviceAddress(address) }.getOrElse { throw ConnectionIssue("请填写有效的 HTTPS 服务地址。") }
        if (services.any { it.base == canonical }) throw ConnectionIssue("此服务地址已保存。")
        save(catalog.copy(services = services + AndroidService(canonical, name(label))))
        return canonical
    }

    fun renameService(base: String, label: String) = replace(service(base).copy(name = name(label)))

    fun removeService(address: String) {
        val selected = address == base
        save(catalog.copy(services = services - service(address), base = if (selected) "" else base,
            keyId = if (selected) null else keyId))
        if (selected) clearBinding()
    }

    fun addKey(address: String, label: String, token: String): String {
        val service = service(address)
        val displayName = keyLabel(service, label)
        if (token.isBlank() || token.any { it.code !in 33..126 }) throw ConnectionIssue("请填写有效的聊天 API 密钥。")
        val id = UUID.randomUUID().toString()
        val key = AndroidNamedKey(id, displayName, storage.seal(address, id, token))
        replace(service.copy(keys = service.keys + key))
        return id
    }

    fun renameKey(address: String, id: String, label: String) {
        val service = service(address)
        val displayName = keyLabel(service, label, id)
        replace(service.copy(keys = service.keys.map { if (it.id == id) it.copy(name = displayName) else it }))
    }

    fun removeKey(address: String, id: String) {
        val service = service(address)
        val selected = address == base && id == keyId
        replace(service.copy(keys = service.keys.filterNot { it.id == id }), if (selected) null else keyId)
        if (selected) clearBinding()
    }

    private fun clearBinding() {
        activeToken = null
        verifiedNow = false
    }

    /** Notification navigation may preserve an existing connection to the same saved service. */
    fun selectOffline(base: String): Boolean {
        if (base == this.base) {
            revision++
            return true
        }
        return openHistory(base)
    }

    fun openHistory(base: String): Boolean {
        if (base.isNotEmpty() && services.none { it.base == base }) return false
        return runCatching {
            save(catalog.copy(base = base, keyId = null))
            clearBinding()
            true
        }.getOrDefault(false)
    }

    suspend fun verify(address: String, id: String) {
        val key = service(address).keys.single { it.id == id }
        val attempt = ++revision
        val token = withContext(Dispatchers.IO) {
            val secret = storage.open(address, key)
            storage.verify(address, secret)
            secret
        }
        if (attempt != revision) throw ConnectionIssue("连接选择已变化，请重新验证。")
        save(catalog.copy(base = address, keyId = id))
        activeToken = token
        verifiedNow = true
    }
}

internal fun connectionFailure(error: Exception): String = when (error) {
    is CancellationException -> throw error
    is ConnectionIssue -> error.message.orEmpty()
    else -> "操作失败，请检查网络、证书及本机密钥存储。"
}
