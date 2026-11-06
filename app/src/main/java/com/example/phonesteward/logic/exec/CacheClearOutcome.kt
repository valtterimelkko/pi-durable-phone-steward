package com.example.phonesteward.logic.exec

/**
 * The cache-clearing verdict (fix 01-6), pure and tested. Android 13 has no
 * public per-app storage-page intent, so the owner opens App info and taps
 * "Clear cache" themselves; the app reads the app's cacheBytes before and
 * again when it resumes, and reports what actually happened:
 * - `done` with `bytesFreed` when the reading fell;
 * - `cancelled` when it did not fall (or a reading is missing);
 * - `failed` when the launch itself threw.
 */
object CacheClearOutcome {

    data class Verdict(val outcome: String, val bytesFreed: Long?)

    fun decide(beforeBytes: Long?, afterBytes: Long?, launched: Boolean): Verdict = when {
        !launched -> Verdict("failed", null)
        beforeBytes == null || afterBytes == null -> Verdict("cancelled", null)
        afterBytes < beforeBytes -> Verdict("done", beforeBytes - afterBytes)
        else -> Verdict("cancelled", null)
    }
}

/** What one cache-clear attempt observed (fix 01-6). */
data class CacheClearReading(
    val beforeBytes: Long?,
    val afterBytes: Long?,
    val launched: Boolean,
) {
    fun verdict(): CacheClearOutcome.Verdict =
        CacheClearOutcome.decide(beforeBytes, afterBytes, launched)
}
