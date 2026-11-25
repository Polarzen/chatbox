package dev.polarzen.chatbox

import java.security.MessageDigest
import org.json.JSONObject

internal data class WearableChange(val row: JSONObject, val stamp: JSONObject, val deleted: Boolean)

/** Snapshot stamps contain an ID's digest, wire version and end time. A changed distance
 * attribution or reinstated record can require a new wire version even if its HC ID is stable. */
internal fun wearableDiff(metric: String, source: String, records: List<JSONObject>, previous: JSONObject,
                          startMs: Long, nowMs: Long): List<WearableChange> {
    val current = records.associateBy { it.getString("id") }
    val changes = mutableListOf<WearableChange>()
    val sha = MessageDigest.getInstance("SHA-256")
    for ((id, row) in current) {
        val digest = java.util.Base64.getEncoder().encodeToString(sha.digest(row.toString().toByteArray(Charsets.UTF_8)))
        val old = previous.optJSONObject(id)
        if (old?.optString("hash") == digest) continue
        val version = maxOf(row.getLong("version"), (old?.getLong("version") ?: -1L) + 1L)
        val stamp = JSONObject().put("hash", digest).put("version", version)
            .put("start", row.getLong("startMs")).put("end", row.getLong("endMs"))
        changes += WearableChange(JSONObject(row.toString()).put("version", version), stamp, false)
    }
    for (id in previous.keys().asSequence()) {
        val old = previous.getJSONObject(id)
        if (id in current || old.optString("hash").isEmpty() || old.getLong("start") < startMs) continue
        val version = old.getLong("version") + 1L
        val deletion = JSONObject().put("metric", metric).put("source", source).put("id", id).put("version", version)
        changes += WearableChange(deletion, JSONObject().put("hash", "").put("version", version).put("end", nowMs), true)
    }
    return changes
}

internal fun wearableBatches(changes: List<WearableChange>): Sequence<List<WearableChange>> = sequence {
    var batch = mutableListOf<WearableChange>()
    var bytes = 0
    for (change in changes) {
        val size = change.row.toString().toByteArray(Charsets.UTF_8).size
        if (batch.isNotEmpty() && (batch.size >= 100 || size + bytes > 512_000)) {
            yield(batch)
            batch = mutableListOf()
            bytes = 0
        }
        batch += change
        bytes += size
    }
    if (batch.isNotEmpty()) yield(batch)
}
