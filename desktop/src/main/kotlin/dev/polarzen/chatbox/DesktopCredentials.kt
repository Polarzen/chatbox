package dev.polarzen.chatbox

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Stores profile credentials encrypted for the current Windows user. */
internal class DesktopCredentials(private val directory: Path) {
    fun contains(profileId: String): Boolean = Files.isRegularFile(fileFor(profileHash(profileId)))

    fun save(profileId: String, credential: String) {
        requireWindows()
        val profile = profileHash(profileId)
        saveAt(fileFor(profile), profile, credential)
    }

    fun saveNamed(profileId: String, keyId: String, credential: String) {
        requireWindows()
        val reference = namedReferenceHash(profileId, keyId)
        saveAt(namedFileFor(reference), reference, credential)
    }

    private fun saveAt(target: Path, entropy: ByteArray, credential: String) {
        val plain = credential.toByteArray(StandardCharsets.UTF_8)
        val protected = try {
            transform(entropy, plain, protect = true)
        } finally {
            plain.fill(0)
        }

        try {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "credential-", ".tmp")
            try {
                Files.write(temporary, protected)
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Windows credential storage requires atomic file replacement.")
            } finally {
                Files.deleteIfExists(temporary)
            }
        } finally {
            protected.fill(0)
        }
    }

    fun load(profileId: String): String? {
        requireWindows()
        val profile = profileHash(profileId)
        return loadAt(fileFor(profile), profile)
    }

    fun loadNamed(profileId: String, keyId: String): String? {
        requireWindows()
        val reference = namedReferenceHash(profileId, keyId)
        return loadAt(namedFileFor(reference), reference)
    }

    private fun loadAt(file: Path, entropy: ByteArray): String? {
        val encrypted = try {
            Files.readAllBytes(file)
        } catch (_: NoSuchFileException) {
            return null
        }
        val plain = try {
            transform(entropy, encrypted, protect = false)
        } finally {
            encrypted.fill(0)
        }
        return try {
            String(plain, StandardCharsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    fun clear(profileId: String) {
        requireWindows()
        Files.deleteIfExists(fileFor(profileHash(profileId)))
    }

    fun clearNamed(profileId: String, keyId: String) {
        requireWindows()
        Files.deleteIfExists(namedFileFor(namedReferenceHash(profileId, keyId)))
    }

    private fun fileFor(profileHash: ByteArray): Path =
        directory.resolve("credential-${Base64.getUrlEncoder().withoutPadding().encodeToString(profileHash)}.bin")

    private fun namedFileFor(referenceHash: ByteArray): Path =
        directory.resolve("named-credential-${Base64.getUrlEncoder().withoutPadding().encodeToString(referenceHash)}.bin")

    private fun profileHash(profileId: String): ByteArray {
        require(profileId.isNotBlank()) { "Profile id must not be blank." }
        return MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(StandardCharsets.UTF_8))
    }

    private fun namedReferenceHash(profileId: String, keyId: String): ByteArray {
        require(profileId.isNotBlank() && keyId.isNotBlank()) { "Credential reference is required." }
        return MessageDigest.getInstance("SHA-256").run {
            update(profileId.toByteArray(StandardCharsets.UTF_8))
            update(0.toByte())
            update(keyId.toByteArray(StandardCharsets.UTF_8))
            digest()
        }
    }

    private fun transform(profileHash: ByteArray, bytes: ByteArray, protect: Boolean): ByteArray {
        val script = powershellScript
        val encodedScript = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
        val process = try {
            val systemRoot = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Windows credential protection failed.")
            val powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
            ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedScript)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: Exception) {
            throw IllegalStateException("Windows credential protection failed.")
        }

        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readBytes() } }
        val inputBytes = Base64.getEncoder().encode(bytes)
        val entropyBytes = Base64.getEncoder().encode(profileHash)
        try {
            process.outputStream.use { stream ->
                stream.write(if (protect) "protect\n".toByteArray(StandardCharsets.US_ASCII) else "unprotect\n".toByteArray(StandardCharsets.US_ASCII))
                stream.write(entropyBytes)
                stream.write('\n'.code)
                stream.write(inputBytes)
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw IllegalStateException("Windows credential protection failed.")
            }
            val response = output.get(5, TimeUnit.SECONDS)
            if (process.exitValue() != 0) throw IllegalStateException("Windows credential protection failed.")
            return Base64.getDecoder().decode(response.toString(StandardCharsets.US_ASCII).trim())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Windows credential protection failed.")
        } catch (_: Exception) {
            throw IllegalStateException("Windows credential protection failed.")
        } finally {
            inputBytes.fill(0)
            entropyBytes.fill(0)
            if (process.isAlive) process.destroyForcibly()
            output.cancel(true)
        }
    }

    private fun requireWindows() {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "Windows credential storage is available only on Windows."
        }
    }

    private val powershellScript = """
        %ErrorActionPreference = 'Stop'
        try {
            Add-Type -AssemblyName System.Security
            %reader = [Console]::In
            %operation = %reader.ReadLine()
            %entropy = [Convert]::FromBase64String(%reader.ReadLine())
            %source = [Convert]::FromBase64String(%reader.ReadLine())
            if (%operation -eq 'protect') {
                %result = [Security.Cryptography.ProtectedData]::Protect(%source, %entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
            } elseif (%operation -eq 'unprotect') {
                %result = [Security.Cryptography.ProtectedData]::Unprotect(%source, %entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
            } else {
                throw 'invalid operation'
            }
            [Console]::Out.Write([Convert]::ToBase64String(%result))
            [Array]::Clear(%source, 0, %source.Length)
            [Array]::Clear(%entropy, 0, %entropy.Length)
            [Array]::Clear(%result, 0, %result.Length)
            exit 0
        } catch {
            [Console]::Error.Write('Credential operation failed')
            exit 1
        }
    """.trimIndent().replace('%', '$')
}
