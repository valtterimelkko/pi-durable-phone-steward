package com.example.phonesteward.logic.shizuku

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.logic.ActionResult
import com.example.phonesteward.logic.exec.AllCachesOutcome
import com.example.phonesteward.logic.exec.ExecutionNotice
import com.example.phonesteward.logic.exec.ProposalExecutionPlanner
import com.example.phonesteward.net.DecisionCheckResponse
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem

/** A10.2 proposal kinds `trim_caches`, `disable_app`, `enable_app`: planning, outcomes and the owner's words. */
class ShizukuPlannerTest {

    private val installed = setOf("com.example.puzzle", "com.example.collage", "com.android.systemui", "com.invented.keyboard")
    private val neverTouch = NeverTouch(keyboardInUse = { "com.invented.keyboard" }, launcher = { null })

    private fun planner(ready: Boolean = true) = ProposalExecutionPlanner(
        lookup = { null }, nowMillis = { 5_000L },
        shizukuReady = { ready }, isInstalled = { it in installed }, neverTouch = neverTouch,
    )

    private fun app(pkg: String): JsonObject = buildJsonObject { put("packageName", pkg); put("label", "Invented") }
    private fun apps() = buildJsonObject {
        put("apps", buildJsonArray {
            add(buildJsonObject { put("packageName", "com.example.puzzle"); put("label", "Puzzle"); put("cacheBytes", 400_000_000L) })
        })
    }

    private fun proposal(vararg items: ProposalItem) = Proposal(proposalId = "pr-1", title = "t", items = items.toList())
    private fun decision(vararg ids: String) = DecisionCheckResponse(valid = true, proposalId = "pr-1", approvedItemIds = ids.toList(), expiresAtMs = 9_000)

    // ---- trim_caches ----

    @Test
    fun `trim_caches with Shizuku ready is planned as the all-caches clear through the shell`() {
        val plan = planner().plan(proposal(ProposalItem("t1", "trim_caches", apps())), "dc-1", decision("t1"))
        val item = plan.allCaches.single()
        assertEquals("t1", item.itemId)
        assertTrue("it runs through Shizuku, not Android's dialog", item.viaShizuku)
        assertTrue(plan.staticOutcomes.isEmpty())
    }

    @Test
    fun `trim_caches without Shizuku fails with a plain reason and nothing is planned`() {
        val plan = planner(ready = false).plan(proposal(ProposalItem("t1", "trim_caches", apps())), "dc-1", decision("t1"))
        assertTrue(plan.allCaches.isEmpty())
        assertEquals("failed", plan.staticOutcomes.single().outcome)
        assertTrue(plan.details["t1"]!!.contains("Shizuku is not set up"))
        val notice = ExecutionNotice.result(proposal(ProposalItem("t1", "trim_caches", apps())), plan, plan.staticOutcomes, null)
        assertTrue(notice.text, notice.text.contains("Shizuku is not set up"))
        assertEquals(ActionResult.Kind.ERROR, notice.kind)
    }

    @Test
    fun `a trim_caches that worked reads like the all-caches clear`() {
        val p = proposal(ProposalItem("t1", "trim_caches", apps()))
        val pl = planner()
        val plan = pl.plan(p, "dc-1", decision("t1"))
        val verdict = AllCachesOutcome.Verdict("done", null, 1_400_000_000L)
        val out = pl.outcomes(plan, null, emptyMap(), emptyMap(), verdict)
        assertEquals("done", out.single().outcome)
        assertEquals(1_400_000_000L, out.single().bytesFreed)
        assertEquals("Done: every app's cache cleared — about 1.4 GB freed.", ExecutionNotice.result(p, plan, out, verdict).text)
    }

    // ---- disable_app and enable_app ----

    @Test
    fun `disable_app is planned for an installed, ordinary app`() {
        val plan = planner().plan(proposal(ProposalItem("d1", "disable_app", app("com.example.puzzle"))), "dc-1", decision("d1"))
        val s = plan.appStates.single()
        assertEquals("com.example.puzzle", s.packageName)
        assertFalse(s.enable)
    }

