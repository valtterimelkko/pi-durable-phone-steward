package com.example.phonesteward.logic.selftest

import com.example.phonesteward.logic.OwnItemsGuard
import com.example.phonesteward.logic.TestItemCheck
import com.example.phonesteward.net.SelftestCheck
import com.example.phonesteward.net.SelftestData

/**
 * `phone_selftest` (protocol §3.3; R1 owner decision: no manual test loops).
 * The five checks run ONLY on the app's own test images (P1 guard:
 * owner package, Pictures/PhoneStewardTest/, phone-steward-test- prefix).
 * This pure runner owns that guarantee: every item descriptor is validated
 * against [TestItemCheck] and the [OwnItemsGuard] before any device call,
 * so a broken adapter cannot steer a check at real media. The owner only
 * taps Android's own dialogs.
 */

/** One item the device created for the test, with its MediaStore markers. */
data class TestItem(
    val uri: String,
    val ownerPackageName: String?,
    val relativePath: String?,
    val displayName: String?,
)

/**
 * What one device interaction produced, from observable signals only
 * (fix 01-5): Android's result code, the measured time from launching the
 * system dialog to its answer, and what the device genuinely observed. The
 * dialog count is NOT observable and is never reported.
 */
data class CheckResult(
    val resultCode: Int?,
    val detail: String,
    val elapsedMs: Long? = null,
) {
    companion object {
        const val RESULT_OK = -1 // android.app.Activity.RESULT_OK
        const val RESULT_CANCELLED = 0
    }
}

/**
 * The `trash_restorable` decision (fix 02-4), pure and tested: the outcome
 * is `pass` only when the row was seen marked trashed BEFORE the untrash
 * request AND seen present with IS_TRASHED = 0 after it. Every other path
 * names the failed step in its detail.
 */
object RestoreVerdict {
    data class Result(val outcome: String, val detail: String)

    fun decide(trashedBefore: Boolean, restoredAfter: Boolean): Result = when {
        !trashedBefore -> Result(
            "fail",
            "step 1 failed: trashed item not found with IS_TRASHED=1 (QUERY_ARG_MATCH_TRASHED)",
        )
        !restoredAfter -> Result(
            "fail",
            "step 2 failed: after the untrash request the row is not present with IS_TRASHED=0",
        )
        else -> Result("pass", "found marked trashed; untrashed; visible again with IS_TRASHED=0")
    }
}

/**
 * Verdict thresholds for the dialog checks (fix 01-5), pure and tested:
 * a human tap takes about a second; an instant OK means no dialog was
 * shown (the MANAGE_MEDIA path).
 */
object SelftestVerdict {
    const val HUMAN_TAP_MS = 1_000L

    /** One dialog check's verdict (N4): the protocol outcome, plus whether
     * Android allowed the trash with NO dialog (it does for the app's own
     * items, so the consent dialog can never appear on them). */
    data class DialogOutcome(val wire: String, val grantedWithoutDialog: Boolean)

    /** `trash_dialog`: pass needs RESULT_OK AND at least a human-tap-long wait. */
    fun trashDialog(resultCode: Int?, elapsedMs: Long?): DialogOutcome = when {
        resultCode == CheckResult.RESULT_OK && elapsedMs != null && elapsedMs >= HUMAN_TAP_MS ->
            DialogOutcome("pass", grantedWithoutDialog = false)
        resultCode == CheckResult.RESULT_OK ->
            DialogOutcome("fail", grantedWithoutDialog = true)
        resultCode == CheckResult.RESULT_CANCELLED -> DialogOutcome("cancelled", grantedWithoutDialog = false)
        else -> DialogOutcome("fail", grantedWithoutDialog = false)
    }

    /** `manage_media_skip`: pass needs RESULT_OK with NO human-tap-long wait. */
    fun manageMediaSkip(granted: Boolean, resultCode: Int?, elapsedMs: Long?): String = when {
        !granted -> "skipped"
        resultCode == CheckResult.RESULT_OK && elapsedMs != null && elapsedMs < HUMAN_TAP_MS -> "pass"
        else -> "fail"
    }
}

interface SelftestDevice {
    fun canManageMedia(): Boolean

    /** Creates `count` own test images and reports their markers. */
    fun createTestImages(count: Int): List<TestItem>

