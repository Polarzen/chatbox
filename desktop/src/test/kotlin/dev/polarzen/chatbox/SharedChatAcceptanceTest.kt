package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val firstQuestion = "本轮代号是青松47"
private const val followUp = "我刚才给的代号是什么？"
private const val prefix = "已记下"
private const val firstAnswer = "已记下本轮代号。"
private const val contextAnswer = "你刚才给的代号是青松47。"

/** Every network hop uses loopback, and every device and service uses disposable storage. */
class SharedChatAcceptanceTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    private fun withBridge(bot: Bot, block: (base: String, bootstrap: String) -> Unit) {
        val script = Path.of("../shared_service/service.py").toAbsolutePath().normalize()
        check(Files.isRegularFile(script)) { "Cannot locate the shared service at $script" }
        val database = files.root.toPath().resolve("bridge.sqlite3")
        fun command(vararg args: String) = ProcessBuilder(
            listOf(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
                "-B", "-X", "utf8", script.toString(), "--db", database.toString()) + args
        ).redirectErrorStream(true).apply {
            environment()["ASTRBOT_CHAT_KEY"] = "isolated-ui-fixture"
        }
        fun run(vararg args: String): String {
            val process = command(*args).start()
            try {
                check(process.waitFor(10, TimeUnit.SECONDS)) { "Bridge CLI timed out" }
                val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
                check(process.exitValue() == 0) { output }
                return output
            } finally {
                process.destroyForcibly()
            }
        }
        run("init", "--upstream", bot.base)
        val bootstrap = run("bootstrap")
        val log = files.newFile("bridge.log").toPath()
        val process = command("serve", "--port", "0").redirectOutput(log.toFile()).start()
        try {
            val address = Regex("http://127[.]0[.]0[.]1:[0-9]+")
            rule.waitUntil(10_000) {
                check(process.isAlive) { Files.readString(log) }
                address.containsMatchIn(Files.readString(log))
            }
            block(address.find(Files.readString(log))!!.value, bootstrap)
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    private class DeviceStore : SharedLocalStorage {
        @Volatile private var record: String? = null
        override fun load() = record
        override fun save(value: String?) { record = value }
    }

    private fun seat(name: String, matcher: SemanticsMatcher) =
        matcher and hasAnyAncestor(hasTestTag(name))

    private fun count(name: String, matcher: SemanticsMatcher): Int =
        rule.onAllNodes(seat(name, matcher)).fetchSemanticsNodes().size

    private fun await(description: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(15_000) {
                rule.mainClock.advanceTimeBy(80)
                condition()
            }
        } catch (e: AssertionError) {
            throw AssertionError("Timed out waiting for $description", e)
        }
    }

