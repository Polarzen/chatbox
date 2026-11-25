package dev.polarzen.chatbox

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.view.WindowInsets as NativeInsets
import android.view.WindowInsetsAnimation
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

@RequiresApi(30)
private class ChatImeAnimation(private val current: MutableState<Any?>) :
    WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
    override fun onStart(animation: WindowInsetsAnimation, bounds: WindowInsetsAnimation.Bounds): WindowInsetsAnimation.Bounds {
        if (animation.typeMask and NativeInsets.Type.ime() != 0) current.value = animation
        return bounds
    }

    override fun onProgress(insets: NativeInsets, runningAnimations: List<WindowInsetsAnimation>): NativeInsets = insets

    override fun onEnd(animation: WindowInsetsAnimation) {
        if (current.value === animation) current.value = null
    }
}

@Composable
internal fun ChatHistoryViewport(
    modifier: Modifier, reading: ChatReading, content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val currentAnimation = remember(view) { mutableStateOf<Any?>(null) }
    if (Build.VERSION.SDK_INT >= 30) DisposableEffect(view) {
        // ComponentActivity.setContent owns this ancestor. Compose handles insets on its child.
        val host = generateSequence(view.parent) { it.parent }.filterIsInstance<ViewGroup>()
            .firstOrNull { it.id == android.R.id.content }
        host?.setWindowInsetsAnimationCallback(ChatImeAnimation(currentAnimation))
        onDispose { host?.setWindowInsetsAnimationCallback(null) }
    }
    val measure = remember(reading, LocalConfiguration.current, currentAnimation) {
        var previousAnimation: Any? = null
        var measuredWidth = -1
        var frozenHeight = -1
        MeasurePolicy { children, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val animation = currentAnimation.value
            val wasTranslated = frozenHeight > reading.viewportHeight
            if (animation !== previousAnimation) {
                // Only the list that already filled the viewport before the first shrink can freeze.
                frozenHeight = if (animation != null) reading.viewportHeight else -1
                previousAnimation = animation
            }
            val list = reading.list
            if (frozenHeight >= 0) {
                val sameGeometry = width == measuredWidth && height <= frozenHeight
                val pinned = !list.isScrollInProgress && !list.canScrollBackward && list.canScrollForward
                if (!sameGeometry || !pinned) frozenHeight = -1
            }
            measuredWidth = width
            // A translated child's bottom already coincides with the visible bottom on thaw.
            reading.anchorReverseHistory(height, keepAnchor = frozenHeight < 0 && !wasTranslated)
            val childHeight = if (frozenHeight >= 0) frozenHeight else height
            val child = children.single().measure(Constraints.fixed(width, childHeight))
            layout(width, height) { child.place(0, height - childHeight) }
        }
    }
    Layout(content, modifier.clipToBounds(), measure)
}

// Reverse-list offsets are relative to the bottom. Remember the actual visible height
// so resizing and restoring an older reading position preserve its distance from the top.
internal class ChatReading(val list: LazyListState = LazyListState(), restoredHeight: Int = -1) {
    var viewportHeight = restoredHeight
        private set

    fun anchorReverseHistory(height: Int, keepAnchor: Boolean) {
        val previous = viewportHeight
        viewportHeight = height
        if (keepAnchor && previous >= 0 && previous != height) {
            Snapshot.withoutReadObservation {
                val index = list.firstVisibleItemIndex
                val offset = list.firstVisibleItemScrollOffset
                if (!list.isScrollInProgress && (index != 0 || offset != 0)) {
                    list.requestScrollToItem(index, offset + previous - height)
                }
            }
        }
    }
}

internal class AndroidComposerEditor(text: String, selection: TextRange = TextRange(text.length)) {
    var value by mutableStateOf(TextFieldValue(text, selection))
        private set

    fun edit(next: TextFieldValue, onDraftText: (String) -> Unit) {
        val changed = next.text != value.text
        value = next
        if (changed) onDraftText(next.text)
    }

    fun sync(text: String) {
        if (text != value.text) value = TextFieldValue(text, TextRange(text.length))
    }

    fun insertNewline(onDraftText: (String) -> Unit) {
        val from = value.selection.min
        val to = value.selection.max
        edit(TextFieldValue(value.text.replaceRange(from, to, "\n"), TextRange(from + 1)), onDraftText)
    }
}

/** readDraft returns the owner's current in-memory text. Read it on open, send and explicit
 *  draft replacement (for example a pending send being accepted or released). IME edits and
 *  selection stay local between those events. Only cursor offsets enter the Activity bundle. */
@Composable
internal fun AndroidChatComposer(
    sessionKey: String,
    readDraft: () -> String,
    draftEvent: Any?,
    onDraftText: (String) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    enabled: Boolean = true,
    hasAttachments: Boolean = false,
    compact: Boolean = false,
    onPasteImages: (() -> Boolean)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val saver = Saver<AndroidComposerEditor, IntArray>(
        save = { intArrayOf(it.value.selection.start, it.value.selection.end) },
        restore = { AndroidComposerEditor(readDraft(), TextRange(it[0], it[1])) },
    )
    val editor = key(sessionKey) { rememberSaveable(saver = saver) { AndroidComposerEditor(readDraft()) } }
    DisposableEffect(editor, draftEvent) {
        editor.sync(readDraft())
        onDispose {}
    }
    fun canSend() = enabled && sendEnabled && (hasAttachments || editor.value.text.isNotBlank())
    fun submit() {
        if (!canSend()) return
        onSend()
        // Native sends consume the draft before returning. Pending network sends publish draftEvent later.
        editor.sync(readDraft())
    }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        actions?.invoke()
        key(sessionKey) {
            BasicTextField(
                value = editor.value,
                onValueChange = { editor.edit(it, onDraftText) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = if (compact) 88.dp else 136.dp)
                    .testTag("composer-field").semantics { contentDescription = "消息" }
                    .onPreviewKeyEvent { event ->
                        if (!enabled || event.type != KeyEventType.KeyDown ||
                            event.nativeKeyEvent.flags and NativeKeyEvent.FLAG_SOFT_KEYBOARD != 0)
                            return@onPreviewKeyEvent false
                        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        when {
                            event.key == Key.V && event.isCtrlPressed -> onPasteImages?.invoke() == true
                            !enter || editor.value.composition != null -> false
                            event.isShiftPressed -> { editor.insertNewline(onDraftText); true }
                            !event.isCtrlPressed -> {
                                if (event.nativeKeyEvent.repeatCount == 0) submit()
                                true
                            }
                            else -> false
                        }
                    },
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                maxLines = if (compact) 2 else 4,
                decorationBox = { inner ->
                    Box(Modifier.background(colors.surface, shape).border(1.dp, colors.outline, shape)
                        .padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                        if (editor.value.text.isEmpty()) Text(if (hasAttachments) "添加附件说明…" else "输入消息…",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                        inner()
                    }
                },
            )
        }
        FilledIconButton(onClick = ::submit, enabled = canSend(), modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.Send, "发送", Modifier.size(22.dp))
        }
    }
}