    /**
     * One system trash dialog for the whole batch (createTrashRequest), or
     * no dialog when `expectDialog` is false and MANAGE_MEDIA is granted.
     * The result carries Android's answer and the measured launch-to-result
     * time in [CheckResult.elapsedMs].
     */
    fun trashRequest(items: List<TestItem>, expectDialog: Boolean): CheckResult

    /**
     * Confirms the item is queryable and marked trashed (QUERY_ARG_MATCH_TRASHED),
     * restores it, and confirms it is visible again. RESULT_OK only after
     * both confirmations (fix 01-5).
     */
    fun restoreTrashed(item: TestItem): CheckResult

    /** Records what ACTION_CLEAR_APP_CACHE returned (informational). */
    fun clearCacheProbe(): CheckResult

    /** Last periodic worker run, or null when none since install. */
    fun lastWorkerRunMs(): Long?
}

class SelftestRunner(
    private val device: SelftestDevice,
    private val guard: OwnItemsGuard,
    private val appPackageName: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val allChecks = listOf(
        "trash_dialog", "manage_media_skip", "trash_restorable", "clear_cache_intent", "workmanager_ran",
    )

    fun run(requested: List<String>): SelftestData {
        // the tracking is PER RUN — a runner instance is
        // reused by the executor, so run-scoped state must not survive a run.
        val trashed = mutableListOf<TestItem>()
        val restored = mutableListOf<TestItem>()
        val attempted = mutableListOf<TestItem>()
        val cancelled = mutableListOf<TestItem>()
        val checks = (requested.ifEmpty { allChecks }).map { id ->
            runOne(id, trashed, restored, attempted, cancelled)
        }
        sweepRestore(trashed, restored, attempted)
        return SelftestData(checks)
    }

    /**
     * before the run returns, EVERY item trashed in it is
     * restored — whichever subset of checks was requested (a run of only
     * `trash_dialog`, or only `manage_media_skip`, still restores). Items a
     * `trash_restorable` check already restored are never restored twice.
     * A sweep failure is reported through O5; the returned check list is
     * unchanged.
     */
    private fun sweepRestore(
        trashed: List<TestItem>,
        restored: MutableList<TestItem>,
        attempted: MutableList<TestItem>,
    ) {
        // : an item whose restore the owner already
        // answered — WHATEVER the answer — is never re-prompted. Only items
        // no restore was attempted for are swept.
        val left = trashed.filter { it !in attempted }
        for (item in left) {
            try {
                val result = device.restoreTrashed(requireOwn(item))
                if (result.resultCode == CheckResult.RESULT_OK) {
                    restored += item
                } else {
                    com.example.phonesteward.logic.obs.Diagnostics.error(
                        "selftest.sweep",
                        IllegalStateException("sweep restore answered ${result.resultCode}"),
                    )
                }
            } catch (e: Exception) {
                com.example.phonesteward.logic.obs.Diagnostics.error("selftest.sweep", e)
            }
        }
    }

    private fun runOne(
        id: String,
        trashed: MutableList<TestItem>,
        restored: MutableList<TestItem>,
        attempted: MutableList<TestItem>,
        cancelled: MutableList<TestItem>,
    ): SelftestCheck {
        val at = nowMillis()
        if (id !in allChecks) {
            return check(id, "skipped", null, "unknown check id", at)
        }
        return try {
            when (id) {
                "trash_dialog" -> trashDialog(at, trashed)
                "manage_media_skip" -> manageMediaSkip(at, trashed)
                "trash_restorable" -> trashRestorable(at, trashed, restored, attempted, cancelled)
                "clear_cache_intent" -> clearCache(at)
                "workmanager_ran" -> workmanagerRan(at)
                else -> check(id, "skipped", null, "unknown check id", at)
            }
        } catch (e: GuardViolation) {
            check(id, "fail", null, e.message ?: "guard violation", at)
        } catch (e: Exception) {
            check(id, "fail", null, (e.message ?: e.javaClass.simpleName).take(200), at)
        }
    }

    /** The ownership gate every item must pass before any device call. */
    private fun requireOwn(item: TestItem): TestItem {
        val own = TestItemCheck.isOwnTestItem(
            item.ownerPackageName,
            item.relativePath,
            item.displayName,
            appPackageName,
        )
        if (!own) throw GuardViolation(
            "refusing non-test item: ${item.displayName ?: "?"} (${item.relativePath ?: "?"})",
        )
        guard.requireOwned(item.uri)
        return item
    }

    private fun createOwn(count: Int): List<TestItem> =
        device.createTestImages(count).map { requireOwn(it) }
            .also { if (it.size < count) throw GuardViolation("device created ${it.size} of $count test images") }

    private fun trashDialog(at: Long, trashed: MutableList<TestItem>): SelftestCheck {
        val items = createOwn(2)
        val result = device.trashRequest(items, expectDialog = true)
        val verdict = SelftestVerdict.trashDialog(result.resultCode, result.elapsedMs)
        if (result.resultCode == CheckResult.RESULT_OK) trashed += items
        val detail = if (verdict.grantedWithoutDialog) {
            "granted-without-dialog: Android allowed the trash of own items without asking; " +
                "${result.detail}; elapsedMs=${result.elapsedMs ?: "unknown"}"
        } else {
            "batch of 2; ${result.detail}; elapsedMs=${result.elapsedMs ?: "unknown"}"
        }
        return check("trash_dialog", verdict.wire, result.resultCode, detail.take(200), at)
    }

    private fun manageMediaSkip(at: Long, trashed: MutableList<TestItem>): SelftestCheck {
        if (!device.canManageMedia()) {
            return check("manage_media_skip", "skipped", null, "MANAGE_MEDIA not granted", at)
        }
        val items = createOwn(1)
        val result = device.trashRequest(items, expectDialog = false)
        val outcome = SelftestVerdict.manageMediaSkip(true, result.resultCode, result.elapsedMs)
        if (result.resultCode == CheckResult.RESULT_OK) trashed += items
        return check(
            "manage_media_skip", outcome, result.resultCode,
            "${result.detail}; elapsedMs=${result.elapsedMs ?: "unknown"}".take(200),
            at,
        )
    }

    /** Restores EVERY item trashed earlier in this run (N4: test images are
     * never left in the trash, however the trash happened). */
    private fun trashRestorable(
        at: Long,
        trashed: MutableList<TestItem>,
        restored: MutableList<TestItem>,
        attempted: MutableList<TestItem>,
        cancelled: MutableList<TestItem>,
    ): SelftestCheck {
        val pending = trashed.filter { it !in attempted }
        if (pending.isEmpty()) {
            return check(
                "trash_restorable", "skipped", null,
                "no trashed test item this run (run trash_dialog first)", at,
            )
        }
        var ok = 0
        var lastCode: Int? = null
        for (item in pending) {
            attempted += item
            val result = device.restoreTrashed(requireOwn(item))
            lastCode = result.resultCode
            if (result.resultCode == CheckResult.RESULT_OK) {
                ok += 1
                restored += item
            } else if (result.resultCode == CheckResult.RESULT_CANCELLED) {
                // : the owner said no — the item stays
                // in the trash and is never asked about again this run.
                cancelled += item
            }
        }
        val outcome = if (ok == pending.size) "pass" else "fail"
        val detail = if (cancelled.isNotEmpty()) {
            "restored $ok of ${pending.size} trashed test items; " +
                "restore cancelled by owner; item left in trash"
        } else {
            "restored $ok of ${pending.size} trashed test items"
        }
        return check("trash_restorable", outcome, lastCode, detail.take(200), at)
    }

    private fun clearCache(at: Long): SelftestCheck {
        val result = device.clearCacheProbe()
        val outcome = if (result.resultCode != null) "pass" else "fail"
        return check(
            "clear_cache_intent", outcome, result.resultCode,
            "ACTION_CLEAR_APP_CACHE returned ${result.resultCode ?: "nothing"}; ${result.detail}".take(200),
            at,
        )
    }

    private fun workmanagerRan(at: Long): SelftestCheck {
        val last = device.lastWorkerRunMs()
        val outcome = if (last != null) "pass" else "fail"
        return check(
            "workmanager_ran", outcome, null,
            last?.let { "last worker run: $it" } ?: "no worker run since install",
            at,
        )
    }

    private fun check(id: String, outcome: String, resultCode: Int?, detail: String, at: Long) =
        SelftestCheck(id = id, outcome = outcome, resultCode = resultCode, detail = detail, atMs = at)

    private class GuardViolation(message: String) : RuntimeException(message)
}
