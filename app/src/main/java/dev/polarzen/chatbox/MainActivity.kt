package dev.polarzen.chatbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private companion object { val transcriptLock = Any() }
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("personal_chat", MODE_PRIVATE) }
    private val user by lazy {
        prefs.getString("user", null) ?: ("chatbox_" + UUID.randomUUID().toString().replace("-", "")).also {
            prefs.edit().putString("user", it).commit()
        }
    }
    private data class Line(val question: String, val answer: String, val state: String)
    private data class Transcript(val session: String, val lines: List<Line>)
    private fun read(base: String): Transcript {
        val entry = runCatching { JSONObject(prefs.getString("chat:$base", "{}") ?: "{}") }.getOrElse { JSONObject() }
        val rows = entry.optJSONArray("lines") ?: JSONArray()
        return Transcript(entry.optString("session").ifBlank { UUID.randomUUID().toString() },
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map {
                val state = it.optString("state")
                Line(it.optString("question"), it.optString("answer"), if (state == "发送中") "上次回复未确认" else state)
            })
    }
    private fun encode(session: String, lines: List<Line>): String {
        val rows = JSONArray()
        lines.forEach { rows.put(JSONObject().put("question", it.question).put("answer", it.answer).put("state", it.state)) }
        return JSONObject().put("session", session).put("lines", rows).toString()
    }
    private fun write(base: String, session: String, lines: List<Line>, expected: String? = null): Boolean =
        synchronized(transcriptLock) {
            if (expected != null && prefs.getString("chat:$base", null) != expected) return@synchronized false
            val edit = prefs.edit().putString("chat:$base", encode(session, lines))
            if (expected == null) edit.putString("base", base)
            edit.commit()
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }
    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
    @Composable private fun Screen() {
        var base by remember { mutableStateOf(prefs.getString("base", "") ?: "") }
        var key by remember { mutableStateOf("") }
        var draft by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
        var partial by remember { mutableStateOf("") }
        var transcript by remember { mutableStateOf(read(base)) }
        var askRetry by remember { mutableStateOf<String?>(null) }
        val scroll = rememberLazyListState()
        LaunchedEffect(transcript.lines.size, partial.length) {
            val index = transcript.lines.lastIndex
            if (index >= 0) scroll.scrollToItem(index)
        }
        fun send(message: String) {
            val clean = message.trim()
            if (busy || clean.isEmpty()) return
            val server = base.trim().trimEnd('/')
            val token = key.trim()
            if (token.isEmpty() || token.any { it.isWhitespace() }) {
                note = "请输入有效的聊天 API 密钥。"; return
            }
            if (runCatching { endpoint(server) }.isFailure) {
                note = "请填写 AstrBot 的 HTTPS 服务地址，不要包含登录信息、查询参数或片段。"; return
            }
            val session = transcript.session
            val rows = transcript.lines + Line(clean, "", "发送中")
            if (!write(server, session, rows)) {
                note = "无法保存本地会话，消息未发送。"; return
            }
            transcript = Transcript(session, rows)
            draft = ""; note = ""; partial = ""; busy = true
            executor.execute {
                val answer = StringBuilder()
                val failure = runCatching {
                    sendToAstrBot(server, token, user, session, clean) { chunk ->
                        answer.clear()
                        answer.append(chunk)
                        val snapshot = answer.toString()
                        runOnUiThread { if (!isDestroyed) partial = snapshot }
                    }
                }.exceptionOrNull()
                val result = rows.dropLast(1) + Line(clean, answer.toString(),
                    if (failure == null) "已完成" else "回复状态未确认")
                val saved = write(server, session, result, expected = encode(session, rows))
                runOnUiThread {
                    if (!isDestroyed) {
                        transcript = Transcript(session, result)
                        busy = false
                        partial = ""
                        note = when {
                            !saved -> "本地保存失败；请勿直接重复发送。"
                            failure is IllegalStateException -> failure.message ?: "回复未确认。"
                            failure != null -> "连接或解析失败。请检查网络、证书和服务版本；服务器可能已收到消息。"
                            answer.isEmpty() -> "回复已结束，但没有可显示的文字。"
                            else -> ""
                        }
                    }
                }
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chatbox · 小鲸鱼", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(base, {
                    base = it; transcript = read(it.trim().trimEnd('/')); note = ""
                }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text("AstrBot HTTPS 地址") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    singleLine = true, label = { Text("对话 API 密钥 · 不保存") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(transcript.lines) { row ->
                        Text("你：${row.question}")
                        val active = busy && row === transcript.lines.lastOrNull()
                        val reply = if (active) partial else row.answer
                        if (reply.isNotBlank()) Text("助手：$reply")
                        if (row.state != "已完成") Text(row.state, style = MaterialTheme.typography.labelMedium)
                        if (!busy && row === transcript.lines.lastOrNull() && row.state != "已完成") {
                            TextButton(onClick = { askRetry = row.question }) { Text("重新发送上一条") }
                        }
                    }
                }
                if (note.isNotBlank()) Text(note, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    maxLines = 3, label = { Text("输入消息") })
                Button(onClick = { send(draft) }, enabled = !busy && draft.isNotBlank()) {
                    Text(if (busy) "等待回复…" else "发送")
                }
            }
        }
        askRetry?.let { previous ->
            AlertDialog(onDismissRequest = { askRetry = null }, title = { Text("重新发送？") },
                text = { Text("上一条可能已经被 AstrBot 接收。继续会发送一条新消息，可能重复执行。") },
                confirmButton = { TextButton(onClick = { askRetry = null; send(previous) }) { Text("确认重发") } },
                dismissButton = { TextButton(onClick = { askRetry = null }) { Text("取消") } })
        }
    }
}
