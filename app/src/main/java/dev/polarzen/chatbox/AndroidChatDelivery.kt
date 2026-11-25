package dev.polarzen.chatbox

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

private const val REPLY_CHANNEL = "chat_replies"
private const val PREF_NOTIFICATIONS = "local_reply_notifications"
private const val STORAGE_FAILURE = "无法保存本地回复，请核对会话后再决定是否重新发送。"

/** Owned by the process. UI entry points run on the main thread. */
internal class AndroidChatDelivery(
    context: Context,
    private val history: AndroidHistory,
    private val isProfileAvailable: (String) -> Boolean = { true },
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("personal_chat", Context.MODE_PRIVATE)
    private val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
    private val active = mutableStateMapOf<Pair<String, String>, Boolean>()
    private val gate = MobileNotificationGate()
    private var enabled by mutableStateOf(prefs.getBoolean(PREF_NOTIFICATIONS, true))

    init {
        gate.setEnabled(enabled)
        manager.createNotificationChannel(NotificationChannel(REPLY_CHANNEL, "回复通知", NotificationManager.IMPORTANCE_DEFAULT))
    }

    var notificationsEnabled: Boolean
        get() = enabled
        set(value) {
            gate.setEnabled(value)
            enabled = value
            prefs.edit().putBoolean(PREF_NOTIFICATIONS, value).apply()
        }

    fun setForeground(value: Boolean) = gate.setForeground(value)

    fun busy(base: String, session: String): Boolean = active[AndroidHistory.normalizeBase(base) to session] == true

    fun send(base: String, session: String, token: String, user: String, draft: MobileDraft,
             heart: LiveHeartRate?, consumeDraft: Boolean = true, turnId: String = UUID.randomUUID().toString(),
             prepared: Boolean = false, networkQuestion: String? = null,
             onFinished: (() -> Unit)? = null, context: JSONObject? = null, contextRevision: String? = null,
             onNote: (String) -> Unit) {
        val address = AndroidHistory.normalizeBase(base)
        val key = address to session
        if (active[key] == true) return
        val secret = token.trim()
        val question = draft.text.trim()
        val assets = draft.attachments.toList()
        val issue = mobileSendIssue(address, session, secret, user, question, assets)
        if (issue != null) {
            onNote(issue)
            return
        }
        if (context != null && assets.isNotEmpty()) {
            onNote(CUSTOM_CONTEXT_MEDIA_NOTICE)
            return
        }
        if ((context == null) != (contextRevision == null) || contextRevision?.isBlank() == true) {
            onNote(CONTEXT_CHANGED)
            return
        }
        val initial = MobileTurn(turnId, question, sent = assets)
        val contextSnapshot = context?.let { JSONObject(it.toString()) }
        val customQuestion = networkQuestion ?: heart?.appendTo(question) ?: question
        val draftRevision: Long?
        try {
            if (contextSnapshot == null) {
                history.begin(address, session, initial, consumeDraft, prepared)
                draftRevision = null
            } else {
                history.flushDrafts()
                draftRevision = history.sessions(address).single { it.id == session }.draftRevision
            }
        } catch (_: Exception) {
            onNote("无法保存本地会话，消息未发送。")
            return
        }
        transfer(key, "chatbox-local-send", onNote, onFinished) {
            if (contextSnapshot != null) {
                val preview = previewAstrBotContext(address, secret, user, session, customQuestion, contextSnapshot)
                if (!preview.canSend) return@transfer preview.reason.ifBlank { CONTEXT_UNAVAILABLE }
                if (preview.revision != contextRevision) return@transfer CONTEXT_CHANGED
                // Persist the operation before dispatch. Only an accepted stream can consume its draft.
                history.begin(address, session, initial, consumeDraft = false)
            }
            val reply = MobileReplyDelivery(initial) { history.update(address, session, it) }
            val failure = try {
                val image = { id: String?, _: Int -> reply.media("image", id) }
                val audio = { id: String?, _: Int -> reply.media("audio", id) }
                if (contextSnapshot != null) {
                    sendAstrBotContext(address, secret, user, session, customQuestion, contextSnapshot,
                        requireNotNull(contextRevision), turnId, onReplyImage = image, onReplyAudio = audio,
                        onEvent = { event ->
                            if (event == AstrBotTransportEvent.StreamReady && consumeDraft) synchronized(history) {
                                history.flushDrafts()
                                val current = history.sessions(address).find { it.id == session }
                                if (current?.draftRevision == draftRevision && current?.draft == draft)
                                    history.draft(address, session, MobileDraft())
                            }
                            reply.event(event)
                        }, onText = reply::text)
                } else if (assets.isEmpty()) {
                    sendToAstrBot(address, secret, user, session, question, heart, onReplyImage = image,
                        onReplyAudio = audio, onEvent = reply::event, onText = reply::text)
                } else {
                    sendAttachmentsToAstrBot(address, secret, user, session, assets.map { File(it.path).toPath() },
                        networkQuestion ?: question, heart, onReplyImage = image, onReplyAudio = audio, onEvent = reply::event,
                        onText = reply::text)
                }
                null
            } catch (e: Exception) { e }
            reply.finish(failure, captureNotification = { captureNotification(address, session, initial.id) }, download = { kind, id ->
                downloadAsset(address, secret, kind, id)
            })
        }
    }

    /** A manual media retry only performs the authenticated file GET for an existing reply in this session. */
    fun retryMedia(base: String, session: String, turn: MobileTurn, asset: MobileAsset,
                   token: String, onNote: (String) -> Unit) {
        val address = AndroidHistory.normalizeBase(base)
        val key = address to session
        if (active[key] == true) return
        val secret = token.trim()
        mobileCredentialsIssue(address, secret)?.let { onNote(it); return }
        val remoteId = asset.remoteId
        if (remoteId.isNullOrBlank() || asset.kind !in setOf("image", "audio")) {
            onNote("回复附件缺少可用编号。")
            return
        }
        transfer(key, "chatbox-reply-download", onNote) {
            val saved = findTurn(address, session, turn.id)
            retryMobileReplyAsset(saved, asset, { kind, id -> downloadAsset(address, secret, kind, id) }) {
                history.update(address, session, it)
            }
        }
    }

    private fun transfer(key: Pair<String, String>, name: String, onNote: (String) -> Unit,
                         onFinished: (() -> Unit)? = null, work: () -> String) {
        active[key] = true
        val ready = AndroidChatTransferService.request(app)
        onNote("")
        thread(name = name, isDaemon = true) {
            if (!ready.join()) main.post { onNote("系统未允许后台传输，请保持 Chat Box 在前台。") }
            val note = try { work() } catch (_: Exception) { STORAGE_FAILURE }
            main.post {
                active.remove(key)
                if (active.isEmpty()) AndroidChatTransferService.finish()
                onNote(note)
                onFinished?.invoke()
            }
        }
    }

    private fun findTurn(base: String, session: String, turn: String): MobileTurn? =
        history.sessions(base).find { it.id == session }?.turns?.find { it.id == turn }

    private fun downloadAsset(base: String, token: String, kind: String, id: String): MobileAsset {
        val bytes = if (kind == "audio") downloadReplyAudio(base, token, id) else downloadReplyImage(base, token, id)
        return cacheMobileReply(app, kind, bytes)
    }

    private fun systemNotificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(REPLY_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun captureNotification(base: String, session: String, turn: String): (() -> Unit)? {
        val epoch = gate.capture(::systemNotificationsAllowed) ?: return null
        return {
            // Profile removal and the final eligibility check share the UI thread.
            main.post {
                gate.show(epoch, { isProfileAvailable(base) && systemNotificationsAllowed() }) {
                    postReply(base, session, turn)
                }
            }
        }
    }

    private fun postReply(base: String, session: String, turn: String) {
        if (findTurn(base, session, turn)?.status != "已完成") return
        val target = Uri.Builder().scheme("chatbox").authority("reply")
            .appendPath(base).appendPath(session).appendPath(turn).build()
        val intent = Intent("dev.polarzen.chatbox.REPLY").setClassName(app, "dev.polarzen.chatbox.MainActivity")
            .setData(target).putExtra("base", base).putExtra("session", session).putExtra("turn", turn)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(app, REPLY_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(app.getString(R.string.app_name))
            .setContentText("收到新回复").setAutoCancel(true).setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
        manager.notify(target.toString(), 1, notification)
    }
}

internal fun mobileSendIssue(base: String, session: String, token: String, user: String,
                            question: String, assets: List<MobileAsset>): String? {
    if (question.startsWith("/task") && (question.length == 5 || question[5].isWhitespace()))
        return "/task 需要 Windows Launcher 和已登录的 Dashboard，请在 Windows 客户端执行。"
    if (question.isBlank() && assets.isEmpty()) return "请输入消息或添加附件。"
    mobileCredentialsIssue(base, token)?.let { return it }
    if (session.isBlank() || user.isBlank()) return "无法读取本机会话，请重新打开会话。"
    for (asset in assets) {
        val file = File(asset.path)
        if (!file.isFile || !file.canRead()) return "无法读取待发附件，请移除或重新选择。"
        if (file.length() !in 1L..512L * 1024 * 1024) return "待发附件为空或超过 512 MiB。"
        val supported = when (asset.kind) {
            "image" -> file.extension.lowercase() in setOf("png", "jpg", "jpeg")
            "audio" -> file.extension.equals("wav", true)
            else -> false
        }
        if (!supported) return "仅支持 PNG、JPEG 图片或 WAV 语音。"
    }
    return null
}

private fun mobileCredentialsIssue(base: String, token: String): String? {
    if (runCatching { endpoint(base) }.isFailure) return "请填写有效的 AstrBot HTTPS 服务地址。"
    if (token.isBlank() || token.any { it.isWhitespace() || it.isISOControl() }) return "请输入有效的聊天 API 密钥。"
    return null
}

internal fun retryMobileReplyAsset(turn: MobileTurn?, asset: MobileAsset, download: (String, String) -> MobileAsset,
                                   save: (MobileTurn) -> Unit): String {
    val id = asset.remoteId
    if (id.isNullOrBlank() || turn == null) return "找不到原会话中的回复附件。"
    val previous = turn.replies.find { it.remoteId == id && it.kind == asset.kind } ?: return "找不到原会话中的回复附件。"
    val replacement = try {
        download(previous.kind, id).copy(remoteId = id)
    } catch (e: Exception) {
        previous.copy(path = "", name = mobileDeliveryError(e, "回复附件下载或解码失败。"))
    }
    save(turn.copy(replies = turn.replies.map {
        if (it.remoteId == id && it.kind == previous.kind) replacement else it
    }))
    return if (replacement.path.isBlank()) replacement.name else ""
}

/** A background completion loses eligibility permanently when the app opens or notifications are disabled. */
internal class MobileNotificationGate {
    private var epoch = 0L
    private var foreground = true
    private var enabled = true

    @Synchronized fun setForeground(value: Boolean) {
        if (value && !foreground) epoch++
        foreground = value
    }
    @Synchronized fun setEnabled(value: Boolean) {
        if (!value && enabled) epoch++
        enabled = value
    }
    @Synchronized fun capture(allowed: () -> Boolean): Long? {
        if (foreground || !enabled) return null
        return try { epoch.takeIf { allowed() } } catch (_: RuntimeException) { null }
    }

    @Synchronized fun show(permit: Long, allowed: () -> Boolean, post: () -> Unit) {
        if (permit == epoch && !foreground && enabled) {
            // The notification service or permission may change while an attachment downloads.
            try { if (allowed()) post() } catch (_: RuntimeException) { }
        }
    }
}

/** All callbacks run on the worker for this turn. A failed save can be retried by the next full snapshot. */
internal class MobileReplyDelivery(initial: MobileTurn, private val save: (MobileTurn) -> Unit) {
    private var turn = initial
    private val downloads = mutableListOf<Pair<Int, String>>()
    private var chatStarted = false
    private var persisted = true // begin() completed before creating the worker.

    private fun snapshot(next: MobileTurn) {
        if (next == turn && persisted) return
        turn = next
        persisted = try { save(next); true } catch (_: Exception) { false }
    }

    fun event(value: AstrBotTransportEvent) {
        val status = when (value) {
            is AstrBotTransportEvent.UploadStarted -> "上传中 ${value.index + 1}/${value.count}"
            is AstrBotTransportEvent.UploadFinished -> "上传中 ${value.index + 1}/${value.count} 已上传"
            AstrBotTransportEvent.ChatStarted -> { chatStarted = true; "等待回复" }
            AstrBotTransportEvent.HeadersReceived, AstrBotTransportEvent.StreamReady -> "等待回复"
            AstrBotTransportEvent.FirstContent -> "回复中"
            AstrBotTransportEvent.Completed -> return
        }
        snapshot(turn.copy(status = status))
    }

    fun text(value: String) = snapshot(turn.copy(answer = value))

    fun media(kind: String, id: String?) {
        val label = if (kind == "audio") "语音" else "图片"
        val name = if (id.isNullOrBlank()) "回复${label}缺少附件编号。" else {
            downloads += turn.replies.size to id
            "回复${label}尚未缓存。"
        }
        snapshot(turn.copy(replies = turn.replies + MobileAsset("", kind, name, remoteId = id?.takeIf(String::isNotBlank))))
    }

    fun finish(failure: Exception?, captureNotification: () -> (() -> Unit)?,
               download: (String, String) -> MobileAsset): String {
        val status = when {
            failure == null -> "已完成"
            !chatStarted && turn.sent.isNotEmpty() -> "附件上传失败，聊天未发送：${mobileDeliveryError(failure)}"
            !chatStarted -> "未发送：${mobileDeliveryError(failure)}"
            failure.message in rejectedChatErrors || rejectedChatHttp.matches(failure.message.orEmpty()) ->
                "聊天请求被拒绝：${mobileDeliveryError(failure)}"
            failure.message == "AstrBot 报告对话失败，请在服务端检查配置。" -> "发送失败：${mobileDeliveryError(failure)}"
            else -> "回复状态未确认：${mobileDeliveryError(failure)}请先核对 AstrBot 会话记录或服务端日志，再决定是否重发。"
        }
        // Capture visibility once at completion, before disk I/O and before any media fetch.
        var notification = if (failure == null) captureNotification() else null
        snapshot(turn.copy(status = status))
        fun notifyReadable() {
            if (persisted && (turn.answer.isNotBlank() || turn.replies.any { it.path.isNotBlank() })) {
                notification?.invoke()
                notification = null
            }
        }
        notifyReadable()
        for ((index, id) in downloads) {
            val previous = turn.replies[index]
            val asset = try { download(previous.kind, id).copy(remoteId = id) } catch (e: Exception) {
                previous.copy(name = mobileDeliveryError(e, "回复附件下载或解码失败。"))
            }
            snapshot(turn.copy(replies = turn.replies.toMutableList().also { it[index] = asset }))
            notifyReadable()
        }
        return when {
            !persisted -> STORAGE_FAILURE
            failure != null -> status
            turn.replies.any { it.path.isBlank() } -> turn.replies.first { it.path.isBlank() }.name
            turn.answer.isBlank() && turn.replies.isEmpty() -> "回复已结束，没有可显示的内容。"
            else -> ""
        }
    }
}

private val safeProtocolErrors = setOf(
    CONTEXT_CHANGED, CONTEXT_UNAVAILABLE,
    "密钥无效或已撤销。", "权限不足，请检查聊天权限。", "缺少文件上传权限，请为 API 密钥启用 file 权限。",
    "找不到文件上传接口，请检查 AstrBot 版本和服务地址。", "找不到对话接口，请检查 AstrBot 版本和服务地址。",
    "服务返回重定向，请直接填写最终服务地址。", "服务未返回对话事件流，请检查接口地址。",
    "AstrBot 报告对话失败，请在服务端检查配置。", "回复流意外中断，可能已在服务端执行。请勿直接重复发送。",
    "回复图片尺寸过大。", "图片尺寸无效或文件已损坏。", "回复图片格式不受支持。", "WAV 格式无效或文件已损坏。",
) + listOf("图片", "语音").flatMap { label ->
    listOf("缺少${label}下载权限，请为 API 密钥启用 file 权限。", "找不到回复${label}，请检查附件是否仍然存在。",
        "${label}下载接口未返回${label}，请检查 AstrBot 服务。", "回复${label}超过 20 MB 限制。",
        "服务返回了空${label}。", "${label}下载接口返回了错误信息，而不是${label}。",
        "${label}上传接口未返回 JSON，请检查 AstrBot 地址和版本。", "${label}上传接口返回了无法识别的响应。",
        "${label}超过服务端大小限制。", "${label}超过服务端大小限制（512 MB）。",
        "${label}上传失败，请检查文件权限和 API 密钥的文件权限。", "${label}上传成功响应中缺少附件编号。")
}
private val safeHttpError = Regex("(?:(?:图片|语音)(?:上传|下载)失败，)?服务器返回 HTTP [1-5][0-9]{2}。")
private val rejectedChatErrors = setOf(CONTEXT_CHANGED, CONTEXT_UNAVAILABLE, "密钥无效或已撤销。", "权限不足，请检查聊天权限。",
    "找不到对话接口，请检查 AstrBot 版本和服务地址。", "服务返回重定向，请直接填写最终服务地址。")
private val rejectedChatHttp = Regex("服务器返回 HTTP 4[0-9]{2}。")

private fun mobileDeliveryError(error: Exception, fallback: String = "连接或服务响应异常，请检查网络和服务。"): String {
    val message = error.message.orEmpty()
    return when {
        message in safeProtocolErrors || safeHttpError.matches(message) -> message
        error is SocketTimeoutException -> "连接服务超时。"
        error is SSLException -> "无法验证服务的 HTTPS 连接，请检查证书和地址。"
        error is UnknownHostException || error is ConnectException -> "无法连接服务，请检查网络和地址。"
        else -> fallback
    }
}
