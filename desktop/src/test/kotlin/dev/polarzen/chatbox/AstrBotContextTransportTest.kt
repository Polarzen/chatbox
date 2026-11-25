package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

class AstrBotContextTransportTest {
    @Test fun recentHintsBoundDisclosureWithoutReducingRequestedRange() {
        val prefs = ConversationPreferences(recentTurns = 2, pins = listOf(ContextPin(1, "assistant")))
        val context = astrBotContext(prefs, listOf(ContextTurn(1, "旧", "消息", "complete", "native-1"),
            ContextTurn(2, "问题", "答".repeat(550000), "complete"),
            ContextTurn(3, "最近", "答", "complete", "native-3")))
        assertEquals(2, context.getInt("recent"))
        assertEquals("native-1", context.getJSONArray("pins").getJSONObject(0).getString("checkpoint"))
        val hints = context.getJSONArray("hints")
        assertEquals(1, hints.length())
        assertEquals("native-3", hints.getJSONObject(0).getString("checkpoint"))
    }

    @Test fun previewAndSendUseSameSelectionOriginalIdentityAndRevision() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = LinkedBlockingQueue<Triple<String, String, JSONObject>>()
        server.createContext("/fixture/api/v1/chat") { exchange ->
            exchange.use {
                val path = it.requestURI.path
                requests.add(Triple(path, it.requestHeaders.getFirst("Authorization"),
                    JSONObject(it.requestBody.reader(Charsets.UTF_8).readText())))
                val body = if (path.endsWith("/preview")) {
                    it.responseHeaders.add("Content-Type", "application/json")
                    """{"status":"ok","data":{"supported":true,"revision":"first","reason":"","canSend":true,
                        "selected":[{"role":"assistant","seq":1,"text":"答","checkpoint":"native-receipt"},
                        {"role":"user","checkpoint":"older"},{"role":"assistant","text":"不可披露"}]}}"""
                } else {
                    it.responseHeaders.add("Content-Type", "text/event-stream")
                    "data: {\"type\":\"plain\",\"data\":\"答\"}\n\ndata: {\"type\":\"end\"}\n\n"
                }
                val bytes = body.toByteArray()
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/fixture"
            val context = astrBotContext(ConversationPreferences(recentTurns = 0,
                pins = listOf(ContextPin(1, "assistant"))), listOf(ContextTurn(1, "问", "答", "complete")))
            val preview = previewAstrBotContext(base, "fixture", "用户", "会话", "新问", context, true)
            assertEquals(listOf(ContextPreviewMessage("assistant", "答", 1, "native-receipt"),
                ContextPreviewMessage("user", "", checkpoint = "older"), ContextPreviewMessage("assistant", "")), preview.selected)
            assertTrue(preview.canSend)
            val previewRequest = requests.remove()
            assertEquals("/fixture/api/v1/chat/context/preview", previewRequest.first)
            assertEquals("Bearer fixture", previewRequest.second)
            val turnId = UUID.randomUUID().toString()
            var answer = ""
            sendAstrBotContext(base, "fixture", "用户", "会话", "新问", context, preview.revision, turnId, true) {
                answer = it
            }
            val send = requests.remove()
            assertEquals("/fixture/api/v1/chat/context/send", send.first)
            assertEquals("Bearer fixture", send.second)
            val request = send.third
            assertEquals("first", request.remove("revision"))
            assertEquals(turnId, request.remove("turn_id"))
            assertEquals(true, request.remove("enable_streaming"))
            assertTrue(request.similar(previewRequest.third))
            assertEquals("用户", request.getString("username"))
            assertEquals("会话", request.getString("session_id"))
            assertEquals("新问", request.getString("message"))
            val pin = request.getJSONObject("context").getJSONArray("pins").getJSONObject(0)
            assertTrue(pin.similar(JSONObject("""{"seq":1,"role":"assistant","question":"问","answer":"答"}""")))
            assertEquals("答", answer)
        } finally {
            server.stop(0)
        }
    }
}
