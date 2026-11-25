package dev.polarzen.chatbox

import androidx.compose.material.AlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.UUID
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--smoke-test") {
        require(args.size <= 2)
        val directory = Files.createTempDirectory("chatbox-smoke-")
        val file = directory.resolve("history.json")
        try {
            val store = DesktopStore(file)
            val server = "https://example.invalid"
            val chat = Chat(store.read(server).session, listOf(Entry("你好", "世界", "已完成")))
            store.save(server, chat)
            val restored = DesktopStore(file)
            check(restored.username == store.username && restored.read(server) == chat)
            val output = mutableListOf<String>()
            readSse("data: hello\n\ndata: world\n\n".reader().buffered()) { output += it; true }
            check(output == listOf("hello", "world"))
            args.getOrNull(1)?.let { Files.writeString(Path.of(it), "chatbox-smoke-ok") }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
        return
    }

    runDesktopShell(args)
}

private data class ContextNoticeEvidence(
    val question: String, val preferences: ConversationPreferences, val result: AstrBotContextPreview,
)

private data class ContextSendReview(
    val prefs: ConversationPreferences, val question: String, val editorSerial: Long, val retry: Entry?,
    val targetGeneration: Long?, val request: JSONObject?, val preview: AstrBotContextPreview?,
)

private data class SendOperation(
    val id: Int,
    val key: SessionKey,
    val target: DesktopSendTarget,
    val entryIndex: Int,
    val stage: String,
    val failureStage: String,
    val stageStartedAtNanos: Long,
    val answer: String = "",
    val replyAttachments: List<ReplyAttachment> = emptyList(),
    val cancellation: AtomicBoolean = AtomicBoolean(false),
)

private fun SendOperation.visibleChat(stored: Chat): Chat {
    if (entryIndex !in stored.entries.indices) return stored
    return stored.copy(entries = stored.entries.toMutableList().also { entries ->
        entries[entryIndex] = entries[entryIndex].copy(answer = answer, replyAttachments = replyAttachments)
    })
}

private data class RetryConfirmation(val key: SessionKey, val entry: Entry)
private data class TimedEntryKey(val key: SessionKey, val index: Int)
private data class ProgressStage(val label: String, val failureLabel: String)

private data class RejectedSourceImage(val name: String, val reason: String)

private data class SourceImageValidation(val valid: List<Path>, val rejected: List<RejectedSourceImage>)

internal data class SourceImageAddition(val paths: List<Path>, val notice: String?)

private fun KeyEvent.opensHistorySearch(): Boolean =
    type == KeyEventType.KeyDown && key == Key.K && isCtrlPressed

private val windowEventDispatcher = Executor(SwingUtilities::invokeLater).asCoroutineDispatcher()

