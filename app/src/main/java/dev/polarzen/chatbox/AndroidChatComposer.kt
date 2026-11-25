package dev.polarzen.chatbox

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

// The surrounding screen already consumes system bars. Measure for the larger viewport once,
// then let RenderNode translate the existing content with the platform's actual IME progress.
// Compose advances the source to the target when the platform finishes (including cancellation).
internal class AndroidChatIme(
    val layoutBottom: State<Int>,
    private val currentBottom: () -> Int,
) {
    fun translation() = (layoutBottom.value - currentBottom()).toFloat()

    // Top-aligned short transcripts have space below their last row. Move them only when
    // that space has been consumed by the keyboard.
    fun historyTranslation(bottomGap: Int) = minOf(0f, translation() + bottomGap.coerceAtLeast(0))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberChatIme(): AndroidChatIme {
    val density = LocalDensity.current
    val source = WindowInsets.imeAnimationSource
    val target = WindowInsets.imeAnimationTarget
    val current = WindowInsets.ime
    val bars = WindowInsets.systemBars
    return remember(density, source, target, current, bars) {
        fun bottom(insets: WindowInsets) = (insets.getBottom(density) - bars.getBottom(density)).coerceAtLeast(0)
        AndroidChatIme(derivedStateOf { minOf(bottom(source), bottom(target)) }, { bottom(current) })
    }
}

internal fun Modifier.chatImeHeight(ime: AndroidChatIme) = layout { measurable, constraints ->
    val bottom = ime.layoutBottom.value.coerceAtMost(constraints.maxHeight)
    val child = measurable.measure(constraints.offset(vertical = -bottom))
    layout(child.width, child.height + bottom) { child.place(0, 0) }
}

internal fun Modifier.moveWithChatIme(ime: AndroidChatIme) =
    graphicsLayer { translationY = ime.translation() }

/** Keep older messages at their top-relative position when the available height changes.
 * The parent resizes for IME endpoints; notices and attachment previews can resize later. */
@Composable
internal fun Modifier.chatImeReverseHistory(ime: AndroidChatIme, list: LazyListState, restoredHeight: Int = -1): Modifier {
    val previous = remember(list) {
        object {
            var height = restoredHeight
        }
    }
    return layout { measurable, constraints ->
        val height = constraints.maxHeight
        if (previous.height >= 0 && previous.height != height) {
            Snapshot.withoutReadObservation {
                // Index and offset already describe the restored position before the first measure.
                val index = list.firstVisibleItemIndex
                val offset = list.firstVisibleItemScrollOffset
                if (index != 0 || offset != 0) {
                    list.requestScrollToItem(index, offset + previous.height - height)
                }
            }
        }
        previous.height = height
        val child = measurable.measure(constraints)
        layout(child.width, child.height) { child.place(0, 0) }
    }.graphicsLayer {
        translationY = if (list.canScrollBackward) 0f else {
            val end = list.layoutInfo.visibleItemsInfo.firstOrNull()
            ime.historyTranslation(end?.offset ?: 0)
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
            Icon(Icons.AutoMirrored.Filled.Send, "发送", Modifier.size(22.dp))
        }
    }
}
