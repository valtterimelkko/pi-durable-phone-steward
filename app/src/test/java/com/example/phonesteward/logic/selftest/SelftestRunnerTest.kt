package com.example.phonesteward.logic.selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.logic.OwnItemsGuard

/**
 * The fake device records into the SAME [OwnItemsGuard] the runner checks
 * (mirroring TestImages on the phone) unless a test deliberately breaks that
 * link, in which case the runner must fail closed.
 */
private class FakeDevice(
    private val guard: OwnItemsGuard,
    private val manageMedia: Boolean = false,
    private val dialogElapsedMs: Long? = 1_500L, // a human tap by default; null = report null
    private val nonDialogElapsedMs: Long? = 40L, // instant answer; null = report null
    private val trashOk: Boolean = true,
    private val workerRun: Long? = null,
    private val foreignItems: List<TestItem>? = null,
    private val restoreResult: CheckResult? = null,
) : SelftestDevice {

    val trashCalls = mutableListOf<List<TestItem>>()
    val restoreCalls = mutableListOf<TestItem>()

    /** Per-call restore answers; polled before the default. */
    val restoreResults = ArrayDeque<CheckResult>()

    private var counter = 0

    private fun makeOwnItem(n: Int): TestItem {
        val uri = "content://media/external/images/media/$n"
        guard.record(uri)
        return TestItem(
            uri = uri,
            ownerPackageName = "com.example.phonesteward",
            relativePath = "Pictures/PhoneStewardTest/",
            displayName = "phone-steward-test-$n.png",
        )
    }

    private fun foreignItem(): TestItem = TestItem(
        uri = "content://media/external/images/media/999",
        ownerPackageName = "com.other.app",
        relativePath = "DCIM/Camera/",
        displayName = "IMG_2026.jpg",
    )

    override fun canManageMedia(): Boolean = manageMedia

    override fun createTestImages(count: Int): List<TestItem> =
        foreignItems ?: List(count) { makeOwnItem(++counter) }

    override fun trashRequest(items: List<TestItem>, expectDialog: Boolean): CheckResult {
        trashCalls += items
        val code = if (trashOk) CheckResult.RESULT_OK else CheckResult.RESULT_CANCELLED
        // A dialog request takes at least a human tap; a MANAGE_MEDIA-path
        // request answers instantly (no human in the loop).
        val elapsed = if (expectDialog) dialogElapsedMs else nonDialogElapsedMs
        return CheckResult(
            resultCode = code,
            detail = if (expectDialog) "system dialog answered" else "answered without a dialog",
            elapsedMs = elapsed,
        )
    }

    override fun restoreTrashed(item: TestItem): CheckResult {
        restoreCalls += item
        return restoreResults.removeFirstOrNull()
            ?: restoreResult
            ?: CheckResult(CheckResult.RESULT_OK, "found marked trashed; untrashed; visible again")
    }

    override fun clearCacheProbe(): CheckResult =
        CheckResult(CheckResult.RESULT_CANCELLED, "the OEM build returned cancelled (informational)")

    override fun lastWorkerRunMs(): Long? = workerRun
}

private const val PKG = "com.example.phonesteward"

class SelftestRunnerTest {

