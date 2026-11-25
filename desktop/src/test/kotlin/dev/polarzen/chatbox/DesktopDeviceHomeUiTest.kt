package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopDeviceHomeUiTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val folder = TemporaryFolder()
    private val dir get() = folder.root.toPath()

    private fun frame() = ui.mainClock.advanceTimeBy(220)

    @Test fun selectsOtherProfileFromRecentAndCreatesThere() {
        val store = DesktopStore(dir.resolve("history.json"))
        val first = store.createProfile("https://one.example")
        store.saveSessionContent(first.id, Chat(first.currentSession, listOf(Entry("甲的提问", "甲的回答", "已完成"))))
        store.renameSession(first.id, first.currentSession, "测试甲")
        val second = store.createProfile("https://two.example")
        store.saveSessionContent(second.id, Chat(second.currentSession, listOf(Entry("乙的提问", "乙的回答", "已完成"))))
        store.renameSession(second.id, second.currentSession, "测试乙")
        val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("keys")))
        val images = ReplyImageCache(dir.resolve("images"))
        ui.mainClock.autoAdvance = false
        ui.setContent { Box(Modifier.requiredSize(1000.dp, 700.dp)) { MaterialTheme { DesktopScreen(store, keys, images) } } }
        frame()
        ui.onNodeWithTag("sidebar-toggle").performClick()
        frame()
        ui.onNodeWithTag("pin-sidebar").performClick()
        frame()
        ui.onNodeWithText("最近对话").assertIsDisplayed()
        ui.onNodeWithText("测试甲").performClick()
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            store.activeProfileId == first.id && ui.onAllNodesWithText("甲的回答", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("甲的回答", substring = true).assertIsDisplayed()
        assertEquals(first.id, store.activeProfileId)
        ui.onNodeWithTag("new-session-button").performClick()
        frame()
        assertEquals(1, store.sessions(first.id).size)
        ui.onNodeWithTag("confirm-new-conversation").performClick()
        frame()
        assertEquals(first.id, store.activeProfileId)
        assertEquals(2, store.sessions(first.id).size)
        assertEquals(1, store.sessions(second.id).size)
    }

    @Test fun phoneSourceHeaderTracksTheAuthenticatedSharedService() {
        val store = DesktopStore(dir.resolve("history.json"))
        val owner = UUID.randomUUID().toString()
        val instance = UUID.randomUUID().toString()
        store.saveDeviceOwner(instance, owner)
        val origin = UUID.randomUUID().toString()
        val phone = DeviceProfile("phone-profile", "original", "https://phone.example", "phone-user", "手机档案", origin, "upstream", true)
        val source = store.cacheDeviceProfile(phone)
        store.createSession(source)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/device/") { exchange -> exchange.use { ex ->
            val result = when (ex.requestURI.path.substringAfterLast('/')) {
                "profiles" -> JSONObject().put("profiles", listOf(JSONObject().put("profile", phone.profile)
                    .put("sourceId", phone.sourceId).put("base", phone.base).put("username", phone.username).put("name", phone.name)
                    .put("originDevice", phone.originDevice).put("upstream", phone.upstream).put("available", phone.available)))
                "sessions" -> JSONObject().put("sessions", emptyList<String>()).put("more", false).put("revision", "1")
                "devices" -> JSONObject().put("devices", emptyList<String>())
                else -> JSONObject()
            }
            val bytes = result.toString().toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(if (ex.requestHeaders.getFirst("Authorization") == "Bearer header-test") 200 else 401, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } }
        server.start()
        try {
            val online = AtomicBoolean(true)
            val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("keys")))
            val sync = DesktopDeviceSync(store, keys) {
                if (online.get()) JSONObject().put("ok", true).put("device", owner).put("instance", instance).put("token", "header-test")
                    .put("localUrl", "http://127.0.0.1:" + server.address.port)
                else JSONObject().put("ok", false).put("error", "service_offline")
            }
            runBlocking { sync.refresh() }
            assertEquals("已同步设备会话。", sync.notice)
            ui.mainClock.autoAdvance = false
            ui.setContent { Box(Modifier.requiredSize(1000.dp, 700.dp)) { MaterialTheme {
                DesktopScreen(store, keys, ReplyImageCache(dir.resolve("images")), deviceSync = sync)
            } } }
            fun assertHeader(text: String) {
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    ui.onAllNodesWithContentDescription("设备同步状态：" + text, substring = true).fetchSemanticsNodes().size == 1
                }
            }
            ui.onNodeWithTag("rail-settings").performClick()
            frame()
            assertHeader("已同步设备会话")
            online.set(false)
            runBlocking { sync.refresh() }
            assertHeader("共享服务离线")
        } finally { server.stop(0) }
    }

    @Test fun freshSelectionSkipsKeyAccessAndReportsRealStorageFailureAfterSelection() {
        val store = DesktopStore(dir.resolve("history.json"))
        val invalidDirectory = Files.writeString(dir.resolve("metadata-file"), "test")
        val keys = NamedKeys(invalidDirectory, DesktopCredentials(dir.resolve("keys")))
        ui.mainClock.autoAdvance = false
        ui.setContent {
            val connection = rememberDesktopConnection(store, keys)
            Column {
                Text(connection.error ?: "尚无密钥错误")
                TextButton(onClick = { connection.useAddress("https://selected.example") }) { Text("选择测试来源") }
            }
        }
        frame()
        ui.onNodeWithText("尚无密钥错误").assertIsDisplayed()
        ui.onNodeWithText("选择测试来源").performClick()
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithText("无法读取已保存的密钥。").fetchSemanticsNodes().size == 1
        }
    }

    @Test fun provenRoomKeepsOneRecentRowAndExposesItsOriginalWithoutSending() {
        val store = DesktopStore(dir.resolve("history.json"))
        val owner = UUID.randomUUID().toString()
        store.saveDeviceOwner(UUID.randomUUID().toString(), owner)
        val remote = DeviceSession("remote-profile", "shared-authority", "同一会话", 10L, 1, 1, "", "owner")
        fun bind(address: String, divergent: Boolean): SessionKey {
            val p = store.createProfile(address)
            val key = SessionKey(p.id, p.currentSession)
            val chat = Chat(key.sessionId, listOf(Entry("原始提问", "合并前回答", "已完成")))
            store.saveSessionContent(p.id, chat)
            store.cacheDeviceProfile(DeviceProfile(p.id, p.id, p.address, store.username, "本机", owner, "route", true), p.id)
            store.prepareDeviceHistory(key, p.id)
            store.bindProvenRoom(key, p.id, remote, chat, aligned = !divergent)
            return key
        }
        val first = bind("https://one.example", true)
        val second = bind("https://two.example", false)
        assertEquals(listOf(first), desktopRecentRows(store, first, emptyList()).map { it.key })
        assertEquals(listOf(second), desktopRecentRows(store, second, emptyList()).map { it.key })
        val images = ReplyImageCache(dir.resolve("images"))
        val audio = ReplyAudioCache(dir.resolve("audio"))
        ui.mainClock.autoAdvance = false
        ui.setContent { MaterialTheme {
            DesktopDeviceCopies(store, first, 1L, images, audio) { error("Viewing an original should preserve the selected draft") }
        } }
        frame()
        ui.onNodeWithText("档案副本").performClick()
        frame()
        ui.onNodeWithText("查看此档案的本机原文").performClick()
        frame()
        ui.onNodeWithText("合并前回答", substring = true).assertIsDisplayed()
    }

    @Test fun enableShowsProgressAndNgrokRecoveryBesideControlsWithExpandedForm() {
        val store = DesktopStore(dir.resolve("history.json"))
        val profile = store.createProfile("https://astrbot.example")
        val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("keys")))
        val finish = CountDownLatch(1)
        val sync = DesktopDeviceSync(store, keys) { request ->
            check(request.getString("action") == "configure")
            check(finish.await(20, TimeUnit.SECONDS))
            JSONObject().put("ok", false).put("error", "ngrok_api_unavailable")
        }
        val connection = DesktopConnection(profile.id, profile.address, listOf(profile), emptyList(), KeyList(emptyList(), null),
            connected = true, verifying = false, pendingKeyId = null, error = null, keyErrors = emptyMap(), activeLabel = null,
            selectProfileAction = { _, _ -> }, adoptSelectedProfileAction = {}, useAddressAction = {}, verifyAction = {},
            selectKeyAction = {}, addKeyAction = { _, _ -> }, renameKeyAction = { _, _ -> }, removeKeyAction = {},
            snapshotAction = { DesktopSendTarget(profile.id, profile.address, "test", false, 1) {} }, invalidateAction = {})
        ui.setContent { MaterialTheme { DesktopDeviceWizard(store, connection, sync) {} } }
        frame()
        ui.onNodeWithText("下一步").performSemanticsAction(SemanticsActions.OnClick)
        frame()
        ui.onNodeWithText("部署配置与 ngrok 授权").performSemanticsAction(SemanticsActions.OnClick)
        frame()
        ui.onNode(isOff()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        frame()
        ui.onNodeWithText("AstrBot · " + profile.address).performScrollTo()
        val enable = ui.onNodeWithText("开启共享并生成配对码")
        try {
            enable.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
            frame()
            ui.onNodeWithText("正在配置共享服务并检查手机入口…").assertIsDisplayed()
            enable.assertIsDisplayed()
        } finally { finish.countDown() }
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithText("4040", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("4040", substring = true).assertIsDisplayed()
        enable.assertIsDisplayed()
        assertFalse(sync.enabled)
    }

    @Test fun firstLaunchCanStayLocalAndReopenSetupWithoutEnablingSharing() {
        val store = DesktopStore(dir.resolve("history.json"))
        val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("keys")))
        val images = ReplyImageCache(dir.resolve("images"))
        val sync = DesktopDeviceSync(store, keys) { error("A local-only setup should not start or configure the host") }
        var skipped = false
        ui.mainClock.autoAdvance = false
        ui.setContent {
            Box(Modifier.requiredSize(1000.dp, 700.dp)) { MaterialTheme {
                DesktopScreen(store, keys, images, deviceSync = sync, firstDeviceSetup = true, onDeviceSetupSeen = { skipped = true })
            } }
        }
        frame()
        ui.onNodeWithTag("device-setup-dialog").assertIsDisplayed()
        ui.onNodeWithText("仅在本机使用").performSemanticsAction(SemanticsActions.OnClick)
        frame()
        ui.runOnIdle { assertTrue(skipped); assertFalse(sync.enabled); assertNull(store.deviceOwner()) }
        ui.onNodeWithTag("rail-settings").performClick()
        frame()
        ui.onNodeWithTag("connection-control-status").performClick()
        frame()
        ui.onNodeWithTag("device-setup-open").performClick()
        frame()
        ui.onNodeWithTag("device-setup-dialog").assertIsDisplayed()
        assertFalse(sync.enabled)
    }
}
