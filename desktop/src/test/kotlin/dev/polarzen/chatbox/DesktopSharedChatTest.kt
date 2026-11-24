package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSharedChatTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    @Test fun seedImportsOnlyTranscriptAndAttachmentHints() {
        val chat = Chat("session", listOf(
            Entry("look", "ab", "已完成", attachmentReferences = listOf("C:/sensitive/file.wav"),
                replyAttachments = listOf(ReplyAttachment("private-id", 1, ReplyAttachmentType.IMAGE))),
            Entry("", "", "发送失败：private diagnostics", attachmentDisplayName = "private.png"),
            Entry("听听", "", "已完成", attachmentDisplayName = "personal recording.WaV")
        ))
        assertEquals(SharedSeed("https://example.invalid", "alice", "session", listOf(
            SharedSeedTurn("[语音附件]\nlook", "a[图片附件]b", "complete"),
            SharedSeedTurn("[图片附件]", "", "unknown"),
            SharedSeedTurn("[语音附件]\n听听", "", "complete")
        )), desktopSharedSeed("https://example.invalid", "alice", chat))
        assertEquals(SharedSeed("", "", "", emptyList()), desktopSharedSeed("", "alice", chat))
    }

    private class Room(turns: String = "[]") : AutoCloseable {
        val posts = CopyOnWriteArrayList<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val binding = SharedBinding("http://127.0.0.1:" + server.address.port, "room", "instance", "token", false)
        var record = SharedLocal(binding).encode()
        val storage = object : SharedLocalStorage {
            override fun load() = record
            override fun save(value: String?) { record = value }
        }

        init {
            server.createContext("/v1/") { exchange ->
                exchange.use {
                    val (code, body) = when (it.requestURI.path) {
                        "/v1/turns" -> 200 to """{"turns":$turns,"more":false}"""
                        "/v1/turn" -> 404 to "{}"
                        "/v1/send" -> {
                            posts.add(JSONObject(it.requestBody.bufferedReader(Charsets.UTF_8).readText()))
                            503 to "{}" // Ambiguous result requires reconciliation.
                        }
                        else -> 404 to "{}"
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    it.sendResponseHeaders(code, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    @Test fun sharedSendDecoratesOnceAndReconcilesTheOriginalRequestAfterReopen() = runBlocking {
        Room().use { room ->
            var sampled = 0
            fun open() = SharedChatState(room.storage, true) { text -> "$text\nBPM " + ++sampled }
            val state = open()
            state.refresh()
            for (command in listOf("/task", "  /task\tlist", "/task\nstatus")) {
                state.editDraft(command)
                state.send()
                assertEquals(command, state.local.draft)
                assertTrue(state.notice.contains("Windows") && state.notice.contains("登录 Dashboard"))
            }
            assertTrue(room.posts.isEmpty())
            assertEquals(0, sampled)
            state.editDraft("近况")
            state.send()
            val reopened = open()
            reopened.refresh()
            reopened.retryPending(reopened.local.pending!!.id)
            assertEquals(1, sampled)
            assertEquals(listOf("近况\nBPM 1", "近况\nBPM 1"), room.posts.map { it.getString("question") })
            assertEquals(1, room.posts.map { it.getString("id") }.distinct().size)
            reopened.releasePending()
            assertEquals("近况", reopened.local.draft)
            reopened.editDraft("/tasks list")
            reopened.send()
            assertEquals("/tasks list", room.posts.last().getString("question"))
            assertEquals(1, sampled)

            val old = SharedPending(UUID.randomUUID().toString(), "/task approve example")
            room.record = SharedLocal(room.binding, pending = old).encode()
            val legacy = open()
            legacy.refresh()
            legacy.retryPending(old.id)
            assertEquals(old, legacy.local.pending)
            assertEquals(3, room.posts.size)
        }
    }

    @Test fun sharedTranscriptKeepsRoleHintsAndShowsAvailabilityAsPlainText() {
        val turns = """[{"seq":1,"id":"one","question":"[语音附件]\n请听","answer":"答〔图片〕","status":"complete"},
            {"seq":2,"id":"two","question":"〔附件：C:/private/record.wav〕","answer":"[图片附件 · 仅原设备可用]","status":"complete"}]"""
        Room(turns).use { room ->
            ui.setContent {
                Box(Modifier.requiredSize(600.dp, 800.dp)) {
                    SharedChatPanel(SharedSeed("", "", "", emptyList()), room.storage, true) {}
                }
            }
            val availability = "共享记录仅含附件提示，无法在此打开文件。"
            ui.waitUntil(8_000) { ui.onAllNodesWithText(availability).fetchSemanticsNodes().size == 3 }
            for (hint in listOf("[语音附件]\n请听", "答〔图片〕", "〔附件〕", "[图片附件 · 仅原设备可用]")) {
                ui.onNodeWithText(hint).assertHasNoClickAction()
            }
            assertTrue(ui.onAllNodesWithText("C:/private", substring = true).fetchSemanticsNodes().isEmpty())
            assertTrue(room.posts.isEmpty())
        }
    }

    @Test fun scopedCiphertextRestoresAndDeletionOnlyRemovesThatLocalSession() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val file = files.root.toPath().resolve("history.json")
        val store = DesktopStore(file)
        val profile = store.createProfile("https://example.invalid/A")
        val key = SessionKey(profile.id, profile.currentSession)
        val storage = DesktopSharedStorage(store, key, profile.address)
        val draft = ComposerDraft("local unsent", listOf(files.root.toPath().resolve("saved.png")))
        store.saveDraft(key, store.nextDraftRevision(), draft)
        storage.save("fixture-secret")
        storage.save("fixture-invite")
        val data = Files.readString(file)
        assertFalse(data.contains("fixture-secret"))
        assertFalse(data.contains("fixture-invite"))
        val restored = DesktopStore(file)
        assertEquals("fixture-invite", DesktopSharedStorage(restored, key, profile.address).load())
        assertEquals(draft, restored.readDraft(key))
        assertNull(DesktopSharedStorage(restored, key, "https://example.invalid/a").load())
        val second = restored.createSession(profile.id)
        val other = DesktopSharedStorage(restored, SessionKey(profile.id, second.session), profile.address)
        assertNull(other.load())
        other.save("second-room")
        restored.deleteSession(profile.id, key.sessionId)
        assertNull(DesktopSharedStorage(restored, key, profile.address).load())
        assertEquals("second-room", other.load())
    }

    @Test fun boundSessionOpensSharedAndCanBrowsePreservedLocalDraft() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val dir = files.root.toPath()
        val store = DesktopStore(dir.resolve("history.json"))
        val profile = store.createProfile("http://127.0.0.1:1")
        val key = SessionKey(profile.id, profile.currentSession)
        store.saveDraft(key, store.nextDraftRevision(), ComposerDraft("native draft"))
        DesktopSharedStorage(store, key, profile.address).save(SharedLocal(revoked = true).encode())
        ui.setContent { DesktopScreen(store, NamedKeys(dir, DesktopCredentials(dir.resolve("credentials"))), ReplyImageCache(dir.resolve("images"))) }
        ui.onNodeWithText("此设备的访问权限已撤销").assertIsDisplayed()
        ui.onNodeWithTag("shared-chat-browse-local").performClick()
        ui.onNodeWithText("native draft").assertIsDisplayed()
        ui.onNodeWithTag("send-button").assertIsNotEnabled()
        ui.onNodeWithTag("shared-chat-open").performClick()
        ui.onNodeWithText("此设备的访问权限已撤销").assertIsDisplayed()
    }

    @Test fun firstRunCanOpenSharingWithoutANativeEndpoint() {
        val dir = files.root.toPath()
        val store = DesktopStore(dir.resolve("history.json"))
        ui.setContent { DesktopScreen(store, NamedKeys(dir, DesktopCredentials(dir.resolve("credentials"))), ReplyImageCache(dir.resolve("images"))) }
        ui.onNodeWithTag("shared-chat-open").performClick()
        ui.onNodeWithText("共享服务地址").assertIsDisplayed()
        assertEquals("shared", store.profile(store.activeProfileId)?.kind)
        assertEquals("", store.lastServer)
    }
}
