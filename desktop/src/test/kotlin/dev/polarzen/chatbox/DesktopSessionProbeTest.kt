package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class DesktopSessionProbeTest {
    @Test fun readsAllPagesAndFiltersToIdentityWebchatSessionsWithoutHttpProxy() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pageRequests = mutableListOf<Pair<Int, String?>>()
        val proxyLookups = AtomicInteger()
        val previousProxy = ProxySelector.getDefault()
        val expected = setOf("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
        try {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    proxyLookups.incrementAndGet()
                    return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
                }
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            })
            server.createContext("/api/v1/chat/sessions") { exchange ->
                exchange.use {
                    val query = it.requestURI.rawQuery.orEmpty().split('&').associate { pair ->
                        val (key, value) = pair.split('=', limit = 2)
                        key to URLDecoder.decode(value, StandardCharsets.UTF_8)
                    }
                    val page = query.getValue("page").toInt()
                    pageRequests += page to it.requestHeaders.getFirst("Authorization")
                    val rows = if (page == 1) {
                        buildList {
                            add(session(expected.first(), "active-user", "webchat"))
                            repeat(98) { add(session("ignored-$it", "someone-else", "webchat")) }
                            add(session("ignored-telegram", "active-user", "telegram"))
                        }
                    } else {
                        listOf(session(expected.last(), "active-user", "webchat"))
                    }
                    val body = """{"status":"ok","data":{"sessions":$rows,"page":$page,"page_size":100,"total":101}}"""
                    val json = body.toByteArray(StandardCharsets.UTF_8)
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(200, json.size.toLong())
                    it.responseBody.write(json)
                }
            }
            server.start()

            val result = fetchDesktopSessions("http://127.0.0.1:${server.address.port}", "probe-key", "active-user")

            assertEquals(expected, result)
            assertEquals(listOf(1, 2), pageRequests.map { it.first })
            assertTrue(pageRequests.all { it.second == "Bearer probe-key" })
            assertEquals(0, proxyLookups.get())
        } finally {
            ProxySelector.setDefault(previousProxy)
            server.stop(0)
        }
    }

    @Test fun reportsSanitizedAuthRedirectAndOfflineFailures() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val responseCode = AtomicInteger(401)
        val redirectedRequests = AtomicInteger()
        try {
            server.createContext("/api/v1/chat/sessions") { exchange ->
                exchange.use {
                    when (responseCode.get()) {
                        302 -> {
                            it.responseHeaders.add("Location", "/redirect-target")
                            it.sendResponseHeaders(302, -1)
                        }
                        200 -> {
                            val body = """{"status":"error","message":"private raw server detail"}"""
                                .toByteArray(StandardCharsets.UTF_8)
                            it.sendResponseHeaders(200, body.size.toLong())
                            it.responseBody.write(body)
                        }
                        else -> it.sendResponseHeaders(responseCode.get(), -1)
                    }
                }
            }
            server.createContext("/redirect-target") { exchange ->
                exchange.use {
                    redirectedRequests.incrementAndGet()
                    it.sendResponseHeaders(200, -1)
                }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}"

            assertEquals("聊天 API 密钥无效或已撤销。", failureMessage(base))
            responseCode.set(403)
            assertEquals("API 密钥缺少聊天权限。", failureMessage(base))
            responseCode.set(302)
            assertEquals("服务返回重定向，请直接填写最终服务地址。", failureMessage(base))
            assertEquals(0, redirectedRequests.get())
            responseCode.set(200)
            val rejected = failureMessage(base)
            assertTrue(rejected.contains("拒绝了会话查询"))
            assertFalse(rejected.contains("private raw server detail"))

            val stoppedAddress = base
            server.stop(0)
            val offline = failureMessage(stoppedAddress)
            assertTrue(offline.contains("无法连接到 AstrBot"))
        } finally {
            server.stop(0)
        }
    }

    @Test fun rejectsNonLoopbackHttpBeforeSendingCredentials() {
        val error = runCatching { fetchDesktopSessions("http://192.168.1.2", "probe-key", "active-user") }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private fun failureMessage(base: String): String =
        runCatching { fetchDesktopSessions(base, "probe-key", "active-user") }
            .exceptionOrNull()?.message ?: error("Expected the session probe to fail")

    private fun session(id: String, creator: String, platform: String) =
        """{"session_id":"$id","creator":"$creator","platform_id":"$platform"}"""
}
