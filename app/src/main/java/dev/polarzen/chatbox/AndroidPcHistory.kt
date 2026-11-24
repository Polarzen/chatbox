package dev.polarzen.chatbox

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.util.UUID

/** UI entry points run on the main dispatcher. HTTP suspends on IO. A request may return
 * after navigation or removal, so check the captured grant before each durable write. */
internal class AndroidPcHistory(private val cache: AndroidPcHistoryCache) {
    companion object {
        private const val WINDOW = 40
        private var instance: AndroidPcHistory? = null
        @Synchronized fun get(context: Context): AndroidPcHistory =
            instance ?: AndroidPcHistory(AndroidPcHistoryCache(context.applicationContext)).also { instance = it }
    }

    private class Grant(val scope: String, val binding: ProfileBinding)
    private val api = SharedProfileTransport(allowLoopback = false)
    private val requests = Mutex()
    private var grant by mutableStateOf<Grant?>(null)
    private var epoch = 0L
    private var sessionLimit = WINDOW
    private var turnLimit = WINDOW
    private val expandedWindows = mutableMapOf<String, Int>()
    private var actionMessage by mutableStateOf("")
    private var networkMessage by mutableStateOf("")

    val bound get() = grant != null
    val notice get() = listOf(actionMessage, networkMessage).filter(String::isNotEmpty).joinToString("\n")
    var profileName by mutableStateOf("")
        private set
    var syncComplete by mutableStateOf(false)
        private set
    var expected by mutableStateOf(0)
        private set
    var received by mutableStateOf(0)
        private set
    var issue by mutableStateOf("")
        private set
    var lastSync by mutableStateOf(0L)
        private set
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var syncing by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var showHidden by mutableStateOf(false)
        private set
    var sessions by mutableStateOf<List<ProfileSession>>(emptyList())
        private set
    var hasMoreSessions by mutableStateOf(false)
        private set
    var selected by mutableStateOf<ProfileSession?>(null)
        private set
    var turns by mutableStateOf<List<SharedTurn>>(emptyList())
        private set
    var hasOlder by mutableStateOf(false)
        private set
    var draft by mutableStateOf("")
        private set
    var pendingId by mutableStateOf<String?>(null)
        private set
    var canRetry by mutableStateOf(false)
        private set
    var cleanupRequired by mutableStateOf(false)
        private set

    init {
        try {
            cache.grant()?.let { (scope, binding) ->
                readDirectory(scope)
                grant = Grant(scope, binding)
            }
        } catch (_: Exception) {
            cleanupRequired = true
            actionMessage = "电脑档案的绑定或缓存无法安全打开。请清理本机档案数据后重新配对。"
        }
    }

