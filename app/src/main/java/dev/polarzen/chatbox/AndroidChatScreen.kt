package dev.polarzen.chatbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Calendar
import java.util.Date

private data class MobilePreviewObservation(
    val draft: MobileDraft,
    val through: Long,
    val revision: String,
    val conditions: Map<String, Boolean>,
)

@Composable
internal fun AndroidChatScreen(
    history: AndroidHistory, connections: AndroidConnections,
    session: MobileSession, sessions: List<MobileSession>, busy: Boolean, readOnly: Boolean,
    note: String, jumpTurn: String?, onJumpHandled: () -> Unit, onSelect: (String) -> Unit,
    onCreate: () -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit,
    onDraftText: (String) -> Unit, onRemoveAsset: (MobileAsset) -> Unit,
    onAddAssets: (List<MobileAsset>) -> Unit, onSend: () -> Unit, onRetry: (MobileTurn) -> Unit,
    onRetryMedia: (MobileTurn, MobileAsset) -> Unit,
    onSettings: () -> Unit, onTheme: () -> Unit, onAppearance: () -> Unit, onHeart: () -> Unit,
    onRecent: (() -> Unit)? = null,
    onDeviceSettings: (() -> Unit)? = null,
    readDraft: (() -> String)? = null,
    draftEvent: Any? = null,
    mediaAvailable: Boolean = true,
    deviceStatus: String? = null,
    wideRecent: (@Composable () -> Unit)? = null,
    preferences: ChatPreferenceHandle,
    connectionEpoch: Any?,
    previewContext: suspend (ConversationPreferences, List<ContextTurn>, MobileDraft) -> AstrBotContextPreview,
    sendReviewed: (MobileTurn?, MobileDraft, ConversationPreferences, AstrBotContextPreview) -> Unit,
) {
    val base = connections.base
    val scopeId = "${base.length}:$base:${session.id}"
    val readings = rememberSaveableStateHolder()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var search by rememberSaveable(base) { mutableStateOf(false) }
    var selectedHit by remember(history, base) { mutableStateOf<MobileHistoryHit?>(null) }
    var retry by rememberSaveable(scopeId) { mutableStateOf<String?>(null) }
    var mediaNote by rememberSaveable(scopeId) { mutableStateOf("") }
    var recording by remember(scopeId) { mutableStateOf(false) }
    var editName by rememberSaveable(preferences.identity) { mutableStateOf(false) }
    var editContext by rememberSaveable(preferences.identity) { mutableStateOf(false) }
    var previewing by remember(preferences.identity) { mutableStateOf(false) }
    var previewRetry by remember(preferences.identity) { mutableStateOf<MobileTurn?>(null) }
    var pinCandidate by remember(preferences.identity) { mutableStateOf<ContextPin?>(null) }
    val prefs = preferences.value
    val custom = prefs.recentTurns != null
    fun submit(turn: MobileTurn? = null) {
        if (custom) { previewRetry = turn; previewing = true }
        else if (turn != null) onRetry(turn) else onSend()
    }
    fun liveDraft() = session.draft.copy(text = readDraft?.invoke() ?:
        history.sessions(base).firstOrNull { it.id == session.id }?.draft?.text.orEmpty())
    var observation by remember(preferences.identity, connectionEpoch, session.turns.lastOrNull(),
        session.draft.attachments, prefs.recentTurns, prefs.pins) { mutableStateOf<MobilePreviewObservation?>(null) }
    var previewNotices by remember(observation) { mutableStateOf(emptySet<String>()) }
    val observed = observation
    LaunchedEffect(observed, preferences.identity) {
        if (observed == null || liveDraft() != observed.draft) return@LaunchedEffect
        for ((kind, active) in observed.conditions) {
            try {
                val claimed = preferences.observeNotice(kind, observed.through, active)
                if (active && claimed && observation == observed && liveDraft() == observed.draft)
                    previewNotices = previewNotices + kind
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A later verified preview can arbitrate a notice after reconnection.
            }
        }
    }
    val remindAfter = prefs.remindAfter
    var reminder by remember(preferences.identity, remindAfter) { mutableStateOf(false) }
    val completed = if (remindAfter == null) 0 else completedContextTurns(mobileContextTurns(session, preferences))
    val reminderDisputed = "remindAfter" in preferences.conflicts
    LaunchedEffect(preferences.identity, remindAfter, preferences.ready, reminderDisputed, connectionEpoch,
        remindAfter != null && completed >= remindAfter) {
        if (remindAfter != null && completed >= remindAfter && preferences.ready && !reminderDisputed) {
            try { if (preferences.claimReminder(completed)) reminder = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The service will arbitrate when the connection returns. */ }
        }
    }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val screenFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeDrawer() { scope.launch { drawer.close() } }
    fun openSearch() { closeDrawer(); search = true }
    val profileSessions = remember(sessions, session) {
        sessions.map { if (it.id == session.id) session else it }
    }
    val hit = selectedHit?.takeIf { it.sessionId == session.id && it.stillMatches(session) }
    LaunchedEffect(hit) {
        if (hit?.side == MobileHistorySide.TITLE) { delay(2000); selectedHit = null }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.K) {
                    openSearch(); true
                } else false
            }.focusRequester(screenFocus).focusable(),
    ) {
        LaunchedEffect(Unit) { screenFocus.requestFocus() }
        val wide = maxWidth >= 720.dp
        val compactHeight = maxHeight < 480.dp
        val imeBottom = WindowInsets.ime.only(WindowInsetsSides.Bottom)
        var sidebarVisible by rememberSaveable { mutableStateOf(true) }
        var localPanel by rememberSaveable { mutableStateOf(false) }
        val openLocalHistory = {
            if (wide) { localPanel = true; sidebarVisible = true } else {
                focus.clearFocus()
                keyboard?.hide()
                scope.launch { drawer.open() }
            }
            Unit
        }
        val drawerWidth = minOf(310.dp, maxWidth * .87f)
        LaunchedEffect(wide) { if (wide) drawer.close() }
        BackHandler(!wide && drawer.isOpen) { closeDrawer() }
        val sidebar: @Composable (Modifier, Boolean) -> Unit = { modifier, overlay ->
            AndroidSessionSidebar(modifier, profileSessions, session.id,
                onSelect = { id -> selectedHit = null; onSelect(id); if (overlay) closeDrawer() },
                onCreate = { selectedHit = null; onCreate(); if (overlay) closeDrawer() },
                onRename = onRename,
                onDelete = { id -> onDelete(id); readings.removeState("${base.length}:$base:$id") },
                onDismiss = if (overlay) ::closeDrawer else null)
        }
        ModalNavigationDrawer(
            modifier = Modifier.windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            drawerState = drawer,
            gesturesEnabled = !wide && (onRecent == null || drawer.isOpen),
            drawerContent = {
                if (!wide && (onRecent == null || deviceStatus == null || drawer.isOpen)) {
                    val closedOffset = with(LocalDensity.current) { -drawerWidth.roundToPx() }
                    val visible by remember(drawer, closedOffset) {
                        derivedStateOf { drawer.isOpen || drawer.currentOffset > closedOffset }
                    }
                    ModalDrawerSheet(modifier = Modifier.width(drawerWidth),
                        windowInsets = if (visible) imeBottom else WindowInsets(0, 0, 0, 0)) {
                        sidebar(Modifier.fillMaxSize(), true)
                    }
                }
            },
        ) {
            Row(Modifier.fillMaxSize()) {
                if (wide && sidebarVisible) {
                    Box(Modifier.width(268.dp).fillMaxHeight().windowInsetsPadding(imeBottom)) {
                        if (wideRecent != null && !localPanel) wideRecent() else sidebar(Modifier.fillMaxSize(), false)
                    }
                    VerticalDivider()
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().clipToBounds().wrapContentHeight(Alignment.Top, unbounded = true)) {
                            ChatHeader(connections, prefs.effectiveName, session.title, hit?.takeIf { it.side == MobileHistorySide.TITLE },
                                sidebarOpen = wide && sidebarVisible && !localPanel,
                                onSidebar = {
                                    if (wide) {
                                        sidebarVisible = localPanel || !sidebarVisible
                                        localPanel = false
                                    } else (onRecent ?: openLocalHistory)()
                                }, onSearch = ::openSearch, onName = { editName = true },
                                onContext = { editContext = true }, onSettings = onSettings, onTheme = onTheme, onAppearance = onAppearance,
                                onHeart = onHeart, onDeviceSettings = onDeviceSettings,
                                onLocalHistory = openLocalHistory.takeIf { onRecent != null && deviceStatus == null },
                                deviceStatus = deviceStatus)
                        }
                        HorizontalDivider()
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            AndroidThemeWallpaper(Modifier.matchParentSize())
                            readings.SaveableStateProvider(scopeId) {
                                ChatTimeline(session, busy, readOnly, hit, jumpTurn, onJumpHandled,
                                    onHitShown = { selectedHit = null },
                                    onRetry = { if (deviceStatus != null) onRetry(it) else retry = it.id }, onRetryMedia = onRetryMedia,
                                    retryLabel = if (deviceStatus != null) "核对发送状态" else "重试",
                                    preferences = preferences, onPin = { pin ->
                                        if ("pins" in preferences.conflicts || !custom) {
                                            pinCandidate = pin.takeUnless { "pins" in preferences.conflicts }
                                            editContext = true
                                        } else {
                                            val pins = if (pin in prefs.pins) prefs.pins - pin else prefs.pins + pin
                                            runCatching { preferences.save(prefs.copy(pins = pins)) }
                                                .onFailure { mediaNote = it.message ?: "固定消息未保存。" }
                                        }
                                    })
                            }
                        }
                        val notice = listOf(note, mediaNote).filter(String::isNotBlank).distinct().joinToString("\n")
                        if (notice.isNotBlank()) {
                            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                                .heightIn(max = if (compactHeight) 38.dp else 84.dp)
                                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                                Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (session.draft.attachments.isNotEmpty()) {
                            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                                .heightIn(max = if (compactHeight) 52.dp else 110.dp)
                                .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                                AndroidAssetList(session.draft.attachments, onRemove = onRemoveAsset)
                            }
                        }
                    }
                    Column(Modifier.windowInsetsPadding(imeBottom)) {
                        val visibleNotices = if (previewNotices.isNotEmpty() && observed != null && observed.draft == liveDraft()) previewNotices else emptySet()
                        val noticeLabel = buildList {
                            if ("capacity" in visibleNotices) add("模型容量预计不足")
                            if ("rolling" in visibleNotices) add("下一轮将移出最近范围内的消息")
                            if (reminder) add("自设轮数提醒 · 已达到 ${remindAfter} 轮")
                        }.joinToString(" · ")
                        if (noticeLabel.isNotBlank()) {
                            val dismiss = { reminder = false; observation = null; previewNotices = emptySet() }
                            ContextReminderBar(noticeLabel, onAdjust = { dismiss(); editContext = true },
                                onNew = onCreate, onDismiss = dismiss)
                        }
                        if (readOnly) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("旧版共享记录暂不可发送。草稿保存在本机。", Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onCreate) { Text("新建会话") }
                            }
                        }
                        Surface(tonalElevation = 1.dp) {
                            AndroidChatComposer(sessionKey = scopeId,
                                readDraft = readDraft ?: { history.sessions(base).firstOrNull { it.id == session.id }?.draft?.text.orEmpty() },
                                draftEvent = Triple(draftEvent, session.turns.lastOrNull()?.id, session.draft.text.isEmpty()),
                                onDraftText = { text -> observation = null; onDraftText(text) }, onSend = { submit() }, sendEnabled = !busy && !readOnly && !recording,
                                hasAttachments = session.draft.attachments.isNotEmpty(), compact = compactHeight,
                                onPasteImages = { mediaAvailable && !busy && pasteMobileImages(context, onAddAssets) { mediaNote = it } },
                                actions = {
                                    if (mediaAvailable) AndroidMediaActions(scopeId, enabled = !busy, onAdd = onAddAssets,
                                        onNote = { mediaNote = it }, onRecordingChanged = { recording = it })
                                })
                        }
                    }
                }
            }
        }
    }
    if (editName) AndroidNameDialog(preferences) { editName = false }
    if (editContext || previewing) key(preferences.identity) {
        val turns = mobileContextTurns(session, preferences)
        val retryTurn = previewRetry.takeIf { previewing }
        val draft = if (retryTurn != null) MobileDraft(retryTurn.question, retryTurn.sent)
            else liveDraft()
        val requestPrefs = if (previewing || custom) prefs else prefs.copy(recentTurns = 0)
        val requestDraft = if (editContext) MobileDraft(draft.text.ifBlank { "预览" }) else draft
        val result by key(turns, requestDraft, requestPrefs.recentTurns, requestPrefs.pins, connectionEpoch, editContext,
            preferences.ready, preferences.contextPending) {
            produceState<AstrBotContextPreview?>(null) {
                value = if (requestDraft.attachments.isNotEmpty()) AstrBotContextPreview.unavailable(CUSTOM_CONTEXT_MEDIA_NOTICE)
                else try { previewContext(requestPrefs, turns, requestDraft) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { AstrBotContextPreview.unavailable(e.message ?: CONTEXT_UNAVAILABLE) }
            }
        }
        LaunchedEffect(result, requestPrefs.recentTurns, requestPrefs.pins, requestDraft, turns, retryTurn, preferences.identity) {
            val returned = result ?: return@LaunchedEffect
            if (!returned.supported || retryTurn != null || !requestPrefs.sameContext(prefs) || requestDraft != liveDraft() ||
                requestDraft.text.isBlank() || preferences.contextPending) return@LaunchedEffect
            val conditions = contextNoticeConditions(returned, turns, prefs)
            if (conditions.isNotEmpty()) {
                val through = turns.maxOfOrNull { if (it.status == "complete") it.seq else 0L } ?: 0L
                observation = MobilePreviewObservation(requestDraft, through, returned.revision, conditions)
            }
        }
        if (previewing) AndroidContextPreview(preferences, draft.text, result,
            canSend = !busy && !readOnly && custom && preferences.ready && !preferences.contextPending,
            adjust = { previewing = false; editContext = true },
            onSend = {
                val checked = result
                if (previewing && checked?.canSend == true) {
                    previewing = false
                    sendReviewed(retryTurn, draft, requestPrefs, checked)
                }
            }, onDismiss = { previewing = false })
        if (editContext) AndroidContextDialog(preferences, turns, result?.supported == true,
            capabilityNote = when {
                result == null -> "正在检查服务能力…"
                result?.supported == true -> "服务支持自选上下文。发送前可核对实际消息与容量。"
                else -> result?.reason.orEmpty()
            }, addPin = pinCandidate, onDismiss = { editContext = false; pinCandidate = null })
    }
    if (search) {
        AndroidHistorySearch(profileSessions, onDismiss = { search = false }) {
            search = false
            keyboard?.hide()
            if (it.sessionId != session.id) onSelect(it.sessionId)
            selectedHit = it
        }
    }
    session.turns.find { it.id == retry }?.let { turn ->
        AlertDialog(onDismissRequest = { retry = null }, title = { Text("重新发送？") },
            text = { Text("上一条可能已经被 AstrBot 接收。请先核对。继续会发送一条新消息，可能重复执行。") },
            confirmButton = { TextButton(enabled = !busy && !readOnly, onClick = {
                retry = null; submit(turn)
            }) { Text("确认重发") } },
            dismissButton = { TextButton(onClick = { retry = null }) { Text("取消") } })
    }
}