    private fun click(name: String, matcher: SemanticsMatcher) {
        await("enabled control $matcher on $name") { count(name, matcher and isEnabled()) == 1 }
        rule.onNode(seat(name, matcher)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    private fun enter(name: String, label: String, value: String) {
        val field = hasText(label) and hasSetTextAction()
        await("field $label on $name") { count(name, field and isEnabled()) == 1 }
        rule.onNode(seat(name, field)).performTextReplacement(value)
        rule.mainClock.advanceTimeByFrame()
    }

    private fun send(name: String, question: String) {
        enter(name, "消息", question)
        click(name, hasText("发送"))
    }

    private fun popupClick(label: String) {
        val control = hasText(label) and hasClickAction() and isEnabled()
        await(label) { rule.onAllNodes(control).fetchSemanticsNodes().size == 1 }
        rule.onNode(control).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    private fun waitText(seat: String, text: String, expected: Int = 1) =
        await("$expected copies of '$text' on $seat") { count(seat, hasText(text)) == expected }

    @Test
    fun desktopEntryImportsActiveSessionAndReopensOnlyItsOwnBinding() {
        assumeTrue("Desktop device credentials require Windows DPAPI", System.getProperty("os.name").startsWith("Windows"))
        rule.mainClock.autoAdvance = false
        Bot().use { bot -> withBridge(bot) { bridge, bootstrap ->
            val seat = "desktop"
            val directory = files.root.toPath()
            val history = directory.resolve("history.json")
            val credentials = DesktopCredentials(directory.resolve("credentials"))
            val keys = NamedKeys(directory, credentials)
            val store = DesktopStore(history)
            val otherProfile = store.createProfile(bot.base + "/other")
            val profile = store.createProfile(bot.base)
            keys.activate(profile.id, keys.add(profile.id, "验收", "isolated-ui-fixture").id)
            store.saveSessionContent(profile.id, Chat(profile.currentSession,
                listOf(Entry("旧图说明", "旧回复", "已完成", attachmentDisplayName = "fixture.png"))))
            val localQuestion = "共享前的本地提问"
            store.saveDraft(SessionKey(profile.id, profile.currentSession), store.nextDraftRevision(),
                ComposerDraft(localQuestion))
            val generation = mutableIntStateOf(0)
            rule.setContent {
                key(generation.intValue) {
                    val opened = remember { DesktopStore(history) }
                    val cache = remember { ReplyImageCache(directory.resolve("reply-images")) }
                    Box(Modifier.testTag(seat)) {
                        MaterialTheme { DesktopScreen(opened, keys, cache) }
                    }
                }
            }
            waitText(seat, "验收")
            click(seat, hasTestTag("connection-control-status"))
            popupClick("验证连接")
            rule.waitUntil(20_000) {
                rule.onAllNodes(hasText("连接已验证")).fetchSemanticsNodes().isNotEmpty()
            }
            popupClick("关闭")
            rule.onNodeWithTag("send-button").assertIsEnabled().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick)
            await("completed ordinary desktop turn") {
                DesktopStore(history).sessionContent(profile.id, profile.currentSession)
                    .entries.lastOrNull()?.let { it.question == localQuestion && it.status == "已完成" } == true
            }
            val oldEntries = DesktopStore(history).sessionContent(profile.id, profile.currentSession).entries
            click(seat, hasTestTag("shared-chat-open"))
            waitText(seat, "在另一台设备继续")
            waitText(seat, "加入已有会话")
            assertEquals(1, count(seat, hasText("当前可见的 2 轮记录", substring = true)))
            enter(seat, "共享服务地址", bridge)
            enter(seat, "创建码", bootstrap)
            click(seat, hasText("导入并创建共享会话"))
            waitText(seat, "[图片附件]\n旧图说明")
            waitText(seat, "答复：$localQuestion")
            assertEquals(1, bot.calls.size)

            rule.runOnIdle { generation.intValue++ }
            waitText(seat, "已同步")
            waitText(seat, "旧回复")
            assertEquals(0, count(seat, hasTestTag("composer-field")))
            val sharedQuestion = "桌面重开后发言"
            send(seat, sharedQuestion)
            waitText(seat, "答复：$sharedQuestion")
            assertEquals(listOf(localQuestion, sharedQuestion), bot.calls.map { it.getString("message") })
            assertTrue(bot.calls.all { it.getString("session_id") == profile.currentSession &&
                it.getString("username") == store.username })

            // A fresh launch with another active profile must neither display nor resume this grant.
            DesktopStore(history).selectProfile(otherProfile.id)
            rule.runOnIdle { generation.intValue++ }
            await("other profile in ordinary local mode") { count(seat, hasTestTag("composer-field")) == 1 }
            assertEquals(0, count(seat, hasText(sharedQuestion)))
            click(seat, hasTestTag("shared-chat-open"))
            waitText(seat, "导入并创建共享会话")
            DesktopStore(history).selectProfile(profile.id)
            rule.runOnIdle { generation.intValue++ }
            waitText(seat, "答复：$sharedQuestion")
            click(seat, hasText("更多"))
            popupClick("退出本机共享")
            popupClick("移除本机绑定")
            await("back to local composer") { count(seat, hasTestTag("composer-field")) == 1 }
            assertEquals(oldEntries, DesktopStore(history).sessionContent(profile.id, profile.currentSession).entries)
            assertEquals(2, bot.calls.size)
        } }
    }

    @Test
    fun twoDevicesStreamRecallAndReconcileAcrossLostResponseOfflineAndReopen() {
        rule.mainClock.autoAdvance = false
        Bot().use { bot -> withBridge(bot) { bridge, bootstrap -> GuestNetwork(bridge).use { guestNetwork ->
            val owner = "owner"
            val guest = "guest"
            val ownerStore = DeviceStore()
            val guestStore = DeviceStore()
            val generation = mutableIntStateOf(0)
            val ownerSeed = SharedSeed(bot.base, "qa-owner", "local-qa-session", emptyList())
            val guestSeed = SharedSeed("", "", "", emptyList())
            rule.setContent {
                MaterialTheme {
                    Row(Modifier.requiredSize(1100.dp, 1000.dp)) {
                        key(generation.intValue) {
                            Box(Modifier.weight(1f).fillMaxHeight().testTag(owner)) {
                                SharedChatPanel(ownerSeed, ownerStore, true) {}
                            }
                            Box(Modifier.weight(1f).fillMaxHeight().testTag(guest)) {
                                SharedChatPanel(guestSeed, guestStore, true) {}
                            }
                        }
                    }
                }
            }
            enter(owner, "共享服务地址", bridge)
            enter(owner, "创建码", bootstrap)
            click(owner, hasText("导入并创建共享会话"))
            waitText(owner, "已同步")
            send(owner, firstQuestion)
            waitText(owner, prefix)
            waitText(owner, "回复生成中…")
            bot.release.countDown()
            waitText(owner, firstAnswer)

            click(owner, hasText("邀请"))
            popupClick("生成一次性加入码")
            val codeNode = SemanticsMatcher("visible pairing code") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .any { it.text.matches(Regex("[a-zA-Z0-9_-]{16}")) }
            }
            await("visible pairing code") { rule.onAllNodes(codeNode).fetchSemanticsNodes().size == 1 }
            val code = rule.onNode(codeNode).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            popupClick("完成")
            click(guest, hasText("加入已有会话"))
            enter(guest, "共享服务地址", guestNetwork.base)
            enter(guest, "一次性加入码", code)
            click(guest, hasText("加入会话"))
            waitText(guest, firstQuestion)
            waitText(guest, firstAnswer)

            guestNetwork.loseSendResponse.set(true)
            send(guest, followUp)
            for (device in listOf(owner, guest)) {
                waitText(device, followUp)
                waitText(device, contextAnswer)
            }
            assertEquals(1, guestNetwork.sendIds.size)
            guestNetwork.offline.set(true)
            await("guest detects loss of network") { count(guest, hasText("已同步")) == 0 }
            val repeated = "相同文本"
            for (i in 1..2) {
                send(owner, repeated)
                waitText(owner, "答复：$repeated", i)
            }
            assertEquals(0, count(guest, hasText(repeated)))
            guestNetwork.offline.set(false)
            waitText(guest, "答复：$repeated", 2)
            // Destroy both panels together. New state must authenticate and fetch exclusively from saved bindings.
            rule.runOnIdle { generation.intValue++ }
            for (device in listOf(owner, guest)) {
                waitText(device, "答复：$repeated", 2)
                click(device, hasText("更多"))
                popupClick("刷新记录")
                waitText(device, "已同步")
                val questions = listOf(firstQuestion, followUp, repeated)
                val ordered = questions.flatMap { question ->
                    rule.onAllNodes(seat(device, hasText(question))).fetchSemanticsNodes().map {
                        it.positionInRoot.y to question
                    }
                }.sortedBy { it.first }.map { it.second }
                assertEquals(listOf(firstQuestion, followUp, repeated, repeated), ordered)
            }
            assertEquals(listOf(firstQuestion, followUp, repeated, repeated), bot.calls.map { it.getString("message") })
            assertTrue(bot.calls.all { it.getString("username") == ownerSeed.username &&
                it.getString("session_id") == ownerSeed.session })

            val recovered = "按原编号重试的提问"
            guestNetwork.loseSendRequest.set(true)
            send(guest, recovered)
            waitText(guest, "按原编号重试")
            rule.runOnIdle { generation.intValue++ }
            waitText(guest, "按原编号重试")
            assertEquals(4, bot.calls.size)
            assertEquals(2, guestNetwork.sendIds.size)
            click(guest, hasText("按原编号重试"))
            waitText(owner, "答复：$recovered")
            waitText(guest, "答复：$recovered")
            assertEquals(3, guestNetwork.sendIds.size)
            assertEquals(guestNetwork.sendIds[1], guestNetwork.sendIds[2])
            assertEquals(1, bot.calls.count { it.getString("message") == recovered })

            click(owner, hasText("更多"))
            popupClick("撤销其他设备")
            popupClick("撤销访问")
            waitText(guest, "此设备的访问权限已撤销")
            assertEquals(0, count(guest, hasText(contextAnswer)))
            assertEquals(0, count(guest, hasText("发送")))
        } } }
    }

    private class Bot : AutoCloseable {
        val calls = CopyOnWriteArrayList<JSONObject>()
        val release = CountDownLatch(1)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/api/v1/chat/sessions") { ex ->
                ex.use {
                    val username = URLDecoder.decode(it.requestURI.rawQuery.split("&")
                        .single { field -> field.startsWith("username=") }.substringAfter("="), Charsets.UTF_8)
                    val rows = calls.filter { call -> call.getString("username") == username }
                        .map { call -> call.getString("session_id") }.distinct().map { id ->
                            JSONObject().put("session_id", id).put("creator", username).put("platform_id", "webchat")
                        }
                    val data = JSONObject().put("status", "ok").put("data", JSONObject().put("page", 1)
                        .put("page_size", 100).put("total", rows.size).put("sessions", JSONArray(rows)))
                        .toString().toByteArray(Charsets.UTF_8)
                    it.sendResponseHeaders(200, data.size.toLong())
                    it.responseBody.write(data)
                }
            }
            server.createContext("/api/v1/chat") { ex ->
                ex.use {
                    val request = JSONObject(it.requestBody.bufferedReader(Charsets.UTF_8).readText())
                    val question = request.getString("message")
                    calls += request
                    it.responseHeaders.add("Content-Type", "text/event-stream")
                    it.sendResponseHeaders(200, 0)
                    val answer = when (question) {
                        firstQuestion -> {
                            event(it, "plain", prefix, true)
                            if (!release.await(30, TimeUnit.SECONDS)) return@use
                            firstAnswer
                        }
                        followUp -> if (calls.any { prior ->
                                prior.getString("username") == request.getString("username") &&
                                    prior.getString("session_id") == request.getString("session_id") &&
                                    prior.getString("message") == firstQuestion
                            }) contextAnswer else "没有收到代号"
                        else -> "答复：$question"
                    }
                    event(it, "plain", answer)
                    event(it, "end", "")
                }
            }
            server.start()
        }

