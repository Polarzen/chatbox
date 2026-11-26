package dev.polarzen.chatbox

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var inboxPoll: Job? = null
    var inboxNotice by mutableStateOf("")
        private set
    private val notes = mutableStateMapOf<Pair<String, String>, String>()
    private var startedActivities = 0

    val themes = SharedThemeState(app.filesDir.toPath(), ChatTheme(dark = prefs.getBoolean("appearance.dark", false)))
    val appearance: ChatTheme get() = themes.theme
    fun setAppearance(value: ChatTheme) = themes.applyTheme(value)

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean("notification.permission.asked", false)
        set(value) { prefs.edit().putBoolean("notification.permission.asked", value).apply() }

    fun activityStarted() {
        if (startedActivities++ == 0) {
            delivery.setForeground(true)
            if (inboxPoll?.isActive != true) inboxPoll = scope.launch { pollNativeInbox() }
            if (devicePoll?.isActive != true) devicePoll = scope.launch {
                device.pollForeground()
            }
        }
    }

    fun activityStopped(changingConfiguration: Boolean) {
        if (--startedActivities == 0 && !changingConfiguration) {
            delivery.setForeground(false)
            devicePoll?.cancel()
            inboxPoll?.cancel()
            history.flushDrafts()
            device.flushDrafts()
        }
    }

    private suspend fun pollNativeInbox() {
        snapshotFlow {
            // A paired room is received through the device service with the original upstream identity.
            if (device.pendingJoin || connections.base.isBlank()) null
            else connections.token()?.let { connections.base to it }
        }.collectLatest { target ->
            inboxNotice = ""
            if (target == null) return@collectLatest
            val (base, token) = target
            var nextOther = 0
            while (currentCoroutineContext().isActive) {
                var interval = 5_000L
                try {
                    val sessions = history.sessions(base).filter {
                        it.turns.isNotEmpty() && !device.usesPairedInbox(base, it.id)
                    }
                    val rooms = if (sessions.isEmpty()) emptyList() else {
                        val selected = history.selected(base).id
                        val others = sessions.filterNot { it.id == selected }
                        val other = if (others.isEmpty()) null else {
                            nextOther %= others.size
                            others[nextOther++]
                        }
                        listOfNotNull(sessions.find { it.id == selected }, other)
                    }
                    var notice = ""
                    for (room in rooms) {
                        if (legacyStorage(room).hasState()) continue
                        val after = withContext(Dispatchers.IO) { history.inboxCursor(base, room.id) } ?: continue
                        val page = try { fetchAstrBotInbox(base, token, history.user, room.id, after) }
                        catch (e: CancellationException) { throw e }
                        catch (_: AstrBotInboxDenied) {
                            notice = inboxSetupNotice("denied", history.user)
                            interval = 30_000L
                            continue
                        }
                        catch (_: Exception) {
                            notice = "部分会话的主动消息暂未同步，请检查连接和 AstrBot 收件权限。"
                            interval = 12_000L
                            continue
                        }
                        if (connections.base != base || connections.token() != token || device.pendingJoin) break
                        if (device.usesPairedInbox(base, room.id)) continue
                        if (page == null) {
                            notice = inboxSetupNotice("unsupported", history.user)
                            interval = 120_000L
                            break
                        }
                        withContext(Dispatchers.IO) { history.receiveInbox(base, room.id, after, page.messages) }
                        if (page.more) interval = 800L
                    }
                    inboxNotice = notice
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    inboxNotice = "主动消息暂未同步，恢复连接后将继续接收。"
                    interval = 12_000L
                }
                delay(interval)
            }
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
        if (device.preferences(base, session.id).value.recentTurns != null) {
            showNote(base, session.id, CONTEXT_CHANGED)
            return false
        }
        delivery.send(base, session.id, connections.token().orEmpty(), history.user, retry ?: current.draft, heart,
            consumeDraft = retry == null) { message ->
            showNote(base, session.id, message)
        }
        return delivery.busy(base, session.id)
    }

    suspend fun previewContext(base: String, session: MobileSession, prefs: ConversationPreferences,
                               turns: List<ContextTurn>, draft: MobileDraft, heart: LiveHeartRate?): AstrBotContextPreview {
        if (draft.attachments.isNotEmpty()) return AstrBotContextPreview.unavailable(CUSTOM_CONTEXT_MEDIA_NOTICE)
        if (base != connections.base || history.selected(base).id != session.id || device.pendingJoin)
            return AstrBotContextPreview.unavailable(CONTEXT_CHANGED)
        val message = heart?.appendTo(draft.text.trim()) ?: draft.text.trim()
        if (device.bound && base.isNotBlank()) return device.previewContext(base, session, message)
        val token = connections.token().orEmpty()
        if (base.isBlank() || token.isBlank()) return AstrBotContextPreview.unavailable(CONTEXT_UNAVAILABLE)
        return withContext(Dispatchers.IO) {
            previewAstrBotContext(base, token, history.user, session.id, message, astrBotContext(prefs, turns))
        }
    }

    fun sendReviewed(base: String, session: MobileSession, retry: MobileTurn?, draft: MobileDraft,
                     prefs: ConversationPreferences, preview: AstrBotContextPreview): Boolean {
        history.flushDrafts()
        val current = history.selected(base)
        val handle = device.preferences(base, session.id)
        val stale = base != connections.base || current.id != session.id || !preview.canSend || preview.networkMessage == null ||
            !handle.ready || handle.contextPending || !handle.value.sameContext(prefs) || prefs.recentTurns == null ||
            retry == null && current.draft != draft || retry != null && current.turns.none { it.id == retry.id }
        if (stale || device.pendingJoin || delivery.busy(base, session.id) ||
            legacyStorage(session).hasState() && !device.managesLegacyNative(base, session.id)) {
            showNote(base, session.id, CONTEXT_CHANGED)
            return false
        }
        if (device.bound && base.isNotBlank()) return device.sendNative(base, current, draft, null, retry == null, preview)
        val context = try { astrBotContext(prefs, mobileContextTurns(current, handle)) }
        catch (_: IllegalArgumentException) {
            showNote(base, session.id, CONTEXT_CHANGED)
            return false
        }
        delivery.send(base, session.id, connections.token().orEmpty(), history.user, draft, null,
            consumeDraft = retry == null, networkQuestion = preview.networkMessage, context = context,
            contextRevision = preview.revision) { showNote(base, session.id, it) }
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
