package dev.polarzen.chatbox

import android.Manifest
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private enum class ChatPage { CHAT, CONNECTIONS, APPEARANCE, SHARED }

class MainActivity : ComponentActivity() {
    private lateinit var app: AndroidAppState
    private lateinit var heart: AndroidHeartRate
    private var page by mutableStateOf(ChatPage.CHAT)
    private var heartOpen by mutableStateOf(false)
    private var images by mutableStateOf<List<Uri>>(emptyList())
    private var jumpTurn by mutableStateOf<String?>(null)
    private var chatGeneration by mutableIntStateOf(0)
    private var notificationsAllowed by mutableStateOf(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateSystemNotifications()
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app = AndroidAppState.get(this)
        heart = lastCustomNonConfigurationInstance as? AndroidHeartRate
            ?: AndroidHeartRate(this, LiveHeartRate(monotonicNanos = SystemClock::elapsedRealtimeNanos))
        if (savedInstanceState == null) {
            if (app.storage(app.history.selected(app.connections.base)).hasState()) page = ChatPage.SHARED
            acceptIntent(intent)
        } else {
            page = savedInstanceState.getString("page")?.let(ChatPage::valueOf) ?: ChatPage.CHAT
            heartOpen = savedInstanceState.getBoolean("heart")
            images = savedInstanceState.getStringArrayList("images").orEmpty().map(Uri::parse)
            jumpTurn = savedInstanceState.getString("jumpTurn")
            chatGeneration = savedInstanceState.getInt("chatGeneration")
        }
        setContent {
            AndroidChatTheme(app.dark) {
                val dark = app.dark
                SideEffect {
                    val bars = WindowCompat.getInsetsController(window, window.decorView)
                    bars.isAppearanceLightStatusBars = !dark
                    bars.isAppearanceLightNavigationBars = !dark
                }
                Screen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name)
        outState.putBoolean("heart", heartOpen)
        outState.putStringArrayList("images", ArrayList(images.map(Uri::toString)))
        outState.putString("jumpTurn", jumpTurn)
        outState.putInt("chatGeneration", chatGeneration)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        app.activityStarted()
    }

    override fun onResume() {
        super.onResume()
        heart.refreshAfterResume()
        updateSystemNotifications()
    }

    override fun onStop() {
        app.activityStopped(isChangingConfigurations)
        super.onStop()
    }

    @Deprecated("ComponentActivity retains the BLE receiver across configuration changes")
    override fun onRetainCustomNonConfigurationInstance(): Any = heart

    override fun onDestroy() {
        if (!isChangingConfigurations) heart.close()
        super.onDestroy()
    }

    private fun updateSystemNotifications() {
        val manager = getSystemService(NotificationManager::class.java)
        notificationsAllowed = manager?.areNotificationsEnabled() == true &&
            manager.getNotificationChannel("chat_replies")?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun requestNotifications(onFirstSend: Boolean) {
        if (!app.delivery.notificationsEnabled || Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            (onFirstSend && app.notificationPermissionAsked)) return
        app.notificationPermissionAsked = true
        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openLocalChat(turn: String? = null) {
        page = ChatPage.CHAT
        heartOpen = false
        images = emptyList()
        jumpTurn = turn
        // A notification or confirmed share must also dismiss any drawer, search or edit dialog.
        chatGeneration++
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun acceptIntent(input: Intent) {
        when (input.action) {
            "dev.polarzen.chatbox.REPLY" -> {
                val turn = runCatching {
                    val base = input.getStringExtra("base") ?: return@runCatching null
                    val session = input.getStringExtra("session") ?: return@runCatching null
                    input.getStringExtra("turn")?.takeIf { app.openReply(base, session, it) }
                }.getOrNull()
                if (turn == null) message("原会话或回复已不存在。") else openLocalChat(turn)
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val granted = runCatching { sharedImages(input) }.getOrDefault(emptyList())
                if (granted.isEmpty()) message("无法读取分享的图片，请从图片应用重新分享。")
                else { heartOpen = false; images = granted }
            }
            else -> return
        }
        // Do not keep the triggering intent as a navigation command on recreation.
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    @Suppress("DEPRECATION")
    private fun sharedImages(input: Intent): List<Uri> {
        if (input.type?.startsWith("image/") != true ||
            input.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return emptyList()
        val grantUris = buildList {
            input.data?.let(::add)
            input.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
            }
        }
        // Android normally moves EXTRA_STREAM to ClipData when granting access. If a sender supplies
        // both, only the grant-bearing carriers can nominate an image for this request.
        val uris = grantUris.ifEmpty {
            if (input.action == Intent.ACTION_SEND) listOfNotNull(input.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else input.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
        return uris.distinct().filter { uri ->
            uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrBlank() &&
                checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    @Composable private fun Screen() {
        val history = app.history
        val connections = app.connections
        val base = connections.base
        val session = history.selected(base)
        val storage = remember(base, session.id) { app.storage(session) }
        val busy = app.delivery.busy(base, session.id)
        val screens = key(chatGeneration) { rememberSaveableStateHolder() }
        val closePage = { page = ChatPage.CHAT }
        BackHandler(page != ChatPage.CHAT, onBack = closePage)
        fun send(draft: MobileDraft? = null) {
            if (app.send(base, session, draft, heart.live)) requestNotifications(onFirstSend = true)
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (page) {
                ChatPage.CHAT -> screens.SaveableStateProvider("chat") {
                    AndroidChatScreen(history, connections, session, history.sessions(base), busy,
                        storage.hasRecord || app.pairingInProgress(session), app.note(base, session.id), jumpTurn,
                        onJumpHandled = { jumpTurn = null },
                        onSelect = { jumpTurn = null; history.select(base, it) },
                        onCreate = { jumpTurn = null; history.create(base) },
                        onRename = { id, name -> history.rename(base, id, name) },
                        onDelete = { id ->
                            val target = history.sessions(base).firstOrNull { it.id == id }
                            if (target != null) {
                                val reason = when {
                                    app.storage(target).hasState() -> "请先在共享会话「更多」中退出本机共享"
                                    app.pairingInProgress(target) -> "共享绑定正在保存，请稍后再删除。"
                                    else -> null
                                }
                                if (reason != null) {
                                    app.showNote(base, id, reason)
                                    if (id != session.id) message(reason)
                                } else {
                                    history.delete(base, id)
                                    app.showNote(base, id, "")
                                }
                            }
                        },
                        onDraftText = { history.draftText(base, session.id, it) },
                        onRemoveAsset = { history.removeAsset(base, session.id, it) },
                        onAddAssets = { history.addAssets(base, session.id, it) },
                        onSend = { send() },
                        onRetry = { turn -> send(MobileDraft(turn.question, turn.sent)) },
                        onRetryMedia = { turn, asset -> app.retryMedia(base, session.id, turn, asset) },
                        onSettings = { page = ChatPage.CONNECTIONS },
                        onAppearance = { page = ChatPage.APPEARANCE },
                        onShared = {
                            if (busy) app.showNote(base, session.id, "请等待当前回复结束，再打开共享会话。")
                            else page = ChatPage.SHARED
                        }, onHeart = { heartOpen = true })
                }
                ChatPage.CONNECTIONS -> AndroidConnectionSettings(connections, closePage)
                ChatPage.APPEARANCE -> Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    AndroidAppearanceScreen(app.dark, app::useDark, app.delivery.notificationsEnabled, { wanted ->
                        app.delivery.notificationsEnabled = wanted
                        if (wanted) requestNotifications(onFirstSend = false)
                    }, notificationsAllowed, {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }, closePage)
                }
                ChatPage.SHARED -> Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    if (storage.hasRecord) TextButton(onClick = closePage) { Text("返回本地历史与档案") }
                    val seed = remember(storage) { androidSharedSeed(base, history.user, session) }
                    val room = remember(storage) { app.sharedSession(session, storage) }
                    DisposableEffect(room) {
                        app.attachShared(session, room, heart.live)
                        onDispose { app.detachShared(session, room) }
                    }
                    ForegroundPanel(seed, room, closePage)
                }
            }
        }
        if (heartOpen) AndroidHeartRatePanel(heart) { heartOpen = false }
        if (images.isNotEmpty()) key(images) {
            ShareDestination(app, session.id, images.size, onCancel = { images = emptyList() }) { address, id ->
                if (app.addSharedImages(address, id, images)) openLocalChat()
                else message("该本地会话已不可用，请选择其他会话。")
            }
        }
    }

    @Composable private fun ForegroundPanel(seed: SharedSeed, room: AndroidSharedSession, onClose: () -> Unit) {
        val storage = room.storage
        val view = remember(storage) { ComposeView(this) }
        val close by rememberUpdatedState(onClose)
        val bound = storage.hasRecord
        DisposableEffect(view, bound) {
            fun display() {
                if (!bound || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    view.setContent { AndroidChatTheme(app.dark) {
                        SharedChatPanel(seed, storage, false, retainedState = room.state,
                            pairingScope = app.pairingScope, onClose = close)
                    } }
                } else {
                    view.setContent { }
                    view.disposeComposition()
                }
            }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_RESUME) display()
            }
            display()
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        key(view) { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun ShareDestination(app: AndroidAppState, currentSession: String, count: Int,
                             onCancel: () -> Unit, onConfirm: (String, String?) -> Unit) {
    val connections = app.connections
    val profiles = connections.services.map { it.base to it.name } + ("" to "独立共享会话")
    var address by rememberSaveable { mutableStateOf(connections.base) }
    val sessions = app.history.sessions(address)
    val available = remember(address, sessions) { sessions.filterNot { app.storage(it).hasState() } }
    var id by rememberSaveable(address) {
        mutableStateOf(if (address == connections.base && available.any { it.id == currentSession }) currentSession
            else available.firstOrNull()?.id)
    }
    val canConfirm = profiles.any { it.first == address } && (id == null || available.any { it.id == id })
    AlertDialog(onDismissRequest = onCancel, title = { Text("分享图片到会话") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("将 ${count} 张图片加入所选会话的草稿。")
            Text("服务档案", style = MaterialTheme.typography.labelLarge)
            var servicesOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { servicesOpen = true }) {
                    Text(profiles.find { it.first == address }?.second ?: "选择服务档案")
                }
                DropdownMenu(servicesOpen, { servicesOpen = false }) {
                    profiles.forEach { (base, name) ->
                        DropdownMenuItem(text = { Text(if (base.isBlank()) name else "$name\n$base") },
                            onClick = { servicesOpen = false; address = base })
                    }
                }
            }
            Text("本地会话", style = MaterialTheme.typography.labelLarge)
            var sessionsOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { sessionsOpen = true }) {
                    Text(available.firstOrNull { it.id == id }?.title ?: "新建会话")
                }
                DropdownMenu(sessionsOpen, { sessionsOpen = false }) {
                    available.forEach { target ->
                        DropdownMenuItem(text = { Text("${target.title} · ${target.turns.size} 轮") },
                            onClick = { sessionsOpen = false; id = target.id })
                    }
                    DropdownMenuItem(text = { Text("新建会话") }, onClick = { sessionsOpen = false; id = null })
                }
            }
            if (sessions.size != available.size) Text("共享会话仅支持文字，可选择其他本地会话。")
        }
    }, confirmButton = {
        TextButton(enabled = canConfirm, onClick = { onConfirm(address, id) }) { Text("加入草稿") }
    }, dismissButton = { TextButton(onClick = onCancel) { Text("取消") } })
}