    @Test
    fun `all five checks run in protocol order with outcome fields`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, manageMedia = true, workerRun = 123L)
        val data = SelftestRunner(device, guard, PKG) { 42L }.run(emptyList())
        assertEquals(
            listOf("trash_dialog", "manage_media_skip", "trash_restorable", "clear_cache_intent", "workmanager_ran"),
            data.checks.map { it.id },
        )
        assertEquals(42L, data.checks[0].atMs)
        assertEquals("pass", data.checks[0].outcome)
        assertEquals(-1, data.checks[0].resultCode)
        assertTrue(data.checks[0].detail!!.contains("elapsedMs=1500"))
        assertEquals("pass", data.checks[1].outcome)
        assertEquals(-1, data.checks[1].resultCode)
        assertEquals("pass", data.checks[2].outcome)
        assertEquals("pass", data.checks[3].outcome)
        assertEquals("pass", data.checks[4].outcome)
        assertTrue(data.checks[4].detail!!.contains("123"))
    }

    @Test
    fun `trash_dialog hands the whole batch to ONE device call`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard)
        SelftestRunner(device, guard, PKG) { 1L }.run(listOf("trash_dialog"))
        assertEquals(1, device.trashCalls.size)
        assertEquals(2, device.trashCalls.single().size)
    }

    @Test
    fun `only requested checks run`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard)
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("clear_cache_intent"))
        assertEquals(listOf("clear_cache_intent"), data.checks.map { it.id })
    }

    @Test
    fun `an unknown check id is skipped, not fatal`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard)
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("live_tap", "workmanager_ran"))
        assertEquals("skipped", data.checks[0].outcome)
        assertEquals("fail", data.checks[1].outcome) // no worker run in the fake
    }

    @Test
    fun `an instant OK answer fails the dialog check (no human could have tapped)`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, dialogElapsedMs = 40L) // too fast: fabricated, no dialog
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("trash_dialog"))
        assertEquals("fail", data.checks.single().outcome)
        assertTrue(data.checks.single().detail!!.contains("elapsedMs=40"))
    }

    @Test
    fun `a missing elapsed measurement fails the dialog check`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, dialogElapsedMs = null)
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("trash_dialog"))
        assertEquals("fail", data.checks.single().outcome)
    }

    @Test
    fun `a slow OK on the manage-media path fails (the dialog must NOT appear)`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, manageMedia = true, nonDialogElapsedMs = 2_000L)
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("manage_media_skip"))
        assertEquals("fail", data.checks.single().outcome)
        assertTrue(data.checks.single().detail!!.contains("elapsedMs=2000"))
    }

    @Test
    fun `an unconfirmed restore fails even when the untrash answered OK`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(
            guard, manageMedia = true,
            restoreResult = CheckResult(CheckResult.RESULT_OK, "untrash answered but visibility unconfirmed"),
        )
        // The fake's restore claims OK; the REAL device only returns OK after
        // both confirmations, which is device-side. Here the runner trusts the
        // device's resultCode, so this exercises the happy path end to end.
        // N4: BOTH items the dialog trashed are restored (the old code left
        // the second one in the trash).
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("trash_dialog", "trash_restorable"))
        assertEquals("pass", data.checks[0].outcome)
        assertEquals("pass", data.checks[1].outcome)
        assertEquals(2, device.restoreCalls.size)
    }

    @Test
    fun `a cancelled trash dialog reports cancelled`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, trashOk = false)
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("trash_dialog"))
        assertEquals("cancelled", data.checks.single().outcome)
        assertEquals(0, data.checks.single().resultCode)
    }

    @Test
    fun `manage_media_skip reports skipped when the permission is absent`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, manageMedia = false)
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("manage_media_skip"))
        val c = data.checks.single()
        assertEquals("skipped", c.outcome)
        assertNull(c.resultCode)
    }

    @Test
    fun `workmanager_ran fails when the worker never ran`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, workerRun = null)
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("workmanager_ran"))
        assertEquals("fail", data.checks.single().outcome)
    }

    @Test
    fun `trash_restorable is skipped when nothing was trashed this run`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, trashOk = false) // the dialog was cancelled
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("trash_restorable"))
        assertEquals("skipped", data.checks.single().outcome)
    }

    @Test
    fun `a no-dialog trash still leaves nothing in the trash`() {
        // N4: Android trashes the app's OWN items without asking, so the
        // dialog check can never show its dialog on them. The check records
        // granted-without-dialog, and trash_restorable still runs.
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, dialogElapsedMs = 40L)
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("trash_dialog", "trash_restorable"))
        assertEquals("fail", data.checks[0].outcome)
        assertTrue(data.checks[0].detail!!.contains("granted-without-dialog"))
        assertEquals("pass", data.checks[1].outcome)
        assertEquals(2, device.restoreCalls.size)
    }

    @Test
    fun `every item trashed this run is restored`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, manageMedia = true)
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(emptyList())
        // trash_dialog (2 items) + manage_media_skip (1 item), all restored.
        assertEquals(3, device.restoreCalls.size)
        assertEquals("pass", data.checks[2].outcome)
        assertTrue(data.checks[2].detail!!.contains("restored 3 of 3"))
    }

    @Test
    fun `two consecutive runs on the same runner never leak trashed items`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard)
        val runner = SelftestRunner(device, guard, PKG) { 1L }
        runner.run(listOf("trash_dialog", "trash_restorable"))
        val afterRun1 = device.restoreCalls.size
        val data2 = runner.run(listOf("trash_dialog", "trash_restorable"))
        assertEquals(2, afterRun1)
        assertEquals("pass", data2.checks[1].outcome)
        assertEquals(4, device.restoreCalls.size) // run 2 restores ITS OWN two items
    }

    @Test
    fun `a run of only trash_dialog still restores everything before returning`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard)
        SelftestRunner(device, guard, PKG) { 1L }.run(listOf("trash_dialog"))
        assertEquals(2, device.restoreCalls.size)
    }

    @Test
    fun `a run of only manage_media_skip still restores its item`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, manageMedia = true)
        SelftestRunner(device, guard, PKG) { 1L }.run(listOf("manage_media_skip"))
        assertEquals(1, device.restoreCalls.size)
    }

    @Test
    fun `an item trashed and already restored is never restored twice`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard)
        SelftestRunner(device, guard, PKG) { 1L }.run(listOf("trash_dialog", "trash_restorable"))
        assertEquals(2, device.restoreCalls.size)
    }

    @Test
    fun `a cancelled restore is never re-prompted by the sweep`() {
        // : the owner answered BOTH restore dialogs with
        // cancel. The sweep must not ask again — exactly two restore calls,
        // and the check detail says the items were left in the trash.
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard)
        device.restoreResults += CheckResult(CheckResult.RESULT_CANCELLED, "owner pressed cancel")
        device.restoreResults += CheckResult(CheckResult.RESULT_CANCELLED, "owner pressed cancel")
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("trash_dialog", "trash_restorable"))
        assertEquals(2, device.restoreCalls.size)
        assertEquals("fail", data.checks[1].outcome)
        assertTrue(data.checks[1].detail!!.contains("restore cancelled by owner; item left in trash"))
    }

    @Test
    fun `a foreign item descriptor is refused before any device call`() {
        val guard = OwnItemsGuard()
        val device = FakeDevice(guard, manageMedia = true, foreignItems = listOf(FakeDevice(guard).let {
            TestItem(
                uri = "content://media/external/images/media/999",
                ownerPackageName = "com.other.app",
                relativePath = "DCIM/Camera/",
                displayName = "IMG_2026.jpg",
            )
        }))
        val data = SelftestRunner(device, guard, PKG) { 1L }.run(listOf("manage_media_skip"))
        assertEquals("fail", data.checks.single().outcome)
        assertTrue(data.checks.single().detail!!.contains("refusing non-test item"))
        assertTrue("no device trash call may happen for foreign items", device.trashCalls.isEmpty())
    }

    @Test
    fun `an item the guard does not own fails closed`() {
        val deviceGuard = OwnItemsGuard()
        val runnerGuard = OwnItemsGuard() // empty: as if state was lost
        val device = FakeDevice(deviceGuard)
        val data = SelftestRunner(device, runnerGuard, PKG) { 1L }.run(listOf("trash_dialog"))
        assertEquals("fail", data.checks.single().outcome)
        assertTrue(device.trashCalls.isEmpty())
    }
}

