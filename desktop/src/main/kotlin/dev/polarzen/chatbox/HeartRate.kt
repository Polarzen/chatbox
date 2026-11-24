package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collect

private data class HeartDevice(val address: String, val type: String, val name: String) {
    val label get() = "${name.ifBlank { "心率设备" }} · ${address.toLong().toString(16).takeLast(6).uppercase()}"
}

private object HeartReader {
    val executable by lazy {
        val path = Files.createTempFile("chatbox-heart-", ".exe")
        path.toFile().deleteOnExit()
        requireNotNull(javaClass.getResourceAsStream("/win32-x86-64/chatbox_heart.exe")).use {
            Files.copy(it, path, StandardCopyOption.REPLACE_EXISTING)
        }
        path.toString()
    }
}

private fun heartEvents(device: HeartDevice?) = flow {
    val args = if (device == null) listOf("scan") else listOf("connect", device.address, device.type)
    val process = ProcessBuilder(listOf(HeartReader.executable) + args).start()
    try {
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        var lastEvent = System.nanoTime()
        var streaming = false
        while (true) {
            if (reader.ready() || !process.isAlive) {
                val event = reader.readLine() ?: break
                if (event == "SUBSCRIBED" || event.startsWith("BPM\t") || event == "NO_READING") {
                    streaming = true
                    lastEvent = System.nanoTime()
                }
                emit(event)
            } else {
                val limit = if (streaming) 10_000_000_000L else 30_000_000_000L
                if (System.nanoTime() - lastEvent > limit) {
                    emit("TIMEOUT")
                    return@flow
                }
                delay(100)
            }
        }
        check(process.waitFor() == 0)
    } finally {
        process.destroyForcibly()
        process.inputStream.close()
        process.outputStream.close()
        process.errorStream.close()
    }
}.flowOn(Dispatchers.IO)

@Composable
internal fun HeartRateControl() {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
    var open by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<HeartDevice?>(null) }
    val devices = remember { mutableStateListOf<HeartDevice>() }
    var status by remember { mutableStateOf("请在手环上开启心率广播。") }
    var bpm by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(scanning, target) {
        val selected = target
        if (!scanning && selected == null) return@LaunchedEffect
        var timeout = false
        try {
            heartEvents(selected).collect { event ->
                val parts = event.split('\t', limit = 4)
                when (parts.first()) {
                    "DEVICE" -> if (parts.size == 4 && parts[1].toLongOrNull() != null) {
                        devices.add(HeartDevice(parts[1], parts[2], parts[3]))
                    }
                    "SUBSCRIBED" -> status = "已连接，等待读数。"
                    "NO_READING" -> { bpm = null; status = "暂无有效读数，请检查佩戴状态。" }
                    "BPM" -> { bpm = parts.getOrNull(1)?.toIntOrNull(); status = "实时心率" }
                    "TIMEOUT" -> timeout = true
                }
            }
            status = when {
                timeout -> "长时间未收到数据，请重新扫描连接。"
                devices.isEmpty() -> "未发现心率广播，请检查手环和电脑蓝牙。"
                else -> "请选择心率设备。"
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            status = if (selected == null) "扫描失败，请检查电脑蓝牙。" else "设备已断开或连接失败，请重新扫描。"
        }
        bpm = null
        scanning = false
        target = null
    }

    TextButton(onClick = { open = true }) { Text(bpm?.let { "♥ $it" } ?: "心率") }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("手环心率") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(bpm?.let { "$it BPM" } ?: status)
                if (target == null && !scanning) devices.forEach { device ->
                    TextButton(onClick = {
                        status = "正在连接…"
                        target = device
                    }) { Text(device.label) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (scanning || target != null) {
                    scanning = false
                    target = null
                    bpm = null
                    status = "已停止。"
                    devices.clear()
                } else {
                    devices.clear()
                    status = "正在扫描附近的心率广播…"
                    scanning = true
                }
            }) { Text(if (scanning || target != null) "停止" else "扫描设备") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("关闭面板") } }
    )
}