    @Test
    fun `enable_app is planned too`() {
        val plan = planner().plan(proposal(ProposalItem("e1", "enable_app", app("com.example.puzzle"))), "dc-1", decision("e1"))
        assertTrue(plan.appStates.single().enable)
    }

    @Test
    fun `disable_app refuses the never-touch list, a missing app and a bad name`() {
        val items = listOf("com.android.systemui", "com.invented.keyboard", "com.example.missing", "com.example;reboot")
            .mapIndexed { i, pkg -> ProposalItem("d$i", "disable_app", app(pkg)) }
        val plan = planner().plan(proposal(*items.toTypedArray()), "dc-1", decision("d0", "d1", "d2", "d3"))
        assertTrue(plan.appStates.isEmpty())
        assertEquals(List(4) { "failed" }, plan.staticOutcomes.map { it.outcome })
    }

    @Test
    fun `enable_app is allowed for a never-touch package, since enabling cannot break the phone`() {
        val plan = planner().plan(proposal(ProposalItem("e1", "enable_app", app("com.android.systemui"))), "dc-1", decision("e1"))
        assertEquals(1, plan.appStates.size)
    }

    @Test
    fun `without Shizuku an app-state item fails and the notice says so`() {
        val p = proposal(ProposalItem("d1", "disable_app", app("com.example.puzzle")))
        val pl = planner(ready = false)
        val plan = pl.plan(p, "dc-1", decision("d1"))
        assertTrue(plan.appStates.isEmpty())
        assertEquals("failed", plan.staticOutcomes.single().outcome)
        val notice = ExecutionNotice.result(p, plan, plan.staticOutcomes, null)
        assertEquals(
            "Shizuku is not set up, so nothing was changed. Open the Status tab to set it up, then approve again.",
            notice.text,
        )
    }

    @Test
    fun `app-state outcomes follow what really changed`() {
        val p = proposal(
            ProposalItem("d1", "disable_app", app("com.example.puzzle")),
            ProposalItem("d2", "disable_app", app("com.example.collage")),
        )
        val pl = planner()
        val plan = pl.plan(p, "dc-1", decision("d1", "d2"))
        val out = pl.outcomes(plan, null, emptyMap(), emptyMap(), appStateResults = mapOf("d1" to true, "d2" to false))
        assertEquals(mapOf("d1" to "done", "d2" to "failed"), out.associate { it.itemId to it.outcome })
        assertNull(out.first().bytesFreed)
    }

    @Test
    fun `the notice counts the apps that changed`() {
        fun note(vararg done: Boolean): ActionResult {
            val items = done.indices.map { ProposalItem("d$it", "disable_app", app(if (it == 0) "com.example.puzzle" else "com.example.collage")) }
            val p = proposal(*items.toTypedArray())
            val pl = planner()
            val plan = pl.plan(p, "dc-1", decision(*items.map { it.itemId }.toTypedArray()))
            val out = pl.outcomes(plan, null, emptyMap(), emptyMap(), appStateResults = done.indices.associate { "d$it" to done[it] })
            return ExecutionNotice.result(p, plan, out, null)
        }
        assertEquals("Disabled the app.", note(true).text)
        assertEquals("Disabled both apps.", note(true, true).text)
        assertEquals("Disabled 1 of 2 apps. The other could not be changed — is Shizuku still running?", note(true, false).text)
        assertEquals("No app was changed — is Shizuku still running? Approve again to retry.", note(false, false).text)
        assertEquals(ActionResult.Kind.SUCCESS, note(true).kind)
        assertEquals(ActionResult.Kind.ERROR, note(false, false).kind)
    }

    @Test
    fun `enabling reads as enabled`() {
        val p = proposal(ProposalItem("e1", "enable_app", app("com.example.puzzle")))
        val pl = planner()
        val plan = pl.plan(p, "dc-1", decision("e1"))
        val out = pl.outcomes(plan, null, emptyMap(), emptyMap(), appStateResults = mapOf("e1" to true))
        assertEquals("Enabled the app.", ExecutionNotice.result(p, plan, out, null).text)
    }
}
