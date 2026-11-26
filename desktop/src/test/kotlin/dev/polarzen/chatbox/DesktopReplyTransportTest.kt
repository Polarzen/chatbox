package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DesktopReplyTransportTest {
    @Test fun bufferedTokensAreBatchedWithoutLosingTextOnParseFailure() {
        val count = 1024
        val frames = List(count) { frame("plain", "字", streaming = true) } + "{"
        withChatServer(frames) { base, _ ->
            val texts = mutableListOf<String>()
            assertThrows(org.json.JSONException::class.java) {
                sendToAstrBot(base, "fixture-key", "user", "session", "question",
                    allowLoopbackHttp = true, onText = { texts += it })
            }
            assertEquals("字", texts.first())
            assertEquals("字".repeat(count), texts.last())
            assertTrue("buffered tokens should share a UI/storage update", texts.size < count / 2)
        }
    }

    @Test fun pendingTextIsPublishedBeforeWaitingForTheRestOfAnIncompleteFrame() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val release = CountDownLatch(1)
        val latestText = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        try {
            server.createContext("/api/v1/chat") { exchange ->
                exchange.use {
                    it.requestBody.readBytes()
                    it.responseHeaders.add("Content-Type", "text/event-stream")
                    it.sendResponseHeaders(200, 0)
                    val burst = frame("plain", "a", true).toSse() + frame("plain", "b", true).toSse() + "data: "
                    it.responseBody.write(burst.toByteArray())
                    it.responseBody.flush()
                    release.await(5, TimeUnit.SECONDS)
                    it.responseBody.write("{\"type\":\"end\"}\n\n".toByteArray())
                }
            }
            server.start()
            val request = Thread {
                try {
                    sendToAstrBot("http://127.0.0.1:${server.address.port}", "fixture-key", "user", "session", "?",
                        allowLoopbackHttp = true, onText = { if (it == "ab") latestText.countDown() })
                } catch (e: Throwable) { failure.set(e) }
            }
            request.start()
            assertTrue("last complete text must appear during the server pause", latestText.await(4, TimeUnit.SECONDS))
            release.countDown()
            request.join(5_000)
            assertFalse(request.isAlive)
            assertNull(failure.get())
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test fun contentRendersBeforeDelayedStreamCompletesAndHeadersAreDistinctMilestones() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val firstFrameSent = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val firstContent = CountDownLatch(1)
        val events = CopyOnWriteArrayList<AstrBotTransportEvent>()
        val texts = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        lateinit var request: Thread
        try {
            server.createContext("/api/v1/chat") { exchange ->
                exchange.use {
                    it.requestBody.readBytes()
                    it.responseHeaders.add("Content-Type", "text/event-stream")
                    it.sendResponseHeaders(200, 0) // chunked: keep the response open after the first frame
                    it.responseBody.write(frame("plain", "early", streaming = true).toSse().toByteArray())
                    it.responseBody.flush()
                    firstFrameSent.countDown()
                    allowCompletion.await(5, TimeUnit.SECONDS)
                    it.responseBody.write((frame("plain", " later", streaming = true).toSse() +
                        frame("end", "").toSse()).toByteArray())
                }
            }
            server.start()
            request = Thread {
                try {
                    sendToAstrBot("http://127.0.0.1:${server.address.port}", "fixture-key", "user", "session",
                        "question", allowLoopbackHttp = true,
                        onEvent = { events += it },
                        onText = {
                            texts += it
                            firstContent.countDown()
                        })
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            request.start()

            assertTrue(firstFrameSent.await(5, TimeUnit.SECONDS))
            assertTrue(firstContent.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(
                AstrBotTransportEvent.ChatStarted, AstrBotTransportEvent.HeadersReceived,
                AstrBotTransportEvent.StreamReady, AstrBotTransportEvent.FirstContent,
            ), events.toList())
            assertEquals("early", texts.first())
            assertFalse(events.contains(AstrBotTransportEvent.Completed))

            allowCompletion.countDown()
            request.join(5_000)
            assertFalse("transport should finish after server releases the stream", request.isAlive)
            assertNull(failure.get())
            assertEquals(AstrBotTransportEvent.Completed, events.last())
            assertEquals("early later", texts.last())
        } finally {
            allowCompletion.countDown()
            server.stop(0)
        }
    }

    @Test fun prematureEofKeepsPartialTextAndDoesNotReportCompletion() {
        withChatServer(listOf(frame("plain", "partial", streaming = true)), appendEnd = false) { base, _ ->
            val events = mutableListOf<AstrBotTransportEvent>()
            val texts = mutableListOf<String>()
            val failure = assertThrows(IllegalStateException::class.java) {
                sendToAstrBot(base, "fixture-key", "user", "session", "question",
                    allowLoopbackHttp = true, onEvent = { events += it }, onText = { texts += it })
            }
            assertTrue(failure.message.orEmpty().contains("意外中断"))
            assertEquals("partial", texts.last())
            assertEquals(listOf(
                AstrBotTransportEvent.ChatStarted, AstrBotTransportEvent.HeadersReceived,
                AstrBotTransportEvent.StreamReady, AstrBotTransportEvent.FirstContent,
            ), events)
        }
    }

    @Test fun rawAndSavedImagesFollowPublicTextOffsetsWithoutChangingTextStream() {
        val frames = listOf(
            frame("plain", "A🙂", streaming = true),
            frame("image", "[IMAGE]one.png"),
            saved("reply-1"),
            frame("image", "[IMAGE]two.png"),
            saved("reply-2"),
            frame("plain", "后"),
            """{"type":"plain","chain_type":"reasoning","data":"hidden"}""",
            """{"type":"plain","chain_type":"tool_call_result","data":"also hidden"}""",
        )
        withChatServer(frames) { base, requests ->
            val texts = mutableListOf<String>()
            val images = mutableListOf<Pair<String?, Int>>()
            sendToAstrBot(base, "fixture-key", "user", "session", "question", allowLoopbackHttp = true,
                onReplyImage = { id, offset -> images += id to offset }) { texts += it }

            assertEquals(listOf("reply-1" to 3, "reply-2" to 3), images)
            assertEquals(listOf("A🙂", "A🙂后", "A🙂后"), texts)
            assertEquals(1, requests.get())
        }
    }

    @Test fun unmatchedRawImageIsReportedAtNextTextBoundaryAndSavedImageCanStandAlone() {
        withChatServer(listOf(frame("image", "[IMAGE]missing.png"), frame("plain", "text"))) { base, _ ->
            val images = mutableListOf<Pair<String?, Int>>()
            val texts = mutableListOf<String>()
            sendToAstrBot(base, "fixture-key", "user", "session", "question", allowLoopbackHttp = true,
                onReplyImage = { id, offset -> images += id to offset }) { texts += it }
            assertEquals(listOf(null to 0), images)
            assertEquals("text", texts.last())
        }

        withChatServer(listOf(saved("only-image"))) { base, _ ->
            val images = mutableListOf<Pair<String?, Int>>()
            sendToAstrBot(base, "fixture-key", "user", "session", "question", allowLoopbackHttp = true,
                onReplyImage = { id, offset -> images += id to offset }) {}
            assertEquals(listOf("only-image" to 0), images)
        }

        withChatServer(listOf(
            frame("plain", "A", streaming = true),
            saved("standalone-boundary"),
            frame("plain", "B"),
        )) { base, _ ->
            val images = mutableListOf<Pair<String?, Int>>()
            val texts = mutableListOf<String>()
            sendToAstrBot(base, "fixture-key", "user", "session", "question", allowLoopbackHttp = true,
                onReplyImage = { id, offset -> images += id to offset }) { texts += it }
            assertEquals(listOf("standalone-boundary" to 1), images)
            assertEquals(listOf("A", "AB", "AB"), texts)
        }

        withChatServer(listOf(frame("image", "[IMAGE]at-eof.png")), appendEnd = false) { base, _ ->
            val images = mutableListOf<Pair<String?, Int>>()
            assertThrows(IllegalStateException::class.java) {
                sendToAstrBot(base, "fixture-key", "user", "session", "question", allowLoopbackHttp = true,
                    onReplyImage = { id, offset -> images += id to offset }) {}
            }
            assertEquals(listOf(null to 0), images)
        }
    }

    @Test fun recordsUseSavedIdsAndPreserveOrderAmongImagesAndText() {
        val frames = listOf(
            frame("record", "[RECORD]https://example.invalid/voice.wav"),
            saved("voice-1", "record"),
            frame("plain", "A🙂", streaming = true),
            saved("voice-2", "record"),
            frame("record", "[RECORD]missing.wav"),
            frame("image", "[IMAGE]real.png"),
            saved("image-1"),
            frame("plain", "B"),
            frame("record", "[RECORD]missing-at-end.wav"),
        )
        withChatServer(frames) { base, _ ->
            val media = mutableListOf<Triple<String, String?, Int>>()
            val texts = mutableListOf<String>()
            val events = mutableListOf<AstrBotTransportEvent>()
            sendToAstrBot(base, "fixture-key", "user", "session", "question", allowLoopbackHttp = true,
                onReplyAudio = { id, offset ->
                    assertTrue(events.contains(AstrBotTransportEvent.FirstContent))
                    media += Triple("audio", id, offset)
                },
                onReplyImage = { id, offset -> media += Triple("image", id, offset) },
                onEvent = { events += it }, onText = { texts += it })
            assertEquals(listOf(
                Triple("audio", "voice-1", 0), Triple("audio", "voice-2", 3),
                Triple("audio", null, 3), Triple("image", "image-1", 3), Triple("audio", null, 4),
            ), media)
            assertEquals(listOf("A🙂", "A🙂B", "A🙂B"), texts)
            assertEquals(1, events.count { it == AstrBotTransportEvent.FirstContent })
        }
    }

    @Test fun malformedFrameFlushesPendingRecordAndPreservesParsingFailure() {
        val frames = listOf(frame("plain", "A🙂"), frame("record", "[RECORD]partial.wav"), "{")
        withChatServer(frames) { base, _ ->
            val audio = mutableListOf<Pair<String?, Int>>()
            val callbackFailure = IllegalStateException("fixture callback failure")
            val failure = assertThrows(org.json.JSONException::class.java) {
                sendToAstrBot(base, "fixture-key", "user", "session", "question", allowLoopbackHttp = true,
                    onReplyAudio = { id, offset ->
                        audio += id to offset
                        throw callbackFailure
                    }, onText = {})
            }
            assertEquals(listOf(null to 3), audio)
            assertEquals(listOf(callbackFailure), failure.suppressed.toList())
        }
    }

    @Test fun downloadsAudioFromAuthenticatedFileEndpointAndChecksResponseType() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = LinkedBlockingQueue<Pair<String?, String>>()
        val payload = "RIFF".toByteArray()
        try {
            server.createContext("/nested/api/v1/file") { exchange ->
                exchange.use {
                    val query = it.requestURI.rawQuery
                    requests += it.requestHeaders.getFirst("Authorization") to query
                    val mime = when {
                        "wrong" in query -> "text/html"
                        "binary" in query -> "application/octet-stream"
                        "ogg" in query -> "application/ogg"
                        else -> "audio/x-wav; charset=binary"
                    }
                    it.responseHeaders.add("Content-Type", mime)
                    it.sendResponseHeaders(200, payload.size.toLong())
                    it.responseBody.write(payload)
                }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}/nested"
            for (id in listOf("file /+猫", "binary", "ogg")) {
                assertArrayEquals(payload, downloadReplyAudio(base, "fixture-key", id, allowLoopbackHttp = true))
            }
            assertEquals("Bearer fixture-key" to "attachment_id=file+%2F%2B%E7%8C%AB", requests.poll())
            val failure = assertThrows(IllegalStateException::class.java) {
                downloadReplyAudio(base, "fixture-key", "wrong", allowLoopbackHttp = true)
            }
            assertTrue(failure.message.orEmpty().contains("未返回语音"))
        } finally {
            server.stop(0)
        }
    }

    @Test fun downloadsSameOriginFileWithBearerAndEncodedAttachmentId() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = LinkedBlockingQueue<Triple<String?, String?, String?>>()
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        try {
            server.createContext("/prefix/api/v1/file") { exchange ->
                exchange.use {
                    requests += Triple(it.requestMethod, it.requestHeaders.getFirst("Authorization"), it.requestURI.rawQuery)
                    it.responseHeaders.add("Content-Type", "image/png")
                    it.sendResponseHeaders(200, imageBytes.size.toLong())
                    it.responseBody.write(imageBytes)
                }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}/prefix"

            val actual = downloadReplyImage(base, "fixture-secret", "id /+猫", allowLoopbackHttp = true)

            assertArrayEquals(imageBytes, actual)
            assertEquals(Triple("GET", "Bearer fixture-secret", "attachment_id=id+%2F%2B%E7%8C%AB"), requests.poll())
        } finally {
            server.stop(0)
        }
    }

    @Test fun rejectsRedirectJsonAndForbiddenFileResponsesWithoutLeakingDetails() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirectedRequests = AtomicInteger()
        try {
            server.createContext("/api/v1/file") { exchange ->
                exchange.use {
                    when (it.requestURI.path) {
                        "/api/v1/file" -> {
                            val mode = it.requestURI.rawQuery.orEmpty()
                            when {
                                mode.contains("redirect") -> {
                                    it.responseHeaders.add("Location", "/redirect-target")
                                    it.sendResponseHeaders(302, -1)
                                }
                                mode.contains("json") -> {
                                    val body = """{"status":"error","message":"fixture-secret reply-id"}""".toByteArray()
                                    it.responseHeaders.add("Content-Type", "application/octet-stream")
                                    it.sendResponseHeaders(200, body.size.toLong())
                                    it.responseBody.write(body)
                                }
                                else -> {
                                    val body = "private server detail".toByteArray()
                                    it.sendResponseHeaders(403, body.size.toLong())
                                    it.responseBody.write(body)
                                }
                            }
                        }
                    }
                }
            }
            server.createContext("/redirect-target") { exchange ->
                redirectedRequests.incrementAndGet()
                exchange.use { it.sendResponseHeaders(200, -1) }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}"

            val redirect = assertThrows(IllegalStateException::class.java) {
                downloadReplyImage(base, "fixture-secret", "redirect", allowLoopbackHttp = true)
            }
            assertTrue(redirect.message.orEmpty().contains("重定向"))
            assertFalse(redirect.message.orEmpty().contains("fixture-secret"))
            assertEquals(0, redirectedRequests.get())

            val json = assertThrows(IllegalStateException::class.java) {
                downloadReplyImage(base, "fixture-secret", "json", allowLoopbackHttp = true)
            }
            assertTrue(json.message.orEmpty().contains("错误信息"))
            assertFalse(json.message.orEmpty().contains("fixture-secret"))
            assertFalse(json.message.orEmpty().contains("reply-id"))

            val audioRedirect = assertThrows(IllegalStateException::class.java) {
                downloadReplyAudio(base, "fixture-secret", "redirect", allowLoopbackHttp = true)
            }
            assertTrue(audioRedirect.message.orEmpty().contains("重定向"))
            assertEquals(0, redirectedRequests.get())

            val audioForbidden = assertThrows(IllegalStateException::class.java) {
                downloadReplyAudio(base, "fixture-secret", "forbidden", allowLoopbackHttp = true)
            }
            assertTrue(audioForbidden.message.orEmpty().contains("file"))
            assertFalse(audioForbidden.message.orEmpty().contains("private server detail"))

            val forbidden = assertThrows(IllegalStateException::class.java) {
                downloadReplyImage(base, "fixture-secret", "forbidden", allowLoopbackHttp = true)
            }
            assertTrue(forbidden.message.orEmpty().contains("file"))
            assertFalse(forbidden.message.orEmpty().contains("private server detail"))
            assertFalse(forbidden.message.orEmpty().contains("fixture-secret"))
        } finally {
            server.stop(0)
        }
    }

    @Test fun capsChunkedReplyImageAndAudioBytes() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/api/v1/file") { exchange ->
                exchange.use { response ->
                    response.responseHeaders.add("Content-Type", "application/octet-stream")
                    response.sendResponseHeaders(200, 0) // chunked; no Content-Length to trust
                    val chunk = ByteArray(64 * 1024)
                    try {
                        repeat(20 * 1024 * 1024 / chunk.size + 1) { response.responseBody.write(chunk) }
                    } catch (_: IOException) {
                        // The client closes once the byte cap is exceeded.
                    }
                }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}"
            for (download in listOf(::downloadReplyImage, ::downloadReplyAudio)) {
                val failure = assertThrows(IllegalStateException::class.java) {
                    download(base, "fixture-key", "large", true)
                }
                assertTrue(failure.message.orEmpty().contains("20 MB"))
            }
        } finally {
            server.stop(0)
        }
    }

    private fun withChatServer(
        frames: List<String>, appendEnd: Boolean = true, block: (String, AtomicInteger) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        try {
            server.createContext("/nested/api/v1/chat") { exchange ->
                exchange.use {
                    requests.incrementAndGet()
                    it.requestBody.readBytes()
                    val events = if (appendEnd) frames + """{"type":"end"}""" else frames
                    val body = events.joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }
                        .toByteArray(StandardCharsets.UTF_8)
                    it.responseHeaders.add("Content-Type", "text/event-stream")
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
            server.start()
            block("http://127.0.0.1:${server.address.port}/nested", requests)
        } finally {
            server.stop(0)
        }
    }

    private fun frame(type: String, data: String, streaming: Boolean = false): String =
        org.json.JSONObject().put("type", type).put("data", data).put("streaming", streaming).toString()

    private fun String.toSse(): String = "data: $this\n\n"

    private fun saved(id: String, type: String = "image"): String = org.json.JSONObject().put("type", "attachment_saved")
        .put("data", org.json.JSONObject().put("id", id).put("type", type)).toString()
}