/** Production screen shared with the desktop UI harness. */
@Composable
internal fun DesktopScreen(
    store: DesktopStore,
    keys: NamedKeys,
    replyImageCache: ReplyImageCache,
    parentWindow: java.awt.Window? = null,
    historySearchOpen: MutableState<Boolean> = remember { mutableStateOf(false) },
    closeRequested: Boolean = false,
    onCloseResult: (Boolean) -> Unit = {},
    isClosing: () -> Boolean = { closeRequested },
    hideRequested: Boolean = false,
    onHideResult: (Boolean) -> Unit = {},
    composerFocusSerial: Long = 0,
    openReply: ReplyDestination? = null,
    onOpenReplyHandled: () -> Unit = {},
    canNavigateReply: () -> Boolean = { true },
    captureReplyNotification: (ReplyDestination, () -> Boolean) -> ReplyToastPermit? = { _, _ -> null },
    windowEvents: DesktopShellVisibility? = null,
    deviceSync: DesktopDeviceSync? = null,
    firstDeviceSetup: Boolean = false,
    onDeviceSetupSeen: () -> Unit = {},
    initialSidebarPinned: Boolean = false,
    onSidebarPinned: (Boolean) -> Unit = {},
    appearanceActions: @Composable () -> Unit = {},
) {
    val replyAudioCache = remember(replyImageCache) { ReplyAudioCache(replyImageCache.directory.resolveSibling("reply-audio")) }
    val sessionsRevision = remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val deletedSessionKeys = remember { ConcurrentHashMap.newKeySet<SessionKey>() }
    val chats = remember { mutableStateMapOf<SessionKey, Chat>() }
    val unsavedReplies = remember { mutableStateMapOf<SessionKey, Chat>() }
    fun latestChat(key: SessionKey): Chat {
        val persisted = store.sessionContent(key.profileId, key.sessionId)
        return unsavedReplies[key] ?: persisted
    }
    val notices = remember { mutableStateMapOf<SessionKey, String>() }
    val composer = remember(store) { DesktopComposerSession(store, scope) { key, message ->
        if (message == null) notices.remove(key) else notices[key] = message
    } }
    val connection = rememberDesktopConnection(store, keys, composer::withLegacyDraftsSaved)
    var deviceWizard by remember { mutableStateOf(firstDeviceSetup) }
    val deviceRevision = deviceSync?.revision ?: 0L
    var preparing by remember { mutableStateOf<Job?>(null) }
    var closeError by remember { mutableStateOf<String?>(null) }
    val windowCommit = closeRequested || hideRequested
    val closingNow by rememberUpdatedState(isClosing)
    val closeResult by rememberUpdatedState(onCloseResult)
    val hideResult by rememberUpdatedState(onHideResult)
    val captureNotification by rememberUpdatedState(captureReplyNotification)
    val replyHandled by rememberUpdatedState(onOpenReplyHandled)
    val canNavigateNow by rememberUpdatedState(canNavigateReply)
    suspend fun saveForWindowChange() {
        preparing?.join()
        closeError = try {
            composer.flushForExit()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            "无法保存草稿，窗口保持打开。请检查本地数据目录后重试。（${error.javaClass.simpleName}）"
        }
        if (closingNow()) closeResult(closeError == null) else hideResult(closeError == null)
    }
    if (windowEvents != null) {
        DisposableEffect(windowEvents, composer) {
            // A hidden window cannot drive recomposition. Tray events still arrive on the AWT event queue.
            windowEvents.bindDraftSave { scope.launch(windowEventDispatcher) { saveForWindowChange() } }
            onDispose { windowEvents.bindDraftSave(null) }
        }
    } else {
        LaunchedEffect(windowCommit) { if (windowCommit) saveForWindowChange() }
    }
    var sending by remember { mutableStateOf<SendOperation?>(null) }
    val activeOperationId = remember { AtomicInteger(0) }
    val qaTimingLog = remember { DesktopQaTimingLog.fromEnvironment() }
    val timingByEntry = remember { ConcurrentHashMap<TimedEntryKey, DesktopRequestTiming>() }
    val failedProgressByEntry = remember { mutableStateMapOf<TimedEntryKey, EntryProgress>() }
    DisposableEffect(qaTimingLog) {
        onDispose { qaTimingLog.close() }
    }
    var retryConfirmation by remember { mutableStateOf<RetryConfirmation?>(null) }
    var nextOperationId by remember { mutableIntStateOf(0) }
    var cacheGeneration by remember { mutableStateOf<Map<ReplyAttachmentKey, Int>>(emptyMap()) }
    var attachmentFailures by remember { mutableStateOf<Map<ReplyAttachmentKey, String>>(emptyMap()) }
    // A repeated attachment ID may share one in-flight download across two live replies.
    val fetchingAttachments = remember { mutableStateMapOf<ReplyAttachmentKey, MutableList<() -> Unit>>() }

    val profileId = connection.profileId
    var searchOpen by historySearchOpen
    var searchSerial by remember { mutableStateOf(0L) }
    var searchDestination by remember(profileId) { mutableStateOf<Pair<SessionKey, SearchHitNavigation>?>(null) }
    var replySerial by remember { mutableStateOf(0L) }
    var replyDestination by remember { mutableStateOf<Pair<SessionKey, ReplyTurnNavigation>?>(null) }
    val selectedProfile = connection.profiles.firstOrNull { it.id == profileId }
    val deviceManaged = deviceSync?.manages(profileId) == true
    val sessions = remember(profileId, connection.profiles, sessionsRevision.intValue, deviceRevision) {
        profileId.takeIf(String::isNotBlank)?.let { runCatching { store.sessions(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    val selectedSessionId = if (profileId.isBlank()) "" else
        sessions.firstOrNull { it.selected }?.id
            ?: sessions.firstOrNull()?.id.orEmpty()
    val currentKey = SessionKey(profileId, selectedSessionId)
    val protectedHistory = !deviceManaged && store.hasSyncedHistory(currentKey)
    val recent = remember(store, connection.profiles, currentKey, sessionsRevision.intValue, deviceRevision) {
        desktopRecentRows(store, currentKey, deviceSync?.takeIf { it.enabled }?.recent().orEmpty())
    }
    val recentById = remember(recent) { recent.associateBy { it.info.id } }
    var refreshOnFocus by remember { mutableIntStateOf(0) }
    DisposableEffect(parentWindow) {
        val listener = object : java.awt.event.WindowAdapter() {
            override fun windowGainedFocus(event: java.awt.event.WindowEvent) {
                if (event.oppositeWindow == null) refreshOnFocus++
            }
        }
        parentWindow?.addWindowFocusListener(listener)
        onDispose { parentWindow?.removeWindowFocusListener(listener) }
    }
    if (deviceSync?.enabled == true) {
        val refresh = remember(deviceSync) { Channel<Unit>(Channel.CONFLATED) }
        LaunchedEffect(refresh, currentKey, connection.profiles, sessionsRevision.intValue, refreshOnFocus, windowEvents?.visible,
            connection.connected) { refresh.trySend(Unit) }
        LaunchedEffect(deviceSync) {
            while (isActive) {
                withTimeoutOrNull(5_000) { refresh.receive() }
                try {
                    deviceSync.refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Another focus, local change or timer retries while the native cache remains readable.
                }
            }
        }
    }
    val whaleGeneration = if (profileId.startsWith("launcher:") && connection.connected &&
        !connection.verifying && !closeRequested
    ) connection.snapshot()?.bindingGeneration else null
    val whaleActive = remember(currentKey, whaleGeneration) { AtomicBoolean(true) }
    var whaleSession by remember(whaleActive) { mutableStateOf<WhaleTaskSession?>(null) }
    var whaleLoginTarget by remember(whaleActive) { mutableStateOf<DesktopSendTarget?>(null) }
    var whaleLoginForRetry by remember(whaleActive) { mutableStateOf(false) }
    DisposableEffect(whaleActive) {
        onDispose {
            whaleActive.set(false)
            whaleSession = null
            whaleLoginTarget = null
        }
    }
    fun whaleTargetCurrent(target: DesktopSendTarget): Boolean =
        !isClosing() && whaleActive.get() && whaleGeneration == target.bindingGeneration &&
            connection.snapshot()?.bindingGeneration == whaleGeneration &&
            store.activeProfileId == currentKey.profileId &&
            store.profile(currentKey.profileId)?.currentSession == currentKey.sessionId

    val storedChat = (if (deviceManaged) null else chats[currentKey]) ?: remember(currentKey, sessionsRevision.intValue, deviceRevision) {
        if (profileId.isNotBlank() && selectedSessionId.isNotBlank()) {
            runCatching { store.sessionContent(profileId, selectedSessionId) }.getOrDefault(Chat(selectedSessionId))
        } else Chat("")
    }
    val chat = sending?.takeIf { it.key == currentKey }?.visibleChat(storedChat) ?: storedChat
    val loadedDraft = remember(composer, currentKey) { runCatching { composer.open(currentKey) } }
    DisposableEffect(composer, currentKey) {
        onDispose { composer.leave(currentKey) }
    }
    val editor = loadedDraft.getOrNull()
    val draft = editor?.field ?: TextFieldValue()
    val currentImages = editor?.draft?.attachments.orEmpty()
    LaunchedEffect(currentKey, deviceRevision, editor?.commitInProgress, preparing == null,
        editor?.field?.composition == null, composer.recordingKey) {
        if (editor != null && preparing == null && deviceManaged) {
            try {
                if (composer.restoreRejectedDraft(currentKey)) sessionsRevision.intValue++
            } catch (_: Exception) { notices[currentKey] = "未发出的草稿仍保存在本机，暂时无法更新输入框。" }
        }
    }
    val preferenceOwner = deviceSync ?: remember(store, keys) { DesktopDeviceSync(store, keys) }
    val handle by remember(preferenceOwner, currentKey, sessionsRevision.intValue) {
        derivedStateOf { preferenceOwner.preferences(currentKey) }
    }
    val prefs = handle.value
    var nameEditor by remember(currentKey) { mutableStateOf<ChatPreferenceHandle?>(null) }
    var contextEditor by remember(currentKey) { mutableStateOf<ChatPreferenceHandle?>(null) }
    var addingPin by remember(currentKey) { mutableStateOf<ContextPin?>(null) }
    var contextReview by remember(currentKey) { mutableStateOf<ContextSendReview?>(null) }
    var confirmNew by remember(currentKey) { mutableStateOf(false) }
    var remind by remember(currentKey, prefs.remindAfter) { mutableStateOf(false) }
    val contextTurns = remember(currentKey, storedChat, deviceRevision, handle.ready) {
        storedChat.entries.mapIndexedNotNull { index, entry ->
            handle.sequenceAt(index)?.let { seq -> ContextTurn(seq, entry.question, entry.answer,
                if (entry.status == "已完成") "complete" else "unknown") }
        }
    }
    var evidence by remember(currentKey, prefs.recentTurns, prefs.pins, draft.text) { mutableStateOf<ContextNoticeEvidence?>(null) }
    var serviceReminder by remember(currentKey, prefs.recentTurns, prefs.pins, draft.text) { mutableStateOf<String?>(null) }
    LaunchedEffect(evidence) {
        val observed = evidence ?: return@LaunchedEffect
        if (!observed.result.supported || observed.question != draft.text.trim() || observed.preferences.recentTurns == null ||
            observed.preferences.recentTurns != prefs.recentTurns || observed.preferences.pins != prefs.pins) return@LaunchedEffect
        val through = contextTurns.filter { it.status == "complete" }.maxOfOrNull { it.seq } ?: 0L
        try {
            for ((kind, active) in contextNoticeConditions(observed.result, contextTurns, prefs)) {
                val show = handle.observeNotice(kind, through, active)
                if (!active && serviceReminder == kind) serviceReminder = null
                if (show && (serviceReminder == null || kind == "capacity")) serviceReminder = kind
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep chatting when the cross-device notice cannot be claimed. */ }
    }
    val completed = completedContextTurns(contextTurns)
    LaunchedEffect(handle.identity, prefs.remindAfter, completed, handle.ready, handle.pending,
        handle.conflicts, refreshOnFocus, deviceSync?.notice) {
        if (prefs.remindAfter?.let { completed >= it } == true) {
            try { if (handle.claimReminder(completed)) remind = true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The next focus or synchronization retries the claim. */ }
        }
    }
    fun openContext(pin: ContextPin? = null) {
        addingPin = pin
        contextEditor = handle
    }
    suspend fun previewContext(value: ConversationPreferences, question: String): AstrBotContextPreview {
        if (deviceManaged) {
            if (handle.contextPending)
                return AstrBotContextPreview.unavailable("上下文设置尚未同步，请先核对两台设备的选择。")
            if (value.recentTurns != prefs.recentTurns || value.pins != prefs.pins)
                return AstrBotContextPreview.unavailable("保存并同步后，服务将核对这项选择。")
            return preferenceOwner.previewContext(currentKey, question)
        }
        val target = runCatching { connection.snapshot() }.getOrNull()
            ?: return AstrBotContextPreview.unavailable("连接服务后可核对其上下文能力。")
        val request = try { astrBotContext(value, contextTurns) } catch (e: Exception) {
            return AstrBotContextPreview.unavailable(preferenceFailure(e))
        }
        return withContext(Dispatchers.IO) {
            target.checkTarget()
            previewAstrBotContext(target.address, target.token, store.username, currentKey.sessionId,
                question, request, target.allowLoopbackHttp)
        }
    }
    val isOffline = selectedProfile?.offlineOnly == true
    fun hasScreenModal() = nameEditor != null || contextEditor != null || contextReview != null || confirmNew || deviceWizard || searchOpen || whaleLoginTarget != null || retryConfirmation != null || closeError != null || closingNow()
    val screenModal = hasScreenModal()
    DesktopModalGuard(screenModal)
    val navigating = openReply != null && !screenModal
    val composerLocked = windowCommit || navigating
    fun composerLockedNow() = composerLocked || windowEvents?.let { it.exiting || it.hideRequested || !it.visible } == true
    val isBusy = sending != null || preparing != null || composerLocked
    LaunchedEffect(openReply, screenModal, windowCommit) {
        val target = openReply ?: return@LaunchedEffect
        if (screenModal || windowCommit) return@LaunchedEffect
        try {
            preparing?.join()
            if (target.key != currentKey) {
                val issue = composer.flushForExit()
                if (issue != null) {
                    notices[currentKey] = issue
                    replyHandled()
                    return@LaunchedEffect
                }
            }
            // A modal can open while a recording or an import finishes, including inside AWT's nested event loop.
            while (hasScreenModal() || !canNavigateNow()) delay(100)
            val selected = synchronized(store) {
                store.selectReplyIfPresent(target).also { found ->
                    if (found) connection.adoptSelectedProfileForReply(checkNotNull(store.profile(target.key.profileId)))
                }
            }
            if (selected) {
                chats[target.key] = latestChat(target.key)
                sessionsRevision.intValue++
                searchDestination = null
                replyDestination = target.key to ReplyTurnNavigation(++replySerial, target.entryIndex)
            } else notices[currentKey] = "这条回复已不存在。"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            notices[currentKey] = safeHistoryFailureMessage(error, "打开回复")
        }
        replyHandled()
    }
    DisposableEffect(currentKey) {
        onDispose { if (replyDestination?.first == currentKey) replyDestination = null }
    }
    val currentOperation = sending?.takeIf { it.key == currentKey }
    val currentProgress = currentOperation?.let {
        EntryProgress(it.entryIndex, it.stage, it.stageStartedAtNanos)
    } ?: chat.entries.lastIndex.takeIf { index ->
        index >= 0 && (chat.entries[index].status.startsWith("发送失败：") || chat.entries[index].status.startsWith("未发送："))
    }?.let { index -> failedProgressByEntry[TimedEntryKey(currentKey, index)] }

    fun chooseImages(replacing: Path? = null) {
        if (composerLockedNow()) return
        val origin = currentKey
        val originEditor = composer.editor(origin)?.takeUnless { it.commitInProgress } ?: return
        originEditor.editSerial++
        try {
            val chooser = JFileChooser().apply {
                dialogTitle = if (replacing == null) "选择图片（可多选）" else "重新选择这张图片"
                isAcceptAllFileFilterUsed = false
                isMultiSelectionEnabled = replacing == null
                fileFilter = FileNameExtensionFilter("PNG / JPEG 图片", "png", "jpg", "jpeg")
            }
            if (chooser.showOpenDialog(parentWindow) == JFileChooser.APPROVE_OPTION && !composerLockedNow()) {
                val selected = chooser.selectedFiles.takeIf { it.isNotEmpty() }
                    ?.toList()
                    ?: listOfNotNull(chooser.selectedFile)
                if (replacing == null) composer.addFiles(origin, selected)
                else composer.replaceMissingAttachment(origin, replacing, selected)
            }
        } catch (_: InvalidPathException) {
            if (!composerLockedNow()) composer.addFiles(origin, emptyList(), invalidPathCount = 1)
        }
    }

    fun invalidateOnFailure(target: DesktopSendTarget, failure: Throwable) {
        if (shouldInvalidateConnection(failure)) {
            SwingUtilities.invokeLater { connection.invalidateIfCurrent(target) }
        }
    }

    fun fetchReplyAttachment(target: DesktopSendTarget, key: ReplyAttachmentKey, onInitialSave: (() -> Unit)? = null) {
        val sessionKey = SessionKey(key.profileId, key.sessionId)
        if (sessionKey in deletedSessionKeys) return
        fetchingAttachments[key]?.let { listeners ->
            onInitialSave?.let(listeners::add)
            return
        }
        fetchingAttachments[key] = listOfNotNull(onInitialSave).toMutableList()
        thread(isDaemon = true, name = "chatbox-reply-attachment") {
            val result = runCatching {
                target.checkTarget()
                check(sessionKey !in deletedSessionKeys) { "会话已删除。" }
                val bytes = when (key.type) {
                    ReplyAttachmentType.IMAGE -> downloadReplyImage(target.address, target.token, key.attachmentId, target.allowLoopbackHttp)
                    ReplyAttachmentType.AUDIO -> downloadReplyAudio(target.address, target.token, key.attachmentId, target.allowLoopbackHttp)
                }
                target.checkTarget()
                check(sessionKey !in deletedSessionKeys) { "会话已删除。" }
                when (key.type) {
                    ReplyAttachmentType.IMAGE -> replyImageCache.save(key.profileId, key.attachmentId, bytes)
                    ReplyAttachmentType.AUDIO -> replyAudioCache.save(key.profileId, key.attachmentId, bytes)
                }
            }
            if (sessionKey !in deletedSessionKeys) result.exceptionOrNull()?.let { invalidateOnFailure(target, it) }
            SwingUtilities.invokeLater {
                if (sessionKey in deletedSessionKeys) return@invokeLater
                val listeners = fetchingAttachments.remove(key).orEmpty()
                val failure = result.exceptionOrNull()
                if (failure == null) {
                    attachmentFailures = attachmentFailures - key
                    cacheGeneration = cacheGeneration + (key to ((cacheGeneration[key] ?: 0) + 1))
                    listeners.forEach { it() }
                } else {
                    attachmentFailures = attachmentFailures + (key to safeAttachmentFailureMessage(failure, key.type))
                }
            }
        }
    }

    fun retryReplyAttachment(key: ReplyAttachmentKey) {
        val sessionKey = SessionKey(key.profileId, key.sessionId)
        if (sessionKey != currentKey || sessionKey in deletedSessionKeys || isBusy || composerLockedNow() ||
            isOffline || !connection.connected || connection.verifying) return
        val target = runCatching { connection.snapshot() }.getOrNull()
        if (target == null || target.profileId != key.profileId) {
            notices[sessionKey] = "请先连接并验证当前服务，再获取回复${key.type.label}。"
            return
        }
        fetchReplyAttachment(target, key)
    }

    fun startRequest(
        clickedAtNanos: Long, targetKey: SessionKey, target: DesktopSendTarget, entry: Entry,
        pending: Chat, attachments: List<Path>, taskSession: WhaleTaskSession?, approved: ContextSendReview?,
    ) {
        unsavedReplies.remove(targetKey)
        chats[targetKey] = pending
        sessionsRevision.intValue++
        notices.remove(targetKey)
        val operationId = ++nextOperationId
        val entryIndex = pending.entries.lastIndex
        val operation = SendOperation(
            operationId, targetKey, target, entryIndex, "准备中", "准备请求失败", clickedAtNanos,
        )
        val timing = DesktopRequestTiming(operationId, qaTimingLog, clickedAtNanos)
        timingByEntry[TimedEntryKey(targetKey, entryIndex)] = timing
        sending = operation
        activeOperationId.set(operationId)
        fun stillLive() = scope.isActive && !closingNow() && targetKey !in deletedSessionKeys && !operation.cancellation.get()
        val notification = LiveReplyNotification { permit ->
            scope.launch(Dispatchers.IO) { permit.show() }
        }
        val query = entry.question
        thread(isDaemon = true, name = "chatbox-request") {
            fun operationIsActive() = !operation.cancellation.get() && activeOperationId.get() == operationId
            var observedFailureStage = operation.failureStage

            fun observeTransportEvent(event: AstrBotTransportEvent) {
                if (!operationIsActive()) throw CancellationException("会话已删除。")
                val stageStartedAt = System.nanoTime()
                timing.onTransportEvent(event)
                val stage = progressStage(event, attachments)
                observedFailureStage = stage.failureLabel
                SwingUtilities.invokeLater {
                    if (!operationIsActive()) return@invokeLater
                    val active = sending
                    if (active?.id == operationId) {
                        sending = active.copy(
                            stage = stage.label,
                            failureStage = stage.failureLabel,
                            stageStartedAtNanos = stageStartedAt,
                        )
                    }
                }
            }

            var answer = ""
            var requestStarted = false
            val receivedAttachments = mutableListOf<ReplyAttachment>()
            val requestedAttachments = mutableSetOf<ReplyAttachmentKey>()

            fun receiveAttachment(type: ReplyAttachmentType, attachmentId: String?, textOffset: Int) {
                if (!operationIsActive()) return
                val attachment = ReplyAttachment(attachmentId?.takeIf(String::isNotBlank), textOffset, type)
                receivedAttachments += attachment
                val updatedEntry = entry.copy(answer = answer, replyAttachments = receivedAttachments.toList())
                val updated = pending.copy(entries = pending.entries.dropLast(1) + updatedEntry)
                val save = runCatching { store.saveSessionContent(targetKey.profileId, updated) }
                val key = attachment.attachmentId?.let { ReplyAttachmentKey(targetKey.profileId, targetKey.sessionId, it, type) }
                val fetch = key != null && requestedAttachments.add(key)
                SwingUtilities.invokeLater {
                    if (!operationIsActive()) return@invokeLater
                    val active = sending
                    if (active?.id == operationId) {
                        sending = active.copy(answer = updatedEntry.answer, replyAttachments = updatedEntry.replyAttachments)
                    }
                    if (save.isFailure) notices[targetKey] = "本地保存失败；收到的${type.label}可能无法在重启后恢复。"
                    if (key != null && fetch) fetchReplyAttachment(target, key, notification::initialAttachmentSaved)
                }
            }

            val result = runCatching {
                check(operationIsActive()) { "会话已删除。" }
                if (taskSession == null) {
                    target.checkTarget()
                    check(operationIsActive()) { "会话已删除。" }
                }
                fun receiveText(delta: String) {
                    if (!operationIsActive()) return
                    answer = delta
                    SwingUtilities.invokeLater {
                        if (operationIsActive() && sending?.id == operationId) sending = sending?.copy(answer = delta)
                    }
                }
                if (taskSession != null) {
                    sendWhaleTask(
                        target, taskSession, query, onEvent = ::observeTransportEvent, onText = ::receiveText,
                        beforeRequest = {
                            check(operationIsActive() && whaleTargetCurrent(target) && whaleSession === taskSession) {
                                "鲸鱼任务登录目标已切换。"
                            }
                            requestStarted = true
                        },
                    )
                } else if (approved != null) {
                    requestStarted = true
                    sendAstrBotContext(target.address, target.token, store.username, targetKey.sessionId, query,
                        checkNotNull(approved.request), checkNotNull(approved.preview).revision, UUID.randomUUID().toString(),
                        target.allowLoopbackHttp,
                        onReplyImage = { id, offset -> receiveAttachment(ReplyAttachmentType.IMAGE, id, offset) },
                        onReplyAudio = { id, offset -> receiveAttachment(ReplyAttachmentType.AUDIO, id, offset) },
                        onEvent = ::observeTransportEvent, onText = ::receiveText)
                } else if (attachments.isEmpty()) {
                    requestStarted = true
                    sendToAstrBot(
                        target.address,
                        target.token,
                        store.username,
                        targetKey.sessionId,
                        query,
                        allowLoopbackHttp = target.allowLoopbackHttp,
                        onReplyImage = { id, offset -> receiveAttachment(ReplyAttachmentType.IMAGE, id, offset) },
                        onReplyAudio = { id, offset -> receiveAttachment(ReplyAttachmentType.AUDIO, id, offset) },
                        onEvent = ::observeTransportEvent,
                        onText = ::receiveText,
                    )
                } else {
                    requestStarted = true
                    sendAttachmentsToAstrBot(
                        target.address,
                        target.token,
                        store.username,
                        targetKey.sessionId,
                        attachments,
                        query,
                        allowLoopbackHttp = target.allowLoopbackHttp,
                        onReplyImage = { id, offset -> receiveAttachment(ReplyAttachmentType.IMAGE, id, offset) },
                        onReplyAudio = { id, offset -> receiveAttachment(ReplyAttachmentType.AUDIO, id, offset) },
                        onEvent = ::observeTransportEvent,
                        onText = ::receiveText,
                    )
                }
            }
            val resultFailure = result.exceptionOrNull()
            timing.requestFinished(
                safeCause = resultFailure?.takeIf { operationIsActive() }
                    ?.let { safeFailureMessage(it, observedFailureStage) },
                cancelled = !operationIsActive(),
            )
            if (!operationIsActive()) return@thread
            val attachments = receivedAttachments.toList()
            val failure = resultFailure
            if (answer.isEmpty() && attachments.isEmpty()) timingByEntry.remove(TimedEntryKey(targetKey, entryIndex), timing)
            val completed = pending.copy(entries = pending.entries.dropLast(1) + entry.copy(
                answer = answer,
                replyAttachments = attachments,
                status = when {
                    result.isSuccess -> "已完成"
                    failure is WhaleTaskOutcomeUnknownException -> "上次回复未确认"
                    else -> (if (requestStarted) "发送失败：" else "未发送：") + observedFailureStage + "：" +
                        safeFailureMessage(failure!!, observedFailureStage)
                },
            ))
            val saved = runCatching { store.saveSessionContent(targetKey.profileId, completed) }
            if (failure != null && taskSession == null && operationIsActive()) invalidateOnFailure(target, failure)
            SwingUtilities.invokeLater {
                if (!operationIsActive()) return@invokeLater
                if (failure is WhaleTaskLoginRequiredException && taskSession != null &&
                    whaleTargetCurrent(target) && whaleSession === taskSession
                ) whaleSession = null
                if (result.isSuccess && saved.isSuccess && approved != null && approved.retry == null) {
                    val source = composer.editor(targetKey)
                    if (source != null && source.editSerial == approved.editorSerial && source.draft.text.trim() == approved.question) {
                        if (runCatching { store.saveDraft(targetKey, store.nextDraftRevision(), ComposerDraft()) }.isSuccess)
                            composer.consumed(targetKey, source.revision)
                    }
                }
                if (saved.isFailure) unsavedReplies[targetKey] = completed
                chats[targetKey] = completed
                sessionsRevision.intValue++
                val operation = sending
                if (operation?.id == operationId) {
                    if (failure != null) {
                        val safeCause = safeFailureMessage(failure, observedFailureStage)
                        failedProgressByEntry[TimedEntryKey(targetKey, entryIndex)] = EntryProgress(
                            entryIndex,
                            observedFailureStage,
                            operation.stageStartedAtNanos,
                            "$observedFailureStage：$safeCause",
                        )
                    }
                    sending = null
                    activeOperationId.compareAndSet(operationId, 0)
                    when {
                        saved.isFailure -> notices[targetKey] = "本地保存失败，请勿直接重发。"
                        result.isSuccess && answer.isEmpty() && attachments.isEmpty() ->
                            notices[targetKey] = "回复已结束，但没有可显示的内容。"
                        result.isSuccess -> notices.remove(targetKey)
                        else -> notices[targetKey] = observedFailureStage + "：" + safeFailureMessage(failure!!, observedFailureStage)
                    }
                    notification.complete(completed.entries.last(), saved.isSuccess) {
                        if (stillLive()) captureNotification(ReplyDestination(targetKey, entryIndex), ::stillLive) else null
                    }
                }
            }
        }
    }

    fun dispatch(retryEntry: Entry? = null, targetKey: SessionKey = currentKey, approved: ContextSendReview? = null) {
        val clickedAtNanos = System.nanoTime()
        if (targetKey != currentKey || protectedHistory || editor == null ||
            composerLockedNow() || sending != null || preparing != null ||
            composer.recordingKey == targetKey || (retryEntry == null && editor.field.composition != null)
        ) return
        val latestPrefs = preferenceOwner.preferences(currentKey)
        val question = retryEntry?.question ?: editor.draft.text.trim()
        val conflicts = latestPrefs.conflicts.any { it == "pins" || it == "recentTurns" }
        if (conflicts || latestPrefs.contextPending) {
            notices[currentKey] = "请先核对并同步上下文设置。"
            openContext()
            return
        }
        if (approved != null && (latestPrefs.value != approved.prefs || question != approved.question ||
                editor.editSerial != approved.editorSerial || approved.preview?.canSend != true ||
                !deviceManaged && runCatching { connection.snapshot()?.bindingGeneration }.getOrNull() != approved.targetGeneration)) {
            notices[currentKey] = CONTEXT_CHANGED
            return
        }
        if (latestPrefs.value.recentTurns != null) {
            val hasMedia = if (retryEntry == null) editor.draft.attachments.isNotEmpty() || composer.pendingImport(targetKey) != null
                else retryEntry.attachmentReferences.isNotEmpty() || retryEntry.attachmentDisplayName != null
            if (hasMedia || profileId.startsWith("launcher:") && isWhaleTaskCommand(question.trimStart())) {
                notices[currentKey] = if (hasMedia) CUSTOM_CONTEXT_MEDIA_NOTICE else "鲸鱼任务需使用服务默认上下文。"
                return
            }
            if (approved == null) {
                val request = if (deviceManaged) null else try { astrBotContext(latestPrefs.value, contextTurns) }
                    catch (e: Exception) { notices[currentKey] = preferenceFailure(e); return }
                contextReview = ContextSendReview(latestPrefs.value, question, editor.editSerial, retryEntry,
                    if (deviceManaged) null else runCatching { connection.snapshot()?.bindingGeneration }.getOrNull(),
                    request, null)
                return
            }
        }
        if (deviceManaged) {
            if (retryEntry != null || deviceSync.reason(targetKey).isNotBlank()) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    sendDeviceDraft(deviceSync, targetKey, editor, composer, store, replyImageCache, replyAudioCache, approved?.preview?.revision) { message ->
                        if (message == null) notices.remove(targetKey) else notices[targetKey] = message
                    }
                    sessionsRevision.intValue++
                } finally { preparing = null }
            }
            preparing = job
            job.start()
            return
        }
        if (retryEntry?.blocksWhaleTaskRetry(targetKey.profileId) == true) {
            notices[targetKey] = WHALE_TASK_CHECK_STATUS
            return
        }
        if (isOffline) {
            notices[targetKey] = "离线历史不可发送。请选择已验证的服务配置。"
            return
        }
        val target = runCatching { connection.snapshot() }.getOrElse { error ->
            notices[targetKey] = if (isKeyAccessFailure(error)) {
                "无法读取所选密钥，请重新选择密钥并连接验证。"
            } else safeFailureMessage(error)
            return
        }
        if (target == null || target.profileId != targetKey.profileId || !connection.connected) {
            notices[targetKey] = "请先连接并验证当前服务，再发送消息。"
            return
        }
        val pendingImport = if (retryEntry == null) composer.pendingImport(targetKey) else null
        val clickedSerial = editor.editSerial
        val current = unsavedReplies[targetKey]
        val prepare = scope.launch(start = CoroutineStart.LAZY) {
            var locked = false
            try {
                val importIssue = pendingImport?.await()
                if (importIssue != null) {
                    notices[targetKey] = importIssue
                    return@launch
                }
                if (targetKey in deletedSessionKeys) return@launch
                val committed = editor.draft
                if (retryEntry == null && (editor.editSerial != clickedSerial || editor.field.composition != null || editor.commitInProgress)) {
                    notices[targetKey] = "草稿已更新，请确认后发送。"
                    return@launch
                }
                val revision = editor.revision
                val original = retryEntry?.question ?: committed.text
                val taskText = original.trimStart()
                val task = target.profileId.startsWith("launcher:") && isWhaleTaskCommand(taskText)
                val query = if (task) taskText else retryEntry?.question ?: original.trim()
                val taskSession = if (task) {
                    if (!whaleTargetCurrent(target)) {
                        notices[targetKey] = "连接或会话已切换，请确认后重试。"
                        return@launch
                    }
                    val hasAttachments = if (retryEntry == null) committed.attachments.isNotEmpty() else
                        retryEntry.attachmentReferences.isNotEmpty() || retryEntry.attachmentReference != null ||
                            retryEntry.attachmentDisplayName != null
                    if (hasAttachments) {
                        notices[targetKey] = "鲸鱼任务仅支持文字，请移除图片或语音后发送。"
                        return@launch
                    }
                    whaleSession ?: run {
                        whaleLoginForRetry = retryEntry != null
                        whaleLoginTarget = target
                        return@launch
                    }
                } else null
                val paths = if (retryEntry == null) committed.attachments else try {
                    retryEntry.attachmentReferences.ifEmpty { listOfNotNull(retryEntry.attachmentReference) }.map { Path.of(it) }
                } catch (_: InvalidPathException) {
                    notices[targetKey] = "无法读取原始附件路径。"
                    return@launch
                }
                if (query.isEmpty() && paths.isEmpty()) return@launch
                val error = withContext(Dispatchers.IO) { attachmentProblem(paths, store.managedMediaDirectory) }
                if (targetKey in deletedSessionKeys) return@launch
                if (retryEntry == null && (editor.revision != revision || editor.editSerial != clickedSerial ||
                        editor.field.composition != null || editor.commitInProgress || composer.recordingKey == targetKey)
                ) {
                    notices[targetKey] = "草稿已更新，请确认后发送。"
                    return@launch
                }
                if (error != null) {
                    notices[targetKey] = error
                    return@launch
                }
                val dispatchPrefs = preferenceOwner.preferences(targetKey)
                if (dispatchPrefs.value.recentTurns != approved?.prefs?.recentTurns ||
                    dispatchPrefs.value.pins != approved?.prefs?.pins.orEmpty() ||
                    dispatchPrefs.conflicts.any { it == "pins" || it == "recentTurns" }) {
                    notices[targetKey] = CONTEXT_CHANGED
                    return@launch
                }
                if (retryEntry == null) {
                    editor.commitInProgress = true
                    locked = true
                }
                val references = paths.map(Path::toString)
                val entry = Entry(
                    question = query, answer = "", status = "发送中", sentAt = System.currentTimeMillis(),
                    attachmentReference = references.firstOrNull(),
                    attachmentDisplayName = paths.firstOrNull()?.fileName?.toString(),
                    attachmentReferences = references,
                )
                val pending = withContext(Dispatchers.IO) {
                    val stored = current ?: store.sessionContent(targetKey.profileId, targetKey.sessionId)
                    val updated = stored.copy(entries = stored.entries + entry)
                    if (retryEntry == null && approved == null) store.commitDraft(targetKey, revision, updated)
                    else {
                        if (retryEntry == null) store.saveDraft(targetKey, revision, committed)
                        store.saveSessionContent(targetKey.profileId, updated)
                    }
                    updated
                }
                if (targetKey in deletedSessionKeys) return@launch
                if (retryEntry == null && approved == null) composer.consumed(targetKey, revision)
                startRequest(clickedAtNanos, targetKey, target, entry, pending, paths, taskSession, approved)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (targetKey !in deletedSessionKeys) notices[targetKey] = "无法保存本地会话，消息未发送。"
            } finally {
                if (locked) editor.commitInProgress = false
                preparing = null
            }
        }
        preparing = prepare
        prepare.start()
    }

    fun selectSearchHit(hit: HistoryHit, query: String): Boolean {
        if (composerLockedNow()) return false
        val key = SessionKey(hit.profileId, hit.sessionId)
        val selected = runCatching {
            synchronized(store) {
                if (hit.profileId != connection.profileId || hit.profileId != store.activeProfileId) return@synchronized null
                val stored = latestChat(key)
                val visible = sending?.takeIf { it.key == key }?.visibleChat(stored) ?: stored
                val source = when (hit.side) {
                    HistorySide.TITLE -> store.sessions(key.profileId).find { it.id == key.sessionId }?.title
                    HistorySide.USER -> visible.entries.getOrNull(hit.entryIndex)?.question
                    HistorySide.ASSISTANT -> visible.entries.getOrNull(hit.entryIndex)?.answer
                }
                if (query.isBlank() || source == null || hit.start < 0 || hit.endExclusive > source.length ||
                    hit.endExclusive - hit.start != query.length ||
                    !source.regionMatches(hit.start, query, 0, query.length, ignoreCase = true)
                ) return@synchronized null
                if (store.profile(key.profileId)?.currentSession != key.sessionId) store.selectSession(key.profileId, key.sessionId)
                stored
            }
        }.getOrNull()
        if (selected == null) {
            notices[currentKey] = "搜索结果已变更或无法打开，请重新选择。"
            sessionsRevision.intValue++
            return false
        }
        chats[key] = selected
        replyDestination = null
        searchSerial++
        searchDestination = if (hit.side == HistorySide.TITLE) null else key to SearchHitNavigation(
            searchSerial, hit.entryIndex, hit.side == HistorySide.ASSISTANT, hit.start, hit.endExclusive,
        )
        sessionsRevision.intValue++
        return true
    }

    val allProfiles = remember(connection.profiles, deviceRevision) { store.profiles }
    val newSources = remember(allProfiles, profileId, deviceRevision, deviceManaged) {
        allProfiles.filter { profile ->
            if (deviceSync?.manages(profile.id) == true) store.deviceProfile(profile.id)?.available != false
            else !profile.offlineOnly && profile.kind != "shared"
        }
    }
    val canCreate = newSources.isNotEmpty() && !composerLocked
    var chooseNewSource by remember { mutableStateOf(false) }
    fun createAt(profile: Profile) {
        if (composerLockedNow()) return
        runCatching {
            val created = store.createSession(profile.id)
            if (profile.id != profileId) {
                if (profile.offlineOnly) connection.adoptSelectedProfileForReply(profile) else connection.selectProfile(profile.id, profile.address)
            }
            searchDestination = null
            chats[SessionKey(profile.id, created.session)] = created
            sessionsRevision.intValue++
            retryConfirmation = null
        }.onFailure { notices[currentKey] = "无法创建新会话。" }
    }
    fun selectNative(key: SessionKey) {
        if (!composerLockedNow()) {
            runCatching {
                store.selectSession(key.profileId, key.sessionId)
                if (key.profileId != profileId) {
                    val profile = checkNotNull(store.profile(key.profileId))
                    if (profile.offlineOnly) connection.adoptSelectedProfileForReply(profile)
                    else connection.selectProfile(profile.id, profile.address)
                }
                searchDestination = null
                chats[key] = latestChat(key)
                sessionsRevision.intValue++
            }.onFailure { notices[currentKey] = "无法打开所选会话。" }
        }
    }
    fun newConversation() {
        if (canCreate && !composerLockedNow()) {
            val preferred = newSources.firstOrNull { it.id == store.activeProfileId } ?: newSources.singleOrNull()
            if (preferred != null) createAt(preferred) else chooseNewSource = true
        }
    }
    val deviceReason = if (deviceManaged) deviceSync?.reason(currentKey).orEmpty() else ""
    val devicePending = if (deviceManaged) deviceSync?.pending(currentKey) ?: 0 else 0
    val sendRouteReady = if (deviceManaged) deviceReason.isBlank() else connection.connected && !connection.verifying
    val deviceTextOnly = deviceManaged && store.profile(profileId)?.kind == "device"
    val canSend = !protectedHistory && selectedSessionId.isNotBlank() && !isBusy && (!isOffline || deviceManaged) && sendRouteReady &&
        (!deviceTextOnly || currentImages.isEmpty() && composer.pendingImport(currentKey) == null) &&
        editor != null && draft.composition == null && composer.recordingKey != currentKey &&
        (draft.text.isNotBlank() || currentImages.isNotEmpty() || composer.pendingImport(currentKey) != null)
    val canRetry = !protectedHistory && !deviceManaged && !isBusy && !isOffline && connection.connected && !connection.verifying && composer.recordingKey != currentKey
    val retryingAttachments = fetchingAttachments.keys.toSet()

    Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.opensHistorySearch()) {
            searchOpen = true
            true
        } else false
    }) {
        DesktopChatLayout(
            composerFocusSerial = composerFocusSerial,
            replyNavigation = replyDestination?.takeIf { it.first == currentKey }?.second,
            searchSelectionSerial = searchSerial,
            searchNavigation = searchDestination?.takeIf { it.first == currentKey }?.second,
            profileId = profileId,
            sessions = recent.map { it.info },
            recentDetails = recentById,
            deletionScope = { id -> recentById[id]?.key?.takeIf { deviceSync?.manages(it.profileId) == true }?.let {
                "删除本机记录，并在下次同步时从两台设备的共享目录移除。"
            } },
            chat = chat,
            onSelectSession = select@{ row ->
                val key = recentById[row]?.key ?: return@select
                selectNative(key)
            },
            onDeleteSession = delete@{ row ->
                val key = recentById[row]?.key ?: return@delete
                if (!composerLockedNow() && preparing == null) {
                    runCatching { store.deleteSession(key.profileId, key.sessionId) }
                        .onSuccess {
                            deletedSessionKeys.add(key)
                            if (searchDestination?.first == key) searchDestination = null
                            sending?.takeIf { it.key == key }?.let { operation ->
                                operation.cancellation.set(true)
                                activeOperationId.compareAndSet(operation.id, 0)
                                sending = null
                            }
                            if (retryConfirmation?.key == key) retryConfirmation = null
                            chats.remove(key)
                            unsavedReplies.remove(key)
                            composer.forget(key)
                            notices.remove(key)
                            fetchingAttachments.keys.removeIf { it.profileId == key.profileId && it.sessionId == key.sessionId }
                            attachmentFailures = attachmentFailures.filterKeys { it.profileId != key.profileId || it.sessionId != key.sessionId }
                            cacheGeneration = cacheGeneration.filterKeys { it.profileId != key.profileId || it.sessionId != key.sessionId }
                            timingByEntry.keys.removeIf { it.key == key }
                            failedProgressByEntry.keys.removeIf { it.key == key }
                            sessionsRevision.intValue++
                        }
                        .onFailure { notices[currentKey] = "删除会话失败：${safeHistoryFailureMessage(it, "删除")}" }
                }
            },
            onRenameSession = rename@{ row, name ->
                val key = recentById[row]?.key ?: return@rename "会话已移除。"
                runCatching { store.renameSession(key.profileId, key.sessionId, name) }
                    .onSuccess { sessionsRevision.intValue++ }
                    .exceptionOrNull()?.let { safeHistoryFailureMessage(it, "写入") }
            },
            onNewSession = { if (canCreate && !composerLockedNow()) confirmNew = true },
            canCreate = canCreate,
            onOpenSearch = { searchOpen = true },
            partnerName = prefs.effectiveName,
            initialSidebarPinned = initialSidebarPinned, onSidebarPinned = onSidebarPinned,
            settingsLabel = "设置 · " + if (deviceManaged) deviceSync.notice else if (connection.connected) "已连接" else "未连接",
            pinnedMessage = { index, role -> prefs.pins.isNotEmpty() && handle.sequenceAt(index)?.let { ContextPin(it, role) in prefs.pins } == true },
            onToggleMessage = if (!handle.ready) null else toggle@{ index, role ->
                val seq = handle.sequenceAt(index)
                if (seq == null) { notices[currentKey] = "此消息尚未与共享会话核对。"; return@toggle }
                val pin = ContextPin(seq, role)
                if ("pins" in handle.conflicts) { openContext(); return@toggle }
                if (prefs.recentTurns == null) openContext(pin) else try {
                    handle.save(prefs.copy(pins = if (pin in prefs.pins) prefs.pins - pin else prefs.pins + pin))
                } catch (e: Exception) { notices[currentKey] = preferenceFailure(e) }
            },
            reminderContent = {
                if (remind || serviceReminder != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("context-reminder"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(when (serviceReminder) {
                        "capacity" -> "模型容量预计不足"
                        "rolling" -> "下一轮将移出最近范围内的消息"
                        else -> "自设轮数提醒 · 已满 ${prefs.remindAfter} 轮"
                    }, Modifier.weight(1f), fontSize = 12.sp)
                    TextButton(onClick = { openContext() }) { Text("调整上下文", fontSize = 12.sp) }
                    TextButton(onClick = { confirmNew = true }, enabled = canCreate) { Text("新开会话", fontSize = 12.sp) }
                    ChatIconButton(DesktopChatIcon.Close, "稍后处理", "dismiss-context-reminder", { if (serviceReminder != null) serviceReminder = null else remind = false })
                }
            },
            topActions = {
                var expanded by remember { mutableStateOf(false) }
                var details by remember { mutableStateOf(false) }
                Box {
                    ChatIconButton(DesktopChatIcon.More, "会话菜单", "conversation-menu", { expanded = true })
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(onClick = { expanded = false; nameEditor = handle }, enabled = handle.ready,
                            modifier = Modifier.testTag("rename-peer")) { Text("修改对方名称") }
                        DropdownMenuItem(onClick = { expanded = false; openContext() }, enabled = handle.ready,
                            modifier = Modifier.testTag("conversation-context")) { Text("上下文") }
                        DropdownMenuItem(onClick = { expanded = false; details = true },
                            modifier = Modifier.testTag("conversation-details")) { Text("服务档案与连接") }
                    }
                }
                DesktopModalGuard(details)
                if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("服务档案与连接") },
                    text = { Column {
                        Text(selectedProfile?.address?.ifBlank { selectedProfile.id } ?: "尚未选择服务档案")
                        DesktopConnectionControls(connection,
                            deviceStatus = deviceSync?.takeIf { deviceManaged }?.notice,
                            onDeviceSetup = null)
                    } },
                    confirmButton = { TextButton(onClick = { details = false }) { Text("完成") } })
            },
            settingsContent = { closeSettings ->
                if (deviceManaged) DesktopDeviceCopies(store, currentKey, deviceRevision, replyImageCache, replyAudioCache, ::selectNative)
                if (whaleSession != null) {
                    TextButton(onClick = {
                        whaleSession = null
                        notices[currentKey] = "已退出鲸鱼任务。"
                    }, enabled = !isBusy) { Text("退出鲸鱼任务") }
                }
                appearanceActions()
                DesktopConnectionControls(connection,
                    deviceStatus = if (deviceManaged) deviceSync.notice.ifBlank { "正在核对设备共享服务…" } else null,
                    onDeviceSetup = deviceSync?.let { sync -> {
                        if (sending != null || preparing != null || composer.recordingKey != null)
                            notices[currentKey] = "请等待本轮发送或录音结束，再配置设备共享。"
                        else if (!sync.enabled && unsavedReplies.isNotEmpty())
                            notices[currentKey] = "有回复尚未存入本机历史。请先检查数据目录，再开启设备共享。"
                        else { closeSettings(); deviceWizard = true }
                    } })
            },
            draft = draft,
            onDraft = { value -> if (!composerLockedNow()) composer.input(currentKey, value) },
            selectedImage = currentImages.firstOrNull(),
            selectedImages = currentImages,
            onChooseImage = { chooseImages() },
            onReplaceMissingAttachment = { path -> chooseImages(path) },
            composerCommitInProgress = composerLocked || editor?.commitInProgress == true,
            onAddImages = { files -> if (!composerLockedNow()) composer.addFiles(currentKey, files) },
            onRemoveImage = { if (!composerLockedNow()) composer.remove(currentKey, null) },
            onRemoveImagePath = { if (!composerLockedNow()) composer.remove(currentKey, it) },
            onPasteAttachment = { composerLockedNow() || (!deviceTextOnly && selectedSessionId.isNotBlank() && composer.paste(currentKey)) },
            canRecord = !deviceTextOnly && selectedSessionId.isNotBlank() && editor?.commitInProgress == false && composer.recordingKey == null && !composerLocked,
            recordingMillis = composer.elapsed.takeIf { composer.recordingKey == currentKey },
            onStartRecording = { if (!composerLockedNow()) composer.startMic(currentKey) },
            onStopRecording = { if (!composerLockedNow()) composer.stopMic(currentKey) },
            onCancelRecording = { if (!composerLockedNow()) composer.stopMic(currentKey, discard = true) },
            onSend = { dispatch() },
            canSend = canSend,
            profileTextOnly = deviceTextOnly,
            notice = if (loadedDraft.isFailure) "本地草稿无法读取。" else notices[currentKey]
                ?: when {
                    protectedHistory -> "此会话保留了已同步的历史。请新建本机会话，或在设备共享设置中连接原数据库。"
                    deviceTextOnly && currentImages.isNotEmpty() -> "此来源的媒体需在原设备发送。移除所选附件即可发送文字。"
                    deviceReason.isNotBlank() -> deviceReason
                    devicePending > 0 -> "待发送或待同步 · $devicePending 条。恢复连接后自动核对。"
                    deviceManaged -> deviceSync?.notice?.takeIf { it.isNotBlank() && it != "已同步设备会话。" }
                    else -> null
                },
            onRetry = { entry ->
                if (canRetry && !composerLockedNow() && chat.entries.any { it === entry || it == entry }) {
                    retryConfirmation = RetryConfirmation(currentKey, entry)
                }
            },
            canRetry = canRetry,
            assistantContent = { item ->
                DesktopReplyContent(
                    partnerName = prefs.effectiveName, profileId = currentKey.profileId,
                    sessionId = currentKey.sessionId,
                    answer = item.answer,
                    attachments = item.replyAttachments,
                    imageCache = replyImageCache,
                    audioCache = replyAudioCache,
                    cacheGeneration = cacheGeneration,
                    failures = attachmentFailures,
                    retrying = retryingAttachments,
                    retryEnabled = canRetry || deviceManaged && connection.connected && !isBusy && !connection.verifying,
                    onRetry = ::retryReplyAttachment,
                )
            },
            onMessageRendered = { sessionId, entryIndex ->
                if (profileId.isNotBlank() && sessionId == currentKey.sessionId) {
                    timingByEntry.remove(TimedEntryKey(currentKey, entryIndex))?.firstDrawn()
                }
            },
            progress = currentProgress,
        )

        if (chooseNewSource) {
            DesktopModalGuard()
            AlertDialog(onDismissRequest = { chooseNewSource = false }, title = { Text("新会话使用哪个档案？") },
                text = { Column { newSources.forEach { profile ->
                    TextButton(onClick = { chooseNewSource = false; createAt(profile) }) { Text(profileDisplayName(profile)) }
                } } }, confirmButton = { TextButton(onClick = { chooseNewSource = false }) { Text("取消") } })
        }
        nameEditor?.let { snapshot -> PartnerNameDialog(snapshot, { nameEditor = null }, { nameEditor = null }) }
        contextEditor?.let { snapshot ->
            ConversationContextDialog(snapshot, contextTurns, draft.text.trim(), { value ->
                try {
                    val question = draft.text.trim()
                    previewContext(value, question).also { result ->
                        if (snapshot.value.recentTurns == value.recentTurns && snapshot.value.pins == value.pins)
                            evidence = ContextNoticeEvidence(question, value, result)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { AstrBotContextPreview.unavailable(CONTEXT_UNAVAILABLE) }
            }, addingPin, { contextEditor = null }, { contextEditor = null })
        }
        contextReview?.let { review ->
            LaunchedEffect(review.prefs, review.question, review.editorSerial) {
                val result = try { previewContext(review.prefs, review.question) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { AstrBotContextPreview.unavailable(CONTEXT_UNAVAILABLE) }
                evidence = ContextNoticeEvidence(review.question, review.prefs, result)
                contextReview = review.copy(preview = result)
            }
            ContextSendDialog(review.question, prefs.effectiveName, review.preview, onConfirm = {
                contextReview = null
                dispatch(review.retry, currentKey, review)
            }, onAdjust = { contextReview = null; openContext() }, onClose = { contextReview = null })
        }
        if (confirmNew) AlertDialog(onDismissRequest = { confirmNew = false }, title = { Text("新开会话？") },
            text = { Text("可以从最近对话返回这里，继续阅读或编辑原草稿。") },
            confirmButton = { TextButton(onClick = { confirmNew = false; newConversation() },
                modifier = Modifier.testTag("confirm-new-conversation")) { Text("新开会话") } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("取消") } })
        if (deviceWizard && deviceSync != null) {
            DesktopDeviceWizard(store, connection, deviceSync, onDismiss = {
                deviceWizard = false
                onDeviceSetupSeen()
            })
        }
        whaleLoginTarget?.let { target ->
            WhaleTaskLoginDialog(
                target.address,
                onDismiss = { whaleLoginTarget = null },
                login = { username, password, totp ->
                    val session = withContext(Dispatchers.IO) {
                        val loginContext = currentCoroutineContext()
                        loginWhaleTasks(target, username, password, totp, beforeRequest = {
                            loginContext.ensureActive()
                            check(whaleLoginTarget === target && whaleTargetCurrent(target)) {
                                "鲸鱼任务登录目标已切换。"
                            }
                        })
                    }
                    if (whaleTargetCurrent(target) && whaleLoginTarget === target) {
                        whaleSession = session
                        whaleLoginTarget = null
                        notices[currentKey] = if (whaleLoginForRetry) "登录成功，请再次点击重发并确认。"
                            else "登录成功，请再次点击发送。"
                    }
                },
            )
        }

        if (closeRequested || closeError != null) {
            AlertDialog(
                onDismissRequest = { if (!closeRequested) closeError = null },
                text = { Text(closeError ?: "正在保存草稿…") },
                confirmButton = { if (!closeRequested) TextButton(onClick = { closeError = null }) { Text("关闭") } },
            )
        }
        if (searchOpen) {
            val overrides = buildMap {
                for ((key, reply) in unsavedReplies) {
                    if (key.profileId == profileId) put(key.sessionId, reply)
                }
                sending?.takeIf { it.key.profileId == profileId }?.let { operation ->
                    chats[operation.key]?.let { pending ->
                        put(operation.key.sessionId, operation.visibleChat(pending))
                    }
                }
            }
            DesktopHistorySearch(
                store, profileId, sessionsRevision.intValue, overrides,
                onDismiss = { searchOpen = false },
                onSelectHit = ::selectSearchHit,
                partnerName = { session -> preferenceOwner.preferences(SessionKey(profileId, session)).value.effectiveName },
            )
        }
        val pendingRetry = retryConfirmation?.takeIf { it.key == currentKey }
        if (pendingRetry != null) {
            AlertDialog(
                onDismissRequest = { retryConfirmation = null },
                title = { Text("重新发送？") },
                text = { Text("此条消息可能已到达 AstrBot，继续可能重复执行。") },
                confirmButton = {
                    TextButton(onClick = {
                        retryConfirmation = null
                        dispatch(pendingRetry.entry, pendingRetry.key)
                    }, enabled = canRetry) { Text("确认重发") }
                },
                dismissButton = { TextButton(onClick = { retryConfirmation = null }) { Text("取消") } },
            )
        }
    }
}