@Composable
private fun ContextReminderBar(label: String, onAdjust: () -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    var actions by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            TextButton(onClick = { actions = true }) { Text("处理") }
            DropdownMenu(actions, { actions = false }) {
                DropdownMenuItem(text = { Text("调整上下文") }, onClick = { actions = false; onAdjust() })
                DropdownMenuItem(text = { Text("新开会话") }, onClick = { actions = false; onNew() })
            }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "稍后处理") }
    }
}

@Composable
private fun ChatHeader(
    connections: AndroidConnections, peerName: String, title: String, titleHit: MobileHistoryHit?,
    sidebarOpen: Boolean, onSidebar: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit,
    onTheme: () -> Unit, onAppearance: () -> Unit, onHeart: () -> Unit, onName: () -> Unit, onContext: () -> Unit,
    onDeviceSettings: (() -> Unit)?, onLocalHistory: (() -> Unit)?, deviceStatus: String?,
) {
    var more by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onSidebar) {
            Icon(painterResource(R.drawable.chat_recent), if (sidebarOpen) "收起最近对话" else "打开最近对话")
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(peerName, style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (titleHit != null) Text(mobileHighlightedText(titleHit.excerpt, titleHit.excerptStart, titleHit.query.length),
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onTheme) { Icon(painterResource(R.drawable.chat_theme), "主题") }
        Box {
            IconButton(onClick = { more = true }) { Icon(painterResource(R.drawable.chat_more), "更多操作") }
            DropdownMenu(more, onDismissRequest = { more = false }) {
                listOfNotNull(
                    "修改对方名称" to onName, "上下文" to onContext, "搜索聊天记录" to onSearch, "会话详情" to { details = true },
                    ("连接设置" to onSettings).takeIf { deviceStatus == null },
                    onDeviceSettings?.let { "设备设置" to it }, onLocalHistory?.let { "本机历史" to it },
                    "外观与通知" to onAppearance, "心率" to onHeart,
                ).forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { more = false; action() })
                }
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("会话详情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (deviceStatus != null) Text(deviceStatus) else {
                    Text(connections.services.firstOrNull { it.base == connections.base }?.name ?: "本机")
                    if (connections.base.isNotBlank()) Text(connections.base)
                    Text(connections.status)
                    if (connections.keyName.isNotBlank()) Text(connections.keyName)
                }
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("完成") } })
}

