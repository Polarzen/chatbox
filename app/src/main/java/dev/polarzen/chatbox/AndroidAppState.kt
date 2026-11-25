package dev.polarzen.chatbox

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Owns work that can outlive an Activity, including accepted sends and confirmed image imports. */
internal class AndroidAppState private constructor(private val app: Context) {
    val history = AndroidHistory(app).also { it.recoverInterrupted() }
    val connections = AndroidConnections(app, history.legacyBases())
    val delivery = AndroidChatDelivery(app, history) { address -> connections.services.any { it.base == address } }
    private val prefs = app.getSharedPreferences("personal_chat", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val pairingScope: CoroutineScope get() = scope
    val device by lazy { AndroidDeviceSync(app, this, scope) }
    private var devicePoll: Job? = null
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
        if (startedActivities++ == 0) {
            delivery.setForeground(true)
            if (devicePoll?.isActive != true) devicePoll = scope.launch {
                while (isActive) { device.refresh(); delay(5000L) }
            }
        }
    }

    fun activityStopped(changingConfiguration: Boolean) {
        if (--startedActivities == 0 && !changingConfiguration) {
            delivery.setForeground(false)
            devicePoll?.cancel()
            history.flushDrafts()
            device.flushDrafts()
        }
    }

    fun note(base: String, session: String): String = notes[base to session].orEmpty()

    fun showNote(base: String, session: String, value: String) {
        if (value.isBlank()) notes.remove(base to session) else notes[base to session] = value
    }

    fun legacyStorage(session: MobileSession) = AndroidSharedStorage(app, session.sharedBase, session.id)

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
        if (base != connections.base || delivery.busy(base, session.id)) return false
        if (legacyStorage(session).hasState() && !device.managesLegacyNative(base,session.id)) return false
        val current = history.selected(base)
        if (current.id != session.id) return false
        if (device.pendingJoin) {
            showNote(base,session.id,"正在核对设备配对结果，请稍后再发送。")
            return false
        }
        if (device.bound && base.isNotBlank()) {
            return device.sendNative(base,current,retry ?: current.draft,heart,retry == null)
        }
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
            history.sessions(base).firstOrNull { it.id == sessionId } ?: return false
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
                    if (current == null || (base.isNotEmpty() && connections.services.none { it.base == base })) {
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
