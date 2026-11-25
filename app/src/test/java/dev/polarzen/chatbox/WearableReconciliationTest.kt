package dev.polarzen.chatbox

import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WearableReconciliationTest {
    private val origin = "com.xiaomi.wearable"
    private fun steps(id: String, count: Int, version: Long = 4) =
        JSONObject().put("metric", "steps").put("source", origin).put("id", id)
            .put("startMs", 100L).put("endMs", 200L).put("version", version).put("count", count)

    private fun diff(rows: List<JSONObject>, index: JSONObject, start: Long = 10) =
        wearableDiff("steps", origin, rows, index, start, 300L)

    @Test fun acknowledgedSnapshotReconcilesCorrectionsDeletesAndReinstatements() {
        val index = JSONObject()
        val zero = steps("a", 0)
        val initial = diff(listOf(zero), index).single()
        assertEquals(0, initial.row.getInt("count"))
        assertEquals(initial.row.toString(), diff(listOf(zero), index).single().row.toString())
        index.put("a", initial.stamp)
        assertTrue(diff(listOf(zero), index).isEmpty())
        val correction = diff(listOf(steps("a", 12)), index).single()
        assertEquals(5, correction.row.getLong("version"))
        index.put("a", correction.stamp)
        val deletion = diff(emptyList(), index).single()
        assertTrue(deletion.deleted)
        assertEquals(6, deletion.row.getLong("version"))
        index.put("a", deletion.stamp)
        assertTrue(diff(emptyList(), index).isEmpty())
        assertEquals(7, diff(listOf(zero), index).single().row.getLong("version"))
    }

    @Test fun aRecordCrossingTheRollingReadBoundaryIsNotDeclaredDeleted() {
        val first = diff(listOf(steps("a", 2)), JSONObject()).single()
        assertTrue(diff(emptyList(), JSONObject().put("a", first.stamp), 120L).isEmpty())
    }

    @Test fun boundedBatchesRetainEveryChangeOnce() {
        val rows = (0 until 203).map { steps(it.toString(), it) }
        val batches = wearableBatches(diff(rows, JSONObject())).toList()
        assertEquals(listOf(100, 100, 3), batches.map { it.size })
        assertEquals(rows.map { it.getString("id") }, batches.flatten().map { it.row.getString("id") })
    }

    @Test fun pendingSleepRevocationKeepsTheStepsIndexAcrossFurtherOfflineEdits() {
        fun policy(vararg metrics: String) = JSONObject().put("source", origin).put("profile", "personal")
            .put("metrics", JSONArray(metrics.toList()))
        val both = policy("steps", "sleep")
        val stepsOnly = policy("steps")
        val queuedDuringEdit = listOf(both, stepsOnly, stepsOnly)
        val adjustedTime = policy("steps", "sleep").put("morningTime", "07:00")
        val initial = diff(listOf(steps("deleted-while-offline", 3)), JSONObject()).single()
        val stepsIndex = JSONObject().put("deleted-while-offline", initial.stamp)
        assertTrue(wearableIndexSurvives("steps", adjustedTime, queuedDuringEdit))
        assertFalse(wearableIndexSurvives("sleep", adjustedTime, queuedDuringEdit))
        assertTrue(diff(emptyList(), stepsIndex).single().deleted)
        assertFalse(wearableIndexSurvives("steps", adjustedTime, listOf(stepsOnly.put("profile", "different"))))
    }

    @Test fun retiredDeviceRequiresAcknowledgementOrVerifiedTerminal401() = runBlocking {
        assertTrue(verifiedWearableRevocation { })
        assertTrue(verifiedWearableRevocation { throw SharedHttpFailure(401) })
        assertFalse(verifiedWearableRevocation { throw DeviceAddressFailure() })
        assertFalse(verifiedWearableRevocation { throw SharedHttpFailure(503) })
        assertFalse(verifiedWearableRevocation { throw java.net.SocketTimeoutException() })
    }
}
