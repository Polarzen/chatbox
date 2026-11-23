package dev.polarzen.chatbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

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
) {
    if (answer.isEmpty() && attachments.isEmpty()) return
    Column {
        var cursor = 0
        var textLabelShown = false
        // sortedBy is stable: attachments at the same text offset retain their SSE order.
        attachments.sortedBy { it.textOffset }.forEach { attachment ->
            val offset = attachment.textOffset.coerceIn(cursor, answer.length)
            if (offset > cursor) {
                val prefix = if (textLabelShown) "" else "助手："
                Text(prefix + answer.substring(cursor, offset))
                textLabelShown = true
            }
            DisableSelection {
                val key = attachment.attachmentId?.let { ReplyAttachmentKey(profileId, sessionId, it, attachment.type) }
                if (key == null) {
                    Text("${attachment.type.label}不可用：服务未提供附件编号。", modifier = Modifier.padding(start = 8.dp))
                } else {
                    DesktopReplyAttachment(key, imageCache, audioCache, cacheGeneration[key] ?: 0,
                        failures[key], key in retrying, retryEnabled, onRetry)
                }
            }
            cursor = offset
        }
        if (cursor < answer.length) {
            val prefix = if (textLabelShown) "" else "助手："
            Text(prefix + answer.substring(cursor))
            textLabelShown = true
        }
        if (!textLabelShown && attachments.isNotEmpty()) Text("助手：")
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
    onRetry: (ReplyAttachmentKey) -> Unit,
) {
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var audioPath by remember(key) { mutableStateOf<Path?>(null) }
    var checked by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, generation) {
        when (key.type) {
            ReplyAttachmentType.IMAGE -> bitmap = withContext(Dispatchers.IO) { imageCache.read(key.profileId, key.attachmentId) }
            ReplyAttachmentType.AUDIO -> audioPath = withContext(Dispatchers.IO) { audioCache.read(key.profileId, key.attachmentId) }
        }
        checked = true
    }
    val audio = audioPath
    val image = bitmap
    val label = key.type.label
    if (audio != null) {
        ReplyAudioPlayback(audio)
    } else if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "助手回复图片",
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
