package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID

class DesktopProvenRoomTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun provenLegacyRoomKeepsBothDraftsAndOriginalFilesWhileUsingOneAuthorityForSendAndRevoke() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val path = files.root.toPath().resolve("history.json")
        var store = DesktopStore(path)
        val localA = store.ensureProfile("A", "https://native-a.invalid")
        val localB = store.ensureProfile("B", "https://native-b.invalid")
        val a = SessionKey("A", localA.currentSession)
        val other = SessionKey("A", store.createSession("A").session)
        val b = SessionKey("B", localB.currentSession)
        val owner = UUID.randomUUID().toString()
        val phone = UUID.randomUUID().toString()
        val instance = "proved-fixture"
        val token = "old-fixture-owner"
        val image = store.managedMediaDirectory.resolve("screenshot-150.png")
        Files.createDirectories(image.parent)
        Files.write(image, byteArrayOf(42))
        store.saveSessionContent(a.profileId, Chat(a.sessionId, listOf(Entry("问", "权威回答", "已完成"))))
        store.saveSessionContent(other.profileId, Chat(other.sessionId, listOf(Entry("问", "独立", "已完成"))))
        store.saveSessionContent(b.profileId, Chat(b.sessionId, listOf(Entry("本机笔记", "保留原图", "已完成", image.toString()))))
        store.renameSession(a.profileId, a.sessionId, "同名")
        store.renameSession(other.profileId, other.sessionId, "同名")
        store.renameSession(b.profileId, b.sessionId, "另一标题")
        store.saveDraft(a, 1, ComposerDraft("来自 A 的草稿"))
        store.saveDraft(b, 2, ComposerDraft("来自 B 的草稿"))
        store.saveDeviceOwner(instance, owner)
        putLegacyRoomFixture(path, b, localB.address, JSONObject().put("address", "https://old.invalid")
            .put("room", "original-room").put("instance", instance).put("token", token).put("owner", true).toString())
        store = DesktopStore(path)
        store.selectSession(b.profileId, b.sessionId)
        fun turn(seq: Int, id: String, question: String, answer: String) =
            JSONObject().put("seq", seq).put("id", id).put("question", question).put("answer", answer).put("status", "complete")
        val canonical = mutableListOf(turn(1, "original-turn", "问", "权威回答"))
        val independent = listOf(turn(1, "unrelated-turn", "问", "独立"))
        fun row(sid: String, count: Int) = JSONObject().put("profile", "remote-A").put("sid", sid)
            .put("originDevice", owner).put("title", "同名").put("activity", count.toLong() * 42)
            .put("total", count).put("imported", count).put("reason", "")
        val profiles = mutableMapOf<String, JSONObject>()
        val sendIds = mutableListOf<String>()
        val proofs = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/device/") { exchange ->
            exchange.use { ex ->
                val query = ex.requestURI.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                val body = if (ex.requestMethod == "POST") JSONObject(ex.requestBody.reader(Charsets.UTF_8).readText()) else JSONObject()
                val answer = when (ex.requestURI.path.substringAfterLast('/')) {
                    "profile" -> {
                        val source = body.getString("sourceId")
                        body.put("profile", "remote-$source").put("originDevice", owner).put("available", true).put("upstream", "verified-upstream")
                            .also { profiles[source] = it }
                    }
                    "profiles" -> JSONObject().put("profiles", JSONArray(profiles.values))
                    "session" -> {
                        check(body.getString("profile") == "remote-B" && body.getString("sid") == b.sessionId)
                        proofs += body.getString("legacyToken")
                        row(a.sessionId, canonical.size)
                    }
                    "sessions" -> JSONObject().put("sessions", JSONArray().put(row(a.sessionId, canonical.size)).put(row(other.sessionId, 1)))
                        .put("more", false).put("revision", "1")
                    "turns" -> {
                        check(query.getValue("profile") == "remote-A")
                        val source = if (query.getValue("sid") == a.sessionId) canonical else independent
                        JSONObject().put("turns", JSONArray(source.drop(query.getValue("after").toInt()))).put("more", false)
                    }
                    "send" -> {
                        check(body.getString("profile") == "remote-A" && body.getString("sid") == a.sessionId)
                        val id = body.getString("id")
                        sendIds += id
                        val item = turn(canonical.size + 1, id, body.getString("question"), "唯一回复")
                        canonical += item
                        JSONObject().put("profile", "remote-A").put("sid", a.sessionId).put("turn", item)
                    }
                    "revoke" -> JSONObject().also { revoked += body.getString("device") }
                    "devices" -> JSONObject().put("devices", JSONArray())
                    else -> error("Unexpected operation")
                }
                val bytes = answer.toString().toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val keys = NamedKeys(files.root.toPath().resolve("keys"), DesktopCredentials(files.root.toPath().resolve("creds")))
            val sync = DesktopDeviceSync(store, keys) { req ->
                check(req.getString("action") == "local")
                JSONObject().put("ok", true).put("localUrl", "http://127.0.0.1:${server.address.port}")
                    .put("instance", instance).put("device", owner).put("token", "fixture-token")
            }
            sync.refresh()
            assertEquals(listOf(token), proofs)
            assertEquals(setOf(a, b), store.deviceRelatedKeys(b).toSet())
            assertEquals(setOf(other, b), sync.recent().map { it.key }.toSet())
            assertEquals("来自 A 的草稿", store.readDraft(a).text)
            assertEquals("来自 B 的草稿", store.readDraft(b).text)
            assertEquals("权威回答", store.sessionContent(b.profileId, b.sessionId).entries.single().answer)
            assertEquals(image.toString(), store.deviceOriginal(b)!!.entries.single().attachmentReference)
            store.cleanupUnreferencedManagedMedia()
            assertTrue(Files.exists(image))
            assertTrue(sync.send(b, "续聊", 3))
            val durableId = store.nextDeviceSend(b)!!.id
            sync.refresh()
            store.selectSession(a.profileId, a.sessionId)
            sync.refresh()
            sync.refresh()
            assertEquals(setOf(a, other), sync.recent().map { it.key }.toSet())
            assertEquals(listOf(durableId), sendIds)
            assertEquals(listOf("权威回答", "唯一回复"), store.sessionContent(b.profileId, b.sessionId).entries.map { it.answer })
            assertEquals(store.sessionContent(a.profileId, a.sessionId).entries, store.sessionContent(b.profileId, b.sessionId).entries)
            sync.revoke(phone)
            val reopened = DesktopStore(path)
            assertEquals(listOf(phone), revoked)
            assertEquals("保留原图", reopened.deviceOriginal(b)!!.entries.single().answer)
            assertEquals("来自 A 的草稿", reopened.readDraft(a).text)
            assertEquals(setOf(a, b), reopened.deviceRelatedKeys(b).toSet())
            assertTrue(Files.exists(image))
        } finally { server.stop(0) }
    }
}
