package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class AstrBotInboxTransportTest {
    @Test fun pollsOnlyInboxAndDoesNotAdvanceAcrossMalformedPages() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = AtomicInteger()
        val statuses = listOf(200, 200, 200, 403, 404, 302)
        val data = listOf(
            """{"messages":[{"id":39,"text":"提醒"}],"more":true}""",
            """{"messages":[{"id":38,"text":"过期"},{"id":45,"text":"新消息"}],"more":false}""",
            """{"messages":[],"more":true}""",
        )
        val queries = java.util.concurrent.LinkedBlockingQueue<String>()
        server.createContext("/local/api/v1/chatbox/inbox") { exchange ->
            exchange.use {
                val i = hits.getAndIncrement()
                queries.add(it.requestMethod + " " + it.requestURI.rawQuery + " " + it.requestHeaders.getFirst("Authorization"))
                it.responseHeaders.add("Content-Type", "application/json")
                if (statuses[i] == 302) it.responseHeaders.add("Location", "/elsewhere")
                val response = """{"status":"ok","data":${data.getOrNull(i) ?: "{}"}}""".toByteArray()
                it.sendResponseHeaders(statuses[i], response.size.toLong())
                it.responseBody.write(response)
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/local"
            suspend fun read() = fetchAstrBotInbox(base, "fixture-token", "user +", "sid", 38, true)
            assertEquals(AstrBotInboxPage(listOf(AstrBotInboxMessage(39, "提醒")), true), read())
            assertEquals("GET username=user+%2B&session_id=sid&after=38&limit=50 Bearer fixture-token", queries.remove())
            assertTrue(runCatching { read() }.isFailure)
            assertTrue(runCatching { read() }.isFailure)
            assertTrue(runCatching { read() }.exceptionOrNull()?.message.orEmpty().contains("授权"))
            assertNull(read())
            assertTrue(runCatching { read() }.isFailure)
            assertEquals(6, hits.get())
        } finally { server.stop(0) }
    }

    @Test fun idIsStableAcrossReadersAndRoomSpecific() {
        assertEquals("8538e4ce-69fc-36d4-900e-993626536f0e", inboxTurnId("room-a", 123))
        assertEquals(inboxTurnId("room-a", 123), inboxTurnId("ROOM-A", 123))
        assertNotEquals(inboxTurnId("room-a", 123), inboxTurnId("room-b", 123))
    }
}
