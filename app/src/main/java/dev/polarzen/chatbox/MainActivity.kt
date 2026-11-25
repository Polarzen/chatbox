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
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ChatPage { WELCOME, PAIR, RECENT, CHAT, DEVICE_CHAT, DEVICE_SETTINGS, CONNECTIONS, APPEARANCE }

class MainActivity : ComponentActivity() {
    private lateinit var app: AndroidAppState
    private lateinit var heart: AndroidHeartRate
    private var page by mutableStateOf(ChatPage.CHAT)
    private val navigation = mutableStateListOf<ChatPage>()
    private val entryPrefs by lazy { getSharedPreferences("device-entry", MODE_PRIVATE) }
    private var ready by mutableStateOf(false)
    private var foreground = false
    private var receivedIntentWhileLoading = false
    private var restoreProfile: String? = null
    private var restoreSid: String? = null
    private var chooseProfile by mutableStateOf(false)
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
        heart = lastCustomNonConfigurationInstance as? AndroidHeartRate
            ?: AndroidHeartRate(this, LiveHeartRate(monotonicNanos = SystemClock::elapsedRealtimeNanos))
        fun restoredPage(name: String) = ChatPage.entries.find { it.name == name }
        val savedPage = savedInstanceState?.getString("page")?.let { restoredPage(it) ?: ChatPage.RECENT }
        if (savedInstanceState != null && savedPage != null) {
            page = savedPage
            navigation.addAll(savedInstanceState.getStringArrayList("navigation").orEmpty().mapNotNull(::restoredPage))
            heartOpen = savedInstanceState.getBoolean("heart")
            images = savedInstanceState.getStringArrayList("images").orEmpty().map(Uri::parse)
            jumpTurn = savedInstanceState.getString("jumpTurn")
            chatGeneration = savedInstanceState.getInt("chatGeneration")
            restoreProfile = savedInstanceState.getString("deviceProfile")
            restoreSid = savedInstanceState.getString("deviceSid")
            chooseProfile = savedInstanceState.getBoolean("chooseProfile")
        }
        lifecycleScope.launch {
            val (loaded, entry) = withContext(Dispatchers.IO) {
                // Decide before opening a database. Remember an unfinished first run across restarts.
                val mode = entryPrefs.getString("start", null) ?: run {
                    val legacy = getDatabasePath("mobile-history.db").isFile ||
                        getSharedPreferences("personal_chat", MODE_PRIVATE).all.isNotEmpty()
                    (if (legacy) "upgrade" else "welcome").also {
                        check(entryPrefs.edit().putString("start", it).commit())
                    }
                }
                val state = AndroidAppState.get(applicationContext)
                val established = state.device.bound
                val initial = when (mode) {
                    "welcome" -> if (established) ChatPage.RECENT else ChatPage.WELCOME
                    "upgrade" -> ChatPage.CHAT
                    else -> ChatPage.RECENT
                }
                state to initial
            }
            app = loaded
            if (savedPage == null) page = entry
            if (entryPrefs.getString("start", null) == "upgrade") completeWelcome()
            ready = true
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startForegroundWork()
            if (savedPage == null || receivedIntentWhileLoading) acceptIntent(intent)
        }
        setContent {
            val dark = ready && app.dark
            AndroidChatTheme(dark) {
                SideEffect {
                    val bars = WindowCompat.getInsetsController(window, window.decorView)
                    bars.isAppearanceLightStatusBars = !dark
                    bars.isAppearanceLightNavigationBars = !dark
                }
                if (ready) key(chatGeneration) { Screen() }
                else Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ready) acceptIntent(intent) else {
            receivedIntentWhileLoading = true
            setIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (ready) outState.putString("page", page.name)
        outState.putStringArrayList("navigation", ArrayList(navigation.map { it.name }))
        outState.putString("deviceProfile", if (ready) app.device.current?.profile ?: restoreProfile else restoreProfile)
        outState.putString("deviceSid", if (ready) app.device.current?.sid ?: restoreSid else restoreSid)
        outState.putBoolean("chooseProfile", chooseProfile)
        outState.putBoolean("heart", heartOpen)
        outState.putStringArrayList("images", ArrayList(images.map(Uri::toString)))
        outState.putString("jumpTurn", jumpTurn)
        outState.putInt("chatGeneration", chatGeneration)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (ready) startForegroundWork()
    }

    private fun startForegroundWork() {
        if (!foreground) {
            foreground = true
            app.activityStarted()
            AndroidWearableSync.get(applicationContext).foreground(true)
        }
    }

    override fun onResume() {
        super.onResume()
        heart.refreshAfterResume()
        updateSystemNotifications()
    }

    override fun onStop() {
        if (foreground) {
            AndroidWearableSync.get(applicationContext).foreground(false)
            app.activityStopped(isChangingConfigurations)
            foreground = false
        }
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

    private fun completeWelcome() {
        entryPrefs.edit().putString("start", "recent").apply()
    }

    private fun go(next: ChatPage) {
        if (page == next) return
        navigation.add(page)
        page = next
    }

    private fun recent() {
        app.device.closeChat()
        restoreProfile = null
        restoreSid = null
        navigation.clear()
        chooseProfile = false
        page = ChatPage.RECENT
        jumpTurn = null
    }

    private fun back() {
        if (page == ChatPage.PAIR) app.device.cancelPreview()
        if (page == ChatPage.DEVICE_CHAT || navigation.isEmpty() || navigation.last() == ChatPage.RECENT) recent()
        else page = navigation.removeAt(navigation.lastIndex)
    }

    private fun openLocalChat(turn: String? = null) {
        app.device.closeChat()
        navigation.clear()
        navigation.add(ChatPage.RECENT)
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

    private fun openDevice(row: AndroidDeviceRow) {
        val d = app.device
        val native = row.nativeBase
        if ((!d.bound || row.profile.startsWith("phone-only:")) && native != null) {
            if (!app.connections.selectOffline(native)) return
            app.history.select(native, row.sid)
            go(ChatPage.CHAT)
        } else {
            entryPrefs.edit().putString("lastProfile", row.profile).apply()
            restoreProfile = row.profile
            restoreSid = row.sid
            d.open(row)
            go(ChatPage.DEVICE_CHAT)
        }
    }

    private fun newDeviceChat(profile: DeviceProfile) {
        chooseProfile = false
        entryPrefs.edit().putString("lastProfile", profile.profile).apply()
        app.device.newChat(profile)
        restoreProfile = profile.profile
        restoreSid = app.device.current?.sid
        go(ChatPage.DEVICE_CHAT)
    }

    private fun newNativeChat(base: String) {
        if (!app.connections.selectOffline(base)) return
        val history = app.history
        val selected = history.selected(base)
        if (selected.turns.isNotEmpty() || selected.draft != MobileDraft() || app.legacyStorage(selected).hasState()) {
            history.create(base)
        }
        jumpTurn = null
        chooseProfile = false
        go(ChatPage.CHAT)
    }

    private fun newChat() {
        val d = app.device
        val connections = app.connections
        if (d.bound) {
            val available = d.profiles.filter { it.available }
            val selected = defaultDeviceProfile(available, entryPrefs.getString("lastProfile", null),
                connections.base, app.history.user)
            if (selected == null) chooseProfile = true else newDeviceChat(selected)
        } else {
            val base = connections.base.ifBlank { connections.services.singleOrNull()?.base.orEmpty() }
            if (base.isBlank() && connections.services.size > 1) chooseProfile = true else newNativeChat(base)
        }
    }

    @Composable private fun Screen() {
        val history = app.history
        val connections = app.connections
        val device = app.device
        val screens = rememberSaveableStateHolder()
        val closePage = ::back
        LaunchedEffect(page, device.bound, device.pendingJoin) {
            if (page == ChatPage.WELCOME && device.bound) { completeWelcome(); recent() }
            else if (page == ChatPage.WELCOME && device.pendingJoin) go(ChatPage.PAIR)
        }
        BackHandler(page != ChatPage.RECENT && page != ChatPage.WELCOME, onBack = closePage)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (page) {
                ChatPage.WELCOME -> AndroidDeviceWelcome({ go(ChatPage.PAIR) }, { completeWelcome(); recent() })
                ChatPage.PAIR -> AndroidDevicePairing(device, app.pairingScope, closePage) { completeWelcome(); recent() }
                ChatPage.RECENT -> screens.SaveableStateProvider("recent") {
                    AndroidRecentScreen(app, ::openDevice, ::newChat) { go(ChatPage.DEVICE_SETTINGS) }
                }
                ChatPage.DEVICE_SETTINGS -> AndroidDeviceSettings(device, app.pairingScope,
                    { go(ChatPage.PAIR) }, { go(ChatPage.CONNECTIONS) }, { go(ChatPage.APPEARANCE) }, closePage)
                ChatPage.CHAT -> screens.SaveableStateProvider("chat") { NativeChat() }
                ChatPage.DEVICE_CHAT -> {
                    val row = device.current
                    val session = device.chat
                    if (row != null && session != null) {
                        val owner = row.nativeBase?.let { "native:$it" } ?: "device:${row.profile}"
                        screens.SaveableStateProvider("${owner.length}:$owner:${session.id}") {
                            DeviceChat(row, session)
                        }
                    } else {
                        val target = device.rows.firstOrNull { it.profile == restoreProfile && it.sid == restoreSid }
                        LaunchedEffect(target, device.syncing, device.bound) {
                            if (target != null && device.bound) device.open(target)
                            else if (!device.syncing) recent()
                        }
                    }
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
            }
        }
        if (heartOpen) AndroidHeartRatePanel(heart) { heartOpen = false }
        if (chooseProfile) {
            if (device.bound) DeviceProfileChooser(device.profiles.filter { it.available }, ::newDeviceChat,
                { chooseProfile = false }, { chooseProfile = false; go(ChatPage.DEVICE_SETTINGS) })
            else AlertDialog(onDismissRequest = { chooseProfile = false }, title = { Text("选择会话所属档案") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    connections.services.forEach { profile ->
                        TextButton(onClick = { newNativeChat(profile.base) }) { Text("${profile.name} · ${profile.base}") }
                    }
                } }, confirmButton = {}, dismissButton = {
                    TextButton(onClick = { chooseProfile = false }) { Text("取消") }
                })
        }
        if (images.isNotEmpty()) key(images) {
            ShareDestination(app, history.selected(connections.base).id, images.size,
                onCancel = { images = emptyList() }) { address, id ->
                if (app.addSharedImages(address, id, images)) openLocalChat()
                else message("该本地会话已不可用，请选择其他会话。")
            }
        }
    }

    @Composable private fun NativeChat() {
        val history = app.history
        val connections = app.connections
        val base = connections.base
        val session = history.selected(base)
        val hasLegacyRoom = remember(base, session.id) { app.legacyStorage(session).hasState() }
        val device = app.device
        val busy = app.delivery.busy(base, session.id) || device.nativeBusy(base, session.id)
        LaunchedEffect(base, session.id, device.bound) {
            if (device.bound) device.focusNative(base, session.id)
        }
        val shown = if (device.bound) device.nativeChat(base, session) else session
        fun send(draft: MobileDraft? = null) {
            if (app.send(base, session, draft, heart.live)) requestNotifications(onFirstSend = true)
        }
        AndroidChatScreen(history, connections, shown, history.sessions(base), busy,
            hasLegacyRoom && !device.managesLegacyNative(base, session.id),
            listOfNotNull(app.note(base, session.id), device.notice.takeIf { device.bound })
                .filter(String::isNotBlank).joinToString("\n"), jumpTurn,
            onJumpHandled = { jumpTurn = null },
            onSelect = { jumpTurn = null; history.select(base, it) },
            onCreate = ::newChat,
            onRename = { id, name -> history.rename(base, id, name) },
            onDelete = { id ->
                history.delete(base, id)
                app.showNote(base, id, "")
            },
            onDraftText = { history.draftText(base, session.id, it) },
            onRemoveAsset = { history.removeAsset(base, session.id, it) },
            onAddAssets = { history.addAssets(base, session.id, it) },
            onSend = { send() },
            onRetry = { turn -> send(MobileDraft(turn.question, turn.sent)) },
            onRetryMedia = { turn, asset -> app.retryMedia(base, session.id, turn, asset) },
            onSettings = { go(ChatPage.CONNECTIONS) },
            onAppearance = { go(ChatPage.APPEARANCE) },
            onHeart = { heartOpen = true },
            onRecent = ::recent, onDeviceSettings = { go(ChatPage.DEVICE_SETTINGS) },
            draftEvent = device.draftEvent)
    }

    @Composable private fun DeviceChat(row: AndroidDeviceRow, session: MobileSession) {
        val device = app.device
        val native = row.nativeBase
        val connection = when {
            !device.bound -> "设备尚未验证"
            device.online -> "电脑已连接"
            device.syncing -> "正在核对电脑连接 · 读取缓存"
            device.notice.isBlank() -> "电脑连接尚未验证 · 读取缓存"
            else -> "电脑连接暂不可用 · 读取缓存"
        }
        val delivery = when {
            !device.online && device.busy -> "待同步 · 恢复连接后将核对此次发送"
            device.busy -> "正在发送或等待回复"
            !device.online -> "草稿保存在手机"
            else -> ""
        }
        val hasLegacyRoom = remember(native, session.id) { native != null && app.legacyStorage(session).hasState() }
        val readOnly = native != null && hasLegacyRoom && !device.managesLegacyNative(native, session.id)
        AndroidChatScreen(app.history, app.connections, session, listOf(session),
            device.busy || (row.reason.isNotBlank() && !readOnly), readOnly, listOf(row.reason, delivery, device.notice).filter { it.isNotBlank() }.joinToString("\n"),
            null, onJumpHandled = {}, onSelect = {}, onCreate = ::newChat,
            onRename = { _, _ -> message("请在会话所属设备中重命名。") },
            onDelete = { message("请在最近对话菜单中选择仅在本机隐藏。") },
            onDraftText = device::editDraft, onRemoveAsset = device::removeAsset,
            onAddAssets = { assets ->
                // A picker or clipboard import may finish after the user opened a different row.
                if (native != null) app.history.addAssets(native, session.id, assets)
            }, onSend = { if (device.send(heart.live)) requestNotifications(onFirstSend = true) },
            onRetry = { message("将按原发送编号自动核对。") }, onRetryMedia = device::retryMedia,
            onSettings = { go(ChatPage.DEVICE_SETTINGS) }, onAppearance = { go(ChatPage.APPEARANCE) },
            onHeart = { heartOpen = true },
            onRecent = ::recent, onDeviceSettings = { go(ChatPage.DEVICE_SETTINGS) },
            readDraft = { device.chat?.draft?.text.orEmpty() }, draftEvent = device.draftEvent,
            mediaAvailable = native != null,
            deviceStatus = listOf(connection, row.source).filter(String::isNotBlank).joinToString(" · "))
    }

}

@Composable
private fun ShareDestination(app: AndroidAppState, currentSession: String, count: Int,
                             onCancel: () -> Unit, onConfirm: (String, String?) -> Unit) {
    val connections = app.connections
    val profiles = connections.services.map { it.base to it.name } + ("" to "本机离线会话")
    var address by rememberSaveable { mutableStateOf(connections.base) }
    val sessions = app.history.sessions(address)
    var id by rememberSaveable(address) {
        mutableStateOf(if (address == connections.base && sessions.any { it.id == currentSession }) currentSession
            else sessions.firstOrNull()?.id)
    }
    val canConfirm = profiles.any { it.first == address } && (id == null || sessions.any { it.id == id })
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
                    Text(sessions.firstOrNull { it.id == id }?.title ?: "新建会话")
                }
                DropdownMenu(sessionsOpen, { sessionsOpen = false }) {
                    sessions.forEach { target ->
                        DropdownMenuItem(text = { Text("${target.title} · ${target.turns.size} 轮") },
                            onClick = { sessionsOpen = false; id = target.id })
                    }
                    DropdownMenuItem(text = { Text("新建会话") }, onClick = { sessionsOpen = false; id = null })
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = canConfirm, onClick = { onConfirm(address, id) }) { Text("加入草稿") }
    }, dismissButton = { TextButton(onClick = onCancel) { Text("取消") } })
}
