package dev.polarzen.chatbox

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** Acceptance checks drive the production DesktopScreen with disposable local profiles. */
class DesktopUiAcceptanceTest {
    private val composeRule = createComposeRule()
    private val preflight = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val fixturePath = System.getenv("CHATBOX_UI_FIXTURE_CONFIG")
                assumeTrue("UI acceptance is opt-in; set CHATBOX_UI_FIXTURE_CONFIG", fixturePath != null && Files.isRegularFile(Path.of(fixturePath)))
                assumeTrue("UI acceptance requires Windows DPAPI", System.getProperty("os.name").startsWith("Windows", true))
                assumeTrue("UI acceptance requires a graphical host", !GraphicsEnvironment.isHeadless())
                assumeTrue("UI acceptance requires a fixture log", System.getenv("CHATBOX_UI_FIXTURE_LOG") != null)
                base.evaluate()
            }
        }
    }
    @get:Rule val acceptanceRules: TestRule = RuleChain.outerRule(preflight).around(composeRule)
    private val rule get() = composeRule

    private lateinit var fixture: Properties
    private lateinit var temp: Path
    private lateinit var dataDir: Path
    private lateinit var historyFile: Path
    private lateinit var credentials: DesktopCredentials
    private lateinit var store: DesktopStore
    private lateinit var keys: NamedKeys
    private lateinit var cache: ReplyImageCache
    private lateinit var profileA: String
    private lateinit var profileB: String
    private val screenEpoch = mutableIntStateOf(0)
    private var priorSocketFactory: javax.net.ssl.SSLSocketFactory? = null
    private var priorHostnameVerifier: javax.net.ssl.HostnameVerifier? = null
    private var fixtureLog: Path? = null
    private var fixtureLogOffset: Long = 0L

    @Before
    fun setup() {
        // The desktop test scene treats an animated text caret as continuous work; freeze the
        // test clock so semantics queries and interactions can reach an idle frame.
        rule.mainClock.autoAdvance = false
        assumeTrue("DPAPI UI acceptance requires Windows", System.getProperty("os.name").startsWith("Windows", true))
        assumeTrue("UI acceptance is opt-in; set CHATBOX_UI_FIXTURE_CONFIG", System.getenv("CHATBOX_UI_FIXTURE_CONFIG") != null)
        val configPath = System.getenv("CHATBOX_UI_FIXTURE_CONFIG")
            ?: throw IllegalStateException("Set CHATBOX_UI_FIXTURE_CONFIG with the private fixture properties path.")
        fixture = Properties().also { props -> Files.newBufferedReader(Path.of(configPath)).use(props::load) }
        fixtureLog = System.getenv("CHATBOX_UI_FIXTURE_LOG")?.let(Path::of)
        fixtureLogOffset = fixtureLog?.let { if (Files.exists(it)) Files.size(it) else 0L } ?: 0L
        installFixtureTrust()
        setRejectOriginal(false)
        temp = Files.createTempDirectory("chatbox-ui-acceptance-")
        dataDir = temp.resolve("Chatbox")
        Files.createDirectories(dataDir)
        historyFile = dataDir.resolve("history.json")
        credentials = DesktopCredentials(dataDir.resolve("credentials"))
        cache = ReplyImageCache(dataDir.resolve("reply-images"))
        profileA = "fixture-profile-a"
        profileB = "fixture-profile-b"
        screenEpoch.intValue = 0
    }

    @After
    fun cleanup() {
        if (::fixture.isInitialized) runCatching { setRejectOriginal(false) }
        priorSocketFactory?.let(HttpsURLConnection::setDefaultSSLSocketFactory)
        priorHostnameVerifier?.let(HttpsURLConnection::setDefaultHostnameVerifier)
        if (::temp.isInitialized) temp.toFile().deleteRecursively()
    }

    @Test
    fun acceptanceB_v015DpapiKeyRenamesAndSurvivesTwoStarts() {
        createV015Profiles(withLegacyKey = true)
        render()
        awaitConnected()
        val legacy = keys.list(profileA).keys.single()
        assertEquals("legacy", legacy.id)

        openManageKeys()
        rule.onNodeWithTag("connection-control-rename-legacy").performClick()
        advanceUiAnimations()
        rule.onNodeWithTag("connection-control-rename-name").performTextClearance()
        rule.onNodeWithTag("connection-control-rename-name").performTextInput("Migrated UI key")
        rule.onNodeWithTag("connection-control-confirm-rename").performClick()
        advanceUiAnimations()
        waitUntil("legacy key rename", 10_000) { keys.list(profileA).keys.singleOrNull()?.name == "Migrated UI key" }
        closeDialog()

        restartScreen()
        awaitConnected()
        assertEquals(listOf("Migrated UI key"), keys.list(profileA).keys.map { it.name })
        assertEquals("legacy", keys.list(profileA).activeId)
        assertEquals("已连接", connectedStatusText())

        restartScreen()
        awaitConnected()
        assertEquals(1, keys.list(profileA).keys.size)
        assertEquals("Migrated UI key", keys.list(profileA).keys.single().name)
        assertTrue("Existing encrypted credential remains usable without re-entry", fixtureRequestSeen("profile=A endpoint=GET /api/v1/chat/sessions key=old_key"))
        assertNoFixtureSecretsInSemantics()
        println("UI_CASE B PASS migrated=0.1.5-schema2 key=legacy renamed=1 starts=2 retyped=false")
    }

    @Test
    fun acceptanceC_namedKeySwitchFailureKeepsPriorBindingAndNextAuthAlias() {
        createV015Profiles(withLegacyKey = true)
        render()
        awaitConnected()
        val originalId = keys.list(profileA).activeId ?: error("Expected migrated active key")
        val secondSecret = fixture.getProperty("token.a.second")
        addKeyThroughUi("Second UI key", secondSecret)
        val secondId = keys.list(profileA).keys.single { it.name == "Second UI key" }.id
        waitUntil("second key verification", 15_000) { keys.list(profileA).activeId == secondId }

        val draft = "draft retained across key switches"
        rule.onNodeWithTag("composer-field").performTextInput(draft)
        val firstSwitchOffset = fixtureLogCurrentOffset()
        openKeyMenu()
        clickSemanticsTag("connection-control-key-$originalId", useUnmergedTree = true)
        waitUntil("successful switch back to original key", 15_000) {
            keys.list(profileA).activeId == originalId && connectedStatusText() == "已连接" &&
                fixtureRequestSeen("profile=A endpoint=GET /api/v1/chat/sessions key=old_key", firstSwitchOffset)
        }
        val secondSwitchOffset = fixtureLogCurrentOffset()
        openKeyMenu()
        clickSemanticsTag("connection-control-key-$secondId", useUnmergedTree = true)
        waitUntil("successful switch to second key", 15_000) {
            keys.list(profileA).activeId == secondId && connectedStatusText() == "已连接" &&
                fixtureRequestSeen("profile=A endpoint=GET /api/v1/chat/sessions key=second_key", secondSwitchOffset)
        }
        val draftMatcher = SemanticsMatcher("composer retains the draft") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag] == "composer-field" &&
                node.config.contains(SemanticsProperties.EditableText) &&
                node.config[SemanticsProperties.EditableText].text == draft
        }
        assertTrue("Draft text survives switching away and back", rule.onAllNodes(draftMatcher).fetchSemanticsNodes().isNotEmpty())
        val switchedRequestOffset = fixtureLogCurrentOffset()
        rule.onNodeWithTag("send-button").assertIsEnabled()
        clickSemanticsTag("send-button")
        waitUntil("outbound request after successful key switch", 15_000) {
            fixtureRequestSeen("profile=A endpoint=POST /api/v1/chat key=second_key", switchedRequestOffset)
        }
        waitUntil("successful switch response completes and unlocks composer", 20_000) {
            val session = store.profile(profileA)!!.currentSession
            store.sessionContent(profileA, session).entries.any { it.question == draft && it.status == "已完成" }
        }

        openSettings()
        clickSemanticsTag("connection-control-owner-permissions")
        advanceUiAnimations()
        rule.onNodeWithTag("connection-control-owner-permissions-dialog").assertIsDisplayed()
        rule.onNodeWithText("本机备注：Second UI key").assertIsDisplayed()
        closeDialog()
        closeDialog()

        // A single menu selection must reject the candidate while preserving the verified binding.
        val rejectedCandidateOffset = fixtureLogCurrentOffset()
        setRejectOriginal(true)
        openKeyMenu()
        clickSemanticsTag("connection-control-key-$originalId", useUnmergedTree = true)
        waitUntil("rejected candidate", 15_000) {
            keys.list(profileA).activeId == secondId && fixtureRequestSeen(
                "profile=A endpoint=GET /api/v1/chat/sessions key=old_key session=- result=401",
                rejectedCandidateOffset,
            )
        }
        assertEquals(secondId, keys.list(profileA).activeId)
        assertEquals("已连接", connectedStatusText())
        rule.onNodeWithTag("connection-control-key-menu").assertIsDisplayed()
        rule.onNodeWithText("聊天 API 密钥无效或已撤销。").assertIsDisplayed()
        val dismissMenuOffset = fixtureLogCurrentOffset()
        clickSemanticsTag("connection-control-key-$secondId", useUnmergedTree = true)
        waitUntil("active key row dismisses the failed candidate menu", 5_000) {
            rule.onAllNodes(SemanticsMatcher("key menu absent") { node ->
                node.config.contains(SemanticsProperties.TestTag) &&
                    node.config[SemanticsProperties.TestTag] == "connection-control-key-menu"
            }, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        assertFalse(
            "Dismissing the failed candidate does not reverify the active key",
            fixtureRequestSeen("profile=A endpoint=GET /api/v1/chat/sessions key=second_key", dismissMenuOffset),
        )

        val afterFailureOffset = fixtureLogCurrentOffset()
        sendText("post-failure synthetic request")
        waitUntil("send after candidate rejection", 15_000) {
            fixtureRequestSeen("profile=A endpoint=POST /api/v1/chat key=second_key", afterFailureOffset)
        }
        assertTrue("Rejected old key must never become active", keys.list(profileA).activeId == secondId)

        // The same visible controls add a distinct B credential and the next B request is scoped to B.
        selectProfile(1)
        closeSettingsIfOpen()
        addKeyThroughUi("B service key", fixture.getProperty("token.b.second"))
        val bId = keys.list(profileB).activeId
        assertTrue("B has its own active key", !bId.isNullOrBlank())
        sendText("synthetic B profile request")
        waitUntil("B scoped authorization", 15_000) {
            fixtureRequestSeen("profile=B endpoint=POST /api/v1/chat key=second_key")
        }
        assertEquals(listOf("B service key"), keys.list(profileB).keys.map { it.name })
        assertEquals("Second UI key", keys.list(profileA).keys.first { it.id == secondId }.name)
        println("UI_CASE C PASS profile=A successfulSwitch=true draftRetained=true failedCandidatePreserved=true nextAlias=second_key profile=B nextAlias=second_key")
    }

    @Test
    fun acceptanceD_profileKeyIsolationRemovalAndRestartSelection() {
        createV015Profiles(withLegacyKey = false)
        render()
        waitForKeyList(profileA)
        assertTrue(keys.list(profileA).keys.isEmpty())
        assertTrue(keys.list(profileB).keys.isEmpty())

        addKeyThroughUi("A-only key", fixture.getProperty("token.a.second"))
        val aKey = keys.list(profileA).keys.single()
        assertEquals(aKey.id, keys.list(profileA).activeId)
        selectProfile(1)
        waitForKeyList(profileB)
        assertTrue("B profile starts without A key", keys.list(profileB).keys.isEmpty())
        assertTrue(keys.list(profileA).keys.single().id == aKey.id)

        addKeyThroughUi("B-only key", fixture.getProperty("token.b.second"))
        val bKey = keys.list(profileB).keys.single()
        assertEquals(bKey.id, keys.list(profileB).activeId)
        openManageKeys()
        rule.onNodeWithTag("connection-control-remove-${bKey.id}").performClick()
        rule.onNodeWithTag("connection-control-confirm-remove-key").performClick()
        waitUntil("active key removal", 10_000) { keys.list(profileB).keys.isEmpty() && keys.list(profileB).activeId == null }
        assertTrue("Removed encrypted blob is deleted", credentials.loadNamed(profileB, bKey.id) == null)
        closeDialog()

        restartScreen()
        waitForKeyList(profileB)
        assertTrue(keys.list(profileB).keys.isEmpty())
        assertEquals(profileB, DesktopStore(historyFile).activeProfileId)
        selectProfile(0)
        waitForKeyList(profileA)
        assertEquals(listOf("A-only key"), keys.list(profileA).keys.map { it.name })
        assertEquals("B key must remain isolated after restart", 0, keys.list(profileB).keys.size)
        assertTrue(keys.list(profileB).activeId == null)
        println("UI_CASE D PASS profileSelectionPersisted=true AKeys=1 BKeys=0 removedActiveUnselected=true")
    }

    @Test
    fun acceptanceE_sessionsDelayedSendAndRealPngJpegPicker() {
        createV015Profiles(withLegacyKey = true)
        render()
        awaitConnected()
        val oldSession = fixture.getProperty("session.a")
        val newSessionButton = rule.onNodeWithTag("new-session-button")
        newSessionButton.performClick()
        val currentAfterNew = store.profile(profileA)!!.currentSession
        assertFalse("New session has a distinct server session UUID", currentAfterNew == oldSession)
        val question = "延迟 fixture response while switching session"
        rule.onNodeWithTag("composer-field").performTextInput(question)
        rule.onNodeWithTag("send-button").performClick()
        waitUntil("streamed partial response", 15_000) {
            runCatching {
                rule.onNodeWithText("本地服务已收到，正在继续", substring = true).assertIsDisplayed()
            }.isSuccess
        }
        val sendingIndex = store.sessions(profileA).indexOfFirst { it.id == currentAfterNew }
        assertTrue("Sending session should remain in the production sidebar", sendingIndex >= 0)
        rule.onNodeWithTag("session-item-$sendingIndex").performClick()
        rule.onNodeWithText("本地服务已收到，正在继续", substring = true).assertIsDisplayed()
        assertEquals("Reselecting the sending session keeps it visible", currentAfterNew, store.profile(profileA)!!.currentSession)
        val oldIndex = store.sessions(profileA).indexOfFirst { it.id == oldSession }
        rule.onNodeWithTag("session-item-$oldIndex").performClick()
        assertEquals("The reviewed session is selected again", oldSession, store.profile(profileA)!!.currentSession)
        rule.onNodeWithTag("session-item-$oldIndex").assertIsDisplayed()
        assertTrue("The selected session contains its original history", store.sessionContent(profileA, oldSession).entries.any {
            it.question == "合成的 A 历史问题"
        })
        assertTrue(
            "The old session does not show the in-flight reply",
            rule.onAllNodes(hasText("正在继续", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
        waitUntil("delayed response persisted in originating session", 20_000) {
            val ended = store.sessionContent(profileA, currentAfterNew).entries.lastOrNull()
            ended?.status == "已完成" && ended.answer.contains("处理并完成")
        }
        assertEquals(oldSession, store.profile(profileA)!!.currentSession)
        assertTrue("Delayed reply did not overwrite the reviewed session", store.sessionContent(profileA, oldSession).entries.none { it.question == question })
        val sentLine = "profile=A endpoint=POST /api/v1/chat key=old_key session=$currentAfterNew"
        waitUntil("server received the original session id", 10_000) { fixtureRequestSeen(sentLine) }
        waitUntil("assistant image cached", 15_000) {
            cache.read(profileA, "reply-image-a") != null
        }

        selectSession(currentAfterNew)
        val media = Path.of(fixture.getProperty("media.dir"))
        val png = media.resolve("upload-a.png")
        val jpeg = media.resolve("upload-b.jpg")
        assertTrue(Files.isRegularFile(png) && Files.isRegularFile(jpeg))
        chooseImageThroughRealPicker(png)
        rule.onNodeWithTag("remove-image-button").assertIsDisplayed()
        rule.onNodeWithTag("remove-image-button").performClick()
        assertNoTaggedNode("remove-image-button")
        chooseImageThroughRealPicker(jpeg)
        rule.onNodeWithTag("composer-field").performTextInput("synthetic JPEG caption")
        rule.onNodeWithTag("send-button").performClick()
        waitUntil("JPEG upload and chat accepted", 20_000) {
            fixtureRequestSeen("profile=A endpoint=POST /api/v1/file key=old_key") &&
                fixtureRequestSeen("profile=A endpoint=POST /api/v1/chat key=old_key") &&
                store.sessionContent(profileA, currentAfterNew).entries.any {
                    it.attachmentDisplayName == jpeg.fileName.toString() && it.status == "已完成"
                }
        }
        waitUntil("reply image persisted to its cache", 15_000) { cache.read(profileA, "reply-image-a") != null }
        val latest = store.sessionContent(profileA, currentAfterNew).entries.last()
        assertTrue("Response metadata remains attached to the originating conversation", latest.replyImages.any { it.attachmentId == "reply-image-a" })
        assertTrue("The request reached the HTTPS mock service with the fixture session id", fixtureRequestSeen("profile=A endpoint=POST /api/v1/chat key=old_key session=$currentAfterNew"))
        println("UI_CASE E PASS newSession=true switchedDuringSend=true correctSession=true pngPickedRemoved=true jpegUploaded=true replyImageCached=true")
    }

    private fun createV015Profiles(withLegacyKey: Boolean) {
        val aSession = fixture.getProperty("session.a")
        val bSession = fixture.getProperty("session.b")
        val now = System.currentTimeMillis()
        val a = profileJson(profileA, fixture.getProperty("base.a"), aSession,
            listOf(sessionJson(aSession, "合成的 A 历史问题", "合成的 A 历史回复", now)))
        val b = profileJson(profileB, fixture.getProperty("base.b"), bSession,
            listOf(sessionJson(bSession, "合成的 B 历史问题", "合成的 B 历史回复", now)))
        Files.writeString(historyFile, JSONObject()
            .put("schemaVersion", 2)
            .put("username", fixture.getProperty("username", "ui_fixture_" + UUID.randomUUID().toString().replace("-", "")))
            .put("activeProfileId", profileA)
            .put("profiles", JSONArray().put(a).put(b))
            .toString())
        if (withLegacyKey) credentials.save(profileA, fixture.getProperty("token.a.original"))
        store = DesktopStore(historyFile)
        keys = NamedKeys(dataDir, credentials)
    }

    private fun profileJson(id: String, address: String, currentSession: String, sessions: List<JSONObject>) = JSONObject()
        .put("id", id).put("address", address).put("kind", "manual").put("offlineOnly", false)
        .put("currentSession", currentSession)
        .put("sessions", JSONArray().also { rows -> sessions.forEach(rows::put) })

    private fun sessionJson(id: String, question: String, answer: String, lastUsedAt: Long) = JSONObject()
        .put("id", id).put("createdAt", lastUsedAt).put("lastUsedAt", lastUsedAt)
        .put("entries", JSONArray().put(JSONObject()
            .put("question", question).put("answer", answer).put("status", "已完成")))

    private fun render() {
        rule.setContent {
            key(screenEpoch.intValue) {
                MaterialTheme { DesktopScreen(store, keys, cache) }
            }
        }
    }

    private fun restartScreen() {
        rule.runOnIdle {
            store = DesktopStore(historyFile)
            credentials = DesktopCredentials(dataDir.resolve("credentials"))
            keys = NamedKeys(dataDir, credentials)
            screenEpoch.intValue++
        }
        rule.waitForIdle()
    }

    private fun installFixtureTrust() {
        priorSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        priorHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val store = java.security.KeyStore.getInstance("PKCS12")
        val password = fixture.getProperty("tls.trustPassword").toCharArray()
        Files.newInputStream(Path.of(fixture.getProperty("tls.trustStore"))).use { store.load(it, password) }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
    }

    private fun awaitConnected() {
        waitUntil("verified service connection", 20_000) { connectedStatusText() == "已连接" }
    }

    private fun connectedStatusText(): String = runCatching {
        rule.onNodeWithTag("connection-control-status").fetchSemanticsNode().config.toString()
    }.getOrDefault("").let { semantics -> if (semantics.contains("已连接")) "已连接" else "" }

    private fun waitForKeyList(profile: String) {
        waitUntil("key list loaded", 10_000) { runCatching { keys.list(profile) }.isSuccess }
    }

    private fun openSettings() {
        if (isDisplayed("connection-control-settings-dialog")) return
        if (isDisplayed("connection-control-key-management")) closeDialog()
        if (isDisplayed("connection-control-key-menu")) {
            val activeId = keys.list(store.activeProfileId).activeId
            if (activeId == null) rule.onNodeWithTag("connection-control-key-button").performClick()
            else clickSemanticsTag("connection-control-key-$activeId", useUnmergedTree = true)
            advanceUiAnimations()
        }
        waitUntil("connection settings status control visible", 5_000) { isDisplayed("connection-control-status") }
        clickSemanticsTag("connection-control-status")
        advanceUiAnimations()
        rule.onNodeWithTag("connection-control-settings-dialog").assertIsDisplayed()
    }

    private fun isDisplayed(tag: String): Boolean = runCatching {
        rule.onNodeWithTag(tag).assertIsDisplayed()
    }.isSuccess

    private fun closeSettingsIfOpen() {
        if (isDisplayed("connection-control-settings-dialog")) closeDialog()
    }

    private fun closeDialog() {
        // Nested Compose dialogs expose both dismiss buttons; the first node is the top dialog.
        val dismissButtons = rule.onAllNodes(hasText("关闭")).fetchSemanticsNodes()
        assertTrue("A visible dialog should expose a close action", dismissButtons.isNotEmpty())
        rule.onAllNodes(hasText("关闭")).get(0).performSemanticsAction(SemanticsActions.OnClick)
        advanceUiAnimations()
    }

    private fun openManageKeys() {
        openKeyMenu()
        clickSemanticsTag("connection-control-manage-keys", useUnmergedTree = true)
        advanceUiAnimations()
        rule.onNodeWithTag("connection-control-key-management").assertIsDisplayed()
    }

    private fun addKeyThroughUi(name: String, secret: String) {
        openKeyMenu()
        if (keys.list(store.activeProfileId).keys.isEmpty()) {
            rule.onNodeWithTag("connection-control-add-key", useUnmergedTree = true).performClick()
        } else {
            clickSemanticsTag("connection-control-manage-keys", useUnmergedTree = true)
        }
        advanceUiAnimations()
        rule.onNodeWithTag("connection-control-key-management").assertIsDisplayed()
        rule.onNodeWithTag("connection-control-add-name").performTextInput(name)
        rule.onNodeWithTag("connection-control-add-secret").performTextInput(secret)
        rule.onNodeWithTag("connection-control-add-submit").performClick()
        waitUntil("key saved and verified", 20_000) {
            runCatching {
                keys.list(store.activeProfileId).let { list ->
                    list.keys.firstOrNull { it.name == name }?.let { list.activeId == it.id } == true
                }
            }.getOrDefault(false)
        }
        assertNoFixtureSecretsInSemantics()
        closeDialog()
    }

    private fun selectProfile(index: Int) {
        openSettings()
        val radioNodes = rule.onAllNodes(roleMatcher(Role.RadioButton)).fetchSemanticsNodes()
        assertTrue("Settings should expose the saved profile radio controls", radioNodes.size > index)
        rule.onAllNodes(roleMatcher(Role.RadioButton)).get(index).performClick()
        advanceUiAnimations()
        val expectedProfile = store.profiles[index].id
        closeDialog()
        waitUntil("profile selection", 10_000) { DesktopStore(historyFile).activeProfileId == expectedProfile }
    }

    private fun selectSession(sessionId: String) {
        val index = store.sessions(profileA).indexOfFirst { it.id == sessionId }
        assertTrue("Session should be present in the production sidebar", index >= 0)
        rule.onNodeWithTag("session-item-$index").performClick()
    }

    private fun roleMatcher(role: Role) = SemanticsMatcher("Role = $role") { node ->
        node.config.contains(SemanticsProperties.Role) && node.config[SemanticsProperties.Role] == role
    }

    private fun sendText(value: String) {
        rule.onNodeWithTag("composer-field").performTextInput(value)
        rule.onNodeWithTag("send-button").assertIsEnabled()
        clickSemanticsTag("send-button")
    }

    private fun chooseImageThroughRealPicker(path: Path) {
        val automationFailure = AtomicReference<Throwable?>()
        val automation = Thread({
            try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            val chooserRef = AtomicReference<JFileChooser?>()
            while (System.nanoTime() < deadline && chooserRef.get() == null) {
                SwingUtilities.invokeAndWait { chooserRef.set(findVisibleChooser()) }
                if (chooserRef.get() == null) Thread.sleep(50)
            }
            val visible = chooserRef.get() ?: error("Swing image picker did not open")
            SwingUtilities.invokeAndWait {
                check(visible.fileFilter.accept(path.toFile())) { "Fixture file does not match the real picker filter" }
                visible.selectedFile = path.toFile()
                val approve = findComponent(visible) { it is JButton && it.actionCommand == JFileChooser.APPROVE_SELECTION } as? JButton
                if (approve != null) approve.doClick() else visible.approveSelection()
            }
            } catch (error: Throwable) {
                automationFailure.set(error)
            }
        }, "chatbox-ui-file-picker").apply { isDaemon = true; start() }
        rule.onNodeWithTag("choose-image-button").performClick()
        automation.join(15_000)
        assertFalse("File picker automation should finish", automation.isAlive)
        assertTrue("Real file picker should accept the synthetic image", automationFailure.get() == null)
        rule.waitForIdle()
        rule.onNodeWithText(path.fileName.toString()).assertIsDisplayed()
    }

    private fun findVisibleChooser(): JFileChooser? = Window.getWindows()
        .filter { it.isShowing }
        .asSequence()
        .mapNotNull { findComponent(it) { child -> child is JFileChooser } as? JFileChooser }
        .firstOrNull()

    private fun findComponent(root: Component, predicate: (Component) -> Boolean): Component? {
        if (predicate(root)) return root
        if (root is Container) root.components.forEach { child -> findComponent(child, predicate)?.let { return it } }
        return null
    }

    private fun setRejectOriginal(reject: Boolean) {
        val control = Properties().apply { setProperty("reject.original", reject.toString()) }
        Files.newOutputStream(Path.of(fixture.getProperty("controls.file"))).use { control.store(it, null) }
    }

    private fun fixtureRequestSeen(fragment: String, afterOffset: Long = fixtureLogOffset): Boolean {
        val log = fixtureLog ?: return false
        if (!Files.exists(log)) return false
        val offset = afterOffset.coerceAtMost(Files.size(log))
        val current = Files.newInputStream(log).use { input ->
            input.skipNBytes(offset)
            input.readAllBytes()
        }
        return current.toString(Charsets.UTF_8).lineSequence().any { it.contains(fragment) }
    }

    private fun fixtureLogCurrentOffset(): Long = fixtureLog?.let(Files::size) ?: 0L

    private fun waitUntil(description: String, timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(25)
        }
        assertTrue("Timed out waiting for $description", runCatching(condition).getOrDefault(false))
    }

    private fun assertNoTaggedNode(tag: String) {
        val matcher = SemanticsMatcher("Test tag = $tag") { node ->
            node.config.contains(SemanticsProperties.TestTag) && node.config[SemanticsProperties.TestTag] == tag
        }
        assertTrue("No node with test tag $tag should remain", rule.onAllNodes(matcher).fetchSemanticsNodes().isEmpty())
    }

    private fun openKeyMenu() {
        clickSemanticsTag("connection-control-key-button")
        advanceUiAnimations()
        waitUntil("key dropdown visible", 5_000) {
            rule.onAllNodes(SemanticsMatcher("Test tag = connection-control-key-menu") { node ->
                node.config.contains(SemanticsProperties.TestTag) &&
                    node.config[SemanticsProperties.TestTag] == "connection-control-key-menu"
            }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickSemanticsTag(tag: String, useUnmergedTree: Boolean = false) {
        rule.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree)
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun advanceUiAnimations() {
        rule.mainClock.advanceTimeBy(500)
    }

    private fun assertNoFixtureSecretsInSemantics() {
        val leaked = listOf("token.a.original", "token.a.second", "token.b.second", "token.invalid").any { property ->
            rule.onAllNodes(hasText(fixture.getProperty(property), substring = false), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse("Synthetic credentials must not appear as visible UI text", leaked)
    }
}
