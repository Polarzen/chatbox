package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class DesktopMediaTransportTest {
    @Test fun uploadsOrderedImagesAndReportsTransportMilestones() {
        val temp = Files.createTempDirectory("chatbox-media-multi-")
        val first = temp.resolve("first.png")
        val second = temp.resolve("second.jpg")
        Files.write(first, byteArrayOf(1, 2, 3))
        Files.write(second, byteArrayOf(4, 5, 6))
        val uploads = LinkedBlockingQueue<ByteArray>()
        val chat = LinkedBlockingQueue<JSONObject>()
        val uploadIndex = AtomicInteger()
        val events = mutableListOf<AstrBotTransportEvent>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/api/v1/file") { exchange ->
                exchange.use {
                    uploads += it.requestBody.readBytes()
                    val id = "attachment-${uploadIndex.incrementAndGet()}"
                    val body = """{"status":"ok","data":{"attachment_id":"$id"}}"""
                        .toByteArray(Charsets.UTF_8)
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
            server.createContext("/api/v1/chat") { exchange ->
                exchange.use {
                    chat += JSONObject(it.requestBody.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() })
                    val response = "data: {\"type\":\"plain\",\"data\":\"ready\"}\n\ndata: {\"type\":\"end\"}\n\n"
                        .toByteArray(Charsets.UTF_8)
                    it.responseHeaders.add("Content-Type", "text/event-stream")
                    it.sendResponseHeaders(200, response.size.toLong())
                    it.responseBody.write(response)
                }
            }
            server.start()

            sendImagesToAstrBot("http://127.0.0.1:${server.address.port}", "fixture-key", "user", "session",
                listOf(first, second), caption = "look", allowLoopbackHttp = true,
                onEvent = { events += it }, onText = {})

            val firstUpload = checkNotNull(uploads.poll())
            val secondUpload = checkNotNull(uploads.poll())
            assertTrue(firstUpload.toString(Charsets.ISO_8859_1).contains("filename=\"image.png\""))
            assertTrue(firstUpload.toList().windowed(3).any { it == listOf<Byte>(1, 2, 3) })
            assertTrue(secondUpload.toString(Charsets.ISO_8859_1).contains("filename=\"image.jpg\""))
            assertTrue(secondUpload.toList().windowed(3).any { it == listOf<Byte>(4, 5, 6) })

            val message = checkNotNull(chat.poll()).getJSONArray("message")
            assertEquals(3, message.length())
            assertEquals("look", message.getJSONObject(0).getString("text"))
            assertEquals("attachment-1", message.getJSONObject(1).getString("attachment_id"))
            assertEquals("first.png", message.getJSONObject(1).getString("filename"))
            assertEquals("attachment-2", message.getJSONObject(2).getString("attachment_id"))
            assertEquals("second.jpg", message.getJSONObject(2).getString("filename"))
            assertEquals(listOf(
                AstrBotTransportEvent.UploadStarted(0, 2), AstrBotTransportEvent.UploadFinished(0, 2),
                AstrBotTransportEvent.UploadStarted(1, 2), AstrBotTransportEvent.UploadFinished(1, 2),
                AstrBotTransportEvent.ChatStarted, AstrBotTransportEvent.HeadersReceived,
                AstrBotTransportEvent.StreamReady, AstrBotTransportEvent.FirstContent,
                AstrBotTransportEvent.Completed,
            ), events)

            val recording = temp.resolve("留言.WAV")
            val format = AudioFormat(16000f, 16, 1, true, false)
            AudioInputStream(byteArrayOf(1, 0, 2, 0).inputStream(), format, 2).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, recording.toFile())
            }
            val expectedWav = Files.readAllBytes(recording)
            val base = "http://127.0.0.1:${server.address.port}"
            for (withImage in listOf(false, true)) {
                val files = if (withImage) listOf(first, recording) else listOf(recording)
                sendAttachmentsToAstrBot(base, "fixture-key", "user", "session", files,
                    caption = if (withImage) "说明" else "", allowLoopbackHttp = true) {}
                val parts = checkNotNull(chat.poll()).getJSONArray("message")
                assertEquals(if (withImage) 3 else 1, parts.length())
                if (withImage) {
                    assertEquals("plain", parts.getJSONObject(0).getString("type"))
                    assertEquals("说明", parts.getJSONObject(0).getString("text"))
                    assertEquals("image", parts.getJSONObject(1).getString("type"))
                    assertEquals("attachment-4", parts.getJSONObject(1).getString("attachment_id"))
                    assertTrue(checkNotNull(uploads.poll()).toString(Charsets.ISO_8859_1)
                        .contains("Content-Type: image/png"))
                }
                val record = parts.getJSONObject(parts.length() - 1)
                assertEquals("record", record.getString("type"))
                assertEquals(if (withImage) "attachment-5" else "attachment-3", record.getString("attachment_id"))
                assertEquals("留言.WAV", record.getString("filename"))
                val multipart = checkNotNull(uploads.poll()).toString(Charsets.ISO_8859_1)
                assertTrue(multipart.contains("name=\"file\"; filename=\"record.wav\""))
                assertTrue(multipart.contains("Content-Type: audio/wav\r\n\r\n" +
                    expectedWav.toString(Charsets.ISO_8859_1) + "\r\n--"))
                assertNull(uploads.poll())
                assertNull(chat.poll())
            }
        } finally {
            server.stop(0)
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun validatesEveryMixedAttachmentBeforeFirstUpload() {
        val temp = Files.createTempDirectory("chatbox-media-preflight-")
        try {
            val valid = Files.write(temp.resolve("first.png"), byteArrayOf(1))
            val oversized = temp.resolve("large.jpg")
            RandomAccessFile(oversized.toFile(), "rw").use { it.setLength(512L * 1024 * 1024 + 1) }
            val invalid = listOf(temp.resolve("missing.wav"), Files.createFile(temp.resolve("empty.wav")),
                Files.write(temp.resolve("unknown.pdf"), byteArrayOf(2)), oversized)
            for (path in invalid) {
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    sendAttachmentsToAstrBot("", "", "", "", listOf(valid, path),
                        onEvent = { fail("Upload started before validating ${path.fileName}") }) {}
                }
                assertTrue(failure.message.orEmpty().contains(path.fileName.toString()))
            }
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun failedSecondUploadLeavesLatestMilestoneAtThatUploadAndSkipsChat() {
        val temp = Files.createTempDirectory("chatbox-media-multi-failure-")
        val first = temp.resolve("first.png")
        val second = temp.resolve("second.png")
        Files.write(first, byteArrayOf(1))
        Files.write(second, byteArrayOf(2))
        val uploadIndex = AtomicInteger()
        val events = mutableListOf<AstrBotTransportEvent>()
        val chatRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/api/v1/file") { exchange ->
                exchange.use {
                    it.requestBody.readBytes()
                    val status = if (uploadIndex.incrementAndGet() == 2) 403 else 200
                    val body = if (status == 200)
                        """{"status":"ok","data":{"attachment_id":"first"}}""".toByteArray()
                    else "{}".toByteArray()
                    if (status == 200) it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(status, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
            server.createContext("/api/v1/chat") { exchange ->
                chatRequests.incrementAndGet()
                exchange.use { it.sendResponseHeaders(200, -1) }
            }
            server.start()

            val failure = assertThrows(IllegalStateException::class.java) {
                sendImagesToAstrBot("http://127.0.0.1:${server.address.port}", "fixture-key", "user", "session",
                    listOf(first, second), allowLoopbackHttp = true, onEvent = { events += it }, onText = {})
            }
            assertTrue(failure.message.orEmpty().contains("文件上传权限"))
            assertEquals(listOf(
                AstrBotTransportEvent.UploadStarted(0, 2), AstrBotTransportEvent.UploadFinished(0, 2),
                AstrBotTransportEvent.UploadStarted(1, 2),
            ), events)
            assertEquals(0, chatRequests.get())
        } finally {
            server.stop(0)
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun uploadsImageThenSendsCaptionAndAttachmentInSameSession() {
        val temp = Files.createTempDirectory("chatbox-media-")
        val image = temp.resolve("猫 图片.png")
        val imageBytes = byteArrayOf(1, 2, 0, 4, 5)
        Files.write(image, imageBytes)
        val uploads = LinkedBlockingQueue<Triple<String?, String?, ByteArray>>()
        val chats = LinkedBlockingQueue<Triple<String?, String?, JSONObject>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/api/v1/file") { exchange ->
                exchange.use {
                    uploads += Triple(it.requestHeaders.getFirst("Authorization"),
                        it.requestHeaders.getFirst("Content-Type"), it.requestBody.readBytes())
                    val body = """{"status":"ok","data":{"attachment_id":"attachment-1"}}"""
                        .toByteArray(Charsets.UTF_8)
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
            server.createContext("/api/v1/chat") { exchange ->
                exchange.use {
                    chats += Triple(it.requestHeaders.getFirst("Authorization"),
                        it.requestHeaders.getFirst("Content-Type"),
                        JSONObject(it.requestBody.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }))
                    val response = "data: {\"type\":\"plain\",\"data\":\"收到图片\"}\n\ndata: {\"type\":\"end\"}\n\n"
                        .toByteArray(Charsets.UTF_8)
                    it.responseHeaders.add("Content-Type", "text/event-stream")
                    it.sendResponseHeaders(200, response.size.toLong())
                    it.responseBody.write(response)
                }
            }
            server.start()

            val base = "http://127.0.0.1:${server.address.port}"
            val displayed = mutableListOf<String>()
            sendImageToAstrBot(base, "fixture-key", "test-user", "existing-session", image,
                caption = "请看看这张图片", allowLoopbackHttp = true, onText = displayed::add)

            val (uploadAuth, uploadType, uploadBody) = checkNotNull(uploads.poll())
            assertEquals("Bearer fixture-key", uploadAuth)
            assertTrue(uploadType.orEmpty().startsWith("multipart/form-data; boundary="))
            val boundary = uploadType!!.substringAfter("boundary=")
            val header = uploadBody.toString(Charsets.ISO_8859_1)
            assertTrue(header.contains("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\""))
            assertTrue(header.contains("Content-Type: image/png\r\n\r\n"))
            assertTrue(uploadBody.toList().windowed(imageBytes.size).any { it == imageBytes.toList() })
            assertTrue(header.endsWith("\r\n--$boundary--\r\n"))

            val (chatAuth, chatType, body) = checkNotNull(chats.poll())
            assertEquals("Bearer fixture-key", chatAuth)
            assertTrue(chatType.orEmpty().startsWith("application/json"))
            assertEquals("test-user", body.getString("username"))
            assertEquals("existing-session", body.getString("session_id"))
            assertTrue(body.getBoolean("enable_streaming"))
            val parts = body.getJSONArray("message")
            assertEquals(2, parts.length())
            assertEquals("plain", parts.getJSONObject(0).getString("type"))
            assertEquals("请看看这张图片", parts.getJSONObject(0).getString("text"))
            assertEquals("image", parts.getJSONObject(1).getString("type"))
            assertEquals("attachment-1", parts.getJSONObject(1).getString("attachment_id"))
            assertEquals("猫 图片.png", parts.getJSONObject(1).getString("filename"))
            assertEquals("收到图片", displayed.last())
            assertNull(chats.poll())
        } finally {
            server.stop(0)
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun imageOnlyPayloadAndUploadFailures() {
        val temp = Files.createTempDirectory("chatbox-media-failure-")
        val image = temp.resolve("only.jpg")
        Files.write(image, byteArrayOf(9, 8, 7))
        val uploads = LinkedBlockingQueue<Int>()
        val chats = LinkedBlockingQueue<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val failureMode = AtomicInteger()
        try {
            server.createContext("/api/v1/file") { exchange ->
                exchange.use {
                    uploads += it.requestBody.readBytes().size
                    if (failureMode.get() == 1) {
                        val body = """{"detail":"Forbidden"}""".toByteArray(Charsets.UTF_8)
                        it.sendResponseHeaders(403, body.size.toLong())
                        it.responseBody.write(body)
                    } else if (failureMode.get() == 2) {
                        val body = """{"status":"error","message":"File too large (limit 512 MB)"}"""
                            .toByteArray(Charsets.UTF_8)
                        it.responseHeaders.add("Content-Type", "application/json")
                        it.sendResponseHeaders(200, body.size.toLong())
                        it.responseBody.write(body)
                    } else {
                        val id = if (failureMode.get() == 3) JSONObject() else "image-only"
                        val body = JSONObject().put("status", "ok").put("data", JSONObject().put("attachment_id", id))
                            .toString().toByteArray(Charsets.UTF_8)
                        it.responseHeaders.add("Content-Type", "application/json")
                        it.sendResponseHeaders(200, body.size.toLong())
                        it.responseBody.write(body)
                    }
                }
            }
            server.createContext("/api/v1/chat") { exchange ->
                exchange.use {
                    chats += JSONObject(it.requestBody.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() })
                    val response = "data: {\"type\":\"end\"}\n\n".toByteArray(Charsets.UTF_8)
                    it.responseHeaders.add("Content-Type", "text/event-stream")
                    it.sendResponseHeaders(200, response.size.toLong())
                    it.responseBody.write(response)
                }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}"

            sendImageToAstrBot(base, "fixture-key", "test-user", "same-session", image,
                allowLoopbackHttp = true) {}
            val imageOnly = checkNotNull(chats.poll()).getJSONArray("message")
            assertEquals(1, imageOnly.length())
            assertEquals("image", imageOnly.getJSONObject(0).getString("type"))
            assertNotNull(uploads.poll())

            failureMode.set(1)
            val failure = assertThrows(IllegalStateException::class.java) {
                sendImageToAstrBot(base, "fixture-key", "test-user", "same-session", image,
                    allowLoopbackHttp = true) {}
            }
            assertTrue(failure.message.orEmpty().contains("file"))
            assertEquals(1, uploads.size) // the denied upload was still received
            assertNull("chat must not run after a rejected upload", chats.poll())

            failureMode.set(2)
            val rejected = assertThrows(IllegalStateException::class.java) {
                sendImageToAstrBot(base, "fixture-key", "test-user", "same-session", image,
                    allowLoopbackHttp = true) {}
            }
            assertTrue(rejected.message.orEmpty().contains("512 MB"))
            assertEquals(2, uploads.size)
            assertNull("chat must not run after an upload API error", chats.poll())

            failureMode.set(3)
            val malformed = assertThrows(IllegalStateException::class.java) {
                sendImageToAstrBot(base, "fixture-key", "test-user", "same-session", image,
                    allowLoopbackHttp = true) {}
            }
            assertEquals("图片上传成功响应中缺少附件编号。", malformed.message)
            assertEquals(3, uploads.size)
            assertNull("chat must not run with a malformed attachment ID", chats.poll())
        } finally {
            server.stop(0)
            temp.toFile().deleteRecursively()
        }
    }
}
