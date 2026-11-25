@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.polarzen.chatbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.LocalSelectionRegistrar
import androidx.compose.foundation.text.selection.SelectionRegistrar
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReplyAttachmentKey(
    val profileId: String,
    val sessionId: String,
    val attachmentId: String,
    val type: ReplyAttachmentType,
)

@Composable
internal fun DesktopReplyContent(
    profileId: String,
    sessionId: String,
    answer: String,
    attachments: List<ReplyAttachment>,
    imageCache: ReplyImageCache,
    audioCache: ReplyAudioCache,
    cacheGeneration: Map<ReplyAttachmentKey, Int>,
    failures: Map<ReplyAttachmentKey, String>,
    retrying: Set<ReplyAttachmentKey>,
    retryEnabled: Boolean,
    onRetry: (ReplyAttachmentKey) -> Unit,
    partnerName: String = "助手",
) {
    if (answer.isEmpty() && attachments.isEmpty()) return
    val urlRanges = remember(answer) { chatUrlRanges(answer) }
    Column {
        var cursor = 0
        // sortedBy is stable: attachments at the same text offset retain their SSE order.
        val orderedAttachments = attachments.sortedBy { it.textOffset }
        for (index in 0..orderedAttachments.size) {
            val attachment = orderedAttachments.getOrNull(index)
            val offset = attachment?.textOffset?.coerceIn(cursor, answer.length) ?: answer.length
            if (offset > cursor) {
                val bodyOffset = cursor
                val body = answer.substring(bodyOffset, offset)
                key(profileId, sessionId, bodyOffset) {
                    SelectableReplyText(body, bodyOffset, 0, urlRanges)
                }
            }
            if (attachment == null) break
            DisableSelection {
                val key = attachment.attachmentId?.let { ReplyAttachmentKey(profileId, sessionId, it, attachment.type) }
                if (key == null) {
                    Text("${attachment.type.label}不可用：服务未提供附件编号。", modifier = Modifier.padding(start = 8.dp))
                } else {
                    DesktopReplyAttachment(key, imageCache, audioCache, cacheGeneration[key] ?: 0,
                        failures[key], key in retrying, retryEnabled, onRetry, partnerName)
                }
            }
            cursor = offset
        }
    }
}

@Composable
private fun SelectableReplyText(text: String, bodyOffset: Int, prefixLength: Int, urlRanges: List<TextRange>) {
    val parent = LocalSelectionRegistrar.current
    if (parent == null) {
        SearchHitText(text, isAnswer = true, bodyOffset = bodyOffset, prefixLength = prefixLength, urlRanges = urlRanges)
        return
    }
    // Compose 1.7.3 calls onTextLayout immediately before notifying the registrar.
    // An appended suffix leaves every existing selection offset valid.
    val registrar = remember(parent) {
        object : SelectionRegistrar by parent {
            var previousText: String? = null
            var appendOnly = false

            fun recordLayout(newText: String) {
                val previous = previousText
                appendOnly = previous != null && newText.startsWith(previous)
                previousText = newText
            }

            override fun notifySelectableChange(selectableId: Long) {
                if (!appendOnly) parent.notifySelectableChange(selectableId)
            }
        }
    }
    CompositionLocalProvider(LocalSelectionRegistrar provides registrar) {
        SearchHitText(text, isAnswer = true, bodyOffset = bodyOffset, prefixLength = prefixLength,
            urlRanges = urlRanges, onTextLayout = { registrar.recordLayout(it.layoutInput.text.text) })
    }
}

@Composable
private fun DesktopReplyAttachment(
    key: ReplyAttachmentKey,
    imageCache: ReplyImageCache,
    audioCache: ReplyAudioCache,
    generation: Int,
    failure: String?,
    retrying: Boolean,
    retryEnabled: Boolean,
    onRetry: (ReplyAttachmentKey) -> Unit, partnerName: String,
) {
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var validated by remember(key, generation) { mutableStateOf<ValidatedAudio?>(null) }
    var checked by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, generation) {
        when (key.type) {
            ReplyAttachmentType.IMAGE -> bitmap = withContext(Dispatchers.IO) { imageCache.read(key.profileId, key.attachmentId) }
            ReplyAttachmentType.AUDIO -> validated = withContext(Dispatchers.IO) { audioCache.readAudio(key.profileId, key.attachmentId) }
        }
        checked = true
    }
    val audio = validated
    val image = bitmap
    val label = key.type.label
    if (audio != null) {
        ReplyAudioPlayback(audio.path, audio.durationMillis)
    } else if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "${partnerName}的回复图片",
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(start = 8.dp),
            contentScale = ContentScale.Fit,
        )
    } else if (retrying) {
        Text("${label}获取中…", modifier = Modifier.padding(start = 8.dp))
    } else {
        Text(
            when {
                failure != null -> "${label}暂不可用：$failure"
                checked -> "${label}尚未缓存。"
                else -> "正在读取${label}…"
            },
            modifier = Modifier.padding(start = 8.dp),
        )
        TextButton(onClick = { onRetry(key) }, enabled = checked && retryEnabled && !retrying) {
            Text("重试获取$label")
        }
    }
}
