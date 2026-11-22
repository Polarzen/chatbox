package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopKeyDetailsTest {
    @Test fun standardAstrBotKeyUsesPublishedTwelveCharacterPrefix() {
        val token = "abk_ABCDEFGHijklmnopqrstuvwxyz0123456789_-ABCDE"
        assertEquals(47, token.length)
        assertEquals("abk_ABCDEFGH…", desktopKeyMarker(token))
    }

    @Test fun customAndShortKeysUseOnlyLabeledFingerprint() {
        listOf("manual-secret-value", "x", "abk_short", "abk_" + "a".repeat(42) + "!").forEach { token ->
            val marker = desktopKeyMarker(token)
            assertTrue(marker.startsWith("Fingerprint (SHA-256): "))
            assertEquals("Fingerprint (SHA-256): ".length + 12, marker.length)
            assertFalse(marker.contains(token))
        }
    }

    @Test fun webUiUriPreservesDeploymentPathAndUsesOwnerRoute() {
        assertEquals(
            "https://example.invalid/astrbot/#/settings?section=settings-openapi",
            desktopKeyDetailsSettingsUri("https://example.invalid/astrbot/").toASCIIString(),
        )
        assertEquals(
            "https://example.invalid/#/settings?section=settings-openapi",
            desktopKeyDetailsSettingsUri("https://example.invalid").toASCIIString(),
        )
    }

    @Test fun webUiUriUsesOnlyValidatedBaseAndRejectsUnsafeComponents() {
        assertEquals(
            "http://127.0.0.1:6185/astrbot/#/settings?section=settings-openapi",
            desktopKeyDetailsSettingsUri("http://127.0.0.1:6185/astrbot").toASCIIString(),
        )
        listOf(
            "https://user:secret@example.invalid",
            "https://example.invalid?token=secret",
            "https://example.invalid/#existing-route",
            "http://example.invalid",
        ).forEach { address ->
            assertTrue(runCatching { desktopKeyDetailsSettingsUri(address) }.isFailure)
        }
    }
}
