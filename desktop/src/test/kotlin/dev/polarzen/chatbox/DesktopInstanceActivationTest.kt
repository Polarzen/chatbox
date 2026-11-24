package dev.polarzen.chatbox

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class DesktopInstanceActivationTest {
    @get:Rule val temp = TemporaryFolder()
    private val api = DesktopInstanceActivation
    private fun newKey() = api.directoryKey(temp.newFolder().toPath())
    private fun record(key: String) = Path.of(System.getProperty("user.home"), ".polarzen-chatbox-tmp", "$key.endpoint")
    private fun reply(profile: String, session: String, index: Int) =
        DesktopActivation.OpenReply(ReplyDestination(SessionKey(profile, session), index))

    @Test fun uriRoundTripsOpaqueUtf8KeysAndCanonicalDirectory() {
        val path = temp.newFolder().toPath()
        val directory = api.directoryKey(path)
        assertTrue(directory.matches(Regex("[0-9a-f]{64}")))
        assertEquals(directory, api.directoryKey(path.resolve("..").resolve(path.fileName)))
        assertNotEquals(directory, newKey())
        val target = reply("manual:谁 /?x#=","session:/🎉 +%", Int.MAX_VALUE)
        val uri = api.toastUri(directory, target.target)
        assertEquals(directory to target, api.parseToastUri(uri))
    }

    @Test fun rejectsMalformedAmbiguousAndOversizedUris() {
        val key = newKey()
        val root = "polarzen-chatbox://reply/$key/"
        val valid = root + "YQ/Yg/0"
        assertEquals(key to reply("a", "b", 0), api.parseToastUri(valid))
        val bad = listOf(
            valid + "?q", valid + "#", valid + "/", valid.replace(key, "A".repeat(64)),
            valid.replace("://reply/", "://other/"), valid.replace("://reply/", "://name@reply/"),
            root + "/Yg/0", root + "YQ=/Yg/0", root + "YR/Yg/0", root + "_w/Yg/0",
            root + "YQ/Yg/00", root + "YQ/Yg/-1", root + "YQ/Yg/2147483648",
            root + "YQ/%59g/0", root + "YQ/Yg/1\n", root + "A".repeat(4096) + "/Yg/0",
        )
        for (uri in bad) assertNull(uri.take(120), api.parseToastUri(uri))
    }

    @Test fun startupAndConcurrentSecondariesQueueEachRequestOnceInItsOwnPrimary() {
        val key = newKey()
        val otherKey = newKey()
        val first = ConcurrentLinkedQueue<DesktopActivation>()
        val second = ConcurrentLinkedQueue<DesktopActivation>()
        val secondaries = Executors.newFixedThreadPool(6)
        val started = CountDownLatch(1)
        try {
            val early = secondaries.submit<Boolean> {
                started.countDown()
                api.forward(key, DesktopActivation.Show, 5000)
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { early.get(100, TimeUnit.MILLISECONDS) }
            api.listen(key) { first.add(it) }.use {
                api.listen(otherKey) { second.add(it) }.use {
                    assertTrue(early.get(6, TimeUnit.SECONDS))
                    val replies = (0..5).map { reply("profile$it", "session/$it", it) }
                    val dispatched = replies.map { target ->
                        secondaries.submit<Boolean> { api.forward(key, target, 4000) }
                    }
                    for (future in dispatched) assertTrue(future.get(5, TimeUnit.SECONDS))
                    val other = reply("other", "session/0", 0)
                    assertTrue(api.forward(otherKey, other))
                    assertEquals(listOf(other), second.toList())
                    assertEquals(1 + replies.size, first.size)
                    assertEquals((replies + DesktopActivation.Show).toSet(), first.toSet())
                }
            }
            assertFalse(Files.exists(record(key)))
            assertFalse(Files.exists(record(otherKey)))
            assertFalse(api.forward(key, DesktopActivation.Show, 80))
        } finally {
            secondaries.shutdownNow()
        }
    }

    @Test fun stalledAndInvalidClientsLeaveReceiverUsableAndCleanupIsIdempotent() {
        val key = newKey()
        val events = ConcurrentLinkedQueue<DesktopActivation>()
        val listener = api.listen(key) { events.add(it) }
        listener.use {
            val endpoint = Files.readAllBytes(record(key))
            val port = ByteBuffer.wrap(endpoint).int
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            Socket(loopback, port).use { stalled ->
                stalled.getOutputStream().write(1)
                assertTrue(api.forward(key, DesktopActivation.Show, 4000))
            }
            val wrongToken = endpoint.copyOfRange(4, endpoint.size).also { it[0] = it[0].inc() }
            for ((token, payload) in listOf(
                wrongToken to "SHOW",
                endpoint.copyOfRange(4, endpoint.size) to "SHOW\n",
                endpoint.copyOfRange(4, endpoint.size) to api.toastUri(newKey(), reply("p", "s", 0).target),
            )) {
                Socket(loopback, port).use { client ->
                    client.soTimeout = 2500
                    client.getOutputStream().write(ByteArray(32))
                    assertEquals(36, client.getInputStream().readNBytes(36).size)
                    val length = ByteBuffer.allocate(4).putInt(payload.length).array()
                    client.getOutputStream().write(token + length + payload.toByteArray())
                    // Depending on the OS, rejecting a request with unread bytes gives EOF or reset.
                    val response = try { client.getInputStream().read() } catch (_: java.net.SocketException) { -1 }
                    assertEquals(-1, response)
                }
            }
            assertTrue(api.forward(key, reply("p", "s", 3)))
            assertEquals(listOf(DesktopActivation.Show, reply("p", "s", 3)), events.toList())
        }
        api.listen(key) { events.add(it) }.use {
            listener.close()
            assertTrue(api.forward(key, DesktopActivation.Show))
        }
        assertFalse(Files.exists(record(key)))
    }

    @Test fun shutdownAllowsWaitingUiCallbackToReturnAndDistinguishesRefusalFromLostAck() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            for (refused in listOf(true, false)) {
                val key = newKey()
                val entered = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val calls = AtomicInteger()
                val listener = api.listen(key) {
                    calls.incrementAndGet()
                    entered.countDown()
                    resume.await()
                    if (refused) throw IOException("UI closed before accepting the activation")
                }
                try {
                    val response = executor.submit<ForwardOutcome> { api.forwardOutcome(key, reply("p", "s", 1), 5000) }
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    executor.submit { listener.close() }.get(1, TimeUnit.SECONDS)
                    assertFalse(Files.exists(record(key)))
                    resume.countDown()
                    val expected = if (refused) ForwardOutcome.RETRYABLE else ForwardOutcome.UNCERTAIN
                    assertEquals(expected, response.get(2, TimeUnit.SECONDS))
                    assertEquals(1, calls.get())
                } finally {
                    resume.countDown()
                    listener.close()
                }
                assertEquals(ForwardOutcome.RETRYABLE, api.forwardOutcome(key, DesktopActivation.Show, 80))
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
