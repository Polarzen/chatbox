package dev.polarzen.chatbox

import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
    val histories = connection.profiles.filterNot { it.offlineOnly || it.kind == "shared" }.associate { profile ->
        profile.id to key(profile.id) { remember(store) { DesktopProfileHistory(store, profile.id) } }
    }
    val profileHistory = histories[profileId]
    val profileBound = profileHistory?.bound == true
    val profileRevision = profileHistory?.revision ?: 0L
    var profilePanel by remember(profileId) { mutableStateOf(false) }
    val sessions = remember(profileId, connection.profiles, sessionsRevision.intValue, profileRevision) {
        profileId.takeIf(String::isNotBlank)?.let { runCatching { store.sessions(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    val selectedSessionId = if (profileId.isBlank()) "" else
        sessions.firstOrNull { it.selected }?.id
            ?: sessions.firstOrNull()?.id.orEmpty()
    val currentKey = SessionKey(profileId, selectedSessionId)
    val standalone = selectedProfile?.kind == "shared"
    val sourceBase = remember(selectedProfile?.address, connection.address, standalone, selectedProfile?.offlineOnly) {
        if (standalone) "" else selectedProfile?.takeUnless { it.offlineOnly }?.let { profile ->
            runCatching {
                val base = DesktopStore.normalizeAddress(sharedAddress(connection.address, allowLoopback = true))
                base.takeIf { it == DesktopStore.normalizeAddress(profile.address) }
            }.getOrNull()
        }
    }
    val sharedStorage = remember(store, currentKey, sourceBase) {
        if (sourceBase != null && selectedSessionId.isNotEmpty()) DesktopSharedStorage(store, currentKey, sourceBase) else null
    }
    var requestedShared by remember { mutableStateOf<SessionKey?>(null) }
    var sharedOpen by remember(sharedStorage, windowEvents?.visible, composerFocusSerial, profileBound) {
        mutableStateOf(!profileBound && sharedStorage != null && (sharedStorage.bound || requestedShared == currentKey))
    }
    val boundToShared = !profileBound && sharedStorage?.bound == true
    var refreshOnFocus by remember { mutableIntStateOf(0) }
    DisposableEffect(parentWindow, sharedStorage, profileBound, composerFocusSerial, windowEvents?.visible) {
        val listener = object : java.awt.event.WindowAdapter() {
            override fun windowGainedFocus(event: java.awt.event.WindowEvent) {
                if (event.oppositeWindow == null) {
                    if (profileBound) refreshOnFocus++ else if (sharedStorage?.bound == true) sharedOpen = true
                }
            }
        }
        parentWindow?.addWindowFocusListener(listener)
        onDispose { parentWindow?.removeWindowFocusListener(listener) }
    }
    val launcherMayImport = !profileId.startsWith("launcher:") ||
        LauncherInstanceResolver.currentUrl(profileId.removePrefix("launcher:"), connection.instances) == sourceBase
    val verifiedSource = sourceBase?.takeIf {
        !standalone && connection.connected && !connection.verifying && launcherMayImport
    }
    for ((id, history) in histories) key(id) {
        if (history.bound) {
            val active = id == profileId
            val source by rememberUpdatedState(verifiedSource.takeIf { active })
            val refresh = remember(history) { Channel<Unit>(Channel.CONFLATED) }
            if (active) {
                LaunchedEffect(refresh, currentKey, sessionsRevision.intValue, source, refreshOnFocus, windowEvents?.visible) {
                    refresh.trySend(Unit)
                }
            }
            // Keep the publisher alive while the user works in another profile. Navigation
            // can request an earlier refresh but cannot cancel an upload already in flight.
            LaunchedEffect(history) {
                while (isActive) {
                    withTimeoutOrNull(5_000) { refresh.receive() }
                    history.refresh(source)
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

    val storedChat = (if (profileBound) null else chats[currentKey]) ?: remember(currentKey, sessionsRevision.intValue, profileRevision, profileBound) {
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
    val isOffline = selectedProfile?.offlineOnly == true
    fun hasScreenModal() = profilePanel || searchOpen || whaleLoginTarget != null || retryConfirmation != null || closeError != null || closingNow()
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
        pending: Chat, attachments: List<Path>, taskSession: WhaleTaskSession?,
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

    fun dispatch(retryEntry: Entry? = null, targetKey: SessionKey = currentKey) {
        val clickedAtNanos = System.nanoTime()
        if (targetKey != currentKey || editor == null || sharedOpen || boundToShared ||
            composerLockedNow() || sending != null || preparing != null ||
            composer.recordingKey == targetKey || (retryEntry == null && editor.field.composition != null)
        ) return
        if (profileHistory != null && profileHistory.bound) {
            val question = editor.draft.text.trim()
            if (retryEntry != null || question.isEmpty() || editor.draft.attachments.isNotEmpty() ||
                composer.pendingImport(targetKey) != null || profileHistory.reason(targetKey.sessionId).isNotBlank() ||
                profileHistory.hasPending(targetKey.sessionId)) return
            val revision = editor.revision
            editor.commitInProgress = true
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    notices.remove(targetKey)
                    if (profileHistory.send(targetKey.sessionId, question, revision)) composer.consumed(targetKey, revision)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (targetKey !in deletedSessionKeys) notices[targetKey] = "共享发送未获确认，请先核对原消息状态。"
                } finally {
                    editor.commitInProgress = false
                    preparing = null
                }
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
                if (retryEntry == null) {
                    editor.commitInProgress = true
                    locked = true
                }
                val references = paths.map(Path::toString)
                val entry = Entry(
                    question = query, answer = "", status = "发送中",
                    attachmentReference = references.firstOrNull(),
                    attachmentDisplayName = paths.firstOrNull()?.fileName?.toString(),
                    attachmentReferences = references,
                )
                val pending = withContext(Dispatchers.IO) {
                    val stored = current ?: store.sessionContent(targetKey.profileId, targetKey.sessionId)
                    val updated = stored.copy(entries = stored.entries + entry)
                    if (retryEntry == null) store.commitDraft(targetKey, revision, updated)
                    else store.saveSessionContent(targetKey.profileId, updated)
                    updated
                }
                if (targetKey in deletedSessionKeys) return@launch
                if (retryEntry == null) composer.consumed(targetKey, revision)
                startRequest(clickedAtNanos, targetKey, target, entry, pending, paths, taskSession)
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

    val canCreate = profileId.isNotBlank() && selectedProfile != null && (!selectedProfile.offlineOnly || standalone) && !composerLocked
    val profileReason = profileHistory?.takeIf { profileBound }?.reason(selectedSessionId).orEmpty()
    val profilePending = profileBound && profileHistory?.hasPending(selectedSessionId) == true
    val sendRouteReady = if (profileBound) profileReason.isBlank() && !profilePending &&
        currentImages.isEmpty() && composer.pendingImport(currentKey) == null else connection.connected && !connection.verifying
    val canSend = !boundToShared && selectedSessionId.isNotBlank() && !isBusy && !isOffline && sendRouteReady &&
        editor != null && draft.composition == null && composer.recordingKey != currentKey &&
        (draft.text.isNotBlank() || currentImages.isNotEmpty() || composer.pendingImport(currentKey) != null)
    val canRetry = !profileBound && !boundToShared && !isBusy && !isOffline && connection.connected && !connection.verifying && composer.recordingKey != currentKey
    val canOpenProfilePanel = profileHistory != null && !isBusy && composer.recordingKey == null &&
        (profileBound || verifiedSource != null && unsavedReplies.keys.none { it.profileId == profileId })
    val retryingAttachments = fetchingAttachments.keys.toSet()

    Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (!sharedOpen && event.opensHistorySearch()) {
            searchOpen = true
            true
        } else false
    }) {
        if (sharedOpen && sharedStorage != null && sourceBase != null) {
            key(sharedStorage) {
                val seed = remember(chat, sourceBase) { desktopSharedSeed(sourceBase, store.username, chat) }
                val browseLocal = {
                    requestedShared = null
                    sharedOpen = false
                }
                Column(Modifier.fillMaxSize()) {
                    if (sharedStorage.bound) TextButton(onClick = browseLocal, modifier = Modifier.testTag("shared-chat-browse-local")) {
                        Text("返回本地历史")
                    }
                    if (profileHistory != null) TextButton(
                        onClick = { profilePanel = true },
                        enabled = canOpenProfilePanel,
                        modifier = Modifier.testTag("profile-share-open"),
                    ) { Text("与手机共享此档案的会话") }
                    SharedChatPanel(seed, sharedStorage, allowLoopback = true, onClose = browseLocal)
                }
            }
        } else DesktopChatLayout(
            composerFocusSerial = composerFocusSerial,
            replyNavigation = replyDestination?.takeIf { it.first == currentKey }?.second,
            searchSelectionSerial = searchSerial,
            searchNavigation = searchDestination?.takeIf { it.first == currentKey }?.second,
            profileId = profileId,
            sessions = sessions,
            chat = chat,
            onSelectSession = { sessionId ->
                if (!composerLockedNow() && profileId.isNotBlank()) {
                    runCatching {
                        store.selectSession(profileId, sessionId)
                        requestedShared = null
                        if (sessionId == currentKey.sessionId && boundToShared) sharedOpen = true
                        searchDestination = null
                        val key = SessionKey(profileId, sessionId)
                        chats[key] = latestChat(key)
                        sessionsRevision.intValue++
                    }.onFailure { notices[currentKey] = "无法打开所选会话。" }
                }
            },
            onDeleteSession = { sessionId ->
                if (!composerLockedNow() && profileId.isNotBlank() && sessions.any { it.id == sessionId }) {
                    val key = SessionKey(profileId, sessionId)
                    runCatching { store.deleteSession(profileId, sessionId) }
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
            onRenameSession = { sessionId, name ->
                runCatching { store.renameSession(profileId, sessionId, name) }
                    .onSuccess { sessionsRevision.intValue++ }
                    .exceptionOrNull()?.let { safeHistoryFailureMessage(it, "写入") }
            },
            onNewSession = {
                if (canCreate && !composerLockedNow()) {
                    runCatching {
                        val created = store.createSession(profileId)
                        searchDestination = null
                        chats[SessionKey(profileId, created.session)] = created
                        sessionsRevision.intValue++
                        retryConfirmation = null
                    }.onFailure { notices[currentKey] = "无法创建新会话。" }
                }
            },
            canCreate = canCreate,
            topActions = {
                TextButton(
                    enabled = !isBusy && composer.recordingKey == null && (profileBound || profileId.isEmpty() ||
                        (sourceBase != null && (boundToShared || launcherMayImport))),
                    modifier = Modifier.testTag("shared-chat-open"),
                    onClick = {
                        if (sending != null || preparing != null || composer.recordingKey != null || composerLockedNow()) return@TextButton
                        if (profileBound) {
                            profilePanel = true
                        } else if (sharedStorage != null) {
                            requestedShared = currentKey
                            sharedOpen = true
                        } else runCatching {
                            val profile = selectedProfile ?: store.selectStandaloneSharedProfile()
                            val session = profile.currentSession.ifEmpty { store.createSession(profile.id).session }
                            requestedShared = SessionKey(profile.id, session)
                            connection.adoptSelectedProfileForReply(checkNotNull(store.profile(profile.id)))
                            sessionsRevision.intValue++
                        }.onFailure { notices[currentKey] = "无法打开共享会话，请检查本地档案。" }
                    },
                ) { Text("共享") }
                IconButton(
                    onClick = { searchOpen = true },
                    modifier = Modifier.size(36.dp).testTag("history-search-open"),
                ) { Icon(Icons.Filled.Search, contentDescription = "搜索聊天记录 (Ctrl+K)") }
                if (whaleSession != null) {
                    TextButton(onClick = {
                        whaleSession = null
                        notices[currentKey] = "已退出鲸鱼任务。"
                    }, enabled = !isBusy) { Text("退出鲸鱼任务") }
                }
                appearanceActions()
                DesktopConnectionControls(connection, onShareProfile = if (canOpenProfilePanel) ({ profilePanel = true }) else null)
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
            onPasteAttachment = { composerLockedNow() || (!profileBound && selectedSessionId.isNotBlank() && composer.paste(currentKey)) },
            canRecord = !profileBound && selectedSessionId.isNotBlank() && editor?.commitInProgress == false && composer.recordingKey == null && !composerLocked,
            recordingMillis = composer.elapsed.takeIf { composer.recordingKey == currentKey },
            onStartRecording = { if (!composerLockedNow()) composer.startMic(currentKey) },
            onStopRecording = { if (!composerLockedNow()) composer.stopMic(currentKey) },
            onCancelRecording = { if (!composerLockedNow()) composer.stopMic(currentKey, discard = true) },
            onSend = { dispatch() },
            canSend = canSend,
            profileTextOnly = profileBound,
            onRecoverPending = if (profilePending && !isBusy) ({
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        notices.remove(currentKey)
                        profileHistory.retry(selectedSessionId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        notices[currentKey] = "暂时无法核对共享消息，请检查连接后重试。"
                    } finally { preparing = null }
                }
                if (preparing == null) { preparing = job; job.start() } else job.cancel()
            }) else null,
            notice = if (loadedDraft.isFailure) "本地草稿无法读取。" else notices[currentKey]
                ?: when {
                    profileBound && currentImages.isNotEmpty() -> "共享续聊只支持文字。此草稿仍保留附件，移除附件后可发送。"
                    profileReason.isNotBlank() -> profileReason
                    profilePending -> "上次发送尚待核对。重试会沿用原消息编号。"
                    profileBound -> profileHistory?.notice
                    boundToShared -> "此会话已共享。点击“共享”查看最新消息或发送。"
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
                    profileId = currentKey.profileId,
                    sessionId = currentKey.sessionId,
                    answer = item.answer,
                    attachments = item.replyAttachments,
                    imageCache = replyImageCache,
                    audioCache = replyAudioCache,
                    cacheGeneration = cacheGeneration,
                    failures = attachmentFailures,
                    retrying = retryingAttachments,
                    retryEnabled = canRetry || profileBound && connection.connected && !isBusy && !connection.verifying,
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

        if (profilePanel && profileHistory != null && selectedProfile != null) {
            DesktopProfilePanel(profileHistory, profileDisplayName(selectedProfile), sessions.size, verifiedSource,
                onOpenRoom = if (sharedStorage?.bound == true) ({
                    profilePanel = false
                    sharedOpen = true
                }) else null,
                onDismiss = { profilePanel = false })
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
private fun attachmentProblem(paths: List<Path>, mediaDirectory: Path): String? {
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
    if (message in SAFE_FAILURES) return message
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
