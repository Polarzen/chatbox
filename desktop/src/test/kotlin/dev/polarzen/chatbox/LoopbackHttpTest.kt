package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class LoopbackHttpTest {
    @Test fun onlyLiteralIpv4LoopbackOptedIn() {
        assertEquals("http://127.0.0.1:23980/api/v1/chat",
            endpoint("http://127.0.0.1:23980", allowLoopbackHttp = true).toString())
        assertEquals("http://127.0.0.1/api/v1/chat",
            endpoint("http://127.0.0.1", allowLoopbackHttp = true).toString())
        assertEquals("https://example.invalid/api/v1/chat", endpoint("https://example.invalid").toString())
        assertTrue(runCatching { endpoint("http://127.0.0.1:23980") }.isFailure)
        for (base in listOf(
            "http://localhost:23980", "http://127.0.0.2:23980", "http://[::1]:23980",
            "http://127.1", "http://2130706433", "http://0x7f000001", "http://0177.0.0.1",
            "http://127.000.0.1", "http://127.0.0.1.", "http://127.0.0.1.example.com",
            "http://192.168.1.10", "http://example.com", "http://%31%32%37.0.0.1",
            "http://127.0.0.1@evil.invalid", "http://evil.invalid@127.0.0.1",
            "http://127.0.0.1?x=1", "http://127.0.0.1#fragment", "http://127.0.0.1:",
            "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1:-1",
            "http:///127.0.0.1", "http://127.0.0.1:bogus", "http://127.0.0.1\\@evil.invalid",
        )) {
            assertTrue("Unexpectedly accepted $base", runCatching { endpoint(base, true) }.isFailure)
        }
    }

    @Test fun localHttpStreamsWithoutProxyOrRedirect() {
        val captured = LinkedBlockingQueue<Pair<String, JSONObject>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val proxyLookups = AtomicInteger()
        val previousProxy = ProxySelector.getDefault()
        try {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    proxyLookups.incrementAndGet()
                    return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
                }
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            })
            server.createContext("/api/v1/chat") { exchange ->
                exchange.use {
                    captured += it.requestHeaders.getFirst("Authorization") to
                        JSONObject(it.requestBody.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() })
                    val response = "data: {\"type\":\"plain\",\"data\":\"本机\"}\n\ndata: {\"type\":\"end\"}\n\n".toByteArray()
                    it.responseHeaders.add("Content-Type", "text/event-stream")
                    it.sendResponseHeaders(200, response.size.toLong())
                    it.responseBody.write(response)
                }
            }
            server.createContext("/redirect/api/v1/chat") { exchange ->
                exchange.use {
                    it.responseHeaders.add("Location", "/api/v1/chat")
                    it.sendResponseHeaders(307, -1)
                }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}"
            val displayed = mutableListOf<String>()
            sendToAstrBot(base, "fixture-key", "test-user", "test-session", "你好",
                allowLoopbackHttp = true, onText = displayed::add)
            assertEquals("本机", displayed.last())
            val (auth, body) = checkNotNull(captured.poll())
            assertEquals("Bearer fixture-key", auth)
            assertEquals("你好", body.getString("message"))
            assertThrows(IllegalStateException::class.java) {
                sendToAstrBot(base + "/redirect", "fixture-key", "test-user", "test-session", "不跟随",
                    allowLoopbackHttp = true) {}
            }
            assertThrows(IllegalArgumentException::class.java) {
                sendToAstrBot(base, "fixture-key", "test-user", "test-session", "默认禁止") {}
            }
            assertNull(captured.poll())
            assertEquals("Loopback must bypass the configured proxy", 0, proxyLookups.get())
        } finally {
            ProxySelector.setDefault(previousProxy)
            server.stop(0)
        }
    }
}
