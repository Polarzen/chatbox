package dev.polarzen.chatbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.SwingUtilities
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
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
        return
    }
    val dataDir = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "Chatbox") }
        ?: Path.of(System.getProperty("user.home"), ".chatbox")
    var lockChannel: FileChannel? = null
    var historyLock: java.nio.channels.FileLock? = null
    var lockFailure: Throwable? = null
    val lockAcquired = runCatching {
        Files.createDirectories(dataDir)
        lockChannel = FileChannel.open(dataDir.resolve("history.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        historyLock = lockChannel?.tryLock()
        historyLock != null
    }.onFailure { lockFailure = it }.getOrDefault(false)
    val store = if (lockAcquired) runCatching { DesktopStore(dataDir.resolve("history.json")) }.getOrNull() else null
    val credentials = DesktopCredentials(dataDir.resolve("credentials"))
    try {
        application {
            Window(onCloseRequest = ::exitApplication, title = "Chatbox · 小鲸鱼",
                state = rememberWindowState(size = DpSize(980.dp, 740.dp))) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        when {
                            !lockAcquired && lockFailure == null -> Text("Chatbox 已在运行，请使用已打开的窗口。", Modifier.padding(16.dp))
                            !lockAcquired -> Text("无法锁定本地聊天历史文件，请检查数据目录权限。", Modifier.padding(16.dp))
                            store == null -> Text("无法读取本地聊天历史。请先备份历史文件，再检查数据目录。", Modifier.padding(16.dp))
                            else -> Screen(store, credentials)
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

private data class SendRetry(val question: String, val image: Path?, val caption: String)
private data class ValidatedTarget(val address: String, val allowLoopbackHttp: Boolean)
private data class ConnectionResult(
    val profileId: String,
    val target: ValidatedTarget,
    val sessions: Set<String>,
    val rememberFailure: Throwable?,
)

private fun canonicalAddress(input: String, allowLoopbackHttp: Boolean = true): String {
    endpoint(input, allowLoopbackHttp)
    return DesktopStore.normalizeAddress(input)
}

private fun resolvedTarget(profileId: String, address: String): ValidatedTarget {
    val targetAddress = if (profileId.startsWith("launcher:")) {
        val identity = profileId.removePrefix("launcher:")
        LauncherInstanceResolver.currentUrl(identity)
            ?: error("AstrBot Launcher 实例当前离线，消息未发送。")
    } else address
    val canonical = canonicalAddress(targetAddress, allowLoopbackHttp = true)
    val target = endpoint(canonical, allowLoopbackHttp = true)
    return ValidatedTarget(canonical, target.scheme.equals("http", ignoreCase = true))
}

private fun safeFailureMessage(error: Throwable, credential: String): String {
    val message = error.message?.trim().orEmpty().ifBlank { "连接或服务错误；回复状态未确认。" }
    return (if (credential.isNotEmpty()) message.replace(credential, "[密钥已隐藏]") else message).take(240)
}

@Composable private fun Screen(store: DesktopStore, credentials: DesktopCredentials) {
    val startupProfileId = remember(store) {
        store.activeProfileId.takeIf { store.profile(it) != null } ?: store.profiles.firstOrNull()?.id.orEmpty()
    }
    var activeProfileId by remember { mutableStateOf(startupProfileId) }
    val initialProfile = remember { store.profile(activeProfileId) }
    var base by remember { mutableStateOf(initialProfile?.address ?: store.lastServer) }
    var addressDraft by remember { mutableStateOf(base) }
    var key by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf(if (activeProfileId.isNotBlank()) store.readProfile(activeProfileId) else Chat("")) }
    var busy by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connectedProfile by remember { mutableStateOf<String?>(null) }
    var connectedAddress by remember { mutableStateOf<String?>(null) }
    var savedCredentialAvailable by remember { mutableStateOf(false) }
    var launcherInstances by remember { mutableStateOf(emptyList<LauncherInstance>()) }
    var connectionPollTick by remember { mutableIntStateOf(0) }
    var sessionsRevision by remember { mutableIntStateOf(0) }
    var pendingLegacySession by remember { mutableStateOf<String?>(null) }
    var autoAuthTargetKey by remember { mutableStateOf<String?>(null) }
    var autoAuthRejectedFor by remember { mutableStateOf<String?>(null) }
    var autoAuthInFlightFor by remember { mutableStateOf<String?>(null) }
    var autoAuthNextAttemptAt by remember { mutableLongStateOf(0L) }
    var manualEntry by remember { mutableStateOf(!activeProfileId.startsWith("launcher:")) }
    var operationId by remember { mutableIntStateOf(0) }
    var partial by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf<SendRetry?>(null) }
    var selectedImage by remember { mutableStateOf<Path?>(null) }
    var profileMenu by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val allProfiles = remember(store, sessionsRevision, activeProfileId) { store.profiles }
    val selectedProfile = remember(allProfiles, activeProfileId) { store.profile(activeProfileId) }
    val isLauncherProfile = activeProfileId.startsWith("launcher:")
    val selectedIdentity = if (isLauncherProfile) activeProfileId.removePrefix("launcher:") else null
    val profileSessions = if (activeProfileId.isNotBlank()) store.sessions(activeProfileId) else emptyList()
    val isLoopback = runCatching { endpoint(base, allowLoopbackHttp = true).scheme.equals("http", true) }.getOrDefault(false)
    val matchingLauncher = launcherInstances.firstOrNull { it.url == runCatching { canonicalAddress(base) }.getOrNull() }
    val draftLauncherMatch = if (manualEntry) launcherInstances.firstOrNull {
        it.url == runCatching { canonicalAddress(addressDraft) }.getOrNull()
    } else null
    val draftDiffersFromActive = manualEntry &&
        runCatching { canonicalAddress(addressDraft) }.getOrNull() != runCatching { canonicalAddress(base) }.getOrNull()
    val credentialLauncherMatch = draftLauncherMatch ?: matchingLauncher.takeUnless { draftDiffersFromActive }
    val hasVerifiedLauncherAddress = isLauncherProfile || credentialLauncherMatch != null
    val credentialProfileId = if (!isLauncherProfile && credentialLauncherMatch != null) {
        "launcher:${credentialLauncherMatch.identity}"
    } else activeProfileId
    val canUseSavedCredential = savedCredentialAvailable && (!isLoopback || hasVerifiedLauncherAddress)
    val hasUnappliedAddress = manualEntry &&
        runCatching { canonicalAddress(addressDraft) }.getOrNull() != runCatching { canonicalAddress(base) }.getOrNull()

    fun selectProfile(profileId: String, address: String) {
        if (busy || connecting) return
        val prior = store.profile(activeProfileId)
        if (prior?.offlineOnly == true && profileId != activeProfileId) {
            pendingLegacySession = prior.currentSession
        } else if (profileId != activeProfileId) {
            pendingLegacySession = null
        }
        if (store.profile(profileId) == null) store.ensureProfile(profileId, address)
        store.selectProfile(profileId)
        activeProfileId = profileId
        base = address
        addressDraft = address
        chat = store.readProfile(profileId)
        manualEntry = !profileId.startsWith("launcher:")
        key = ""
        savedCredentialAvailable = false
        connectedProfile = null
        connectedAddress = null
        autoAuthTargetKey = null
        autoAuthRejectedFor = null
        autoAuthInFlightFor = null
        autoAuthNextAttemptAt = 0L
        note = ""
        sessionsRevision++
    }

    fun useManualAddress(): Boolean {
        val address = runCatching { canonicalAddress(addressDraft, allowLoopbackHttp = true) }
            .getOrElse { note = "请输入有效的 HTTPS 地址，或本机 127.0.0.1 HTTP 地址。"; return false }
        val launcher = launcherInstances.firstOrNull { it.url == address }
        val profileId = launcher?.let { "launcher:${it.identity}" } ?: store.profiles.firstOrNull {
            it.kind == "manual" && !it.offlineOnly && it.address == address
        }?.id ?: "manual:$address"
        selectProfile(profileId, address)
        return true
    }

    fun authenticate(forceStoredCredential: Boolean = false, automaticFor: String? = null) {
        if (busy || connecting) return
        val typedToken = if (forceStoredCredential) "" else key.trim()
        if (!forceStoredCredential && manualEntry && addressDraft.trimEnd('/') != base.trimEnd('/')) {
            if (!useManualAddress()) return
        }
        val profileId = activeProfileId
        if (profileId.isBlank()) {
            note = "请输入服务地址，然后选择“使用地址”。"
            return
        }
        if (store.profile(profileId)?.offlineOnly == true) {
            note = "此档案仅保存离线历史。请选择运行中的 Launcher 实例或输入新的服务地址。"
            return
        }
        val address = base
        if (typedToken.isEmpty() && !forceStoredCredential && !canUseSavedCredential &&
            addressDraft.trimEnd('/') == base.trimEnd('/')) {
            note = "请输入对话 API 密钥。"
            return
        }
        connecting = true
        connectedProfile = null
        connectedAddress = null
        note = "正在验证连接…"
        thread(isDaemon = true, name = "chatbox-authenticate") {
            var verifiedProfileIdForAttempt = profileId
            var verifiedAddressForAttempt = address
            val result = runCatching {
                val launcherMatch = if (profileId.startsWith("launcher:")) null else
                    LauncherInstanceResolver.matchEnteredUrl(address)
                val verifiedProfileId = launcherMatch?.let { "launcher:${it.identity}" } ?: profileId
                verifiedProfileIdForAttempt = verifiedProfileId
                val target = resolvedTarget(verifiedProfileId, address)
                verifiedAddressForAttempt = target.address
                val token = typedToken.ifEmpty {
                    check(!target.allowLoopbackHttp || verifiedProfileId.startsWith("launcher:")) {
                        "手动本机 HTTP 地址不会自动使用已保存的密钥，请重新输入密钥并连接。"
                    }
                    credentials.load(verifiedProfileId) ?: error("没有可用的已保存密钥，请输入密钥。")
                }
                val sessions = fetchDesktopSessions(target.address, token, store.username)
                check(resolvedTarget(verifiedProfileId, address).address == target.address) {
                    "服务地址在验证期间发生变化，请重新连接。"
                }
                val shouldRemember = !target.allowLoopbackHttp || verifiedProfileId.startsWith("launcher:")
                val rememberFailure = if (shouldRemember) runCatching { credentials.save(verifiedProfileId, token) }.exceptionOrNull() else null
                ConnectionResult(verifiedProfileId, target, sessions, rememberFailure)
            }
            SwingUtilities.invokeLater {
                connecting = false
                result.fold({ verified ->
                    val verifiedId = verified.profileId
                    val verifiedTarget = verified.target
                    val verifiedSessions = verified.sessions
                    val savedFailure = verified.rememberFailure
                    if (store.profile(verifiedId) == null) store.ensureProfile(verifiedId, verifiedTarget.address)
                    val adoptFrom = pendingLegacySession ?: store.profile(activeProfileId)
                        ?.takeIf { it.offlineOnly && verifiedId.startsWith("launcher:") }
                        ?.currentSession
                    val adopted = runCatching { store.adoptVerifiedSessions(verifiedId, verifiedTarget.address, verifiedSessions) }
                    if (adopted.isFailure) {
                        if (automaticFor != null) {
                            autoAuthInFlightFor = null
                            autoAuthNextAttemptAt = System.currentTimeMillis() + 20_000L
                        }
                        note = "连接已验证，但无法更新会话列表。${adopted.exceptionOrNull()?.message.orEmpty()}"
                        return@fold
                    }
                    adoptFrom?.takeIf { it in adopted.getOrThrow() }?.let { session ->
                        runCatching { store.selectSession(verifiedId, session) }
                    }
                    pendingLegacySession = null
                    store.selectProfile(verifiedId)
                    activeProfileId = verifiedId
                    manualEntry = !verifiedId.startsWith("launcher:")
                    base = verifiedTarget.address
                    if (addressDraft.trimEnd('/') == address.trimEnd('/')) addressDraft = verifiedTarget.address
                    chat = store.readProfile(verifiedId)
                    sessionsRevision++
                    connectedProfile = verifiedId
                    connectedAddress = verifiedTarget.address
                    val shouldRemember = !verifiedTarget.allowLoopbackHttp || verifiedId.startsWith("launcher:")
                    savedCredentialAvailable = shouldRemember && savedFailure == null
                    if (savedCredentialAvailable) key = ""
                    note = when {
                        !shouldRemember -> "连接已验证。手动本机 HTTP 密钥仅保留在当前窗口。"
                        savedFailure != null -> "连接已验证，但无法安全保存密钥：${savedFailure.message ?: "Windows 凭据保护失败"}"
                        else -> "连接已验证，密钥已安全保存。"
                    }
                    automaticFor?.let {
                        autoAuthInFlightFor = null
                        autoAuthNextAttemptAt = Long.MAX_VALUE
                    }
                }, { error ->
                    val safeMessage = safeFailureMessage(error, typedToken)
                    note = safeMessage
                    if (typedToken.isEmpty() &&
                        (safeMessage.contains("密钥无效") || safeMessage.contains("缺少聊天权限") || safeMessage.contains("权限不足"))
                    ) {
                        val rejectedAddress = runCatching { canonicalAddress(verifiedAddressForAttempt) }.getOrDefault(verifiedAddressForAttempt)
                        autoAuthRejectedFor = "$verifiedProfileIdForAttempt@$rejectedAddress"
                        autoAuthNextAttemptAt = Long.MAX_VALUE
                    }
                    automaticFor?.let { autoKey ->
                        autoAuthInFlightFor = null
                        if (autoAuthRejectedFor == autoKey) autoAuthNextAttemptAt = Long.MAX_VALUE
                    }
                })
            }
        }
    }

    fun send(question: String, image: Path? = null, caption: String = question) {
        val query = question.trim()
        val imageCaption = caption.trim()
        if ((query.isEmpty() && image == null) || busy || connecting) return
        if (image != null) {
            val extension = image.fileName.toString().substringAfterLast('.', "").lowercase()
            if (extension !in setOf("png", "jpg", "jpeg") || !Files.isRegularFile(image) || !Files.isReadable(image)) {
                note = "请选择可读取的 PNG 或 JPEG 图片。"
                return
            }
        }
        val profileId = activeProfileId
        if (profileId.isBlank() || selectedProfile?.offlineOnly == true) {
            note = "离线历史不可发送。请选择运行中的服务或手动服务地址。"
            return
        }
        val address = base
        if (connectedProfile != profileId || connectedAddress != runCatching { canonicalAddress(address, true) }.getOrNull()) {
            note = "请先连接并验证当前服务，再发送消息。"
            return
        }
        val entryQuestion = if (image == null) query else imageCaption
        val pending = chat.copy(entries = chat.entries + Entry(
            entryQuestion, "", "发送中", image?.toString(), image?.fileName?.toString(),
        ))
        if (runCatching { store.saveProfile(profileId, pending) }.isFailure) {
            note = "无法保存本地会话，消息未发送。"
            return
        }
        chat = pending
        draft = ""
        busy = true
        note = ""
        partial = ""
        val entry = pending.entries.last()
        val requestId = ++operationId
        val typedToken = key.trim()
        val verifiedAddress = connectedAddress
        thread(isDaemon = true, name = "chatbox-request") {
            var answer = ""
            var tokenUsed = ""
            var requestStarted = false
            val result = runCatching {
                val target = resolvedTarget(profileId, address)
                check(target.address == connectedAddress) { "服务地址已变化，请重新连接验证后再发送。" }
                val token = if (typedToken.isNotBlank()) typedToken else {
                    check(!target.allowLoopbackHttp || profileId.startsWith("launcher:")) {
                        "手动本机 HTTP 地址需要重新输入密钥。"
                    }
                    credentials.load(profileId) ?: error("已保存密钥不可用，请重新连接。")
                }
                tokenUsed = token
                // Re-resolve Launcher ownership immediately before using a saved credential.
                if (target.address != verifiedAddress) {
                    error("服务地址已变化，消息未发送。请重新连接验证。")
                }
                if (profileId.startsWith("launcher:") && target.address != resolvedTarget(profileId, address).address) {
                    error("AstrBot Launcher 服务地址已变化，消息未发送。请重新连接。")
                }
                if (image == null) {
                    requestStarted = true
                    sendToAstrBot(target.address, token, store.username, pending.session, query,
                        allowLoopbackHttp = target.allowLoopbackHttp) { delta ->
                        answer = delta
                        SwingUtilities.invokeLater { if (operationId == requestId && activeProfileId == profileId) partial = delta }
                    }
                } else {
                    requestStarted = true
                    sendImageToAstrBot(target.address, token, store.username, pending.session, image, imageCaption,
                        allowLoopbackHttp = target.allowLoopbackHttp) { delta ->
                        answer = delta
                        SwingUtilities.invokeLater { if (operationId == requestId && activeProfileId == profileId) partial = delta }
                    }
                }
            }
            SwingUtilities.invokeLater {
                val failure = result.exceptionOrNull()?.let { safeFailureMessage(it, tokenUsed) }
                val completed = pending.copy(entries = pending.entries.dropLast(1) +
                    entry.copy(answer = answer, status = if (result.isSuccess) "已完成" else
                        (if (requestStarted) "发送失败：" else "未发送：") + failure.orEmpty()))
                val saved = runCatching { store.saveProfile(profileId, completed) }.isSuccess
                chat = completed
                busy = false
                partial = ""
                note = when {
                    !saved -> "本地保存失败，请勿直接重发。"
                    result.isSuccess && answer.isEmpty() -> "回复已结束，但没有可显示的文字。"
                    result.isSuccess -> ""
                    else -> failure ?: "连接或解析失败；服务可能已收到消息，请勿直接重发。"
                }
                if (typedToken.isEmpty() && (failure?.contains("密钥无效") == true ||
                        failure?.contains("权限不足") == true || failure?.contains("权限") == true)
                ) {
                    autoAuthRejectedFor = "$profileId@${runCatching { canonicalAddress(address) }.getOrDefault(address)}"
                    autoAuthNextAttemptAt = Long.MAX_VALUE
                    connectedProfile = null
                    connectedAddress = null
                }
                if (result.isSuccess && image != null) {
                    selectedImage = null
                    draft = ""
                }
            }
        }
    }

    fun chooseImage() {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择图片"
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter("PNG / JPEG 图片", "png", "jpg", "jpeg")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val selected = chooser.selectedFile.toPath()
            selectedImage = selected
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val found = withContext(Dispatchers.IO) { runCatching { LauncherInstanceResolver.runningInstances() }.getOrDefault(emptyList()) }
            launcherInstances = found
            connectionPollTick++
            delay(10_000)
        }
    }
    LaunchedEffect(activeProfileId, launcherInstances, busy) {
        if (activeProfileId.startsWith("launcher:")) {
            val id = activeProfileId.removePrefix("launcher:")
            val current = launcherInstances.singleOrNull { it.identity == id }
            if (current == null) {
                connectedProfile = null
                connectedAddress = null
                autoAuthTargetKey = null
                autoAuthInFlightFor = null
                autoAuthNextAttemptAt = 0L
                if (!busy) note = "AstrBot Launcher 实例离线；本地会话仍可查看。"
            } else if (current.url != base && !busy) {
                store.ensureProfile(activeProfileId, current.url)
                val hasManualDraft = manualEntry && addressDraft.trimEnd('/') != base.trimEnd('/')
                base = current.url
                if (!hasManualDraft) addressDraft = current.url
                chat = store.readProfile(activeProfileId)
                connectedProfile = null
                connectedAddress = null
                note = "AstrBot Launcher 地址已变化，请重新连接验证。"
                sessionsRevision++
            }
        }
    }
    LaunchedEffect(activeProfileId, base) {
        savedCredentialAvailable = false
        autoAuthTargetKey = null
        autoAuthRejectedFor = null
        autoAuthInFlightFor = null
        autoAuthNextAttemptAt = 0L
    }
    LaunchedEffect(activeProfileId, credentialProfileId) {
        savedCredentialAvailable = activeProfileId.isNotBlank() && withContext(Dispatchers.IO) {
            runCatching { credentials.contains(credentialProfileId) }.getOrDefault(false)
        }
    }
    LaunchedEffect(activeProfileId, base, launcherInstances, connectionPollTick) {
        if (activeProfileId.isNotBlank() && store.profile(activeProfileId)?.offlineOnly != true) {
            val targetProfileId: String
            val target = if (activeProfileId.startsWith("launcher:")) {
                targetProfileId = activeProfileId
                launcherInstances.singleOrNull { it.identity == activeProfileId.removePrefix("launcher:") }
                    ?.let { runCatching { ValidatedTarget(canonicalAddress(it.url), true) }.getOrNull() }
            } else {
                val canonicalManualAddress = runCatching { canonicalAddress(base) }.getOrNull()
                val match = launcherInstances.firstOrNull { it.url == canonicalManualAddress }
                if (match != null) {
                    targetProfileId = "launcher:${match.identity}"
                    runCatching { ValidatedTarget(canonicalAddress(match.url), true) }.getOrNull()
                } else {
                    targetProfileId = activeProfileId
                    val canonical = canonicalManualAddress
                    canonical?.let { address ->
                        val isHttps = runCatching { endpoint(address, true).scheme.equals("https", true) }.getOrDefault(false)
                        if (isHttps) ValidatedTarget(address, false) else null
                    }
                }
            }
            if (target != null) {
                val autoKey = "$targetProfileId@${target.address}"
                if (autoAuthTargetKey != autoKey) {
                    autoAuthTargetKey = autoKey
                    autoAuthInFlightFor = null
                    autoAuthNextAttemptAt = 0L
                }
                val mayUseSavedCredential = !target.allowLoopbackHttp || targetProfileId.startsWith("launcher:")
                if (mayUseSavedCredential) {
                    val available = withContext(Dispatchers.IO) {
                        runCatching { credentials.contains(targetProfileId) }.getOrDefault(false)
                    }
                    if (targetProfileId == credentialProfileId) savedCredentialAvailable = available
                    if (available && key.isBlank() && connectedProfile != targetProfileId && !connecting &&
                        autoAuthInFlightFor != autoKey && autoAuthRejectedFor != autoKey &&
                        System.currentTimeMillis() >= autoAuthNextAttemptAt
                    ) {
                        autoAuthInFlightFor = autoKey
                        autoAuthNextAttemptAt = System.currentTimeMillis() + 20_000L
                        authenticate(forceStoredCredential = true, automaticFor = autoKey)
                    }
                }
            }
        }
    }
    LaunchedEffect(chat.entries.size, busy) {
        if (chat.entries.isNotEmpty()) scroll.scrollToItem(chat.entries.lastIndex)
    }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chatbox · 小鲸鱼", style = MaterialTheme.typography.h5)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { profileMenu = true }, enabled = !busy && !connecting) {
                val title = when {
                    isLauncherProfile -> "AstrBot Launcher · ${selectedIdentity?.take(8)}"
                    selectedProfile?.offlineOnly == true -> "离线历史"
                    !selectedProfile?.address.isNullOrBlank() -> selectedProfile!!.address.take(32)
                    else -> "选择服务地址"
                }
                Text("配置：$title ▾")
            }
            DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                allProfiles.forEach { profile ->
                    DropdownMenuItem(onClick = { profileMenu = false; selectProfile(profile.id, profile.address) }) {
                        val offline = profile.id.startsWith("launcher:") && launcherInstances.none { it.identity == profile.id.removePrefix("launcher:") }
                        val description = when (profile.kind) {
                            "launcher" -> "AstrBot ${profile.id.removePrefix("launcher:").take(8)}"
                            "legacy" -> "历史 ${profile.address.ifBlank { profile.id.take(8) }}"
                            else -> profile.address
                        }
                        Text("${if (offline || profile.offlineOnly) "离线 · " else ""}${description.take(48)}")
                    }
                }
                launcherInstances.forEach { instance ->
                    val id = "launcher:${instance.identity}"
                    if (allProfiles.none { it.id == id }) {
                        DropdownMenuItem(onClick = {
                            profileMenu = false
                            selectProfile(id, instance.url)
                        }) { Text("运行中 · AstrBot ${instance.identity.take(8)}") }
                    }
                }
            }
            Text(if (connectedProfile == activeProfileId) "已验证" else if (isLauncherProfile && launcherInstances.none { it.identity == selectedIdentity }) "离线" else "未连接",
                style = MaterialTheme.typography.caption)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = addressDraft,
                onValueChange = { addressDraft = it },
                modifier = Modifier.weight(1f), enabled = !busy && !connecting && (!isLauncherProfile || manualEntry),
                singleLine = true, label = { Text("服务地址（HTTPS 或本机 127.0.0.1 HTTP）") },
            )
            if (!isLauncherProfile || manualEntry) Button(onClick = { useManualAddress() }, enabled = !busy && !connecting) { Text("使用地址") }
            else TextButton(onClick = { manualEntry = true; addressDraft = "" }, enabled = !busy && !connecting) { Text("手动地址") }
        }
        if (!isLauncherProfile && matchingLauncher != null) {
            TextButton(onClick = { selectProfile("launcher:${matchingLauncher.identity}", matchingLauncher.url) }, enabled = !busy && !connecting) {
                Text("检测到 Launcher 运行实例；切换到已验证配置")
            }
        }
        if (hasUnappliedAddress) Text("地址尚未应用；当前会话仍使用上方已选配置。", style = MaterialTheme.typography.caption)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(key, {
                key = it
                connectedProfile = null
                connectedAddress = null
                autoAuthTargetKey = null
                autoAuthRejectedFor = null
                autoAuthInFlightFor = null
                autoAuthNextAttemptAt = 0L
            }, Modifier.weight(1f), enabled = !busy && !connecting, singleLine = true,
                label = { Text(if (savedCredentialAvailable) "输入新密钥以替换，或留空使用已保存密钥" else "对话 API 密钥") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Button(onClick = { authenticate() }, enabled = !busy && !connecting && selectedProfile?.offlineOnly != true &&
                (key.isNotBlank() || canUseSavedCredential)) {
                Text(if (connecting) "验证中…" else "连接 / 验证")
            }
            if (savedCredentialAvailable) TextButton(onClick = {
                val id = credentialProfileId
                thread(isDaemon = true, name = "chatbox-clear-credential") {
                    val result = runCatching { credentials.clear(id) }
                    SwingUtilities.invokeLater {
                        savedCredentialAvailable = false
                        connectedProfile = null
                        connectedAddress = null
                        note = if (result.isSuccess) "已清除此配置的已保存密钥。" else "无法清除已保存密钥。"
                    }
                }
            }, enabled = !busy && !connecting) { Text("清除") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { sessionMenu = true }, enabled = !busy && !connecting) {
                val session = profileSessions.firstOrNull { it.selected }
                Text("会话：${session?.id?.take(8) ?: chat.session.take(8)} ▾")
            }
            DropdownMenu(expanded = sessionMenu, onDismissRequest = { sessionMenu = false }) {
                profileSessions.forEach { session ->
                    DropdownMenuItem(onClick = {
                        sessionMenu = false
                        store.selectSession(activeProfileId, session.id)
                        chat = store.readProfile(activeProfileId, session.id)
                    }) { Text("${session.id.take(12)} · ${session.entryCount} 条${if (session.selected) " ✓" else ""}") }
                }
            }
            TextButton(onClick = {
                if (!busy && !connecting) {
                    chat = store.createSession(activeProfileId)
                    sessionsRevision++
                    note = "已创建新会话。"
                }
            }, enabled = !busy && !connecting && activeProfileId.isNotBlank() && selectedProfile?.offlineOnly != true) { Text("新会话") }
            if (selectedProfile?.offlineOnly == true || (isLauncherProfile && launcherInstances.none { it.identity == selectedIdentity }) ) {
                Text("离线历史", style = MaterialTheme.typography.caption)
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chat.entries) { item ->
                if (item.question.isNotBlank()) Text("你：${item.question}")
                item.attachmentDisplayName?.let { Text("图片：$it", style = MaterialTheme.typography.caption) }
                val answer = if (busy && item === chat.entries.lastOrNull()) partial else item.answer
                if (answer.isNotBlank()) Text("助手：$answer")
                if (item.status != "已完成") Text(item.status, style = MaterialTheme.typography.caption)
                if (!busy && item.status != "已完成") {
                    TextButton(onClick = {
                        val image = item.attachmentReference?.let { runCatching { Path.of(it) }.getOrNull() }
                        retry = SendRetry(item.question, image, item.question)
                    }, enabled = !connecting && selectedProfile?.offlineOnly != true && connectedProfile == activeProfileId) {
                        Text("重试这条")
                    }
                }
            }
        }
        if (note.isNotBlank()) Text(note, color = MaterialTheme.colors.error)
        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), enabled = !busy && !connecting, maxLines = 4,
            label = { Text(if (selectedImage == null) "输入消息" else "图片说明（可选）") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick = { chooseImage() }, enabled = !busy) { Text("选择 PNG/JPEG") }
            selectedImage?.let { path ->
                Text(path.fileName.toString(), modifier = Modifier.weight(1f))
                TextButton(onClick = { selectedImage = null }, enabled = !busy) { Text("移除图片") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(onClick = { send(draft, selectedImage, draft) },
                enabled = !busy && !connecting && !hasUnappliedAddress && connectedProfile == activeProfileId &&
                    selectedProfile?.offlineOnly != true && (draft.isNotBlank() || selectedImage != null)) {
                Text(if (busy) "等待回复…" else "发送")
            }
        }
        retry?.let { request ->
            AlertDialog(onDismissRequest = { retry = null }, title = { Text("重新发送？") },
                text = { Text("此条消息可能已到达 AstrBot，继续可能重复执行。") },
                confirmButton = { TextButton(onClick = {
                    retry = null
                    val caption = request.caption.ifBlank { request.question.substringBefore("\n[图片：") }
                    send(request.question, request.image, caption)
                }) { Text("确认重发") } },
                dismissButton = { TextButton(onClick = { retry = null }) { Text("取消") } })
        }
    }
}
