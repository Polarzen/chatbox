package dev.polarzen.chatbox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun AndroidHeartRatePanel(receiver: AndroidHeartRate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pendingAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val address = pendingAddress
        pendingAddress = null
        if (grants.isEmpty() || grants.values.any { !it }) receiver.permissionDenied()
        else if (address == null) receiver.startScan()
        else {
            val device = receiver.selected?.takeIf { it.address == address }
                ?: receiver.devices.find { it.address == address }
            if (device != null) receiver.connect(device)
        }
    }
    fun start(device: AndroidHeartDevice? = null) {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device == null ->
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(Manifest.permission.BLUETOOTH_CONNECT)
            device == null -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyList()
        }
        val missing = permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingAddress = device?.address
            launcher.launch(missing.toTypedArray())
        } else if (device == null) receiver.startScan() else receiver.connect(device)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手环心率") },
        text = {
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(receiver.bpm?.let { "$it BPM" } ?: receiver.status)
                Text("有实时读数时，聊天消息会自动将当前心率发送给 AstrBot。",
                    style = MaterialTheme.typography.bodySmall)
                if (!receiver.active) {
                    receiver.selected?.let { device ->
                        TextButton(onClick = { start(device) }) { Text("重新连接 ${device.label}") }
                    }
                    receiver.devices.filter { it.address != receiver.selected?.address }.forEach { device ->
                        TextButton(onClick = { start(device) }) { Text(device.label) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (receiver.active) receiver.stop() else start() }) {
                Text(if (receiver.active) "停止" else "扫描设备")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭面板") } },
    )
}
