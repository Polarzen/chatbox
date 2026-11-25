package dev.polarzen.chatbox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidSharedCipher(private val name: String, private val scope: ByteArray) {
    private val provider = "AndroidKeyStore"
    private val ivSize = 12

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

    fun decrypt(bytes: ByteArray): String {
        require(bytes.size >= ivSize + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes, 0, ivSize))
        cipher.updateAAD(scope)
        return String(cipher.doFinal(bytes, ivSize, bytes.size - ivSize), Charsets.UTF_8)
    }

    fun encrypt(value: String, create: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create))
        cipher.updateAAD(scope)
        check(cipher.iv.size == ivSize)
        return cipher.iv + cipher.doFinal(value.toByteArray())
    }
}

/** Read-only compatibility for records created before device pairing. */
internal class AndroidSharedStorage(context: Context, base: String, session: String) {
    private val scope = MessageDigest.getInstance("SHA-256").digest("$base\u0000$session".toByteArray())
    private val name = "shared-" + Base64.encodeToString(scope, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private val cipher = AndroidSharedCipher(name, scope)
    private val file = context.noBackupFilesDir.toPath().resolve(name)

    fun hasState(): Boolean = !Files.notExists(file)

    fun load(): String? = try {
        cipher.decrypt(Files.readAllBytes(file))
    } catch (_: NoSuchFileException) {
        null
    }
}
