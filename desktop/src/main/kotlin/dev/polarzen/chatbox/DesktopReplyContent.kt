package dev.polarzen.chatbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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

internal data class ReplyImageKey(val profileId: String, val sessionId: String, val attachmentId: String)

@Composable
internal fun DesktopReplyContent(
    profileId: String,
    sessionId: String,
    answer: String,
    images: List<ReplyImage>,
    cache: ReplyImageCache,
    cacheGeneration: Map<ReplyImageKey, Int>,
    failures: Map<ReplyImageKey, String>,
    retrying: Set<ReplyImageKey>,
    retryEnabled: Boolean,
    onRetry: (String) -> Unit,
) {
    if (answer.isEmpty() && images.isEmpty()) return
    Column {
        var cursor = 0
        var textLabelShown = false
        images.withIndex().sortedWith(compareBy<IndexedValue<ReplyImage>> { it.value.textOffset }.thenBy { it.index })
            .forEach { (_, replyImage) ->
                val offset = replyImage.textOffset.coerceIn(cursor, answer.length)
                if (offset > cursor) {
                    val prefix = if (textLabelShown) "" else "助手："
                    Text(prefix + answer.substring(cursor, offset))
                    textLabelShown = true
                }
                DesktopReplyImage(
                    profileId = profileId,
                    sessionId = sessionId,
                    replyImage = replyImage,
                    cache = cache,
                    generation = replyImage.attachmentId?.let { cacheGeneration[ReplyImageKey(profileId, sessionId, it)] } ?: 0,
                    failure = replyImage.attachmentId?.let { failures[ReplyImageKey(profileId, sessionId, it)] },
                    retrying = replyImage.attachmentId?.let { ReplyImageKey(profileId, sessionId, it) in retrying } ?: false,
                    retryEnabled = retryEnabled,
                    onRetry = onRetry,
                )
                cursor = offset
            }
        if (cursor < answer.length) {
            val prefix = if (textLabelShown) "" else "助手："
            Text(prefix + answer.substring(cursor))
            textLabelShown = true
        }
        if (!textLabelShown && images.isNotEmpty()) Text("助手：")
    }
}

@Composable
private fun DesktopReplyImage(
    profileId: String,
    sessionId: String,
    replyImage: ReplyImage,
    cache: ReplyImageCache,
    generation: Int,
    failure: String?,
    retrying: Boolean,
    retryEnabled: Boolean,
    onRetry: (String) -> Unit,
) {
    val attachmentId = replyImage.attachmentId
    if (attachmentId == null) {
        Text("图片不可用：服务未提供附件编号。", modifier = Modifier.padding(start = 8.dp))
        return
    }
    var bitmap by remember(profileId, sessionId, attachmentId) { mutableStateOf<ImageBitmap?>(null) }
    var checked by remember(profileId, sessionId, attachmentId) { mutableStateOf(false) }
    LaunchedEffect(profileId, sessionId, attachmentId, generation) {
        val cached = withContext(Dispatchers.IO) { runCatching { cache.read(profileId, attachmentId) }.getOrNull() }
        bitmap = cached
        checked = true
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "助手回复图片",
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(start = 8.dp),
            contentScale = ContentScale.Fit,
        )
    } else if (retrying) {
        Text("图片获取中…", modifier = Modifier.padding(start = 8.dp))
    } else {
        Text(
            when {
                failure != null -> "图片暂不可用：$failure"
                checked -> "图片尚未缓存。"
                else -> "正在读取图片…"
            },
            modifier = Modifier.padding(start = 8.dp),
        )
        TextButton(onClick = { onRetry(attachmentId) }, enabled = checked && retryEnabled && !retrying) {
            Text("重试获取图片")
        }
    }
}
