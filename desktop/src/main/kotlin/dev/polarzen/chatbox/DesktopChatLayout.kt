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
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import javax.imageio.ImageIO

private val ChatCanvas = Color(0xFFF7F6FA)
private val ChatPanel = Color(0xFFFFFFFF)
private val ChatInk = Color(0xFF292735)
private val ChatMuted = Color(0xFF888594)
private val ChatLine = Color(0xFFE9E6EF)
private val ChatPurple = Color(0xFF7455C7)
private val ChatPurpleLight = Color(0xFFF0EBFB)
private val ChatAssistant = Color(0xFFFFFFFF)
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
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(ChatCanvas).testTag("chat-root"),
    ) {
        val narrow = maxWidth < 790.dp
        var sidebarOpen by remember { mutableStateOf(!narrow) }
        LaunchedEffect(narrow) {
            if (narrow) sidebarOpen = false
        }
        val embeddedSidebar = sidebarOpen && !narrow

        Row(Modifier.fillMaxSize()) {
            if (embeddedSidebar) {
                SessionSidebar(
                    sessions = sessions,
                    canCreate = canCreate,
                    onNewSession = onNewSession,
                    onSelectSession = onSelectSession,
                    onDeleteSession = onDeleteSession,
                    onDismiss = {},
                    modifier = Modifier.width(232.dp).fillMaxHeight(),
                )
            }
            ConversationPane(
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
                    .background(Color(0x550E0C14))
                    .clickable { sidebarOpen = false },
            )
            SessionSidebar(
                sessions = sessions,
                canCreate = canCreate,
                onNewSession = onNewSession,
                onSelectSession = onSelectSession,
                onDeleteSession = onDeleteSession,
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
    canCreate: Boolean,
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
) {
    val sessionListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pendingDeletion by remember { mutableStateOf<SessionInfo?>(null) }
    Surface(
        modifier = modifier,
        color = ChatPanel,
        elevation = if (elevated) 12.dp else 0.dp,
        shape = if (elevated) RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp) else RoundedCornerShape(0.dp),
    ) {
        Column(
            Modifier.fillMaxSize()
                .then(if (!elevated) Modifier.border(width = 1.dp, color = ChatLine) else Modifier)
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
                        backgroundColor = ChatPurple,
                        contentColor = Color.White,
                        disabledBackgroundColor = ChatPurple.copy(alpha = .35f),
                        disabledContentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 1.dp),
                ) {
                    Text("＋   新建会话", fontSize = 14.sp)
                }
                if (elevated) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-sidebar-button")) {
                        Text("关闭", color = ChatMuted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (sessions.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(end = SidebarInset), contentAlignment = Alignment.Center) {
                    Text("还没有对话", color = ChatMuted, fontSize = 13.sp)
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(end = SidebarInset),
                        state = sessionListState,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        itemsIndexed(sessions, key = { _, session -> session.id }) { index, session ->
                            val selected = session.selected
                            var menuExpanded by remember(session.id) { mutableStateOf(false) }
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (selected) ChatPurpleLight else Color.Transparent)
                                    .clickable { onSelectSession(session.id); onDismiss() }
                                    .padding(start = 11.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                                    .testTag("session-item-$index"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                    Text(
                                        session.title.ifBlank { "新会话" },
                                        color = if (selected) ChatPurple else ChatInk,
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                    )
                                    if (session.entryCount > 0) {
                                        Spacer(Modifier.height(3.dp))
                                        Text("${session.entryCount} 轮", color = ChatMuted, fontSize = 11.sp)
                                    }
                                }
                                Box {
                                    TextButton(
                                        onClick = { menuExpanded = true },
                                        modifier = Modifier.sizeIn(minWidth = 36.dp, minHeight = 36.dp)
                                            .semantics { contentDescription = "会话 ${session.title.ifBlank { "新会话" }} 菜单" }
                                            .testTag("session-menu-${session.id}"),
                                        contentPadding = PaddingValues(0.dp),
                                    ) { Text("⋯", color = ChatMuted, fontSize = 18.sp) }
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        DropdownMenuItem(
                                            onClick = {
                                                menuExpanded = false
                                                pendingDeletion = session
                                            },
                                            modifier = Modifier.testTag("delete-session-${session.id}"),
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
    val deleting = pendingDeletion
    if (deleting != null) {
        val title = deleting.title.ifBlank { "新会话" }
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("删除会话？") },
            text = { Text("将删除本机保存的这段聊天记录“$title”。确定删除吗？此操作无法撤销。") },
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
@OptIn(ExperimentalFoundationApi::class)
private fun SessionListScrollbar(state: LazyListState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val atTop = !state.canScrollBackward
    val atBottom = !state.canScrollForward
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val highlighted = hovered || dragged
    val style = remember(highlighted) {
        ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = SidebarInset,
            shape = SessionThumbShape(if (highlighted) 6.dp else 4.dp),
            hoverDurationMillis = 0,
            unhoverColor = Color(0xFFA49FAF),
            hoverColor = Color(0xFF797284),
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
            Text(symbol, fontSize = 9.sp, color = if (enabled) ChatMuted else ChatMuted.copy(alpha = .35f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ConversationPane(
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
    modifier: Modifier = Modifier,
) {
    val selectedTitle = sessions.firstOrNull { it.selected }?.title?.ifBlank { "新会话" } ?: "新会话"
    val isExternalDragOver = remember { mutableStateOf(false) }
    val addImagesState = rememberUpdatedState(onAddImages)
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
                val files = droppedFilePaths(event)
                isExternalDragOver.value = false
                if (files.isEmpty()) return false
                addImagesState.value(files)
                return true
            }
        }
    }
    Box(
        modifier.fillMaxSize()
            .background(ChatCanvas)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    when (val nativeEvent = event.nativeEvent) {
                        is DropTargetDragEvent -> nativeEvent.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        is DropTargetDropEvent -> nativeEvent.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        else -> false
                    }
                },
                target = dragTarget,
            ),
    ) {
      Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onToggleSidebar,
                modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp)
                    .semantics { contentDescription = if (sidebarOpen) "收起最近对话" else "打开最近对话" }
                    .testTag("sidebar-toggle"),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (sidebarOpen) "‹" else "☰", color = ChatMuted, fontSize = 19.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    selectedTitle,
                    color = ChatInk,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!narrow && !embeddedSidebar && !sidebarOpen) {
                TextButton(
                    onClick = onNewSession,
                    enabled = canCreate,
                    modifier = Modifier.testTag("new-session-toolbar-button"),
                ) { Text("＋ 新会话", color = ChatPurple, fontSize = 13.sp) }
            }
            topActions()
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(ChatLine))

        val listState = rememberLazyListState()
        val lastEntry = chat.entries.lastOrNull()
        LaunchedEffect(chat.session, chat.entries.size) {
            if (chat.entries.isEmpty()) listState.scrollToItem(0)
            else listState.scrollToItem(chat.entries.lastIndex, scrollOffset = Int.MAX_VALUE)
        }
        LaunchedEffect(chat.session, lastEntry?.answer, lastEntry?.replyAttachments, lastEntry?.status) {
            val layout = listState.layoutInfo
            val lastVisibleIndex = layout.visibleItemsInfo.lastOrNull()?.index
            if (chat.entries.isNotEmpty() && lastVisibleIndex == layout.totalItemsCount - 1) {
                listState.scrollToItem(chat.entries.lastIndex, scrollOffset = Int.MAX_VALUE)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("message-region"),
            state = listState,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (chat.entries.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().heightIn(min = 280.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("开始一段对话", color = ChatInk, fontSize = 17.sp)
                            Spacer(Modifier.height(7.dp))
                            Text("在下方输入消息。", color = ChatMuted, fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onNewSession,
                                enabled = canCreate,
                                modifier = Modifier.testTag("empty-new-session-button"),
                                shape = RoundedCornerShape(11.dp),
                            ) { Text("新建会话", color = ChatPurple, fontSize = 13.sp) }
                        }
                    }
                }
            }
            itemsIndexed(chat.entries, key = { index, _ -> "${chat.session}-$index" }) { index, entry ->
                Column(Modifier.fillMaxWidth().testTag("message-entry-$index"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (entry.question.isNotBlank() || entry.attachmentDisplayName != null || entry.attachmentReferences.isNotEmpty()) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            Surface(
                                modifier = Modifier.align(Alignment.CenterEnd)
                                    .widthIn(max = minOf(720.dp, maxWidth * .82f))
                                    .testTag("message-user-$index"),
                                color = ChatPurpleLight,
                                shape = RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp),
                            ) {
                                Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                                    val references = entry.attachmentReferences.ifEmpty {
                                        listOfNotNull(entry.attachmentReference)
                                    }
                                    references.forEachIndexed { attachmentIndex, reference ->
                                        val path = runCatching { Path.of(reference) }.getOrNull()
                                        MessageAttachmentPreview(
                                            path = path,
                                            displayName = path?.fileName?.toString()
                                                ?: if (attachmentIndex == 0) entry.attachmentDisplayName.orEmpty() else "图片",
                                        )
                                        if (attachmentIndex < references.lastIndex) Spacer(Modifier.height(5.dp))
                                    }
                                    if (references.isEmpty() && entry.attachmentDisplayName != null) {
                                        Text("图片 · ${entry.attachmentDisplayName}", color = ChatMuted, fontSize = 11.sp)
                                    }
                                    if (references.isNotEmpty() && entry.question.isNotBlank()) {
                                        Spacer(Modifier.height(5.dp))
                                    }
                                    if (entry.question.isNotBlank()) {
                                        CopyableMessage(entry.question) { Text(entry.question, color = ChatInk, fontSize = 14.sp) }
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
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
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
                            color = ChatAssistant,
                            shape = RoundedCornerShape(5.dp, 18.dp, 18.dp, 18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ChatLine),
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
                                    val active = entry.status == "发送中" && entryProgress != null
                                    var detailsExpanded by remember(chat.session, index) {
                                        mutableStateOf(false)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (active) CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp).testTag("message-progress-$index"),
                                            color = ChatPurple,
                                            strokeWidth = 1.5.dp,
                                        )
                                        if (active) Spacer(Modifier.width(6.dp))
                                        Text(
                                            when {
                                                active -> "${entryProgress!!.stage} · ${elapsedSeconds} 秒"
                                                failed -> entryProgress?.failureSummary ?: failureSummary(entry.status)
                                                else -> entry.status
                                            },
                                            color = ChatMuted,
                                            fontSize = 11.sp,
                                        )
                                        if (failed) {
                                            TextButton(
                                                onClick = { detailsExpanded = !detailsExpanded },
                                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                                modifier = Modifier.testTag("failure-details-toggle-$index"),
                                            ) { Text(if (detailsExpanded) "收起详情" else "查看详情", color = ChatPurple, fontSize = 11.sp) }
                                        }
                                        if (canRetry && !active && failed) {
                                            Spacer(Modifier.width(6.dp))
                                            TextButton(
                                                onClick = { onRetry(entry) },
                                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                                modifier = Modifier.testTag("retry-message-$index"),
                                            ) { Text("重试", color = ChatPurple, fontSize = 12.sp) }
                                        }
                                    }
                                    if (failed && detailsExpanded) {
                                        Text(
                                            entry.status.substringAfter('：', entry.status),
                                            color = ChatMuted,
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

        notice?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colors.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp))
        }
        Composer(
            draft = draft,
            onDraft = onDraft,
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
      if (isExternalDragOver.value) {
          Box(
              Modifier.fillMaxSize().background(ChatPurple.copy(alpha = .12f)).testTag("external-drop-overlay"),
              contentAlignment = Alignment.Center,
          ) {
              Surface(color = ChatPanel, shape = RoundedCornerShape(14.dp), elevation = 4.dp) {
                  Text(
                      "松开以添加图片",
                      color = ChatPurple,
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
    ) { Text("复制正文", color = ChatPurple, fontSize = 11.sp) }
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
) {
    Surface(
        modifier = modifier.testTag("composer"),
        color = ChatPanel,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ChatLine),
        elevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            selectedImages.forEachIndexed { index, path ->
                SelectedImagePreview(
                    path = path,
                    onRemove = { onRemoveImagePath?.invoke(path) ?: onRemoveImage() },
                    removeTag = if (index == 0) "remove-image-button" else "remove-image-button-$index",
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(
                    onClick = onChooseImage,
                    modifier = Modifier.size(39.dp).testTag("choose-image-button"),
                    contentPadding = PaddingValues(0.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ChatPurple),
                ) { Text("＋", fontSize = 21.sp) }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    modifier = Modifier.weight(1f).heightIn(min = 39.dp, max = 112.dp)
                        .padding(vertical = 9.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                when {
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
                                    else -> { if (canSend) onSend(); true }
                                }
                            } else false
                        }
                        .testTag("composer-field"),
                    textStyle = TextStyle(color = ChatInk, fontSize = 14.sp, lineHeight = 20.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(ChatPurple),
                    maxLines = 4,
                    decorationBox = { inner ->
                        Box {
                            if (draft.text.isEmpty()) Text(
                                if (selectedImages.isEmpty()) "输入消息…（Enter 发送，Shift+Enter 换行）" else "添加图片说明…",
                                color = ChatMuted,
                                fontSize = 13.sp,
                            )
                            inner()
                        }
                    },
                )
                Button(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.height(39.dp).widthIn(min = 78.dp).testTag("send-button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = ChatPurple,
                        contentColor = Color.White,
                        disabledBackgroundColor = ChatPurple.copy(alpha = .35f),
                        disabledContentColor = Color.White,
                    ),
                    elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 1.dp),
                ) { Text("发送", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun SelectedImagePreview(path: Path, onRemove: () -> Unit, removeTag: String = "remove-image-button") {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { readBoundedImagePreview(path) }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ChatCanvas).padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFECE9F1)),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null) Image(
                bitmap = image,
                contentDescription = "待发送图片预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            ) else Text("图片", color = ChatMuted, fontSize = 11.sp)
        }
        Text(
            path.fileName?.toString().orEmpty(),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            color = ChatInk,
            fontSize = 12.sp,
            maxLines = 1,
        )
        TextButton(onClick = onRemove, modifier = Modifier.testTag(removeTag)) {
            Text("移除", color = ChatMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MessageAttachmentPreview(path: Path?, displayName: String) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = path?.let { withContext(Dispatchers.IO) { readBoundedImagePreview(it) } }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(ChatCanvas).padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFECE9F1)),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null) Image(
                bitmap = image,
                contentDescription = "图片附件预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            ) else Text("图片", color = ChatMuted, fontSize = 10.sp)
        }
        Text(
            displayName.ifBlank { "图片" },
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            color = ChatMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