/** Local preflight completes for every part before a pending history row or upload is created. */
internal fun attachmentProblem(paths: List<Path>, mediaDirectory: Path): String? {
    val rejected = paths.mapNotNull { path ->
        val name = path.fileName?.toString().orEmpty()
        if (name.endsWith(".wav", ignoreCase = true)) {
            val reason = when {
                path.toAbsolutePath().normalize().parent != mediaDirectory.toAbsolutePath().normalize() -> "不是本机录音"
                !Files.isRegularFile(path) || !Files.isReadable(path) -> "文件不可读取"
                runCatching { validatedAudio(path) }.isFailure -> "录音损坏、超限或格式不受支持"
                else -> null
            }
            reason?.let { RejectedSourceImage(name, it) }
        } else {
            inspectSourceImages(listOf(path), requireContent = true).rejected.firstOrNull()
        }
    }
    return if (rejected.isEmpty()) null else "附件无法发送：${formatRejectedImages(rejected)}。"
}

private fun inspectSourceImages(paths: List<Path>, requireContent: Boolean = false): SourceImageValidation {
    val valid = mutableListOf<Path>()
    val rejected = mutableListOf<RejectedSourceImage>()
    paths.forEach { original ->
        val normalized = runCatching { original.toAbsolutePath().normalize() }.getOrNull()
        val name = original.fileName?.toString().orEmpty().ifBlank { "图片" }
        val size = normalized?.takeIf { Files.isRegularFile(it) && Files.isReadable(it) }
            ?.let { runCatching { Files.size(it) }.getOrNull() }
        val reason = when {
            normalized == null -> "路径无效"
            normalized.fileName?.toString()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty() !in IMAGE_EXTENSIONS -> "仅支持 PNG/JPEG"
            !Files.isRegularFile(normalized) || !Files.isReadable(normalized) -> "文件不可读取"
            size == null -> "文件不可读取"
            size == 0L && requireContent -> "文件为空"
            size > MAX_SOURCE_IMAGE_BYTES -> "超过 512 MiB"
            else -> null
        }
        if (reason == null) valid.add(checkNotNull(normalized)) else rejected.add(RejectedSourceImage(name, reason))
    }
    return SourceImageValidation(valid.distinct(), rejected)
}

