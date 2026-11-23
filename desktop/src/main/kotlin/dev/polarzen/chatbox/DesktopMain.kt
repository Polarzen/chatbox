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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
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

    val dataDir = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "Chatbox") }
        ?: Path.of(System.getProperty("user.home"), ".chatbox")
    var lockChannel: FileChannel? = null
    var historyLock: java.nio.channels.FileLock? = null
    var lockFailure: Throwable? = null
    val lockAcquired = runCatching {
        Files.createDirectories(dataDir)
        lockChannel = FileChannel.open(
            dataDir.resolve("history.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        historyLock = lockChannel?.tryLock()
        historyLock != null
    }.onFailure { lockFailure = it }.getOrDefault(false)
    val store = if (lockAcquired) runCatching { DesktopStore(dataDir.resolve("history.json")) }.getOrNull() else null
    val credentials = DesktopCredentials(dataDir.resolve("credentials"))
    val keys = NamedKeys(dataDir, credentials)
    val replyImageCache = ReplyImageCache(dataDir.resolve("reply-images"))
    try {
        val appearanceStore = DesktopAppearanceStore(dataDir)
        application {
            var appearance by remember { mutableStateOf(appearanceStore.load()) }
            var appearanceError by remember { mutableStateOf<String?>(null) }
            val historySearchOpen = remember { mutableStateOf(false) }
            Window(
                onCloseRequest = ::exitApplication,
                title = "chatbox",
                onPreviewKeyEvent = { event ->
                    if (event.opensHistorySearch() && lockAcquired && store != null) {
                        historySearchOpen.value = true
                        true
                    } else false
                },
                state = rememberWindowState(size = DpSize(1000.dp, 760.dp)),
            ) {
                DesktopWindowEffects(window, appearance) { appearanceError = it }
                LaunchedEffect(window) {
                    window.minimumSize = Dimension(480, 400)
                }
                DesktopTheme(appearance.dark) {
                    Surface(Modifier.fillMaxSize()) {
                        when {
                            !lockAcquired && lockFailure == null -> Text("Chatbox 已在运行，请使用已打开的窗口。", Modifier.padding(16.dp))
                            !lockAcquired -> Text("无法锁定本地聊天历史文件，请检查数据目录权限。", Modifier.padding(16.dp))
                            store == null -> Text("无法读取本地聊天历史。请先备份历史文件，再检查数据目录。", Modifier.padding(16.dp))
                            else -> DesktopScreen(store, keys, replyImageCache, window, historySearchOpen) {
                                DesktopAppearanceControls(
                                    appearance,
                                    onPreview = { appearance = it },
                                    onCommit = { updated ->
                                        appearance = updated
                                        if (runCatching { appearanceStore.save(updated) }.isFailure) {
                                            appearanceError = "无法保存窗口外观设置。"
                                        }
                                    },
                                )
                            }
                        }
                    }
                    appearanceError?.let { message ->
                        AlertDialog(
                            onDismissRequest = { appearanceError = null },
                            text = { Text(message) },
                            confirmButton = { TextButton(onClick = { appearanceError = null }) { Text("关闭") } },
                        )
                    }
                }
            }
        }
    } finally {
        runCatching { historyLock?.release() }
        runCatching { lockChannel?.close() }
    }
}

private data class SessionKey(val profileId: String, val sessionId: String)

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

/** Production screen shared with the desktop UI harness. */
@Composable
internal fun DesktopScreen(
    store: DesktopStore,
    keys: NamedKeys,
    replyImageCache: ReplyImageCache,
    parentWindow: java.awt.Window? = null,
    historySearchOpen: MutableState<Boolean> = remember { mutableStateOf(false) },
    appearanceActions: @Composable () -> Unit = {},
) {
    val replyAudioCache = remember(replyImageCache) { ReplyAudioCache(replyImageCache.directory.resolveSibling("reply-audio")) }
    val connection = rememberDesktopConnection(store, keys)
    val sessionsRevision = remember { mutableIntStateOf(0) }
    val drafts = remember { mutableStateMapOf<SessionKey, TextFieldValue>() }
    val selectedImages = remember { mutableStateMapOf<SessionKey, List<Path>>() }
    val deletedSessionKeys = remember { ConcurrentHashMap.newKeySet<SessionKey>() }
    val chats = remember { mutableStateMapOf<SessionKey, Chat>() }
    val unsavedReplies = remember { mutableStateMapOf<SessionKey, Chat>() }
    fun latestChat(key: SessionKey): Chat {
        val persisted = store.sessionContent(key.profileId, key.sessionId)
        return unsavedReplies[key] ?: persisted
    }
    val notices = remember { mutableStateMapOf<SessionKey, String>() }
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
    var fetchingAttachments by remember { mutableStateOf<Set<ReplyAttachmentKey>>(emptySet()) }

    val profileId = connection.profileId
    var searchOpen by historySearchOpen
    var searchSerial by remember { mutableStateOf(0L) }
    var searchDestination by remember(profileId) { mutableStateOf<Pair<SessionKey, SearchHitNavigation>?>(null) }
    val selectedProfile = connection.profiles.firstOrNull { it.id == profileId }
    val sessions = remember(profileId, connection.profiles, sessionsRevision.intValue) {
        profileId.takeIf(String::isNotBlank)?.let { runCatching { store.sessions(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    val selectedSessionId = if (profileId.isBlank()) "" else
        sessions.firstOrNull { it.selected }?.id
            ?: sessions.firstOrNull()?.id.orEmpty()
    val currentKey = SessionKey(profileId, selectedSessionId)
    val storedChat = if (profileId.isNotBlank() && selectedSessionId.isNotBlank()) {
        chats[currentKey] ?: runCatching { store.sessionContent(profileId, selectedSessionId) }.getOrDefault(Chat(selectedSessionId))
    } else Chat("")
    val chat = sending?.takeIf { it.key == currentKey }?.visibleChat(storedChat) ?: storedChat
    val draft = drafts[currentKey] ?: TextFieldValue()
    val currentImages = selectedImages[currentKey].orEmpty()
    val isOffline = selectedProfile?.offlineOnly == true
    val isBusy = sending != null
    val currentOperation = sending?.takeIf { it.key == currentKey }
    val currentProgress = currentOperation?.let {
        EntryProgress(it.entryIndex, it.stage, it.stageStartedAtNanos)
    } ?: chat.entries.lastIndex.takeIf { index ->
        index >= 0 && (chat.entries[index].status.startsWith("发送失败：") || chat.entries[index].status.startsWith("未发送："))
    }?.let { index -> failedProgressByEntry[TimedEntryKey(currentKey, index)] }

    fun addValidatedImages(sessionKey: SessionKey, files: List<File>, invalidPathCount: Int = 0) {
        if (files.isEmpty() && invalidPathCount == 0) return
        val addition = addSourceImages(selectedImages[sessionKey].orEmpty(), files, invalidPathCount)
        if (addition.paths.isEmpty()) selectedImages.remove(sessionKey) else selectedImages[sessionKey] = addition.paths
        if (addition.notice == null) notices.remove(sessionKey) else notices[sessionKey] = addition.notice
    }

    fun chooseImages() {
        try {
            val chooser = JFileChooser().apply {
                dialogTitle = "选择图片（可多选）"
                isAcceptAllFileFilterUsed = false
                isMultiSelectionEnabled = true
                fileFilter = FileNameExtensionFilter("PNG / JPEG 图片", "png", "jpg", "jpeg")
            }
            if (chooser.showOpenDialog(parentWindow) == JFileChooser.APPROVE_OPTION) {
                val selected = chooser.selectedFiles.takeIf { it.isNotEmpty() }
                    ?.toList()
                    ?: listOfNotNull(chooser.selectedFile)
                addValidatedImages(currentKey, selected)
            }
        } catch (_: InvalidPathException) {
            addValidatedImages(currentKey, emptyList(), invalidPathCount = 1)
        }
    }

    fun invalidateOnFailure(target: DesktopSendTarget, failure: Throwable) {
        if (shouldInvalidateConnection(failure)) {
            SwingUtilities.invokeLater { connection.invalidateIfCurrent(target) }
        }
    }

    fun fetchReplyAttachment(target: DesktopSendTarget, key: ReplyAttachmentKey) {
        val sessionKey = SessionKey(key.profileId, key.sessionId)
        if (sessionKey in deletedSessionKeys || key in fetchingAttachments) return
        fetchingAttachments = fetchingAttachments + key
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
                fetchingAttachments = fetchingAttachments - key
                val failure = result.exceptionOrNull()
                if (failure == null) {
                    attachmentFailures = attachmentFailures - key
                    cacheGeneration = cacheGeneration + (key to ((cacheGeneration[key] ?: 0) + 1))
                } else {
                    attachmentFailures = attachmentFailures + (key to safeAttachmentFailureMessage(failure, key.type))
                }
            }
        }
    }

    fun retryReplyAttachment(key: ReplyAttachmentKey) {
        val sessionKey = SessionKey(key.profileId, key.sessionId)
        if (sessionKey != currentKey || sessionKey in deletedSessionKeys || isBusy || isOffline || !connection.connected || connection.verifying) return
        val target = runCatching { connection.snapshot() }.getOrNull()
        if (target == null || target.profileId != key.profileId) {
            notices[sessionKey] = "请先连接并验证当前服务，再获取回复${key.type.label}。"
            return
        }
        fetchReplyAttachment(target, key)
    }

    fun dispatch(questionText: String, images: List<Path>, retryEntry: Entry? = null, retryKey: SessionKey? = null) {
        val clickedAtNanos = System.nanoTime()
        val targetKey = retryKey ?: currentKey
        if (sending != null || targetKey.profileId.isBlank() || targetKey.sessionId.isBlank()) return
        if (retryKey != null && retryKey != currentKey) return
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
        val query = questionText.trim()
        val imageValidation = inspectSourceImages(images)
        if (imageValidation.rejected.isNotEmpty()) {
            notices[targetKey] = "图片无法发送：${formatRejectedImages(imageValidation.rejected)}。"
            return
        }
        val validatedImages = imageValidation.valid
        if (query.isEmpty() && validatedImages.isEmpty()) return

        val references = validatedImages.map(Path::toString)
        val entry = Entry(
            question = query,
            answer = "",
            status = "发送中",
            attachmentReference = references.firstOrNull(),
            attachmentDisplayName = validatedImages.firstOrNull()?.fileName?.toString(),
            attachmentReferences = references,
        )
        val current = runCatching { latestChat(targetKey) }
            .getOrElse {
                notices[targetKey] = "无法读取本地会话，消息未发送。"
                return
            }
        val pending = current.copy(entries = current.entries + entry)
        val persisted = runCatching { store.saveSessionContent(targetKey.profileId, pending) }
        if (persisted.isFailure) {
            val failure = persisted.exceptionOrNull()!!
            invalidateOnFailure(target, failure)
            notices[targetKey] = if (shouldInvalidateConnection(failure)) safeFailureMessage(failure)
                else "无法保存本地会话，消息未发送。"
            return
        }

        unsavedReplies.remove(targetKey)
        chats[targetKey] = pending
        sessionsRevision.intValue++
        notices.remove(targetKey)
        if (retryEntry == null) {
            if (draft.text == questionText) drafts[targetKey] = TextFieldValue()
            if (selectedImages[targetKey] == images) selectedImages.remove(targetKey)
        }
        val operationId = ++nextOperationId
        val entryIndex = pending.entries.lastIndex
        val operation = SendOperation(
            operationId, targetKey, target, entryIndex, "准备中", "准备请求失败", clickedAtNanos,
        )
        val timing = DesktopRequestTiming(operationId, qaTimingLog, clickedAtNanos)
        timingByEntry[TimedEntryKey(targetKey, entryIndex)] = timing
        sending = operation
        activeOperationId.set(operationId)
        val caption = query
        thread(isDaemon = true, name = "chatbox-request") {
            fun operationIsActive() = !operation.cancellation.get() && activeOperationId.get() == operationId
            var observedFailureStage = operation.failureStage

            fun observeTransportEvent(event: AstrBotTransportEvent) {
                if (!operationIsActive()) throw CancellationException("会话已删除。")
                val stageStartedAt = System.nanoTime()
                timing.onTransportEvent(event)
                val stage = progressStage(event)
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
                    if (key != null && fetch) fetchReplyAttachment(target, key)
                }
            }

            val result = runCatching {
                check(operationIsActive()) { "会话已删除。" }
                target.checkTarget()
                check(operationIsActive()) { "会话已删除。" }
                requestStarted = true
                if (validatedImages.isEmpty()) {
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
                        onText = { delta ->
                            if (!operationIsActive()) return@sendToAstrBot
                            answer = delta
                            SwingUtilities.invokeLater {
                                if (!operationIsActive()) return@invokeLater
                                val active = sending
                                if (active?.id == operationId) sending = active.copy(answer = delta)
                            }
                        },
                    )
                } else {
                    sendImagesToAstrBot(
                        target.address,
                        target.token,
                        store.username,
                        targetKey.sessionId,
                        validatedImages,
                        caption,
                        allowLoopbackHttp = target.allowLoopbackHttp,
                        onReplyImage = { id, offset -> receiveAttachment(ReplyAttachmentType.IMAGE, id, offset) },
                        onReplyAudio = { id, offset -> receiveAttachment(ReplyAttachmentType.AUDIO, id, offset) },
                        onEvent = ::observeTransportEvent,
                        onText = { delta ->
                            if (!operationIsActive()) return@sendImagesToAstrBot
                            answer = delta
                            SwingUtilities.invokeLater {
                                if (!operationIsActive()) return@invokeLater
                                val active = sending
                                if (active?.id == operationId) sending = active.copy(answer = delta)
                            }
                        },
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
                status = if (result.isSuccess) "已完成" else
                    (if (requestStarted) "发送失败：" else "未发送：") + observedFailureStage + "：" +
                        safeFailureMessage(failure!!, observedFailureStage),
            ))
            val saved = runCatching { store.saveSessionContent(targetKey.profileId, completed) }
            if (failure != null && operationIsActive()) invalidateOnFailure(target, failure)
            SwingUtilities.invokeLater {
                if (!operationIsActive()) return@invokeLater
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
                }
            }
        }
    }

    fun selectSearchHit(hit: HistoryHit, query: String): Boolean {
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
        searchSerial++
        searchDestination = if (hit.side == HistorySide.TITLE) null else key to SearchHitNavigation(
            searchSerial, hit.entryIndex, hit.side == HistorySide.ASSISTANT, hit.start, hit.endExclusive,
        )
        sessionsRevision.intValue++
        return true
    }

    val canCreate = profileId.isNotBlank() && selectedProfile != null && !selectedProfile.offlineOnly
    val canSend = selectedSessionId.isNotBlank() && !isBusy && !isOffline && connection.connected && !connection.verifying &&
        (draft.text.isNotBlank() || currentImages.isNotEmpty())
    val canRetry = !isBusy && !isOffline && connection.connected && !connection.verifying

    Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.opensHistorySearch()) {
            searchOpen = true
            true
        } else false
    }) {
        DesktopChatLayout(
            searchSelectionSerial = searchSerial,
            searchNavigation = searchDestination?.takeIf { it.first == currentKey }?.second,
            profileId = profileId,
            sessions = sessions,
            chat = chat,
            onSelectSession = { sessionId ->
                if (profileId.isNotBlank()) {
                    runCatching {
                        store.selectSession(profileId, sessionId)
                        searchDestination = null
                        val key = SessionKey(profileId, sessionId)
                        chats[key] = latestChat(key)
                        sessionsRevision.intValue++
                    }.onFailure { notices[currentKey] = "无法打开所选会话。" }
                }
            },
            onDeleteSession = { sessionId ->
                if (profileId.isNotBlank() && sessions.any { it.id == sessionId }) {
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
                            selectedImages.remove(key)
                            drafts.remove(key)
                            notices.remove(key)
                            fetchingAttachments = fetchingAttachments.filterNot { it.profileId == key.profileId && it.sessionId == key.sessionId }.toSet()
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
                if (canCreate) {
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
                IconButton(
                    onClick = { searchOpen = true },
                    modifier = Modifier.size(36.dp).testTag("history-search-open"),
                ) { Icon(Icons.Filled.Search, contentDescription = "搜索聊天记录 (Ctrl+K)") }
                appearanceActions()
                DesktopConnectionControls(connection)
            },
            draft = draft,
            onDraft = { value -> drafts[currentKey] = value },
            selectedImage = currentImages.firstOrNull(),
            selectedImages = currentImages,
            onChooseImage = ::chooseImages,
            onAddImages = { files -> addValidatedImages(currentKey, files) },
            onRemoveImage = { selectedImages.remove(currentKey) },
            onRemoveImagePath = { path ->
                val remaining = selectedImages[currentKey].orEmpty().filterNot { it == path }
                if (remaining.isEmpty()) selectedImages.remove(currentKey) else selectedImages[currentKey] = remaining
            },
            onSend = { dispatch(draft.text, currentImages) },
            canSend = canSend,
            notice = notices[currentKey],
            onRetry = { entry ->
                if (canRetry && chat.entries.any { it === entry || it == entry }) {
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
                    retrying = fetchingAttachments,
                    retryEnabled = canRetry,
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
                        val references = pendingRetry.entry.attachmentReferences.ifEmpty {
                            listOfNotNull(pendingRetry.entry.attachmentReference)
                        }
                        val images = references.mapNotNull { runCatching { Path.of(it) }.getOrNull() }
                        if (images.size != references.size) {
                            notices[pendingRetry.key] = "无法读取原始图片，请确认文件仍然存在。"
                        } else {
                            dispatch(pendingRetry.entry.question, images, pendingRetry.entry, pendingRetry.key)
                        }
                    }, enabled = canRetry) { Text("确认重发") }
                },
                dismissButton = { TextButton(onClick = { retryConfirmation = null }) { Text("取消") } },
            )
        }
    }
}

