package dev.polarzen.chatbox

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopProfilePanelTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    private fun await(matcher: SemanticsMatcher) {
        rule.waitUntil(25_000) {
            rule.mainClock.advanceTimeBy(100)
            rule.onAllNodes(matcher).fetchSemanticsNodes().size == 1
        }
    }

    private fun click(matcher: SemanticsMatcher) {
        val enabled = matcher and hasClickAction() and isEnabled()
        await(enabled)
        rule.onNode(enabled).performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test fun pairFromConnectionSettingsAndContinueInOriginalComposer() {
        assumeTrue("Windows protects desktop device credentials with DPAPI", System.getProperty("os.name").startsWith("Windows"))
        rule.mainClock.autoAdvance = false
        val directory = files.root.toPath()
        val bot = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val source = "http://127.0.0.1:${bot.address.port}"
        val store = DesktopStore(directory.resolve("history.json"))
        val profileB = store.createProfile(source + "/other")
        store.saveSessionContent(profileB.id, Chat(profileB.currentSession, listOf(Entry("B", "另一个档案", "已完成"))))
        val profile = store.createProfile(source)
        val other = store.createSession(profile.id).session
        val sid = profile.currentSession
        store.selectSession(profile.id, sid)
        val oldEntry = Entry("配对前：海棠", "旧的完整答复", "已完成")
        store.saveSessionContent(profile.id, Chat(sid, listOf(oldEntry)))
        val keys = NamedKeys(directory, DesktopCredentials(directory.resolve("credentials")))
        keys.activate(profile.id, keys.add(profile.id, "测试密钥", "panel-test-key").id)
        val calls = CopyOnWriteArrayList<JSONObject>()
        bot.createContext("/api/v1/chat/sessions") { exchange -> exchange.use { ex ->
            val rows = listOf(sid, other).map { JSONObject().put("session_id", it)
                .put("creator", store.username).put("platform_id", "webchat") }
            val body = JSONObject().put("status", "ok").put("data", JSONObject().put("page", 1)
                .put("page_size", 100).put("total", rows.size).put("sessions", JSONArray(rows)))
                .toString().toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        } }
        bot.createContext("/api/v1/chat") { exchange -> exchange.use { ex ->
            val request = JSONObject(ex.requestBody.bufferedReader(Charsets.UTF_8).readText())
            calls += request
            val answer = "回答：" + request.getString("message")
            ex.responseHeaders.set("Content-Type", "text/event-stream")
            val body = listOf("plain" to answer, "end" to "").joinToString("") { (type, data) ->
                "data: " + JSONObject().put("type", type).put("data", data).put("streaming", false) + "\n\n"
            }.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        } }
        bot.start()
        try {
            withSharedService(directory, source) { address, code, offline ->
                val cache = ReplyImageCache(directory.resolve("reply-images"))
                val launch = mutableIntStateOf(0)
                rule.setContent { key(launch.intValue) {
                    val opened = remember { DesktopStore(directory.resolve("history.json")) }
                    MaterialTheme { DesktopScreen(opened, keys, cache) }
                } }
                fun switchTo(id: String) {
                    click(hasTestTag("connection-control-status"))
                    await(hasTestTag("connection-control-settings-dialog"))
                    val choice = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
                        hasAnyAncestor(hasTestTag("connection-control-settings-dialog"))
                    val index = store.profiles.indexOfFirst { it.id == id }
                    rule.onAllNodes(choice)[index].performSemanticsAction(SemanticsActions.OnClick)
                    click(hasText("关闭"))
                }
                await(hasText("测试密钥"))
                click(hasTestTag("connection-control-status"))
                click(hasTestTag("connection-control-verify"))
                click(hasTestTag("connection-profile-share"))
                await(hasText("此档案现有 2 段会话", substring = true))
                await(hasText("档案：$source"))
                rule.onNodeWithTag("profile-share-confirm").assertIsNotEnabled()
                rule.onNodeWithTag("profile-share-address").performTextReplacement(address)
                rule.onNodeWithTag("profile-share-bootstrap").performTextReplacement(code)
                click(hasTestTag("profile-share-confirm"))
                click(hasTestTag("profile-share-invite"))
                val inviteLabel = hasText("邀请码：", substring = true)
                await(inviteLabel)
                val invite = rule.onNode(inviteLabel).fetchSemanticsNode().config[SemanticsProperties.Text]
                    .single().text.substringAfter("邀请码：").substringBefore("（")
                val phone = SharedProfileTransport(allowLoopback = true)
                val binding = runBlocking { phone.join(address, invite) }
                click(hasText("关闭"))
                rule.waitUntil(25_000) {
                    rule.mainClock.advanceTimeBy(100)
                    runBlocking { phone.sessions(binding).complete }
                }
                assertEquals(setOf(sid, other), runBlocking { phone.sessions(binding).sessions.map { it.sid }.toSet() })
                assertEquals("旧的完整答复", runBlocking { phone.turns(binding, sid).turns.single().answer })
                assertTrue(calls.isEmpty())
                runBlocking { phone.send(binding, sid, UUID.randomUUID().toString(), "手机追问") }
                await(hasText("助手：回答：手机追问"))
                rule.onNodeWithTag("composer-field").performTextInput("电脑续聊")
                click(hasTestTag("send-button"))
                await(hasText("助手：回答：电脑续聊"))
                assertEquals(listOf("配对前：海棠", "手机追问", "电脑续聊"),
                    runBlocking { phone.turns(binding, sid).turns.map { it.question } })
                assertEquals(2, calls.size)
                assertTrue(calls.all { it.getString("username") == store.username && it.getString("session_id") == sid })
                assertEquals(oldEntry, DesktopStore(directory.resolve("history.json")).sessionContent(profile.id, sid).entries.first())
                rule.runOnIdle { launch.intValue++ }
                await(hasTestTag("composer-field"))
                await(hasText("助手：回答：电脑续聊"))
                offline {
                    if (rule.onAllNodes(hasTestTag("session-menu-$sid")).fetchSemanticsNodes().isEmpty()) {
                        click(hasTestTag("sidebar-toggle"))
                    }
                    click(hasTestTag("session-menu-$sid"))
                    click(hasTestTag("rename-session-$sid"))
                    rule.onNodeWithTag("rename-session-name").performTextReplacement("断线时更名")
                    click(hasTestTag("confirm-rename-session"))
                    if (rule.onAllNodes(hasTestTag("close-sidebar-button")).fetchSemanticsNodes().isNotEmpty()) {
                        click(hasTestTag("close-sidebar-button"))
                    }
                    switchTo(profileB.id)
                    await(hasText("助手：另一个档案"))
                }
                rule.waitUntil(25_000) {
                    rule.mainClock.advanceTimeBy(500)
                    runBlocking { phone.sessions(binding).sessions.first { it.sid == sid }.title == "断线时更名" }
                }
                assertEquals(profileB.id, DesktopStore(directory.resolve("history.json")).activeProfileId)
                switchTo(profile.id)
                await(hasText("助手：回答：电脑续聊"))
                val recoveryCode = runServiceCli(directory, "profile-recover", "--profile", binding.profile)
                rule.runOnIdle {
                    val damaged = DesktopStore(directory.resolve("history.json"))
                    damaged.saveProfileAuthorization(profile.id, damaged.profileAuthorization(profile.id), "invalid-test-record")
                    launch.intValue++
                }
                click(hasTestTag("shared-chat-open"))
                await(hasText("档案主人恢复码"))
                rule.onNodeWithTag("profile-share-address").performTextReplacement(address)
                rule.onNodeWithTag("profile-share-bootstrap").performTextReplacement(recoveryCode)
                rule.onNodeWithTag("profile-share-confirm").assertIsNotEnabled()
                click(hasText("关闭"))
                click(hasTestTag("connection-control-status"))
                click(hasTestTag("connection-control-verify"))
                await(hasText("连接已验证"))
                click(hasTestTag("connection-profile-share"))
                rule.onNodeWithTag("profile-share-address").performTextReplacement(address)
                rule.onNodeWithTag("profile-share-bootstrap").performTextReplacement(recoveryCode)
                click(hasText("恢复此档案主人授权"))
                click(hasText("撤销授权"))
                click(hasText("确认撤销"))
                rule.waitUntil(15_000) {
                    rule.mainClock.advanceTimeBy(100)
                    runBlocking { (runCatching { phone.sessions(binding) }.exceptionOrNull() as? SharedHttpFailure)?.status == 401 }
                }
            }
        } finally { bot.stop(0) }
    }

    private fun serviceCommand(directory: Path, vararg arguments: String): ProcessBuilder {
        val script = Path.of("../shared_service/service.py").toAbsolutePath().normalize()
        return ProcessBuilder(listOf("python", "-B", "-X", "utf8", script.toString(),
            "--db", directory.resolve("service.sqlite3").toString()) + arguments).redirectErrorStream(true).apply {
            environment()["ASTRBOT_CHAT_KEY"] = "panel-test-key"
        }
    }

    private fun runServiceCli(directory: Path, vararg arguments: String): String {
        val child = serviceCommand(directory, *arguments).start()
        try {
            check(child.waitFor(10, TimeUnit.SECONDS))
            val output = child.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            check(child.exitValue() == 0) { output }
            return output
        } finally { child.destroyForcibly() }
    }

    private fun withSharedService(directory: Path, upstream: String, test: (String, String, (() -> Unit) -> Unit) -> Unit) {
        runServiceCli(directory, "init", "--upstream", upstream)
        val code = runServiceCli(directory, "profile-bootstrap")
        val log = directory.resolve("service.log")
        fun start(port: String) = serviceCommand(directory, "serve", "--port", port).redirectOutput(log.toFile()).start()
        var server = start("0")
        fun awaitAddress(): String {
            val pattern = Regex("http://127[.]0[.]0[.]1:[0-9]+")
            rule.waitUntil(10_000) { check(server.isAlive); pattern.containsMatchIn(Files.readString(log)) }
            return pattern.find(Files.readString(log))!!.value
        }
        try {
            val address = awaitAddress()
            test(address, code) { action ->
                check(server.destroyForcibly().waitFor(5, TimeUnit.SECONDS))
                action()
                server = start(java.net.URI(address).port.toString())
                check(awaitAddress() == address)
            }
        } finally { server.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
    }
}
