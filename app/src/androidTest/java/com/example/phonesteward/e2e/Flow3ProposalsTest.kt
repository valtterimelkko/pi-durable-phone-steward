package com.example.phonesteward.e2e

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.testlab.ScreenFixtures

/**
 * Flow 3, Proposals: cards, ticking, approve and reject, the one Android confirmation of
 * `clear_all_caches` (tapped with UiAutomator), and every result the owner can see.
 */
@RunWith(AndroidJUnit4::class)
class Flow3ProposalsTest : E2eBase() {
    override val flow = "proposals"

    private val pkg get() = context.packageName

    private fun land(vararg seeded: com.example.phonesteward.net.Proposal) {
        seeded.forEach(fake::addProposal)
        launchApp()
        pairAndLand()
        openTab("Proposals")
        waitForTag("proposals-list")
    }

    private fun grantForCacheClear() {
        shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        shell("appops set $pkg GET_USAGE_STATS allow")
    }

    @Test
    fun severalCardsShowTheReviewLineAndTheBadge() {
        land(ScreenFixtures.cacheProposal, ScreenFixtures.uninstallProposal, ScreenFixtures.mediaProposal)
        waitForText("Clear temporary files")
        shot("list")
        // The badge on the Proposals tab counts the open proposals.
        eventually("badge shows 3") {
            compose.onNode(androidx.compose.ui.test.hasText("3") and hasAnyAncestor(hasTestTag("proposals-badge")), useUnmergedTree = true).assertExists(); true
        }
        // N15: every visible card says which review it comes from.
        val stamps = compose.onAllNodesWithText("From the review of", substring = true).fetchSemanticsNodes()
        assertTrue("at least one review line on screen", stamps.isNotEmpty())
        val line = stamps[0].config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("review line looks like 'From the review of 7 Oct 19:57' but was '$line'", Regex("From the review of \\d{1,2} \\w{3} \\d{2}:\\d{2}").matches(line))
        // The cache card says plainly that one Android confirmation clears every app's cache.
        assertTrue(hasText("Clearing temporary files asks Android ONCE", substring = true))
        // The uninstall card's note must not send the owner to Storage & cache.
        compose.onNodeWithTagScroll("proposals-list", "Remove two apps you have not opened this year")
        shot("list-uninstall")
        assertFalse("uninstall cards must not talk about cache screens", hasText("Storage & cache", substring = true))
    }

    @Test
    fun rejectRecordsTheDecisionAndShowsTheResult() {
        land(ScreenFixtures.uninstallProposal)
        waitForText("Remove two apps you have not opened this year")
        shot("reject-before")
        click(tag("reject-button"))
        waitForText("Proposal rejected.")
        shot("reject-after")
        val decisions = fake.requestsTo("POST", "/v1/proposals/pr-apps/decision")
        assertEquals(1, decisions.size)
        assertEquals("reject", decisions[0].str("decision"))
        eventually("the card leaves the list") { hasText("No open proposals") }
        assertEquals("rejected", fake.statusOf("pr-apps"))
    }

