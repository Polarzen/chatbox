package dev.polarzen.chatbox

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class AndroidSharedSession(val storage: AndroidSharedStorage) {
    var heart: LiveHeartRate? = null
    var release: Job? = null
    val state = SharedChatState(storage, false) { text -> heart?.appendTo(text) ?: text }
}

/** Owns work that can outlive an Activity, including accepted sends and confirmed image imports. */
internal class AndroidAppState private constructor(private val app: Context) {
    val history = AndroidHistory(app).also { it.recoverInterrupted() }
    val connections = AndroidConnections(app, history.legacyBases())
    val delivery = AndroidChatDelivery(app, history) { address -> connections.services.any { it.base == address } }
    private val prefs = app.getSharedPreferences("personal_chat", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val pairingScope: CoroutineScope get() = scope
    private val sharedSessions = mutableMapOf<Pair<String, String>, AndroidSharedSession>()
    private val notes = mutableStateMapOf<Pair<String, String>, String>()
    private var startedActivities = 0

    var dark by mutableStateOf(prefs.getBoolean("appearance.dark", false))
        private set

    fun useDark(value: Boolean) {
        prefs.edit().putBoolean("appearance.dark", value).apply()
        dark = value
    }

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean("notification.permission.asked", false)
        set(value) { prefs.edit().putBoolean("notification.permission.asked", value).apply() }

    fun activityStarted() {
        if (startedActivities++ == 0) delivery.setForeground(true)
    }

    fun activityStopped(changingConfiguration: Boolean) {
        if (--startedActivities == 0 && !changingConfiguration) delivery.setForeground(false)
    }

    fun note(base: String, session: String): String = notes[base to session].orEmpty()

    fun showNote(base: String, session: String, value: String) {
        if (value.isBlank()) notes.remove(base to session) else notes[base to session] = value
    }

    private fun sharedKey(session: MobileSession) = session.sharedBase to session.id

    fun storage(session: MobileSession) = sharedSessions[sharedKey(session)]?.storage
        ?: AndroidSharedStorage(app, session.sharedBase, session.id)

    fun sharedSession(session: MobileSession, storage: AndroidSharedStorage): AndroidSharedSession =
        sharedSessions[sharedKey(session)] ?: AndroidSharedSession(storage)

    fun attachShared(session: MobileSession, room: AndroidSharedSession, heart: LiveHeartRate) {
        room.release?.cancel()
        room.release = null
        room.heart = heart
        sharedSessions[sharedKey(session)] = room
    }

    fun detachShared(session: MobileSession, room: AndroidSharedSession) {
        room.heart = null
        val key = sharedKey(session)
        if (room.storage.hasRecord || (!room.state.busy && !room.state.saveFailed)) {
            sharedSessions.remove(key, room)
            return
        }
        // A one-use pairing response must be saved even if the initiating Activity disappears.
        // Once persisted, a future visit can reload from disk and recheck the binding normally.
        room.release = scope.launch {
            snapshotFlow { room.storage.hasRecord || (!room.state.busy && !room.state.saveFailed) }.first { it }
            sharedSessions.remove(key, room)
        }
    }

    fun pairingInProgress(session: MobileSession): Boolean = sharedSessions[sharedKey(session)]?.state?.let {
        (it.busy && it.local.binding == null) || it.saveFailed
    } == true

    /** The notification payload is untrusted. Validate all three components before selecting anything. */
    fun openReply(base: String, session: String, turn: String): Boolean {
        if (session.isBlank() || turn.isBlank() || base !in history.legacyBases()) return false
        val profile = if (base.isEmpty()) "" else {
            connections.services.singleOrNull { AndroidHistory.normalizeBase(it.base) == base }?.base ?: return false
        }
        val target = history.sessions(profile).firstOrNull { it.id == session } ?: return false
        if (target.turns.none { it.id == turn } || !connections.selectOffline(profile)) return false
        history.select(profile, session)
        return true
    }

    fun send(base: String, session: MobileSession, retry: MobileDraft?, heart: LiveHeartRate): Boolean {
        if (base != connections.base || delivery.busy(base, session.id) ||
            storage(session).hasState() || pairingInProgress(session)) return false
        val current = history.selected(base)
        if (current.id != session.id) return false
        delivery.send(base, session.id, connections.token().orEmpty(), history.user, retry ?: current.draft, heart,
            consumeDraft = retry == null) { message ->
            showNote(base, session.id, message)
        }
        return delivery.busy(base, session.id)
    }

    fun retryMedia(base: String, session: String, turn: MobileTurn, asset: MobileAsset) {
        if (connections.base != base || history.selected(base).id != session) return
        delivery.retryMedia(base, session, turn, asset, connections.token().orEmpty()) { showNote(base, session, it) }
    }

    /** Called only after the user has confirmed a local profile and conversation. */
    fun addSharedImages(base: String, sessionId: String?, uris: List<Uri>): Boolean {
        if (base.isNotEmpty() && connections.services.none { it.base == base }) return false
        val target = if (sessionId != null) {
            val existing = history.sessions(base).firstOrNull { it.id == sessionId } ?: return false
            if (storage(existing).hasState() || pairingInProgress(existing)) return false
            existing
        } else null
        if (!connections.selectOffline(base)) return false
        val id = target?.id?.also { history.select(base, it) } ?: history.create(base).id
        showNote(base, id, "正在读取分享图片…")
        scope.launch {
            try {
                val images = withContext(Dispatchers.IO) { importMobileImages(app, uris) }
                var stored = false
                try {
                    val current = history.sessions(base).firstOrNull { it.id == id }
                    if (current == null || (base.isNotEmpty() && connections.services.none { it.base == base }) ||
                        storage(current).hasState() || pairingInProgress(current)) {
                        showNote(base, id, "目标本地会话已不可用，图片未加入草稿。")
                        return@launch
                    }
                    history.addAssets(base, id, images)
                    stored = true
                    showNote(base, id, if (images.size == uris.size) ""
                        else "已加入 ${images.size} 张图片，其余图片不可用，请重新选择。")
                } finally {
                    if (!stored) images.forEach { File(it.path).delete() }
                }
            } catch (_: Exception) {
                showNote(base, id, "无法加入图片，请重新分享或使用＋选择 PNG、JPEG 图片。")
            }
        }
        return true
    }

    companion object {
        private var instance: AndroidAppState? = null

        @Synchronized fun get(context: Context): AndroidAppState = instance
            ?: AndroidAppState(context.applicationContext).also { instance = it }
    }
}

/** Only type and availability cross the pairing boundary. File paths and bytes remain local. */
internal fun androidSharedSeed(base: String, user: String, session: MobileSession): SharedSeed {
    fun withHints(text: String, media: List<MobileAsset>): String = buildList {
        if (text.isNotEmpty()) add(text)
        media.forEach {
            val type = when (it.kind) {
                "audio" -> "语音"
                "image" -> "图片"
                else -> "文件"
            }
            add("[${type}附件 · 共享记录无法打开]")
        }
    }.joinToString("\n")
    return SharedSeed(base, user, session.id, session.turns.map { turn ->
        SharedSeedTurn(withHints(turn.question, turn.sent), withHints(turn.answer, turn.replies),
            if (turn.status == "已完成") "complete" else "unknown")
    })
}
