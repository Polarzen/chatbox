package dev.polarzen.chatbox

import java.util.concurrent.CancellationException

internal enum class HistorySide { TITLE, USER, ASSISTANT }

internal data class HistoryHit(
    val profileId: String,
    val sessionId: String,
    val sessionTitle: String,
    val entryIndex: Int,
    val side: HistorySide,
    val start: Int,
    val endExclusive: Int,
    val excerpt: String,
    val excerptStart: Int,
)

internal data class SearchEntry(val question: String, val answer: String)
internal data class SearchSession(val id: String, val title: String, val entries: List<SearchEntry>)

/** An immutable projection of one profile, in the same order as its session list. */
internal class HistorySearchSnapshot(
    val profileId: String,
    private val sessions: List<SearchSession>,
) {
    /** Overrides are chats from this profile with newer in-memory content, keyed by session ID. */
    fun search(
        query: String,
        overrides: Map<String, Chat> = emptyMap(),
        cancelled: () -> Boolean = { false },
    ): List<HistoryHit> = buildList {
        if (query.isBlank()) return@buildList
        for (session in sessions) {
            fun collect(source: String, entryIndex: Int, side: HistorySide) {
                checkHistorySearchCancellation(cancelled)
                for (start in 0..source.length - query.length) {
                    if (start % 1024 == 0) checkHistorySearchCancellation(cancelled)
                    if (!source.regionMatches(start, query, 0, query.length, ignoreCase = true)) continue
                    val end = start + query.length
                    val (text, excerptStart) = excerpt(source, start, end)
                    add(HistoryHit(profileId, session.id, session.title, entryIndex, side,
                        start, end, text, excerptStart))
                }
            }
            collect(session.title, -1, HistorySide.TITLE)
            val updatedChat = overrides[session.id]
            if (updatedChat != null && updatedChat.session == session.id) {
                updatedChat.entries.forEachIndexed { index, entry ->
                    collect(entry.question, index, HistorySide.USER)
                    collect(entry.answer, index, HistorySide.ASSISTANT)
                }
            } else {
                session.entries.forEachIndexed { index, entry ->
                    collect(entry.question, index, HistorySide.USER)
                    collect(entry.answer, index, HistorySide.ASSISTANT)
                }
            }
        }
    }

    private fun excerpt(source: String, start: Int, end: Int): Pair<String, Int> {
        var left = (start - 28).coerceAtLeast(0)
        var right = end + (source.length - end).coerceAtMost(28)
        if (left > 0 && source[left].isLowSurrogate()) left--
        if (right < source.length && source[right].isLowSurrogate()) right++
        val text = buildString {
            if (left > 0) append('…')
            append(source, left, right)
            if (right < source.length) append('…')
        }
        val matchOffset = start - left + (if (left > 0) 1 else 0)
        return text to matchOffset
    }
}

internal fun checkHistorySearchCancellation(cancelled: () -> Boolean) {
    if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException()
}