private fun inspectSourceImages(paths: List<Path>): SourceImageValidation {
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
            size?.let { it > MAX_SOURCE_IMAGE_BYTES } == true -> "超过 512 MiB"
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
    val validation = inspectSourceImages(paths)
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

private fun progressStage(event: AstrBotTransportEvent): ProgressStage = when (event) {
    is AstrBotTransportEvent.UploadStarted -> ProgressStage(
        "正在上传图片 ${event.index + 1}/${event.count}",
        "上传图片 ${event.index + 1}/${event.count} 失败",
    )
    is AstrBotTransportEvent.UploadFinished -> ProgressStage(
        "图片已上传 ${event.index + 1}/${event.count}",
        "准备发送消息失败",
    )
    AstrBotTransportEvent.ChatStarted -> ProgressStage("等待服务器响应", "等待服务器响应失败")
    AstrBotTransportEvent.HeadersReceived -> ProgressStage("验证响应流", "验证响应流失败")
    AstrBotTransportEvent.StreamReady -> ProgressStage("等待首段回复", "等待首段回复失败")
    AstrBotTransportEvent.FirstContent -> ProgressStage("正在接收回复", "接收回复失败")
    AstrBotTransportEvent.Completed -> ProgressStage("整理回复", "整理回复失败")
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
private fun safeFailureMessage(
    error: Throwable,
    failedStage: String? = null,
    fallback: String = "连接或服务错误；回复状态未确认。",
): String {
    if (error is SocketTimeoutException) return when {
        failedStage?.startsWith("上传图片") == true -> "图片上传超时。"
        failedStage == "等待服务器响应失败" -> "等待服务器响应超时。"
        failedStage == "验证响应流失败" -> "验证响应流超时。"
        failedStage == "等待首段回复失败" -> "等待首段回复超时。"
        failedStage == "接收回复失败" -> "接收回复超时。"
        else -> "连接服务超时。"
    }
    if (error is InterruptedException) return when {
        failedStage?.startsWith("上传图片") == true -> "图片上传已中断。"
        failedStage == "等待服务器响应失败" -> "等待服务器响应时已中断。"
        failedStage == "等待首段回复失败" -> "等待首段回复时已中断。"
        failedStage == "接收回复失败" -> "接收回复时已中断。"
        else -> "请求已中断。"
    }
    val message = error.message.orEmpty()
    if (message in SAFE_FAILURES) return message
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
