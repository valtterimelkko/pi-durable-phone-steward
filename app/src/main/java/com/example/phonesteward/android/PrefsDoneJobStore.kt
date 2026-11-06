package com.example.phonesteward.android

import android.content.Context
import com.example.phonesteward.logic.jobs.DoneJobStore
import com.example.phonesteward.logic.jobs.StoredResult

/**
 * Persists finished job results in a private SharedPreferences file, so a
 * killed process re-posts the stored result instead of re-executing the job.
 * Values are small JSON objects (metadata only). Thin adapter for
 * [DoneJobStore].
 */
class PrefsDoneJobStore(context: Context) : DoneJobStore {

    private val prefs =
        context.getSharedPreferences("phone_steward_done_jobs", Context.MODE_PRIVATE)

    override fun loadAll(): Map<String, StoredResult> {
        val out = linkedMapOf<String, StoredResult>()
        for ((jobId, encoded) in prefs.all) {
            val text = encoded as? String ?: continue
            try {
                out[jobId] = StoredResult.decode(text)
            } catch (_: Exception) {
                // A corrupted entry is dropped, never trusted.
            }
        }
        return out
    }

    override fun save(jobId: String, result: StoredResult) {
        prefs.edit().putString(jobId, StoredResult.encode(result)).apply()
    }

    override fun remove(jobId: String) {
        prefs.edit().remove(jobId).apply()
    }
}
