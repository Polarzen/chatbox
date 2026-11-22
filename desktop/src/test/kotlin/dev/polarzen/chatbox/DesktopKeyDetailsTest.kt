package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopKeyDetailsTest {
    @Test fun ownerSettingsUriPreservesDeploymentPath() {
        assertEquals(
            "https://example.invalid/astrbot/#/settings?section=settings-openapi",
            desktopKeyDetailsSettingsUri("https://example.invalid/astrbot/").toASCIIString(),
        )
        assertEquals(
            "https://example.invalid/#/settings?section=settings-openapi",
            desktopKeyDetailsSettingsUri("https://example.invalid").toASCIIString(),
        )
    }

    @Test fun ownerSettingsUriAllowsOnlyValidatedBases() {
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
