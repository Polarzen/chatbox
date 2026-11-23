package dev.polarzen.chatbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SessionEditor(initial: ComposerDraft, initialRevision: Long) {
    var field by mutableStateOf(TextFieldValue(initial.text, selection = TextRange(initial.text.length)))
    var draft by mutableStateOf(initial)
    var revision = initialRevision
    var dirty = false
}

/** UI state belongs to the originating session even when an import or recording finishes after navigation. */
internal class DesktopComposerSession(
    private val store: DesktopStore,
    private val scope: CoroutineScope,
    private val notice: (SessionKey, String?) -> Unit,
) {
    private val editors = mutableMapOf<SessionKey, SessionEditor>()
    private val saves = mutableMapOf<SessionKey, Job>()
    private val imports = mutableStateMapOf<SessionKey, Deferred<String?>>()
    private var mic by mutableStateOf<Pair<SessionKey, MicRecording>?>(null)
    private var discardMic = false
    private var micCompletion: Job? = null
    var elapsed by mutableStateOf(0L)
        private set

    fun editor(key: SessionKey) = editors[key]
    fun pendingImport(key: SessionKey) = imports[key]
    val recordingKey get() = mic?.first

    // DesktopStore has already loaded the history. This reads only the published in-memory row.
    fun open(key: SessionKey): SessionEditor = editors.getOrPut(key) {
        SessionEditor(store.readDraft(key), store.nextDraftRevision())
    }

    private fun update(key: SessionKey, editor: SessionEditor, draft: ComposerDraft) {
        if (draft == editor.draft) return
        editor.draft = draft
        editor.revision = store.nextDraftRevision()
        editor.dirty = true
        if (key.sessionId.isBlank()) return
        saves.remove(key)?.cancel()
        saves[key] = scope.launch {
            delay(300)
            saveWithNotice(key)
        }
    }

    fun input(key: SessionKey, value: TextFieldValue) {
        val editor = editors[key] ?: return
        editor.field = value
        if (value.composition == null) update(key, editor, editor.draft.copy(text = value.text))
    }

    fun remove(key: SessionKey, path: Path?) {
        val editor = editors[key] ?: return
        val remaining = if (path == null) emptyList() else editor.draft.attachments.filterNot { it == path }
        update(key, editor, editor.draft.copy(attachments = remaining))
    }

    /** Serialize imports per editor, keeping the order of successive pastes even for large bitmaps. */
    private fun enqueue(key: SessionKey, action: suspend () -> String?) {
        val previous = imports[key]
        val job = scope.async {
            val earlierIssue = previous?.await()
            try {
                if (key in editors) action() ?: earlierIssue else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "无法添加附件：${e.localizedMessage ?: "读取失败"}"
                if (key in editors) notice(key, message)
                message
            }
        }
        imports[key] = job
        job.invokeOnCompletion {
            scope.launch { if (imports[key] === job) imports.remove(key) }
        }
    }

    fun addFiles(key: SessionKey, files: List<File>, invalidPathCount: Int = 0) {
        enqueue(key) {
            val existing = editors.getValue(key).draft.attachments
            val addition = withContext(Dispatchers.IO) { addSourceImages(existing, files, invalidPathCount) }
            append(key, addition.paths.filterNot { it in existing }, addition.notice)
            addition.notice
        }
    }

    private fun append(key: SessionKey, paths: List<Path>, message: String?) {
        val editor = editors[key] ?: return
        val combined = (editor.draft.attachments + paths).distinct()
        update(key, editor, editor.draft.copy(attachments = combined))
        notice(key, message)
    }

    fun paste(key: SessionKey): Boolean {
        if (key !in editors) return false
        when (val capture = captureClipboardImages()) {
            ClipboardImages.NativePaste -> return false
            is ClipboardImages.Files -> addFiles(key, capture.files)
            is ClipboardImages.Failure -> notice(key, capture.message)
            is ClipboardImages.Bitmap -> enqueue(key) {
                val path = withContext(Dispatchers.IO) { writeClipboardPng(capture.image, store.managedMediaDirectory) }
                append(key, listOf(path), null)
                null
            }
        }
        return true
    }

    fun startMic(key: SessionKey, start: (Path) -> MicRecording = { MicRecording.start(it) }) {
        if (mic != null || key !in editors) return
        val capture = start(store.managedMediaDirectory)
        mic = key to capture
        discardMic = false
        elapsed = 0
        notice(key, null)
        micCompletion = scope.launch {
            val ticker = launch {
                while (true) {
                    elapsed = capture.elapsedMillis
                    delay(150)
                }
            }
            try {
                val result = capture.result.await()
                // Preserve the order of imports accepted before this recording finished.
                if (!discardMic) enqueue(key) {
                    append(key, listOfNotNull(result.path), result.notice)
                    result.notice
                }
            } finally {
                ticker.cancel()
                mic = null
            }
        }
    }

    fun stopMic(key: SessionKey, discard: Boolean = false) {
        val capture = mic?.takeIf { it.first == key }?.second ?: return
        if (discard) {
            discardMic = true
            capture.cancel()
        } else capture.stop()
    }

    fun leave(key: SessionKey) {
        stopMic(key)
        editors[key]?.let { editor ->
            if (editor.field.composition != null) editor.field = TextFieldValue(
                editor.draft.text, selection = TextRange(editor.draft.text.length),
            )
        }
        scope.launch {
            if (recordingKey == key) micCompletion?.join()
            imports[key]?.join()
            saveWithNotice(key)
        }
    }

    fun forget(key: SessionKey) {
        stopMic(key, discard = true)
        editors.remove(key)
        saves.remove(key)?.cancel()
    }

    fun consumed(key: SessionKey, revision: Long) {
        val editor = editors[key] ?: return
        if (editor.revision != revision) return
        saves.remove(key)?.cancel()
        editor.draft = ComposerDraft()
        if (editor.field.composition == null) editor.field = TextFieldValue()
        editor.dirty = false
    }

    private suspend fun save(key: SessionKey) {
        if (key.sessionId.isBlank()) return
        val editor = editors[key]?.takeIf { it.dirty } ?: return
        val revision = editor.revision
        val snapshot = editor.draft
        withContext(Dispatchers.IO) { store.saveDraft(key, revision, snapshot) }
        if (editor.revision == revision) editor.dirty = false
    }

    private suspend fun saveWithNotice(key: SessionKey) {
        try {
            save(key)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (key in editors) notice(key, "无法保存草稿：${e.localizedMessage ?: "写入失败"}")
        }
    }

    suspend fun flushForExit(): String? {
        val capture = mic?.second
        val pending = imports.values.toList()
        capture?.stop()
        micCompletion?.join()
        // Recording completion may have queued its final WAV while the device was closing.
        val issue = (pending + imports.values.toList()).distinct().mapNotNull { it.await() }.firstOrNull()
        editors.keys.toList().forEach { save(it) }
        return issue ?: capture?.result?.await()?.notice
    }
}