@Composable
private fun AndroidSessionSidebar(
    modifier: Modifier, sessions: List<MobileSession>, active: String,
    onSelect: (String) -> Unit, onCreate: () -> Unit, onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit, onDismiss: (() -> Unit)?,
) {
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val recent = remember(sessions) { sessions.sortedByDescending { it.turns.lastOrNull()?.createdAt ?: 0L } }
    Surface(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本机历史", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onCreate) { Icon(Icons.Outlined.Add, "新建会话") }
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(recent, key = { it.id }) { item ->
                    val activeRow = item.id == active
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (activeRow) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .semantics { selected = activeRow }.clickable(role = Role.Button) { onSelect(item.id) }
                        .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (item.turns.isNotEmpty()) Text("${item.turns.size} 轮",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(painterResource(R.drawable.chat_more), "会话 ${item.title} 菜单")
                            }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; editId = item.id })
                                DropdownMenuItem(text = { Text("删除会话", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; deleteId = item.id })
                            }
                        }
                    }
                }
            }
        }
    }
    sessions.firstOrNull { it.id == editId }?.let { item ->
        var name by rememberSaveable(item.id) { mutableStateOf(item.title) }
        AlertDialog(onDismissRequest = { editId = null }, title = { Text("重命名会话") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true,
                        label = { Text("会话名称") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (name.isNotBlank()) { onRename(item.id, name.trim()); editId = null }
                        }))
                    if (item.manualName != null) TextButton(onClick = {
                        onRename(item.id, ""); editId = null
                    }) { Text("恢复默认名称") }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                onRename(item.id, name.trim()); editId = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editId = null }) { Text("取消") } })
    }
    sessions.firstOrNull { it.id == deleteId }?.let { item ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除会话？") },
            text = { Text("将删除本机保存的这段聊天记录“${item.title}”。确定删除吗？此操作无法撤销。") },
            confirmButton = { TextButton(onClick = { onDelete(item.id); deleteId = null }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } })
    }
}

