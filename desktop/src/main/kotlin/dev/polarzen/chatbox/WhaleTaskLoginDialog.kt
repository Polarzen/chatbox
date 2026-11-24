package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun WhaleTaskLoginDialog(
    address: String,
    onDismiss: () -> Unit,
    login: suspend (String, String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录鲸鱼任务") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("目标实例　$address")
                OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("Dashboard 用户名") }, singleLine = true)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(totp, { value ->
                    if (value.length <= 6 && value.all { it in '0'..'9' }) totp = value
                }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("六位验证码（可选）") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
                failure?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!busy) {
                    busy = true
                    failure = null
                    scope.launch {
                        try {
                            login(username.trim(), password, totp)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            password = ""
                            totp = ""
                            val fallback = "登录失败，请检查实例连接、用户名、密码及验证码后重试。"
                            failure = if (error is WhaleTaskLoginRequiredException) fallback
                                else safeFailureMessage(error, fallback = fallback)
                        } finally {
                            busy = false
                        }
                    }
                }
            }, enabled = !busy && username.isNotBlank() && password.isNotEmpty() &&
                (totp.isEmpty() || totp.length == 6)) { Text(if (busy) "登录中…" else "登录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
