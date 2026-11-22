package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class NamedKeysTest {
    @Test fun profilesAreIsolatedNamesAreUniqueAndSecretsRemainEncrypted() {
        assumeWindows()
        val directory = Files.createTempDirectory("chatbox-named-keys-")
        try {
            val credentials = DesktopCredentials(directory)
            val keys = NamedKeys(directory, credentials)
            val secretA = "fixture-secret-profile-a-b7d9"
            val secretB = "fixture-secret-profile-b-2c41"
            val keyA = keys.add("profile-a", " Primary ", secretA)
            val keyB = keys.add("profile-b", "primary", secretB)

            assertEquals("Primary", keys.list("profile-a").keys.single().name)
            assertEquals("primary", keys.list("profile-b").keys.single().name)
            assertEquals(secretA, keys.secret("profile-a", keyA.id))
            assertEquals(secretB, keys.secret("profile-b", keyB.id))
            val fileCountBeforeRejectedAdds = Files.list(directory).use { it.count() }
            assertThrows(IllegalArgumentException::class.java) { keys.add("profile-a", "Blank", " \t ") }
            val duplicateSecret = "duplicate-secret-should-not-be-stored"
            val duplicateError = assertThrows(IllegalArgumentException::class.java) {
                keys.add("profile-a", " pRiMaRy ", duplicateSecret)
            }
            assertFalse(duplicateError.message.orEmpty().contains(duplicateSecret))
            assertEquals(fileCountBeforeRejectedAdds, Files.list(directory).use { it.count() })
            assertEquals(1, keys.list("profile-a").keys.size)
            assertNull(credentials.loadNamed("profile-a", keyB.id))
            Files.list(directory).use { files ->
                files.forEach { file ->
                    val stored = String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
                    assertFalse(stored.contains(secretA))
                    assertFalse(stored.contains(secretB))
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun migratesLegacyBlobOnceAndPersistsRenameAcrossRestarts() {
        assumeWindows()
        val directory = Files.createTempDirectory("chatbox-named-migration-")
        try {
            val credentials = DesktopCredentials(directory)
            val profile = "legacy-profile"
            val legacySecret = "fixture-legacy-secret-d9a1"
            credentials.save(profile, legacySecret)

            var list = NamedKeys(directory, credentials).list(profile)
            assertEquals(NamedKey("legacy", "原有密钥"), list.keys.single())
            assertEquals("legacy", list.activeId)
            assertEquals(legacySecret, NamedKeys(directory, credentials).secret(profile, "legacy"))
            NamedKeys(directory, credentials).rename(profile, "legacy", "重命名")

            repeat(2) {
                val restarted = NamedKeys(directory, DesktopCredentials(directory))
                list = restarted.list(profile)
                assertEquals(listOf(NamedKey("legacy", "重命名")), list.keys)
                assertEquals("legacy", list.activeId)
                assertEquals(legacySecret, restarted.secret(profile, "legacy"))
            }

            NamedKeys(directory, credentials).remove(profile, "legacy")
            val afterRemoval = NamedKeys(directory, credentials)
            assertEquals(emptyList<NamedKey>(), afterRemoval.list(profile).keys)
            assertNull(afterRemoval.list(profile).activeId)
            assertNull(credentials.load(profile))
            credentials.save(profile, "fixture-secret-after-migration-should-stay-unlisted")
            assertEquals(emptyList<NamedKey>(), NamedKeys(directory, credentials).list(profile).keys)
            credentials.clear(profile)
            assertEquals(emptyList<NamedKey>(), NamedKeys(directory, credentials).list(profile).keys)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun removingActiveKeyClearsSelectionAndSecretBlob() {
        assumeWindows()
        val directory = Files.createTempDirectory("chatbox-named-remove-")
        try {
            val credentials = DesktopCredentials(directory)
            val keys = NamedKeys(directory, credentials)
            val first = keys.add("profile", "First", "fixture-secret-first-374a")
            val second = keys.add("profile", "Second", "fixture-secret-second-a983")
            keys.activate("profile", first.id)

            keys.remove("profile", first.id)

            assertNull(keys.list("profile").activeId)
            assertEquals(listOf(second), keys.list("profile").keys)
            assertNull(credentials.loadNamed("profile", first.id))
            assertEquals("fixture-secret-second-a983", keys.secret("profile", second.id))
            assertThrows(IllegalArgumentException::class.java) { keys.secret("profile", first.id) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun assumeWindows() = assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
}
