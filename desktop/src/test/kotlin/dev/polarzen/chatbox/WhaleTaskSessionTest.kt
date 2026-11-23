package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean

class WhaleTaskSessionTest {
    private val login = "/api/v1/auth/login"
    private val discover = "/api/plug/astrbot_plugin_whale_tasks/task-control/session"
    private val send = "/api/chat/send"
    private val token = "fixture.header.signature"
    private val id = "00000000-2222-4333-8444-555555555555"
    private val key = "ordinary.api.key"

    private data class Capture(val path: String, val method: String, val auth: String?, val cookie: String?, val body: String)
    private data class Response(val body: String, val status: Int = 200, val type: String = "application/json")

    private inner class Fixture : AutoCloseable {
        val requests = CopyOnWriteArrayList<Capture>()
        val responses = java.util.concurrent.ConcurrentHashMap(mapOf(
            login to Response("""{"status":"ok","data":{"token":"$token"}}"""),
            discover to Response("""{"session_id":"$id"}"""),
            send to Response("data: {\"type\":\"plain\",\"data\":\"就绪\"}\n\ndata: {\"type\":\"end\"}\n\n", type = "text/event-stream"),
        ))
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> exchange.use {
                val path = it.requestURI.path
                requests += Capture(path, it.requestMethod, it.requestHeaders.getFirst("Authorization"),
                    it.requestHeaders.getFirst("Cookie"), it.requestBody.readBytes().decodeToString())
                val response = responses[path] ?: Response("unexpected", 404)
                if (response.status == 0) return@use // Disconnect after receiving the body, before response headers.
                if (response.status == 307) it.responseHeaders.add("Location", "/forbidden")
                if (path == login) it.responseHeaders.add("Set-Cookie", "dashboard=fixture-cookie; Path=/")
                it.responseHeaders.add("Content-Type", response.type)
                val bytes = response.body.toByteArray()
                it.sendResponseHeaders(response.status, bytes.size.toLong())
                it.responseBody.write(bytes)
            } }
            start()
        }
        val address = "http://127.0.0.1:${server.address.port}"
        fun target(profile: String = "launcher:fixture", origin: String = address, generation: Long = 7L,
                   check: () -> Unit = {}) = DesktopSendTarget(profile, origin, key, true, generation, check)
        fun loginTo(target: DesktopSendTarget = target(), code: String = "") =
            loginWhaleTasks(target, "fixture-user", "fixture-password", code)
        override fun close() = server.stop(0)
    }

    @Test fun loginDiscoveryAndBoundChatKeepCredentialsSeparate() {
        Fixture().use { f ->
            var checks = 0
            val target = f.target { checks++ }
            val session = f.loginTo(target, "012345")
            val text = mutableListOf<String>()
            val events = mutableListOf<AstrBotTransportEvent>()
            val command = "/task read file.md -- 检查\n  第二行  "
            sendWhaleTask(target, session, command, events::add, text::add)
            assertEquals("就绪", text.last())
            assertEquals(AstrBotTransportEvent.Completed, events.last())
            assertEquals(3, checks)
            assertEquals(listOf(login, discover, send), f.requests.map { it.path })
            assertEquals(listOf("POST", "GET", "POST"), f.requests.map { it.method })
            assertEquals(listOf(null, "Bearer $token", "Bearer $token"), f.requests.map { it.auth })
            assertTrue(f.requests.all { it.cookie == null && key !in it.body })
            val credentials = JSONObject(f.requests[0].body)
            assertEquals(setOf("username", "password", "code"), credentials.keySet())
            assertEquals("fixture-password", credentials.getString("password"))
            assertEquals("012345", credentials.getString("code"))
            assertEquals("", f.requests[1].body)
            val payload = JSONObject(f.requests[2].body)
            assertEquals(setOf("message", "session_id", "enable_streaming"), payload.keySet())
            assertEquals(id, payload.getString("session_id"))
            val parts = payload.getJSONArray("message")
            assertEquals(1, parts.length())
            assertEquals("plain", parts.getJSONObject(0).getString("type"))
            assertEquals(command, parts.getJSONObject(0).getString("text"))
            assertFalse(session.toString().contains(token))
        }
    }

    @Test fun targetCommandAndSecondFactorGatesPrecedeTransmission() {
        Fixture().use { f ->
            val target = f.target()
            for (wrong in listOf(f.target("manual:fixture"), f.target(origin = "https://example.invalid"),
                f.target(origin = f.address + "/other"), f.target(origin = "http://localhost"))) {
                assertThrows(IllegalArgumentException::class.java) { f.loginTo(wrong) }
            }
            assertThrows(IllegalArgumentException::class.java) { f.loginTo(target, "recovery-code") }
            assertThrows(IllegalStateException::class.java) { f.loginTo(f.target { error("offline") }) }
            assertTrue(f.requests.isEmpty())
            val session = f.loginTo(target)
            assertFalse(JSONObject(f.requests.first().body).has("code"))
            for (wrong in listOf(f.target("launcher:other"), f.target(origin = f.address + "/x"), f.target(generation = 8))) {
                assertThrows(WhaleTaskLoginRequiredException::class.java) { sendWhaleTask(wrong, session, "/task", {}, {}) }
            }
            for (command in listOf("ordinary", "/taskfoo", "/tasks list", "text /task list")) {
                assertThrows(IllegalArgumentException::class.java) { sendWhaleTask(target, session, command, {}, {}) }
            }
            assertThrows(IllegalStateException::class.java) {
                sendWhaleTask(f.target { error("offline") }, session, "/task", {}, {})
            }
            assertEquals(listOf(login, discover), f.requests.map { it.path })
        }
    }

    @Test fun responsesFailClosedAndRedirectsNeverReceiveCredentials() {
        for (stage in listOf(login, discover, send)) Fixture().use { f ->
            f.responses[stage] = Response("private-body", 307)
            assertThrows(IllegalStateException::class.java) {
                val session = f.loginTo()
                sendWhaleTask(f.target(), session, "/task", {}, {})
            }
            assertEquals(stage, f.requests.last().path)
            assertFalse(f.requests.any { it.path == "/forbidden" })
        }
        Fixture().use { f ->
            f.responses[login] = Response("""{"status":"ok","data":{"token":"$key"}}""")
            assertThrows(IllegalStateException::class.java) { f.loginTo() }
            assertEquals(listOf(login), f.requests.map { it.path })
        }
        Fixture().use { f ->
            f.responses[discover] = Response("""{"session_id":"bad"}""")
            assertThrows(IllegalStateException::class.java) { f.loginTo() }
            f.responses[discover] = Response("private-body", 403)
            val failure = assertThrows(IllegalStateException::class.java) { f.loginTo() }
            assertFalse(failure.message.orEmpty().contains("private-body"))
            assertFalse(f.requests.any { it.path == send })
        }
        Fixture().use { f ->
            val session = f.loginTo()
            f.responses[send] = Response("private-body", 401)
            assertThrows(WhaleTaskLoginRequiredException::class.java) { sendWhaleTask(f.target(), session, "/task", {}, {}) }
        }
    }

    @Test fun switchingDuringTargetCheckSuppressesEachRequest() {
        for (blocked in 0..2) Fixture().use { f ->
            val scanning = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val selected = AtomicBoolean(true)
            var scan = 0
            val target = f.target {
                if (scan++ == blocked) {
                    scanning.countDown()
                    check(resume.await(5, SECONDS))
                }
            }
            val changed = IllegalStateException("selection changed")
            val recheck: () -> Unit = { if (!selected.get()) throw changed }
            val work = FutureTask<Unit> {
                val session = loginWhaleTasks(target, "fixture-user", "fixture-password", "", beforeRequest = recheck)
                sendWhaleTask(target, session, "/task", {}, {}, beforeRequest = recheck)
            }
            Thread(work).start()
            try {
                assertTrue(scanning.await(5, SECONDS))
                selected.set(false)
            } finally {
                resume.countDown()
            }
            val failure = assertThrows(ExecutionException::class.java) { work.get(5, SECONDS) }
            assertSame(changed, failure.cause)
            assertEquals(listOf(login, discover, send).take(blocked), f.requests.map { it.path })
        }
    }

    @Test fun interruptedAndMalformedRepliesAndServerFailureHaveUnconfirmedOutcomes() {
        Fixture().use { f ->
            val target = f.target()
            val session = f.loginTo(target)
            for (response in listOf(
                Response("data: {\"type\":\"plain\",\"data\":\"partial\"}\n\n", type = "text/event-stream"),
                Response("data: invalid-json\n\n", type = "text/event-stream"),
                Response("private-error", status = 502),
                Response("", status = 0),
                Response("""{"status":"error"}"""),
            )) {
                f.responses[send] = response
                assertThrows(WhaleTaskOutcomeUnknownException::class.java) {
                    sendWhaleTask(target, session, "/task", {}, {})
                }
            }
            f.responses[send] = Response("private-error", status = 403)
            val denied = assertThrows(IllegalStateException::class.java) { sendWhaleTask(target, session, "/task", {}, {}) }
            assertFalse(denied is WhaleTaskOutcomeUnknownException)
        }
    }
}
