package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class DesktopTest {
    @Test fun persistedIdentityAndSessionSurviveReload() {
        val temp = Files.createTempDirectory("chatbox-history-")
        try {
            val f = temp.resolve("history.json")
            val before = DesktopStore(f)
            val server = "https://example.invalid"
            val chat = Chat(before.read(server).session, listOf(Entry("第一条", "", "发送中")))
            before.save(server, chat)
            val after = DesktopStore(f)
            assertEquals(before.username, after.username)
            assertEquals(chat.session, after.read(server).session)
            assertEquals("上次回复未确认", after.read(server).entries.single().status)
            assertNotEquals(chat.session, after.read("https://another.invalid").session)
        } finally { temp.toFile().deleteRecursively() }
    }

    @Test fun loopbackHttpsSseRoundTrip() {
        val temp = Files.createTempDirectory("chatbox-tls-")
        val keyStoreFile = temp.resolve("test.p12")
        val javaBin = Path.of(System.getProperty("java.home"), "bin")
        val keytool = javaBin.resolve(if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val pw = "test-fixture-only".toCharArray()
        val fixtureLog = temp.resolve("keytool.log")
        val prior = HttpsURLConnection.getDefaultSSLSocketFactory()
        var server: HttpsServer? = null
        try {
            val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost,IP:127.0.0.1", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(), "-storepass", String(pw), "-noprompt")
                .redirectErrorStream(true).redirectOutput(fixtureLog.toFile()).start()
            try {
                assertTrue("keytool timed out", process.waitFor(30, TimeUnit.SECONDS))
                assertEquals(Files.readString(fixtureLog), 0, process.exitValue())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            val keys = KeyStore.getInstance("PKCS12")
            Files.newInputStream(keyStoreFile).use { keys.load(it, pw) }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managers.init(keys, pw)
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(keys)
            val serverTls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            val clientTls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.socketFactory)
            val seen = LinkedBlockingQueue<Triple<String, String, JSONObject>>()
            val listener = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server = listener
            listener.httpsConfigurator = HttpsConfigurator(serverTls)
            listener.createContext("/api/v1/chat") { exchange ->
                exchange.use {
                    seen += Triple(it.requestMethod, it.requestHeaders.getFirst("Authorization"),
                        JSONObject(it.requestBody.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }))
                    it.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
                    it.sendResponseHeaders(200, 0)
                    it.responseBody.bufferedWriter(Charsets.UTF_8).use { out ->
                        out.write(": heartbeat\n\ndata: {\"type\":\"plain\",\"streaming\":true,\"data\":\"你\"}\n\n")
                        out.flush()
                        out.write("data: {\"type\":\"plain\",\"streaming\":true,\"data\":\"好\"}\n\n")
                        out.write("data: {\"type\":\"plain\",\"chain_type\":\"reasoning\",\"data\":\"不应显示\"}\n\n")
                        out.write("data: {\"type\":\"end\"}\n\n")
                    }
                }
            }
            listener.createContext("/redirect/api/v1/chat") { exchange ->
                exchange.use {
                    it.responseHeaders.add("Location", "/api/v1/chat")
                    it.sendResponseHeaders(307, -1)
                }
            }
            listener.start()
            val address = "https://127.0.0.1:${listener.address.port}"
            val file = temp.resolve("history.json")
            val before = DesktopStore(file)
            val initial = before.read(address)
            val displays = mutableListOf<String>()
            sendToAstrBot(address, "fixture-token", before.username, initial.session, "测试", displays::add)
            assertTrue(displays.contains("你"))
            assertEquals("你好", displays.last())
            val (method, auth, body) = checkNotNull(seen.poll())
            assertEquals("POST", method)
            assertEquals("Bearer fixture-token", auth)
            assertEquals(before.username, body.getString("username"))
            assertEquals(initial.session, body.getString("session_id"))
            assertEquals("测试", body.getString("message"))
            assertTrue(body.getBoolean("enable_streaming"))
            val first = initial.copy(entries = listOf(Entry("测试", displays.last(), "已完成")))
            before.save(address, first)
            val after = DesktopStore(file)
            sendToAstrBot(address, "fixture-token", after.username, after.read(address).session, "续聊") {}
            val continued = checkNotNull(seen.poll()).third
            assertEquals(before.username, continued.getString("username"))
            assertEquals(first.session, continued.getString("session_id"))
            assertEquals("续聊", continued.getString("message"))
            val redirect = assertThrows(IllegalStateException::class.java) {
                sendToAstrBot(address + "/redirect", "fixture-token", after.username, first.session, "不跟随") {}
            }
            assertTrue(redirect.message.orEmpty().contains("重定向"))
            assertNull("request must never follow redirect", seen.poll())

        } finally {
            server?.stop(0)
            HttpsURLConnection.setDefaultSSLSocketFactory(prior)
            temp.toFile().deleteRecursively()
        }
    }
}
