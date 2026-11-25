package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject

internal data class ContextPin(val seq: Long, val role: String)
internal data class ContextTurn(val seq: Long, val question: String, val answer: String, val status: String,
                               val checkpoint: String? = null)
internal data class ContextMessage(val seq: Long, val role: String, val text: String)

private val pinOrder = compareBy<ContextPin> { it.seq }.thenBy { if (it.role == "user") 0 else 1 }

private fun normalizedName(value: String?): String? {
    if (value == null) return null
    require(value.codePoints().noneMatch { Character.isISOControl(it) || it in 0xD800..0xDFFF }) {
        "名称不能包含控制字符或无效字符。"
    }
    return value.trim().takeIf { it.isNotEmpty() }?.also {
        require(it.codePointCount(0, it.length) <= 40) { "名称最多可用 40 个字符。" }
    }
}

internal data class ConversationPreferences(
    val profileName: String? = null,
    val peerName: String? = null,
    val recentTurns: Int? = null,
    val pins: List<ContextPin> = emptyList(),
    val remindAfter: Int? = null,
) {
    val effectiveName: String get() = normalizedName(peerName) ?: normalizedName(profileName) ?: "助手"

    fun normalized(): ConversationPreferences {
        require(recentTurns == null || recentTurns in 0..500) { "最近轮数须在 0 到 500 之间。" }
        require(remindAfter == null || remindAfter in 1..10000) { "提醒轮数须在 1 到 10000 之间。" }
        require(pins.size <= 128) { "最多可固定 128 条消息。" }
        require(pins.isEmpty() || recentTurns != null) { "固定消息前请选择最近轮数。" }
        require(pins.all { it.seq > 0 && (it.role == "user" || it.role == "assistant") }) { "固定消息无效。" }
        return copy(profileName = normalizedName(profileName), peerName = normalizedName(peerName),
            pins = pins.distinct().sortedWith(pinOrder))
    }

    fun toJson(): JSONObject = normalized().let { value ->
        val items = JSONArray()
        value.pins.forEach { items.put(JSONObject().put("seq", it.seq).put("role", it.role)) }
        JSONObject().put("profileName", value.profileName ?: JSONObject.NULL)
            .put("peerName", value.peerName ?: JSONObject.NULL)
            .put("recentTurns", value.recentTurns ?: JSONObject.NULL)
            .put("pins", items)
            .put("remindAfter", value.remindAfter ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): ConversationPreferences {
            fun name(key: String): String? = if (json.isNull(key)) null else json.get(key).let {
                require(it is String) { "名称格式无效。" }
                it
            }
            fun count(key: String): Int? = if (json.isNull(key)) null else integer(json.get(key)).let {
                require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                it.toInt()
            }
            val pins = if (!json.has("pins")) emptyList() else {
                val rows = json.getJSONArray("pins")
                require(rows.length() <= 128) { "最多可固定 128 条消息。" }
                List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    val role = row.get("role")
                    require(role is String)
                    ContextPin(integer(row.get("seq")), role)
                }
            }
            return ConversationPreferences(name("profileName"), name("peerName"), count("recentTurns"),
                pins, count("remindAfter")).normalized()
        }

        private fun integer(value: Any): Long {
            require(value is Int || value is Long) { "轮数或消息序号格式无效。" }
            return (value as Number).toLong()
        }
    }
}

internal data class ConversationPreferenceChanges(val expected: JSONObject, val changes: JSONObject)
internal data class ConversationPreferenceMerge(val value: ConversationPreferences, val conflicts: Set<String>)

internal fun conversationPreferenceChanges(
    base: ConversationPreferences, desired: ConversationPreferences,
): ConversationPreferenceChanges {
    val before = base.toJson()
    val after = desired.toJson()
    val expected = JSONObject()
    val changes = JSONObject()
    for (key in before.keys()) {
        val same = if (key == "pins") base.pins.toSet() == desired.pins.toSet()
            else before.get(key) == after.get(key)
        if (!same) {
            expected.put(key, before.get(key))
            changes.put(key, after.get(key))
        }
    }
    return ConversationPreferenceChanges(expected, changes)
}

