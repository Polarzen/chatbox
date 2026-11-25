package dev.polarzen.chatbox

import org.json.JSONObject

/** A snapshot of the original conversation. Capture it when opening an editor. */
internal class ChatPreferenceHandle(
    val identity: String,
    val value: ConversationPreferences,
    val conflicts: Set<String>,
    val pending: Boolean,
    val paired: Boolean,
    val ready: Boolean,
    val sequenceAt: (Int) -> Long?,
    val save: (ConversationPreferences) -> Unit,
    val claimReminder: suspend (Int) -> Boolean,
    val saveReviewed: (ConversationPreferences, Set<String>) -> Unit = { desired, _ -> save(desired) },
    val observeNotice: suspend (String, Long, Boolean) -> Boolean = { _, _, _ -> false },
    val contextPending: Boolean = false,
    val remoteValue: ConversationPreferences = value,
)

internal fun ConversationPreferences.sameContext(other: ConversationPreferences) =
    recentTurns == other.recentTurns && pins == other.pins

internal data class ContextNoticeState(val through: Long, val active: Boolean)

internal data class PreferenceJournal(
    val base: ConversationPreferences = ConversationPreferences(),
    val value: ConversationPreferences = base,
    val conflicts: Set<String> = emptySet(),
    val claimed: Set<Int> = emptySet(),
    val reverted: Set<String> = emptySet(),
    val notices: Map<String,ContextNoticeState> = emptyMap(),
) {
    val pending get() = base != value || reverted.isNotEmpty()
    val contextPending get() = base.recentTurns != value.recentTurns || base.pins != value.pins ||
        "recentTurns" in reverted || "pins" in reverted || "recentTurns" in conflicts || "pins" in conflicts

    // A remote observation can arrive while the user is editing. Keep the user's
    // value and the latest server baseline separately until explicitly accepted.
    fun observe(remote: ConversationPreferences): PreferenceJournal {
        val merged = mergeConversationPreferences(base, value, remote)
        // An earlier POST may have committed even if its response was lost. An explicit
        // local revert to the old baseline is still an edit until the service confirms it.
        val retained = if (reverted.isEmpty()) emptyList() else {
            val changed = conversationPreferenceChanges(value, remote).changes
            val localChanges = conversationPreferenceChanges(base,value).changes
            reverted.filter { changed.has(it) && !localChanges.has(it) }
        }
        var next = merged.value
        val disputed = (conflicts + merged.conflicts + retained).toMutableSet()
        if (retained.isNotEmpty()) {
            val json = next.toJson()
            val local = value.toJson()
            retained.forEach { json.put(it, local.get(it)) }
            if (json.isNull("recentTurns") && json.getJSONArray("pins").length() > 0) {
                listOf("recentTurns", "pins").forEach { json.put(it, local.get(it)); disputed.add(it) }
            }
            next = ConversationPreferences.fromJson(json)
        }
        val dirty = conversationPreferenceChanges(remote, next).changes.keys().asSequence().toSet()
        return copy(base = remote, value = next, conflicts = disputed.intersect(dirty), reverted = emptySet())
    }

    fun edit(snapshot: ConversationPreferences, desired: ConversationPreferences, seenConflicts: Set<String>): PreferenceJournal {
        val normalized = desired.normalized()
        val result = mergeConversationPreferences(snapshot, normalized, value)
        check(result.conflicts.isEmpty()) { "设置已更改，请重新打开并确认。" }
        val touched = conversationPreferenceChanges(value, result.value).changes.keys().asSequence().toSet()
        val dirty = conversationPreferenceChanges(base, result.value).changes
        return copy(value = result.value, conflicts = conflicts - seenConflicts,
            reverted = (reverted + touched).filterNot(dirty::has).toSet(),
            notices = if ("pins" in touched || "recentTurns" in touched) emptyMap() else notices)
    }

    fun encode(profile: Boolean): String {
        fun split(p: ConversationPreferences) = p.toJson().also { json ->
            json.keys().asSequence().toList().filter { (it == "profileName") != profile }.forEach(json::remove)
        }
        return JSONObject().put("base", split(base)).put("value", split(value))
            .put("conflicts", org.json.JSONArray(conflicts.filter { (it == "profileName") == profile }))
            .put("reverted", org.json.JSONArray(reverted.filter { (it == "profileName") == profile }))
            .also { if (!profile) {
                it.put("claimed", org.json.JSONArray(claimed.toList()))
                it.put("notices", JSONObject().apply {
                    for ((kind,state) in notices) put(kind,JSONObject().put("through",state.through).put("active",state.active))
                })
            } }.toString()
    }

    companion object {
        fun decode(profile: String?, room: String?): PreferenceJournal {
            if (profile == null && room == null) return PreferenceJournal()
            val p = profile?.let(::JSONObject)
            val r = room?.let(::JSONObject)
            fun join(field: String): ConversationPreferences {
                val values = r?.optJSONObject(field) ?: JSONObject()
                values.put("profileName", p?.optJSONObject(field)?.opt("profileName") ?: JSONObject.NULL)
                return ConversationPreferences.fromJson(values)
            }
            fun fields(name: String) = listOfNotNull(p, r).flatMap { part ->
                val fields = part.optJSONArray(name) ?: return@flatMap emptyList()
                List(fields.length()) { fields.getString(it) }
            }.toSet()
            val claims = r?.optJSONArray("claimed")
            val notices = r?.optJSONObject("notices")
            return PreferenceJournal(join("base"), join("value"), fields("conflicts"),
                if (claims == null) emptySet() else List(claims.length()) { claims.getInt(it) }.toSet(), fields("reverted"),
                notices?.keys()?.asSequence()?.associateWith { kind ->
                    val item = notices.getJSONObject(kind)
                    ContextNoticeState(item.getLong("through"),item.getBoolean("active"))
                }.orEmpty())
        }
    }
}

