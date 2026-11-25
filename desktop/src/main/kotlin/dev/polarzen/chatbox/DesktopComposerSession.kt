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
    var commitInProgress by mutableStateOf(false)
    var editSerial = 0L
    var replacedByComposition = ""
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
        val editor = editors[key]?.takeUnless { it.commitInProgress } ?: return
        val previous = editor.field
        val composition = value.composition
        if (previous.text != value.text || previous.composition != composition) editor.editSerial++
        editor.field = value
        val committed = if (composition == null) {
            editor.replacedByComposition = ""
            value.text
        } else {
            // An IME batch may confirm characters and begin another preedit in a single update.
            val prefix = value.text.substring(0, composition.min)
            val suffix = value.text.substring(composition.max)
            val oldRange = previous.composition ?: previous.selection
            val sameSurroundings = prefix == previous.text.substring(0, oldRange.min) &&
                suffix == previous.text.substring(oldRange.max)
            editor.replacedByComposition = when {
                previous.composition == null && previous.text == value.text ->
                    previous.text.substring(composition.min, composition.max)
                sameSurroundings && previous.composition == null -> previous.text.substring(oldRange.min, oldRange.max)
                sameSurroundings -> editor.replacedByComposition
                else -> ""
            }
            prefix + editor.replacedByComposition + suffix
        }
        update(key, editor, editor.draft.copy(text = committed))
    }

    fun remove(key: SessionKey, path: Path?) {
        val editor = editors[key]?.takeUnless { it.commitInProgress } ?: return
        editor.editSerial++
        val remaining = if (path == null) emptyList() else editor.draft.attachments.filterNot { it == path }
        update(key, editor, editor.draft.copy(attachments = remaining))
    }

    /** Serialize imports per editor, keeping the order of successive pastes even for large bitmaps. */
    private fun enqueue(key: SessionKey, action: suspend () -> String?) {
        editors[key]?.let { it.editSerial++ } ?: return
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
        if (editors[key]?.commitInProgress != false) return
        enqueue(key) {
            val existing = editors.getValue(key).draft.attachments
            val addition = withContext(Dispatchers.IO) { addSourceImages(existing, files, invalidPathCount) }
            append(key, addition.paths.filterNot { it in existing }, addition.notice)
            addition.notice
        }
    }

    fun replaceMissingAttachment(key: SessionKey, original: Path, files: List<File>) {
        if (editors[key]?.commitInProgress != false) return
        enqueue(key) {
            val selected = withContext(Dispatchers.IO) { addSourceImages(emptyList(), files, requireContent = true) }
            val editor = editors[key] ?: return@enqueue null
            val paths = editor.draft.attachments
            val index = paths.indexOf(original)
            if (index < 0) return@enqueue null
            val path = selected.paths.singleOrNull()
            val issue = when {
                selected.notice != null -> selected.notice
                files.size != 1 || path == null -> "请选择一张 PNG 或 JPEG 图片。"
                path != original && path in paths -> "该图片已在待发附件中，请选择其他图片。"
                else -> null
            }
            if (issue == null) {
                val updated = paths.toMutableList().apply { set(index, checkNotNull(path)) }
                update(key, editor, editor.draft.copy(attachments = updated))
            }
            notice(key, issue)
            issue
        }
    }

    private fun append(key: SessionKey, paths: List<Path>, message: String?) {
        val editor = editors[key] ?: return
        val combined = (editor.draft.attachments + paths).distinct()
        update(key, editor, editor.draft.copy(attachments = combined))
        notice(key, message)
    }

    fun paste(key: SessionKey, captureClipboard: () -> ClipboardImages = { captureClipboardImages() }): Boolean {
        if (key !in editors) return false
        if (editors.getValue(key).commitInProgress) return true
        when (val capture = captureClipboard()) {
            ClipboardImages.NativePaste -> return false
            is ClipboardImages.Files -> addFiles(key, capture.files)
            is ClipboardImages.Failure -> enqueue(key) {
                notice(key, capture.message)
                capture.message
            }
            is ClipboardImages.Bitmap -> enqueue(key) {
                val path = withContext(Dispatchers.IO) { writeClipboardPng(capture.image, store.managedMediaDirectory) }
                append(key, listOf(path), null)
                null
            }
        }
        return true
    }

    fun startMic(key: SessionKey, start: (Path) -> MicRecording = { MicRecording.start(it) }) {
        val editor = editors[key]?.takeUnless { it.commitInProgress } ?: return
        if (mic != null) return
        editor.editSerial++
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

    /** Handle a confirmed rejection after the editor has already consumed its queued draft. */
    fun restoreRejectedDraft(key: SessionKey): Boolean {
        val editor = editors[key] ?: return false
        if (editor.commitInProgress || editor.field.composition != null || recordingKey == key || pendingImport(key) != null) return false
        val (request, restored) = store.restoredDeviceDraft(key) ?: return false
        if (editor.draft == restored || !editor.dirty && editor.draft == ComposerDraft()) {
            if (editor.draft != restored) {
                editor.editSerial++
                editor.revision = store.nextDraftRevision()
                editor.draft = restored
                editor.field = TextFieldValue(restored.text, selection = TextRange(restored.text.length))
            }
            store.acknowledgeRestoredDraft(key, request)
        } else {
            // The store moves the rejected draft to its own session when a newer editor must win.
            editor.revision = store.nextDraftRevision()
            store.saveDraft(key, editor.revision, editor.draft)
            editor.dirty = false
        }
        return true
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

    suspend fun withLegacyDraftsSaved(
        offlineProfileIds: Set<String>, sessionIds: Set<String>, adopt: suspend () -> Set<String>,
    ): Set<String> {
        val origins = editors.filterKeys { it.profileId in offlineProfileIds && it.sessionId in sessionIds }
        if (origins.isEmpty()) return adopt()
        origins.values.forEach { it.commitInProgress = true }
        try {
            val recording = recordingKey
            if (recording != null && recording in origins) {
                stopMic(recording)
                micCompletion?.join()
            }
            for (key in origins.keys) {
                imports[key]?.await()
                try {
                    save(key)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException("legacy-draft-save", e)
                }
            }
            val moved = adopt()
            origins.keys.filter { it.sessionId in moved }.forEach(::forget)
            return moved
        } finally {
            origins.values.forEach { it.commitInProgress = false }
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
