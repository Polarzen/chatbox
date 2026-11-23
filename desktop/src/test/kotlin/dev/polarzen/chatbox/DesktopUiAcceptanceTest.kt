package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
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
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
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

/** Acceptance checks drive the production desktop UI with synthetic sessions and disposable profiles. */
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
        resetFixtureControls()
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
        if (::fixture.isInitialized) runCatching { resetFixtureControls() }
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

    @OptIn(ExperimentalTestApi::class)
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
        advanceUiAnimations()
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
        assertTrue("Response metadata remains attached to the originating conversation", latest.replyAttachments.any { it.attachmentId == "reply-image-a" })
        assertTrue("The request reached the HTTPS mock service with the fixture session id", fixtureRequestSeen("profile=A endpoint=POST /api/v1/chat key=old_key session=$currentAfterNew"))

        val multiSelectOffset = fixtureLogCurrentOffset()
        chooseImagesThroughRealPicker(listOf(png, jpeg))
        assertComposerContainsText(png.fileName.toString())
        assertComposerContainsText(jpeg.fileName.toString())
        rule.onNodeWithTag("remove-image-button").assertIsDisplayed()
        rule.onNodeWithTag("remove-image-button-1").assertIsDisplayed()
        val multiCaption = "保留 JPEG 的说明"
        val secondLine = "第二行来自 Shift+Enter"
        val expectedQuestion = "$multiCaption\n$secondLine"
        rule.onNodeWithTag("composer-field").performTextInput(multiCaption)
        rule.onNodeWithTag("remove-image-button").performClick()
        advanceUiAnimations()
        assertNoComposerText(png.fileName.toString(), "Removing the PNG removes only its preview")
        assertComposerContainsText(jpeg.fileName.toString())
        rule.onNodeWithTag("remove-image-button").assertIsDisplayed()
        assertNoTaggedNode("remove-image-button-1")
        assertEquals("Removing a preview preserves the existing caption draft", multiCaption, composerFieldText())
        rule.onNodeWithTag("composer-field").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("composer-field").performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) }
        }
        rule.onNodeWithTag("composer-field").performTextInput(secondLine)
        assertEquals("Shift+Enter inserts a newline and keeps the composer draft", expectedQuestion, composerFieldText())
        val fileRequest = "profile=A endpoint=POST /api/v1/file key=old_key session=- result=200"
        val chatRequest = "profile=A endpoint=POST /api/v1/chat key=old_key session=$currentAfterNew"
        assertEquals("Shift+Enter inserts a newline without uploading or sending", 0, fixtureRequestCount(fileRequest, multiSelectOffset) + fixtureRequestCount(chatRequest, multiSelectOffset))
        assertTrue("Shift+Enter does not create a message entry", store.sessionContent(profileA, currentAfterNew).entries.none { it.question == expectedQuestion })

        rule.onNodeWithTag("composer-field").performKeyInput { pressKey(Key.Enter) }
        waitUntil("remaining JPEG and multiline caption are sent", 20_000) {
            store.sessionContent(profileA, currentAfterNew).entries.lastOrNull()?.let { entry ->
                entry.question == expectedQuestion && entry.status == "已完成" &&
                    entry.attachmentDisplayName == jpeg.fileName.toString() &&
                    entry.attachmentReferences == listOf(jpeg.toString())
            } == true && fixtureRequestSeen(fileRequest, multiSelectOffset) && fixtureRequestSeen(chatRequest, multiSelectOffset)
        }
        val multiEntry = store.sessionContent(profileA, currentAfterNew).entries.last()
        assertEquals("Only the retained JPEG path is recorded on the sent message", listOf(jpeg.toString()), multiEntry.attachmentReferences)
        assertEquals("The removed PNG is not recorded as an attachment", jpeg.fileName.toString(), multiEntry.attachmentDisplayName)
        assertEquals("Exactly one file upload reached the fixture", 1, fixtureRequestCount(fileRequest, multiSelectOffset))
        assertEquals("Exactly one chat request reached the fixture", 1, fixtureRequestCount(chatRequest, multiSelectOffset))
        assertEquals("The composer clears after ordinary Enter sends", "", composerFieldText())

        restartScreen()
        val reopened = store.sessionContent(profileA, currentAfterNew).entries.last()
        assertEquals("Multiline question persists after reopening", expectedQuestion, reopened.question)
        assertEquals("Completed status persists after reopening", "已完成", reopened.status)
        assertEquals("Only the JPEG attachment persists after reopening", listOf(jpeg.toString()), reopened.attachmentReferences)
        assertEquals("JPEG display name persists after reopening", jpeg.fileName.toString(), reopened.attachmentDisplayName)
        println("UI_CASE E PASS newSession=true switchedDuringSend=true correctSession=true pngPickedRemoved=true jpegUploaded=true replyImageCached=true multiSelect=true removeOnePreservedDraft=true shiftEnterNoSend=true enterSentOneRetainedImage=true fixtureFilePosts=" + fixtureRequestCount(fileRequest, multiSelectOffset) + " fixtureChatPosts=" + fixtureRequestCount(chatRequest, multiSelectOffset) + " savedAttachmentName=" + multiEntry.attachmentDisplayName + " historyReloaded=true")
    }

    @Test
    fun sidebarScrollbarVisibilityFollowsContentAndViewportHeight() {
        val sessions = mutableStateOf(emptyList<SessionInfo>())
        val height = mutableIntStateOf(440)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(900.dp, height.intValue.dp)) {
                    DesktopChatLayout(
                        sessions = sessions.value, chat = Chat(""),
                        onSelectSession = {}, onNewSession = {}, canCreate = true, topActions = {},
                        draft = TextFieldValue(), onDraft = {}, selectedImage = null,
                        onChooseImage = {}, onRemoveImage = {}, onSend = {}, canSend = false,
                        notice = null, onRetry = {}, canRetry = false, assistantContent = {},
                    )
                }
            }
        }
        fun assertControlsAbsent() {
            for (tag in listOf("session-scrollbar", "session-scroll-thumb", "session-scroll-up", "session-scroll-down")) {
                rule.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
            }
        }
        rule.onNodeWithText("还没有对话").assertIsDisplayed()
        assertControlsAbsent()
        val history = List(20) { index ->
            SessionInfo("session-$index", 1, index == 0, "一段会换行的合成会话标题".repeat(3))
        }
        rule.runOnIdle { sessions.value = history.take(3) }
        advanceUiAnimations()
        rule.onNodeWithTag("session-item-2").assertIsDisplayed()
        assertControlsAbsent()
        rule.runOnIdle { sessions.value = history }
        advanceUiAnimations()
        rule.onNodeWithTag("session-scroll-thumb").assertIsDisplayed()
        rule.runOnIdle { sessions.value = history.take(3) }
        advanceUiAnimations()
        rule.onNodeWithTag("session-item-2").assertIsDisplayed()
        assertControlsAbsent()
        rule.runOnIdle { height.intValue = 180 }
        advanceUiAnimations()
        rule.onNodeWithTag("session-scroll-thumb").assertIsDisplayed()
        rule.runOnIdle { height.intValue = 440 }
        advanceUiAnimations()
        rule.onNodeWithTag("session-item-2").assertIsDisplayed()
        assertControlsAbsent()
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun acceptanceF_scrolledDuplicateSessionsKeepDraftsAndDeleteInFlightHistory() {
        createV015Profiles(withLegacyKey = true, extraASessions = 44)
        render()
        awaitConnected()

        val duplicateSessions = store.sessions(profileA).filter { it.title == "重复标题" }
        assertEquals("Long history contains duplicate visible titles", 44, duplicateSessions.size)
        val scrolledSession = duplicateSessions[35].id
        val otherSession = duplicateSessions[12].id
        val scrolledIndex = store.sessions(profileA).indexOfFirst { it.id == scrolledSession }
        val orderBeforeSelection = store.sessions(profileA)
        val selectedBeforeDrag = store.profile(profileA)!!.currentSession
        assertEquals(0, visibleSessionIndices().first())
        rule.onNodeWithTag("session-scroll-thumb").performMouseInput {
            // The grab point is outside even the 6 dp hovered thumb, inside its wider hit area.
            val x = centerX - 5.dp.toPx()
            moveTo(Offset(x, 8.dp.toPx()))
            press()
            moveTo(Offset(x, centerY))
            release()
        }
        advanceUiAnimations()
        assertTrue("Dragging beside the painted thumb scrolls the long list", visibleSessionIndices().first() > 0)
        assertEquals("Scrolling preserves the active conversation", selectedBeforeDrag, store.profile(profileA)!!.currentSession)
        scrollToSessionRow(scrolledIndex)
        val visibleBeforeSelection = visibleSessionIndices()
        assertTrue("The requested duplicate row is actually in the sidebar viewport", scrolledIndex in visibleBeforeSelection)
        assertEquals("The displayed middle row is the intended session", scrolledSession, orderBeforeSelection[scrolledIndex].id)
        val clickedRowBounds = rule.onNodeWithTag("session-item-$scrolledIndex").fetchSemanticsNode().boundsInRoot
        assertTrue("The middle row has visible screen bounds", clickedRowBounds.width > 0f && clickedRowBounds.height > 0f)
        rule.onNodeWithTag("session-item-$scrolledIndex").performClick()
        assertEquals(scrolledSession, store.profile(profileA)!!.currentSession)
        val targetReply = store.sessionContent(profileA, scrolledSession).entries.single().answer
        waitUntil("the scrolled duplicate's unique history is displayed", 5_000) {
            runCatching { rule.onNodeWithText(targetReply, substring = true).assertIsDisplayed() }.isSuccess
        }
        val visibleAfterSelection = visibleSessionIndices()
        assertEquals("Selecting a duplicate title keeps the same sidebar viewport", visibleBeforeSelection, visibleAfterSelection)

        val scrolledDraft = "draft on older duplicate"
        rule.onNodeWithTag("composer-field").performTextInput(scrolledDraft)
        selectSession(otherSession)
        val otherDraft = "draft on another duplicate"
        rule.onNodeWithTag("composer-field").performTextInput(otherDraft)
        selectSession(scrolledSession)
        assertComposerText(scrolledDraft)
        selectSession(otherSession)
        assertComposerText(otherDraft)

        selectSession(scrolledSession)
        rule.onNodeWithTag("composer-field").performTextClearance()
        val delayedQuestion = "延迟 delete this in-flight local conversation"
        val sendOffset = fixtureLogCurrentOffset()
        val visibleBeforeBackgroundReply = visibleSessionIndices()
        sendText(delayedQuestion)
        waitUntil("delayed reply starts in selected session", 10_000) {
            runCatching { rule.onNodeWithText("本地服务已收到，正在继续", substring = true).assertIsDisplayed() }.isSuccess
        }
        assertEquals(scrolledSession, store.profile(profileA)!!.currentSession)
        assertEquals("Sidebar viewport remains stable during the background reply", visibleBeforeBackgroundReply, visibleSessionIndices())
        assertTrue("The clicked row originated below the start of the long list", scrolledIndex > 0)
        assertEquals("Selecting a row preserves duplicate-title sidebar order", orderBeforeSelection.map { it.id }, store.sessions(profileA).map { it.id })

        val orderAtDeletion = store.sessions(profileA).map { it.id }
        val deleteIndex = orderAtDeletion.indexOf(scrolledSession)
        assertTrue("The in-flight session remains in sidebar order until deletion", deleteIndex >= 0)
        val expectedReplacement = orderAtDeletion.getOrNull(deleteIndex + 1)
            ?: orderAtDeletion.getOrNull(deleteIndex - 1)
            ?: error("Deleting the only session should create a replacement session")

        clickSemanticsTag("session-menu-$scrolledSession")
        advanceUiAnimations()
        waitUntil("session delete menu is displayed", 5_000) {
            runCatching { rule.onNodeWithText("删除会话", substring = false).assertIsDisplayed() }.isSuccess
        }
        rule.onNodeWithText("删除会话", substring = false).performClick()
        advanceUiAnimations()
        waitUntil("session delete confirmation is displayed", 5_000) { isDisplayed("confirm-delete-session") }
        rule.onNodeWithTag("confirm-delete-session").performClick()
        waitUntil("in-flight session removed from local history", 10_000) {
            store.sessions(profileA).none { it.id == scrolledSession }
        }
        waitUntil("late callback settles after deletion", 20_000) {
            fixtureRequestSeen("profile=A endpoint=POST /api/v1/chat key=old_key session=$scrolledSession", sendOffset)
        }
        assertTrue("Late send callbacks must not recreate the deleted history row", store.sessions(profileA).none { it.id == scrolledSession })
        assertEquals("Deleting the selected session selects its adjacent row", expectedReplacement, store.profile(profileA)!!.currentSession)

        assertTrue("Other duplicate-title history remains intact", store.sessionContent(profileA, otherSession).entries.any {
            it.question == "重复标题"
        })
        restartScreen()
        val reloaded = DesktopStore(historyFile)
        assertTrue("Deletion persists after reopening the store", reloaded.sessions(profileA).none { it.id == scrolledSession })
        assertEquals("The replacement selection persists after reopening", expectedReplacement, reloaded.profile(profileA)!!.currentSession)
        assertTrue("Unrelated conversation history persists after reopening", reloaded
            .sessionContent(profileA, otherSession).entries.any { it.question == "重复标题" })
        assertTrue("The original conversation history persists after reopening", reloaded
            .sessionContent(profileA, fixture.getProperty("session.a")).entries.any { it.question == "合成的 A 历史问题" })
        println("UI_CASE F PASS duplicateTitles=44 scrolledSelection=true perSessionDrafts=true deletedDuringSend=true noResurrection=true remainingHistoryPersisted=true")
    }

    @Test
    fun acceptanceG_sendPhasesTruncatedStreamAndUploadFailureRemainRecoverable() {
        createV015Profiles(withLegacyKey = true)
        render()
        awaitConnected()
        setFixtureControl("chat.delay.before.headers.ms", "4000")
        setFixtureControl("chat.delay.before.first.event.ms", "4000")

        val headersQuestion = "fixture:delay-before-headers wait before headers"
        sendText(headersQuestion)
        assertSendingWithoutAnswer(headersQuestion, "等待服务器响应")
        waitUntil("pre-header delayed response completes", 15_000) { entryForCurrentSession(headersQuestion)?.status == "已完成" }

        val firstEventQuestion = "fixture:delay-before-first-event wait before first event"
        sendText(firstEventQuestion)
        assertSendingWithoutAnswer(firstEventQuestion, "等待首段回复")
        waitUntil("pre-event delayed response completes", 15_000) { entryForCurrentSession(firstEventQuestion)?.status == "已完成" }

        val truncatedQuestion = "fixture:truncate-sse return a partial stream"
        sendText(truncatedQuestion)
        waitUntil("truncated response is persisted as a failure", 15_000) {
            entryForCurrentSession(truncatedQuestion)?.let {
                it.status.startsWith("发送失败：") && it.answer.contains("回复流中断前的部分内容")
            } == true
        }
        rule.onNodeWithText("回复流中断", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("composer-field").performTextInput("ready after truncated response")
        rule.onNodeWithTag("send-button").assertIsEnabled()
        rule.onNodeWithTag("composer-field").performTextClearance()

        setFixtureControl("upload.fail", "true")
        val fixtureOffset = fixtureLogCurrentOffset()
        chooseImageThroughRealPicker(Path.of(fixture.getProperty("media.dir")).resolve("upload-a.png"))
        rule.onNodeWithTag("composer-field").performTextInput("caption for a failing fixture upload")
        rule.onNodeWithTag("send-button").performClick()
        waitUntil("upload failure is persisted without a chat request", 20_000) {
            entryForCurrentSession("caption for a failing fixture upload")?.status?.startsWith("发送失败：") == true &&
                fixtureRequestSeen("profile=A endpoint=POST /api/v1/file key=old_key session=- result=503", fixtureOffset)
        }
        val uploadEntryIndex = store.sessionContent(profileA, store.profile(profileA)!!.currentSession)
            .entries.indexOfLast { it.question == "caption for a failing fixture upload" }
        assertTrue("Failed upload should remain in the current message history", uploadEntryIndex >= 0)
        val uploadFailureBubble = hasText("上传图片 1/1 失败", substring = true) and
            hasAnyAncestor(hasTestTag("message-assistant-$uploadEntryIndex"))
        waitUntil("upload failure stage appears in the failed message bubble", 5_000) {
            runCatching { rule.onNode(uploadFailureBubble).assertIsDisplayed() }.isSuccess
        }
        assertFalse("A rejected upload must not start a chat request", fixtureRequestSeen(
            "profile=A endpoint=POST /api/v1/chat key=old_key", fixtureOffset,
        ))
        rule.onNodeWithTag("composer-field").performTextInput("ready after upload failure")
        rule.onNodeWithTag("send-button").assertIsEnabled()
        setFixtureControl("upload.fail", "false")
        println("UI_CASE G PASS beforeHeaders=true beforeFirstEvent=true truncatedSse=failedAndPersisted upload503=reported chatNotSent=true composerRecovered=true")
    }

    @Test
    fun voiceReplyFromChatShowsPlaybackInOrderAfterRestart() {
        createV015Profiles(withLegacyKey = true)
        render()
        awaitConnected()
        rule.onNodeWithTag("new-session-button").performClick()
        val question = "fixture:voice-reply"
        sendText(question)
        waitUntil("completed voice reply", 15_000) { entryForCurrentSession(question)?.status == "已完成" }

        val play = rule.onNodeWithText("播放语音")
        fun assertReplyOrder() {
            waitUntil("play control between the two reply text blocks", 10_000) {
                rule.mainClock.advanceTimeByFrame()
                val before = rule.onNodeWithText("助手：语音前文").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val button = play.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
                val after = rule.onNodeWithText("语音后文").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                before.center.y < button.center.y && button.center.y < after.center.y
            }
        }
        assertReplyOrder()
        assertTrue("Voice download should use the active profile key",
            fixtureRequestSeen("profile=A endpoint=GET /api/v1/file key=old_key session=- result=200"))
        restartScreen()
        assertReplyOrder()

        val verifyPlayback = System.getenv("CHATBOX_UI_VERIFY_AUDIO_PLAYBACK") == "1"
        if (verifyPlayback) {
            play.performClick()
            waitUntil("voice playback started", 10_000) {
                rule.mainClock.advanceTimeByFrame()
                rule.onNodeWithText("停止播放").assertIsDisplayed()
                true
            }
            waitUntil("voice playback completed", 10_000) {
                rule.mainClock.advanceTimeByFrame()
                play.assertIsDisplayed().assertIsEnabled()
                true
            }
            rule.onNodeWithText("语音播放失败", substring = true).assertDoesNotExist()
        }
        println("UI_CASE VOICE PASS ordered=true historyReloaded=true playbackVerified=$verifyPlayback")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun v019_longReplyPreservesReadingLineAcrossTextImageAndCompletion() {
        createV015Profiles(withLegacyKey = true)
        rule.setContent {
            Box(Modifier.requiredSize(650.dp, 480.dp)) { MaterialTheme { DesktopScreen(store, keys, cache) } }
        }
        awaitConnected()
        val stream = UUID.randomUUID().toString().take(8)
        val prompt = "fixture:long-stream:$stream"
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = clipboard.getContents(null)
        try {
            sendText(prompt)
            awaitUi("paragraph 90 visible at the live tail") { visibleParagraph(90) }
            val draft = "下一条草稿"
            rule.onNodeWithTag("composer-field").performTextInput(draft)
            advanceUiAnimations()
            val viewport = bounds("message-region")
            assertTrue("Reply spans at least three viewports", paragraphBox(90).top - paragraphBox(1).top > 3 * viewport.height)
            rule.onNodeWithTag("return-to-latest").assertDoesNotExist()
            val composer = bounds("composer")
            // Wheel through the final lazy item. Its semantics remain visible even though
            // the actual reply tail is now several screens below the viewport.
            wheelUntil(-3f) { paragraphBox(90).top > viewport.bottom + 2 * viewport.height }
            val middle = nearestVisibleParagraph(90, "Paragraph")
            val anchor = paragraphBox(middle)
            assertTrue(visibleParagraph(middle))
            rule.onNodeWithTag("message-entry-1").assertIsDisplayed()
            val latest = bounds("return-to-latest")
            assertTrue("Latest control overlays the message viewport", latest.top >= viewport.top && latest.bottom <= viewport.bottom)
            assertEquals(composer, bounds("composer"))
            val session = store.profile(profileA)!!.currentSession
            val name = "当前流式会话"
            clickSemanticsTag("sidebar-toggle")
            advanceUiAnimations()
            openRename(session)
            enterName(name)
            clickSemanticsTag("confirm-rename-session")
            advanceUiAnimations()
            clickSemanticsTag("close-sidebar-button")
            advanceUiAnimations()
            rule.onNodeWithText(name).assertIsDisplayed()
            assertEquals(anchor.top, paragraphBox(middle).top, 2f)

            val digits = middle.toString().padStart(3, '0')
            rule.onNodeWithTag("message-region").performMouseInput { doubleClick(anchor.center - viewport.topLeft) }
            fun assertSelectionCopied() {
                rule.waitUntil(2_000) {
                    runCatching { clipboard.setContents(StringSelection("selection check"), null) }.isSuccess
                }
                rule.onNodeWithTag("message-region").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
                awaitUi("selected paragraph number $digits copied") { clipboard.getData(DataFlavor.stringFlavor) == digits }
            }
            assertSelectionCopied()

            fun assertReadingLine() {
                assertEquals("Paragraph $middle y after stream update", anchor.top, paragraphBox(middle).top, 2f)
                assertTrue(visibleParagraph(middle))
                rule.onNodeWithTag("return-to-latest").assertIsDisplayed()
                assertEquals(composer, bounds("composer"))
                assertEquals(session, store.profile(profileA)!!.currentSession)
                assertEquals(name, store.sessions(profileA).single { it.id == session }.title)
                assertComposerText(draft)
                assertSelectionCopied()
            }
            releaseStream(stream, "text")
            awaitUi("second text batch rendered") { paragraphBox(108).height > 0 }
            assertReadingLine()
            releaseStream(stream, "image")
            awaitUi("reply image decoded and laid out") {
                rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("助手回复图片")),
                    useUnmergedTree = true).fetchSemanticsNodes().any { it.size.height > 0 }
            }
            assertReadingLine()
            releaseStream(stream, "end")
            awaitUi("completed reply rendered") {
                entryForCurrentSession(prompt)?.status == "已完成" &&
                    rule.onAllNodes(hasTestTag("message-progress-1")).fetchSemanticsNodes().isEmpty()
            }
            assertReadingLine()

            val saved = DesktopStore(historyFile)
            assertEquals(name, saved.sessions(profileA).single { it.id == session }.title)
            val reply = saved.sessionContent(profileA, session).entries.last()
            assertEquals(prompt, reply.question)
            assertEquals("reply-image-a", reply.replyAttachments.single().attachmentId)
            clickSemanticsTag("return-to-latest")
            awaitUi("latest click reveals image tail") { atImageTail() }
            wheelUntil(-3f) { isDisplayed("return-to-latest") }
            wheelUntil(3f) { atImageTail() }
            assertEquals(composer, bounds("composer"))
            wheelUntil(-3f) { isDisplayed("return-to-latest") && paragraphBox(108).top > viewport.bottom + viewport.height }
            val nextQuestion = "延迟的下一条问题"
            val previousCount = saved.sessionContent(profileA, session).entries.size
            rule.onNodeWithTag("composer-field").performTextClearance()
            sendText(nextQuestion)
            awaitUi("sending from older text reveals the new turn") {
                rule.onNodeWithText(nextQuestion).assertIsDisplayed()
                rule.onNodeWithText("本地服务已收到，正在继续", substring = true).assertIsDisplayed()
                rule.onNodeWithTag("return-to-latest").assertDoesNotExist()
                true
            }
            awaitUi("new turn completes in the same conversation") {
                store.sessionContent(profileA, session).entries.let {
                    it.size == previousCount + 1 && it.last().question == nextQuestion && it.last().status == "已完成"
                }
            }
            println("UI_CASE V019_READING PASS paragraph=$middle anchorY=${anchor.top} viewport=${viewport.height} batches=2 image=true completion=true copy=true newTurnVisible=true")
        } finally {
            for (stage in listOf("text", "image", "end")) releaseStream(stream, stage)
            rule.waitUntil(2_000) {
                runCatching { clipboard.setContents(previous ?: StringSelection(""), null) }.isSuccess
            }
        }
    }

    @Test
    fun v019_resumedFollowingTracksEachSubsequentBatchAndImage() {
        createV015Profiles(withLegacyKey = true)
        render()
        awaitConnected()
        val stream = UUID.randomUUID().toString().take(8)
        val prompt = "fixture:long-stream:$stream fixture:two-text-batches"
        fun followingTextTail(number: Int) = visibleParagraph(number) && !isDisplayed("return-to-latest")
        try {
            sendText(prompt)
            awaitUi("initial tail") { followingTextTail(90) }
            wheelUntil(-3f) { isDisplayed("return-to-latest") && !visibleParagraph(90) }
            clickSemanticsTag("return-to-latest")
            advanceUiAnimations()
            awaitUi("clicked latest before batch two") { followingTextTail(90) }
            releaseStream(stream, "text")
            awaitUi("latest follows the next nine paragraphs") { followingTextTail(99) }
            wheelUntil(-3f) { isDisplayed("return-to-latest") && !visibleParagraph(99) }
            wheelUntil(3f) { followingTextTail(99) }
            releaseStream(stream, "text2")
            awaitUi("manual return follows another nine paragraphs") { followingTextTail(108) }
            releaseStream(stream, "image")
            awaitUi("following remains active after asynchronous image decode") { atImageTail() }
            releaseStream(stream, "end")
            awaitUi("tail follows the completed status layout") { entryForCurrentSession(prompt)?.status == "已完成" && atImageTail() }
            println("UI_CASE V019_FOLLOW PASS clickThenText=99 wheelThenText=108 image=true completion=true")
        } finally {
            for (stage in listOf("text", "text2", "image", "end")) releaseStream(stream, stage)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun v019_namesAndBackgroundStreamKeepSessionIdentityAndSelectedDraft() {
        createV015Profiles(withLegacyKey = true)
        val historic = fixture.getProperty("session.a")
        val duplicate = store.createSession(profileA).session
        val imageOnly = store.createSession(profileA).session
        val fresh = store.createSession(profileA).session
        val review = (1..70).joinToString("\n\n") { "Review " + it.toString().padStart(3, '0') + " | 独立回复" }
        store.saveSessionContent(profileA, Chat(duplicate, listOf(Entry("合成的 A 历史问题", review, "已完成"))))
        store.saveSessionContent(profileA, Chat(imageOnly, listOf(Entry("", "", "已完成", attachmentDisplayName = "sample.png"))))
        // Exercise the persisted legacy explicit title alongside generated titles.
        val json = JSONObject(Files.readString(historyFile))
        val sessions = json.getJSONArray("profiles").getJSONObject(0).getJSONArray("sessions")
        (0 until sessions.length()).map(sessions::getJSONObject).single { it.getString("id") == historic }.put("title", "服务历史标题")
        Files.writeString(historyFile, json.toString())
        store = DesktopStore(historyFile)
        store.selectSession(profileA, historic)
        render()
        awaitConnected()
        fun row(id: String) = "session-item-" + store.sessions(profileA).indexOfFirst { it.id == id }
        fun assertRow(id: String, title: String) {
            rule.onNode(hasTestTag(row(id)) and hasText(title)).assertIsDisplayed()
        }
        fun saveName(id: String, value: String) {
            openRename(id)
            enterName(value)
            clickSemanticsTag("confirm-rename-session")
            awaitUi("renamed row $id") { assertRow(id, value.trim()); true }
        }
        assertRow(historic, "服务历史标题")
        assertRow(duplicate, "合成的 A 历史问题")
        assertRow(imageOnly, "图片会话")
        assertRow(fresh, "新会话")
        val order = store.sessions(profileA).map { it.id }
        val stream = UUID.randomUUID().toString().take(8)
        val prompt = "fixture:long-stream:$stream"
        try {
            sendText(prompt)
            awaitUi("first batch at tail") { visibleParagraph(90) }
            wheelUntil(-3f) { isDisplayed("return-to-latest") }
            wheelUntil(3f) { visibleParagraph(90) && !isDisplayed("return-to-latest") }
            releaseStream(stream, "text")
            awaitUi("following reader sees the second batch tail") { visibleParagraph(108) }
            rule.onNodeWithTag("return-to-latest").assertDoesNotExist()
            wheelUntil(-3f) { isDisplayed("return-to-latest") }
            selectSession(duplicate)
            awaitUi("selected conversation independently opens at its own tail") {
                bounds("message-region").contains(paragraphBox(70, "Review").center) && !isDisplayed("return-to-latest")
            }
            rule.onNodeWithTag("composer-field").performTextInput("独立草稿")
            val attachment = Path.of(fixture.getProperty("media.dir")).resolve("upload-b.jpg")
            chooseImageThroughRealPicker(attachment)
            wheelUntil(-3f) { paragraphBox(70, "Review").top > bounds("message-region").bottom + bounds("message-region").height }
            val reviewLine = nearestVisibleParagraph(70, "Review")
            val before = paragraphBox(reviewLine, "Review")
            fun assertSelected() {
                assertEquals(duplicate, store.profile(profileA)!!.currentSession)
                assertEquals(order, store.sessions(profileA).map { it.id })
                assertComposerText("独立草稿")
                assertComposerContainsText(attachment.fileName.toString())
                assertEquals(before.top, paragraphBox(reviewLine, "Review").top, 2f)
                rule.onNodeWithTag("return-to-latest").assertIsDisplayed()
            }
            openRename(historic)
            enterName("忽略")
            clickSemanticsTag("cancel-rename-session")
            advanceUiAnimations()
            assertRow(historic, "服务历史标题")
            openRename(historic)
            enterName("  ")
            clickSemanticsTag("confirm-rename-session")
            rule.onNodeWithTag("rename-session-error").assertIsDisplayed()
            val nameField = rule.onNodeWithTag("rename-session-name")
            nameField.performSemanticsAction(SemanticsActions.RequestFocus)
            advanceUiAnimations()
            assertTrue("Rename field receives keyboard focus", nameField.fetchSemanticsNode().config[SemanticsProperties.Focused])
            nameField.performKeyInput { pressKey(Key.Escape) }
            advanceUiAnimations()
            rule.onNodeWithTag("rename-session-dialog").assertDoesNotExist()
            assertRow(historic, "服务历史标题")
            openRename(historic)
            enterName("无法写入")
            val priorHistory = Files.readString(historyFile)
            Files.setAttribute(historyFile, "dos:readonly", true)
            try {
                clickSemanticsTag("confirm-rename-session")
                rule.onNodeWithTag("rename-session-error").assertIsDisplayed()
                assertEquals("无法写入", rule.onNodeWithTag("rename-session-name").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                assertEquals(priorHistory, Files.readString(historyFile))
                assertEquals("服务历史标题", store.sessions(profileA).single { it.id == historic }.title)
            } finally {
                Files.setAttribute(historyFile, "dos:readonly", false)
            }
            clickSemanticsTag("cancel-rename-session")
            advanceUiAnimations()
            saveName(historic, "  重复手动名称  ")
            assertSelected()
            saveName(duplicate, "重复手动名称")
            assertRow(historic, "重复手动名称")
            rule.onNode(hasText("重复手动名称") and !hasAnyAncestor(sessionRowMatcher()),
                useUnmergedTree = true).assertIsDisplayed()
            clickSemanticsTag("session-menu-$duplicate", useUnmergedTree = true)
            advanceUiAnimations()
            clickSemanticsTag("delete-session-$duplicate", useUnmergedTree = true)
            advanceUiAnimations()
            rule.onNodeWithText("聊天记录“重复手动名称”", substring = true).assertIsDisplayed()
            clickSemanticsTag("cancel-delete-session")
            advanceUiAnimations()
            assertSelected()
            releaseStream(stream, "image")
            awaitUi("background image cached") { cache.read(profileA, "reply-image-a") != null }
            assertSelected()
            releaseStream(stream, "end")
            awaitUi("background reply persisted") {
                store.sessionContent(profileA, historic).entries.last().let { it.status == "已完成" && it.answer.contains("Paragraph 108") && it.replyAttachments.size == 1 }
            }
            assertSelected()
            restartScreen()
            awaitConnected()
            assertRow(historic, "重复手动名称")
            assertRow(duplicate, "重复手动名称")
            assertEquals(duplicate, store.profile(profileA)!!.currentSession)
            openRename(historic)
            clickSemanticsTag("restore-session-name")
            awaitUi("default historical title restored") { assertRow(historic, "服务历史标题"); true }
            assertRow(duplicate, "重复手动名称")
            assertEquals("服务历史标题", DesktopStore(historyFile).sessions(profileA).single { it.id == historic }.title)
            assertEquals(order, store.sessions(profileA).map { it.id })
            println("UI_CASE V019_NAMES PASS defaults=4 duplicate=true backgroundImage=true selectedDraft=true persisted=true restore=true")
        } finally {
            for (stage in listOf("text", "image", "end")) releaseStream(stream, stage)
        }
    }

    @Test
    fun v019_sameSessionIdAcrossProfilesAndResizeToFit() {
        val profile = mutableStateOf("Profile A")
        val height = mutableIntStateOf(160)
        rule.setContent {
            Box(Modifier.requiredSize(420.dp, height.intValue.dp)) {
                MessageViewport(profile.value, "shared-session-id", entryCount = 1, modifier = Modifier) {
                    item {
                        Column {
                            Text("${profile.value} beginning")
                            Spacer(Modifier.height(280.dp))
                            Text("${profile.value} end")
                        }
                    }
                }
            }
        }
        fun insideViewport(label: String): Boolean {
            val text = rule.onNodeWithText(label).fetchSemanticsNode()
            val viewport = bounds("message-region")
            return text.positionInRoot.y >= viewport.top && text.positionInRoot.y + text.size.height <= viewport.bottom
        }
        awaitUi("profile A opens at tail") { insideViewport("Profile A end") }
        wheelUntil(-3f) { insideViewport("Profile A beginning") && isDisplayed("return-to-latest") }
        rule.runOnIdle { profile.value = "Profile B" }
        awaitUi("same session ID in another profile opens independently at tail") {
            insideViewport("Profile B end") && !isDisplayed("return-to-latest")
        }
        wheelUntil(-3f) { insideViewport("Profile B beginning") && isDisplayed("return-to-latest") }
        rule.runOnIdle { height.intValue = 420 }
        awaitUi("entire conversation fits after resize") {
            insideViewport("Profile B beginning") && insideViewport("Profile B end") && !isDisplayed("return-to-latest")
        }
        rule.runOnIdle { height.intValue = 160 }
        awaitUi("shrinking the viewport follows the tail again") {
            insideViewport("Profile B end") && !insideViewport("Profile B beginning") && !isDisplayed("return-to-latest")
        }
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun openRename(id: String) {
        clickSemanticsTag("session-menu-$id", useUnmergedTree = true)
        advanceUiAnimations()
        clickSemanticsTag("rename-session-$id", useUnmergedTree = true)
        advanceUiAnimations()
    }

    private fun enterName(value: String) {
        rule.onNodeWithTag("rename-session-name").performTextClearance()
        rule.onNodeWithTag("rename-session-name").performTextInput(value)
    }

    private fun readLayout(label: String): Pair<TextLayoutResult, Offset> {
        val node = rule.onNodeWithText(label, substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        check(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single() to node.positionInRoot
    }

    private fun paragraphBox(number: Int, prefix: String = "Paragraph"): Rect {
        val label = "$prefix " + number.toString().padStart(3, '0')
        val (layout, origin) = readLayout(label)
        val offset = layout.layoutInput.text.text.indexOf(label)
        check(offset >= 0)
        return layout.getBoundingBox(offset + prefix.length + 2).translate(origin)
    }

    private fun nearestVisibleParagraph(last: Int, prefix: String): Int {
        val viewport = bounds("message-region")
        var first = 1
        var end = last
        while (first < end) {
            val middle = (first + end) / 2
            if (paragraphBox(middle, prefix).top < viewport.top) first = middle + 1 else end = middle
        }
        check(paragraphBox(first, prefix).bottom <= viewport.bottom)
        return first
    }

    private fun visibleParagraph(number: Int) = bounds("message-region").contains(paragraphBox(number).center)

    private fun atImageTail(): Boolean {
        val image = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("助手回复图片")),
            useUnmergedTree = true).fetchSemanticsNode()
        val end = image.positionInRoot.y + image.size.height
        val viewport = bounds("message-region")
        return end in viewport.top..viewport.bottom && !isDisplayed("return-to-latest")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun wheelUntil(amount: Float, done: () -> Boolean) {
        val viewport = rule.onNodeWithTag("message-region")
        val scrollRange = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val start = scrollRange.value()
        repeat(80) {
            if (done()) {
                // Desktop smooth-wheel events finish over several frames. Measure after the
                // gesture settles so the anchor excludes the user's remaining scroll delta.
                var previous = scrollRange.value()
                var stable = 0
                for (frame in 1..20) {
                    Thread.sleep(20)
                    rule.mainClock.advanceTimeBy(64)
                    val current = scrollRange.value()
                    stable = if (current == previous) stable + 1 else 0
                    if (stable >= 3) break
                    previous = current
                }
                if (done()) return
            }
            viewport.performMouseInput {
                moveTo(center)
                scroll(amount)
            }
            advanceUiAnimations()
        }
        assertTrue("Wheel $amount from $start ended at ${scrollRange.value()}/${scrollRange.maxValue()} " +
            "with return-to-latest=${isDisplayed("return-to-latest")}", done())
    }

    private fun awaitUi(description: String, condition: () -> Boolean) {
        waitUntil(description, 15_000) {
            rule.mainClock.advanceTimeBy(100)
            condition()
        }
    }

    private fun releaseStream(runId: String, stage: String) {
        val controls = Path.of(fixture.getProperty("controls.file"))
        Files.writeString(controls.resolveSibling("long-stream-$runId.$stage"), "")
    }

    private fun createV015Profiles(withLegacyKey: Boolean, extraASessions: Int = 0) {
        val aSession = fixture.getProperty("session.a")
        val bSession = fixture.getProperty("session.b")
        val now = System.currentTimeMillis()
        val a = profileJson(profileA, fixture.getProperty("base.a"), aSession,
            listOf(sessionJson(aSession, "合成的 A 历史问题", "合成的 A 历史回复", now)) +
                (0 until extraASessions).map { index ->
                    sessionJson(UUID.randomUUID().toString(), "重复标题", "本地保留的回复 $index", now - index - 1)
                })
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
        val expectedProfile = store.profiles[index].id
        // Saved profiles appear in store order, before the dynamically discovered Launcher instances.
        val radio = rule.onAllNodes(roleMatcher(Role.RadioButton) and
            hasAnyAncestor(hasTestTag("connection-control-settings-dialog")))[index]
        radio.performSemanticsAction(SemanticsActions.OnClick)
        advanceUiAnimations()
        radio.assertIsSelected()
        closeDialog()
        waitUntil("profile selection", 10_000) { DesktopStore(historyFile).activeProfileId == expectedProfile }
    }

    private fun selectSession(sessionId: String) {
        val index = store.sessions(profileA).indexOfFirst { it.id == sessionId }
        assertTrue("Session should be present in the production sidebar", index >= 0)
        scrollToSessionRow(index)
        rule.onNodeWithTag("session-item-$index").performClick()
    }

    private fun scrollToSessionRow(targetIndex: Int) {
        var visible = visibleSessionIndices()
        if (targetIndex in visible) return

        val itemCount = store.sessions(profileA).size
        repeat(itemCount + 2) {
            if (targetIndex in visible) return
            val movingDown = targetIndex > (visible.maxOrNull() ?: -1)
            val visibleEdge = if (movingDown) visible.last() else visible.first()
            rule.onNodeWithTag("session-item-$visibleEdge").performTouchInput {
                val start = center
                down(start)
                moveTo(start + Offset(0f, if (movingDown) -360f else 360f), delayMillis = 250)
                up()
            }
            rule.mainClock.advanceTimeBy(250)
            rule.waitForIdle()
            visible = visibleSessionIndices()
        }
        assertTrue("Sidebar should scroll row $targetIndex into view; visible rows were $visible", rule.onAllNodes(
            SemanticsMatcher("session row $targetIndex visible") { node ->
                node.config.contains(SemanticsProperties.TestTag) &&
                    node.config[SemanticsProperties.TestTag] == "session-item-$targetIndex"
            },
        ).fetchSemanticsNodes().isNotEmpty())
    }

    private fun visibleSessionIndices(): List<Int> = rule.onAllNodes(sessionRowMatcher()).fetchSemanticsNodes().mapNotNull { node ->
        if (!node.config.contains(SemanticsProperties.TestTag)) return@mapNotNull null
        node.config[SemanticsProperties.TestTag].removePrefix("session-item-").toIntOrNull()
    }.sorted()

    private fun sessionRowMatcher() = SemanticsMatcher("session row") { node ->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag].startsWith("session-item-")
    }

    private fun assertComposerText(expected: String) {
        val matcher = SemanticsMatcher("composer contains the expected session draft") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag] == "composer-field" &&
                node.config.contains(SemanticsProperties.EditableText) &&
                node.config[SemanticsProperties.EditableText].text == expected
        }
        assertTrue("Composer should restore the selected session draft", rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty())
    }

    private fun composerFieldText(): String =
        rule.onNodeWithTag("composer-field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun assertComposerContainsText(expected: String) {
        rule.onNode(hasText(expected) and hasAnyAncestor(hasTestTag("composer"))).assertIsDisplayed()
    }

    private fun assertNoComposerText(expected: String, message: String) {
        assertTrue(message, rule.onAllNodes(hasText(expected) and hasAnyAncestor(hasTestTag("composer"))).fetchSemanticsNodes().isEmpty())
    }

    private fun entryForCurrentSession(question: String) = runCatching {
        val sessionId = store.profile(profileA)!!.currentSession
        store.sessionContent(profileA, sessionId).entries.lastOrNull { it.question == question }
    }.getOrNull()

    private fun assertSendingWithoutAnswer(question: String, expectedStage: String) {
        waitUntil("pending entry before response content", 5_000) {
            entryForCurrentSession(question)?.let { it.status == "发送中" && it.answer.isEmpty() } == true
        }
        rule.onNodeWithTag("send-button").assertIsNotEnabled()
        waitUntil("visible $expectedStage progress stage", 5_000) {
            runCatching { rule.onNodeWithText(expectedStage, substring = true).assertIsDisplayed() }.isSuccess
        }
        waitUntil("visible elapsed timer for $expectedStage", 6_000) {
            rule.mainClock.advanceTimeBy(250)
            (1..5).any { seconds ->
                runCatching {
                    rule.onNodeWithText("$expectedStage · $seconds 秒", substring = true).assertIsDisplayed()
                }.isSuccess
            }
        }
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
        chooseImagesThroughRealPicker(listOf(path))
    }

    private fun chooseImagesThroughRealPicker(paths: List<Path>) {
        require(paths.isNotEmpty()) { "At least one image must be selected." }
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
                paths.forEach { path ->
                    check(visible.fileFilter.accept(path.toFile())) { "Fixture file does not match the real picker filter" }
                }
                visible.selectedFiles = paths.map(Path::toFile).toTypedArray()
                check(visible.selectedFiles.map { it.toPath().normalize() } == paths.map(Path::normalize)) {
                    "The real picker did not retain the complete synthetic multi-selection"
                }
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
        assertTrue("Real file picker should accept the synthetic image selection", automationFailure.get() == null)
        rule.waitForIdle()
        paths.forEach { path ->
            assertTrue("Real picker selection renders " + path.fileName, rule.onAllNodes(hasText(path.fileName.toString())).fetchSemanticsNodes().isNotEmpty())
        }
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
        setFixtureControl("reject.original", reject.toString())
    }

    private fun setFixtureControl(name: String, value: String) {
        val path = Path.of(fixture.getProperty("controls.file"))
        val controls = Properties()
        if (Files.isRegularFile(path)) Files.newInputStream(path).use(controls::load)
        controls.setProperty(name, value)
        Files.newOutputStream(path).use { controls.store(it, null) }
    }

    private fun resetFixtureControls() {
        val control = Properties().apply {
            setProperty("reject.original", "false")
            setProperty("chat.delay.before.headers.ms", "1500")
            setProperty("chat.delay.before.first.event.ms", "1500")
            setProperty("chat.truncate.sse", "false")
            setProperty("upload.fail", "false")
        }
        Files.newOutputStream(Path.of(fixture.getProperty("controls.file"))).use { control.store(it, null) }
    }

    private fun fixtureRequestSeen(fragment: String, afterOffset: Long = fixtureLogOffset): Boolean {
        return fixtureRequestCount(fragment, afterOffset) > 0
    }

    private fun fixtureRequestCount(fragment: String, afterOffset: Long = fixtureLogOffset): Int {
        val log = fixtureLog ?: return 0
        if (!Files.exists(log)) return 0
        val offset = afterOffset.coerceAtMost(Files.size(log))
        val current = Files.newInputStream(log).use { input ->
            input.skipNBytes(offset)
            input.readAllBytes()
        }
        return current.toString(Charsets.UTF_8).lineSequence().count { it.contains(fragment) }
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
