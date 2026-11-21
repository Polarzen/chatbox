package dev.polarzen.chatbox

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class DesktopCredentialsTest {
    @Test fun windowsCredentialStorageProtectsProfileSecretsAndSupportsUpdateClear() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val directory = Files.createTempDirectory("chatbox-credentials-")
        try {
            val credentials = DesktopCredentials(directory)
            val profileA = "profile-a"
            val profileB = "profile-b"
            val firstSecret = "fixture-secret-profile-a-7b2d"
            val updatedSecret = "fixture-secret-profile-a-updated-18c4"
            val secondSecret = "fixture-secret-profile-b-963a"

            credentials.save(profileA, firstSecret)
            credentials.save(profileB, secondSecret)
            assertEquals(firstSecret, DesktopCredentials(directory).load(profileA))
            assertEquals(secondSecret, credentials.load(profileB))
            assertCiphertextOnly(directory, firstSecret, secondSecret)

            credentials.save(profileA, updatedSecret)
            assertEquals(updatedSecret, DesktopCredentials(directory).load(profileA))
            assertEquals(secondSecret, credentials.load(profileB))
            assertCiphertextOnly(directory, firstSecret, updatedSecret, secondSecret)

            credentials.clear(profileA)
            assertNull(credentials.load(profileA))
            assertEquals(secondSecret, DesktopCredentials(directory).load(profileB))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun assertCiphertextOnly(directory: Path, vararg secrets: String) {
        Files.list(directory).use { files ->
            assertEquals(2L, files.count())
        }
        Files.list(directory).use { files ->
            files.forEach { file ->
                val stored = String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
                secrets.forEach { secret -> assertFalse(stored.contains(secret)) }
            }
        }
    }
}
