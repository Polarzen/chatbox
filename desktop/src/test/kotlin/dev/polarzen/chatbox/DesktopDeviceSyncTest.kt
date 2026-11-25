package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.UUID

class DesktopDeviceSyncTest {
    @get:Rule val files = TemporaryFolder()
    private val owner = UUID.randomUUID().toString()
    private val phone = UUID.randomUUID().toString()
    private val instance = "fixture-instance"

    @Test fun verifyingANativeSidNeverAdoptsOrDeletesDeviceProfiles() {
        val path = files.root.toPath().resolve("history.json")
        val store = DesktopStore(path)
        val local = store.ensureProfile("native", "https://configured.invalid")
        store.saveDeviceOwner(instance, owner)
        val remote = DeviceProfile("remote", "native", local.address, "different-user", "手机", phone, "route", true)
        val mirror = store.cacheDeviceProfile(remote)
        val emptyMirror = store.cacheDeviceProfile(remote.copy(profile = "empty", sourceId = "other"))
        val key = store.cacheDeviceSession(mirror, DeviceSession("remote", "also-in-native-index", "旧会话", 10, 1, 1, "", phone))
        store.mergeProfilePage(key, remote.profile, listOf(SharedTurn(1, "phone-message", "手机", "原件", "complete")))
        assertTrue(store.adoptVerifiedSessions(local.id, local.address, setOf(key.sessionId)).isEmpty())
        val read = DesktopStore(path)
        assertNull(read.sessions(local.id).find { it.id == key.sessionId })
        assertEquals("原件", read.sessionContent(mirror, key.sessionId).entries.single().answer)
        assertEquals(remote.username, read.deviceProfile(mirror)?.username)
        assertNotNull(read.deviceProfile(emptyMirror))
    }

    @Test fun firstLaunchKeepsTheNativeProfileAndDraftPrivateUntilConsent() = runBlocking {
        val store = DesktopStore(files.root.toPath().resolve("history.json"))
        val local = store.ensureProfile("native", "https://not-contacted.invalid")
        val key = SessionKey(local.id, local.currentSession)
        store.saveDraft(key, 1, ComposerDraft("私人"))
        val keys = NamedKeys(files.root.toPath().resolve("keys"), DesktopCredentials(files.root.toPath().resolve("secrets")))
        val sync = DesktopDeviceSync(store, keys) { error("Host called before consent") }
        sync.refresh()
        assertFalse(sync.enabled)
        assertFalse(sync.manages(local.id))
        assertFalse(sync.send(key, "私人", 1))
        assertEquals(ComposerDraft("私人"), store.readDraft(key))
        assertNull(store.deviceOwner())
    }

