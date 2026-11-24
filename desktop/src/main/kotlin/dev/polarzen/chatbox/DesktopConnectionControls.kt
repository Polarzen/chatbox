package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.Desktop

/** Compact connection, named-key, and settings controls intended for the application header. */
@Composable
internal fun DesktopConnectionControls(connection: DesktopConnection) {
    var keyMenuExpanded by remember { mutableStateOf(false) }
    var switchingKeyId by remember { mutableStateOf<String?>(null) }
    var switchingProfileId by remember { mutableStateOf(connection.profileId) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var manageExpanded by remember { mutableStateOf(false) }
    var permissionTarget by remember { mutableStateOf<NamedKey?>(null) }
    var pendingRename by remember { mutableStateOf<NamedKey?>(null) }
    var pendingRemoval by remember { mutableStateOf<NamedKey?>(null) }

    val activeKey = connection.keyList.keys.firstOrNull { it.id == connection.keyList.activeId }
    val statusColor = when {
        connection.verifying -> MaterialTheme.colors.primary
        connection.connected -> desktopPalette.success
        else -> MaterialTheme.colors.error
    }
    val statusText = when {
        connection.verifying -> "验证中"
        connection.connected -> "已连接"
        else -> "未连接"
    }

    LaunchedEffect(connection.profileId) {
        switchingKeyId = null
        switchingProfileId = connection.profileId
        keyMenuExpanded = false
    }
    LaunchedEffect(
        connection.profileId,
        connection.keyList.activeId,
        connection.pendingKeyId,
        connection.keyErrors,
        connection.verifying,
        connection.connected,
    ) {
        val candidateId = switchingKeyId ?: return@LaunchedEffect
        if (switchingProfileId != connection.profileId) {
            switchingKeyId = null
            keyMenuExpanded = false
            return@LaunchedEffect
        }
        when {
            !connection.keyErrors[candidateId].isNullOrBlank() && !connection.verifying -> {
                // Keep the menu in its current open/closed state; the specific error is now shown
                // beside this candidate. In particular, an outside dismissal is never undone.
                switchingKeyId = null
            }
            connection.connected && connection.keyList.activeId == candidateId &&
                connection.pendingKeyId != candidateId && !connection.verifying -> {
                keyMenuExpanded = false
                switchingKeyId = null
            }
        }
    }

    BoxWithConstraints(Modifier.wrapContentWidth()) {
        val narrowHeader = maxWidth < 440.dp
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .testTag("connection-control-status")
                    .semantics { contentDescription = "连接状态：$statusText；打开连接设置" }
                    .clickable { settingsExpanded = true },
                color = statusColor.copy(alpha = 0.10f),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    if (narrowHeader) when {
                        connection.verifying -> "验证"
                        connection.connected -> "在线"
                        else -> "未连"
                    } else statusText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    color = statusColor,
                    style = MaterialTheme.typography.caption,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(2.dp))
            TextButton(
                onClick = { keyMenuExpanded = true },
                modifier = Modifier.testTag("connection-control-key-button"),
            ) {
                Text("🔑", modifier = Modifier.semantics { contentDescription = "密钥" })
                Spacer(Modifier.width(4.dp))
                Text(
                    activeKey?.name?.takeIf(String::isNotBlank)
                        ?: if (connection.keyList.keys.isEmpty()) "添加密钥" else "选择密钥",
                    modifier = Modifier.widthIn(max = if (narrowHeader) 104.dp else 190.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.button,
                )
                Spacer(Modifier.width(3.dp))
                Text("▾", modifier = Modifier.semantics { contentDescription = "展开密钥菜单" })
            }
            Spacer(Modifier.width(2.dp))
            DropdownMenu(
                expanded = keyMenuExpanded,
                onDismissRequest = { keyMenuExpanded = false },
                modifier = Modifier.testTag("connection-control-key-menu"),
            ) {
            if (connection.keyList.keys.isEmpty()) {
                DropdownMenuItem(onClick = {
                    keyMenuExpanded = false
                    val selectedProfile = connection.profiles.firstOrNull { it.id == connection.profileId }
                    if (connection.profileId.isBlank() || selectedProfile == null || selectedProfile.offlineOnly) {
                        settingsExpanded = true
                    } else {
                        manageExpanded = true
                    }
                }) {
                    val selectedProfile = connection.profiles.firstOrNull { it.id == connection.profileId }
                    val canAddForSelection = connection.profileId.isNotBlank() && selectedProfile != null && !selectedProfile.offlineOnly
                    Text(
                        if (canAddForSelection) "添加密钥" else "选择服务以添加密钥",
                        modifier = Modifier.testTag("connection-control-add-key"),
                    )
                }
            } else {
                connection.keyList.keys.forEach { item ->
                    val isActive = item.id == connection.keyList.activeId
                    val isPending = item.id == connection.pendingKeyId
                    val keyError = connection.keyErrors[item.id]?.takeIf(String::isNotBlank)
                    val hasError = keyError != null
                    DropdownMenuItem(
                        onClick = {
                            when {
                                hasError && !connection.verifying -> {
                                    switchingProfileId = connection.profileId
                                    switchingKeyId = item.id
                                    connection.selectKey(item.id)
                                }
                                !isActive && !isPending -> {
                                    switchingProfileId = connection.profileId
                                    switchingKeyId = item.id
                                    connection.selectKey(item.id)
                                }
                                isPending && connection.verifying -> Unit
                                else -> keyMenuExpanded = false
                            }
                        },
                        modifier = Modifier.testTag("connection-control-key-${item.id}"),
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.name.ifBlank { "未命名密钥" },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isActive) Text("✓", color = MaterialTheme.colors.primary)
                            }
                            when {
                                hasError -> {
                                    Text(keyError!!, color = MaterialTheme.colors.error,
                                        style = MaterialTheme.typography.caption)
                                    if (isActive) Text("当前使用", color = MaterialTheme.colors.primary,
                                        style = MaterialTheme.typography.caption)
                                }
                                isPending -> Text("切换中…", style = MaterialTheme.typography.caption)
                                isActive -> Text("当前使用", color = MaterialTheme.colors.primary,
                                    style = MaterialTheme.typography.caption)
                            }
                        }
                    }
                }
            }
            DropdownMenuItem(onClick = {
                keyMenuExpanded = false
                manageExpanded = true
            }, modifier = Modifier.testTag("connection-control-manage-keys")) {
                Text("管理密钥…")
            }
            }
        }
    }

    DesktopModalGuard(
        settingsExpanded || manageExpanded || permissionTarget != null || pendingRename != null || pendingRemoval != null,
    )
    if (settingsExpanded) {
        ConnectionSettingsDialog(
            connection = connection,
            onDismiss = { settingsExpanded = false },
            onShowPermissions = {
                permissionTarget = connection.keyList.keys.firstOrNull { it.id == connection.keyList.activeId }
            },
        )
    }
    permissionTarget?.let { item ->
        OwnerPermissionsDialog(connection, item) { permissionTarget = null }
    }
    if (manageExpanded) {
        KeyManagementDialog(
            connection = connection,
            onDismiss = { manageExpanded = false },
            onRename = { pendingRename = it },
            onRemove = { pendingRemoval = it },
            onPermissions = { permissionTarget = it },
        )
    }
    pendingRename?.let { item ->
        RenameKeyDialog(connection, item, onDismiss = { pendingRename = null })
    }
    pendingRemoval?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("删除密钥？") },
            text = { Text("要删除“${item.name.ifBlank { "未命名密钥" }}”吗？此操作会从本机移除它。") },
            confirmButton = {
                TextButton(onClick = {
                    connection.removeKey(item.id)
                    pendingRemoval = null
                }, modifier = Modifier.testTag("connection-control-confirm-remove-key")) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("取消") } },
            modifier = Modifier.testTag("connection-control-remove-key-confirmation"),
        )
    }
}