internal fun addSourceImages(
    existing: List<Path>,
    files: List<File>,
    invalidPathCount: Int = 0,
    requireContent: Boolean = false,
): SourceImageAddition {
    val paths = mutableListOf<Path>()
    var rejectedPathCount = invalidPathCount
    files.forEach { file ->
        try {
            paths.add(file.toPath())
        } catch (_: InvalidPathException) {
            rejectedPathCount++
        }
    }
    val validation = inspectSourceImages(paths, requireContent)
    val combined = (existing + validation.valid).distinct()
    val notice = sourceImageNotice(
        validation.rejected,
        addedCount = combined.size - existing.size,
        invalidPathCount = rejectedPathCount,
    )
    return SourceImageAddition(combined, notice)
}

private fun sourceImageNotice(
    rejected: List<RejectedSourceImage>,
    addedCount: Int,
    invalidPathCount: Int,
): String? {
    if (rejected.isEmpty() && invalidPathCount == 0) return null
    val added = if (addedCount > 0) "已添加 $addedCount 张图片；" else "未添加图片；"
    val skipped = buildList {
        if (rejected.isNotEmpty()) add(formatRejectedImages(rejected))
        if (invalidPathCount > 0) add("无效文件名（$invalidPathCount 个）")
    }.joinToString("、")
    return added + "已跳过 $skipped。"
}

