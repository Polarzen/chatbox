package dev.polarzen.chatbox

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class WearableHealthSourceTest {
    private val start = Instant.parse("2025-05-01T23:00:00Z")
    private val middle = start.plusSeconds(60)
    private val end = start.plusSeconds(120)
    private val mi = "com.xiaomi.wearable"

    // Health Connect normally fills the ID, origin and modification time when reading.
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private fun stored(source: String = mi, deviceType: Int? = Device.TYPE_FITNESS_BAND,
                       method: Int = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
                       modified: Instant = end): Metadata =
        Metadata(recordingMethod = method, id = "stored-record-id", dataOrigin = DataOrigin(source),
            lastModifiedTime = modified, device = deviceType?.let { Device(it, "Fixture vendor", "Fixture band") })

    @Test fun filtersPackagePhoneAndManualRecords() {
        assertTrue(wearableOrigin(stored(), mi))
        assertTrue(wearableOrigin(stored(deviceType = null), mi))
        assertFalse(wearableOrigin(stored("other.phone"), mi))
        assertFalse(wearableOrigin(stored("other.phone"), "other.phone"))
        assertFalse(wearableOrigin(stored(deviceType = Device.TYPE_PHONE), mi))
        assertFalse(wearableOrigin(stored(method = Metadata.RECORDING_METHOD_MANUAL_ENTRY), mi))
    }

    @Test fun preservesIdentifiersRevisionOffsetsAndCount() {
        val row = wearableRecord(StepsRecord(start, ZoneOffset.ofHours(8), end, null, 42, stored()))
        assertEquals("stored-record-id", row.getString("id"))
        assertEquals(mi, row.getString("source"))
        val device = row.getJSONObject("device")
        assertEquals("fitness_band", device.getString("type"))
        assertEquals("Fixture vendor", device.getString("manufacturer"))
        assertEquals("Fixture band", device.getString("model"))
        assertFalse(wearableRecord(StepsRecord(start, null, end, null, 42, stored(deviceType = null))).has("device"))
        assertEquals(end.toEpochMilli(), row.getLong("version"))
        assertEquals(start.toEpochMilli(), row.getLong("startMs"))
        assertEquals(28800, row.getInt("startOffsetSeconds"))
        assertFalse(row.has("endOffsetSeconds"))
        assertEquals(42, row.getInt("count"))
    }

    @Test fun preservesRealStageIntervalsAndIrregularSampleTimes() {
        val session = SleepSessionRecord(start, null, end, null, stored(), stages = listOf(
            SleepSessionRecord.Stage(middle, end, SleepSessionRecord.STAGE_TYPE_REM)))
        val stages = wearableRecord(session).getJSONArray("stages")
        assertEquals(1, stages.length())
        assertEquals("rem", stages.getJSONObject(0).getString("stage"))
        assertEquals(middle.toEpochMilli(), stages.getJSONObject(0).getLong("startMs"))
        val times = listOf(start.plusMillis(25), middle.plusSeconds(17))
        val record = HeartRateRecord(start, null, end, null,
            times.mapIndexed { i, t -> HeartRateRecord.Sample(t, 70L + i) }, stored())
        val samples = wearableRecord(record).getJSONArray("samples")
        assertEquals(2, samples.length())
        for ((i, t) in times.withIndex()) {
            assertEquals(t.toEpochMilli(), samples.getJSONObject(i).getLong("timeMs"))
            assertEquals(70 + i, samples.getJSONObject(i).getInt("bpm"))
        }
    }

    @Test fun distanceRequiresSameOriginAndUnambiguousCoverage() {
        val session = ExerciseSessionRecord(start, null, end, null, stored(),
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)
        fun distance(a: Instant, b: Instant, meters: Double, origin: String = mi) =
            DistanceRecord(a, null, b, null, Length.meters(meters), stored(origin, modified = end.plusSeconds(1)))
        val first = distance(start, middle, 100.0)
        val second = distance(middle, end, 115.5)
        val row = wearableRecord(session, listOf(second, first))
        assertEquals("running", row.getString("exerciseType"))
        assertEquals(215.5, row.getDouble("distanceMeters"), 0.0)
        assertEquals(end.plusSeconds(1).toEpochMilli(), row.getLong("version"))
        for (pieces in listOf(listOf(first), listOf(first, first, second), listOf(distance(start, end, 50.0, "other")))) {
            assertFalse(wearableRecord(session, pieces).has("distanceMeters"))
        }
    }

    @Test fun sharesOnlyWorkoutSamplesAndPreservesSinglePointMeasurements() {
        val record = HeartRateRecord(start, null, end, null,
            listOf(start, middle, end).map { HeartRateRecord.Sample(it, 120) }, stored())
        val windows = listOf(start.plusSeconds(1).toEpochMilli()..middle.toEpochMilli(),
            middle.toEpochMilli()..end.minusSeconds(1).toEpochMilli())
        val selected = wearableWorkoutHeart(record, windows).single().getJSONArray("samples")
        assertEquals(1, selected.length())
        assertEquals(middle.toEpochMilli(), selected.getJSONObject(0).getLong("timeMs"))
        assertTrue(wearableWorkoutHeart(record, emptyList()).isEmpty())
        val point = HeartRateRecord(middle, null, middle, null,
            listOf(HeartRateRecord.Sample(middle, 90)), stored())
        val row = wearableWorkoutHeart(point, windows).single()
        assertEquals(row.getLong("startMs"), row.getLong("endMs"))
    }

    @Test fun highFrequencyWorkoutUsesStableBoundedChunks() {
        val record = HeartRateRecord(start, null, end, null,
            (0..4096).map { HeartRateRecord.Sample(start.plusMillis(it.toLong()), 88L) }, stored())
        val rows = wearableWorkoutHeart(record, listOf(start.toEpochMilli() until end.toEpochMilli()))
        assertEquals(listOf("stored-record-id", "stored-record-id#1"), rows.map { it.getString("id") })
        assertEquals(listOf(4096, 1), rows.map { it.getJSONArray("samples").length() })
    }
}
