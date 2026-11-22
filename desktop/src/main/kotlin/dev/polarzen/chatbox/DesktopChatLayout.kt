package dev.polarzen.chatbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.imageio.ImageIO

private val ChatCanvas = Color(0xFFF7F6FA)
private val ChatPanel = Color(0xFFFFFFFF)
private val ChatInk = Color(0xFF292735)
private val ChatMuted = Color(0xFF888594)
private val ChatLine = Color(0xFFE9E6EF)
private val ChatPurple = Color(0xFF7455C7)
private val ChatPurpleLight = Color(0xFFF0EBFB)
private val ChatAssistant = Color(0xFFFFFFFF)

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
                onChooseImage = onChooseImage,
                onRemoveImage = onRemoveImage,
                onSend = onSend,
                canSend = canSend,
                notice = notice,
                onRetry = onRetry,
                canRetry = canRetry,
                assistantContent = assistantContent,
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
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
) {
    Surface(
        modifier = modifier,
        color = ChatPanel,
        elevation = if (elevated) 12.dp else 0.dp,
        shape = if (elevated) RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp) else RoundedCornerShape(0.dp),
    ) {
        Column(
            Modifier.fillMaxSize()
                .then(if (!elevated) Modifier.border(width = 1.dp, color = ChatLine) else Modifier)
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onNewSession(); onDismiss() },
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
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("还没有对话", color = ChatMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    itemsIndexed(sessions, key = { _, session -> session.id }) { index, session ->
                        val selected = session.selected
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (selected) ChatPurpleLight else Color.Transparent)
                                .clickable { onSelectSession(session.id); onDismiss() }
                                .padding(horizontal = 11.dp, vertical = 10.dp)
                                .testTag("session-item-$index"),
                        ) {
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
                    }
                }
            }
        }
    }
}

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
    onChooseImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    notice: String?,
    onRetry: (Entry) -> Unit,
    canRetry: Boolean,
    assistantContent: @Composable (Entry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedTitle = sessions.firstOrNull { it.selected }?.title?.ifBlank { "新会话" } ?: "新会话"
    Column(modifier.background(ChatCanvas)) {
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
        LaunchedEffect(chat.session, lastEntry?.answer, lastEntry?.status) {
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
                        }
                    }
                }
            }
            itemsIndexed(chat.entries) { index, entry ->
                Column(Modifier.fillMaxWidth().testTag("message-entry-$index"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (entry.question.isNotBlank() || entry.attachmentDisplayName != null) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            Surface(
                                modifier = Modifier.align(Alignment.CenterEnd)
                                    .widthIn(max = minOf(720.dp, maxWidth * .82f))
                                    .testTag("message-user-$index"),
                                color = ChatPurpleLight,
                                shape = RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp),
                            ) {
                                Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                                    entry.attachmentDisplayName?.let {
                                        Text("图片 · $it", color = ChatMuted, fontSize = 11.sp)
                                        if (entry.question.isNotBlank()) Spacer(Modifier.height(5.dp))
                                    }
                                    if (entry.question.isNotBlank()) Text(entry.question, color = ChatInk, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        Surface(
                            modifier = Modifier.align(Alignment.CenterStart)
                                .widthIn(max = minOf(760.dp, maxWidth * .86f))
                                .testTag("message-assistant-$index"),
                            color = ChatAssistant,
                            shape = RoundedCornerShape(5.dp, 18.dp, 18.dp, 18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ChatLine),
                        ) {
                            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                                assistantContent(entry)
                                if (entry.status != "已完成") {
                                    Spacer(Modifier.height(7.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(entry.status, color = ChatMuted, fontSize = 11.sp)
                                        if (canRetry && entry.status.isNotBlank()) {
                                            Spacer(Modifier.width(6.dp))
                                            TextButton(
                                                onClick = { onRetry(entry) },
                                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                                modifier = Modifier.testTag("retry-message-$index"),
                                            ) { Text("重试", color = ChatPurple, fontSize = 12.sp) }
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
        Composer(
            draft = draft,
            onDraft = onDraft,
            selectedImage = selectedImage,
            onChooseImage = onChooseImage,
            onRemoveImage = onRemoveImage,
            onSend = onSend,
            canSend = canSend,
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp, top = 8.dp),
        )
    }
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
) {
    Surface(
        modifier = modifier.testTag("composer"),
        color = ChatPanel,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ChatLine),
        elevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            selectedImage?.let { path ->
                SelectedImagePreview(path = path, onRemove = onRemoveImage)
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
                                if (selectedImage == null) "输入消息…（Enter 发送，Shift+Enter 换行）" else "添加图片说明…",
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
private fun SelectedImagePreview(path: Path, onRemove: () -> Unit) {
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
        TextButton(onClick = onRemove, modifier = Modifier.testTag("remove-image-button")) {
            Text("移除", color = ChatMuted, fontSize = 12.sp)
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
