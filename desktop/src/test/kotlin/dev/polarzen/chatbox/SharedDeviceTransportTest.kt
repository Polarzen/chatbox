package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SharedDeviceTransportTest {
    private val transport = SharedDeviceTransport(allowLoopback = true)

    @Test fun signedAddressChangeChecksBothEndpointsBeforeBearer() = runBlocking {
        Endpoint().use { old ->
            Endpoint().use { next ->
                val saved = DeviceBinding.create(old.base, "service")
                old.setup(saved, next.base)
                next.setup(saved, next.base)
                val updated = transport.refreshAddress(saved)
                assertEquals(saved.copy(address = next.base), updated)
                assertEquals(listOf("prove"), old.paths)
                assertEquals(listOf("prove", "me"), next.paths)
                assertEquals(listOf(null), old.bearers)
                assertEquals(listOf(null, "Bearer " + saved.token), next.bearers)
            }
        }
    }

    @Test fun staleAddressWrongSignatureAndUnknownDeviceKeepBearerPrivate() = runBlocking {
        Endpoint().use { endpoint ->
            val saved = DeviceBinding.create(endpoint.base, "service")
            endpoint.setup(saved, "https://elsewhere.example")
            assertTrue(runCatching { transport.profiles(saved) }.exceptionOrNull() is DeviceAddressFailure)
            endpoint.setup(saved.copy(token = "different-key"), endpoint.base)
            assertTrue(runCatching { transport.verifyAddress(saved, endpoint.base) }.exceptionOrNull() is DeviceAddressFailure)
            endpoint.proofStatus = 401
            assertTrue(runCatching { transport.me(saved) }.exceptionOrNull() is DeviceAddressFailure)
            assertEquals(listOf("prove", "prove", "prove"), endpoint.paths)
            assertTrue(endpoint.bearers.all { it == null })
        }
    }

    @Test fun matchingProofThenUnauthorizedIsDistinctFromFailedProof() = runBlocking {
        Endpoint().use { endpoint ->
            val saved = DeviceBinding.create(endpoint.base, "service")
            endpoint.setup(saved, endpoint.base)
            endpoint.meStatus = 401
            val failure = runCatching { transport.me(saved) }.exceptionOrNull()
            assertEquals(401, (failure as SharedHttpFailure).status)
            assertEquals(listOf("prove", "me"), endpoint.paths)
        }
    }

    @Test fun joinRequiresMatchingAuthenticatedIdentity() = runBlocking {
        Endpoint().use { endpoint ->
            val saved = DeviceBinding.create(endpoint.base, "service")
            endpoint.setup(saved, endpoint.base)
            assertEquals(saved.device, transport.join(saved, "code", "用户", 2, 7).device)
            endpoint.meDevice = "another-device"
            val failure = runCatching { transport.join(saved, "code", "用户", 2, 7) }.exceptionOrNull()
            assertTrue(failure is DeviceAddressFailure)
            assertEquals(listOf("join", "prove", "me", "join", "prove", "me"), endpoint.paths)
        }
    }

    @Test fun pagedDirectoryAndHistoryPreserveUnicodeKeysAndLongSequences() = runBlocking {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val saved = DeviceBinding.create("http://127.0.0.1:${http.address.port}", "service", owner = true)
        val profile = "来源 + 一"
        val sid = "测试&sid=重复"
        val revision = "快照+1"
        val text = "大段中文🐾".repeat(10000)
        val after = Int.MAX_VALUE.toLong() + 12
        val queries = CopyOnWriteArrayList<Map<String, String>>()
        http.createContext("/v1/device/") { ex ->
            val fields = ex.requestURI.rawQuery.split("&").associate {
                it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
            }
            queries.add(fields)
            val reply = when (ex.requestURI.path.substringAfterLast('/')) {
                "sessions" -> {
                    val offset = fields.getValue("offset").toInt()
                    val rows = JSONArray()
                    for (i in offset until minOf(offset + 100, 123)) {
                        rows.put(JSONObject().put("profile", profile).put("sid", sid + i).put("title", "聊天")
                            .put("activity", 0).put("total", 1).put("imported", 1).put("originDevice", saved.device)
                            .put("reason", ""))
                    }
                    JSONObject().put("sessions", rows).put("more", offset == 0).put("revision", revision)
                }
                else -> {
                    val turn = JSONObject().put("seq", after + 1).put("id", "turn").put("question", "问题")
                        .put("answer", text).put("status", "complete")
                    JSONObject().put("turns", JSONArray().put(turn)).put("more", false)
                }
            }
            val bytes = reply.toString().toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            val first = transport.sessions(saved)
            assertTrue(first.more)
            val second = transport.sessions(saved, first.sessions.size, revision = first.revision)
            assertFalse(second.more)
            assertEquals(123, (first.sessions + second.sessions).map { it.sid }.distinct().size)
            assertEquals(revision, queries[1]["revision"])
            val turn = transport.turns(saved, profile, sid, after).turns.single()
            assertEquals(profile, queries[2]["profile"])
            assertEquals(sid, queries[2]["sid"])
            assertEquals(after.toString(), queries[2]["after"])
            assertEquals(after + 1, turn.seq)
            assertEquals(text, turn.answer)
        } finally {
            http.stop(0)
        }
    }

    @Test fun mediaOnlyGrantsNativeDispatchOnFirstReservation() = runBlocking {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val saved = DeviceBinding.create("http://127.0.0.1:${http.address.port}", "service", owner = true)
        val bodies = CopyOnWriteArrayList<JSONObject>()
        http.createContext("/v1/device/") { ex ->
            val body = JSONObject(ex.requestBody.reader(Charsets.UTF_8).readText())
            val first = bodies.isEmpty()
            bodies.add(body)
            val turn = JSONObject().put("seq", 1).put("id", body.getString("id")).put("question", "照片")
                .put("answer", body.optString("answer")).put("status", body.optString("status", "running"))
            val reply = JSONObject().put("profile", body.getString("profile")).put("sid", body.getString("sid"))
                .put("turn", turn)
            if (ex.requestURI.path.endsWith("/media-begin")) reply.put("dispatch", first)
            val bytes = reply.toString().toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            assertTrue(transport.beginMedia(saved, "p", "room", "id", "照片").dispatch)
            assertFalse(transport.beginMedia(saved, "p", "room", "id", "照片").dispatch)
            val result = transport.endMedia(saved, "p", "room", "id", "已收到", "complete")
            assertEquals("complete", result.turn.status)
            assertFalse(result.dispatch)
            assertEquals(setOf("profile", "sid", "id", "question"), bodies[0].keySet())
            assertEquals(setOf("profile", "sid", "id", "answer", "status"), bodies[2].keySet())
        } finally {
            http.stop(0)
        }
    }

    private class Endpoint : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        private lateinit var binding: DeviceBinding
        private lateinit var published: String
        var proofStatus = 200
        var meStatus = 200
        var meDevice: String? = null
        val paths = CopyOnWriteArrayList<String>()
        val bearers = CopyOnWriteArrayList<String?>()

        init {
            server.createContext("/v1/device/") { ex ->
                val path = ex.requestURI.path.substringAfterLast('/')
                paths.add(path)
                bearers.add(ex.requestHeaders.getFirst("Authorization"))
                val r = JSONObject().put("device", binding.device).put("instance", binding.instance)
                    .put("address", published)
                val status = when (path) {
                    "join" -> {
                        val body = JSONObject(ex.requestBody.reader(Charsets.UTF_8).readText())
                        r.put("owner", false)
                        if (body.getString("token") == binding.token && body.getString("device") == binding.device) 200 else 400
                    }
                    "me" -> {
                        r.put("owner", binding.owner)
                        meDevice?.let { r.put("device", it) }
                        meStatus
                    }
                    "prove" -> {
                        val body = JSONObject(ex.requestBody.reader(Charsets.UTF_8).readText())
                        val nonce = body.getString("nonce")
                        val key = MessageDigest.getInstance("SHA-256").digest(binding.token.toByteArray())
                        val mac = Mac.getInstance("HmacSHA256")
                        mac.init(SecretKeySpec(key, "HmacSHA256"))
                        val message = "chatbox-device-address-v1\n${binding.instance}\n${binding.device}\n$published\n$nonce"
                        r.put("proof", mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) })
                        proofStatus
                    }
                    else -> 404
                }
                val bytes = r.toString().toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun setup(identity: DeviceBinding, address: String) {
            binding = identity
            published = address
        }

        override fun close() = server.stop(0)
    }
}