    @Test fun mediaSourceAndDeviceQueueSurviveRestartAndOnlyTheMatchingDraftIsConsumed() {
        val path = files.root.toPath().resolve("history.json")
        val initial = DesktopStore(path)
        val p = initial.ensureProfile("native", "https://native.invalid")
        val key = SessionKey(p.id, p.currentSession)
        initial.saveDeviceOwner(instance, owner)
        val remote = DeviceProfile("pc", "native", p.address, initial.username, "本机", owner, "route", true)
        initial.cacheDeviceProfile(remote, p.id)
        val original = initial.managedMediaDirectory.resolve("screenshot-77.png")
        java.nio.file.Files.createDirectories(original.parent)
        java.nio.file.Files.write(original, byteArrayOf(1, 2, 3))
        val source = Entry("图片", "", "发送中", attachmentReference = original.toString())
        val question = desktopSharedSeedTurn(source).question
        initial.saveDraft(key, 1, ComposerDraft(source.question, listOf(original)))
        val id = initial.queueDeviceSend(key, question, 1, source)
        initial.saveDraft(key, 2, ComposerDraft("下一条"))
        initial.saveDraft(key, 1, ComposerDraft("旧草稿"))
        initial.cleanupUnreferencedManagedMedia()
        assertTrue(java.nio.file.Files.exists(original))
        val store = DesktopStore(path)
        assertEquals(ComposerDraft("下一条"), store.readDraft(key))
        assertEquals(source, store.nextDeviceSend(key)!!.entry?.copy(attachmentDisplayName = null))
        store.prepareDeviceHistory(key, remote.profile)
        store.markDeviceSend(key, id, "media", "dispatch")
        val result = source.copy(answer = "答", status = "已完成", replyAttachments =
            listOf(ReplyAttachment("opaque", 1, ReplyAttachmentType.AUDIO)))
        store.markDeviceSend(key, id, "media", "finished", result)
        val terminal = SharedTurn(1, id, question, desktopSharedSeedTurn(result).answer, "complete")
        assertFalse(store.mergeProfilePage(key, remote.profile, listOf(terminal), 100))
        store.markDeviceSend(key, id, "media", "confirmed", result)
        assertTrue(store.mergeProfilePage(key, remote.profile, listOf(terminal), 100))
        assertNull(store.nextDeviceSend(key))
        val entry = DesktopStore(path).sessionContent(key.profileId, key.sessionId).entries.single()
        assertEquals(result, entry.copy(attachmentDisplayName = null))
        assertFalse(question.contains(original.toString()))
        assertEquals("下一条", store.readDraft(key).text)
    }