private fun formatRejectedImages(rejected: List<RejectedSourceImage>): String {
    val shown = rejected.take(3).joinToString("、") { image ->
        val shortName = image.name.take(24).let { if (it.length < image.name.length) "$it…" else it }
        "$shortName（${image.reason}）"
    }
    val remainder = rejected.size - minOf(rejected.size, 3)
    return shown + if (remainder > 0) "，另有 $remainder 个文件" else ""
}

private const val MAX_SOURCE_IMAGE_BYTES = 512L * 1024L * 1024L
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

private fun progressStage(event: AstrBotTransportEvent, attachments: List<Path>): ProgressStage {
    fun label(index: Int) = if (attachments.getOrNull(index)?.fileName?.toString()?.endsWith(".wav", true) == true) "语音" else "图片"
    return when (event) {
        is AstrBotTransportEvent.UploadStarted -> ProgressStage(
            "正在上传${label(event.index)} ${event.index + 1}/${event.count}",
            "上传${label(event.index)} ${event.index + 1}/${event.count} 失败",
        )
        is AstrBotTransportEvent.UploadFinished -> ProgressStage(
            "${label(event.index)}已上传 ${event.index + 1}/${event.count}",
            "准备发送消息失败",
        )
        AstrBotTransportEvent.ChatStarted -> ProgressStage("等待服务器响应", "等待服务器响应失败")
        AstrBotTransportEvent.HeadersReceived -> ProgressStage("验证响应流", "验证响应流失败")
        AstrBotTransportEvent.StreamReady -> ProgressStage("等待首段回复", "等待首段回复失败")
        AstrBotTransportEvent.FirstContent -> ProgressStage("正在接收回复", "接收回复失败")
        AstrBotTransportEvent.Completed -> ProgressStage("整理回复", "整理回复失败")
    }
}

