package dev.polarzen.chatbox

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal object WearableJobs {
    private const val PERIODIC = 0x484301
    private const val RETRY = 0x484302

    private fun job(context: Context, id: Int) = JobInfo.Builder(id,
        ComponentName(context, WearableSyncJobService::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setPersisted(true)

    fun schedule(context: Context, periodic: Boolean, retry: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (periodic && scheduler.getPendingJob(PERIODIC) == null) {
            scheduler.schedule(job(context, PERIODIC).setPeriodic(15 * 60_000L).build())
        } else if (!periodic) scheduler.cancel(PERIODIC)
        if (retry && scheduler.getPendingJob(RETRY) == null) {
            scheduler.schedule(job(context, RETRY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        } else if (!retry) scheduler.cancel(RETRY)
    }
}

class WearableSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        running[params.jobId] = scope.launch {
            val retry = !AndroidWearableSync.get(applicationContext).runSync(background = true)
            running.remove(params.jobId)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
