package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class HeartDevice(val address: String, val type: String, val name: String) {
    val label: String get() {
        val mac = address.toLong().toString(16).padStart(12, '0').uppercase().chunked(2).joinToString(":")
        return "${name.ifBlank { "心率设备" }} · $mac（${if (type == "R") "随机地址" else "公共地址"}）"
    }
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

private fun heartEvents(device: HeartDevice?): Flow<String> = heartProcessLines {
    val args = if (device == null) listOf("scan") else listOf("connect", device.address, device.type)
    ProcessBuilder(listOf(HeartReader.executable) + args).start()
}

internal fun heartProcessLines(start: () -> Process): Flow<String> = channelFlow {
    val process = start()
    try {
        // The owner can terminate the process even when the reader is blocked on a partial line.
        val reader = launch {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use {
                    while (true) send(it.readLine() ?: break)
                }
                if (process.waitFor() != 0) send("ERROR\tREADER")
            } catch (error: IOException) {
                ensureActive()
                throw error
            }
        }
        reader.join()
    } finally {
        try {
            try {
                process.outputStream.close()
            } finally {
                if (!process.waitFor(750, TimeUnit.MILLISECONDS)) process.destroyForcibly().waitFor()
            }
        } finally {
            process.inputStream.close()
            process.errorStream.close()
        }
    }
}.flowOn(Dispatchers.IO)

// Each start has its own identity, including reconnects to the same device.
internal class HeartRun(val device: HeartDevice?)

private enum class HeartPhase { CONNECTING, WAITING_FOR_PACKET, RECEIVING }