private fun chatReadingSaver(turns: List<MobileTurn>) = listSaver<ChatReading, Any>(
    save = { reading ->
        val list = reading.list
        val index = list.firstVisibleItemIndex
        val offset = list.firstVisibleItemScrollOffset
        val layout = list.layoutInfo
        val row = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (list.canScrollBackward && row != null) listOf(index, offset, row.key, reading.viewportHeight)
        else listOf(index, offset)
    },
    restore = { saved ->
        var index = saved[0] as Int
        val offset = saved[1] as Int
        val key = saved.getOrNull(2) as? String
        if (key != null && index > 0) {
            val turnId = key.substringBeforeLast('/')
            val turn = turns.indexOfFirst { it.id == turnId }
            if (turn >= 0) {
                val assistant = key.endsWith("/1")
                index = (turns.size - turn) * 2 - if (assistant) 1 else 0
            }
        }
        ChatReading(LazyListState(index, offset), saved.getOrNull(3) as? Int ?: -1)
    },
)

@Composable
private fun ChatTimeline(
    session: MobileSession, busy: Boolean, readOnly: Boolean, hit: MobileHistoryHit?,
    jumpTurn: String?, onJumpHandled: () -> Unit, onHitShown: () -> Unit,
    onRetry: (MobileTurn) -> Unit, onRetryMedia: (MobileTurn, MobileAsset) -> Unit,
    preferences: ChatPreferenceHandle, onPin: (ContextPin) -> Unit, retryLabel: String,
) {
    if (session.turns.isEmpty()) {
        Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            val label = if (LocalChatTheme.current.wallpaper == null) Modifier else Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
            Column(label, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("开始一段对话", style = MaterialTheme.typography.titleMedium)
                Text("在下方输入消息。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (jumpTurn != null) LaunchedEffect(jumpTurn) { onJumpHandled() }
        return
    }
    val reading = rememberSaveable(saver = chatReadingSaver(session.turns)) { ChatReading() }
    val list = reading.list
    var center by remember { mutableFloatStateOf(0f) }
    var targetLine by remember(hit) { mutableStateOf<Float?>(null) }
    var markedTurn by remember { mutableStateOf<String?>(null) }
    val finished by rememberUpdatedState(onHitShown)
    val handled by rememberUpdatedState(onJumpHandled)
    val bodyHit = hit?.takeIf { it.turnId != null }
    val messageCount = session.turns.size * 2
    val observer = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) finished()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(bodyHit) {
        if (bodyHit == null) return@LaunchedEffect
        val round = session.turns.indexOfFirst { it.id == bodyHit.turnId }
        if (round < 0) return@LaunchedEffect
        list.scrollToItem(messageCount - round * 2 - if (bodyHit.side == MobileHistorySide.ASSISTANT) 1 else 0)
        snapshotFlow { targetLine }.filterNotNull().first()
        // Follow the measured line while the search keyboard closes and media previews acquire their size.
        withTimeoutOrNull(2000) {
            snapshotFlow { targetLine?.minus(center) }.filterNotNull().collect { delta ->
                if (kotlin.math.abs(delta) >= 1f) list.scrollBy(-delta)
            }
        }
        finished()
    }
    LaunchedEffect(jumpTurn) {
        if (jumpTurn == null) return@LaunchedEffect
        val round = session.turns.indexOfFirst { it.id == jumpTurn }
        if (round >= 0) {
            val index = messageCount - round * 2 - 1
            list.scrollToItem(index)
            val layout = list.layoutInfo
            layout.visibleItemsInfo.firstOrNull { it.index == index }?.let { row ->
                list.scrollBy((row.size - (layout.viewportEndOffset - layout.viewportStartOffset)).toFloat())
            }
            markedTurn = jumpTurn
        }
        handled()
    }
    LaunchedEffect(markedTurn) {
        if (markedTurn != null) { delay(2000); markedTurn = null }
    }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        val hitGeometry = if (bodyHit == null) Modifier else Modifier.nestedScroll(observer).onGloballyPositioned {
            center = it.positionInRoot().y + it.size.height / 2f
        }
        ChatHistoryViewport(Modifier.fillMaxSize().then(hitGeometry), reading) {
            LazyColumn(Modifier.fillMaxSize().testTag("message-region"), state = list, reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "end-of-chat") { Spacer(Modifier.height(1.dp)) }
                items(messageCount, key = { reversed ->
                    val index = messageCount - 1 - reversed
                    "${session.turns[index / 2].id}/${index % 2}"
                }) { reversed ->
                    val index = messageCount - 1 - reversed
                    val turn = session.turns[index / 2]
                    val user = index % 2 == 0
                    val side = if (user) MobileHistorySide.USER else MobileHistorySide.ASSISTANT
                    val relevantHit = bodyHit?.takeIf { it.turnId == turn.id && it.side == side }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (user) GroupTime(turn.createdAt, session.turns.getOrNull(index / 2 - 1)?.createdAt)
                        val pin = preferences.sequenceAt(index / 2)?.takeIf { preferences.ready &&
                            turn.status == "已完成" && (if (user) turn.question else turn.answer).isNotBlank() }
                            ?.let { ContextPin(it, if (user) "user" else "assistant") }
                        ChatBubble(turn, user, relevantHit, markedTurn == turn.id && !user,
                            peerName = preferences.value.effectiveName, canRetry = !busy && !readOnly,
                            onRetry = { onRetry(turn) }, retryLabel = retryLabel,
                            onRetryMedia = { asset -> onRetryMedia(turn, asset) }, onLine = { targetLine = it },
                            pinAction = pin?.let {
                                (if (it in preferences.value.pins) "取消固定" else "加入上下文") to { onPin(it) }
                            })
                    }
                }
            }
        }
        if (list.canScrollBackward) {
            OutlinedButton(onClick = { finished(); scope.launch { list.scrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Text("回到最新")
            }
        }
    }
}

