package dev.polarzen.chatbox

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopPairingRecoveryTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    @Before fun controlFrames() {
        ui.mainClock.autoAdvance = false
    }

    private fun connection(profile: Profile) = DesktopConnection(profile.id, profile.address, listOf(profile), emptyList(), KeyList(emptyList(), null),
        connected = true, verifying = false, pendingKeyId = null, error = null, keyErrors = emptyMap(), activeLabel = null,
        selectProfileAction = { _, _ -> }, adoptSelectedProfileAction = {}, useAddressAction = {}, verifyAction = {},
        selectKeyAction = {}, addKeyAction = { _, _ -> }, renameKeyAction = { _, _ -> }, removeKeyAction = {},
        snapshotAction = { DesktopSendTarget(profile.id, profile.address, "fixture-upstream", false, 1) {} }, invalidateAction = {})

    private fun waitFor(label: String) = ui.waitUntil(10_000) {
        ui.mainClock.advanceTimeByFrame()
        ui.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun click(label: String) {
        ui.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick)
        ui.mainClock.advanceTimeByFrame()
    }

    @Test fun firstSetupKeepsTheEnteredTokenWhenTheProgramIsMissing() {
        val dir = folder.root.toPath()
        val store = DesktopStore(dir.resolve("history.json"))
        val connection = connection(store.createProfile("https://astrbot.example"))
        val phone = "https://first.example"
        val attempts = AtomicInteger()
        val sync = DesktopDeviceSync(store, NamedKeys(dir, DesktopCredentials(dir.resolve("keys"))),
            configuredSettings = { DesktopDeviceRuntime.configuredSettings(dir) }) { request ->
            check(request.getString("action") == "configure")
            check(request.optString("ngrokToken") == "fixture-token" && request.optString("phoneUrl") == phone)
            val error = if (attempts.incrementAndGet() == 1) "ngrok_missing" else "ngrok_auth_required"
            JSONObject().put("ok", false).put("error", error)
        }
        ui.setContent { MaterialTheme { DesktopDeviceWizard(store, connection, sync) {} } }
        click("下一步")
        ui.onNodeWithText("已有 ngrok HTTPS 域名（可选）").performTextReplacement(phone)
        click("部署配置与 ngrok 授权")
        ui.onNodeWithText("ngrok authtoken（已授权可留空）").performTextReplacement("fixture-token")
        click("收起部署配置")
        ui.onNode(isOff()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        click("开启共享并生成配对码")
        waitFor("找不到 ngrok.exe")
        ui.onNodeWithText("ngrok.exe 完整路径（可选）").assertIsFocused().performTextReplacement("C:/fixture/ngrok.exe")
        click("返回")
        waitFor("找到 AstrBot")
        click("下一步")
        click("开启共享并生成配对码")
        waitFor("Your Authtoken")
        ui.onNodeWithText("ngrok authtoken（已授权可留空）").assertIsFocused()
        assertEquals(2, attempts.get())
        assertNull(sync.invite)
        assertFalse(sync.enabled)
    }

    @Test fun coldStartWithAnOwnerKeepsSavedOptionsWhenOnlyTheNgrokTokenIsEdited() = retrySavedOptions(withOwner = true)

    @Test fun coldStartAfterAnUnfinishedSetupKeepsOptionsWithoutGrantingConsent() = retrySavedOptions(withOwner = false)

    private fun retrySavedOptions(withOwner: Boolean) {
        val dir = folder.root.toPath()
        val store = DesktopStore(dir.resolve("history.json"))
        val connection = connection(store.createProfile("https://astrbot.example"))
        if (withOwner) store.saveDeviceOwner(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val saved = JSONObject().put("mode", "ngrok").put("phoneUrl", "https://chosen.example")
            .put("ngrokPath", "C:/custom/ngrok.exe").put("gatewayPort", 19141)
            .put("dbPath", dir.resolve("custom.sqlite3").toString()).put("autostart", withOwner)
        Files.writeString(dir.resolve("config.json"), saved.toString())
        val attempts = AtomicInteger()
        val firstStatus = CountDownLatch(1)
        val sync = DesktopDeviceSync(store, NamedKeys(dir, DesktopCredentials(dir.resolve("keys"))),
            configuredSettings = { DesktopDeviceRuntime.configuredSettings(dir) }) { request ->
            when (request.getString("action")) {
                "status" -> JSONObject().put("ok", false).put("error", "ngrok_auth_required").also { firstStatus.countDown() }
                "configure" -> {
                    check(saved.keySet().all { request.opt(it) == saved.get(it) })
                    check(request.optString("ngrokToken") == "fixture-token")
                    attempts.incrementAndGet()
                    JSONObject().put("ok", false).put("error", "ngrok_missing")
                }
                else -> error("Unexpected runtime action")
            }
        }
        ui.setContent { MaterialTheme { DesktopDeviceWizard(store, connection, sync) {} } }
        if (withOwner) {
            assertTrue(firstStatus.await(5, TimeUnit.SECONDS))
            click("生成配对码")
            waitFor("选择手机连接方式")
        } else {
            click("下一步")
            ui.onNodeWithText("开启共享并生成配对码").assertIsNotEnabled()
            ui.onAllNodes(isOff()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            click("部署配置与 ngrok 授权")
        }
        val token = ui.onNodeWithText("ngrok authtoken（已授权可留空）")
        assertTrue(token.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty())
        token.performTextReplacement("fixture-token")
        ui.mainClock.advanceTimeByFrame()
        click("开启共享并生成配对码")
        waitFor("找不到 ngrok.exe")
        assertEquals(1, attempts.get())
    }

    @Test fun retriesTheSelectedEndpointAndOnlyDisplaysTheInviteFromTheSuccessfulAttempt() {
        val dir = folder.root.toPath()
        val store = DesktopStore(dir.resolve("history.json"))
        val profile = store.createProfile("https://astrbot.example")
        val owner = UUID.randomUUID().toString()
        val instance = UUID.randomUUID().toString()
        store.saveDeviceOwner(instance, owner)
        val phone = "https://phone.example"
        val program = "C:/fixture/ngrok.exe"
        val code = UUID.randomUUID().toString().take(8)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val invites = AtomicInteger()
        server.createContext("/v1/device/") { exchange -> exchange.use { ex ->
            val result = when (ex.requestURI.path.substringAfterLast('/')) {
                "profiles" -> JSONObject().put("profiles", emptyList<String>())
                "profile" -> JSONObject(ex.requestBody.bufferedReader().readText()).put("profile", profile.id)
                    .put("originDevice", owner).put("available", false)
                "sessions" -> JSONObject().put("sessions", emptyList<String>()).put("more", false).put("revision", "1")
                "devices" -> JSONObject().put("devices", emptyList<String>())
                "invite" -> {
                    invites.incrementAndGet()
                    JSONObject().put("code", code).put("expiresIn", 300)
                }
                else -> error("Unexpected device route")
            }
            val bytes = result.toString().toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } }
        server.start()
        try {
            val healthy = JSONObject().put("ok", true).put("mode", "ngrok").put("phoneUrl", phone)
                .put("localUrl", "http://127.0.0.1:" + server.address.port).put("instance", instance)
                .put("device", owner).put("token", "fixture-owner")
            val missing = AtomicBoolean(false)
            val statusChecks = AtomicInteger()
            val configureAttempts = AtomicInteger()
            val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("keys")))
            val sync = DesktopDeviceSync(store, keys, configuredSettings = { DesktopDeviceRuntime.configuredSettings(dir) }) { request ->
                when (request.getString("action")) {
                    "status" -> {
                        statusChecks.incrementAndGet()
                        if (missing.getAndSet(false)) JSONObject().put("ok", false).put("error", "ngrok_missing")
                        else healthy
                    }
                    "configure" -> {
                        val attempt = configureAttempts.incrementAndGet()
                        // The host published a dynamic phone URL. Leaving the domain field empty must keep it dynamic.
                        check(request.getString("phoneUrl").isEmpty() && request.optString("ngrokPath") == program)
                        check(request.optInt("gatewayPort") == 19123)
                        when (attempt) {
                            1 -> JSONObject().put("ok", false).put("error", "ngrok_auth_required")
                            2 -> {
                                check(request.optString("ngrokToken") == "fixture-token")
                                healthy
                            }
                            else -> error("Unexpected configuration attempt")
                        }
                    }
                    else -> error("Unexpected runtime action")
                }
            }
            val connection = connection(profile)
            ui.setContent { MaterialTheme { DesktopDeviceWizard(store, connection, sync) {} } }
            waitFor("入口已通过电脑的 HTTPS 检查。")
            missing.set(true)
            click("生成配对码")
            waitFor("找不到 ngrok.exe")
            val pathField = ui.onNodeWithText("ngrok.exe 完整路径（可选）")
            pathField.assertIsFocused().performTextReplacement(program)
            ui.onNodeWithText("本机受限网关端口").performTextReplacement("19123")
            val retry = "开启共享并生成配对码"
            val previousChecks = statusChecks.get()
            click(retry)
            waitFor("Your Authtoken")
            assertEquals(previousChecks, statusChecks.get())
            assertTrue(sync.enabled)
            assertEquals(0, invites.get())
            ui.onAllNodesWithText("一次性短码", substring = true).assertCountEquals(0)
            ui.onNodeWithText("ngrok authtoken（已授权可留空）").assertIsFocused().performTextReplacement("fixture-token")
            click(retry)
            waitFor("一次性短码 · " + code)
            ui.onNodeWithContentDescription("手机设备配对二维码").assertExists()
            assertEquals(1, invites.get())
            assertEquals(2, configureAttempts.get())
        } finally {
            server.stop(0)
        }
    }
}
