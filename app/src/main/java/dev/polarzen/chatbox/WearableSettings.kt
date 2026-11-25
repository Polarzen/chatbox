package dev.polarzen.chatbox

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val metricLabels = mapOf("steps" to "步数", "sleep" to "睡眠", "exercise" to "运动", "heart_rate" to "运动期间心率")

@Composable
private fun WearableSwitch(label: String, checked: Boolean, enabled: Boolean = true,
                           change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Switch, change),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun WearableChoice(label: String, choices: List<Pair<String, String>>, selected: String,
                           enabled: Boolean, choose: (String) -> Unit) {
    val current = choices.firstOrNull { it.first == selected }
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(enabled = enabled && choices.isNotEmpty(), onClick = { expanded = true }) {
            Text(current?.second ?: if (selected.isEmpty()) "请选择" else "原选择已不可用")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; choose(id) })
            }
        }
    }
}

private fun syncTime(millis: Long): String = if (millis <= 0) "尚无成功同步" else
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))

private val readPermissions = metricLabels.keys.associateWith { WearableHealthSource.permissionFor(it) }

private fun statusLabel(code: String?) = when (code) {
    null, "" -> "尚未同步"
    "ok", "available" -> "已同步"
    "no_records", "no_data" -> "未读到记录"
    "no_permission" -> "未获读取权限"
    "source_unavailable" -> "来源不可用"
    "offline" -> "手机离线"
    "stale" -> "记录已过期"
    else -> code
}

