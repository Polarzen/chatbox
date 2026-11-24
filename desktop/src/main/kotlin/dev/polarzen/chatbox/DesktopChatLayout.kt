package dev.polarzen.chatbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Files
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import javax.imageio.ImageIO

private val SidebarInset = 14.dp

internal data class EntryProgress(
    val entryIndex: Int,
    val stage: String,
    val stageStartedAtNanos: Long,
    val failureSummary: String? = null,
)

@Composable
internal fun DesktopChatLayout(
    sessions: List<SessionInfo>,
    chat: Chat,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    canCreate: Boolean,
    topActions: @Composable () -> Unit,
    draft: TextFieldValue,
    onDraft: (TextFieldValue) -> Unit,
    selectedImage: Path?,
    onChooseImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    notice: String?,
    onRetry: (Entry) -> Unit,
    canRetry: Boolean,
    assistantContent: @Composable (Entry) -> Unit,
    selectedImages: List<Path> = listOfNotNull(selectedImage),
    onRemoveImagePath: ((Path) -> Unit)? = null,
    onAddImages: (List<File>) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onMessageRendered: ((String, Int) -> Unit)? = null,
    progress: EntryProgress? = null,
    profileId: String = "",
    onRenameSession: (String, String?) -> String? = { _, _ -> "当前无法修改会话名称。" },
    searchNavigation: SearchHitNavigation? = null,
    searchSelectionSerial: Long = 0L,
    onPasteAttachment: () -> Boolean = { false },
    recordingMillis: Long? = null,
    canRecord: Boolean = false,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onReplaceMissingAttachment: ((Path) -> Unit)? = null,
    composerCommitInProgress: Boolean = false,
    composerFocusSerial: Long = 0L,
    replyNavigation: ReplyTurnNavigation? = null,
    profileTextOnly: Boolean = false,
    onRecoverPending: (() -> Unit)? = null,
    recentDetails: Map<String, DesktopRecentRow> = emptyMap(),
    deletionScope: (String) -> String? = { null },
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(desktopPalette.canvas).testTag("chat-root"),
    ) {
        val narrow = maxWidth < 790.dp
        var sidebarOpen by remember { mutableStateOf(!narrow) }
        LaunchedEffect(narrow, searchSelectionSerial, composerFocusSerial, replyNavigation?.serial) {
            if (narrow) sidebarOpen = false
        }
        val embeddedSidebar = sidebarOpen && !narrow

        Row(Modifier.fillMaxSize()) {
            if (embeddedSidebar) {
                SessionSidebar(
                    sessions = sessions,
                    recentDetails = recentDetails, deletionScope = deletionScope,
                    profileTextOnly = profileTextOnly,
                    canCreate = canCreate,
                    onNewSession = onNewSession,
                    onSelectSession = onSelectSession,
                    onDeleteSession = onDeleteSession,
                    onRenameSession = onRenameSession,
                    onDismiss = {},
                    modifier = Modifier.width(232.dp).fillMaxHeight(),
                )
            }
            ConversationPane(
                profileId = profileId,
                profileTextOnly = profileTextOnly,
                onRecoverPending = onRecoverPending,
                navigation = searchNavigation,
                replyNavigation = replyNavigation,
                composerFocusSerial = composerFocusSerial,
                sessions = sessions,
                chat = chat,
                narrow = narrow,
                sidebarOpen = sidebarOpen,
                embeddedSidebar = embeddedSidebar,
                onToggleSidebar = { sidebarOpen = !sidebarOpen },
                onNewSession = onNewSession,
                canCreate = canCreate,
                topActions = topActions,
                draft = draft,
                onDraft = onDraft,
                onPasteAttachment = onPasteAttachment,
                recordingMillis = recordingMillis,
                canRecord = canRecord,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onCancelRecording = onCancelRecording,
                onReplaceMissingAttachment = onReplaceMissingAttachment,
                composerCommitInProgress = composerCommitInProgress,
                selectedImage = selectedImage,
                selectedImages = selectedImages,
                onChooseImage = onChooseImage,
                onRemoveImage = onRemoveImage,
                onRemoveImagePath = onRemoveImagePath,
                onAddImages = onAddImages,
                onSend = onSend,
                canSend = canSend,
                notice = notice,
                onRetry = onRetry,
                canRetry = canRetry,
                assistantContent = assistantContent,
                onMessageRendered = onMessageRendered,
                progress = progress,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        if (sidebarOpen && narrow) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = .34f))
                    .clickable { sidebarOpen = false },
            )
            SessionSidebar(
                sessions = sessions,
                recentDetails = recentDetails, deletionScope = deletionScope,
                profileTextOnly = profileTextOnly,
                canCreate = canCreate,
                onNewSession = onNewSession,
                onSelectSession = onSelectSession,
                onDeleteSession = onDeleteSession,
                onRenameSession = onRenameSession,
                onDismiss = { sidebarOpen = false },
                modifier = Modifier.align(Alignment.CenterStart)
                    .widthIn(max = 280.dp)
                    .fillMaxHeight()
                    .fillMaxWidth(0.84f),
                elevated = true,
            )
        }
    }
}

