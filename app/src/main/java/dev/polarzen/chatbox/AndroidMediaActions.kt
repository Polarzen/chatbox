package dev.polarzen.chatbox

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

private fun addPickedImages(context: Context, uris: List<Uri>, add: (List<MobileAsset>) -> Unit, note: (String) -> Unit) {
    if (uris.isEmpty()) return
    val app = context.applicationContext
    // The activity may rotate or leave this session during a copy. Deliver to the captured draft owner.
    thread(name = "Chatbox image import") {
        val result = runCatching { importMobileImages(app, uris) }
        Handler(Looper.getMainLooper()).post {
            result.fold(onSuccess = { assets ->
                try {
                    add(assets)
                    val skipped = uris.distinct().size - assets.size
                    note(if (skipped > 0) "已添加 ${assets.size} 张图片，跳过 $skipped 个不可用或格式不支持的附件。" else "")
                } catch (e: Exception) {
                    note("无法保存图片草稿：${e.localizedMessage ?: "本机存储不可用"}")
                }
            }, onFailure = { note("无法添加图片，请重新选择或复制。${it.localizedMessage.orEmpty()}") })
        }
    }
}

@Composable
internal fun AndroidMediaActions(
    scopeId: String, enabled: Boolean, onAdd: (List<MobileAsset>) -> Unit, onNote: (String) -> Unit,
    onRecordingChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var menu by remember(scopeId) { mutableStateOf(false) }
    var pickerOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerDelivery by remember { mutableStateOf<((List<Uri>) -> Unit)?>(null) }
    var permissionOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var startAfterGrant by remember(scopeId) { mutableStateOf(false) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var recording by remember(scopeId) { mutableStateOf<MobileRecording?>(null) }
    var elapsed by remember(scopeId) { mutableLongStateOf(0L) }

    fun begin() {
        if (recording != null || !enabled) return
        val capture = MobileRecording()
        recording = capture
        elapsed = 0L
        onRecordingChanged(true)
        try {
            capture.start(context) { asset, notice ->
                try {
                    if (asset != null) onAdd(listOf(asset))
                    if (notice != null) onNote(notice)
                } catch (e: Exception) {
                    onNote("无法保存录音草稿：${e.localizedMessage ?: "本机存储不可用"}")
                } finally {
                    if (recording === capture) {
                        recording = null
                        onRecordingChanged(false)
                    }
                }
            }
        } catch (e: Exception) {
            recording = null
            onRecordingChanged(false)
            onNote("无法开始录音：${e.localizedMessage ?: "麦克风不可用"}")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val origin = pickerOrigin
        val deliver = pickerDelivery
        pickerOrigin = null
        pickerDelivery = null
        if (deliver != null) deliver(uris)
        else if (origin == scopeId) addPickedImages(context, uris, onAdd, onNote)
        else if (uris.isNotEmpty()) onNote("目标会话已更改，请重新选择图片。")
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val origin = permissionOrigin
        permissionOrigin = null
        if (origin == scopeId) {
            if (granted) startAfterGrant = true
            else onNote("未获得麦克风权限。仍可输入文字和选择图片。")
        }
    }
    DisposableEffect(scopeId, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> { resumed = false; recording?.stop() }
                Lifecycle.Event.ON_RESUME -> resumed = true
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            recording?.let {
                it.stop()
                recording = null
                onRecordingChanged(false)
            }
        }
    }
    LaunchedEffect(startAfterGrant, resumed, enabled) {
        if (startAfterGrant && resumed) { startAfterGrant = false; begin() }
        if (!enabled) recording?.stop()
    }
    LaunchedEffect(recording) {
        val capture = recording ?: return@LaunchedEffect
        while (true) { elapsed = capture.elapsedMillis; delay(200) }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "添加附件" }) { Text("＋") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("选择图片（可多选）") }, onClick = {
                        menu = false
                        pickerOrigin = scopeId
                        pickerDelivery = { addPickedImages(context, it, onAdd, onNote) }
                        runCatching { picker.launch(arrayOf("image/png", "image/jpeg")) }
                            .onFailure { pickerOrigin = null; pickerDelivery = null; onNote("系统图片选择器不可用。") }
                    })
                    DropdownMenuItem(text = { Text("粘贴图片") }, onClick = {
                        menu = false
                        val uris = runCatching {
                            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                            if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                        }.getOrNull().orEmpty()
                        if (uris.isEmpty()) onNote("剪贴板里没有可读取的图片，请复制截图后再粘贴。")
                        else addPickedImages(context, uris, onAdd, onNote)
                    })
                }
            }
            if (recording == null) {
                TextButton(enabled = enabled, modifier = Modifier.semantics { contentDescription = "开始麦克风录音" },
                    onClick = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
                        else { permissionOrigin = scopeId; permission.launch(Manifest.permission.RECORD_AUDIO) }
                    }) {
                    Icon(painterResource(android.R.drawable.ic_btn_speak_now), null, Modifier.size(20.dp))
                    Text("录音")
                }
            } else {
                TextButton(onClick = { recording?.stop() }) { Text("停止") }
                TextButton(onClick = { recording?.cancel() }) { Text("取消") }
            }
        }
        if (recording != null) Text("正在录音 ${mobileMediaDuration(elapsed)}")
    }
}
