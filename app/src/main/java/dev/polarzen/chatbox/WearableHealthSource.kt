package dev.polarzen.chatbox

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import org.json.JSONArray
import org.json.JSONObject

internal class WearableHealthSource(context: Context) {
    private val app = context.applicationContext
    val sdkStatus: Int get() = HealthConnectClient.getSdkStatus(app)
    val available: Boolean get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE
    private val client by lazy { HealthConnectClient.getOrCreate(app) }

    fun backgroundAvailable(): Boolean = available && client.features.getFeatureStatus(
        HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
    ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    suspend fun grantedPermissions(): Set<String> =
        if (available) client.permissionController.getGrantedPermissions() else emptySet()

    fun permissionsFor(metrics: Set<String>, background: Boolean = false): Set<String> = buildSet {
        for (metric in metrics) add(permissionFor(metric))
        if ("exercise" in metrics) add(distancePermission)
        if (metrics.isNotEmpty() && background && backgroundAvailable()) add(backgroundPermission)
    }

    /** All pages of one metric and origin, including records with zero values.
     * A failed read throws, so callers only reconcile deletions after a complete read.
     * The caller diffs IDs and payloads against the last acknowledged snapshot. For heart_rate,
     * supply exercise rows read under this consent or an empty list if exercise was not granted.
     */
    suspend fun readMetric(metric: String, source: String, startMs: Long, endMs: Long,
                           workouts: List<JSONObject>): List<JSONObject> {
        require(source in miFitnessPackages)
        val range = boundedRange(startMs, endMs)
        if (metric == "heart_rate") {
            val windows = workouts.filter { it.optString("metric") == "exercise" && it.optString("source") == source }
                .map { it.getLong("startMs") until it.getLong("endMs") }
            if (windows.isEmpty()) return emptyList()
            return readRecords(HeartRateRecord::class, source, range).flatMap { wearableWorkoutHeart(it, windows) }
        }
        val records = readRecords(recordTypes.getValue(metric), source, range)
        val distances = if (metric == "exercise" && records.isNotEmpty() &&
            distancePermission in grantedPermissions()) {
            try {
                readRecords(DistanceRecord::class, source, range)
            } catch (e: SecurityException) {
                // Distance has a separate permission which may have been revoked during a read.
                if (distancePermission in grantedPermissions()) throw e
                emptyList()
            }
        } else emptyList()
        return records.map { record -> wearableRecord(record, distances) }
    }

    /** Probe only Mi Fitness origins, returning package names without retaining records. */
    suspend fun discoverSources(metric: String, startMs: Long, endMs: Long): Set<String> {
        val range = boundedRange(startMs, endMs)
        return miFitnessPackages.filterTo(linkedSetOf()) { source ->
            readRecords(recordTypes.getValue(metric), source, range, firstOnly = true).isNotEmpty()
        }
    }

    private suspend fun <T : Record> readRecords(type: KClass<T>, source: String, range: TimeRangeFilter,
                                                firstOnly: Boolean = false): List<T> {
        val result = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type, range, setOf(DataOrigin(source)),
                pageSize = 100, pageToken = token))
            for (record in page.records) {
                if (wearableOrigin(record.metadata, source)) {
                    result.add(record)
                    if (firstOnly) return result
                }
            }
            token = page.pageToken
        } while (token != null)
        return result
    }

    companion object {
        val miFitnessPackages = setOf("com.xiaomi.wearable", "com.mi.health")
        private val recordTypes = mapOf(
            "steps" to StepsRecord::class, "sleep" to SleepSessionRecord::class,
            "exercise" to ExerciseSessionRecord::class, "heart_rate" to HeartRateRecord::class
        )
        val distancePermission = HealthPermission.getReadPermission(DistanceRecord::class)
        const val backgroundPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        fun permissionFor(metric: String): String = HealthPermission.getReadPermission(recordTypes.getValue(metric))

        private fun boundedRange(startMs: Long, endMs: Long): TimeRangeFilter {
            require(startMs >= 0 && endMs > startMs && endMs - startMs <= Duration.ofDays(30).toMillis())
            return TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs))
        }
    }
}

