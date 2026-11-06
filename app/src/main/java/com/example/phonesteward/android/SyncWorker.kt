package com.example.phonesteward.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.phonesteward.logic.jobs.JobPump
import com.example.phonesteward.logic.jobs.JobTracker

/**
 * The periodic sync worker : about every 4 hours, network required.
 * Runs one pump cycle (fetch + report) and posts device status. All phone
 * work goes through the same [JobPump] as the foreground long-poll, so the
 * tracker's de-duplication holds no matter who called.
 */
class SyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = AppGraph.get(applicationContext)
        try {
            val summary = graph.jobPump.pollOnce(waitSeconds = 0)
            graph.recordWorkerRun()
            // A10.1: Steward trash folders older than 30 days go for good; the periodic worker owns that.
            purgeStewardTrash(graph)
            flushRestoreReports(graph)
            com.example.phonesteward.logic.obs.Diagnostics.event("info", "worker_ran", "ok")
            graph.deviceStatusReporter.send()
            Result.success()
        } catch (e: Exception) {
            // Offline or server down: the next periodic run retries; the
            // tracker keeps any stored results.
            com.example.phonesteward.logic.obs.Diagnostics.error("worker.run", e)
            Result.retry()
        }
    }

    private fun purgeStewardTrash(graph: AppGraph) {
        try {
            val purged = graph.stewardTrash.purgeOlderThan(TRASH_DAYS)
            if (purged.foldersRemoved > 0) {
                com.example.phonesteward.logic.obs.Diagnostics.event(
                    "info", "steward_trash_purged", "folders=${purged.foldersRemoved} bytes=${purged.bytesRemoved}",
                )
            }
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("worker.trash_purge", e)
        }
    }

    /** A11.3: the next sync sends any restore report the server did not accept at the time. */
    private fun flushRestoreReports(graph: AppGraph) {
        try {
            val sent = graph.restoreReporter().flush()
            if (sent > 0) {
                com.example.phonesteward.logic.obs.Diagnostics.event("info", "steward_trash_reported", "reports=$sent")
            }
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("worker.restore_reports", e)
        }
    }

    companion object {
        /** A10.1: a dated Steward trash folder is removed once it is more than this many days old. */
        const val TRASH_DAYS = 30
        internal const val WORK_NAME = "phone-steward-sync"

        /** Idempotent schedule; call from app start and after pairing. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
