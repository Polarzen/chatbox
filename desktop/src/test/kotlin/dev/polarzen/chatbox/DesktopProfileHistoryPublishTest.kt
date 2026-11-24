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

class DesktopProfileHistoryPublishTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun unreadableRoomGrantPublishesOnlyLocalHistoryAndBackgroundChangesStayInTheOwningProfile() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val path = files.root.toPath().resolve("history.json")
        val store = DesktopStore(path)
        val a = store.ensureProfile("A", "https://a.invalid")
        val b = store.ensureProfile("B", "https://b.invalid")
        val sid = a.currentSession
        val key = SessionKey(a.id, sid)
        store.saveSessionContent(a.id, Chat(sid, listOf(Entry("配对前", "旧答复", "已完成"))))
        // Record exists on the right origin, but its decrypted contents are not valid room credentials.
        DesktopSharedStorage(store, key, a.address).save("{")
        val records = mutableMapOf<String, JSONObject>()
        val removals = mutableListOf<String>()
        val uploaded = mutableListOf<String>()
        var proof: String? = null
        var expected = 0
        val bridge = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        bridge.createContext("/v1/profile/") { exchange ->
            exchange.use {
                val body = if (it.requestMethod == "POST") JSONObject(it.requestBody.reader(Charsets.UTF_8).readText()) else JSONObject()
                val reply = when (it.requestURI.path.substringAfterLast('/')) {
                    "create" -> JSONObject().put("profile", "remote-A").put("name", "A").put("instance", "i")
                        .put("owner", true).put("token", "owner-token")
                    "sessions" -> JSONObject().put("sessions", JSONArray(records.values)).put("revision", 1).put("more", false)
                        .put("complete", true).put("expected", expected).put("name", "A").put("issue", "")
                    "status" -> { expected = body.getInt("expected"); JSONObject() }
                    "session" -> {
                        val id = body.getString("sid")
                        if (id == sid) proof = body.getString("legacyToken")
                        body.put("reason", if (id == sid) "仅可阅读电脑上传的历史。" else "")
                            .put("imported", records[id]?.getInt("imported") ?: 0).also { entry -> records[id] = entry }
                    }
                    "import" -> {
                        val turns = body.getJSONArray("turns")
                        for (i in 0 until turns.length()) uploaded += turns.getJSONObject(i).getString("answer")
                        records.getValue(body.getString("sid")).put("imported", turns.length())
                        JSONObject().put("imported", turns.length())
                    }
                    "remove" -> {
                        removals += body.getString("sid")
                        records.remove(body.getString("sid"))
                        JSONObject()
                    }
                    "turns" -> JSONObject().put("turns", JSONArray()).put("more", false)
                    else -> error("Unexpected route")
                }
                val bytes = reply.toString().toByteArray(Charsets.UTF_8)
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        bridge.start()
        try {
            val controller = DesktopProfileHistory(store, a.id)
            controller.authorize("http://127.0.0.1:${bridge.address.port}", "bootstrap", a.address, "A")
            assertEquals("unverified", proof)
            assertEquals(listOf("旧答复"), uploaded)
            assertTrue(controller.reason(sid).contains("仅可阅读"))
            assertEquals("{", DesktopSharedStorage(store, key, a.address).load())
            assertFalse(controller.send(sid, "不可发送", store.nextDraftRevision()))
            val newSession = store.createSession(a.id).session
            store.renameSession(a.id, newSession, "新名称")
            store.selectProfile(b.id)
            controller.refresh(null)
            assertEquals(setOf(sid, newSession), records.keys)
            assertEquals("新名称", records.getValue(newSession).getString("title"))
            store.deleteSession(a.id, sid)
            val unlisted = store.createSession(a.id).session
            store.deleteSession(a.id, unlisted)
            store.selectProfile(b.id)
            controller.refresh(null)
            assertEquals(setOf(newSession), records.keys)
            assertEquals(setOf(sid, unlisted), removals.toSet())
            assertTrue(store.profileRemoved(a.id).isEmpty())
            assertEquals(b.id, store.activeProfileId)
            assertEquals(b.currentSession, store.profile(b.id)!!.currentSession)
        } finally {
            bridge.stop(0)
        }
    }

    @Test fun lostImportAndSendReceiptsRecoverAcrossRestartAndOnlyPublishTheSelectedProfile() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val path = files.root.toPath().resolve("history.json")
        val source = "http://127.0.0.1:6185"
        val store = DesktopStore(path)
        val profile = store.ensureProfile("launcher:fixture", source)
        val sid = profile.currentSession
        store.saveSessionContent(profile.id, Chat(sid, List(101) { Entry("重复", "答", "已完成") }))
        val separate = store.ensureProfile("B", "https://b.invalid")
        store.saveProfile(separate.id, Chat("foreign", listOf(Entry("私有", "内容", "已完成"))))

        val token = java.util.concurrent.atomic.AtomicReference(UUID.randomUUID().toString())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val address = "http://127.0.0.1:${server.address.port}"
        val rows = mutableListOf<JSONObject>()
        val starts = mutableListOf<Int>()
        val published = mutableListOf<String>()
        val requests = mutableListOf<JSONObject>()
        var created: JSONObject? = null
        var session: JSONObject? = null
        var status = JSONObject().put("expected", 0).put("complete", false).put("issue", "")
        server.createContext("/v1/profile/") { exchange ->
            exchange.use {
                val route = it.requestURI.path.substringAfterLast('/')
                val body = if (it.requestMethod == "POST") JSONObject(it.requestBody.reader(Charsets.UTF_8).readText()) else JSONObject()
                val params = it.requestURI.rawQuery.orEmpty().split('&').associate { p -> p.substringBefore('=') to p.substringAfter('=') }
                var code = 200
                val reply = if (route != "create" && it.requestHeaders.getFirst("Authorization") != "Bearer ${token.get()}") {
                    code = 401
                    JSONObject()
                } else when (route) {
                    "create" -> {
                        created = body
                        if (body.getString("code") == "回执丢失") code = 503
                        val remote = if (body.getString("code") == "另一档案") "unrelated" else "remote"
                        token.set(UUID.randomUUID().toString())
                        JSONObject().put("profile", remote).put("name", "测试档案").put("instance", "instance")
                            .put("owner", true).put("token", token.get())
                    }
                    "sessions" -> JSONObject(status.toString()).put("sessions", JSONArray(listOfNotNull(session)))
                        .put("name", "测试档案").put("more", false).put("revision", 7)
                    "status" -> { status = body; JSONObject() }
                    "session" -> {
                        published += body.getString("sid")
                        body.put("reason", "").put("imported", session?.getInt("imported") ?: 0).also { item -> session = item }
                    }
                    "import" -> {
                        val start = body.getInt("start")
                        starts += start
                        val page = body.getJSONArray("turns")
                        for (n in 0 until page.length()) rows += page.getJSONObject(n).put("seq", start + n + 1)
                        session!!.put("imported", rows.size)
                        if (start == 50) code = 503 // The transaction committed before its receipt was lost.
                        JSONObject().put("imported", rows.size)
                    }
                    "turns" -> JSONObject().put("turns", JSONArray(rows.drop(params.getValue("after").toInt())))
                        .put("more", false)
                    "turn" -> rows.single { row -> row.getString("id") == params["id"] }
                    "send" -> {
                        requests += body
                        rows += JSONObject().put("seq", rows.size + 1).put("id", body.getString("id"))
                            .put("question", body.getString("question")).put("answer", "唯一回复").put("status", "complete")
                        code = 503
                        JSONObject()
                    }
                    else -> { code = 404; JSONObject() }
                }
                val bytes = reply.toString().toByteArray(Charsets.UTF_8)
                it.sendResponseHeaders(code, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val desktop = DesktopProfileHistory(store, profile.id)
            desktop.authorize(address, "回执丢失", source, "测试档案")
            assertFalse(desktop.bound)
            assertNull(store.profileAuthorization(profile.id))
            desktop.authorize(address, "主人恢复码", source, "测试档案")
            assertTrue(desktop.notice, desktop.bound)
            assertEquals(profile.id, created!!.getString("sourceId"))
            assertEquals(store.username, created!!.getString("username"))
            assertEquals(source, created!!.getString("sourceBase"))
            assertEquals(listOf(0, 50), starts)
            assertFalse(status.getBoolean("complete"))
            assertTrue(status.getString("issue").contains("51–100"))
            assertFalse(Files.readString(path).contains(token.get()))

            val reopened = DesktopStore(path)
            val resumed = DesktopProfileHistory(reopened, profile.id)
            resumed.refresh(null)
            assertEquals(listOf(0, 50, 100), starts)
            assertEquals(101, rows.map { it.getString("id") }.distinct().size)
            assertEquals(setOf(sid), published.toSet())
            assertEquals(1, status.getInt("expected"))
            assertTrue(resumed.notice, status.getBoolean("complete"))
            assertTrue(requests.isEmpty())
            assertEquals(separate.id, reopened.activeProfileId)

            assertTrue(resumed.send(sid, "继续", reopened.nextDraftRevision()))
            assertTrue(resumed.hasPending(sid))
            val restartedStore = DesktopStore(path)
            val restarted = DesktopProfileHistory(restartedStore, profile.id)
            restarted.retry(sid)
            restarted.refresh(null)
            assertFalse(restarted.hasPending(sid))
            assertEquals(1, requests.size)
            assertEquals(102, restartedStore.sessionContent(profile.id, sid).entries.size)
            assertEquals("唯一回复", restartedStore.sessionContent(profile.id, sid).entries.last().answer)
            assertEquals(101, session!!.getInt("total"))
            assertEquals(separate.id, restartedStore.activeProfileId)

            token.set("expired")
            restarted.refresh(null)
            assertTrue(restarted.needsRecovery)
            restarted.recover(address, "主人恢复码", source, "测试档案")
            assertFalse(restarted.notice, restarted.needsRecovery)
            val draft = ComposerDraft("待发草稿")
            val key = SessionKey(profile.id, sid)
            restartedStore.saveDraft(key, restartedStore.nextDraftRevision(), draft)
            restartedStore.saveProfileAuthorization(profile.id, restartedStore.profileAuthorization(profile.id), "damaged-record")
            val repair = DesktopProfileHistory(restartedStore, profile.id)
            repair.refresh(null)
            assertTrue(repair.needsRecovery)
            repair.recover(address, "另一档案", source, "测试档案")
            assertTrue(repair.needsRecovery)
            assertEquals("damaged-record", restartedStore.profileAuthorization(profile.id))
            repair.recover(address, "主人恢复码", source, "测试档案")
            assertFalse(repair.notice, repair.needsRecovery)
            assertEquals(draft, restartedStore.readDraft(key))
            assertEquals(102, restartedStore.sessionContent(profile.id, sid).entries.size)
            assertEquals(listOf(0, 50, 100), starts)
            assertEquals(1, requests.size)

            restartedStore.updateProfileAddress(profile.id, "http://127.0.0.1:7012")
            repair.refresh(null)
            assertTrue(repair.reason(sid).contains("验证"))
            repair.refresh("http://127.0.0.1:7012")
            assertEquals("", repair.reason(sid))
        } finally {
            server.stop(0)
        }
    }
}
