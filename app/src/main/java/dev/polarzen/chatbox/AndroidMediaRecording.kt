package dev.polarzen.chatbox

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/** One capture owns its recorder, output, and original session callbacks through publication. */
internal class MobileRecording {
    @Volatile private var stopping = false
    @Volatile private var discarded = false
    @Volatile private var sampleBytes = 0
    val elapsedMillis: Long get() = sampleBytes.toLong() * 1000 / (MOBILE_RECORD_RATE * 2)

    fun stop() { stopping = true }
    fun cancel() { discarded = true; stop() }

    fun start(context: Context, finished: (MobileAsset?, String?) -> Unit) {
        val app = context.applicationContext
        thread(name = "Chatbox microphone") {
            var asset: MobileAsset? = null
            var note: String? = null
            try {
                val part = File.createTempFile("capture-", ".part", mobileMediaDirectory(app))
                try {
                    RandomAccessFile(part, "rw").use { output ->
                        output.seek(44)
                        try {
                            if (!stopping) capture(output)
                        } catch (e: Exception) {
                            note = "录音中断：${e.localizedMessage ?: "麦克风不可用"}"
                        }
                        if (!discarded && sampleBytes > 0) {
                            output.setLength(44L + sampleBytes)
                            output.seek(0)
                            output.write(mobilePcmHeader(sampleBytes))
                            output.fd.sync()
                        }
                    }
                    if (!discarded && sampleBytes > 0) {
                        asset = MobileAsset(publishMobileMedia(part, "wav").absolutePath, "audio", "录音.wav")
                        if (sampleBytes == MOBILE_REPLY_LIMIT - 44) note = "已达到录音附件大小上限。"
                    } else if (!discarded && note == null) note = "没有录到有效采样。"
                } finally {
                    part.delete()
                }
            } catch (e: Exception) {
                note = "无法保存录音：${e.localizedMessage ?: "本机附件不可用"}"
            }
            Handler(Looper.getMainLooper()).post {
                if (discarded) {
                    asset?.let { File(it.path).delete() }
                    finished(null, null)
                } else finished(asset, note)
            }
        }
    }

    @SuppressLint("MissingPermission") // The composer checks RECORD_AUDIO immediately before capture.
    private fun capture(output: RandomAccessFile) {
        val channels = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(MOBILE_RECORD_RATE, channels, encoding)
        check(minimum > 0) { "麦克风不支持 16 kHz PCM。" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, MOBILE_RECORD_RATE, channels, encoding,
            maxOf(minimum, 8192))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风不可用。" }
            if (stopping) return
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法启动麦克风。" }
            val buffer = ByteArray(4096)
            val capacity = MOBILE_REPLY_LIMIT - 44
            var lastSample = SystemClock.elapsedRealtime()
            do {
                val count = recorder.read(buffer, 0, minOf(buffer.size, capacity - sampleBytes), AudioRecord.READ_NON_BLOCKING)
                check(count >= 0 && count % 2 == 0) { "麦克风已断开或返回无效采样。" }
                if (count > 0) {
                    output.write(buffer, 0, count)
                    sampleBytes += count
                    lastSample = SystemClock.elapsedRealtime()
                } else if (!stopping) {
                    check(SystemClock.elapsedRealtime() - lastSample < 5000) { "麦克风没有返回采样。" }
                    Thread.sleep(12)
                }
            } while (!stopping && sampleBytes < capacity)
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally {
                recorder.release()
            }
        }
    }
}
