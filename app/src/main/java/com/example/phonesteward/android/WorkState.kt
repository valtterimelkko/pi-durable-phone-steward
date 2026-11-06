package com.example.phonesteward.android

import android.content.Context
import androidx.work.WorkManager
import com.example.phonesteward.logic.obs.Diagnostics
import com.example.phonesteward.net.DiagnosticsWorker

/**
 * O6 (A6): reads the WorkManager state of the unique periodic sync work.
 * `NONE` when nothing is scheduled; a failed read is reported (O5) and
 * answers `NONE` — never invented state.
 */
object WorkState {

    fun periodicState(context: Context, workName: String, lastRunMs: Long): DiagnosticsWorker = try {
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get()
        val info = infos.firstOrNull()
        DiagnosticsWorker(
            state = info?.state?.name ?: "NONE",
            lastRunMs = lastRunMs,
            runAttemptCount = info?.runAttemptCount ?: 0,
        )
    } catch (e: Exception) {
        Diagnostics.error("diag.worker_state", e)
        DiagnosticsWorker(state = "NONE", lastRunMs = lastRunMs, runAttemptCount = 0)
    }
}