@Composable
private fun KeyManagementDialog(
    connection: DesktopConnection,
    onDismiss: () -> Unit,
    onRename: (NamedKey) -> Unit,
    onRemove: (NamedKey) -> Unit,
    onPermissions: (NamedKey) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var newSecret by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf(false) }
    val normalizedName = newName.trim()
    val duplicateName = normalizedName.isNotEmpty() && connection.keyList.keys.any {
        it.name.trim().equals(normalizedName, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("密钥管理") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (connection.keyList.keys.isEmpty()) {
                    Text("还没有保存的密钥。添加后可为每把密钥设置一个仅保存在本机的名称。")
                }
                connection.keyList.keys.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name.ifBlank { "未命名密钥" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.id == connection.keyList.activeId) {
                                Text("当前使用", color = MaterialTheme.colors.primary,
                                    style = MaterialTheme.typography.caption)
                            }
                        }
                        TextButton(onClick = { onRename(item) }, modifier = Modifier.testTag("connection-control-rename-${item.id}")) {
                            Text("重命名")
                        }
                        TextButton(onClick = { onPermissions(item) },
                            modifier = Modifier.testTag("connection-control-permissions-${item.id}")) {
                            Text("权限")
                        }
                        TextButton(onClick = { onRemove(item) }, modifier = Modifier.testTag("connection-control-remove-${item.id}")) {
                            Text("删除")
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("添加密钥", style = MaterialTheme.typography.subtitle1)
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; actionError = false },
                    modifier = Modifier.fillMaxWidth().testTag("connection-control-add-name"),
                    singleLine = true,
                    label = { Text("名称") },
                    isError = duplicateName,
                )
                if (duplicateName) Text("此名称已使用，请换一个名称。", color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption)
                OutlinedTextField(
                    value = newSecret,
                    onValueChange = { newSecret = it; actionError = false },
                    modifier = Modifier.fillMaxWidth().testTag("connection-control-add-secret"),
                    singleLine = true,
                    label = { Text("API 密钥") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                if (actionError) Text("未能保存密钥，请检查输入后重试。", color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption)
                if (connection.error != null) Text("上次操作未完成。", color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        connection.addKey(normalizedName, newSecret)
                        newSecret = ""
                        newName = ""
                        actionError = false
                    } catch (_: Exception) {
                        newSecret = ""
                        actionError = true
                    }
                },
                enabled = normalizedName.isNotEmpty() && !duplicateName && newSecret.isNotBlank(),
                modifier = Modifier.testTag("connection-control-add-submit"),
            ) { Text("保存密钥") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        modifier = Modifier.testTag("connection-control-key-management"),
    )
}

