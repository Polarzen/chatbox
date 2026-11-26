package dev.polarzen.chatbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Keep the selected conversation current and rotate through earlier conversations of the verified profile. */
@Composable
internal fun rememberDesktopInbox(
    store: DesktopStore, target: DesktopSendTarget?, selected: SessionKey,
    mayReceive: (SessionKey) -> Boolean, onReceived: (ReplyDestination) -> Unit,
): String {
    var issue by remember(target?.profileId, target?.bindingGeneration, selected) { mutableStateOf("") }
    val eligible by rememberUpdatedState(mayReceive)
    val received by rememberUpdatedState(onReceived)
    LaunchedEffect(store, target?.profileId, target?.bindingGeneration, selected) {
        if (target == null || target.profileId != selected.profileId) return@LaunchedEffect
        val retryAfter = mutableMapOf<SessionKey, Long>()
        var offset = 0
        while (isActive) {
            val others = withContext(Dispatchers.IO) {
                store.sessions(target.profileId).asSequence()
                    .filter { it.id != selected.sessionId && it.entryCount > 0 }
                    .map { SessionKey(target.profileId, it.id) }.toList()
            }
            val batch = buildList {
                add(selected)
                repeat(minOf(2, others.size)) { add(others[(offset + it) % others.size]) }
            }
            if (others.isNotEmpty()) offset = (offset + minOf(2, others.size)) % others.size
            val cursors = batch.filter { eligible(it) && System.nanoTime() >= (retryAfter[it] ?: Long.MIN_VALUE) }
                .mapNotNull { key -> store.inboxCursor(key)?.let { key to it } }
            var more = false
            if (cursors.isNotEmpty()) {
                try {
                    // All requests start immediately after the same target proof. Launcher discovery
                    // can inspect Windows processes and listeners, so run it once, away from the EDT.
                    withContext(Dispatchers.IO) { target.checkTarget() }
                    coroutineScope {
                        for ((key, cursor) in cursors) launch {
                            try {
                                val page = fetchAstrBotInbox(target.address, target.token, store.username,
                                    key.sessionId, cursor, target.allowLoopbackHttp)
                                if (page == null) {
                                    if (key == selected) issue = inboxSetupNotice("unsupported", store.username)
                                    retryAfter[key] = System.nanoTime() + 60_000_000_000L
                                    return@launch
                                }
                                if (!eligible(key)) return@launch
                                if (key == selected) issue = ""
                                val destination = withContext(Dispatchers.IO) {
                                    currentCoroutineContext().ensureActive()
                                    store.appendInbox(key, cursor, page.messages)
                                }
                                if (destination != null) {
                                    received(destination)
                                    more = more || page.more
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                if (key == selected) issue = if (e is AstrBotInboxDenied)
                                    inboxSetupNotice("denied", store.username) else "主动消息暂未同步，连接恢复后会重试。"
                                retryAfter[key] = System.nanoTime() + 15_000_000_000L
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    issue = "主动收件暂时无法验证服务地址，请检查 AstrBot 连接。"
                    delay(15_000L)
                }
            }
            delay(if (more) 250L else 4_000L)
        }
    }
    return issue
}