        private fun event(ex: HttpExchange, type: String, data: String, streaming: Boolean = false) {
            val frame = JSONObject().put("type", type).put("data", data).put("streaming", streaming)
            ex.responseBody.write("data: $frame\n\n".toByteArray(Charsets.UTF_8))
            ex.responseBody.flush()
        }

        override fun close() {
            release.countDown()
            server.stop(0)
        }
    }

    /** Interrupt only the guest's network. The bridge always owns the real turn and request ID. */
    private class GuestNetwork(private val bridge: String) : AutoCloseable {
        val offline = AtomicBoolean()
        val loseSendRequest = AtomicBoolean()
        val loseSendResponse = AtomicBoolean()
        val sendIds = CopyOnWriteArrayList<String>()
        private val workers = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = workers
            server.createContext("/") { exchange -> exchange.use { forward(it) } }
            server.start()
        }

        private fun forward(ex: HttpExchange) {
            if (offline.get()) {
                ex.sendResponseHeaders(503, -1)
                return
            }
            val con = URI(bridge + ex.requestURI).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                con.requestMethod = ex.requestMethod
                con.connectTimeout = 3000
                con.readTimeout = 5000
                ex.requestHeaders.getFirst("Authorization")?.let { con.setRequestProperty("Authorization", it) }
                if (ex.requestMethod == "POST") {
                    con.doOutput = true
                    con.setRequestProperty("Content-Type", "application/json")
                    val data = ex.requestBody.readBytes()
                    if (ex.requestURI.path == "/v1/send") {
                        sendIds += JSONObject(data.toString(Charsets.UTF_8)).getString("id")
                        if (loseSendRequest.getAndSet(false)) return
                    }
                    con.setFixedLengthStreamingMode(data.size)
                    con.outputStream.use { it.write(data) }
                }
                val code = con.responseCode
                val response = (if (code < 400) con.inputStream else con.errorStream)?.use { it.readBytes() } ?: byteArrayOf()
                if (ex.requestURI.path == "/v1/send" && loseSendResponse.getAndSet(false)) return
                ex.sendResponseHeaders(code, response.size.toLong())
                ex.responseBody.write(response)
            } finally {
                con.disconnect()
            }
        }

        override fun close() {
            server.stop(0)
            workers.shutdownNow()
        }
    }
}
