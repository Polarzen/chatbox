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

    @Test fun legacyMigrationPreservesCaseCollisionsOfflineAndPriorBackup() {
        val temp = Files.createTempDirectory("chatbox-migrate-")
        try {
            val file = temp.resolve("history.json")
            val backup = temp.resolve("history.json.pre-migration.bak")
            val servers = JSONObject()
                .put("HTTP://127.0.0.1:15822", legacyRecord("same-id", "upper"))
                .put("http://127.0.0.1:15822", legacyRecord("lower-id", "lower", "发送中"))
                .put("HtTp://127.0.0.1:15822", legacyRecord("same-id", "duplicate"))
                .put("http://127.0.0.1:15822/Case", legacyRecord("path-upper", "path upper"))
                .put("http://127.0.0.1:15822/case", legacyRecord("path-lower", "path lower"))
            val original = JSONObject()
                .put("username", "existing-identity")
                .put("lastServer", "http://127.0.0.1:15822")
                .put("servers", servers)
            Files.writeString(file, original.toString())
            val beforeMigration = Files.readString(file)

            val migrated = DesktopStore(file)
            assertEquals("existing-identity", migrated.username)
            assertEquals("", migrated.lastServer)
            assertTrue(migrated.profiles.all { it.offlineOnly })
            assertEquals(4, migrated.profiles.size)
            val rootProfiles = migrated.profiles.filter { it.address == "http://127.0.0.1:15822" }
            assertEquals(2, rootProfiles.size)
            assertEquals(3, rootProfiles.sumOf { migrated.sessions(it.id).size })
            assertEquals(2, migrated.profiles.count { it.address.endsWith("/Case") || it.address.endsWith("/case") })
            assertEquals(5, migrated.profiles.sumOf { migrated.sessions(it.id).sumOf { session -> session.entryCount } })
            assertTrue(Files.exists(backup))
            assertEquals(beforeMigration, Files.readString(backup))

            val active = migrated.profile(migrated.activeProfileId)!!
            assertEquals("lower-id", active.currentSession)
            val lower = migrated.readProfile(active.id)
            assertEquals("lower", lower.entries.single().question)
            assertEquals("上次回复未确认", lower.entries.single().status)
            assertEquals("file-ref", lower.entries.single().attachmentReference)
            assertEquals("report.txt", lower.entries.single().attachmentDisplayName)
            assertEquals(2, rootProfiles.sumOf { migrated.sessions(it.id).count { session -> session.id == "same-id" } })

            val migratedBytes = Files.readString(file)
            val reopened = DesktopStore(file)
            assertEquals(5, reopened.profiles.sumOf { reopened.sessions(it.id).sumOf { session -> session.entryCount } })
            assertEquals(migratedBytes, Files.readString(file))
            assertEquals(beforeMigration, Files.readString(backup))
        } finally { temp.toFile().deleteRecursively() }
    }

    @Test fun profileAndSessionSelectionsPersistWithAttachments() {
        val temp = Files.createTempDirectory("chatbox-profiles-")
        try {
            val file = temp.resolve("history.json")
            val store = DesktopStore(file)
            val launcher = store.ensureProfile("launcher:fixture", "HTTPS://Example.com:8443/ChatPath/")
            assertEquals("https://example.com:8443/ChatPath", launcher.address)
            assertEquals("launcher", launcher.kind)
            val first = store.readProfile(launcher.id)
            val firstSaved = first.copy(entries = listOf(Entry("question one", "answer one", "已完成", "file:///saved/item", "item.png")))
            store.saveProfile(launcher.id, firstSaved)
            val second = store.createSession(launcher.id).copy(entries = listOf(Entry("question two", "answer two", "已完成")))
            store.saveProfile(launcher.id, second)
            store.selectSession(launcher.id, first.session)

            val manual = store.read("https://EXAMPLE.com:8443/ChatPath")
            assertNotEquals("launcher URL must not resolve to launcher identity", first.session, manual.session)
            val sameManual = store.read("HTTPS://example.COM:8443/ChatPath/")
            assertEquals(manual.session, sameManual.session)
            val differentPathCase = store.read("https://example.com:8443/chatpath")
            assertNotEquals(manual.session, differentPathCase.session)

            val reloaded = DesktopStore(file)
            assertEquals("manual:https://example.com:8443/chatpath", reloaded.profile(reloaded.activeProfileId)!!.id)
            assertEquals("launcher:fixture", reloaded.profiles.single { it.kind == "launcher" }.id)
            val storedLauncher = reloaded.profile("launcher:fixture")!!
            assertEquals(first.session, storedLauncher.currentSession)
            assertEquals(firstSaved, reloaded.readProfile(storedLauncher.id, first.session))
            assertTrue(reloaded.sessions(storedLauncher.id).any { it.id == second.session && it.entryCount == 1 })
        } finally { temp.toFile().deleteRecursively() }
    }

    @Test fun verifiedAdoptionMovesOnlyUniqueNoncollidingSessionsAndKeepsSelection() {
        val temp = Files.createTempDirectory("chatbox-adopt-")
        try {
            val file = temp.resolve("history.json")
            val servers = JSONObject()
                .put("http://127.0.0.1:8101", legacyRecord("verified-id", "verified"))
                .put("http://127.0.0.1:8102", legacyRecord("ambiguous-id", "ambiguous one"))
                .put("http://127.0.0.1:8103", legacyRecord("ambiguous-id", "ambiguous two"))
                .put("http://127.0.0.1:8104", legacyRecord("colliding-id", "source collision"))
            Files.writeString(file, JSONObject()
                .put("username", "existing-identity")
                .put("lastServer", "http://127.0.0.1:8101")
                .put("servers", servers).toString())
            val store = DesktopStore(file)
            val source = store.profiles.single { store.sessions(it.id).any { session -> session.id == "verified-id" } }
            store.selectProfile(source.id)
            store.selectSession(source.id, "verified-id")
            val target = store.ensureProfile("launcher:verified", "https://service.example:9443")
            store.saveProfile(target.id, Chat("colliding-id", listOf(Entry("existing", "kept", "已完成"))))
            store.selectProfile(source.id)
            store.selectSession(source.id, "verified-id")

            val adopted = store.adoptVerifiedSessions("launcher:verified", "HTTPS://Service.Example:9443", setOf(
                "verified-id", "ambiguous-id", "colliding-id"))
            assertEquals(setOf("verified-id"), adopted)
            assertEquals("launcher:verified", store.activeProfileId)
            assertEquals("verified-id", store.profile("launcher:verified")!!.currentSession)
            assertEquals(Chat("verified-id", listOf(Entry("verified", "answer", "已完成", "file-ref", "report.txt"))),
                store.readProfile("launcher:verified", "verified-id"))
            assertEquals(Chat("colliding-id", listOf(Entry("existing", "kept", "已完成"))),
                store.readProfile("launcher:verified", "colliding-id"))
            assertEquals(2, store.profiles.count { it.offlineOnly && store.sessions(it.id).any { session -> session.id == "ambiguous-id" } })
            assertTrue(store.profiles.none { it.id == source.id })
            assertTrue(store.adoptVerifiedSessions("launcher:verified", "https://service.example:9443",
                setOf("verified-id", "ambiguous-id", "colliding-id")).isEmpty())

            val reloaded = DesktopStore(file)
            assertEquals("colliding-id", reloaded.profile("launcher:verified")!!.currentSession)
            assertEquals("kept", reloaded.readProfile("launcher:verified", "colliding-id").entries.single().answer)
        } finally { temp.toFile().deleteRecursively() }
    }

    private fun legacyRecord(session: String, question: String, status: String = "已完成"): JSONObject =
        JSONObject().put("session", session).put("entries", org.json.JSONArray().put(JSONObject()
            .put("question", question).put("answer", "answer").put("status", status)
            .put("attachmentReference", "file-ref").put("attachmentDisplayName", "report.txt")))

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
                val exitCode = process.exitValue()
                val diagnostic = if (exitCode == 0) "" else String(Files.readAllBytes(fixtureLog), java.nio.charset.Charset.defaultCharset())
                assertEquals(diagnostic, 0, exitCode)
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
            sendToAstrBot(address, "fixture-token", before.username, initial.session, "测试", onText = displays::add)
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