private fun safeHistoryFailureMessage(error: Throwable, operation: String): String = when (error) {
    is AccessDeniedException -> "本地历史文件访问被拒绝（AccessDeniedException）。"
    is SecurityException -> "没有权限访问本地历史文件。"
    is FileSystemException -> "本地历史文件操作失败（FileSystemException）。"
    is IOException -> "本地历史文件读写失败（${error.javaClass.simpleName}）。"
    else -> when (error.message) {
        "Unknown session" -> "所选会话已不存在。"
        "Unknown profile" -> "所选服务档案已不存在。"
        else -> "${operation}失败（${error.javaClass.simpleName}）。"
    }
}

private fun isKeyAccessFailure(error: Throwable): Boolean = when (error.message) {
    "Windows credential protection failed.",
    "Named credential storage is unavailable.",
    "已保存密钥不可用，请重新连接。" -> true
    else -> false
}

private fun shouldInvalidateConnection(error: Throwable): Boolean = when (error.message) {
    "密钥无效或已撤销。",
    "权限不足，请检查聊天权限。",
    "缺少文件上传权限，请为 API 密钥启用 file 权限。",
    "缺少图片下载权限，请为 API 密钥启用 file 权限。",
    "缺少语音下载权限，请为 API 密钥启用 file 权限。",
    "服务地址已变化，请重新连接验证后再发送。",
    "服务地址已变化，请重新连接验证后再获取图片。",
    "服务地址已变化，请重新连接验证。",
    "AstrBot Launcher 所属实例或服务地址已变化，请重新连接验证。",
    "本机服务所属关系已变化，请重新连接验证。",
    "连接档案已不存在，请重新连接验证。",
    "AstrBot Launcher 实例当前离线，消息未发送。" -> true
    else -> false
}