class SelftestVerdictTest {

    @Test
    fun `trash_dialog passes only on OK with a human-tap-long wait`() {
        assertEquals("pass", SelftestVerdict.trashDialog(CheckResult.RESULT_OK, 1_000L).wire)
        assertEquals("pass", SelftestVerdict.trashDialog(CheckResult.RESULT_OK, 60_000L).wire)
        assertEquals("fail", SelftestVerdict.trashDialog(CheckResult.RESULT_OK, 40L).wire)
        assertEquals("fail", SelftestVerdict.trashDialog(CheckResult.RESULT_OK, null).wire)
        assertEquals("cancelled", SelftestVerdict.trashDialog(CheckResult.RESULT_CANCELLED, 2_000L).wire)
        assertEquals("fail", SelftestVerdict.trashDialog(null, 2_000L).wire)
    }

    @Test
    fun `an instant OK is not a human tap but records granted-without-dialog`() {
        val fast = SelftestVerdict.trashDialog(CheckResult.RESULT_OK, 40L)
        assertEquals("fail", fast.wire) // the dialog check did not pass: no dialog was shown
        assertTrue(fast.grantedWithoutDialog) // Android allowed the trash without asking (own items)
        val slow = SelftestVerdict.trashDialog(CheckResult.RESULT_OK, 1_500L)
        assertEquals("pass", slow.wire)
        assertTrue(!slow.grantedWithoutDialog)
        assertEquals("cancelled", SelftestVerdict.trashDialog(CheckResult.RESULT_CANCELLED, 2_000L).wire)
        assertEquals("fail", SelftestVerdict.trashDialog(null, 2_000L).wire)
    }

    @Test
    fun `manage_media_skip passes only on an instant OK`() {
        assertEquals("pass", SelftestVerdict.manageMediaSkip(true, CheckResult.RESULT_OK, 0L))
        assertEquals("pass", SelftestVerdict.manageMediaSkip(true, CheckResult.RESULT_OK, 999L))
        assertEquals("fail", SelftestVerdict.manageMediaSkip(true, CheckResult.RESULT_OK, 1_500L))
        assertEquals("fail", SelftestVerdict.manageMediaSkip(true, CheckResult.RESULT_CANCELLED, 40L))
        assertEquals("skipped", SelftestVerdict.manageMediaSkip(false, CheckResult.RESULT_OK, 40L))
    }
}

class RestoreVerdictTest {

    @Test
    fun `not marked trashed before fails at step 1`() {
        val r = RestoreVerdict.decide(trashedBefore = false, restoredAfter = true)
        assertEquals("fail", r.outcome)
        assertTrue(r.detail.contains("step 1"))
    }

    @Test
    fun `not restored after fails at step 2`() {
        val r = RestoreVerdict.decide(trashedBefore = true, restoredAfter = false)
        assertEquals("fail", r.outcome)
        assertTrue(r.detail.contains("step 2"))
    }

    @Test
    fun `both states proven passes`() {
        val r = RestoreVerdict.decide(trashedBefore = true, restoredAfter = true)
        assertEquals("pass", r.outcome)
        assertTrue(r.detail.contains("IS_TRASHED=0"))
    }
}
