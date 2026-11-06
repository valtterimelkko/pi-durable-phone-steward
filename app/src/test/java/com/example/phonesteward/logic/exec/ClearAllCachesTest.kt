package com.example.phonesteward.logic.exec

import com.example.phonesteward.logic.ActionResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.ClearAllCachesTarget
import com.example.phonesteward.net.DecisionCheckResponse
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem
import com.example.phonesteward.net.ProtocolJson

/**
 * N6 + protocol A6: `clear_all_caches` — ONE approval, ONE Android
 * clear-temporary-files confirmation that clears EVERY app's cache. The
 * report is one item; without all-files access it fails with a short
 * reason; `clear_app_cache` (single app) keeps working.
 */
class ClearAllCachesTest {

    private val allFiles = { true }
    private val noAllFiles = { false }
    private val never = { Long.MAX_VALUE }

    private fun proposalWithAllCaches(): Proposal {
        val target = buildJsonObject {
            putJsonArray("apps") {
                add(
                    buildJsonObject {
                        put("packageName", "com.whatsapp")
                        put("label", "WhatsApp")
                        put("cacheBytes", 400_000_000L)
                    },
                )
                add(
                    buildJsonObject {
                        put("packageName", "org.telegram.messenger")
                        put("label", "Telegram")
                        put("cacheBytes", 100_000_000L)
                    },
                )
            }
        }
        return Proposal(
            proposalId = "pr-1",
            createdAtMs = 1,
            source = "agent",
            title = "Clear caches",
            reason = "two big caches",
            bytesEstimate = 500_000_000L,
            status = "open",
            items = listOf(
                ProposalItem(
                    itemId = "it-1",
                    kind = "clear_all_caches",
                    target = target,
                    bytesEstimate = 500_000_000L,
                    reason = null,
                ),
            ),
        )
    }

    private fun decision(vararg approved: String, proposalId: String = "pr-1") = DecisionCheckResponse(
        valid = true,
        proposalId = proposalId,
        approvedItemIds = approved.toList(),
        expiresAtMs = never() + 1_000,
    )

    @Test
    fun `clear_all_caches plans one action carrying the biggest caches`() {
        val planner = ProposalExecutionPlanner(lookup = { null }, nowMillis = never, allFilesGranted = allFiles)
        val plan = planner.plan(proposalWithAllCaches(), "dc-1", decision("it-1"))
        assertTrue(plan.allowed)
        assertEquals(1, plan.allCaches.size)
        assertEquals(
            listOf("com.whatsapp", "org.telegram.messenger"),
            plan.allCaches.single().apps.map { it.packageName },
        )
    }

    @Test
    fun `without all-files access the item fails with a short reason, nothing launches`() {
        val planner = ProposalExecutionPlanner(lookup = { null }, nowMillis = never, allFilesGranted = noAllFiles)
        val plan = planner.plan(proposalWithAllCaches(), "dc-1", decision("it-1"))
        assertTrue(plan.allowed) // the proposal is still executed: the item reports failed
        assertEquals(0, plan.allCaches.size)
        val out = planner.outcomes(plan, null, emptyMap(), emptyMap())
        val item = out.single()
        assertEquals("failed", item.outcome)
        assertEquals(null, item.resultCode)
        assertTrue(
            "the reason names all-files access",
            (plan.details[item.itemId] ?: "").contains("all-files"),
        )
    }

    @Test
    fun `the outcome mapping follows A6`() {
        assertEquals("done", AllCachesOutcome.decide(ActivityResults.OK, 123L).outcome)
        assertEquals(123L, AllCachesOutcome.decide(ActivityResults.OK, 123L).bytesFreed)
        assertEquals("done", AllCachesOutcome.decide(ActivityResults.OK, null).outcome)
        assertEquals(null, AllCachesOutcome.decide(ActivityResults.OK, null).bytesFreed)
        assertEquals("cancelled", AllCachesOutcome.decide(ActivityResults.CANCELLED, null).outcome)
        val failed = AllCachesOutcome.decide(7, null)
        assertEquals("failed", failed.outcome)
        assertEquals(7, failed.resultCode)
        val launchFailed = AllCachesOutcome.decide(null, null)
        assertEquals("failed", launchFailed.outcome)
        assertEquals(null, launchFailed.resultCode)
    }

