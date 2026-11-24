package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun DesktopProfilePanel(
    history: DesktopProfileHistory,
    name: String,
    sessionCount: Int,
    verifiedSource: String?,
    onOpenRoom: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<ProfileDevice?>(null) }
    val recovering = history.needsRecovery
    val showCodeForm = !history.bound || recovering
    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(history, showCodeForm) {
        if (!showCodeForm) {
            code = ""
            history.loadDevices()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("与手机共享此档案的会话") },
        text = {
            Column(Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("档案：$name")
                Text("此档案现有 ${sessionCount} 段会话。授权的手机可阅读这些会话及今后新增的会话，并在原会话中继续发送文字。")
                Text("旧附件显示类型提示。手机隐藏会话只影响该手机。在电脑删除后，下次同步会移出手机目录；共享服务按原房间规则保留记录。")
                if (showCodeForm) {
                    OutlinedTextField(address, { address = it }, enabled = !busy, singleLine = true,
                        label = { Text("共享服务地址") }, modifier = Modifier.fillMaxWidth().testTag("profile-share-address"))
                    OutlinedTextField(code, { code = it }, enabled = !busy, singleLine = true,
                        label = { Text(if (recovering) "档案主人恢复码" else "档案创建码或原档案主人恢复码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("profile-share-bootstrap"))
                    if (verifiedSource == null) Text("请先在连接设置中验证当前 AstrBot 档案。")
                } else {
                    Text("在手机打开「最近对话 → 来自电脑」，填写手机可访问的共享服务 HTTPS 地址及一次性邀请码。127.0.0.1 仅指当前设备。")
                    TextButton(enabled = !busy, onClick = { runAction { history.createInvite() } },
                        modifier = Modifier.testTag("profile-share-invite")) { Text("生成一次性邀请码") }
                    history.invite?.let { invite ->
                        SelectionContainer { Text("邀请码：${invite.code}（签发后 ${invite.expiresIn} 秒内有效）") }
                    }
                    TextButton(enabled = !busy, onClick = { runAction { history.loadDevices() } }) { Text("刷新设备列表") }
                    history.devices.forEach { device ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("${device.name} · ${device.id.takeLast(8)}", Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = { revokeTarget = device }) { Text("撤销授权") }
                        }
                    }
                    Text("撤销立即阻止新读取与发送。离线手机下次联网核对授权时会关闭入口并清除该档案缓存。")
                    if (onOpenRoom != null) TextButton(enabled = !busy, onClick = onOpenRoom) { Text("打开此会话原有的单会话共享") }
                }
                Text("电脑在线运行 Chatbox 时发布本地变化。读取未缓存的记录需要共享服务及其 HTTPS 入口在线；发送还需要原 AstrBot 实例在线。")
                if (history.notice.isNotBlank()) Text(history.notice)
                if (busy) Text("正在处理…")
            }
        },
        confirmButton = {
            if (showCodeForm) Button(enabled = !busy && verifiedSource != null && address.isNotBlank() && code.isNotBlank(),
                onClick = { verifiedSource?.let { source -> runAction {
                    if (recovering) history.recover(address.trim(), code.trim(), source, name)
                    else history.authorize(address.trim(), code.trim(), source, name)
                } } },
                modifier = Modifier.testTag("profile-share-confirm")) { Text(if (recovering) "恢复此档案主人授权" else "确认共享此档案") }
            else TextButton(enabled = !busy, onClick = { runAction { history.refresh(verifiedSource) } }) { Text("立即同步") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } },
    )
    revokeTarget?.let { device ->
        AlertDialog(onDismissRequest = { revokeTarget = null }, title = { Text("撤销设备授权？") },
            text = { Text("设备「${device.name} · ${device.id.takeLast(8)}」将无法继续访问此档案。") },
            confirmButton = { TextButton(onClick = {
                revokeTarget = null
                runAction { history.revoke(device.id) }
            }) { Text("确认撤销") } },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("取消") } })
    }
}
