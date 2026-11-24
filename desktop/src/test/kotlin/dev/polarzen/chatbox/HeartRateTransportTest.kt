package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue

class HeartRateTransportTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun eachTextRequestUsesTheLatestValidReadingAndItsOriginalTimestamp() {
        var wall = Instant.parse("2026-01-01T12:00:00Z")
        var nanos = 0L
        val heartRate = LiveHeartRate({ wall }, { nanos })
        Server().use { server ->
            fun send(source: LiveHeartRate? = heartRate, message: String = "你好"): String {
                sendToAstrBot(server.base, "fixture", "user", "session", message, heartRate = source,
                    allowLoopbackHttp = true) {}
                return server.chats.remove().getString("message")
            }
            assertEquals("你好", send(null))
            assertEquals("你好", send())
            heartRate.update(72)
            val first = "你好\n\n手环心率：72 BPM（接收时间 2026-01-01T12:00:00Z）。"
            assertEquals(first, send())
            val command = "  /persona 切换\t示例  "
            assertEquals(command, send(message = command))
            wall = wall.minusSeconds(60)
            nanos = 9_999_999_999L
            assertEquals(first, send())
            nanos++
            assertEquals("你好", send())
            heartRate.update(85)
            assertEquals("你好\n\n手环心率：85 BPM（接收时间 2026-01-01T11:59:00Z）。", send())
            for (invalid in listOf(0, 65_536, null)) {
                heartRate.update(75)
                heartRate.update(invalid)
                assertEquals("你好", send())
            }
        }
    }

    @Test fun bufferedMeasurementExpiresTenSecondsAfterReceipt() {
        var wall = Instant.EPOCH.plusSeconds(9)
        var nanos = 0L
        val heartRate = LiveHeartRate({ wall }, { nanos })
        assertTrue(heartRate.update(80, Instant.EPOCH))
        assertEquals(1_000L, heartRate.remainingMillis())
        wall = wall.plusSeconds(60)
        nanos = 999_999_999L
        assertEquals(1L, heartRate.remainingMillis())
        assertTrue(heartRate.appendTo("问题").contains("1970-01-01T00:00:00Z"))
        nanos++
        assertEquals(0L, heartRate.remainingMillis())
        assertEquals("问题", heartRate.appendTo("问题"))
        for (invalidTime in listOf(wall.minusSeconds(10), wall.plusNanos(1), Instant.MIN)) {
            heartRate.update(82)
            assertFalse(heartRate.update(99, invalidTime))
            assertEquals(0L, heartRate.remainingMillis())
            assertEquals("问题", heartRate.appendTo("问题"))
        }
        heartRate.update(82)
        assertFalse(heartRate.update(0, wall))
        assertEquals(0L, heartRate.remainingMillis())
    }

    @Test fun attachmentRequestsReadAfterUploadsAndKeepMediaInOrder() {
        var nanos = 0L
        val heartRate = LiveHeartRate({ Instant.EPOCH }, { nanos })
        val media = listOf("photo.png", "voice.wav").map { name ->
            files.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }.toPath()
        }
        Server().use { server ->
            fun send(caption: String = "", afterUploads: () -> Unit): List<JSONObject> {
                sendAttachmentsToAstrBot(server.base, "fixture", "user", "session", media, caption,
                    heartRate = heartRate, allowLoopbackHttp = true,
                    onEvent = { if (it == AstrBotTransportEvent.UploadFinished(1, 2)) afterUploads() }) {}
                repeat(2) {
                    val body = server.uploads.remove()
                    assertFalse(body.contains("BPM") || body.contains("心率"))
                }
                val parts = server.chats.remove().getJSONArray("message")
                val segments = (0 until parts.length()).map(parts::getJSONObject)
                val attachments = segments.takeLast(2)
                assertEquals(listOf("image", "record"), attachments.map { it.getString("type") })
                assertEquals(listOf("photo.png", "voice.wav"), attachments.map { it.getString("filename") })
                assertEquals(listOf("image-id", "audio-id"), attachments.map { it.getString("attachment_id") })
                return segments.dropLast(2)
            }
            fun textOf(parts: List<JSONObject>): String {
                val segment = parts.single()
                assertEquals("plain", segment.getString("type"))
                return segment.getString("text")
            }
            heartRate.update(68)
            val latest = send { heartRate.update(81) }
            assertEquals("手环心率：81 BPM（接收时间 1970-01-01T00:00:00Z）。", textOf(latest))
            assertEquals("说明\n\n手环心率：81 BPM（接收时间 1970-01-01T00:00:00Z）。", textOf(send("说明") {}))
            assertEquals("说明", textOf(send("说明") { nanos += 10_000_000_000L }))
            heartRate.update(83)
            assertEquals(emptyList<JSONObject>(), send { heartRate.update(null) })
        }
    }

    private class Server : AutoCloseable {
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${http.address.port}"
        val chats = LinkedBlockingQueue<JSONObject>()
        val uploads = LinkedBlockingQueue<String>()

        init {
            http.createContext("/api/v1/chat") { exchange ->
                exchange.use {
                    chats.add(JSONObject(it.requestBody.readBytes().toString(Charsets.UTF_8)))
                    it.respond("text/event-stream", "data: {\"type\":\"end\"}\n\n")
                }
            }
            http.createContext("/api/v1/file") { exchange ->
                exchange.use {
                    val body = it.requestBody.readBytes().toString(Charsets.UTF_8)
                    uploads.add(body)
                    val id = if ("Content-Type: image/png" in body) "image-id" else "audio-id"
                    it.respond("application/json", """{"status":"ok","data":{"attachment_id":"$id"}}""")
                }
            }
            http.start()
        }

        private fun HttpExchange.respond(type: String, text: String) {
            val data = text.toByteArray(Charsets.UTF_8)
            responseHeaders.set("Content-Type", type)
            sendResponseHeaders(200, data.size.toLong())
            responseBody.write(data)
        }

        override fun close() = http.stop(0)
    }
}
