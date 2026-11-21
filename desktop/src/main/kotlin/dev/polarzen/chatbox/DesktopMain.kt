package dev.polarzen.chatbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

internal data class Entry(val question: String, val answer: String, val status: String)
internal data class Chat(val session: String, val entries: List<Entry> = emptyList())
internal class DesktopStore(private val file: Path) {
    private val state: JSONObject = if (Files.exists(file)) JSONObject(Files.readString(file)) else JSONObject()
    val username: String = state.optString("username").ifBlank {
        "chatbox_" + UUID.randomUUID().toString().replace("-", "")
    }
    val lastServer: String get() = state.optString("lastServer")
    fun read(server: String): Chat {
        val row = state.optJSONObject("servers")?.optJSONObject(server)
        val session = row?.optString("session").orEmpty().ifBlank { UUID.randomUUID().toString() }
        val entries = row?.optJSONArray("entries") ?: JSONArray()
        return Chat(session, (0 until entries.length()).map { i ->
            entries.getJSONObject(i).let {
                val status = it.getString("status").let { s -> if (s == "发送中") "上次回复未确认" else s }
                Entry(it.getString("question"), it.getString("answer"), status)
            }
        })
    }
    fun save(server: String, chat: Chat) {
        val copy = JSONObject(state.toString())
        val servers = copy.optJSONObject("servers") ?: JSONObject()
        val entries = JSONArray()
        chat.entries.forEach { entries.put(JSONObject().put("question", it.question).put("answer", it.answer).put("status", it.status)) }
        servers.put(server, JSONObject().put("session", chat.session).put("entries", entries))
        copy.put("username", username).put("lastServer", server).put("servers", servers)
        val dir = file.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "history-", ".tmp")
        try {
            Files.writeString(tmp, copy.toString())
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(tmp) }
        copy.keySet().forEach { state.put(it, copy.get(it)) }
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--smoke-test") {
        require(args.size <= 2)
        val directory = Files.createTempDirectory("chatbox-smoke-")
        val file = directory.resolve("history.json")
        try {
            val store = DesktopStore(file)
            val server = "https://example.invalid"
            val chat = Chat(store.read(server).session, listOf(Entry("你好", "世界", "已完成")))
            store.save(server, chat)
            val restored = DesktopStore(file)
            check(restored.username == store.username && restored.read(server) == chat)
            val output = mutableListOf<String>()
            readSse("data: hello\n\ndata: world\n\n".reader().buffered()) { output += it; true }
            check(output == listOf("hello", "world"))
            args.getOrNull(1)?.let { Files.writeString(Path.of(it), "chatbox-smoke-ok") }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
        return
    }
    val dataDir = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "Chatbox") }
        ?: Path.of(System.getProperty("user.home"), ".chatbox")
    val store = runCatching { DesktopStore(dataDir.resolve("history.json")) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Chatbox · 小鲸鱼") {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    store.fold({ Screen(it) }, { Text("无法读取本地聊天历史。请先备份历史文件，再检查数据目录。", Modifier.padding(16.dp)) })
                }
            }
        }
    }
}

@Composable private fun Screen(store: DesktopStore) {
    var base by remember { mutableStateOf(store.lastServer) }
    var key by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf(store.read(base)) }
    var busy by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf<String?>(null) }
    val scroll = rememberLazyListState()
    LaunchedEffect(chat.entries.size, busy) {
        if (chat.entries.isNotEmpty()) scroll.scrollToItem(chat.entries.lastIndex)
    }
    fun send(question: String) {
        val query = question.trim()
        val server = base.trim().trimEnd('/')
        val token = key.trim()
        if (query.isEmpty() || busy) return
        if (runCatching { endpoint(server, allowLoopbackHttp = true) }.isFailure || token.isEmpty() || token.any { it.isWhitespace() }) {
            note = "请填写 HTTPS 地址，或本机 http://127.0.0.1 地址，并输入聊天 API 密钥。"
            return
        }
        val pending = chat.copy(entries = chat.entries + Entry(query, "", "发送中"))
        if (runCatching { store.save(server, pending) }.isFailure) {
            note = "无法保存本地会话，消息未发送。"; return
        }
        chat = pending; draft = ""; busy = true; note = ""; partial = ""
        thread(isDaemon = true, name = "chatbox-request") {
            var answer = ""
            val result = runCatching {
                sendToAstrBot(server, token, store.username, pending.session, query, allowLoopbackHttp = true) { delta ->
                    answer = delta
                    SwingUtilities.invokeLater { partial = delta }
                }
            }
            SwingUtilities.invokeLater {
                val completed = pending.copy(entries = pending.entries.dropLast(1) +
                    Entry(query, answer, if (result.isSuccess) "已完成" else "回复状态未确认"))
                val saved = runCatching { store.save(server, completed) }.isSuccess
                chat = completed; busy = false; partial = ""
                note = when {
                    !saved -> "本地保存失败，请勿直接重发。"
                    result.exceptionOrNull() is IllegalStateException -> result.exceptionOrNull()?.message ?: "回复未确认。"
                    result.isFailure -> "连接或解析失败。请检查网络、证书和服务版本；服务可能已收到消息。"
                    answer.isEmpty() -> "回复已结束，但没有可显示的文字。"
                    else -> ""
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chatbox · 小鲸鱼", style = MaterialTheme.typography.h5)
        OutlinedTextField(base, {
            val previous = base.trim().trimEnd('/')
            val next = it.trim().trimEnd('/')
            base = it
            if (previous != next) { key = ""; chat = store.read(next); note = "" }
        }, Modifier.fillMaxWidth(),
            enabled = !busy, singleLine = true, label = { Text("AstrBot 地址（HTTPS 或 127.0.0.1 HTTP）") })
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
            label = { Text("对话 API 密钥 · 不保存") }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chat.entries) { item ->
                Text("你：${item.question}")
                val answer = if (busy && item === chat.entries.lastOrNull()) partial else item.answer
                if (answer.isNotBlank()) Text("助手：$answer")
                if (item.status != "已完成") Text(item.status, style = MaterialTheme.typography.caption)
                if (!busy && item === chat.entries.lastOrNull() && item.status != "已完成") {
                    TextButton(onClick = { retry = item.question }) { Text("重新发送上一条") }
                }
            }
        }
        if (note.isNotBlank()) Text(note, color = MaterialTheme.colors.error)
        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), enabled = !busy, maxLines = 4,
            label = { Text("输入消息") })
        Button(onClick = { send(draft) }, enabled = !busy && draft.isNotBlank()) {
            Text(if (busy) "等待回复…" else "发送")
        }
        retry?.let { question ->
            AlertDialog(onDismissRequest = { retry = null }, title = { Text("重新发送？") },
                text = { Text("上一条可能已到达 AstrBot，继续可能重复执行。") },
                confirmButton = { TextButton(onClick = { retry = null; send(question) }) { Text("确认重发") } },
                dismissButton = { TextButton(onClick = { retry = null }) { Text("取消") } })
        }
    }
}
