package com.example.phonesteward.logic.exec

/**
 * The `clear_all_caches` verdicts and arithmetic (protocol A6,
 * N6). Pure and tested:
 * - `done` when Android returned RESULT_OK (one confirmation that clears
 *   EVERY app's cache), `cancelled` on RESULT_CANCELED, `failed` for any
 *   other result code — or a null one (the launch itself failed);
 * - `bytesFreed` is the sum of the listed caches' bytes before, minus
 *   after; null when the after reading is unreadable.
 */
object AllCachesOutcome {

    data class Verdict(val outcome: String, val resultCode: Int?, val bytesFreed: Long?)

    fun decide(resultCode: Int?, bytesFreed: Long?): Verdict = when (resultCode) {
        ActivityResults.OK -> Verdict("done", resultCode, bytesFreed)
        ActivityResults.CANCELLED -> Verdict("cancelled", resultCode, null)
        null -> Verdict("failed", null, null)
        else -> Verdict("failed", resultCode, null)
    }
}

/** The before/after cache-byte arithmetic over ALL installed apps. */
object AllCachesBytes {

    /**
     * the total of one all-apps pass is ALL-OR-NULL. Any
     * failed package reading makes the whole pass unreadable (`null`), so
     * `bytesFreed` becomes null and the app shows "unknown" — never a
     * partial figure presented as the total.
     */
    fun total(readings: List<Long?>): Long? =
        if (readings.any { it == null }) null else readings.filterNotNull().sum()

    /**
     * The A6 `bytesFreed`: the all-apps total before, minus after. null when
     * either pass is unreadable (the app then shows "unknown", never 0);
     * never negative (a cache that grew reads as 0 freed).
     */
    fun freed(before: Long?, after: Long?): Long? = when {
        before == null || after == null -> null
        else -> (before - after).coerceAtLeast(0L)
    }
}

/** The owner-facing result line for a clear_all_caches execution. */
object AllCachesNotice {

    /** N11: shown when the owner's approval led to no action at all. */
    const val NOTHING_DONE = "Nothing was done \u2014 approve again to retry."

    fun text(verdict: AllCachesOutcome.Verdict?, deniedReason: String?, doneCount: Int): String = when {
        verdict?.outcome == "done" && verdict.bytesFreed != null ->
            "Done: every app's cache cleared \u2014 about " +
                com.example.phonesteward.logic.ByteFormat.format(verdict.bytesFreed) + " freed."
        verdict?.outcome == "done" ->
            "Done: every app's cache cleared \u2014 space freed unknown."
        verdict != null && verdict.outcome == "failed" && verdict.resultCode == null ->
            deniedReason ?: "The cache clear could not start."
        deniedReason != null -> deniedReason
        doneCount == 0 -> NOTHING_DONE
        else -> "Done: $doneCount action(s) completed."
    }

    /** N16: the same line with its kind, for the result card on Proposals. */
    fun result(
        verdict: AllCachesOutcome.Verdict?,
        deniedReason: String?,
        doneCount: Int,
    ): com.example.phonesteward.logic.ActionResult {
        val text = text(verdict, deniedReason, doneCount)
        val kind = when {
            verdict?.outcome == "done" -> com.example.phonesteward.logic.ActionResult.Kind.SUCCESS
            verdict != null && verdict.outcome == "failed" && verdict.resultCode == null ->
                com.example.phonesteward.logic.ActionResult.Kind.ERROR
            deniedReason != null -> com.example.phonesteward.logic.ActionResult.Kind.ERROR
            doneCount == 0 -> com.example.phonesteward.logic.ActionResult.Kind.INFO
            else -> com.example.phonesteward.logic.ActionResult.Kind.SUCCESS
        }
        return com.example.phonesteward.logic.ActionResult(text, kind)
    }
}
