package dev.polarzen.chatbox

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import org.json.JSONObject

/** Opt-in, content-free timing output for repeatable desktop QA measurements. */
internal class DesktopQaTimingLog private constructor(private val file: Path?) {
    private val runId = UUID.randomUUID().toString()
    private val writer = file?.let {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "chatbox-qa-timing").apply { isDaemon = true }
        }
    }

    fun record(
        operationId: Int,
        stage: String,
        elapsedMillis: Long,
        imageIndex: Int? = null,
        imageCount: Int? = null,
        safeCause: String? = null,
    ) {
        if (writer == null) return
        val event = JSONObject()
            .put("run_id", runId)
            .put("operation_id", operationId)
            .put("stage", stage)
            .put("elapsed_ms", elapsedMillis)
        if (imageIndex != null && imageCount != null) {
            event.put("image_index", imageIndex).put("image_count", imageCount)
        }
        safeCause?.let { event.put("safe_cause", it) }
        val line = event.toString()
        enqueue {
            Files.createDirectories(file!!.toAbsolutePath().parent)
            Files.writeString(file, "$line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private fun enqueue(action: () -> Unit) {
        runCatching { writer?.execute { runCatching(action) } }
    }

    fun close() {
        writer?.shutdown()
    }

    companion object {
        const val QA_FILE_ENV = "CHATBOX_QA_TIMING_FILE"

        fun fromEnvironment(): DesktopQaTimingLog {
            val path = System.getenv(QA_FILE_ENV)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Path.of(it) }.getOrNull() }
            return DesktopQaTimingLog(path)
        }
    }

}

internal class DesktopRequestTiming(
    private val operationId: Int,
    private val log: DesktopQaTimingLog,
    startNanos: Long = System.nanoTime(),
) {
    private val startedAtNanos = startNanos
    private val firstDrawRecorded = AtomicBoolean(false)

    init {
        log.record(operationId, "send_clicked", 0L)
    }

    fun onTransportEvent(event: AstrBotTransportEvent) {
        when (event) {
            is AstrBotTransportEvent.UploadStarted -> record("upload_started", event.index, event.count)
            is AstrBotTransportEvent.UploadFinished -> record("upload_finished", event.index, event.count)
            AstrBotTransportEvent.ChatStarted -> record("chat_started")
            AstrBotTransportEvent.HeadersReceived -> record("headers_received")
            AstrBotTransportEvent.StreamReady -> record("stream_ready")
            AstrBotTransportEvent.FirstContent -> record("first_content")
            AstrBotTransportEvent.Completed -> record("transport_completed")
            else -> Unit
        }
    }

    fun requestFinished(safeCause: String? = null, cancelled: Boolean = false) {
        val stage = when {
            cancelled -> "request_cancelled"
            safeCause != null -> "request_failed"
            else -> "request_finished"
        }
        record(stage, safeCause = safeCause)
    }

    fun firstDrawn() {
        if (firstDrawRecorded.compareAndSet(false, true)) record("first_render")
    }

    private fun record(stage: String, imageIndex: Int? = null, imageCount: Int? = null, safeCause: String? = null) {
        val elapsedMillis = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L
        log.record(operationId, stage, elapsedMillis, imageIndex, imageCount, safeCause)
    }
}