/** Convert only known protocol failures to display text; never reflect arbitrary server or secret text. */
internal fun safeFailureMessage(
    error: Throwable,
    failedStage: String? = null,
    fallback: String = "连接或服务错误；回复状态未确认。",
): String {
    if (error is WhaleTaskOutcomeUnknownException) return WHALE_TASK_CHECK_STATUS
    if (error is WhaleTaskLoginRequiredException) return "鲸鱼任务登录已失效，请重新登录后重试。"
    when (error.message) {
        "找不到任务接口，请检查 AstrBot 和任务插件版本。",
        "任务插件返回的会话编号无效。",
        "任务接口返回 HTTP 503。" -> return "任务接口尚不可用，请检查鲸鱼任务插件是否已启用并重载。"
        "当前 Dashboard 账号无权使用此任务会话，请检查插件绑定。" ->
            return "当前账号无权使用鲸鱼任务，请检查插件的管理账号绑定。"
    }
    if (error is SocketTimeoutException) return when {
        failedStage?.startsWith("上传语音") == true -> "语音上传超时。"
        failedStage?.startsWith("上传图片") == true -> "图片上传超时。"
        failedStage == "等待服务器响应失败" -> "等待服务器响应超时。"
        failedStage == "验证响应流失败" -> "验证响应流超时。"
        failedStage == "等待首段回复失败" -> "等待首段回复超时。"
        failedStage == "接收回复失败" -> "接收回复超时。"
        else -> "连接服务超时。"
    }
    if (error is InterruptedException) return when {
        failedStage?.startsWith("上传语音") == true -> "语音上传已中断。"
        failedStage?.startsWith("上传图片") == true -> "图片上传已中断。"
        failedStage == "等待服务器响应失败" -> "等待服务器响应时已中断。"
        failedStage == "等待首段回复失败" -> "等待首段回复时已中断。"
        failedStage == "接收回复失败" -> "接收回复时已中断。"
        else -> "请求已中断。"
    }
    val message = error.message.orEmpty()
    if (message in SAFE_FAILURES || message == CONTEXT_UNAVAILABLE || message == CONTEXT_CHANGED) return message
    if (Regex("(?:图片|语音)上传失败，服务器返回 HTTP [1-5][0-9]{2}。").matches(message)) return message
    if (Regex("服务器返回 HTTP [1-5][0-9]{2}\\.").matches(message)) return message
    if (Regex("(?:图片|语音)下载失败，服务器返回 HTTP [1-5][0-9]{2}。").matches(message)) return message
    return fallback
}

