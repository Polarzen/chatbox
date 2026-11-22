package dev.polarzen.chatbox

import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "chatbox",
                state = rememberWindowState(size = DpSize(1000.dp, 760.dp)),
            ) {
                LaunchedEffect(window) {
                    window.minimumSize = Dimension(480, 400)
                }
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        when {
                            !lockAcquired && lockFailure == null -> Text("Chatbox 已在运行，请使用已打开的窗口。", Modifier.padding(16.dp))
                            !lockAcquired -> Text("无法锁定本地聊天历史文件，请检查数据目录权限。", Modifier.padding(16.dp))
                            store == null -> Text("无法读取本地聊天历史。请先备份历史文件，再检查数据目录。", Modifier.padding(16.dp))
                            else -> DesktopScreen(store, keys, replyImageCache)
                        }
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
    val pendingEntry: Entry,
    val answer: String = "",
)

private data class RetryConfirmation(val key: SessionKey, val entry: Entry)

/** Production screen shared with the desktop UI harness. */
@Composable
internal fun DesktopScreen(store: DesktopStore, keys: NamedKeys, replyImageCache: ReplyImageCache) {
    val connection = rememberDesktopConnection(store, keys)
    val sessionsRevision = remember { mutableIntStateOf(0) }
    val drafts = remember { mutableStateMapOf<SessionKey, TextFieldValue>() }
    val selectedImages = remember { mutableStateMapOf<SessionKey, Path>() }
    val chats = remember { mutableStateMapOf<SessionKey, Chat>() }
    val notices = remember { mutableStateMapOf<SessionKey, String>() }
    var sending by remember { mutableStateOf<SendOperation?>(null) }
    var retryConfirmation by remember { mutableStateOf<RetryConfirmation?>(null) }
    var nextOperationId by remember { mutableIntStateOf(0) }
    var cacheGeneration by remember { mutableStateOf<Map<ReplyImageKey, Int>>(emptyMap()) }
    var imageFailures by remember { mutableStateOf<Map<ReplyImageKey, String>>(emptyMap()) }
    var retryingImages by remember { mutableStateOf<Set<ReplyImageKey>>(emptySet()) }

    val profileId = connection.profileId
    val selectedProfile = connection.profiles.firstOrNull { it.id == profileId }
    val sessions = remember(profileId, connection.profiles, sessionsRevision.intValue) {
        profileId.takeIf(String::isNotBlank)?.let { runCatching { store.sessions(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    val selectedSessionId = if (profileId.isBlank()) "" else
        sessions.firstOrNull { it.selected }?.id
            ?: sessions.firstOrNull()?.id.orEmpty()
    val currentKey = SessionKey(profileId, selectedSessionId)
    val chat = if (profileId.isNotBlank() && selectedSessionId.isNotBlank()) {
        chats[currentKey] ?: runCatching { store.sessionContent(profileId, selectedSessionId) }.getOrDefault(Chat(selectedSessionId))
    } else Chat("")
    val draft = drafts[currentKey] ?: TextFieldValue()
    val selectedImage = selectedImages[currentKey]
    val isOffline = selectedProfile?.offlineOnly == true
    val isBusy = sending != null

    fun chooseImage() {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择图片"
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter("PNG / JPEG 图片", "png", "jpg", "jpeg")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            selectedImages[currentKey] = chooser.selectedFile.toPath()
        }
    }

    fun invalidateOnFailure(target: DesktopSendTarget, failure: Throwable) {
        if (shouldInvalidateConnection(failure)) {
            SwingUtilities.invokeLater { connection.invalidateIfCurrent(target) }
        }
    }

    fun retryReplyImage(sessionKey: SessionKey, attachmentId: String) {
        if (sessionKey != currentKey || isBusy || isOffline || !connection.connected || connection.verifying) return
        val target = runCatching { connection.snapshot() }.getOrNull()
        if (target == null || target.profileId != sessionKey.profileId) {
            notices[sessionKey] = "请先连接并验证当前服务，再获取回复图片。"
            return
        }
        val imageKey = ReplyImageKey(sessionKey.profileId, sessionKey.sessionId, attachmentId)
        if (imageKey in retryingImages) return
        retryingImages = retryingImages + imageKey
        thread(isDaemon = true, name = "chatbox-reply-image-retry") {
            val result = runCatching {
                target.checkTarget()
                val bytes = downloadReplyImage(target.address, target.token, attachmentId, target.allowLoopbackHttp)
                target.checkTarget()
                replyImageCache.save(sessionKey.profileId, attachmentId, bytes)
            }
            result.exceptionOrNull()?.let { invalidateOnFailure(target, it) }
            SwingUtilities.invokeLater {
                retryingImages = retryingImages - imageKey
                val failure = result.exceptionOrNull()
                if (failure == null) {
                    imageFailures = imageFailures - imageKey
                    cacheGeneration = cacheGeneration + (imageKey to ((cacheGeneration[imageKey] ?: 0) + 1))
                } else {
                    imageFailures = imageFailures + (imageKey to safeFailureMessage(failure))
                }
            }
        }
    }

    fun dispatch(questionText: String, image: Path?, retryEntry: Entry? = null, retryKey: SessionKey? = null) {
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
        if (query.isEmpty() && image == null) return
        if (image != null) {
            val extension = image.fileName.toString().substringAfterLast('.', "").lowercase()
            if (extension !in setOf("png", "jpg", "jpeg") || !Files.isRegularFile(image) || !Files.isReadable(image)) {
                notices[targetKey] = "请选择可读取的 PNG 或 JPEG 图片。"
                return
            }
        }

        val entry = Entry(query, "", "发送中", image?.toString(), image?.fileName?.toString())
        val current = runCatching { store.sessionContent(targetKey.profileId, targetKey.sessionId) }
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

        chats[targetKey] = pending
        sessionsRevision.intValue++
        notices.remove(targetKey)
        if (retryEntry == null) {
            if (draft.text == questionText) drafts[targetKey] = TextFieldValue()
            if (selectedImages[targetKey] == image) selectedImages.remove(targetKey)
        }
        val operationId = ++nextOperationId
        sending = SendOperation(operationId, targetKey, target, entry)
        val caption = query
        thread(isDaemon = true, name = "chatbox-request") {
            var answer = ""
            var requestStarted = false
            val receivedImages = mutableListOf<ReplyImage>()
            val fetchedAttachments = mutableSetOf<String>()

            fun receiveReplyImage(attachmentId: String?, textOffset: Int) {
                val images = synchronized(receivedImages) {
                    receivedImages += ReplyImage(attachmentId, textOffset)
                    receivedImages.toList()
                }
                val updated = pending.copy(entries = pending.entries.dropLast(1) + entry.copy(answer = answer, replyImages = images))
                val save = runCatching { store.saveSessionContent(targetKey.profileId, updated) }
                val imageKey = attachmentId?.let { ReplyImageKey(targetKey.profileId, targetKey.sessionId, it) }
                SwingUtilities.invokeLater {
                    if (imageKey != null) retryingImages = retryingImages + imageKey
                    if (save.isFailure) notices[targetKey] = "本地保存失败；收到的图片可能无法在重启后恢复。"
                }
                if (attachmentId == null || !fetchedAttachments.add(attachmentId)) return
                thread(isDaemon = true, name = "chatbox-reply-image") {
                    val fetchResult = runCatching {
                        target.checkTarget()
                        val bytes = downloadReplyImage(target.address, target.token, attachmentId, target.allowLoopbackHttp)
                        target.checkTarget()
                        replyImageCache.save(targetKey.profileId, attachmentId, bytes)
                    }
                    fetchResult.exceptionOrNull()?.let { invalidateOnFailure(target, it) }
                    SwingUtilities.invokeLater {
                        if (imageKey != null) {
                            retryingImages = retryingImages - imageKey
                            val failure = fetchResult.exceptionOrNull()
                            if (failure == null) {
                                imageFailures = imageFailures - imageKey
                                cacheGeneration = cacheGeneration + (imageKey to ((cacheGeneration[imageKey] ?: 0) + 1))
                            } else {
                                imageFailures = imageFailures + (imageKey to safeFailureMessage(failure))
                            }
                        }
                    }
                }
            }

            val result = runCatching {
                target.checkTarget()
                requestStarted = true
                if (image == null) {
                    sendToAstrBot(
                        target.address,
                        target.token,
                        store.username,
                        targetKey.sessionId,
                        query,
                        allowLoopbackHttp = target.allowLoopbackHttp,
                        onReplyImage = ::receiveReplyImage,
                        onText = { delta ->
                            answer = delta
                            SwingUtilities.invokeLater {
                                val active = sending
                                if (active?.id == operationId) sending = active.copy(answer = delta)
                            }
                        },
                    )
                } else {
                    sendImageToAstrBot(
                        target.address,
                        target.token,
                        store.username,
                        targetKey.sessionId,
                        image,
                        caption,
                        allowLoopbackHttp = target.allowLoopbackHttp,
                        onReplyImage = ::receiveReplyImage,
                        onText = { delta ->
                            answer = delta
                            SwingUtilities.invokeLater {
                                val active = sending
                                if (active?.id == operationId) sending = active.copy(answer = delta)
                            }
                        },
                    )
                }
            }
            val imageList = synchronized(receivedImages) { receivedImages.toList() }
            val failure = result.exceptionOrNull()
            val completed = pending.copy(entries = pending.entries.dropLast(1) + entry.copy(
                answer = answer,
                replyImages = imageList,
                status = if (result.isSuccess) "已完成" else
                    (if (requestStarted) "发送失败：" else "未发送：") + safeFailureMessage(failure!!),
            ))
            val saved = runCatching { store.saveSessionContent(targetKey.profileId, completed) }
            if (failure != null) invalidateOnFailure(target, failure)
            SwingUtilities.invokeLater {
                chats[targetKey] = completed
                sessionsRevision.intValue++
                val operation = sending
                if (operation?.id == operationId) {
                    sending = null
                    when {
                        saved.isFailure -> notices[targetKey] = "本地保存失败，请勿直接重发。"
                        result.isSuccess && answer.isEmpty() && imageList.isEmpty() ->
                            notices[targetKey] = "回复已结束，但没有可显示的内容。"
                        result.isSuccess -> notices.remove(targetKey)
                        else -> notices[targetKey] = safeFailureMessage(failure!!)
                    }
                }
            }
        }
    }

    val canCreate = profileId.isNotBlank() && selectedProfile != null && !selectedProfile.offlineOnly
    val canSend = !isBusy && !isOffline && connection.connected && !connection.verifying &&
        (draft.text.isNotBlank() || selectedImage != null)
    val canRetry = !isBusy && !isOffline && connection.connected && !connection.verifying

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            DesktopChatLayout(
                sessions = sessions,
                chat = chat,
                onSelectSession = { sessionId ->
                    if (profileId.isNotBlank()) {
                        runCatching {
                            store.selectSession(profileId, sessionId)
                            val key = SessionKey(profileId, sessionId)
                            val selected = chats[key].takeIf { sending?.key == key }
                                ?: store.sessionContent(profileId, sessionId)
                            chats[key] = selected
                            sessionsRevision.intValue++
                        }.onFailure { notices[currentKey] = "无法打开所选会话。" }
                    }
                },
                onNewSession = {
                    if (canCreate) {
                        runCatching {
                            val created = store.createSession(profileId)
                            chats[SessionKey(profileId, created.session)] = created
                            sessionsRevision.intValue++
                            retryConfirmation = null
                        }.onFailure { notices[currentKey] = "无法创建新会话。" }
                    }
                },
                canCreate = canCreate,
                topActions = { DesktopConnectionControls(connection) },
                draft = draft,
                onDraft = { value -> drafts[currentKey] = value },
                selectedImage = selectedImage,
                onChooseImage = ::chooseImage,
                onRemoveImage = { selectedImages.remove(currentKey) },
                onSend = { dispatch(draft.text, selectedImage) },
                canSend = canSend,
                notice = notices[currentKey],
                onRetry = { entry ->
                    if (canRetry && chat.entries.any { it === entry || it == entry }) {
                        retryConfirmation = RetryConfirmation(currentKey, entry)
                    }
                },
                canRetry = canRetry,
                assistantContent = { item ->
                    val operation = sending
                    val stream = if (operation?.key == currentKey && operation.pendingEntry === item) operation.answer else item.answer
                    DesktopReplyContent(
                        profileId = currentKey.profileId,
                        sessionId = currentKey.sessionId,
                        answer = stream,
                        images = item.replyImages,
                        cache = replyImageCache,
                        cacheGeneration = cacheGeneration,
                        failures = imageFailures,
                        retrying = retryingImages,
                        retryEnabled = canRetry,
                        onRetry = { attachmentId -> retryReplyImage(currentKey, attachmentId) },
                    )
                },
            )

            val pendingRetry = retryConfirmation?.takeIf { it.key == currentKey }
            if (pendingRetry != null) {
                AlertDialog(
                    onDismissRequest = { retryConfirmation = null },
                    title = { Text("重新发送？") },
                    text = { Text("此条消息可能已到达 AstrBot，继续可能重复执行。") },
                    confirmButton = {
                        TextButton(onClick = {
                            retryConfirmation = null
                            val image = pendingRetry.entry.attachmentReference?.let { runCatching { Path.of(it) }.getOrNull() }
                            dispatch(pendingRetry.entry.question, image, pendingRetry.entry, pendingRetry.key)
                        }, enabled = canRetry) { Text("确认重发") }
                    },
                    dismissButton = { TextButton(onClick = { retryConfirmation = null }) { Text("取消") } },
                )
            }
        }
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
private fun safeFailureMessage(error: Throwable): String {
    val message = error.message.orEmpty()
    if (message in SAFE_FAILURES) return message
    if (Regex("服务器返回 HTTP [1-5][0-9]{2}\\.").matches(message)) return message
    if (Regex("图片下载失败，服务器返回 HTTP [1-5][0-9]{2}\\.").matches(message)) return message
    return "连接或服务错误；回复状态未确认。"
}

private val SAFE_FAILURES = setOf(
    "密钥无效或已撤销。",
    "权限不足，请检查聊天权限。",
    "缺少文件上传权限，请为 API 密钥启用 file 权限。",
    "缺少图片下载权限，请为 API 密钥启用 file 权限。",
    "找不到对话接口，请检查 AstrBot 版本和服务地址。",
    "服务返回重定向，请直接填写最终服务地址。",
    "服务未返回对话事件流，请检查接口地址。",
    "AstrBot 报告对话失败，请在服务端检查配置。",
    "找不到回复图片，请检查附件是否仍然存在。",
    "图片下载接口未返回图片，请检查 AstrBot 服务。",
    "回复图片超过 20 MB 限制。",
    "服务返回了空图片。",
    "图片下载接口返回了错误信息，而不是图片。",
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
