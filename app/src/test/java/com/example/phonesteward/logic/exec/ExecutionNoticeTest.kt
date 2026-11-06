package com.example.phonesteward.logic.exec

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.example.phonesteward.logic.ActionResult
import com.example.phonesteward.net.DecisionCheckResponse
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem

/**
 * The owner-facing line after an approval (N16): accurate per kind, plain words. 0.2.4: an
 * uninstall batch says how many apps went; an approval whose every file failed before any
 * dialog says nothing was deleted and why (it used to read like silence).
 */
class ExecutionNoticeTest {

    private val now = 5_000L

    private fun trashTarget(id: Int): JsonObject = buildJsonObject {
        put("mediaId", "external_primary:images:$id")
        put("relativePath", "DCIM/Camera/")
        put("displayName", "IMG_$id.jpg")
        put("sizeBytes", 10)
    }

    private fun pkgTarget(pkg: String): JsonObject = buildJsonObject { put("packageName", pkg) }

    private val matching = LiveMediaRow(
        isFavourite = false, inKeep = false, sizeBytes = 10,
        relativePath = "DCIM/Camera/", displayName = "IMG_1.jpg",
    )

    private fun run(
        items: List<ProposalItem>,
        lookup: MediaLookup = MediaLookup { null },
        ticked: List<String> = items.map { it.itemId },
        uninstallVerdicts: Map<String, UninstallOutcome.Verdict> = emptyMap(),
        trashCode: Int? = ActivityResults.OK,
    ): ActionResult {
        val proposal = Proposal(proposalId = "pr-1", title = "t", items = items)
        val planner = ProposalExecutionPlanner(lookup = lookup, nowMillis = { now })
        val plan = planner.plan(
            proposal, "dc-1",
            DecisionCheckResponse(valid = true, proposalId = "pr-1", approvedItemIds = ticked, expiresAtMs = 9_000),
        )
        val outcomes = planner.outcomes(plan, trashCode, uninstallVerdicts, emptyMap(), null)
        return ExecutionNotice.result(proposal, plan, outcomes, allCachesVerdict = null)
    }

    private fun apps(n: Int) = (1..n).map { ProposalItem("u$it", "uninstall_app", pkgTarget("com.invented.app$it")) }

    private fun verdicts(vararg done: Boolean): Map<String, UninstallOutcome.Verdict> =
        done.withIndex().associate { (i, d) ->
            "u${i + 1}" to UninstallOutcome.Verdict(if (d) "done" else "cancelled", null, null)
        }

    // ---- uninstalls ----

    @Test
    fun `a mixed uninstall batch counts the apps that really went`() {
        val flags = BooleanArray(15) { it < 7 }
        val result = run(apps(15), uninstallVerdicts = verdicts(*flags))
        assertEquals("Uninstalled 7 of 15 apps.", result.text)
        assertEquals(ActionResult.Kind.INFO, result.kind)
    }

    @Test
    fun `every app gone reads as a success`() {
        val result = run(apps(15), uninstallVerdicts = verdicts(*BooleanArray(15) { true }))
        assertEquals("Uninstalled all 15 apps.", result.text)
        assertEquals(ActionResult.Kind.SUCCESS, result.kind)
    }

    @Test
    fun `no app gone says nothing was uninstalled and how to retry`() {
        val result = run(apps(3), uninstallVerdicts = verdicts(false, false, false))
        assertEquals("No apps were uninstalled — approve again to retry.", result.text)
        assertEquals(ActionResult.Kind.INFO, result.kind)
    }

    @Test
    fun `one app is worded in the singular`() {
        assertEquals("Uninstalled the app.", run(apps(1), uninstallVerdicts = verdicts(true)).text)
        assertEquals("The app was not uninstalled — approve again to retry.", run(apps(1), uninstallVerdicts = verdicts(false)).text)
    }

    @Test
    fun `only the ticked apps are counted`() {
        val result = run(apps(4), ticked = listOf("u1", "u2"), uninstallVerdicts = verdicts(true, false))
        assertEquals("Uninstalled 1 of 2 apps.", result.text)
    }

    // ---- trash that never reached a dialog ----

    @Test
    fun `every file failing the identity check says nothing was deleted and why`() {
        val items = (1..12).map { ProposalItem("t$it", "trash_media", trashTarget(it)) }
        // The live rows are gone / different: the phone refuses every item before any dialog.
        val result = run(items, lookup = MediaLookup { null }, trashCode = ActivityResults.CANCELLED)
        assertEquals(
            "These files changed since the review, so nothing was deleted. The steward will propose them again.",
            result.text,
        )
        assertEquals(ActionResult.Kind.ERROR, result.kind)
        assertFalse(result.text.contains("approve again"))
    }

    @Test
    fun `one file failing the identity check reads in the singular`() {
        val result = run(listOf(ProposalItem("t1", "trash_media", trashTarget(1))), trashCode = ActivityResults.CANCELLED)
        assertEquals(
            "This file changed since the review, so nothing was deleted. The steward will propose it again.",
            result.text,
        )
    }

    @Test
    fun `files that are now protected say so`() {
        val protectedRow = matching.copy(isFavourite = true)
        val result = run(
            listOf(ProposalItem("t1", "trash_media", trashTarget(1)), ProposalItem("t2", "trash_media", trashTarget(2))),
            lookup = MediaLookup { protectedRow },
            trashCode = ActivityResults.CANCELLED,
        )
        assertEquals(
            "These files are now protected (a favourite or marked Keep), so nothing was deleted.",
            result.text,
        )
        assertEquals(ActionResult.Kind.ERROR, result.kind)
    }

    @Test
    fun `some files refused and the rest trashed says how many went`() {
        val ok = ProposalItem("t1", "trash_media", trashTarget(1))
        val changed = ProposalItem("t2", "trash_media", trashTarget(2).let { buildJsonObject { it.forEach { (k, v) -> put(k, v) }; put("displayName", "other.jpg") } })
        val result = run(listOf(ok, changed), lookup = MediaLookup { matching }, trashCode = ActivityResults.OK)
        assertEquals("Moved 1 of 2 files to the trash. 1 changed since the review and was left alone.", result.text)
        assertEquals(ActionResult.Kind.INFO, result.kind)
    }

    @Test
    fun `a trash dialog the owner cancelled keeps the retry line`() {
        val items = listOf(ProposalItem("t1", "trash_media", trashTarget(1)))
        val result = run(items, lookup = MediaLookup { matching }, trashCode = ActivityResults.CANCELLED)
        assertEquals("Nothing was done — approve again to retry.", result.text)
        assertEquals(ActionResult.Kind.INFO, result.kind)
    }

    @Test
    fun `an accepted trash dialog says how many files went`() {
        val items = listOf(ProposalItem("t1", "trash_media", trashTarget(1)))
        val result = run(items, lookup = MediaLookup { matching }, trashCode = ActivityResults.OK)
        assertEquals("Moved 1 of 1 file to the trash.", result.text)
        assertEquals(ActionResult.Kind.SUCCESS, result.kind)
    }
}