private fun safeAttachmentFailureMessage(error: Throwable, type: ReplyAttachmentType): String {
    if (type == ReplyAttachmentType.AUDIO &&
        (error is IllegalArgumentException || error is javax.sound.sampled.UnsupportedAudioFileException)) {
        return "语音文件损坏或格式不受支持。"
    }
    return safeFailureMessage(error, fallback = "获取或缓存${type.label}失败。")
}

private val SAFE_FAILURES = setOf(
    "鲸鱼任务登录目标已切换。",
    "任务请求中断，命令可能已执行，请先核对状态。",
    "无法连接本机 Dashboard，请检查 Launcher。",
    "密钥无效或已撤销。",
    "权限不足，请检查聊天权限。",
    "缺少文件上传权限，请为 API 密钥启用 file 权限。",
    "缺少图片下载权限，请为 API 密钥启用 file 权限。",
    "缺少语音下载权限，请为 API 密钥启用 file 权限。",
    "找不到对话接口，请检查 AstrBot 版本和服务地址。",
    "服务返回重定向，请直接填写最终服务地址。",
    "服务未返回对话事件流，请检查接口地址。",
    "AstrBot 报告对话失败，请在服务端检查配置。",
    "找不到回复图片，请检查附件是否仍然存在。",
    "图片下载接口未返回图片，请检查 AstrBot 服务。",
    "回复图片超过 20 MB 限制。",
    "服务返回了空图片。",
    "图片下载接口返回了错误信息，而不是图片。",
    "找不到回复语音，请检查附件是否仍然存在。",
    "语音下载接口未返回语音，请检查 AstrBot 服务。",
    "回复语音超过 20 MB 限制。",
    "服务返回了空语音。",
    "语音下载接口返回了错误信息，而不是语音。",
    "找不到文件上传接口，请检查 AstrBot 版本和服务地址。",
    "语音超过服务端大小限制。",
    "语音上传接口未返回 JSON，请检查 AstrBot 地址和版本。",
    "语音上传接口返回了无法识别的响应。",
    "语音上传失败，请检查文件权限和 API 密钥的文件权限。",
    "语音上传成功响应中缺少附件编号。",
    "图片超过服务端大小限制。",
    "图片超过服务端大小限制（512 MB）。",
    "无法读取所选图片。",
    "仅支持 PNG、JPEG、GIF、WebP、BMP 或 TIFF 图片。",
    "图片上传接口未返回 JSON，请检查 AstrBot 地址和版本。",
    "图片上传接口返回了无法识别的响应。",
    "图片上传失败，请检查文件权限和 API 密钥的文件权限。",
    "服务地址已变化，请重新连接验证后再发送。",
    "服务地址已变化，请重新连接验证后再获取图片。",
    "服务地址已变化，请重新连接验证。",
    "AstrBot Launcher 所属实例或服务地址已变化，请重新连接验证。",
    "本机服务所属关系已变化，请重新连接验证。",
    "连接档案已不存在，请重新连接验证。",
    "AstrBot Launcher 实例当前离线，消息未发送。",
    "手动本机 HTTP 地址需要重新输入密钥。",
    "无法连接服务。",
)