internal class HeartRateState(
    val liveHeartRate: LiveHeartRate = LiveHeartRate(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    var active by mutableStateOf<HeartRun?>(null)
        private set
    var selected by mutableStateOf<HeartDevice?>(null)
        private set
    val devices = mutableStateListOf<HeartDevice>()
    var status by mutableStateOf("请在手环上开启心率广播。")
        private set
    var bpm by mutableStateOf<Int?>(null)
        private set

    private fun clearReading() {
        liveHeartRate.update(null)
        bpm = null
    }

    fun start(device: HeartDevice?) {
        if (device == null) {
            devices.clear()
            status = "正在扫描附近的心率广播…"
        } else {
            selected = device
            status = "正在连接 ${device.label}…"
        }
        clearReading()
        active = HeartRun(device)
    }

    fun stop() {
        active = null
        clearReading()
        status = "已停止。"
    }

    private fun finish(run: HeartRun, message: String) {
        if (active !== run) return
        clearReading()
        status = message
        active = null
    }

    private fun recordDevice(parts: List<String>) {
        if (parts.size != 4 || (parts[1].toLongOrNull() ?: -1) !in 0L..0xffffffffffffL || parts[2] !in listOf("P", "R")) return
        val device = HeartDevice(parts[1], parts[2], parts[3])
        val index = devices.indexOfFirst { it.address == device.address && it.type == device.type }
        if (index < 0) {
            devices.add(device)
        } else if (device.name.isNotBlank()) {
            devices[index] = device
        }
        if (device.name.isNotBlank() && selected?.address == device.address && selected?.type == device.type) {
            selected = device
        }
    }

    private fun errorMessage(code: String?, scanning: Boolean) = when (code) {
        "RADIO" -> "蓝牙不可用，请开启电脑蓝牙并检查蓝牙适配器。"
        "SCAN" -> "扫描失败，请检查电脑蓝牙后重新扫描。"
        "DEVICE" -> "无法连接设备，请靠近手环并确认心率广播已开启。"
        "SERVICE" -> "无法访问心率服务，请确认手环已开启标准蓝牙心率广播。"
        "CHARACTERISTIC" -> "无法读取心率测量特征，请确认设备支持标准心率广播。"
        "SUBSCRIBE" -> "无法订阅心率通知，请断开其他连接手环的应用后重试。"
        else -> if (scanning) "扫描失败，请检查电脑蓝牙。" else "连接失败，请检查心率广播后重新连接。"
    }

    suspend fun read(run: HeartRun, events: Flow<String>) {
        if (active !== run) return
        val scanning = run.device == null
        var phase = HeartPhase.CONNECTING
        var deadline = nowMs() + 30_000
        try {
            coroutineScope {
                val lines = events.produceIn(this)
                try {
                    while (active === run) {
                        val result = withTimeoutOrNull(deadline - nowMs()) { lines.receiveCatching() }
                        if (active !== run) break
                        if (result == null) {
                            val message = when {
                                scanning -> "扫描超时，请检查电脑蓝牙后重新扫描。"
                                phase == HeartPhase.CONNECTING -> "连接超时，请靠近手环并检查心率广播后重新连接。"
                                phase == HeartPhase.WAITING_FOR_PACKET -> "未收到首个心率读数，请检查手环佩戴和广播后重新连接。"
                                else -> "心率数据已中断，请检查手环距离和佩戴状态后重新连接。"
                            }
                            finish(run, message)
                            break
                        }
                        result.exceptionOrNull()?.let { throw it }
                        val event = result.getOrNull()
                        if (event == null) {
                            val message = when {
                                !scanning -> "设备已断开，请检查手环后重新连接。"
                                devices.isEmpty() -> "未发现心率广播，请检查手环和电脑蓝牙。"
                                else -> "请选择心率设备。"
                            }
                            finish(run, message)
                            break
                        }
                        val parts = event.split('\t', limit = 4)
                        when (parts.first()) {
                            "DEVICE" -> if (scanning) recordDevice(parts)
                            "ERROR" -> finish(run, errorMessage(parts.getOrNull(1), scanning))
                            "DISCONNECTED" -> finish(run, "设备已断开，请检查手环后重新连接。")
                            "SUBSCRIBED" -> if (!scanning && phase == HeartPhase.CONNECTING) {
                                phase = HeartPhase.WAITING_FOR_PACKET
                                deadline = nowMs() + 10_000
                                status = "已订阅心率通知，等待首个读数…"
                            }
                            "BPM", "NO_READING" -> if (!scanning) {
                                val epochMs = if (parts.first() == "BPM" && parts.size == 3) parts[2].toLongOrNull() else null
                                val reading = parts.getOrNull(1)?.toIntOrNull()
                                if (epochMs != null && liveHeartRate.update(reading, Instant.ofEpochMilli(epochMs))) {
                                    phase = HeartPhase.RECEIVING
                                    deadline = nowMs() + liveHeartRate.remainingMillis()
                                    bpm = reading
                                    status = "实时心率"
                                } else {
                                    clearReading()
                                    status = "暂无有效读数，请检查佩戴状态。"
                                    if (parts.first() == "NO_READING") {
                                        phase = HeartPhase.RECEIVING
                                        deadline = nowMs() + 10_000
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    lines.cancel()
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            finish(run, errorMessage(null, scanning))
        }
    }
}

@Composable
internal fun rememberHeartRateState(events: (HeartDevice?) -> Flow<String> = ::heartEvents): HeartRateState {
    val state = remember { HeartRateState() }
    DisposableEffect(state) {
        onDispose { state.stop() }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.active }.collectLatest { run ->
            if (run != null) state.read(run, events(run.device))
        }
    }
    return state
}

@Composable
internal fun HeartRatePanel(state: HeartRateState) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(state.bpm?.let { "♥ $it" } ?: "心率") }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("手环心率") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(state.bpm?.let { "$it BPM" } ?: state.status)
                Text("有实时心率时，读数和接收时间会随普通聊天消息发给 AstrBot 服务器。", style = MaterialTheme.typography.caption)
                if (state.active == null) {
                    state.selected?.let { device ->
                        TextButton(onClick = { state.start(device) }) { Text("重新连接 ${device.label}") }
                    }
                    state.devices.forEach { device ->
                        if (device.address != state.selected?.address || device.type != state.selected?.type) {
                            TextButton(onClick = { state.start(device) }) { Text(device.label) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (state.active != null) state.stop() else state.start(null)
            }) { Text(if (state.active != null) "停止" else "扫描设备") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("关闭面板") } }
    )
}