@Composable
private fun SessionSidebar(
    sessions: List<SessionInfo>,
    recentDetails: Map<String, DesktopRecentRow>,
    deletionScope: (String) -> String?,
    profileTextOnly: Boolean,
    canCreate: Boolean,
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String?) -> String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
) {
    val sessionListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pendingDeletion by remember { mutableStateOf<SessionInfo?>(null) }
    var pendingRename by remember { mutableStateOf<SessionInfo?>(null) }
    Surface(
        modifier = modifier,
        color = desktopPalette.panel,
        elevation = if (elevated) 12.dp else 0.dp,
        shape = if (elevated) RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp) else RoundedCornerShape(0.dp),
    ) {
        Column(
            Modifier.fillMaxSize()
                .then(if (!elevated) Modifier.border(width = 1.dp, color = desktopPalette.line) else Modifier)
                .padding(start = SidebarInset, top = 14.dp, bottom = 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(end = SidebarInset),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        onNewSession()
                        if (!elevated) {
                            scope.launch {
                                withFrameNanos { }
                                if (sessionListState.layoutInfo.totalItemsCount > 0) {
                                    sessionListState.animateScrollToItem(0)
                                }
                            }
                        }
                        onDismiss()
                    },
                    enabled = canCreate,
                    modifier = Modifier.weight(1f).height(42.dp).testTag("new-session-button"),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = desktopPalette.accent,
                        contentColor = desktopPalette.onAccent,
                        disabledBackgroundColor = desktopPalette.accentSurface,
                        disabledContentColor = desktopPalette.muted,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 1.dp),
                ) {
                    Text("＋   新建会话", fontSize = 14.sp)
                }
                if (elevated) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-sidebar-button")) {
                        Text("关闭", color = desktopPalette.muted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("最近对话", color = desktopPalette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            if (sessions.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(end = SidebarInset), contentAlignment = Alignment.Center) {
                    Text("还没有对话", color = desktopPalette.muted, fontSize = 13.sp)
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(end = SidebarInset),
                        state = sessionListState,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        itemsIndexed(sessions, key = { _, session -> session.id }) { index, session ->
                            val tagId = recentDetails[session.id]?.tagId ?: session.id
                            val selected = session.selected
                            var menuExpanded by remember(session.id) { mutableStateOf(false) }
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (selected) desktopPalette.accentSurface else Color.Transparent)
                                    .clickable { onSelectSession(session.id); onDismiss() }
                                    .padding(start = 11.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                                    .testTag("session-item-$index"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                    Text(
                                        session.title.ifBlank { "新会话" },
                                        color = if (selected) desktopPalette.accent else desktopPalette.ink,
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    recentDetails[session.id]?.label?.let { Text(it, color = desktopPalette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    if (session.entryCount > 0) {
                                        Spacer(Modifier.height(3.dp))
                                        Text("${session.entryCount} 轮", color = desktopPalette.muted, fontSize = 11.sp)
                                    }
                                }
                                Box {
                                    TextButton(
                                        onClick = { menuExpanded = true },
                                        modifier = Modifier.sizeIn(minWidth = 36.dp, minHeight = 36.dp)
                                            .semantics { contentDescription = "会话 ${session.title.ifBlank { "新会话" }} 菜单" }
                                            .testTag("session-menu-$tagId"),
                                        contentPadding = PaddingValues(0.dp),
                                    ) { Text("⋯", color = desktopPalette.muted, fontSize = 18.sp) }
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        DropdownMenuItem(
                                            onClick = {
                                                menuExpanded = false
                                                pendingRename = session
                                            },
                                            modifier = Modifier.testTag("rename-session-$tagId"),
                                        ) { Text("重命名") }
                                        DropdownMenuItem(
                                            onClick = {
                                                menuExpanded = false
                                                pendingDeletion = session
                                            },
                                            modifier = Modifier.testTag("delete-session-$tagId"),
                                        ) { Text("删除会话", color = MaterialTheme.colors.error) }
                                    }
                                }
                            }
                        }
                    }
                    if (sessionListState.canScrollBackward || sessionListState.canScrollForward) {
                        SessionListScrollbar(sessionListState, Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }
    DesktopModalGuard(pendingRename != null || pendingDeletion != null)
    pendingRename?.let { session ->
        RenameSessionDialog(
            session = session,
            onSave = { name -> onRenameSession(session.id, name) },
            onDismiss = { pendingRename = null },
        )
    }
    val deleting = pendingDeletion
    if (deleting != null) {
        val title = deleting.title.ifBlank { "新会话" }
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("删除会话？") },
            text = { Text(deletionScope(deleting.id)?.let { "“$title”\n$it 此操作无法撤销。" } ?: if (profileTextOnly)
                "将删除本机保存的“$title”。下次同步会将它从手机目录移除，共享服务按原房间规则保留记录。此操作无法撤销。"
                else "将删除本机保存的这段聊天记录“$title”。确定删除吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletion = null
                        onDeleteSession(deleting.id)
                    },
                    modifier = Modifier.testTag("confirm-delete-session"),
                ) { Text("删除", color = MaterialTheme.colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }, modifier = Modifier.testTag("cancel-delete-session")) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun RenameSessionDialog(session: SessionInfo, onSave: (String?) -> String?, onDismiss: () -> Unit) {
    var name by remember(session.id) {
        val title = session.title.ifBlank { "新会话" }
        mutableStateOf(TextFieldValue(title, TextRange(0, title.length)))
    }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldFocus = remember { FocusRequester() }
    fun save(value: String?) {
        if (value != null && value.isBlank()) {
            error = "请输入会话名称。"
        } else {
            val failure = onSave(value)
            if (failure == null) {
                onDismiss()
                return
            }
            error = if (value == null) "恢复默认名称失败：$failure" else "保存会话名称失败：$failure"
        }
        fieldFocus.requestFocus()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    isError = error != null,
                    label = { Text("会话名称") },
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus).testTag("rename-session-name"),
                )
                error?.let { Text(it, color = MaterialTheme.colors.error, modifier = Modifier.testTag("rename-session-error")) }
                if (session.manualName != null) {
                    TextButton(onClick = { save(null) }, modifier = Modifier.testTag("restore-session-name")) {
                        Text("恢复默认名称")
                    }
                }
                LaunchedEffect(session.id) { fieldFocus.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(onClick = { save(name.text.trim()) }, modifier = Modifier.testTag("confirm-rename-session")) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel-rename-session")) { Text("取消") }
        },
        modifier = Modifier.testTag("rename-session-dialog").onPreviewKeyEvent { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                onDismiss()
                true
            } else false
        },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SessionListScrollbar(state: LazyListState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val atTop = !state.canScrollBackward
    val atBottom = !state.canScrollForward
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val highlighted = hovered || dragged
    val palette = desktopPalette
    val style = remember(highlighted, palette) {
        ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = SidebarInset,
            shape = SessionThumbShape(if (highlighted) 6.dp else 4.dp),
            hoverDurationMillis = 0,
            unhoverColor = palette.scrollbar,
            hoverColor = palette.scrollbarHover,
        )
    }

    Column(
        modifier.width(SidebarInset).fillMaxHeight().hoverable(interaction)
            .scrollable(state, Orientation.Vertical, reverseDirection = true)
            .testTag("session-scrollbar"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SessionScrollArrow(
            symbol = "▲",
            description = "向上滚动一个会话",
            tag = "session-scroll-up",
            highlighted = highlighted,
            enabled = !atTop,
            onClick = {
                scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex - 1).coerceAtLeast(0)) }
            },
        )

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("session-scroll-thumb"),
            style = style,
            interactionSource = interaction,
        )

        SessionScrollArrow(
            symbol = "▼",
            description = "向下滚动一个会话",
            tag = "session-scroll-down",
            highlighted = highlighted,
            enabled = !atBottom,
            onClick = {
                scope.launch {
                    val lastIndex = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                    val firstIndex = state.firstVisibleItemIndex
                    if (firstIndex < lastIndex) state.animateScrollToItem(firstIndex + 1)
                    else state.animateScrollToItem(lastIndex, Int.MAX_VALUE)
                }
            },
        )
    }
}