internal data class PreferenceRoom(val profile: String, val sid: String)

internal fun preferenceHandle(
    identity: String, snapshot: PreferenceJournal, paired: Boolean, ready: Boolean,
    sequenceAt: (Int) -> Long?, valid: () -> Boolean,
    update: ((PreferenceJournal) -> PreferenceJournal) -> PreferenceJournal,
    remoteClaim: suspend (Int) -> Boolean,
    remoteNotice: suspend (String, Long, Boolean) -> Boolean = { _, _, _ -> false },
    directThrough: () -> Long = { 0L },
): ChatPreferenceHandle {
    fun saveReviewed(desired: ConversationPreferences, reviewed: Set<String>) {
        check(ready && valid()) { "会话来源已更改或尚未核实，请重新打开设置。" }
        update { current ->
            val resolving = snapshot.conflicts.intersect(reviewed)
            val changed = conversationPreferenceChanges(snapshot.base, current.base).changes
            check(resolving.none(changed::has)) { "其他设备已再次修改设置，请重新核对。" }
            val next = current.edit(snapshot.value, desired, resolving)
            if (paired) next else next.copy(base = next.value, reverted = emptySet())
        }
    }
    return ChatPreferenceHandle(identity, snapshot.value, snapshot.conflicts, paired && snapshot.pending,
        paired, ready, sequenceAt, { desired ->
            saveReviewed(desired, conversationPreferenceChanges(snapshot.value,desired).changes.keys().asSequence().toSet())
        }, claim@{ count ->
        val threshold = snapshot.value.remindAfter ?: return@claim false
        if (!ready || count < threshold || !valid() || "remindAfter" in snapshot.conflicts) return@claim false
        if (paired) return@claim remoteClaim(threshold)
        var claimed = false
        update { current ->
            if (current.value.remindAfter != threshold || threshold in current.claimed) current
            else current.copy(claimed = current.claimed + threshold).also { claimed = true }
        }
        claimed
    }, ::saveReviewed, observe@{ kind, through, active ->
        require(kind == "capacity" || kind == "rolling")
        require(through >= 0)
        if (!ready || !valid() || snapshot.value.recentTurns == null) return@observe false
        if (paired) return@observe remoteNotice(kind,through,active)
        var claimed = false
        update { current ->
            val previous = current.notices[kind]
            if (through != directThrough() || current.value.pins != snapshot.value.pins || current.value.recentTurns != snapshot.value.recentTurns ||
                previous != null && (through < previous.through || through == previous.through && previous.active && !active)) current
            else {
                claimed = active && previous?.active != true
                current.copy(notices = current.notices + (kind to ContextNoticeState(through,active)))
            }
        }
        claimed
    }, snapshot.contextPending, snapshot.base)
}

/** The caller serializes requests to one grant. Local edits may occur during either request. */
internal suspend fun syncPreferenceRoom(
    api: SharedDeviceTransport, binding: DeviceBinding, key: PreferenceRoom,
    update: ((PreferenceJournal) -> PreferenceJournal) -> PreferenceJournal,
) {
    val remote = api.chatPreferences(binding, key.profile, key.sid)
    val journal = update { it.observe(remote) }
    val changes = journal.value.toJson()
    val baseline = journal.base.toJson()
    journal.conflicts.forEach { changes.put(it, baseline.get(it)) }
    if (changes.isNull("recentTurns") && changes.getJSONArray("pins").length() > 0) {
        changes.put("recentTurns", baseline.get("recentTurns"))
        changes.put("pins", baseline.get("pins"))
    }
    val outgoing = ConversationPreferences.fromJson(changes)
    if (outgoing == journal.base) return
    val result = try {
        api.updateChatPreferences(binding, key.profile, key.sid, journal.base, outgoing)
    } catch (failure: SharedHttpFailure) {
        if (failure.status != 409) throw failure
        val latest = api.chatPreferences(binding, key.profile, key.sid)
        update { it.observe(latest) }
        return
    }
    update { it.copy(base = outgoing).observe(result) }
}
