package dev.polarzen.chatbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Keeps user-started local transfers foreground. The service never starts or reconstructs network work. */
internal class AndroidChatTransferService : Service() {
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val request = current?.takeIf { it.generation == intent?.getStringExtra(GENERATION) }
        // Even a late start from a dead process owes the system a foreground promotion.
        val promoted = try {
            startForeground(PROGRESS_ID, progressNotification())
            // The system can silently ignore promotion when the foreground AppOp is restricted.
            Build.VERSION.SDK_INT < 29 || (foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) != 0
        } catch (_: RuntimeException) { false }
        if (!promoted) {
            if (request != null) {
                current = null
                request.ready.complete(false)
            }
            if (current == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (request != null) {
            request.service = this
            request.ready.complete(true)
        } else if (current == null) {
            stopWhenLatest()
        }
        return START_NOT_STICKY
    }

    private fun progressNotification(): Notification {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "传输进度", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        })
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name)).setContentText("正在传输消息或附件")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE).apply {
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            }.build()
    }

    private fun stopWhenLatest() {
        if (stopSelfResult(lastStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (current?.service === this) current = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Drop the expired promotion immediately. A newer undelivered start still gets its callback.
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        if (current?.service === this) current = null
        super.onDestroy()
    }

    private class Start {
        val generation = UUID.randomUUID().toString()
        val ready = CompletableFuture<Boolean>()
        var service: AndroidChatTransferService? = null
    }

    companion object {
        private const val CHANNEL = "chat_transfers"
        private const val PROGRESS_ID = 2
        private const val GENERATION = "generation"
        // Accessed by UI/service callbacks on the main thread. Workers only wait on the future.
        private var current: Start? = null

        fun request(context: Context): CompletableFuture<Boolean> {
            current?.let { return it.ready }
            val start = Start()
            current = start
            val intent = Intent(context, AndroidChatTransferService::class.java).putExtra(GENERATION, start.generation)
            val accepted = try { context.startForegroundService(intent) != null } catch (_: RuntimeException) { false }
            if (!accepted) {
                current = null
                start.ready.complete(false)
            }
            return start.ready
        }

        fun finish() {
            val start = current ?: return
            current = null
            start.service?.stopWhenLatest()
        }
    }
}