// Compose sizes the draggable thumb to the track width. Inset only its painted outline.
private class SessionThumbShape(private val width: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val pixels = with(density) { width.roundToPx() }.toFloat().coerceAtMost(size.width)
        val left = ((size.width - pixels) / 2f).toInt().toFloat()
        return Outline.Rounded(RoundRect(Rect(left, 0f, left + pixels, size.height), CornerRadius(pixels / 2f)))
    }
}

@Composable
private fun SessionScrollArrow(
    symbol: String,
    description: String,
    tag: String,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().height(18.dp).onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (highlighted || focused) {
            Text(symbol, fontSize = 9.sp, color = if (enabled) desktopPalette.muted else desktopPalette.muted.copy(alpha = .35f))
        }
    }
}

@Composable
private fun ConversationToolbar(
    selectedTitle: String,
    sidebarOpen: Boolean,
    onToggleSidebar: () -> Unit,
    showNewSession: Boolean,
    onNewSession: () -> Unit,
    canCreate: Boolean,
    actions: @Composable () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onToggleSidebar,
                    modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp)
                        .semantics { contentDescription = if (sidebarOpen) "收起最近对话" else "打开最近对话" }
                        .testTag("sidebar-toggle"),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(if (sidebarOpen) "‹" else "☰", color = desktopPalette.muted, fontSize = 19.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                selectedTitle,
                color = desktopPalette.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                if (showNewSession) {
                    TextButton(
                        onClick = onNewSession,
                        enabled = canCreate,
                        modifier = Modifier.testTag("new-session-toolbar-button"),
                    ) { Text("＋ 新会话", color = desktopPalette.accent, fontSize = 13.sp) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        },
    ) { (toggle, title, newSession, actionsRow), constraints ->
        val width = constraints.maxWidth
        val compact = width.toDp() < 640.dp
        val inset = (if (compact) 12.dp else 22.dp).roundToPx().coerceAtMost(width / 2)
        val contentWidth = width - 2 * inset
        val childConstraints = constraints.copy(minWidth = 0, maxWidth = contentWidth, minHeight = 0)

        val togglePlaceable = toggle.measure(childConstraints)
        val newSessionPlaceable = newSession.measure(childConstraints.copy(maxWidth = contentWidth - togglePlaceable.width))
        val remainingWidth = contentWidth - togglePlaceable.width - newSessionPlaceable.width
        val minLineHeight = maxOf(
            (if (compact) 40.dp else 62.dp).roundToPx(),
            togglePlaceable.height,
            newSessionPlaceable.height,
        ).coerceAtMost(constraints.maxHeight)
        val stackedHeightIsBounded = compact && constraints.hasBoundedHeight
        val actionsPlaceable = actionsRow.measure(
            childConstraints.copy(
                maxWidth = if (compact) contentWidth else remainingWidth,
                maxHeight = if (stackedHeightIsBounded) constraints.maxHeight - minLineHeight else constraints.maxHeight,
            ),
        )
        val titleWidth = if (compact) remainingWidth else remainingWidth - actionsPlaceable.width
        val titlePlaceable = title.measure(
            childConstraints.copy(
                minWidth = titleWidth,
                maxWidth = titleWidth,
                maxHeight = if (stackedHeightIsBounded) constraints.maxHeight - actionsPlaceable.height else constraints.maxHeight,
            ),
        )
        val titleLineHeight = maxOf(minLineHeight, titlePlaceable.height)
        val contentHeight = if (compact) titleLineHeight + actionsPlaceable.height else maxOf(titleLineHeight, actionsPlaceable.height)
        val height = contentHeight.coerceAtLeast(constraints.minHeight)
        layout(width, height) {
            val lineHeight = if (compact) height - actionsPlaceable.height else height
            togglePlaceable.placeRelative(inset, (lineHeight - togglePlaceable.height) / 2)
            val titleStart = inset + togglePlaceable.width
            titlePlaceable.placeRelative(titleStart, (lineHeight - titlePlaceable.height) / 2)
            newSessionPlaceable.placeRelative(titleStart + titleWidth, (lineHeight - newSessionPlaceable.height) / 2)
            val actionsY = if (compact) lineHeight else (height - actionsPlaceable.height) / 2
            actionsPlaceable.placeRelative(width - inset - actionsPlaceable.width, actionsY)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ConversationPane(
    profileId: String,
    profileTextOnly: Boolean,
    onRecoverPending: (() -> Unit)?,
    navigation: SearchHitNavigation?,
    replyNavigation: ReplyTurnNavigation?,
    composerFocusSerial: Long,
    sessions: List<SessionInfo>,
    chat: Chat,
    narrow: Boolean,
    sidebarOpen: Boolean,
    embeddedSidebar: Boolean,
    onToggleSidebar: () -> Unit,
    onNewSession: () -> Unit,
    canCreate: Boolean,
    topActions: @Composable () -> Unit,
    draft: TextFieldValue,
    onDraft: (TextFieldValue) -> Unit,
    selectedImage: Path?,
    selectedImages: List<Path>,
    onChooseImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onRemoveImagePath: ((Path) -> Unit)?,
    onAddImages: (List<File>) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    notice: String?,
    onRetry: (Entry) -> Unit,
    canRetry: Boolean,
    assistantContent: @Composable (Entry) -> Unit,
    onMessageRendered: ((String, Int) -> Unit)?,
    progress: EntryProgress?,
    onPasteAttachment: () -> Boolean = { false },
    recordingMillis: Long? = null,
    canRecord: Boolean = false,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onReplaceMissingAttachment: ((Path) -> Unit)? = null,
    composerCommitInProgress: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val selectedTitle = sessions.firstOrNull { it.selected }?.title?.ifBlank { "新会话" } ?: "新会话"
    val isExternalDragOver = remember { mutableStateOf(false) }
    val addImagesState = rememberUpdatedState(onAddImages)
    val dropDisabled = rememberUpdatedState(composerCommitInProgress || profileTextOnly)
    val dragTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                isExternalDragOver.value = true
            }

            override fun onMoved(event: DragAndDropEvent) {
                isExternalDragOver.value = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isExternalDragOver.value = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isExternalDragOver.value = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isExternalDragOver.value = false
                if (dropDisabled.value) return false
                val files = droppedFilePaths(event)
                if (files.isEmpty()) return false
                addImagesState.value(files)
                return true
            }
        }
    }
    Box(
        modifier.fillMaxSize()
            .background(desktopPalette.canvas)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    !dropDisabled.value && when (val nativeEvent = event.nativeEvent) {
                        is DropTargetDragEvent -> nativeEvent.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        is DropTargetDropEvent -> nativeEvent.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        else -> false
                    }
                },
                target = dragTarget,
            ),
    ) {
      Column(Modifier.fillMaxSize()) {
        ConversationToolbar(
            selectedTitle = selectedTitle,
            sidebarOpen = sidebarOpen,
            onToggleSidebar = onToggleSidebar,
            showNewSession = !narrow && !embeddedSidebar && !sidebarOpen,
            onNewSession = onNewSession,
            canCreate = canCreate,
            actions = topActions,
        )
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(desktopPalette.line))

        MessageViewport(
            profileId = profileId,
            session = chat.session,
            entryCount = chat.entries.size,
            navigation = navigation,
            replyNavigation = replyNavigation,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (chat.entries.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().heightIn(min = 280.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("开始一段对话", color = desktopPalette.ink, fontSize = 17.sp)
                            Spacer(Modifier.height(7.dp))
                            Text("在下方输入消息。", color = desktopPalette.muted, fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onNewSession,
                                enabled = canCreate,
                                modifier = Modifier.testTag("empty-new-session-button"),
                                shape = RoundedCornerShape(11.dp),
                            ) { Text("新建会话", color = desktopPalette.accent, fontSize = 13.sp) }
                        }
                    }
                }
            }
            itemsIndexed(chat.entries, key = { index, _ -> "${chat.session}-$index" }) { index, entry ->
                SearchHitEntry(index) {
                    Column(Modifier.fillMaxWidth().testTag("message-entry-$index"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (entry.question.isNotBlank() || entry.attachmentDisplayName != null || entry.attachmentReferences.isNotEmpty()) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                Surface(
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                        .widthIn(max = minOf(720.dp, maxWidth * .82f))
                                        .testTag("message-user-$index"),
                                    color = desktopPalette.accentSurface,
                                    shape = RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp),
                                ) {
                                    Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                                        val references = entry.attachmentReferences.ifEmpty {
                                            listOfNotNull(entry.attachmentReference)
                                        }
                                        DesktopStoredAttachments(entry, references)
                                        if (references.isNotEmpty() && entry.question.isNotBlank()) {
                                            Spacer(Modifier.height(5.dp))
                                        }
                                        if (entry.question.isNotBlank()) {
                                            CopyableMessage(entry.question) {
                                                SearchHitText(entry.question, isAnswer = false, color = desktopPalette.ink, fontSize = 14.sp)
                                            }
                                        }
                                        if (references.any { it.endsWith(".wav", ignoreCase = true) }) {
                                            val status = when (entry.status) {
                                                "已完成" -> "已发送"
                                                "发送中" -> "发送中"
                                                else -> failureSummary(entry.status)
                                            }
                                            Text(status, color = desktopPalette.muted, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                        val renderedState = remember(chat.session, index) {
                            mutableStateOf(false)
                        }
                        val renderCallback by rememberUpdatedState(onMessageRendered)
                        LaunchedEffect(renderedState.value) {
                            if (renderedState.value) renderCallback?.invoke(chat.session, index)
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth().replyTurnAnchor(index)) {
                            Surface(
                                modifier = Modifier.align(Alignment.CenterStart)
                                    .widthIn(max = minOf(760.dp, maxWidth * .86f))
                                    .drawWithContent {
                                        drawContent()
                                        if (!renderedState.value && (entry.answer.isNotEmpty() || entry.replyAttachments.isNotEmpty())) {
                                            renderedState.value = true
                                        }
                                    }
                                    .testTag("message-assistant-$index"),
                                color = desktopPalette.panel,
                                contentColor = desktopPalette.ink,
                                shape = RoundedCornerShape(5.dp, 18.dp, 18.dp, 18.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, desktopPalette.line),
                            ) {
                                Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                                    if (entry.answer.isNotEmpty()) {
                                        CopyableMessage(entry.answer) { assistantContent(entry) }
                                    } else {
                                        assistantContent(entry)
                                    }
                                    if (entry.status != "已完成") {
                                        Spacer(Modifier.height(7.dp))
                                        val entryProgress = progress?.takeIf { it.entryIndex == index }
                                        var elapsedSeconds by remember(chat.session, index) {
                                            mutableIntStateOf(0)
                                        }
                                        LaunchedEffect(chat.session, index, entryProgress?.stage, entryProgress?.stageStartedAtNanos, entry.status) {
                                            if (entry.status == "发送中" && entryProgress != null && entryProgress.stage.isNotBlank()) {
                                                while (true) {
                                                    elapsedSeconds = ((System.nanoTime() - entryProgress.stageStartedAtNanos)
                                                        .coerceAtLeast(0L) / 1_000_000_000L).toInt()
                                                    delay(1000)
                                                }
                                            }
                                        }
                                        val failed = entry.status.startsWith("发送失败：") ||
                                            entry.status.startsWith("未发送：") ||
                                            entry.status == "上次回复未确认"
                                        val blockRetry = entry.blocksWhaleTaskRetry(profileId)
                                        val active = entry.status == "发送中" && entryProgress != null
                                        var detailsExpanded by remember(chat.session, index) {
                                            mutableStateOf(false)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (active) CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp).testTag("message-progress-$index"),
                                                color = desktopPalette.accent,
                                                strokeWidth = 1.5.dp,
                                            )
                                            if (active) Spacer(Modifier.width(6.dp))
                                            Text(
                                                when {
                                                    active -> "${entryProgress!!.stage} · ${elapsedSeconds} 秒"
                                                    blockRetry -> WHALE_TASK_CHECK_STATUS
                                                    failed -> entryProgress?.failureSummary ?: failureSummary(entry.status)
                                                    else -> entry.status
                                                },
                                                color = desktopPalette.muted,
                                                fontSize = 11.sp,
                                            )
                                            if (failed) {
                                                TextButton(
                                                    onClick = { detailsExpanded = !detailsExpanded },
                                                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                                    modifier = Modifier.testTag("failure-details-toggle-$index"),
                                                ) { Text(if (detailsExpanded) "收起详情" else "查看详情", color = desktopPalette.accent, fontSize = 11.sp) }
                                            }
                                            if (canRetry && !active && failed && !blockRetry) {
                                                Spacer(Modifier.width(6.dp))
                                                TextButton(
                                                    onClick = { onRetry(entry) },
                                                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                                    modifier = Modifier.testTag("retry-message-$index"),
                                                ) { Text("重试", color = desktopPalette.accent, fontSize = 12.sp) }
                                            }
                                        }
                                        if (failed && detailsExpanded) {
                                            Text(
                                                entry.status.substringAfter('：', entry.status),
                                                color = desktopPalette.muted,
                                                fontSize = 11.sp,
                                                modifier = Modifier.fillMaxWidth().testTag("failure-details-$index"),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        notice?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colors.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp))
        }
        if (onRecoverPending != null) TextButton(onClick = onRecoverPending,
            modifier = Modifier.testTag("profile-share-recover")) { Text("核对并重试原消息") }
        Composer(
            attachmentsEnabled = !profileTextOnly,
            composerFocusSerial = composerFocusSerial,
            draft = draft,
            onDraft = onDraft,
            onPasteAttachment = onPasteAttachment,
            recordingMillis = recordingMillis,
            canRecord = canRecord,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onReplaceMissingAttachment = onReplaceMissingAttachment,
            composerCommitInProgress = composerCommitInProgress,
            selectedImage = selectedImage,
            selectedImages = selectedImages,
            onChooseImage = onChooseImage,
            onRemoveImage = onRemoveImage,
            onRemoveImagePath = onRemoveImagePath,
            onSend = onSend,
            canSend = canSend,
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp, top = 8.dp),
        )
      }
      if (isExternalDragOver.value && !composerCommitInProgress) {
          Box(
              Modifier.fillMaxSize().background(desktopPalette.accent.copy(alpha = .12f)).testTag("external-drop-overlay"),
              contentAlignment = Alignment.Center,
          ) {
              Surface(color = desktopPalette.panel, shape = RoundedCornerShape(14.dp), elevation = 4.dp) {
                  Text(
                      "松开以添加图片",
                      color = desktopPalette.accent,
                      fontSize = 15.sp,
                      modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                  )
              }
          }
      }
    }
}

@Composable
private fun CopyableMessage(text: String, content: @Composable () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var clipboardBusy by remember { mutableStateOf(false) }
    val messageClipboard = remember(clipboard) {
        object : ClipboardManager by clipboard {
            override fun setText(annotatedString: AnnotatedString) {
                try {
                    clipboard.setText(annotatedString)
                    clipboardBusy = false
                } catch (_: IllegalStateException) {
                    clipboardBusy = true
                }
            }
        }
    }
    CompositionLocalProvider(LocalClipboardManager provides messageClipboard) {
        SelectionContainer(content = content)
    }
    TextButton(
        onClick = { messageClipboard.setText(AnnotatedString(text)) },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        modifier = Modifier.height(28.dp),
    ) { Text("复制正文", color = desktopPalette.accent, fontSize = 11.sp) }
    if (clipboardBusy) Text("剪贴板占用中，请再试一次。", color = MaterialTheme.colors.error, fontSize = 11.sp)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private fun droppedFilePaths(event: DragAndDropEvent): List<File> {
    val transferable = when (val nativeEvent = event.nativeEvent) {
        is DropTargetDragEvent -> nativeEvent.transferable
        is DropTargetDropEvent -> nativeEvent.transferable
        else -> return emptyList()
    }
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    val files = try {
        transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
    } catch (error: InvalidPathException) {
        return listOf(File(error.input))
    } catch (_: UnsupportedFlavorException) {
        return emptyList()
    } catch (_: IOException) {
        return emptyList()
    }
    return files.orEmpty().mapNotNull { it as? File }
}

internal const val WHALE_TASK_CHECK_STATUS = "命令可能已执行，请先用 /task list 或 /task status 核对。"

internal fun Entry.blocksWhaleTaskRetry(profileId: String): Boolean {
    if (status != "上次回复未确认" || !profileId.startsWith("launcher:")) return false
    val command = question.trimStart()
    if (!isWhaleTaskCommand(command)) return false
    val verb = command.substring(5).trimStart().takeWhile { !it.isWhitespace() }
    return verb !in setOf("", "help", "list", "status", "result")
}

private fun failureSummary(status: String): String = when {
    status == "上次回复未确认" -> "上次回复未确认"
    status.startsWith("未发送：") -> "消息尚未发送"
    status.contains("上传图片失败") || status.contains("图片上传失败") -> "上传图片失败"
    status.contains("等待响应超时") || status.contains("等待服务响应超时") -> "等待响应超时"
    status.contains("回复流中断") || status.contains("回复内容中断") -> "回复流中断"
    status.startsWith("发送失败：") -> "回复请求失败"
    else -> "请求失败"
}

@Composable
internal fun Composer(
    draft: TextFieldValue,
    onDraft: (TextFieldValue) -> Unit,
    selectedImage: Path?,
    onChooseImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    modifier: Modifier = Modifier,
    selectedImages: List<Path> = listOfNotNull(selectedImage),
    onRemoveImagePath: ((Path) -> Unit)? = null,
    onPasteAttachment: () -> Boolean = { false },
    recordingMillis: Long? = null,
    canRecord: Boolean = false,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onReplaceMissingAttachment: ((Path) -> Unit)? = null,
    composerCommitInProgress: Boolean = false,
    composerFocusSerial: Long = 0L,
    attachmentsEnabled: Boolean = true,
) {
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(composerFocusSerial) {
        if (composerFocusSerial > 0L) fieldFocus.requestFocus()
    }
    Surface(
        modifier = modifier.testTag("composer"),
        color = desktopPalette.panel,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, desktopPalette.line),
        elevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (composerCommitInProgress) {
                Text("正在保存消息…", color = desktopPalette.muted, fontSize = 12.sp)
            }
            if (selectedImages.isNotEmpty()) {
                Column(Modifier.heightIn(max = 156.dp).verticalScroll(rememberScrollState())) {
                    selectedImages.forEachIndexed { index, path ->
                        key(path) {
                            SelectedAttachmentPreview(
                                path = path,
                                onRemove = { onRemoveImagePath?.invoke(path) ?: onRemoveImage() },
                                onReplace = onReplaceMissingAttachment,
                                enabled = !composerCommitInProgress,
                                removeTag = if (index == 0) "remove-image-button" else "remove-image-button-$index",
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            if (recordingMillis != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "正在录音  ${formatAudioDuration(recordingMillis)}",
                        modifier = Modifier.weight(1f).testTag("recording-elapsed"),
                        color = desktopPalette.ink,
                        fontSize = 13.sp,
                    )
                    TextButton(onClick = onStopRecording, enabled = !composerCommitInProgress, modifier = Modifier.testTag("stop-recording")) { Text("停止") }
                    TextButton(onClick = onCancelRecording, enabled = !composerCommitInProgress, modifier = Modifier.testTag("cancel-recording")) { Text("取消") }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(
                    onClick = onChooseImage,
                    enabled = attachmentsEnabled && !composerCommitInProgress,
                    modifier = Modifier.size(39.dp).testTag("choose-image-button"),
                    contentPadding = PaddingValues(0.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = desktopPalette.accent),
                ) { Text("＋", fontSize = 21.sp) }
                if (recordingMillis == null) {
                    OutlinedButton(
                        onClick = onStartRecording,
                        enabled = canRecord && !composerCommitInProgress,
                        modifier = Modifier.size(39.dp).semantics { contentDescription = "开始麦克风录音" }
                            .testTag("start-recording"),
                        contentPadding = PaddingValues(0.dp),
                        shape = CircleShape,
                    ) { Text("录音", fontSize = 12.sp) }
                }
                BasicTextField(
                    value = draft,
                    readOnly = composerCommitInProgress,
                    onValueChange = { if (!composerCommitInProgress) onDraft(it) },
                    modifier = Modifier.weight(1f).heightIn(min = 39.dp, max = 112.dp)
                        .padding(vertical = 9.dp)
                        .focusRequester(fieldFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.V && event.isCtrlPressed &&
                                !event.isShiftPressed && !event.isAltPressed && !event.isMetaPressed
                            ) return@onPreviewKeyEvent composerCommitInProgress || onPasteAttachment()
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                when {
                                    composerCommitInProgress -> true
                                    draft.composition != null -> false
                                    event.isShiftPressed -> {
                                        val selectionStart = minOf(draft.selection.start, draft.selection.end)
                                            .coerceIn(0, draft.text.length)
                                        val selectionEnd = maxOf(draft.selection.start, draft.selection.end)
                                            .coerceIn(0, draft.text.length)
                                        onDraft(
                                            TextFieldValue(
                                                text = draft.text.replaceRange(selectionStart, selectionEnd, "\n"),
                                                selection = androidx.compose.ui.text.TextRange(selectionStart + 1),
                                            ),
                                        )
                                        true
                                    }
                                    else -> { if (canSend && recordingMillis == null) onSend(); true }
                                }
                            } else false
                        }
                        .testTag("composer-field"),
                    textStyle = TextStyle(color = desktopPalette.ink, fontSize = 14.sp, lineHeight = 20.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(desktopPalette.accent),
                    maxLines = 4,
                    decorationBox = { inner ->
                        Box {
                            if (draft.text.isEmpty()) Text(
                                if (selectedImages.isEmpty()) "输入消息…（Enter 发送，Shift+Enter 换行）" else "添加附件说明…",
                                color = desktopPalette.muted,
                                fontSize = 13.sp,
                            )
                            inner()
                        }
                    },
                )
                Button(
                    onClick = onSend,
                    enabled = canSend && recordingMillis == null && !composerCommitInProgress,
                    modifier = Modifier.height(39.dp).widthIn(min = 78.dp).testTag("send-button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = desktopPalette.accent,
                        contentColor = desktopPalette.onAccent,
                        disabledBackgroundColor = desktopPalette.accentSurface,
                        disabledContentColor = desktopPalette.muted,
                    ),
                    elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 1.dp),
                ) { Text("发送", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
internal fun DesktopStoredAttachments(entry: Entry, references: List<String> = entry.attachmentReferences.ifEmpty { listOfNotNull(entry.attachmentReference) }) {
    references.forEachIndexed { attachmentIndex, reference ->
        val path = runCatching {
            if (reference.startsWith("file:", ignoreCase = true)) Path.of(java.net.URI(reference)) else Path.of(reference)
        }.getOrNull()
        if (path != null && path.isWaveRecording()) {
            ReplyAudioPlayback(path)
        } else MessageAttachmentPreview(
            path = path,
            displayName = path?.fileName?.toString()
                ?: if (attachmentIndex == 0) entry.attachmentDisplayName.orEmpty() else "图片",
        )
        if (attachmentIndex < references.lastIndex) Spacer(Modifier.height(5.dp))
    }
    if (references.isEmpty() && entry.attachmentDisplayName != null) {
        Text("图片 · ${entry.attachmentDisplayName}", color = desktopPalette.muted, fontSize = 11.sp)
    }
}

private fun Path.isWaveRecording() = fileName?.toString()?.endsWith(".wav", ignoreCase = true) == true

@Composable
private fun SelectedAttachmentPreview(
    path: Path,
    onRemove: () -> Unit,
    onReplace: ((Path) -> Unit)?,
    enabled: Boolean,
    removeTag: String,
) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var missing by remember(path) { mutableStateOf(false) }
    val audio = path.isWaveRecording()
    LaunchedEffect(path) {
        missing = withContext(Dispatchers.IO) { Files.notExists(path) }
        if (!missing && !audio) bitmap = withContext(Dispatchers.IO) { readBoundedImagePreview(path) }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(desktopPalette.canvas).padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (audio && !missing) {
            ReplyAudioPlayback(path)
        } else if (!audio && !missing) Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(desktopPalette.thumbnail),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null) Image(
                bitmap = image,
                contentDescription = "待发送图片预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            ) else Text("图片", color = desktopPalette.muted, fontSize = 11.sp)
        }
        Text(
            if (missing) "文件缺失：${path.fileName ?: path}" else (path.fileName ?: path).toString(),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            color = if (missing) MaterialTheme.colors.error else desktopPalette.ink,
            fontSize = 12.sp,
            maxLines = 1,
        )
        if (missing && onReplace != null && path.toFile().extension.lowercase() in setOf("png", "jpg", "jpeg")) {
            TextButton(onClick = { onReplace(path) }, enabled = enabled) {
                Text("重新选择", fontSize = 12.sp)
            }
        }
        TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.testTag(removeTag)) {
            Text("移除", color = desktopPalette.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MessageAttachmentPreview(path: Path?, displayName: String) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var openFailed by remember(path) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path) {
        bitmap = path?.let { withContext(Dispatchers.IO) { readBoundedImagePreview(it) } }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(desktopPalette.canvas).padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(desktopPalette.thumbnail),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null) Image(
                bitmap = image,
                contentDescription = "图片附件预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            ) else Text("图片", color = desktopPalette.muted, fontSize = 10.sp)
        }
        Text(
            displayName.ifBlank { "图片" },
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            color = desktopPalette.muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (bitmap != null && path != null && path.fileName.toString().substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png")) {
            TextButton(onClick = {
                scope.launch { openFailed = withContext(Dispatchers.IO) {
                    runCatching { java.awt.Desktop.getDesktop().open(path.toFile()) }.isFailure
                } }
            }) { Text(if (openFailed) "无法打开原图" else "打开原图", fontSize = 11.sp) }
        }
    }
}

private fun readBoundedImagePreview(path: Path): ImageBitmap? = runCatching {
    val input = ImageIO.createImageInputStream(path.toFile()) ?: return@runCatching null
    input.use { stream ->
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@use null
        try {
            reader.input = stream
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0) return@use null

            val longestSide = maxOf(width, height).toLong()
            val sampleSize = ((longestSide + 159L) / 160L).coerceAtLeast(1L).toInt()
            val params = reader.defaultReadParam
            params.setSourceSubsampling(sampleSize, sampleSize, 0, 0)
            reader.read(0, params)?.toComposeImageBitmap()
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()
