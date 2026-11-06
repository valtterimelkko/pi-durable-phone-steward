package com.example.phonesteward.logic.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.4: an uninstall's outcome comes from what is on the phone afterwards, not from the
 * dialog's result code (ACTION_DELETE gives no result: it always read "cancelled", live
 * 2026-10-08). Pure JVM: "is it installed?" and "how big was it?" are injected.
 */
class UninstallFlowTest {

    private class Phone(vararg installed: String) {
        val apps = installed.toMutableSet()
        val sizes = mutableMapOf<String, Long>()
        val dialogs = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        fun flow(settleAfterCancelMs: Long = 1_000, settleOtherwiseMs: Long = 3_000, settleAfterOkMs: Long = 6_000) = UninstallFlow(
            isInstalled = { it in apps },
            sizeBytes = { sizes[it] },
            sleep = { sleeps += it },
            pollMs = 100,
            settleAfterCancelMs = settleAfterCancelMs,
            settleAfterOkMs = settleAfterOkMs,
            settleOtherwiseMs = settleOtherwiseMs,
        )
    }

    @Test
    fun `the dialog says cancelled but the app is gone - done, with the size measured before`() {
        val phone = Phone("com.old.game")
        phone.sizes["com.old.game"] = 5_000L
        val verdict = phone.flow().run("com.old.game") {
            phone.dialogs += "com.old.game"
            phone.apps -= "com.old.game" // the owner pressed OK
            ActivityResults.CANCELLED // ACTION_DELETE always answers like this
        }
        assertEquals(UninstallOutcome.Verdict("done", ActivityResults.CANCELLED, 5_000L), verdict)
    }

    @Test
    fun `the dialog answered but the app is still installed - cancelled`() {
        val phone = Phone("com.old.game")
        val verdict = phone.flow().run("com.old.game") {
            phone.dialogs += "com.old.game"
            ActivityResults.OK // even an OK does not count while the app is still there
        }
        assertEquals("cancelled", verdict.outcome)
        assertNull(verdict.bytesFreed)
    }

    @Test
    fun `an app already gone before the dialog is done with no dialog`() {
        val phone = Phone() // the owner removed it by hand
        phone.sizes["com.old.game"] = 5_000L
        var launched = false
        val verdict = phone.flow().run("com.old.game") {
            launched = true
            ActivityResults.CANCELLED
        }
        assertFalse("no dialog for an app that is not installed", launched)
        assertEquals("done", verdict.outcome)
        assertNull("nothing was measured, so nothing is claimed", verdict.bytesFreed)
        assertNull(verdict.resultCode)
    }

    @Test
    fun `an unreadable size is null, never zero`() {
        val phone = Phone("com.old.game") // no size known
        val verdict = phone.flow().run("com.old.game") {
            phone.apps -= "com.old.game"
            ActivityResults.OK
        }
        assertEquals("done", verdict.outcome)
        assertNull(verdict.bytesFreed)
    }

    @Test
    fun `a removal that lands a moment after the dialog closes still counts`() {
        val phone = Phone("com.old.game")
        phone.sizes["com.old.game"] = 70L
        // Android finishes removing the package while the app is polling.
        var polls = 0
        val flow = UninstallFlow(
            isInstalled = { pkg ->
                // First call: before the dialog. Next ones: after it.
                polls += 1
                !(polls >= 4 && pkg == "com.old.game")
            },
            sizeBytes = { phone.sizes[it] },
            sleep = { },
            pollMs = 100,
            settleAfterCancelMs = 1_000,
            settleAfterOkMs = 6_000,
            settleOtherwiseMs = 3_000,
        )
        val verdict = flow.run("com.old.game") { ActivityResults.CANCELLED }
        assertEquals(UninstallOutcome.Verdict("done", ActivityResults.CANCELLED, 70L), verdict)
    }

    @Test
    fun `a still-installed app is polled only for the settle time`() {
        val phone = Phone("com.keep")
        phone.flow(settleAfterCancelMs = 1_000).run("com.keep") { ActivityResults.CANCELLED }
        assertEquals("1000 ms in 100 ms steps", 10, phone.sleeps.size)
        assertEquals(1_000L, phone.sleeps.sum())
        phone.sleeps.clear()
        phone.flow(settleAfterOkMs = 6_000).run("com.keep") { ActivityResults.OK }
        assertEquals("after RESULT_OK it waits the longer OK settle", 6_000L, phone.sleeps.sum())
        phone.sleeps.clear()
        phone.flow(settleOtherwiseMs = 3_000).run("com.keep") { ActivityResults.FIRST_USER }
        assertEquals("any other code waits the middle settle", 3_000L, phone.sleeps.sum())
    }

    @Test
    fun `an OK answer for a big app whose removal finishes 4 seconds later is still done`() {
        // 0.2.4 correction 1: ACTION_UNINSTALL_PACKAGE answers OK only after the removal; a slow
        // phone can still show the package for a few seconds. Virtual clock: sleeping advances it.
        var clockMs = 0L
        val flow = UninstallFlow(
            isInstalled = { clockMs < 4_000 },
            sizeBytes = { 900_000_000L },
            sleep = { clockMs += it },
            pollMs = 100,
            settleAfterCancelMs = 1_500,
            settleAfterOkMs = 10_000,
            settleOtherwiseMs = 5_000,
        )
        // The first isInstalled call (before the dialog) sees it installed; the launch itself takes no time.
        val verdict = flow.run("com.big.app") { ActivityResults.OK }
        assertEquals(UninstallOutcome.Verdict("done", ActivityResults.OK, 900_000_000L), verdict)
        assertTrue("it returned as soon as the package was gone, not after the full 10 s", clockMs in 4_000..4_200)
    }

    @Test
    fun `an OK answer for an app that never goes is cancelled after at most 10 seconds`() {
        var clockMs = 0L
        val flow = UninstallFlow(
            isInstalled = { true },
            sizeBytes = { null },
            sleep = { clockMs += it },
            pollMs = 100,
            settleAfterCancelMs = 1_500,
            settleAfterOkMs = 10_000,
            settleOtherwiseMs = 5_000,
        )
        assertEquals("cancelled", flow.run("com.stuck") { ActivityResults.OK }.outcome)
        assertEquals(10_000L, clockMs)
    }

    @Test
    fun `a dialog that could not start leaves the app installed - cancelled`() {
        val phone = Phone("com.keep")
        val verdict = phone.flow().run("com.keep") { null }
        assertEquals("cancelled", verdict.outcome)
        assertTrue(phone.apps.contains("com.keep"))
    }

    @Test
    fun `the outcomes of a mixed batch follow the truth on the phone`() {
        val phone = Phone("a", "b", "c") // d was removed by hand
        phone.sizes += mapOf("a" to 10L, "b" to 20L, "c" to 30L)
        val flow = phone.flow()
        val verdicts = listOf("a", "b", "c", "d").associateWith { pkg ->
            flow.run(pkg) {
                if (pkg != "b") phone.apps -= pkg // the owner kept b
                ActivityResults.CANCELLED
            }
        }
        assertEquals(listOf("done", "cancelled", "done", "done"), verdicts.values.map { it.outcome })
        assertEquals(listOf<Long?>(10L, null, 30L, null), verdicts.values.map { it.bytesFreed })
    }
}
