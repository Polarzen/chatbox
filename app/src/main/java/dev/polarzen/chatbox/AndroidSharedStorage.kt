package dev.polarzen.chatbox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidSharedStorage(context: Context, base: String, session: String) : SharedLocalStorage {
    private companion object {
        val lock = Any()
        const val provider = "AndroidKeyStore"
        const val ivSize = 12
    }

    private val scope = MessageDigest.getInstance("SHA-256").digest("$base\u0000$session".toByteArray())
    private val name = "shared-" + Base64.encodeToString(scope, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private val file = context.noBackupFilesDir.toPath().resolve(name)

    var hasRecord by mutableStateOf(hasState())
        private set

    fun hasState(): Boolean = synchronized(lock) { !Files.notExists(file) }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(provider).apply { load(null) }
        if (store.containsAlias(name)) return checkNotNull(store.getKey(name, null) as? SecretKey)
        check(create) { "无法读取此会话的本机密钥。" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
        generator.init(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    override fun load(): String? = synchronized(lock) {
        val bytes = try {
            Files.readAllBytes(file)
        } catch (_: NoSuchFileException) {
            return@synchronized null
        }
        require(bytes.size >= ivSize + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes, 0, ivSize))
        cipher.updateAAD(scope)
        String(cipher.doFinal(bytes, ivSize, bytes.size - ivSize), Charsets.UTF_8)
    }

    override fun save(value: String?) {
        synchronized(lock) {
            if (value == null) {
                // The panel requests removal only after the user explicitly leaves this device.
                Files.deleteIfExists(file)
                hasRecord = false
                return
            }
            // Authenticate the previous record before replacing it.
            val existing = load()
            if (existing == value) return
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = existing == null))
            cipher.updateAAD(scope)
            val iv = cipher.iv
            check(iv.size == ivSize)
            val encrypted = cipher.doFinal(value.toByteArray())
            val temp = Files.createTempFile(file.parent, name, ".tmp")
            try {
                FileOutputStream(temp.toFile()).use {
                    it.write(iv)
                    it.write(encrypted)
                    it.fd.sync()
                }
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                hasRecord = true
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }
}