    @Test
    fun `bytesFreed is the all-apps total or null, and unknown when unreadable`() {
        // one failed package read makes the pass unreadable —
        // the total is null, so bytesFreed is null and the app shows unknown.
        assertEquals(null, AllCachesBytes.total(listOf(1L, null, 2L)))
        assertEquals(null, AllCachesBytes.total(listOf(null, null)))
        assertEquals(3L, AllCachesBytes.total(listOf(1L, 2L)))
        assertEquals(0L, AllCachesBytes.total(emptyList()))
        assertEquals(400L, AllCachesBytes.freed(before = 500L, after = 100L))
        assertEquals(null, AllCachesBytes.freed(before = null, after = 100L))
        assertEquals(null, AllCachesBytes.freed(before = 500L, after = null))
        assertEquals("a grown cache never reads as negative freed", 0L, AllCachesBytes.freed(before = 100L, after = 400L))
    }

    @Test
    fun `the app shows unknown, never 0, when the reading is unreadable`() {
        val done = AllCachesOutcome.Verdict("done", ActivityResults.OK, null)
        val text = AllCachesNotice.text(done, deniedReason = null, doneCount = 1)
        assertTrue(text.contains("unknown"))
        assertTrue(!text.contains("0 B"))
        // A real figure still shows (ByteFormat rounds, so assert the shape).
        val withFigure = AllCachesNotice.text(done.copy(bytesFreed = 123_456L), null, 1)
        assertTrue(withFigure.contains("freed"))
        assertTrue(!withFigure.contains("unknown"))
        // A denial names the permission instead of pretending completion.
        val denied = AllCachesNotice.text(null, deniedReason = "all-files access is needed", doneCount = 0)
        assertTrue(denied.contains("all-files"))
        assertTrue(!denied.contains("Done:"))
    }

    @Test
    fun `the wire target parses the A6 shape`() {
        val target = ProtocolJson.decodeFromJsonElement(
            ClearAllCachesTarget.serializer(),
            proposalWithAllCaches().items.single().target.jsonObject,
        )
        assertEquals(2, target.apps.size)
        assertEquals(400_000_000L, target.apps[0].cacheBytes)
        assertEquals("WhatsApp", target.apps[0].label)
    }

    @Test
    fun `clear_app_cache keeps working for a single app`() {
        val target = buildJsonObject {
            put("packageName", "com.whatsapp")
            put("label", "WhatsApp")
        }
        val proposal = Proposal(
            proposalId = "pr-2", createdAtMs = 1, source = "agent", title = "t", reason = null,
            bytesEstimate = 0, status = "open",
            items = listOf(ProposalItem("it-9", "clear_app_cache", target, 0, null)),
        )
        val planner = ProposalExecutionPlanner(lookup = { null }, nowMillis = never, allFilesGranted = allFiles)
        val plan = planner.plan(proposal, "dc-2", decision("it-9", proposalId = "pr-2"))
        assertEquals(0, plan.allCaches.size)
        assertEquals(1, plan.clearCaches.size)
        assertEquals("com.whatsapp", plan.clearCaches.single().packageName)
    }

    @Test
    fun `N11 nothing done says so and offers a retry`() {
        val cancelled = AllCachesOutcome.Verdict("cancelled", ActivityResults.CANCELLED, null)
        assertEquals(
            "Nothing was done \u2014 approve again to retry.",
            AllCachesNotice.text(cancelled, deniedReason = null, doneCount = 0),
        )
        // A trash batch the owner cancelled has no cache verdict at all.
        assertEquals(
            "Nothing was done \u2014 approve again to retry.",
            AllCachesNotice.text(null, deniedReason = null, doneCount = 0),
        )
        val failed = AllCachesOutcome.Verdict("failed", 5, null)
        assertEquals(
            "Nothing was done \u2014 approve again to retry.",
            AllCachesNotice.text(failed, deniedReason = null, doneCount = 0),
        )
        // Something was done: the old line stays.
        assertEquals("Done: 2 action(s) completed.", AllCachesNotice.text(null, null, 2))
    }

    @Test
    fun `N16 each execution outcome is classified for the result card`() {
        val done = AllCachesOutcome.Verdict("done", ActivityResults.OK, 123_456L)
        assertEquals(ActionResult.Kind.SUCCESS, AllCachesNotice.result(done, null, 1).kind)
        assertTrue(AllCachesNotice.result(done, null, 1).text.contains("freed"))
        assertEquals(
            ActionResult.Kind.INFO,
            AllCachesNotice.result(AllCachesOutcome.Verdict("cancelled", ActivityResults.CANCELLED, null), null, 0).kind,
        )
        assertEquals(
            ActionResult.Kind.ERROR,
            AllCachesNotice.result(null, deniedReason = "all-files access is needed", doneCount = 0).kind,
        )
        assertEquals(ActionResult.Kind.SUCCESS, AllCachesNotice.result(null, null, 3).kind)
    }
}
