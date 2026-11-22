package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI
import java.security.MessageDigest

private val standardAstrBotKey = Regex("abk_[A-Za-z0-9_-]{43}")

/** Identifies the verified key without retaining or displaying its secret value. */
internal fun desktopKeyMarker(token: String): String =
    if (standardAstrBotKey.matches(token)) {
        token.take(12) + "…"
    } else {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val fingerprint = digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        "Fingerprint (SHA-256): $fingerprint"
    }

/** Builds the owner WebUI destination from a base already verified by the desktop connection flow. */
internal fun desktopKeyDetailsSettingsUri(verifiedBase: String): URI {
    endpoint(verifiedBase, allowLoopbackHttp = true)
    val base = URI(verifiedBase.trim())
    return URI(base.toASCIIString().trimEnd('/') + "/#/settings?section=settings-openapi")
}

@Composable
internal fun DesktopKeyDetails(marker: String, verifiedBase: String) {
    var showDetails by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }

    TextButton(onClick = { openError = null; showDetails = true }) {
        Text("密钥信息")
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("已验证的密钥") },
            text = {
                Column {
                    Text(marker)
                    Spacer(Modifier.height(8.dp))
                    Text("在 AstrBot WebUI 的 OpenAPI 密钥列表中，对照名称、密钥前缀、创建时间、状态和权限范围，确认当前密钥与旧密钥。")
                    openError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    openError = runCatching {
                        val target = desktopKeyDetailsSettingsUri(verifiedBase)
                        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            "此系统不支持打开浏览器。请在浏览器中打开 AstrBot WebUI。"
                        }
                        Desktop.getDesktop().browse(target)
                        null
                    }.getOrElse {
                        "无法打开 AstrBot WebUI。请检查服务地址和系统默认浏览器设置。"
                    }
                }) {
                    Text("打开 AstrBot WebUI")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = false }) { Text("关闭") }
            },
            modifier = Modifier.padding(8.dp),
        )
    }
}