@Composable
internal fun WearableSettingsScreen(device: AndroidDeviceSync, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sync = remember(context) { AndroidWearableSync.get(context.applicationContext) }
    val state by sync.state.collectAsState()
    val owner = remember(context) { AndroidAppState.get(context).history.user }
    val saved = state.settings
    var selected by rememberSaveable(saved.metrics) { mutableStateOf(ArrayList(saved.metrics)) }
    var source by rememberSaveable(saved) { mutableStateOf(saved.source) }
    var profile by rememberSaveable(saved) { mutableStateOf(saved.profile) }
    var sid by rememberSaveable(saved) { mutableStateOf(saved.sid) }
    var workouts by rememberSaveable(saved) { mutableStateOf(saved.metrics.isNotEmpty() && saved.exerciseEvents) }
    var mornings by rememberSaveable(saved) { mutableStateOf(saved.morningTask) }
    var notifyWorkout by rememberSaveable(saved) { mutableStateOf(saved.notifyWorkout) }
    var notifyMorning by rememberSaveable(saved) { mutableStateOf(saved.notifyMorning) }
    var time by rememberSaveable(saved) { mutableStateOf(saved.morningTime) }
    var window by rememberSaveable(saved) { mutableStateOf(saved.windowMinutes.toString()) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        note = ""
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { note = "未能确认操作结果。请检查健康数据共享、设备配对和电脑连接。" }
            finally { busy = false }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
            if (event == Lifecycle.Event.ON_STOP) credential = ""
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val health = remember(context) { WearableHealthSource(context) }
    val sdk = remember(health, revision) { health.sdkStatus }
    val available = sdk == HealthConnectClient.SDK_AVAILABLE
    val backgroundPermission = WearableHealthSource.backgroundPermission
    val backgroundSupported = remember(health, sdk) { runCatching { health.backgroundAvailable() }.getOrDefault(false) }
    var granted by remember { mutableStateOf(emptySet<String>()) }
    var requested by rememberSaveable { mutableStateOf("") }
    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        if (requested in readPermissions && readPermissions[requested] in result && requested !in selected)
            selected = ArrayList(selected + requested)
        else if (requested.isNotEmpty() && (readPermissions[requested] ?: requested) !in result)
            note = "未获此项权限。可以在系统健康数据共享设置中调整。"
        requested = ""
        revision++
    }
    fun request(key: String, values: Set<String>) {
        requested = key
        try { permissions.launch(values) }
        catch (_: Exception) { requested = ""; note = "无法打开系统授权页，请在系统健康数据共享设置中检查权限。" }
    }
    LaunchedEffect(health, revision) {
        granted = try { health.grantedPermissions() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { note = "无法读取系统健康权限。"; emptySet() }
    }
    val allowed = selected.filter { readPermissions[it] in granted }.toSet()
    var discovered by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var probing by remember { mutableStateOf(false) }
    LaunchedEffect(allowed, revision) {
        probing = allowed.isNotEmpty()
        val end = System.currentTimeMillis()
        val start = end - Duration.ofDays(30).toMillis()
        val results = mutableMapOf<String, Set<String>>()
        try {
            for (metric in allowed) {
                try { results[metric] = health.discoverSources(metric, start, end) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { note = "未能检查${metricLabels[metric]}来源，请检查系统授权。" }
            }
            discovered = results
        } finally { probing = false }
    }
    val rows = device.rows
    // Compose runtime 1.7.6 lint misses the assignment to the producer's value below.
    @Suppress("ProduceStateDoesNotAssignValue")
    val directory by produceState<Pair<String, Set<DeviceRoomKey>>?>(null, device.bound, device.address, rows) {
        if (!device.bound) { value = null; return@produceState }
        value = try {
            withContext(Dispatchers.IO) {
                AndroidDeviceStore(context).use { store ->
                    store.grant()?.takeIf { it.second }?.let { (binding, _) ->
                        binding.device to store.directory().filter { it.reason.isBlank() }
                            .mapTo(mutableSetOf()) { DeviceRoomKey(it.profile, it.sid) }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { note = "无法读取当前配对的个人档案与会话。"; null }
    }
    val profiles = device.profiles.filter { device.bound && it.available && it.username == owner &&
        it.originDevice == directory?.first }
    val sessions = rows.filter { it.profile == profile && it.username == owner && it.reason.isBlank() &&
        DeviceRoomKey(it.profile, it.sid) in directory?.second.orEmpty() }
    val workoutActive = workouts && "exercise" in allowed
    val morningActive = mornings && "sleep" in allowed
    val duration = window.toIntOrNull()
    val validTime = time.matches(Regex("([01][0-9]|2[0-3]):[0-5][0-9]"))
    DeviceForm("手环数据", onBack) {
        Text("Health Connect · 健康数据共享", style = MaterialTheme.typography.titleLarge)
        when {
            sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Text("请安装或更新系统健康数据共享组件。")
            !available -> Text("此设备当前无法访问健康数据共享。")
            else -> Text("先让小米运动健康同步手环，再选择指标并授予系统读取权限。")
        }
        Text("最近成功同步 · ${syncTime(state.lastSync)}")
        if (state.notice.isNotBlank()) Text(state.notice)
        if (note.isNotBlank()) Text(note)
        if (state.pending) Text("共享设置正在等待与电脑核对")
        if (!device.bound) Text("请先在设备设置中连接自己的电脑。")
        HorizontalDivider()
        Text("读取指标", style = MaterialTheme.typography.titleMedium)
        val editable = !busy && requested.isEmpty()
        for ((metric, label) in metricLabels) {
            val permission = readPermissions.getValue(metric)
            WearableSwitch(label, metric in selected && permission in granted,
                editable && available) { checked ->
                if (!checked) selected = ArrayList(selected - metric)
                else if (permission in granted) selected = ArrayList((selected + metric).distinct())
                else request(metric, health.permissionsFor(setOf(metric)))
            }
            val status = when {
                metric in saved.metrics && permission !in granted -> "未获读取权限"
                metric in discovered && discovered[metric].isNullOrEmpty() && metric in allowed ->
                    "最近 30 天内未读到此项小米运动健康记录"
                else -> statusLabel(state.statuses[metric])
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        if ("heart_rate" in selected && "exercise" !in selected) Text("查询运动期间心率还需要读取运动时段。")
        val distance = WearableHealthSource.distancePermission
        if ("exercise" in allowed && distance !in granted) {
            TextButton(enabled = editable, onClick = { request(distance, setOf(distance)) }) { Text("允许读取运动距离") }
        }
        if (backgroundSupported && backgroundPermission !in granted) {
            OutlinedButton(enabled = editable && selected.isNotEmpty(), onClick = {
                request(backgroundPermission, setOf(backgroundPermission))
            }) { Text("允许在后台读取") }
        } else if (available) {
            Text(if (backgroundSupported) "系统已允许后台读取" else "此系统支持在 Chatbox 打开时读取")
        }
        val metricSources = state.sources + discovered
        val observed = metricSources.filterKeys { it in allowed }.values.flatten().toSet()
        val origins = WearableHealthSource.miFitnessPackages.sortedByDescending { it in observed }
        WearableChoice("小米运动健康来源包名", origins.map { name ->
            name to if (name in observed) "$name · 已读到记录" else "$name · 尚未读到记录"
        }, source, editable) { source = it }
        if (probing) Text("正在检查最近 30 天的健康记录…")
        else if (allowed.isNotEmpty() && observed.isEmpty())
            Text("最近 30 天内尚未发现已授权指标的小米运动健康记录。可选择手机上对应的来源包名，等待后续同步。")
        for ((metric, packages) in metricSources.filterKeys { it in allowed }) {
            if (packages.isNotEmpty()) Text("${metricLabels[metric] ?: metric} · ${packages.sorted().joinToString("、")}",
                style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        WearableChoice("允许查询的个人档案", profiles.map { it.profile to "${it.name} · ${it.username}" },
            profile, editable) { if (profile != it) { profile = it; sid = "" } }
        if (device.bound && profiles.isEmpty())
            Text("请先在手机的“本机服务档案与密钥”中连接个人 AstrBot 档案，再让该档案同步到电脑。")
        HorizontalDivider()
        Text("后台任务", style = MaterialTheme.typography.titleMedium)
        WearableSwitch("处理新完成的运动", workoutActive, editable && "exercise" in allowed) { workouts = it }
        WearableSwitch("晨间查询昨晚睡眠", morningActive, editable && "sleep" in allowed) { mornings = it }
        if (morningActive) {
            OutlinedTextField(time, { time = it }, Modifier.fillMaxWidth(), singleLine = true,
                enabled = editable, label = { Text("本地时间（HH:mm）") }, isError = !validTime)
            Text("跟随手机时区 · ${ZoneId.systemDefault().id}")
            OutlinedTextField(window, { window = it }, Modifier.fillMaxWidth(), singleLine = true,
                enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("等待睡眠数据的分钟数（1–1440）") }, isError = duration !in 1..1440)
        }
        Text("个人会话提醒", style = MaterialTheme.typography.titleMedium)
        val sendWorkout = notifyWorkout && workoutActive
        val sendMorning = notifyMorning && morningActive
        val needsDestination = sendWorkout || sendMorning
        val destination = sessions.firstOrNull { it.sid == sid }?.sid.orEmpty()
        WearableSwitch("发送运动总结", sendWorkout, editable && workoutActive) { notifyWorkout = it }
        WearableSwitch("发送晨间摘要", sendMorning, editable && morningActive) { notifyMorning = it }
        val destinations = sessions.map { it.sid to "${it.title} · ${it.sid}" }
        WearableChoice(if (needsDestination) "接收提醒的个人会话" else "接收提醒的个人会话（可选）",
            if (needsDestination) destinations else listOf("" to "暂不指定") + destinations,
            sid, editable && profiles.any { it.profile == profile } && sessions.isNotEmpty()) { sid = it }
        if (!needsDestination) Text("可以留空。已开启的任务会静默查询。")
        else if (destination.isEmpty() && profile.isNotBlank()) Text(
            if (sessions.isEmpty()) "请先在此档案中完成一次个人对话，再返回选择接收提醒的会话。"
            else "请选择此档案中有效的个人会话以开启提醒。")
        val ready = editable && device.bound && allowed.isNotEmpty() && source in origins &&
            profiles.any { it.profile == profile } && (!needsDestination || destination.isNotEmpty()) &&
            (!morningActive || (validTime && duration in 1..1440))
        Button(enabled = ready, onClick = { perform {
            sync.save(WearableSettings(metrics = allowed, profile = profile, sid = destination,
                exerciseEvents = workoutActive, morningTask = morningActive,
                notifyWorkout = sendWorkout, notifyMorning = sendMorning,
                morningTime = if (validTime) time else saved.morningTime,
                windowMinutes = duration?.takeIf { it in 1..1440 } ?: saved.windowMinutes, source = source))
            note = "共享设置已保存在本机"
        } }) { Text("保存共享设置") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        HorizontalDivider()
        OutlinedButton(enabled = !busy && device.online && !state.pending && saved.metrics.isNotEmpty(),
            onClick = { confirmation = "issue" }) { Text("生成或更换 AstrBot 插件凭据") }
        OutlinedButton(enabled = !busy && saved.metrics.isNotEmpty(), onClick = { confirmation = "stop" }) {
            Text("停止共享")
        }
        TextButton(enabled = !busy && device.bound, onClick = { confirmation = "clear" }) {
            Text("清除已同步健康缓存")
        }
    }
    if (confirmation == "issue") {
        AlertDialog(onDismissRequest = { confirmation = "" },
            title = { Text("生成插件凭据？") },
            text = { Text("新凭据仅供已选择的档案使用。原插件凭据将立即失效。") },
            confirmButton = { TextButton(onClick = { confirmation = ""; perform { credential = sync.issueCredential() } }) {
                Text("生成凭据")
            } }, dismissButton = { TextButton(onClick = { confirmation = "" }) { Text("取消") } })
    } else if (confirmation.isNotEmpty()) {
        val stop = confirmation == "stop"
        AlertDialog(onDismissRequest = { confirmation = "" },
            title = { Text(if (stop) "停止共享手环数据？" else "清除已同步健康缓存？") },
            text = { Text(if (stop) "电脑收到撤销后，插件将失去读取权限，待执行的推送会停止。"
                else "将清除共享服务上的健康记录。已发送的聊天摘要仍保存在会话中。") },
            confirmButton = { TextButton(onClick = {
                confirmation = ""
                perform { if (stop) sync.stop() else sync.clearCache() }
            }) { Text(if (stop) "停止共享" else "清除缓存") } },
            dismissButton = { TextButton(onClick = { confirmation = "" }) { Text("取消") } })
    }
    if (credential.isNotEmpty()) {
        AlertDialog(onDismissRequest = { credential = "" }, title = { Text("配置 AstrBot 手环插件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("在 AstrBot 的 Chatbox 手环插件配置中填入以下两项。凭据仅在此处显示一次。")
                    SelectionContainer { Text("service_url\n${device.address}") }
                    SelectionContainer { Text("plugin_token\n$credential") }
                }
            },
            confirmButton = { TextButton(onClick = {
                val clip = ClipData.newPlainText("Chatbox 插件凭据", credential)
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                credential = ""
                note = "已复制插件凭据"
            }) { Text("复制凭据并关闭") } },
            dismissButton = { TextButton(onClick = { credential = "" }) { Text("关闭") } })
    }
}