@Composable
private fun GroupTime(now: Long, previous: Long?) {
    if (now <= 0) return
    val context = LocalContext.current
    val label = remember(context, now, previous) {
        val day = Calendar.getInstance().apply { timeInMillis = now }
        val prior = previous?.takeIf { it > 0 }?.let { Calendar.getInstance().apply { timeInMillis = it } }
        val newDay = prior == null || day.get(Calendar.YEAR) != prior.get(Calendar.YEAR) ||
            day.get(Calendar.DAY_OF_YEAR) != prior.get(Calendar.DAY_OF_YEAR)
        val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(now))
        when {
            newDay -> android.text.format.DateFormat.getDateFormat(context).format(Date(now)) + "  " + time
            now - previous >= 15 * 60_000L -> time
            else -> ""
        }
    }
    if (label.isNotEmpty()) {
        val contrast = if (LocalChatTheme.current.wallpaper == null) Modifier else Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp)
        Text(label, Modifier.padding(vertical = 8.dp).then(contrast),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatBubble(
    turn: MobileTurn, user: Boolean, hit: MobileHistoryHit?, marked: Boolean,
    canRetry: Boolean, onRetry: () -> Unit, onRetryMedia: (MobileAsset) -> Unit, onLine: (Float) -> Unit,
    pinAction: Pair<String, () -> Unit>?, retryLabel: String, peerName: String,
) {
    val colors = MaterialTheme.colorScheme
    val source = if (user) turn.question else turn.answer
    val assets = if (user) turn.sent else turn.replies
    val bubble = if (user) colors.primaryContainer else colors.surface
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val actions = buildList<Pair<String, () -> Unit>> {
        if (source.isNotEmpty()) add("复制正文" to { clipboard.setText(AnnotatedString(source)) })
        pinAction?.let(::add)
        if (!user && canRetry && turn.status != "已完成") add(retryLabel to onRetry)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val limit = minOf(680.dp, maxWidth * (if (user) .84f else .92f))
        Box(Modifier.align(if (user) Alignment.CenterEnd else Alignment.CenterStart)) {
            Surface(
                modifier = Modifier
                    .widthIn(max = limit).semantics { stateDescription = if (user) "你" else peerName }
                    .then(if (actions.isEmpty()) Modifier else Modifier
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { menu = true }) }
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.Menu ||
                                (it.key == Key.F10 && it.isShiftPressed))) { menu = true; true } else false
                        }.focusable()
                        .semantics(mergeDescendants = true) {
                            onLongClick("消息操作") { menu = true; true }
                            customActions = actions.map { (label, action) ->
                                CustomAccessibilityAction(label) { action(); true }
                            }
                        }),
                shape = if (user) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp)
                    else RoundedCornerShape(5.dp, 18.dp, 18.dp, 18.dp),
                color = bubble, contentColor = colors.onSurface,
                border = if (marked) BorderStroke(2.dp, colors.primary) else null,
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                    if (user && assets.isNotEmpty()) AndroidAssetList(assets)
                    if (source.isNotEmpty()) {
                        var layout by remember(hit) { mutableStateOf<TextLayoutResult?>(null) }
                        var top by remember(hit) { mutableStateOf<Float?>(null) }
                        val offset = hit?.start
                        val measured = layout
                        val textTop = top
                        if (offset != null && measured != null && textTop != null &&
                            measured.layoutInput.text.text == source && offset in source.indices) {
                            val line = measured.getLineForOffset(offset)
                            SideEffect { onLine(textTop + (measured.getLineTop(line) + measured.getLineBottom(line)) / 2f) }
                        }
                        Text(if (hit == null) AnnotatedString(source)
                                else mobileHighlightedText(source, hit.start, hit.query.length),
                                modifier = if (hit == null) Modifier else Modifier.onGloballyPositioned {
                                    top = it.positionInRoot().y
                                }, style = MaterialTheme.typography.bodyMedium,
                                onTextLayout = { if (hit != null) layout = it })
                    }
                    if (!user) {
                        for (asset in assets) key(asset) {
                            AndroidAssetList(listOf(asset))
                            val missing = remember(asset.path) { asset.path.isBlank() || !File(asset.path).isFile }
                            if (missing && !asset.remoteId.isNullOrBlank()) {
                                TextButton(enabled = canRetry, onClick = { onRetryMedia(asset) }) {
                                    Text(if (asset.kind == "audio") "重试获取语音" else "重试获取图片")
                                }
                            }
                        }
                    }
                    if (!user && turn.status != "已完成") {
                        Text(turn.status, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
            DropdownMenu(menu, { menu = false }) {
                actions.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                }
            }
        }
    }
}
