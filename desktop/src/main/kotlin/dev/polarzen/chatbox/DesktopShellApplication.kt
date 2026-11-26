package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.AlertDialog
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

// The screen acknowledges hide and exit only after its draft has been flushed.
internal class DesktopShellVisibility {
    var visible by mutableStateOf(true)
        private set
    var hideRequested by mutableStateOf(false)
        private set
    var exiting by mutableStateOf(false)
        private set
    private var stillWantsHide = false
    private var saveDrafts: (() -> Unit)? = null

    fun bindDraftSave(save: (() -> Unit)?) {
        saveDrafts = save
        if (hideRequested || exiting) save?.invoke()
    }

    fun hide(trayAvailable: Boolean) {
        if (!exiting && trayAvailable) {
            stillWantsHide = true
            if (!hideRequested) {
                hideRequested = true
                saveDrafts?.invoke()
            }
        }
    }

    fun windowClose(closeToTray: Boolean, trayAvailable: Boolean) {
        if (closeToTray && trayAvailable) hide(true) else exit()
    }

    fun exit() {
        if (exiting) return
        val savingForHide = hideRequested
        exiting = true
        stillWantsHide = false
        hideRequested = false
        if (!savingForHide) saveDrafts?.invoke()
    }

    fun show() {
        if (!exiting) {
            stillWantsHide = false
            visible = true
        }
    }

    fun finishHide(saved: Boolean, trayAvailable: Boolean) {
        if (hideRequested && stillWantsHide && saved && trayAvailable) visible = false
        stillWantsHide = false
        hideRequested = false
    }

    fun finishExit(saved: Boolean, quit: () -> Unit, restore: () -> Unit) {
        if (!exiting) return
        if (saved) quit() else {
            exiting = false
            visible = true
            restore()
        }
    }
}

internal class DesktopReplyNotifier(
    initiallyEnabled: Boolean,
    private val isForeground: () -> Boolean,
    private val showToast: (ReplyDestination) -> Boolean,
) {
    private var enabledPeriod: Any? = if (initiallyEnabled) Any() else null

    fun capture(target: ReplyDestination, stillLive: () -> Boolean): ReplyToastPermit? {
        val period = synchronized(this) { enabledPeriod } ?: return null
        if (isForeground()) return null
        return ReplyToastPermit {
            synchronized(this) { period === enabledPeriod && stillLive() && showToast(target) }
        }
    }

    @Synchronized fun saveSettings(notifyReplies: Boolean, persist: () -> Unit) {
        persist()
        if (!notifyReplies) enabledPeriod = null
        else if (enabledPeriod == null) enabledPeriod = Any()
    }
}

internal fun DesktopShellSettings.registrationForSave(updated: DesktopShellSettings, registered: DesktopHotkey?): DesktopHotkey? {
    val previous = hotkey.takeIf { hotkeyEnabled }
    val requested = updated.hotkey.takeIf { updated.hotkeyEnabled }
    // An unchanged Save retries a failed startup registration. Saving unrelated edits preserves the actual binding.
    return if (requested == previous && updated != this) registered else requested
}

private val LocalModalCount = staticCompositionLocalOf<MutableIntState?> { null }

@Composable
internal fun DesktopModalGuard(active: Boolean = true) {
    val count = LocalModalCount.current ?: return
    if (active) DisposableEffect(count) {
        count.intValue++
        onDispose { count.intValue-- }
    }
}

// All delivery and binding run on the EDT, including delivery during a native dialog's nested event loop.
internal class ActivationInbox {
    private val early = ArrayDeque<DesktopActivation>()
    private var handler: ((DesktopActivation) -> Unit)? = null
    private var accepting: () -> Boolean = { true }

    fun post(action: DesktopActivation) {
        fun deliver() {
            if (!accepting()) throw IOException("Chatbox is closing")
            val current = handler
            if (current == null) early.addLast(action) else current(action)
        }
        if (EventQueue.isDispatchThread()) deliver() else {
            try {
                CompletableFuture.runAsync(::deliver, EventQueue::invokeLater).join()
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }
        }
    }

    fun bind(accepting: () -> Boolean = { true }, handler: (DesktopActivation) -> Unit) {
        this.accepting = accepting
        this.handler = handler
        while (early.isNotEmpty()) handler(early.removeFirst())
    }