internal fun wearableOrigin(metadata: Metadata, source: String): Boolean =
    metadata.dataOrigin.packageName == source && source in WearableHealthSource.miFitnessPackages &&
        metadata.recordingMethod != Metadata.RECORDING_METHOD_MANUAL_ENTRY &&
        metadata.device?.type in setOf(null, Device.TYPE_UNKNOWN, Device.TYPE_FITNESS_BAND, Device.TYPE_WATCH)

/** Keep a sample once even when the selected source wrote overlapping exercise sessions. */
internal fun wearableWorkoutHeart(record: HeartRateRecord, windows: List<LongRange>): List<JSONObject> {
    val samples = record.samples.filter { sample -> windows.any { sample.time.toEpochMilli() in it } }
    // A long session can exceed one upload. The source UUID and chunk number are
    // deterministic, so a shorter revision can delete its former trailing chunks.
    return samples.sortedBy { it.time }.chunked(4096).mapIndexed { index, chunk ->
        wearableRecord(HeartRateRecord(record.startTime, record.startZoneOffset,
            record.endTime, record.endZoneOffset, chunk, record.metadata)).also { row ->
            if (index > 0) row.put("id", "${record.metadata.id}#$index")
        }
    }
}

internal fun wearableRecord(record: Record, distances: List<DistanceRecord> = emptyList()): JSONObject {
    val meta = record.metadata
    val json = JSONObject().put("id", meta.id).put("version", meta.lastModifiedTime.toEpochMilli())
        .put("source", meta.dataOrigin.packageName)
    meta.device?.let { device ->
        val description = JSONObject().put("type", when (device.type) {
            Device.TYPE_WATCH -> "watch"
            Device.TYPE_FITNESS_BAND -> "fitness_band"
            else -> "unknown"
        })
        fun label(text: String?): String? = text?.trim()?.take(128)?.trimEnd()?.takeIf { it.isNotEmpty() }
        label(device.manufacturer)?.let { description.put("manufacturer", it) }
        label(device.model)?.let { description.put("model", it) }
        json.put("device", description)
    }

    fun interval(metric: String, start: Instant, end: Instant, startOffset: ZoneOffset?, endOffset: ZoneOffset?) {
        json.put("metric", metric).put("startMs", start.toEpochMilli()).put("endMs", end.toEpochMilli())
        startOffset?.let { json.put("startOffsetSeconds", it.totalSeconds) }
        endOffset?.let { json.put("endOffsetSeconds", it.totalSeconds) }
    }

    when (record) {
        is StepsRecord -> {
            interval("steps", record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
            json.put("count", record.count)
        }
        is HeartRateRecord -> {
            interval("heart_rate", record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
            json.put("samples", JSONArray(record.samples.map {
                JSONObject().put("timeMs", it.time.toEpochMilli()).put("bpm", it.beatsPerMinute)
            }))
        }
        is SleepSessionRecord -> {
            interval("sleep", record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
            json.put("stages", JSONArray(record.stages.map {
                JSONObject().put("startMs", it.startTime.toEpochMilli()).put("endMs", it.endTime.toEpochMilli())
                    .put("stage", when (it.stage) {
                        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
                        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
                        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
                        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
                        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
                        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
                        else -> "unknown"
                    })
            }))
        }
        is ExerciseSessionRecord -> {
            interval("exercise", record.startTime, record.endTime, record.startZoneOffset, record.endZoneOffset)
            json.put("exerciseType", exerciseName(record.exerciseType))
            // Distance records are independent of sessions. Require uninterrupted coverage of
            // exactly this interval to avoid attributing a partial or overlapping total.
            val segments = distances.filter { it.startTime < record.endTime && it.endTime > record.startTime &&
                it.metadata.dataOrigin == meta.dataOrigin && it.metadata.device == meta.device }.sortedBy { it.startTime }
            if (segments.isNotEmpty() && segments.first().startTime == record.startTime &&
                segments.last().endTime == record.endTime &&
                segments.zipWithNext().all { (a, b) -> a.endTime == b.startTime }) {
                json.put("distanceMeters", segments.sumOf { it.distance.inMeters })
                json.put("version", maxOf(json.getLong("version"), segments.maxOf { it.metadata.lastModifiedTime.toEpochMilli() }))
            }
        }
        else -> error("Unsupported wearable record type")
    }
    return json
}

private fun exerciseName(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "other_workout"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running_treadmill"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "biking"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "biking_stationary"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swimming_pool"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming_open_water"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "strength_training"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
    else -> "health_connect_$type"
}
