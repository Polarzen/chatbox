package dev.polarzen.chatbox

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidConnectionsTest {
    private class MemoryStorage : AndroidConnectionStorage {
        var catalog = AndroidConnectionCatalog(emptyList())
        var probes = 0
        var rejectSave = false
        var beforeProbe: () -> Unit = {}
        override fun load() = catalog
        override fun save(catalog: AndroidConnectionCatalog) {
            if (rejectSave) throw IOException()
            this.catalog = catalog
        }
        override fun seal(base: String, id: String, token: String) = "$base/$id/$token"
        override fun open(base: String, key: AndroidNamedKey): String {
            val prefix = "$base/${key.id}/"
            check(key.sealedToken.startsWith(prefix))
            return key.sealedToken.removePrefix(prefix)
        }
        override fun verify(base: String, token: String) {
            beforeProbe()
            probes++
            check(base == "https://${token}.example") { "rejected" }
        }
    }

    @Test fun failedCandidateKeepsPreviousTargetThroughRestart() = runBlocking {
        val storage = MemoryStorage()
        val connections = AndroidConnections(storage)
        val first = connections.addService("First", " HTTPS://ONE.example/ ")
        val second = connections.addService("Second", "https://two.example")
        val original = connections.addKey(first, "Primary", "one")
        val wrong = connections.addKey(second, "Rejected", "one")
        assertNull(connections.token())
        connections.verify(first, original)
        assertTrue(runCatching { connections.verify(second, wrong) }.isFailure)
        assertEquals(first, connections.base)
        assertEquals("Primary", connections.keyName)
        assertEquals("one", connections.token())
        val restored = AndroidConnections(storage)
        assertEquals(first, restored.base)
        assertEquals(original, restored.keyId)
        assertEquals("one", restored.token())
        assertEquals(2, storage.probes)
        val valid = restored.addKey(second, "Secondary", "two")
        storage.rejectSave = true
        assertTrue(runCatching { restored.verify(second, valid) }.isFailure)
        assertEquals(first, restored.base)
        assertEquals(original, restored.keyId)
        storage.rejectSave = false
        restored.verify(second, valid)
        assertEquals(second, restored.base)
        assertEquals("two", restored.token())
        assertFalse(restored.selectOffline("https://unknown.example"))
        assertEquals(second, restored.base)
        assertTrue(restored.openHistory(first))
        assertEquals(first, restored.base)
        assertNull(restored.token())
        assertNull(AndroidConnections(storage).token())
    }

    @Test fun delayedProbeCannotReactivateKeyAfterExplicitOfflineNavigation() = runBlocking {
        val storage = MemoryStorage()
        val connections = AndroidConnections(storage)
        val first = connections.addService("First", "https://one.example")
        val second = connections.addService("Second", "https://two.example")
        val key = connections.addKey(first, "Primary", "one")
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        storage.beforeProbe = { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { connections.verify(first, key) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(connections.openHistory(second))
        } finally {
            resume.countDown()
        }
        assertTrue(result.await().isFailure)
        assertEquals(second, connections.base)
        assertNull(connections.token())
        assertEquals(second, AndroidConnections(storage).base)
    }
}
