package dev.polarzen.chatbox

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun mobileMediaDuration(millis: Long): String {
    if (millis < 1000) return "<1 秒"
    val seconds = millis / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** The audio manager delivers focus changes on the main thread, alongside player callbacks. */
private class MobilePlayback(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    var active by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    fun play(path: String) {
        stop()
        failure = null
        try {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val next = MediaPlayer()
            player = next
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { change ->
                    if (change < 0 && player === next) stop()
                }.build()
            check(manager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "无法获得音频焦点。" }
            focus = request
            active = true
            next.setAudioAttributes(attributes)
            next.setOnCompletionListener { if (player === next) stop() }
            next.setOnErrorListener { _, _, _ ->
                if (player === next) { failure = "此设备无法播放该语音。"; stop() }
                true
            }
            next.setOnPreparedListener {
                if (player === next) try {
                    next.start()
                } catch (e: Exception) {
                    failure = e.localizedMessage ?: "播放设备不可用。"
                    stop()
                }
            }
            next.setDataSource(path)
            next.prepareAsync()
        } catch (e: Exception) {
            failure = e.localizedMessage ?: "播放设备不可用。"
            stop()
        }
    }

    fun stop() {
        val previous = player
        player = null
        try {
            previous?.release()
        } finally {
            focus?.let { manager?.abandonAudioFocusRequest(it) }
            focus = null
            active = false
        }
    }
}

@Composable
private fun AudioAsset(asset: MobileAsset) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playback = remember(asset.path) { MobilePlayback(context.applicationContext) }
    val duration by produceState<Result<Long>?>(null, asset.path) {
        value = withContext(Dispatchers.IO) { runCatching { mobileWavDuration(File(asset.path)) } }
    }
    DisposableEffect(playback, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) playback.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); playback.stop() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = duration?.isSuccess == true, onClick = {
            if (playback.active) playback.stop() else playback.play(asset.path)
        }) { Text(if (playback.active) "停止播放" else "播放语音") }
        duration?.getOrNull()?.let { Text(mobileMediaDuration(it)) }
    }
    if (duration?.isFailure == true) Text("语音不可用")
    playback.failure?.let { Text("播放失败：$it", color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ImageAsset(asset: MobileAsset) {
    val small by produceState<Result<Bitmap>?>(null, asset.path) {
        value = withContext(Dispatchers.IO) { runCatching { mobileBitmap(File(asset.path), 256) } }
    }
    var opened by remember(asset.path) { mutableStateOf(false) }
    val thumb = small?.getOrNull()
    if (thumb != null) {
        Image(thumb.asImageBitmap(), contentDescription = "查看图片 ${asset.name}",
            contentScale = ContentScale.Fit, modifier = Modifier.size(96.dp).clickable(role = Role.Button) { opened = true })
    } else Text(if (small == null) "正在读取图片…" else "图片不可用")
    if (opened) Dialog(onDismissRequest = { opened = false }) {
        val large by produceState<Result<Bitmap>?>(null, asset.path) {
            value = withContext(Dispatchers.IO) { runCatching { mobileBitmap(File(asset.path), 2048) } }
        }
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(asset.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                large?.getOrNull()?.let {
                    Image(it.asImageBitmap(), asset.name, Modifier.fillMaxWidth().weight(1f, fill = false), contentScale = ContentScale.Fit)
                } ?: Text(if (large == null) "正在读取图片…" else "图片不可用")
                TextButton(onClick = { opened = false }) { Text("关闭") }
            }
        }
    }
}

@Composable
internal fun AndroidAssetList(assets: List<MobileAsset>, onRemove: ((MobileAsset) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (asset in assets) key(asset) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (asset.name.isNotBlank()) Text(asset.name, style = MaterialTheme.typography.labelMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when {
                        asset.path.isBlank() -> Text(if (asset.kind == "audio") "语音不可用" else "图片不可用")
                        asset.kind == "audio" -> AudioAsset(asset)
                        asset.kind == "image" -> ImageAsset(asset)
                        else -> Text("附件不可用")
                    }
                }
                if (onRemove != null) TextButton(onClick = { onRemove(asset) }) { Text("移除") }
            }
        }
    }
}