@Composable
private fun RenameKeyDialog(connection: DesktopConnection, item: NamedKey, onDismiss: () -> Unit) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    var actionError by remember { mutableStateOf(false) }
    val normalizedName = name.trim()
    val duplicateName = normalizedName.isNotEmpty() && connection.keyList.keys.any {
        it.id != item.id && it.name.trim().equals(normalizedName, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名密钥") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; actionError = false },
                    modifier = Modifier.fillMaxWidth().testTag("connection-control-rename-name"),
                    singleLine = true,
                    label = { Text("名称") },
                    isError = duplicateName,
                )
                if (duplicateName) Text("此名称已使用，请换一个名称。", color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption)
                if (actionError) Text("未能重命名密钥，请重试。", color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    connection.renameKey(item.id, normalizedName)
                    onDismiss()
                } catch (_: Exception) {
                    actionError = true
                }
            }, enabled = normalizedName.isNotEmpty() && !duplicateName,
                modifier = Modifier.testTag("connection-control-confirm-rename")) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        modifier = Modifier.testTag("connection-control-rename-dialog"),
    )
}

@Composable
private fun ConnectionSettingsDialog(
    connection: DesktopConnection,
    onDismiss: () -> Unit,
    onShowPermissions: () -> Unit,
) {
    var addressDraft by remember(connection.profileId, connection.address) { mutableStateOf(connection.address) }
    var localError by remember { mutableStateOf(false) }
    val selectedProfile = connection.profiles.firstOrNull { it.id == connection.profileId }
    val addressCaption = MaterialTheme.typography.caption.copy(
        color = desktopPalette.accent,
        textDecoration = TextDecoration.Underline,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接设置") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                Text(
                    when {
                        connection.verifying -> "正在验证连接…"
                        connection.connected -> "连接已验证"
                        else -> "未连接"
                    },
                    color = if (connection.connected) desktopPalette.success else MaterialTheme.colors.onSurface,
                    style = MaterialTheme.typography.subtitle1,
                )
                if (connection.error != null) {
                    Text("上次操作未完成，请检查地址和密钥后重试。", color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption)
                }
                if (connection.profiles.isEmpty() && connection.address.isBlank() && connection.instances.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("首次连接：选择运行中的 AstrBot Launcher 实例，或输入服务地址，然后选择“应用地址”并验证。")
                }

                if (connection.profiles.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("已保存的配置", style = MaterialTheme.typography.subtitle1)
                    connection.profiles.forEach { profile ->
                        val label = profileDisplayName(profile)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = profile.id == connection.profileId,
                                onClick = { connection.selectProfile(profile.id, profile.address) },
                            )
                            Column {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (profile.address.isNotBlank()) Text(profile.address, style = addressCaption,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                if (connection.instances.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("运行中的 Launcher 实例", style = MaterialTheme.typography.subtitle1)
                    connection.instances.forEach { instance ->
                        val id = "launcher:${instance.identity}"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = connection.profileId == id,
                                onClick = { connection.selectProfile(id, instance.url) },
                            )
                            Column {
                                Text("AstrBot Launcher · ${instance.identity.take(8)}")
                                Text(instance.url, style = addressCaption)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("手动服务地址", style = MaterialTheme.typography.subtitle1)
                OutlinedTextField(
                    value = addressDraft,
                    onValueChange = { addressDraft = it; localError = false },
                    modifier = Modifier.fillMaxWidth().testTag("connection-control-address"),
                    singleLine = true,
                    label = { Text("HTTPS 或本机 127.0.0.1 HTTP") },
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        try {
                            connection.useAddress(addressDraft.trim())
                            localError = false
                        } catch (_: Exception) {
                            localError = true
                        }
                    }, enabled = addressDraft.isNotBlank(), modifier = Modifier.testTag("connection-control-use-address")) {
                        Text("应用地址")
                    }
                }
                if (localError) Text("地址无效，请输入完整的 HTTPS 地址或受支持的本机地址。", color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption)
                if (selectedProfile?.offlineOnly == true) {
                    Text("当前配置只包含离线历史。选择运行中的服务或应用新的地址以连接。",
                        style = MaterialTheme.typography.caption)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onShowPermissions,
                    enabled = connection.connected,
                    modifier = Modifier.testTag("connection-control-owner-permissions"),
                ) { Text("已验证能力与密钥权限…") }
            }
        },
        confirmButton = {
            Button(
                onClick = { connection.verify(); localError = false },
                enabled = !connection.verifying,
                modifier = Modifier.testTag("connection-control-verify"),
            ) { Text(if (connection.verifying) "验证中…" else "验证连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        modifier = Modifier.testTag("connection-control-settings-dialog"),
    )
}

@Composable
private fun OwnerPermissionsDialog(connection: DesktopConnection, item: NamedKey, onDismiss: () -> Unit) {
    var openError by remember { mutableStateOf(false) }
    val verifiedForItem = connection.connected && connection.keyList.activeId == item.id
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已验证能力与密钥权限") },
        text = {
            Column {
                Text("已验证能力")
                if (verifiedForItem) {
                    Text("此密钥已通过聊天 API 验证：Chatbox 对话身份的 AstrBot 会话列表可读取。")
                } else {
                    Text("此密钥尚未在当前连接中通过验证；聊天 API 能力未知。")
                }
                Text("本次不会调用文件或管理员接口，因此无法判断该密钥是否具有相应权限。")
                Spacer(Modifier.height(10.dp))
                Text("本机备注：${item.name.ifBlank { "未命名密钥" }}")
                Text("这是你为本机保存的名称，与服务端显示的名称和权限没有对应关系。")
                if (!connection.connected) {
                    Spacer(Modifier.height(8.dp))
                    Text("先验证当前服务后即可从 WebUI 查看此服务的密钥列表。",
                        style = MaterialTheme.typography.caption)
                }
                if (openError) {
                    Spacer(Modifier.height(8.dp))
                    Text("无法打开 AstrBot WebUI。请检查服务地址和默认浏览器。",
                        color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    openError = !openOwnerKeySettings(connection.address)
                },
                enabled = connection.connected,
                modifier = Modifier.testTag("connection-control-owner-open-webui"),
            ) { Text("打开 AstrBot WebUI") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        modifier = Modifier.testTag("connection-control-owner-permissions-dialog"),
    )
}

private fun profileDisplayName(profile: Profile): String = when {
    profile.offlineOnly -> "离线历史"
    profile.kind == "launcher" -> "AstrBot Launcher · ${profile.id.removePrefix("launcher:").take(8)}"
    else -> profile.address.ifBlank { "服务配置" }
}

private fun openOwnerKeySettings(address: String): Boolean = runCatching {
    val target = desktopKeyDetailsSettingsUri(address)
    check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
    Desktop.getDesktop().browse(target)
}.isSuccess