    @Test
    fun anUntickedItemIsNotApproved() {
        land(ScreenFixtures.mediaProposal)
        waitForText("Move old screen recordings to the trash")
        // Untick the second recording, then approve.
        clickText("screen_recording_2025_11_09.mp4", substring = true)
        shot("untick")
        click(tag("approve-button"))
        waitForText("This file changed since the review", substring = true)
        shot("untick-result")
        val decisions = fake.requestsTo("POST", "/v1/proposals/pr-media/decision")
        assertEquals(1, decisions.size)
        assertEquals("approve", decisions[0].str("decision"))
        val ids = decisions[0].json["itemIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("only the ticked item travels", listOf("it-m1"), ids)
        // The phone checked its authority and reported only the ticked item; the unticked one is already `kept` in the decision (S12).
        eventually("execution reported") { fake.requestsTo("POST", "/v1/proposals/pr-media/execution").isNotEmpty() }
        val outcomes = fake.requestsTo("POST", "/v1/proposals/pr-media/execution")[0].json["items"]!!.jsonArray
            .associate { it.jsonObject["itemId"]!!.jsonPrimitive.content to it.jsonObject["outcome"]!!.jsonPrimitive.content }
        assertFalse("an unticked item is not reported at all (S12)", outcomes.containsKey("it-m2"))
        assertTrue("it-m1 was not done (no such media row on this device)", outcomes["it-m1"] != "done")
        // 0.2.4: the ticked file was refused at the identity check (no such row here), so the notice says nothing was
        // deleted and why, rather than the retry line.
        assertEquals(
            "This file changed since the review, so nothing was deleted. The steward will propose it again.",
            resultText(),
        )
    }

    @Test
    fun clearAllCachesShowsAndroidsOneConfirmationThenTheFreedResult() {
        grantForCacheClear()
        // Something to free: a file in this app's own cache.
        File(context.cacheDir, "e2e-junk.bin").writeBytes(ByteArray(6 * 1024 * 1024) { 7 })
        land(ScreenFixtures.cacheProposal)
        waitForText("Clear temporary files")
        shot("cache-before")
        click(tag("approve-button"))
        // Android's one confirmation, in its own UI.
        waitForPackageNot(pkg, 30_000)
        eventually("Android's clear-cache confirmation") { hasSystemText("Clear") && hasSystemText("Cancel") }
        shot("cache-android-confirmation")
        waitSystemText("Clear").click()
        waitForText("Done: every app's cache cleared", substring = true, timeoutMs = 60_000)
        shot("cache-result")
        val text = resultText()
        assertTrue("result names what was freed: '$text'", Regex("about [\\d.,]+ ?[KMG]?B freed\\.").containsMatchIn(text))
        val report = fake.requestsTo("POST", "/v1/proposals/pr-cache/execution")
        assertEquals(1, report.size)
        val item = report[0].json["items"]!!.jsonArray[0].jsonObject
        assertEquals("done", item["outcome"]!!.jsonPrimitive.content)
        assertEquals("executed", fake.statusOf("pr-cache"))
        eventually("the executed proposal leaves the list") { hasText("No open proposals") }
    }

    @Test
    fun cancellingTheConfirmationReportsCancelledAndKeepsTheProposal() {
        grantForCacheClear()
        land(ScreenFixtures.cacheProposal)
        waitForText("Clear temporary files")
        click(tag("approve-button"))
        waitForPackageNot(pkg, 30_000)
        eventually("Android's clear-cache confirmation") { hasSystemText("Cancel") }
        waitSystemText("Cancel").click()
        waitForText("Nothing was done — approve again to retry.", timeoutMs = 60_000)
        shot("cache-cancelled")
        val item = fake.requestsTo("POST", "/v1/proposals/pr-cache/execution")[0].json["items"]!!.jsonArray[0].jsonObject
        assertEquals("cancelled", item["outcome"]!!.jsonPrimitive.content)
        // A6.1: nothing done returns the proposal to open, so the owner can approve again.
        eventually("the proposal is back in the list") { hasText("Clear temporary files") && hasTag("approve-button") }
        assertEquals("open", fake.statusOf("pr-cache"))
    }

    @Test
    fun aSupersededProposalSaysSoAndLeavesTheList() {
        land(ScreenFixtures.mediaProposal)
        waitForText("Move old screen recordings to the trash")
        fake.supersedeNextDecision = true
        click(tag("approve-button"))
        waitForText("This proposal is no longer open.")
        shot("superseded")
        assertEquals(1, fake.requestsTo("POST", "/v1/proposals/pr-media/decision").size)
        // The stale card must not stay behind to be approved again.
        eventually("the stale card is gone") { !hasTag("approve-button") }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithTagScroll(listTag: String, text: String) {
    onNode(hasTestTag(listTag)).performScrollToNode(hasText(text))
}