/** Keep conflicting local edits in the returned draft until the user resolves them. */
internal fun mergeConversationPreferences(
    base: ConversationPreferences, local: ConversationPreferences, remote: ConversationPreferences,
): ConversationPreferenceMerge {
    val b = base.normalized()
    val l = local.normalized()
    val r = remote.normalized()
    val conflicts = mutableSetOf<String>()
    fun <T> merge(key: String, before: T, mine: T, theirs: T): T {
        if (mine == before) return theirs
        if (theirs != before && theirs != mine) conflicts.add(key)
        return mine
    }
    var value = ConversationPreferences(
        merge("profileName", b.profileName, l.profileName, r.profileName),
        merge("peerName", b.peerName, l.peerName, r.peerName),
        merge("recentTurns", b.recentTurns, l.recentTurns, r.recentTurns),
        merge("pins", b.pins, l.pins, r.pins),
        merge("remindAfter", b.remindAfter, l.remindAfter, r.remindAfter),
    )
    // Reverting to the default range and pinning a message concurrently require one user decision.
    if (value.pins.isNotEmpty() && value.recentTurns == null) {
        conflicts.addAll(listOf("recentTurns", "pins"))
        value = value.copy(recentTurns = l.recentTurns, pins = l.pins)
    }
    return ConversationPreferenceMerge(value, conflicts)
}

private fun ContextTurn.isCompletedPair(): Boolean =
    status == "complete" && question.isNotBlank() && answer.isNotBlank()

internal fun completedContextTurns(turns: List<ContextTurn>): Int = turns.count(ContextTurn::isCompletedPair)

/** Missing observations leave the previous threshold state intact. */
internal fun contextNoticeConditions(
    preview: AstrBotContextPreview, turns: List<ContextTurn>, prefs: ConversationPreferences,
): Map<String, Boolean> = buildMap {
    if (!preview.supported) return@buildMap
    val limit = preview.maxTokens
    val estimate = preview.estimatedTokens // The adapter includes its output reserve.
    if (limit != null && limit > 0 && estimate != null && estimate > 0) {
        val threshold = (limit / 100) * 85 + ((limit % 100) * 85 + 99) / 100
        put("capacity", estimate >= threshold)
    }

    val recent = prefs.recentTurns ?: 0
    val oldest = if (recent > 0) turns.filter(ContextTurn::isCompletedPair)
        .sortedByDescending { it.seq }.getOrNull(recent - 1) else null
    if (oldest == null) {
        put("rolling", false)
        return@buildMap
    }
    val unpinned = listOf("user", "assistant").filter { ContextPin(oldest.seq, it) !in prefs.pins }
    if (unpinned.isEmpty()) {
        put("rolling", false)
    } else if (preview.selected.any { message ->
            message.seq == oldest.seq && message.role in unpinned &&
                message.text == (if (message.role == "user") oldest.question else oldest.answer)
        }) {
        put("rolling", true)
    }
}

/** Null delegates to the service's existing history. An empty list explicitly selects no history. */
internal fun selectContext(turns: List<ContextTurn>, preferences: ConversationPreferences): List<ContextMessage>? {
    val value = preferences.normalized()
    if (value.recentTurns == null && value.pins.isEmpty()) return null
    val ordered = turns.filter { it.status == "complete" }.sortedBy { it.seq }
    val selected = value.pins.toMutableSet()
    ordered.asReversed().asSequence()
        .filter(ContextTurn::isCompletedPair)
        .take(value.recentTurns ?: 0)
        .forEach {
            selected.add(ContextPin(it.seq, "user"))
            selected.add(ContextPin(it.seq, "assistant"))
        }
    return buildList {
        for (turn in ordered) {
            if (turn.question.isNotBlank() && selected.remove(ContextPin(turn.seq, "user")))
                add(ContextMessage(turn.seq, "user", turn.question))
            if (turn.answer.isNotBlank() && selected.remove(ContextPin(turn.seq, "assistant")))
                add(ContextMessage(turn.seq, "assistant", turn.answer))
        }
    }
}