    @Test fun remoteNativeSourcesAndLostFirstReceiptConvergeWithoutSwitchingTheDraft() = runBlocking {
        val path = files.root.toPath().resolve("history.json")
        var store = DesktopStore(path)
        val p = store.ensureProfile("native", "https://native.invalid")
        val key = SessionKey(p.id, p.currentSession)
        store.saveDeviceOwner(instance, owner)
        store.saveDraft(key, 1, ComposerDraft("私人草稿"))
        val rows = mutableMapOf<Pair<String, String>, JSONObject>()
        val turns = mutableMapOf<Pair<String, String>, MutableList<JSONObject>>()
        val operations = mutableMapOf<String, JSONObject>()
        val published = mutableListOf<String>()
        val posts = mutableListOf<String>()
        val lookups = mutableListOf<String>()
        val peers = mutableMapOf<String, JSONObject>()
        fun profile(id: String, source: String, username: String, origin: String) =
            JSONObject().put("profile", id).put("sourceId", source).put("username", username).put("base", p.address)
                .put("name", "同名").put("originDevice", origin).put("upstream", "route").put("available", true)
        fun row(prof: String, sid: String, origin: String, activity: Long) = JSONObject().put("profile", prof).put("sid", sid)
            .put("originDevice", origin).put("title", "同名").put("activity", activity).put("total", 1)
            .put("imported", 1).put("reason", "")
        fun turn(seq: Int, id: String, question: String) = JSONObject().put("seq", seq).put("id", id)
            .put("question", question).put("answer", "答$seq").put("status", "complete")
        peers["mobile"] = profile("mobile", "native", "other-user", phone)
        val phoneKey = "mobile" to p.currentSession
        rows[phoneKey] = row("mobile", p.currentSession, phone, 91)
        turns[phoneKey] = mutableListOf(turn(1, "other-id", "手机原文"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var dropFirst = true
        var offline = false
        server.createContext("/v1/device/") { request ->
            request.use { ex ->
                val method = ex.requestURI.path.substringAfterLast('/')
                val query = ex.requestURI.rawQuery.orEmpty().split("&").map { it.substringBefore('=') to it.substringAfter('=') }.toMap()
                val body = if (ex.requestMethod == "POST") JSONObject(ex.requestBody.reader(Charsets.UTF_8).readText()) else JSONObject()
                var code = 200
                val answer = when (method) {
                    "profiles" -> JSONObject().put("profiles", JSONArray(peers.values))
                    "profile" -> profile("pc", body.getString("sourceId"), body.getString("username"), owner)
                        .put("name", body.getString("name")).also { peers["pc"] = it }
                    "sessions" -> JSONObject().put("sessions", JSONArray(rows.values)).put("revision", "1").put("more", false)
                    "session" -> error("An empty local draft was published")
                    "devices" -> JSONObject().put("devices", JSONArray())
                    "turns" -> {
                        val after = query.getValue("after").toInt()
                        JSONObject().put("turns", JSONArray(turns.getValue(query.getValue("profile") to query.getValue("sid")).drop(after)))
                            .put("more", false)
                    }
                    "operation" -> operations[query.getValue("id")].also { lookups += query.getValue("id") } ?: JSONObject().also { code = 404 }
                    "start", "send" -> {
                        val target = body.getString("profile") to body.getString("sid")
                        val id = body.getString("id")
                        posts += method
                        val timeline = turns.getOrPut(target) { mutableListOf() }
                        val t = turn(timeline.size + 1, id, body.getString("question"))
                        timeline.add(t)
                        rows[target] = row(target.first, target.second, owner, System.currentTimeMillis())
                            .put("total", timeline.size).put("imported", timeline.size)
                        JSONObject().put("profile", target.first).put("sid", target.second).put("turn", t).also {
                            operations[id] = it
                            published += body.toString()
                            if (dropFirst) { dropFirst = false; code = 503 }
                        }
                    }
                    "remove" -> JSONObject().also { rows.remove(body.getString("profile") to body.getString("sid")) }
                    else -> error(method)
                }
                val bytes = answer.toString().toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val keys = NamedKeys(files.root.toPath().resolve("keys"), DesktopCredentials(files.root.toPath().resolve("creds")))
            fun sync() = DesktopDeviceSync(store, keys) { req ->
                check(req.getString("action") == "local")
                if (offline) JSONObject().put("ok", false).put("error", "service_offline") else
                    JSONObject().put("ok", true).put("device", owner).put("instance", instance).put("token", "fixture-token")
                        .put("localUrl", "http://127.0.0.1:${server.address.port}").put("phoneUrl", "https://host.invalid")
            }
            var sync = sync()
            sync.refresh()
            val mobile = sync.recent().single()
            assertEquals(p.currentSession, mobile.key.sessionId)
            assertNotEquals(p.id, mobile.key.profileId)
            assertEquals("other-user", store.deviceProfile(mobile.key.profileId)!!.username)
            assertEquals("手机原文", store.sessionContent(mobile.key.profileId, mobile.key.sessionId).entries.single().question)
            store.renameSession(mobile.key.profileId, mobile.key.sessionId, "我的本机昵称")
            sync.refresh()
            assertEquals("我的本机昵称", sync.recent().single().title)
            store.renameSession(mobile.key.profileId, mobile.key.sessionId, null)
            assertEquals("同名", sync.recent().single().title)
            assertEquals("私人草稿", store.readDraft(key).text)
            assertEquals(p.id, store.activeProfileId)
            offline = true
            sync.refresh()
            assertTrue(sync.send(key, "同文", 2))
            assertTrue(sync.send(key, "同文", 3))
            store.saveDraft(key, 4, ComposerDraft("保留"))
            offline = false
            sync.refresh()
            assertEquals(listOf("start"), posts)
            store = DesktopStore(path)
            sync = sync()
            sync.refresh()
            sync.refresh()
            assertEquals(listOf("start", "send"), posts)
            assertEquals(operations.keys.first(), lookups.first())
            assertEquals(2, operations.size)
            assertEquals(listOf("答1", "答2"), store.sessionContent(key.profileId, key.sessionId).entries.map { it.answer })
            assertEquals("保留", store.readDraft(key).text)
            assertEquals(key, sync.recent().first().key)
            assertEquals(0, sync.pending(key))
            assertTrue(published.none { "私人草稿" in it || "保留" in it })
            store.deleteSession(mobile.key.profileId, mobile.key.sessionId)
            sync.refresh()
            assertFalse(phoneKey in rows)
            assertEquals(key, sync.recent().single().key)
        } finally { server.stop(0) }
    }

    @Test fun deviceRefreshChecksOldReceiptsWithoutReplayingOldSendsOrDeletions() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val path = files.root.toPath().resolve("history.json")
        val original = DesktopStore(path)
        val local = original.ensureProfile("native", "https://old.invalid")
        val key = SessionKey(local.id, local.currentSession)
        val pending = SharedPending(UUID.randomUUID().toString(), "尚待核对")
        val deleted = original.createSession(local.id).session
        val oldOwner = JSONObject().put("instance", instance).put("source", local.address).put("token", "original-owner")
        val entropy = java.security.MessageDigest.getInstance("SHA-256").digest(
            JSONArray(listOf("desktop-profile-owner-v1", local.id, original.username)).toString().toByteArray(Charsets.UTF_8))
        editLegacyFixture(path, key) { profile, session ->
            profile.put("profileAuthorization", java.util.Base64.getEncoder().encodeToString(
                WindowsDpapi.transform(entropy, oldOwner.toString().toByteArray(Charsets.UTF_8), true)))
            session.put("profileHistory", JSONObject().put("profile", "pc").put("prefix", 0)
                .put("pending", JSONObject().put("id", pending.id).put("question", pending.question)))
        }
        val store = DesktopStore(path)
        store.deleteSession(local.id, deleted)
        assertTrue(store.deviceRemoved(local.id).isEmpty())
        assertEquals(setOf(deleted), store.legacyHiddenSessions(local.id))
        store.saveDeviceOwner(instance, owner)
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun row(sid: String) = JSONObject().put("profile", "pc").put("sid", sid).put("originDevice", owner)
            .put("title", "原会话").put("activity", 1).put("imported", 0).put("total", 0).put("reason", "")
        server.createContext("/") { exchange -> exchange.use { ex ->
            val route = ex.requestURI.path
            requests += route
            val answer = when (route) {
                "/v1/device/profiles" -> JSONObject().put("profiles", JSONArray())
                "/v1/device/profile" -> JSONObject(ex.requestBody.reader().readText())
                    .put("profile", "pc").put("originDevice", owner).put("upstream", "test").put("available", true)
                "/v1/device/sessions" -> JSONObject().put("sessions", JSONArray().put(row(key.sessionId)).put(row(deleted)))
                    .put("more", false).put("revision", "1")
                "/v1/device/devices" -> JSONObject().put("devices", JSONArray())
                else -> null
            }
            val bytes = (answer ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(if (answer == null) 404 else 200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } }
        server.start()
        try {
            val keys = NamedKeys(files.root.toPath().resolve("keys"), DesktopCredentials(files.root.toPath().resolve("secrets")))
            val sync = DesktopDeviceSync(store, keys) {
                JSONObject().put("ok", true).put("localUrl", "http://127.0.0.1:${server.address.port}")
                    .put("instance", instance).put("device", owner).put("token", "device-owner")
            }
            sync.refresh()
            assertTrue("/v1/device/turn" in requests)
            assertTrue(requests.all { it in setOf("/v1/device/profiles", "/v1/device/profile", "/v1/device/sessions",
                "/v1/device/turn", "/v1/device/devices") })
            assertEquals(pending, DesktopStore(path).profileHistoryPosition(key, "pc").pending)
            assertEquals(setOf(deleted), store.legacyHiddenSessions(local.id))
            assertTrue(store.sessions(local.id).none { it.id == deleted })
            assertTrue(sync.reason(key).contains("尚未确认"))
        } finally { server.stop(0) }
    }

    @Test fun confirmedAliasAndMediaUseOnlyTheVerifiedNativeUpstream() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val phoneBase = "https://phone-astrbot.invalid"
        val path = files.root.toPath().resolve("history.json")
        var store = DesktopStore(path)
        val local = store.ensureProfile("native", base)
        val key = SessionKey(local.id, local.currentSession)
        store.saveDeviceOwner(instance, owner)
        val keys = NamedKeys(files.root.toPath().resolve("keys"), DesktopCredentials(files.root.toPath().resolve("secrets")))
        val keyId = keys.add(local.id, "fixture", "fake-local-key").id
        keys.activate(local.id, keyId)
        val aliases = mutableListOf<String>()
        var pc: JSONObject? = null
        var op: JSONObject? = null
        var endCount = 0
        var beginCount = 0
        var dispatchCount = 0
        var uploads = 0
        val phoneProfile = JSONObject().put("profile", "remote").put("sourceId", "phone-source").put("name", "手机")
            .put("username", "other").put("originDevice", phone).put("base", phoneBase)
        server.createContext("/api/v1/file") { exchange ->
            exchange.use {
                check(it.requestHeaders.getFirst("Authorization") == "Bearer fake-local-key" && it.requestBody.readBytes().isNotEmpty())
                uploads++
                val bytes = """{"status":"ok","data":{"attachment_id":"fixture-media"}}""".toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.use { output -> output.write(bytes) }
            }
        }
        server.createContext("/") { request ->
            request.use { ex ->
                val body = if (ex.requestMethod == "POST") JSONObject(ex.requestBody.reader(Charsets.UTF_8).readText()) else JSONObject()
                if (ex.requestURI.path == "/api/v1/chat") {
                    check(body.toString().contains("fixture-media") && body.toString().contains(key.sessionId))
                    dispatchCount++
                    val reply = "data: {\"type\":\"plain\",\"data\":\"原答\"}\n\ndata: {\"type\":\"end\"}\n\n".toByteArray()
                    ex.responseHeaders.add("Content-Type", "text/event-stream")
                    ex.sendResponseHeaders(200, reply.size.toLong())
                    ex.responseBody.use { it.write(reply) }
                    return@use
                }
                var code = 200
                val answer = if (ex.requestURI.path == "/api/v1/chat/sessions") {
                    check(ex.requestHeaders.getFirst("Authorization") == "Bearer fake-local-key")
                    JSONObject().put("status", "ok").put("data", JSONObject().put("page", 1).put("page_size", 100)
                        .put("total", 0).put("sessions", JSONArray()))
                } else when (ex.requestURI.path.substringAfterLast('/')) {
                    "profiles" -> {
                        phoneProfile.put("available", phoneBase in aliases).put("upstream", if (phoneBase in aliases) "route" else "")
                        JSONObject().put("profiles", JSONArray(listOfNotNull(phoneProfile, pc)))
                    }
                    "profile" -> body.put("profile", "pc").put("originDevice", owner).put("available", true).also { pc = it }
                    "sessions" -> {
                        val records = op?.let { JSONObject().put("profile", "pc").put("sid", key.sessionId).put("total", 1)
                            .put("imported", 1).put("title", "媒体").put("originDevice", owner).put("activity", 42).put("reason", "") }
                        JSONObject().put("sessions", JSONArray(listOfNotNull(records))).put("more", false).put("revision", 1)
                    }
                    "media-begin" -> {
                        beginCount++
                        if (op == null) {
                            val turn = JSONObject().put("seq", 1).put("id", body.getString("id")).put("question", body.getString("question"))
                                .put("answer", "").put("status", "running")
                            op = JSONObject().put("profile", "pc").put("sid", key.sessionId).put("turn", turn)
                            code = 503
                        }
                        check(op!!.getJSONObject("turn").getString("id") == body.getString("id"))
                        op!!.put("dispatch", beginCount == 1)
                    }
                    "media-end" -> {
                        endCount++
                        op!!.getJSONObject("turn").put("status", body.getString("status")).put("answer", body.getString("answer"))
                        when (endCount) {
                            1 -> JSONObject().also { code = 503 }
                            2 -> JSONObject(op.toString()).also { it.getJSONObject("turn").put("answer", "错配的完成回执") }
                            else -> op!!
                        }
                    }
                    "operation" -> checkNotNull(op)
                    "turns" -> JSONObject().put("turns", JSONArray().put(checkNotNull(op).getJSONObject("turn"))).put("more", false)
                    "devices" -> JSONObject().put("devices", JSONArray())
                    else -> error(ex.requestURI.path)
                }
                val bytes = answer.toString().toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            fun controller() = DesktopDeviceSync(store, keys) { req ->
                when (req.getString("action")) {
                    "local" -> JSONObject().put("ok", true).put("device", owner).put("instance", instance).put("token", "fixture-token")
                        .put("localUrl", base)
                    "upstream" -> {
                        check(req.getString("base") == base && req.getString("key") == "fake-local-key")
                        check(!req.has("aliases"))
                        if (req.has("addAlias")) aliases += req.getString("addAlias")
                        JSONObject().put("ok", true).put("upstream", "route")
                    }
                    else -> error("Unexpected action")
                }
            }
            var sync = controller()
            sync.refresh()
            assertTrue(aliases.isEmpty())
            assertEquals(phoneBase, sync.unmatchedProfiles.single().base)
            assertEquals(base, sync.upstreamCandidates.single().base)
            sync.linkSource("remote", local.id)
            assertEquals(listOf(phoneBase), aliases)
            assertTrue(sync.unmatchedProfiles.isEmpty())
            val image = files.newFile("private-filename.png").toPath()
            java.nio.file.Files.write(image, java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="))
            val original = Entry("问题", "", "待发送", image.toString())
            val accepted = sync.sendMedia(key, original, 1) { error("The first reservation receipt was lost") }
            assertTrue(accepted)
            assertEquals(0, dispatchCount)
            store = DesktopStore(path)
            sync = controller()
            sync.refresh()
            assertEquals("finished", store.nextDeviceSend(key)?.phase)
            store = DesktopStore(path)
            sync = controller()
            sync.refresh()
            assertEquals("finished", store.nextDeviceSend(key)?.phase)
            sync.refresh()
            assertEquals(2, beginCount)
            assertEquals(1, uploads)
            assertEquals(1, dispatchCount)
            assertEquals(3, endCount)
            assertFalse(op.toString().contains("private-filename"))
            val entry = store.sessionContent(local.id, key.sessionId).entries.single()
            assertEquals(image.toString(), entry.attachmentReference)
            assertEquals("原答", entry.answer)
            assertEquals(0, sync.pending(key))

            val question = desktopSharedSeedTurn(original).question
            val interrupted = store.queueDeviceSend(key, question, 2, original)
            store.markDeviceSend(key, interrupted, "media")
            op = JSONObject().put("profile", "pc").put("sid", key.sessionId)
                .put("turn", JSONObject().put("seq", 2).put("id", interrupted).put("question", question)
                    .put("answer", "").put("status", "running"))
            val moved = java.nio.file.Files.move(image, image.resolveSibling("temporarily-unavailable.png"))
            sync.refresh()
            assertEquals("", store.nextDeviceSend(key)?.phase)
            assertEquals(1, uploads)
            java.nio.file.Files.move(moved, image)
            assertTrue(store.claimDeviceMedia(key, interrupted))
            assertFalse(store.claimDeviceMedia(key, interrupted))
            store = DesktopStore(path)
            sync = controller()
            sync.refresh()
            assertEquals("上次回复未确认", store.sessionContent(local.id, key.sessionId).entries.last().status)
            assertEquals(0, sync.pending(key))
            assertEquals(1, uploads)
            assertEquals(1, dispatchCount)
            assertEquals(4, endCount)
        } finally { server.stop(0) }
    }
}
