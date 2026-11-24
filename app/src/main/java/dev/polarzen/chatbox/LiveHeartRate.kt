package dev.polarzen.chatbox

import java.time.Duration
import java.time.Instant

private val readingLifetime = Duration.ofSeconds(10)

/** Latest BLE measurement for one reader. Monotonic time controls expiry across wall-clock changes. */
internal class LiveHeartRate(
    private val wallClock: () -> Instant = Instant::now,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private data class Reading(val bpm: Int, val receivedAt: Instant, val receivedNanos: Long)
    private var latest: Reading? = null

    fun update(bpm: Int?) {
        record(bpm, null)
    }

    fun update(bpm: Int?, receivedAt: Instant): Boolean = record(bpm, receivedAt)

    @Synchronized
    private fun record(bpm: Int?, receivedAt: Instant?): Boolean {
        val nowNanos = monotonicNanos()
        val now = wallClock()
        val capture = receivedAt ?: now
        val age = Duration.between(capture, now)
        latest = if (bpm != null && bpm in 1..65535 && !age.isNegative && age < readingLifetime) {
            Reading(bpm, capture, nowNanos - age.toNanos())
        } else null
        return latest != null
    }

    /** Milliseconds until expiry, rounded up for scheduling a display refresh. */
    @Synchronized
    fun remainingMillis(): Long {
        val reading = latest ?: return 0
        val age = monotonicNanos() - reading.receivedNanos
        if (age !in 0L until readingLifetime.toNanos()) return 0
        return (readingLifetime.toNanos() - age + 999_999L) / 1_000_000L
    }

    @Synchronized
    fun appendTo(text: String): String {
        val reading = latest ?: return text
        if (text.trimStart().startsWith("/")) return text
        if (remainingMillis() == 0L) return text
        val measurement = "手环心率：${reading.bpm} BPM（接收时间 ${reading.receivedAt}）。"
        return if (text.isEmpty()) measurement else "$text\n\n$measurement"
    }
}
