package dev.polarzen.chatbox

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.UUID

class DesktopPreferencePersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun directScopeAndThresholdSurviveRestart() = runBlocking {
        val path = tmp.root.toPath().resolve("history.json")
        val store = DesktopStore(path)
        val p = store.ensureProfile("native", "https://fixture.invalid")
        val k = SessionKey(p.id, p.currentSession)
        val keys = NamedKeys(tmp.root.toPath(), DesktopCredentials(tmp.root.toPath()))
        val sync = DesktopDeviceSync(store, keys) { error("unexpected network access") }
        val first = sync.preferences(k)
        first.save(first.value.copy(profileName = "服务", peerName = "小蓝", remindAfter = 2))
        val changed = sync.preferences(k)
        assertFalse(changed.claimReminder(1))
        assertTrue(changed.claimReminder(2))
        val sibling = SessionKey(p.id, store.createSession(p.id).session)
        assertEquals("服务", sync.preferences(sibling).value.effectiveName)
        val restored = DesktopDeviceSync(DesktopStore(path), keys) { error("unexpected network access") }.preferences(k)
        assertEquals("小蓝", restored.value.effectiveName)
        assertFalse(restored.claimReminder(3))
        assertFalse(restored.pending)
        assertEquals(ConversationPreferences(), store.deviceJournal(PreferenceRoom(k.profileId,k.sessionId)).value)
    }

    @Test fun sameFieldConflictSurvivesReconnectAndIndependentFieldCommits() = runBlocking {
        val path = tmp.root.toPath().resolve("history.json")
        val room = PreferenceRoom("profile", "session")
        var remote = ConversationPreferences(peerName = "旧名")
        val writes = mutableListOf<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0),0)
        server.createContext("/v1/device/chat-preferences") { exchange ->
            if (exchange.requestMethod == "POST") {
                val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                writes.add(body)
                val changed = body.getJSONObject("changes")
                val merged = remote.toJson()
                changed.keys().forEach { k -> merged.put(k, changed.get(k)) }
                remote = ConversationPreferences.fromJson(merged)
            }
            val bytes = remote.toJson().toString().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val grant = DeviceBinding("http://127.0.0.1:${server.address.port}", "fixture", UUID.randomUUID().toString(), "key", true)
            val api = SharedDeviceTransport(true)
            val store = DesktopStore(path)
            fun edit(before: ConversationPreferences) = before.copy(peerName = "电脑", remindAfter = 5)
            syncPreferenceRoom(api, grant, room) { store.deviceJournal(room, it) }
            store.deviceJournal(room) { it.edit(it.value, edit(it.value), emptySet()) }
            remote = remote.copy(peerName = "手机", recentTurns = 3)
            val reopened = DesktopStore(path)
            syncPreferenceRoom(api, grant, room) { reopened.deviceJournal(room,it) }
            val result = reopened.deviceJournal(room)
            assertEquals(setOf("peerName"), result.conflicts)
            assertEquals("电脑", result.value.peerName)
            assertEquals("手机", result.base.peerName)
            assertEquals(3, result.value.recentTurns)
            assertEquals(setOf("remindAfter"), writes.single().getJSONObject("changes").keySet())
            reopened.deviceJournal(room) { it.edit(it.value,it.value,it.conflicts) }
            syncPreferenceRoom(api, grant, room) { reopened.deviceJournal(room,it) }
            assertEquals("电脑", remote.peerName)
            assertTrue(reopened.deviceJournal(room).conflicts.isEmpty())
        } finally { server.stop(0) }
    }

    @Test fun resolvingANameDoesNotAcceptAnUnrelatedContextConflict() {
        val baseline = ConversationPreferences(peerName="远端名",recentTurns=2)
        var record = PreferenceJournal(baseline, baseline.copy(peerName="本机名",recentTurns=4),
            setOf("peerName","recentTurns"))
        val handle = preferenceHandle("room",record,true,true,{ null },{ true },
            { edit -> edit(record).also { record = it } },{ false })
        handle.saveReviewed(handle.value, setOf("peerName","profileName"))
        assertEquals(setOf("recentTurns"),record.conflicts)
        assertEquals("本机名",record.value.peerName)
    }

    @Test fun conflictingDisplayNameDoesNotBlockTheConfirmedContext() {
        val confirmed = ConversationPreferences(peerName="手机",recentTurns=2,pins=listOf(ContextPin(1,"user")))
        val local = confirmed.copy(peerName="电脑")
        val journal = PreferenceJournal(confirmed,local,setOf("peerName"))
        val handle = preferenceHandle("room",journal,true,true,{ 1L },{ true },{ it(journal) },{ false })
        assertTrue(handle.pending)
        assertFalse(handle.contextPending)
        assertEquals(confirmed,handle.remoteValue)
        assertTrue(handle.value.sameContext(confirmed))
    }

    @Test fun revertingAnUnacknowledgedNameSurvivesRestartAndRemoteObservation() {
        val original = ConversationPreferences(peerName="原名")
        val posted = original.copy(peerName="临时名")
        val sending = PreferenceJournal(original).edit(original,posted,emptySet())
        val reverted = sending.edit(posted,original,emptySet())
        val loaded = PreferenceJournal.decode(reverted.encode(true),reverted.encode(false))
        assertTrue(loaded.pending)
        val remote = loaded.observe(posted)
        assertEquals(original,remote.value)
        assertEquals(setOf("peerName"),remote.conflicts)
        assertFalse(loaded.observe(original).pending)
    }

    @Test fun capacityAndRollingOnlyRearmAfterANewerTurnOrChangedPolicy() = runBlocking {
        val path = tmp.root.toPath().resolve("history.json")
        val store = DesktopStore(path)
        val source = store.ensureProfile("native","https://fixture.invalid")
        val key = SessionKey(source.id,source.currentSession)
        val keys = NamedKeys(tmp.root.toPath(), DesktopCredentials(tmp.root.toPath()))
        val turns = List(3) { Entry("问 $it","答","已完成") }
        store.saveSessionContent(source.id, Chat(key.sessionId,turns.take(2)))
        val first = DesktopDeviceSync(store,keys)
        first.preferences(key).save(ConversationPreferences(recentTurns=2))
        val original = first.preferences(key)
        assertTrue(original.observeNotice("capacity",2,true))
        assertFalse(original.observeNotice("capacity",2,false))
        val read = DesktopStore(path)
        val sync = DesktopDeviceSync(read,keys)
        val restored = sync.preferences(key)
        assertFalse(restored.observeNotice("capacity",2,true))
        assertFalse(restored.observeNotice("capacity",1,false))
        assertTrue(restored.observeNotice("rolling",2,true))
        read.saveSessionContent(source.id,Chat(key.sessionId,turns))
        assertFalse(restored.observeNotice("capacity",3,false))
        assertTrue(restored.observeNotice("capacity",3,true))
        restored.save(restored.value.copy(recentTurns=1))
        assertFalse(restored.observeNotice("capacity",3,true))
        assertTrue(sync.preferences(key).observeNotice("capacity",3,true))
    }

    @Test fun stalePreviewRestoresAuthoredTextWithoutReplacingANewerDraft() {
        val path = tmp.root.toPath().resolve("history.json")
        val store = DesktopStore(path)
        val p = store.ensureProfile("native","https://fixture.invalid")
        val key = SessionKey(p.id,p.currentSession)
        val id = store.queueDeviceSend(key,"待核对的问题",1,contextRevision="approved")
        assertEquals("approved",DesktopStore(path).nextDeviceSend(key)?.contextRevision)
        store.saveDraft(key,2,ComposerDraft("新的草稿"))
        store.restoreRejectedDeviceDraft(key,id,409)
        val restored = DesktopStore(path)
        assertNull(restored.nextDeviceSend(key))
        assertEquals("新的草稿",restored.readDraft(key).text)
        assertEquals(key.sessionId,restored.profile(p.id)?.currentSession)
        val other = restored.sessions(p.id).single { it.id != key.sessionId }
        assertEquals("待核对的问题",restored.readDraft(SessionKey(p.id,other.id)).text)
    }

    @Test fun explicitDeviceRejectionsRestoreOnlyProvenUnsentRequests() = runBlocking {
        val path = tmp.root.toPath().resolve("history.json")
        val store = DesktopStore(path)
        val owner = UUID.randomUUID().toString()
        store.saveDeviceOwner("instance",owner)
        val p = DeviceProfile("remote","source","https://native.invalid","user","手机","phone","route",true)
        val profile = store.cacheDeviceProfile(p)
        val room = DeviceSession(p.profile,"sid","title",10,0,0,"","phone")
        val key = store.cacheDeviceSession(profile,room)
        val file = tmp.newFile("image.png").toPath()
        val media = Entry("附件文字","","待发送",attachmentReference=file.toString())
        val original = ComposerDraft("  附件文字  ",listOf(file))
        store.saveDraft(key,1,original)
        val rejectedId = store.queueDeviceSend(key,desktopSharedSeedTurn(media).question,1,media)
        var mediaPosts = 0
        var textPosts = 0
        var contextPosts = 0
        var contextId = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { ex -> ex.use {
            var code = 200
            val response = when (ex.requestURI.path.substringAfterLast('/')) {
                "profiles" -> JSONObject().put("profiles",JSONArray().put(JSONObject()
                    .put("profile",p.profile).put("sourceId",p.sourceId).put("base",p.base).put("username",p.username)
                    .put("name",p.name).put("originDevice",p.originDevice).put("upstream",p.upstream).put("available",true)))
                "sessions" -> JSONObject().put("more",false).put("revision","1").put("sessions",JSONArray().put(JSONObject()
                    .put("profile",room.profile).put("sid",room.sid).put("title",room.title).put("activity",10)
                    .put("total",0).put("imported",0).put("reason","").put("originDevice",p.originDevice)))
                "turns" -> JSONObject().put("turns",JSONArray()).put("more",false)
                "media-begin" -> JSONObject().also { mediaPosts++; code = 403 }
                "send" -> {
                    val request = JSONObject(ex.requestBody.reader(Charsets.UTF_8).readText())
                    if (request.has("revision")) {
                        contextPosts++
                        code = 202
                        request.put("contextState","pending")
                    } else JSONObject().also { textPosts++; code = 503 }
                }
                "operation" -> if (ex.requestURI.rawQuery == "id=$contextId") {
                    code = 409
                    JSONObject().put("code","context_not_dispatched").put("error","上游未派发")
                } else JSONObject().also { code = 401 }
                "devices" -> JSONObject().put("devices",JSONArray())
                "chat-preferences" -> ConversationPreferences().toJson()
                else -> JSONObject().also { code = 404 }
            }
            val body = response.toString().toByteArray()
            ex.sendResponseHeaders(code,body.size.toLong())
            ex.responseBody.use { it.write(body) }
        } }
        server.start()
        try {
            val sync = DesktopDeviceSync(store,NamedKeys(tmp.root.toPath(),DesktopCredentials(tmp.root.toPath()))) {
                JSONObject().put("ok",true).put("localUrl","http://127.0.0.1:${server.address.port}")
                    .put("instance","instance").put("device",owner).put("token","fixture")
            }
            sync.refresh()
            assertEquals(1,mediaPosts)
            assertNull(store.nextDeviceSend(key))
            assertEquals(rejectedId to original,DesktopStore(path).restoredDeviceDraft(key))
            store.saveDraft(key,2,ComposerDraft("后输入"))
            val backup = store.sessions(profile).single { it.id != room.sid }
            assertEquals(original,store.readDraft(SessionKey(profile,backup.id)))
            contextId = store.queueDeviceSend(key,"后输入",2,contextRevision="confirmed")
            sync.refresh()
            assertEquals(contextId,DesktopStore(path).nextDeviceSend(key)?.id)
            sync.refresh()
            assertEquals(1, contextPosts)
            assertNull(DesktopStore(path).nextDeviceSend(key))
            assertEquals(contextId to ComposerDraft("后输入"),DesktopStore(path).restoredDeviceDraft(key))
            store.saveDraft(key,3,ComposerDraft("继续编辑"))
            val uncertainId = store.queueDeviceSend(key,"继续编辑",3)
            sync.refresh()
            sync.refresh()
            assertEquals(1,textPosts)
            assertEquals(uncertainId,DesktopStore(path).nextDeviceSend(key)?.id)
            assertEquals(ComposerDraft(),store.readDraft(key))
        } finally { server.stop(0) }
    }
}
