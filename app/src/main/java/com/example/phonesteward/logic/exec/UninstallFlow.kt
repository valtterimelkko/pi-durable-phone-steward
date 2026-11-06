package com.example.phonesteward.logic.exec

/** The report for one `uninstall_app` item. */
object UninstallOutcome {
    data class Verdict(val outcome: String, val resultCode: Int?, val bytesFreed: Long?)
}

/**
 * One app's uninstall, judged by what is on the phone afterwards (0.2.4).
 *
 * ACTION_DELETE gives no result code at all: it answered "cancelled" even when the owner pressed OK
 * (live, 2026-10-08, 15 apps). The app now launches ACTION_UNINSTALL_PACKAGE with EXTRA_RETURN_RESULT
 * (RESULT_OK after the removal, RESULT_CANCELED on Cancel, RESULT_FIRST_USER on failure), but the code
 * is still only recorded; what is on the phone decides:
 * - the package is already gone before the dialog (the owner removed it by hand): `done`, no dialog;
 * - after the dialog the package is gone: `done`, `bytesFreed` = the size read before, or null;
 * - still installed after the dialog (and a short settle time, because Android finishes the removal
 *   a moment after its screen closes): `cancelled`.
 *
 * Pure JVM: "is it installed?", the size reading and the sleep are injected.
 */
class UninstallFlow(
    private val isInstalled: (String) -> Boolean,
    private val sizeBytes: (String) -> Long?,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val pollMs: Long = 150,
    /** Wait this long for the removal to show after Android said "cancelled". */
    private val settleAfterCancelMs: Long = 1_500,
    /** After RESULT_OK (the removal has finished, but a big app can still show for a moment): poll until it is gone. */
    private val settleAfterOkMs: Long = 10_000,
    /** ...and this long after any other answer (RESULT_FIRST_USER is a failed removal). */
    private val settleOtherwiseMs: Long = 5_000,
) {

    fun run(packageName: String, launch: () -> Int?): UninstallOutcome.Verdict {
        if (!isInstalled(packageName)) return UninstallOutcome.Verdict("done", null, null)
        val size = try {
            sizeBytes(packageName)
        } catch (_: Exception) {
            null
        }
        val code = try {
            launch()
        } catch (_: Exception) {
            null
        }
        val settleMs = when (code) {
            null -> 0L
            ActivityResults.CANCELLED -> settleAfterCancelMs
            ActivityResults.OK -> settleAfterOkMs
            else -> settleOtherwiseMs
        }
        repeat((settleMs / pollMs).toInt()) {
            if (!isInstalled(packageName)) return UninstallOutcome.Verdict("done", code, size)
            sleep(pollMs)
        }
        return if (isInstalled(packageName)) {
            UninstallOutcome.Verdict("cancelled", code, null)
        } else {
            UninstallOutcome.Verdict("done", code, size)
        }
    }
}