    fun dispose() {
        accepting = { false }
        handler = null
    }
}

private fun startupMessage(message: String) = application {
    Window(onCloseRequest = ::exitApplication, title = "Chat Box", icon = androidx.compose.ui.res.painterResource("chat-box.png"), state = rememberWindowState(size = DpSize(520.dp, 220.dp))) {
        DesktopTheme(false) { Surface(Modifier.fillMaxSize()) { Text(message, Modifier.padding(16.dp)) } }
    }
}

internal fun runDesktopShell(args: Array<String>) {
    val dataDir = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "Chatbox") }
        ?: Path.of(System.getProperty("user.home"), ".chatbox")
    withDesktopInstance(args, dataDir, ::startupMessage) { directoryKey, inbox, activationAvailable ->
        val loaded = runCatching { DesktopStore(dataDir.resolve("history.json")) }
        loaded.getOrNull()?.let { runCatching { it.cleanupUnreferencedManagedMedia() } }
        WindowsReplyToast.initializeAppId()
        runPrimaryWindow(dataDir, directoryKey, loaded, inbox, activationAvailable)
    }
}

internal fun withDesktopInstance(
    args: Array<String>, dataDir: Path, onError: (String) -> Unit,
    primary: (String, ActivationInbox, Boolean) -> Unit,
) {
    val toastLaunch = args.firstOrNull() == "--open-toast"
    val launch = if (toastLaunch && args.size == 2) DesktopInstanceActivation.parseToastUri(args[1]) else null
    if (args.isNotEmpty() && launch == null) {
        onError("无法打开此 Chat Box 通知，通知链接无效。")
        return
    }
    val directoryKey = try {
        DesktopInstanceActivation.directoryKey(dataDir)
    } catch (_: Exception) {
        onError("无法锁定本地聊天历史文件，请检查数据目录权限。")
        return
    }
    val action = launch?.second ?: DesktopActivation.Show
    val ownerKey = launch?.first ?: directoryKey
    val local = ownerKey == directoryKey

    var channel: FileChannel? = null
    var lock: FileLock? = null
    var receiver: AutoCloseable? = null
    try {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (lock == null) {
            if (local) {
                try {
                    if (channel == null) {
                        Files.createDirectories(dataDir)
                        channel = FileChannel.open(dataDir.resolve("history.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                    }
                    lock = channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    // The same JVM can also host multiple launch requests.
                } catch (_: IOException) {
                    onError("无法锁定本地聊天历史文件，请检查数据目录权限。")
                    return
                }
                if (lock != null) break
            }
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0 || Thread.currentThread().isInterrupted) {
                onError(if (local) "Chat Box 已在运行，请使用已打开的窗口。" else "原数据目录中的 Chat Box 未运行，此通知已失效。")
                return
            }
            when (DesktopInstanceActivation.forwardOutcome(ownerKey, action, remaining)) {
                ForwardOutcome.ACCEPTED -> return
                ForwardOutcome.UNCERTAIN -> {
                    onError("唤起请求已发出，但未收到确认。请查看原 Chat Box 窗口。")
                    return
                }
                ForwardOutcome.RETRYABLE -> try {
                    Thread.sleep(minOf(40, remaining))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        val inbox = ActivationInbox()
        if (launch != null) inbox.post(action)
        receiver = runCatching { DesktopInstanceActivation.listen(directoryKey, inbox::post) }.getOrNull()
        primary(directoryKey, inbox, receiver != null)
    } finally {
        runCatching { receiver?.close() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }
}

private fun runPrimaryWindow(
    dataDir: Path, directoryKey: String, loaded: Result<DesktopStore>, inbox: ActivationInbox, activationAvailable: Boolean,
) {
    val store = loaded.getOrNull()
    val keys = NamedKeys(dataDir, DesktopCredentials(dataDir.resolve("credentials")))
    val images = ReplyImageCache(dataDir.resolve("reply-images"))
    val appearanceStore = DesktopAppearanceStore(dataDir)
    val initialAppearance = appearanceStore.load()
    val chatThemeState = DesktopThemeState(dataDir, ChatTheme(dark = initialAppearance.dark))
    val deviceSync = store?.let { DesktopDeviceSync(it, keys).apply { themeState = chatThemeState } }
    val setupMarker = dataDir.resolve("device-setup-seen")
    val firstDeviceSetup = store?.profiles?.isEmpty() == true && !Files.exists(setupMarker)
    val settingsStore = DesktopShellSettingsStore(dataDir)
    application(exitProcessOnExit = false) {
        val visibility = remember { DesktopShellVisibility() }
        val modalCount = remember { mutableIntStateOf(0) }
        val historySearchOpen = remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var localAppearance by remember { mutableStateOf(initialAppearance) }
        val theme = chatThemeState.theme
        val appearance = localAppearance.copy(dark = theme.dark)
        var themeOpen by remember { mutableStateOf(false) }
        var settings by remember { mutableStateOf(settingsStore.load()) }
        var settingsOpen by remember { mutableStateOf(false) }
        var shellError by remember { mutableStateOf<String?>(if (activationAvailable) null else "无法启用窗口唤起服务，后台回复提醒不可用。") }
        var trayAvailable by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = {
                if (store == null) {
                    visibility.exit()
                    exitApplication()
                } else visibility.windowClose(settings.closeToTray, trayAvailable)
            },
            title = "Chat Box",
            icon = androidx.compose.ui.res.painterResource("chat-box.png"),
            visible = visibility.visible,
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.K && event.isCtrlPressed && store != null) {
                    historySearchOpen.value = true
                    true
                } else false
            },
            state = rememberWindowState(size = DpSize(1000.dp, 760.dp)),
        ) {
            var composerFocusSerial by remember { mutableLongStateOf(0L) }
            val pendingReplies = remember { mutableStateListOf<ReplyDestination>() }
            var openReply by remember { mutableStateOf<ReplyDestination?>(null) }
            val tray = remember(window) {
                DesktopTrayWindow(window, { if (!visibility.exiting) inbox.post(DesktopActivation.Show) }, {
                    if (store == null) exitApplication() else visibility.exit()
                })
            }
            fun foreground(focusComposer: Boolean) {
                if (visibility.exiting) return
                visibility.show()
                val main = tray.foreground()
                if (main && focusComposer && modalCount.intValue == 0) composerFocusSerial++
            }
            val keysPressed = rememberUpdatedState {
                if (!visibility.hideRequested && visibility.visible && modalCount.intValue == 0 &&
                    tray.trayAvailable && tray.isMainForeground()
                ) visibility.hide(true) else foreground(true)
            }
            val hotkeys = remember(window) { DesktopHotkeySettings { keysPressed.value() } }
            val notifier = remember(tray) {
                DesktopReplyNotifier(settings.notifyReplies, tray::isForeground) {
                    try {
                        WindowsReplyToast.show(DesktopInstanceActivation.toastUri(directoryKey, it))
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            DisposableEffect(window) {
                window.minimumSize = Dimension(480, 400)
                trayAvailable = store != null && tray.installTray()
                if (store != null && settings.hotkeyEnabled) {
                    val conflict = hotkeys.change(settings.hotkey)
                    if (conflict != null) {
                        shellError = listOfNotNull(shellError, "$conflict。快捷键尚未启用，可在“快捷键与托盘”中更改，或直接保存以重试。").joinToString("\n")
                    }
                }
                fun bindActivations() = inbox.bind(accepting = { !visibility.exiting }) { event ->
                    foreground(event == DesktopActivation.Show)
                    if (event is DesktopActivation.OpenReply && store != null) pendingReplies.add(event.target)
                }
                val listener = object : WindowAdapter() {
                    override fun windowOpened(event: WindowEvent) = bindActivations()
                }
                window.addWindowListener(listener)
                if (window.isShowing) bindActivations()
                onDispose {
                    inbox.dispose()
                    window.removeWindowListener(listener)
                    hotkeys.close()
                    tray.close()
                }
            }
            val next = pendingReplies.firstOrNull()
            fun mayNavigate() = modalCount.intValue == 0 && !settingsOpen && shellError == null &&
                !visibility.exiting && !tray.hasVisibleDialog()
            val modal = modalCount.intValue != 0 || settingsOpen || shellError != null
            LaunchedEffect(next, openReply, modal, visibility.exiting) {
                if (next != null && openReply == null && !visibility.exiting && !modal) {
                    // AWT file pickers can outlive the corresponding composition event.
                    while (tray.hasVisibleDialog()) delay(100)
                    if (mayNavigate()) openReply = next
                }
            }
            DesktopWindowEffects(window, appearance) { shellError = it }
            CompositionLocalProvider(LocalModalCount provides modalCount) {
                DesktopTheme(theme.dark, theme) {
                    Surface(Modifier.fillMaxSize()) {
                        if (store == null) Text(listOfNotNull(
                            "无法读取本地聊天历史。", (loaded.exceptionOrNull() as? DesktopHistoryCorruption)?.message,
                            "请先备份历史文件，再检查数据目录。",
                        ).joinToString("\n"), Modifier.padding(16.dp))
                        else DesktopScreen(
                            store, keys, images, window, historySearchOpen,
                            deviceSync = deviceSync,
                            firstDeviceSetup = firstDeviceSetup,
                            onOpenTheme = { themeOpen = true },
                            initialSidebarPinned = settings.recentPanelPinned,
                            onSidebarPinned = { pinned ->
                                val updated = settings.copy(recentPanelPinned = pinned)
                                runCatching { settingsStore.save(updated) }.onSuccess { settings = updated }
                                    .onFailure { shellError = "无法保存最近对话的固定状态。" }
                            },
                            onDeviceSetupSeen = {
                                if (!Files.exists(setupMarker) && runCatching { Files.createFile(setupMarker) }.isFailure)
                                    shellError = "无法保存首启设置。请检查本地数据目录的写入权限。"
                            },
                            closeRequested = visibility.exiting,
                            isClosing = { visibility.exiting },
                            onCloseResult = { saved -> visibility.finishExit(saved, ::exitApplication) { foreground(false) } },
                            hideRequested = visibility.hideRequested,
                            onHideResult = { visibility.finishHide(it, tray.trayAvailable) },
                            windowEvents = visibility,
                            composerFocusSerial = composerFocusSerial,
                            openReply = openReply,
                            canNavigateReply = ::mayNavigate,
                            onOpenReplyHandled = {
                                openReply = null
                                pendingReplies.removeFirstOrNull()
                            },
                            captureReplyNotification = { target, stillLive ->
                                if (activationAvailable) notifier.capture(target, stillLive) else null
                            },
                        ) {
                            DesktopAppearanceControls(
                                appearance, onPreview = { localAppearance = it },
                                onCommit = { updated ->
                                    localAppearance = updated
                                    if (runCatching { appearanceStore.save(updated) }.isFailure) shellError = "无法保存窗口外观设置。"
                                    if (updated.dark != theme.dark) scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                synchronized(chatThemeState) {
                                                    chatThemeState.setTheme(chatThemeState.theme.copy(dark = updated.dark))
                                                }
                                            }
                                        } catch (e: IOException) { shellError = "无法保存主题设置。" }
                                    }
                                },
                            )
                            ShellMenu(trayAvailable, { visibility.hide(tray.trayAvailable) }, { settingsOpen = true })
                        }
                    }
                    if (themeOpen) DesktopThemeEditor(chatThemeState, window) { themeOpen = false }
                    if (settingsOpen) {
                        DesktopModalGuard()
                        DesktopShellSettingsDialog(settings, trayAvailable, hotkeys.registeredKey, onSave = { updated ->
                            val registration = settings.registrationForSave(updated, hotkeys.registeredKey)
                            val error = hotkeys.change(registration) {
                                notifier.saveSettings(updated.notifyReplies) { settingsStore.save(updated) }
                            }
                            if (error == null) settings = updated
                            error
                        }, onDismiss = { settingsOpen = false })
                    }
                    shellError?.let { message ->
                        DesktopModalGuard()
                        AlertDialog(
                            onDismissRequest = { shellError = null },
                            text = { Text(message) },
                            confirmButton = { TextButton(onClick = { shellError = null }) { Text("关闭") } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellMenu(trayAvailable: Boolean, onHide: () -> Unit, onSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp).testTag("shell-menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = "桌面菜单")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(onClick = { expanded = false; onHide() }, enabled = trayAvailable) { Text("收起到托盘") }
            DropdownMenuItem(onClick = { expanded = false; onSettings() }) { Text("快捷键与托盘") }
        }
    }
}
