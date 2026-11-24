package dev.polarzen.chatbox

import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DesktopRecentRow(val key: SessionKey, val info: SessionInfo, val label: String?, val tagId: String = key.sessionId)

/** Sidebar identity includes the source even if multiple AstrBot users have the same session ID. */
internal fun desktopRecentRows(
    store: DesktopStore, selected: SessionKey, shared: List<DesktopDeviceRecent>,
): List<DesktopRecentRow> = synchronized(store) {
    val directory = shared.associateBy { it.key }
    val profiles = store.profiles
    val rows = profiles.flatMap { profile ->
        store.sessions(profile.id).map { session ->
            val key = SessionKey(profile.id, session.id)
            val remote = directory[key]
            val rowId = if (profiles.size == 1) session.id else "${profile.id.length}:${profile.id}${session.id}"
            DesktopRecentRow(key, session.copy(id = rowId, selected = key == selected), remote?.source ?: profileDisplayName(profile))
        }
    }
    val ordered = if (profiles.size < 2) rows else rows.sortedByDescending { it.info.activity }
    val representatives = store.deviceRecentRepresentatives(selected, ordered.map { it.key })
    val visible = ordered.filter { it.key in representatives }
    val duplicates = visible.groupingBy { it.info.title }.eachCount()
    val sameSessionId = visible.groupingBy { it.key.sessionId }.eachCount()
    visible.map { row -> row.copy(
        label = row.label.takeIf { duplicates[row.info.title] != 1 },
        tagId = if (sameSessionId[row.key.sessionId] == 1) row.key.sessionId else row.info.id,
    ) }
}

internal suspend fun sendDeviceDraft(
    sync: DesktopDeviceSync, key: SessionKey, editor: SessionEditor, composer: DesktopComposerSession,
    store: DesktopStore, images: ReplyImageCache, audio: ReplyAudioCache, notice: (String?) -> Unit,
) {
    val serial = editor.editSerial
    var locked = false
    try {
        val issue = composer.pendingImport(key)?.await()
        if (issue != null) { notice(issue); return }
        if (editor.editSerial != serial || editor.field.composition != null || editor.commitInProgress ||
            composer.recordingKey == key) return
        val revision = editor.revision
        val draft = editor.draft
        val question = draft.text.trim()
        if (question.isEmpty() && draft.attachments.isEmpty()) return
        editor.commitInProgress = true
        locked = true
        notice(null)
        val accepted = if (draft.attachments.isEmpty()) sync.send(key, question, revision) else {
            val error = withContext(Dispatchers.IO) { attachmentProblem(draft.attachments, store.managedMediaDirectory) }
            if (error != null) { notice(error); return }
            val entry = Entry(question, "", "待发送", attachmentReference = draft.attachments.first().toString(),
                attachmentDisplayName = draft.attachments.first().fileName.toString(), attachmentReferences = draft.attachments.map(Path::toString))
            sync.sendMedia(key, entry, revision) { target -> withContext(Dispatchers.IO) {
                var answer = ""
                val attachments = mutableListOf<ReplyAttachment>()
                sendAttachmentsToAstrBot(target.address, target.token, target.username, target.sid, draft.attachments, question,
                    allowLoopbackHttp = target.allowLoopbackHttp,
                    onReplyImage = { id, offset -> attachments += ReplyAttachment(id, offset, ReplyAttachmentType.IMAGE) },
                    onReplyAudio = { id, offset -> attachments += ReplyAttachment(id, offset, ReplyAttachmentType.AUDIO) },
                    onText = { answer = it })
                for (item in attachments.distinctBy { it.type to it.attachmentId }) {
                    val id = item.attachmentId ?: continue
                    // Download failure keeps the original attachment reference available for native retry.
                    runCatching { when (item.type) {
                        ReplyAttachmentType.IMAGE -> images.save(key.profileId, id,
                            downloadReplyImage(target.address, target.token, id, target.allowLoopbackHttp))
                        ReplyAttachmentType.AUDIO -> audio.save(key.profileId, id,
                            downloadReplyAudio(target.address, target.token, id, target.allowLoopbackHttp))
                    } }
                }
                entry.copy(answer = answer, status = "已完成", replyAttachments = attachments)
            } }
        }
        if (accepted) composer.consumed(key, revision) else notice(sync.notice.ifBlank { "请检查连接，草稿仍保存在本机。" })
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        notice("发送状态待核对。恢复连接后将按原消息编号检查。")
    } finally {
        if (locked) editor.commitInProgress = false
    }
}
