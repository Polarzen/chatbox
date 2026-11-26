package dev.polarzen.chatbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedThemeStateTest {
    @get:Rule val temp = TemporaryFolder()
    private val dark = ChatTheme(dark = true)
    private val light = ChatTheme()
    private val device = DeviceBinding.create("https://example.org", "instance")

    /** Simulates a second device changing the shared value, including conditional GETs. */
    private class Authority {
        var revision = 0L
        var theme: ChatTheme? = null
        fun read(since: Long?) = SharedThemeSnapshot(revision = revision,
            theme = if (since == revision) null else theme, changed = since != revision)
        fun put(expected: Long, value: ChatTheme): Long {
            assertEquals(revision, expected)
            theme = value
            return ++revision
        }
    }

    @Test fun offlineChangeSurvivesRestartAndReachesAnotherDevice() = runBlocking {
        val dir = temp.newFolder().toPath()
        val server = Authority()
        val state = SharedThemeState(dir, light)
        state.attach(device.instance, device.device)
        state.synchronize(device, server::read, server::put)
        state.applyTheme(dark)
        state.synchronize(device, { throw java.io.IOException("offline") }, server::put)
        assertNull(server.theme)
        val reopened = SharedThemeState(dir, light)
        assertEquals(light, reopened.theme)
        reopened.attach(device.instance, device.device)
        assertEquals(dark, reopened.theme)
        reopened.synchronize(device, server::read, server::put)
        val other = DeviceBinding.create("https://example.org", device.instance)
        val reader = SharedThemeState(temp.newFolder().toPath(), light)
        reader.attach(other.instance, other.device)
        reader.synchronize(other, server::read, server::put)
        assertEquals(dark, reader.theme)
        assertEquals(1L, server.revision)
    }

    @Test fun olderPendingValueCannotOverwriteNewerRevisionOrCrossBindings() = runBlocking {
        val server = Authority()
        server.put(0, light)
        val state = SharedThemeState(temp.newFolder().toPath(), dark)
        state.attach(device.instance, device.device)
        state.synchronize(device, server::read, server::put)
        assertEquals(light, state.theme)
        state.applyTheme(dark)
        server.put(1, light)
        val message = state.synchronize(device, server::read, server::put)
        assertTrue(message.isNotEmpty())
        state.synchronize(device, server::read, server::put)
        assertEquals(message, state.issue)
        assertEquals(light, state.theme)
        assertEquals(2L, server.revision)
        state.attach(null, null)
        assertEquals(dark, state.theme)
        state.synchronize(device, { fail("obsolete binding"); error("obsolete") }, server::put)
        assertEquals(2L, server.revision)
    }

    @Test fun pendingEditWhilePostIsInFlightIsNotLost() = runBlocking {
        val server = Authority()
        val state = SharedThemeState(temp.newFolder().toPath(), light)
        state.attach(device.instance, device.device)
        state.synchronize(device, server::read, server::put)
        state.applyTheme(dark)
        state.synchronize(device, server::read) { revision, submitted ->
            state.applyTheme(light)
            server.put(revision, submitted)
        }
        assertEquals(dark, server.theme)
        state.synchronize(device, server::read, server::put)
        assertEquals(light, server.theme)
        assertEquals(2L, server.revision)
    }
}