    private inline fun local(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            actionMessage = "未能读写此手机的电脑历史缓存，请检查存储空间及本机密钥。"
        }
    }

    private fun readDirectory(scope: String) {
        val state = cache.directory(scope)
        profileName = state.name
        syncComplete = state.finished && state.published
        expected = state.expected
        received = state.received
        issue = state.issue
        lastSync = state.syncedAt
        readList(scope)
        selected?.let { row ->
            val current = cache.session(scope, row.sid)
            if (current == null) {
                expandedWindows.remove(row.sid)
                back()
            } else selected = current
        }
    }

    private fun readList(scope: String) {
        val page = cache.sessions(scope, query, showHidden, sessionLimit + 1)
        hasMoreSessions = page.size > sessionLimit
        sessions = page.take(sessionLimit)
    }

    fun search(value: String) {
        query = value
        sessionLimit = WINDOW
        local { grant?.let { readList(it.scope) } }
    }

    fun filterHidden(hidden: Boolean) {
        showHidden = hidden
        sessionLimit = WINDOW
        local { grant?.let { readList(it.scope) } }
    }

    fun moreSessions() {
        if (!hasMoreSessions) return
        sessionLimit += WINDOW
        local { grant?.let { readList(it.scope) } }
    }

    fun hide(sid: String, hidden: Boolean) = local {
        val scope = grant?.scope ?: return
        cache.hide(scope, sid, hidden)
        if (selected?.sid == sid) back()
        readList(scope)
    }

    private fun readComposer(scope: String, sid: String) {
        val composer = cache.composer(scope, sid)
        draft = composer.draft
        if (pendingId != composer.pending) canRetry = false
        pendingId = composer.pending
    }

    private fun readTurns(scope: String, sid: String) {
        val page = cache.turns(scope, sid, turnLimit + 1)
        hasOlder = page.size > turnLimit
        turns = page.takeLast(turnLimit)
        readComposer(scope, sid)
    }

    fun open(sid: String) = local {
        val scope = grant?.scope ?: return
        val row = cache.session(scope, sid) ?: return
        if (selected?.sid != sid) {
            back()
            selected = row
            turnLimit = expandedWindows[sid] ?: WINDOW
        }
        readTurns(scope, sid)
    }

    fun back() {
        selected = null
        turnLimit = WINDOW
        turns = emptyList()
        hasOlder = false
        draft = ""
        pendingId = null
        canRetry = false
        actionMessage = ""
    }

    fun older() = local {
        val scope = grant?.scope ?: return
        val sid = selected?.sid ?: return
        if (!hasOlder) return
        turnLimit += WINDOW
        readTurns(scope, sid)
        expandedWindows[sid] = turnLimit
    }

    fun editDraft(value: String) = local {
        val scope = grant?.scope ?: return
        val sid = selected?.sid ?: return
        if (pendingId != null) return
        cache.draft(scope, sid, value)
        draft = value
        actionMessage = ""
    }

    private fun clear(message: String) {
        epoch++
        grant = null
        back()
        expandedWindows.clear()
        profileName = ""
        expected = 0
        received = 0
        issue = ""
        lastSync = 0L
        syncComplete = false
        connected = false
        query = ""
        showHidden = false
        sessionLimit = WINDOW
        sessions = emptyList()
        hasMoreSessions = false
        networkMessage = ""
        actionMessage = message
        cleanupRequired = true
        try {
            cache.forget()
            cleanupRequired = false
        } catch (_: Exception) {
            actionMessage = "已关闭电脑档案入口。本机清理尚未完成，请重试清理后再配对。"
        }
    }

    fun forget() = clear("已清除本机的电脑档案绑定和缓存。")

    suspend fun join(address: String, code: String) {
        if (busy || bound || cleanupRequired) return
        val base = try {
            sharedAddress(address, allowLoopback = false)
        } catch (_: Exception) {
            actionMessage = "请输入手机可访问、证书受信任的完整 HTTPS 服务地址。"
            return
        }
        val start = epoch
        busy = true
        networkMessage = ""
        actionMessage = ""
        try {
            requests.withLock {
                if (start != epoch || bound || cleanupRequired) return
                val binding = api.join(base, code.trim())
                if (start != epoch) return
                val scope = cache.bind(binding)
                grant = Grant(scope, binding)
                readDirectory(scope)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (start == epoch) actionMessage = if (e is SharedHttpFailure && e.status in listOf(400, 401, 403, 404, 410))
                "配对失败，请检查档案邀请码及服务地址。邀请码可能已使用或过期。" else failure(e)
        } finally {
            busy = false
        }
        if (bound) refresh()
    }

    private fun failure(e: Exception): String = when {
        e is SQLiteException || e is GeneralSecurityException -> "读写电脑历史缓存失败，请检查本机存储和密钥。"
        e !is SharedHttpFailure -> "未能同步电脑历史，请检查网络、服务地址及 HTTPS 证书。"
        e.status == 409 -> "目录正在变化或会话忙，请稍后刷新。"
        e.status == 404 || e.status == 410 -> "共享服务找不到这段记录，请刷新目录。"
        else -> "共享服务返回 HTTP ${e.status}。"
    }

    private fun failed(g: Grant, e: Exception) {
        if (grant !== g) return
        if (e is SharedHttpFailure && e.status == 401) {
            clear("此设备的档案授权已撤销，已关闭入口并清理本机缓存。")
        } else {
            connected = false
            networkMessage = failure(e)
        }
    }

    private fun online() {
        connected = true
        networkMessage = ""
    }

    /** Revision and offset are committed with every page. An interrupted snapshot is
     * resumable, and only its final page can prune absent SIDs and their dependent rows. */
    private suspend fun synchronizeDirectory(g: Grant) {
        var offset = 0
        var revision: String? = null
        var restarted = false
        while (grant === g) {
            val page = try {
                api.sessions(g.binding, offset = offset, revision = revision)
            } catch (e: SharedHttpFailure) {
                if (offset == 0 || e.status != 409 || restarted) throw e
                offset = 0
                revision = null
                restarted = true
                continue
            }
            if (grant !== g) return
            online()
            val now = System.currentTimeMillis()
            if (offset == 0) {
                val previous = cache.directory(g.scope)
                if (previous.revision == page.revision) {
                    if (previous.finished) {
                        cache.touchDirectory(g.scope, page, now)
                        readDirectory(g.scope)
                        return
                    }
                    if (page.more && previous.received >= page.sessions.size) {
                        offset = previous.received
                        revision = page.revision
                        continue
                    }
                }
            } else {
                check(revision == page.revision)
            }
            cache.applyDirectory(g.scope, page, offset, now)
            readDirectory(g.scope)
            if (!page.more) return
            revision = page.revision
            offset += page.sessions.size
        }
    }

    private fun viewing(g: Grant, sid: String) = grant === g && selected?.sid == sid

    private suspend fun lookup(g: Grant, sid: String, id: String): Boolean {
        val row = try {
            api.turn(g.binding, sid, id)
        } catch (e: SharedHttpFailure) {
            if (e.status != 404) throw e
            if (viewing(g, sid) && pendingId == id) canRetry = true
            return false
        }
        if (grant === g) {
            cache.applyTurns(g.scope, sid, listOf(row))
            if (viewing(g, sid)) {
                canRetry = false
                readTurns(g.scope, sid)
            }
        }
        return true
    }

    private suspend fun synchronizeBody(g: Grant, session: ProfileSession) {
        val sid = session.sid
        pendingId?.let { lookup(g, sid, it) }
        if (!viewing(g, sid)) return
        var cursor = cache.cursor(g.scope, sid)
        // Provide a recent window immediately while older pages fill the durable prefix.
        val nearTail = session.imported.toLong() - WINDOW
        if (cursor == 0L && nearTail > WINDOW && turns.isEmpty()) {
            val preview = api.turns(g.binding, sid, nearTail, WINDOW)
            if (!viewing(g, sid)) return
            cache.applyTurns(g.scope, sid, preview.turns)
            readTurns(g.scope, sid)
        }
        var after = cursor
        var terminalPrefix = true
        do {
            val page = api.turns(g.binding, sid, after)
            if (!viewing(g, sid)) return
            for (turn in page.turns) {
                after = turn.seq
                if (turn.status == "running") terminalPrefix = false
                if (terminalPrefix) cursor = turn.seq
            }
            if (page.turns.isNotEmpty()) {
                cache.applyTurns(g.scope, sid, page.turns, cursor)
                readTurns(g.scope, sid)
            }
        } while (page.more)
    }

    suspend fun refresh() {
        val g = grant ?: return
        if (syncing) return
        syncing = true
        try {
            requests.withLock {
                if (grant !== g) return
                synchronizeDirectory(g)
                if (grant === g) selected?.let { synchronizeBody(g, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(g, e)
        } finally {
            syncing = false
        }
    }

    suspend fun send() = submit(retry = false)
    suspend fun retryPending() = submit(retry = true)

    private suspend fun submit(retry: Boolean) {
        val g = grant ?: return
        val session = selected ?: return
        val sid = session.sid
        val clickedId = pendingId
        if (busy || session.reason.isNotEmpty() || draft.isBlank()) return
        if (retry && (!canRetry || clickedId == null) || !retry && clickedId != null) return
        busy = true
        actionMessage = ""
        try {
            requests.withLock {
                if (!viewing(g, sid) || pendingId != clickedId || selected?.reason?.isNotEmpty() == true) return
                val question = draft
                if (question.isBlank()) return
                // An old pending operation needs an authoritative lookup and, if explicitly
                // retried, the service's answer for the SAME id before its draft can be released.
                if (!retry && pcQuestionTooLarge(question)) {
                    actionMessage = "提问超过 64 KiB（UTF-8），已保留草稿。请缩短后发送。"
                    return
                }
                if (question.trimStart().let { it == "/task" || it.startsWith("/task") && it.getOrNull(5)?.isWhitespace() == true }) {
                    actionMessage = "请在 Windows 的本机会话中选择 Launcher 实例后执行 /task。"
                    return
                }
                val id = if (retry) {
                    val existing = checkNotNull(clickedId)
                    if (lookup(g, sid, existing) || !viewing(g, sid)) return
                    existing
                } else {
                    UUID.randomUUID().toString().also {
                        cache.begin(g.scope, sid, it)
                        pendingId = it
                    }
                }
                canRetry = false
                val row = try {
                    api.send(g.binding, sid, id, question)
                } catch (e: SharedHttpFailure) {
                    if (grant !== g) return
                    if (pcDraftRejected(e.status, retriedAfterMissingLookup = retry)) {
                        cache.rejected(g.scope, sid, id)
                        if (viewing(g, sid)) {
                            readComposer(g.scope, sid)
                            actionMessage = when {
                                e.status == 413 -> "提问超过服务大小限制，已保留草稿。"
                                e.conflict == SharedConflict.RUNNING -> "另一台设备正在生成回复，已保留草稿。"
                                e.conflict == SharedConflict.UNRESOLVED -> "上一轮的结果未知，请先由服务主人核对 AstrBot。草稿已保留。"
                                else -> "服务拒绝此次发送（HTTP ${e.status}），已保留草稿。请刷新查看会话状态。"
                            }
                        }
                        return
                    }
                    throw e
                }
                if (grant !== g) return
                online()
                cache.applyTurns(g.scope, sid, listOf(row))
                if (viewing(g, sid)) readTurns(g.scope, sid)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(g, e)
            if (viewing(g, sid) && pendingId != null) actionMessage = "发送结果尚未确认，将按原发送编号核对。"
        } finally {
            busy = false
        }
    }
}

internal fun pcQuestionTooLarge(question: String): Boolean {
    val maxBytes = 64 * 1024
    return question.length > maxBytes || question.toByteArray(Charsets.UTF_8).size > maxBytes
}

internal fun pcDraftRejected(status: Int, retriedAfterMissingLookup: Boolean): Boolean =
    status == 413 || !retriedAfterMissingLookup && status in listOf(400, 403, 409, 422)
