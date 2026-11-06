package com.example.phonesteward.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flow B1, the model picker (A10.4): the footer names the current model; tapping it opens a searchable list grouped by
 * provider, with unavailable models greyed and the reason shown, pay-as-you-go ones marked, and the one question before a
 * model that costs money.
 */
@RunWith(AndroidJUnit4::class)
class FlowB1ModelTest : E2eBase() {
    override val flow = "model"

    private fun land() {
        launchApp()
        pairAndLand()
        waitForText("GLM 5.3 Flash")
    }

    private fun openPicker() {
        clickTag("chat-model")
        waitForTag("model-picker")
        waitForText("Choose a model")
        waitForText("DeepSeek V4.1 Flash")
    }

    /**
     * The list is lazy: on a small screen the lower rows are not composed until they are scrolled into view, so a
     * test reaches a row by scrolling to it first (never assumes it is on the first screen).
     */
    private fun reveal(text: String, substring: Boolean = false) {
        tag("model-list").performScrollToNode(androidx.compose.ui.test.hasText(text, substring = substring))
        compose.waitForIdle()
    }

    /** The descriptions of every row, gathered while the list scrolls item by item from the top to the end. */
    private fun everyRowDescription(): Set<String> {
        val seen = linkedSetOf<String>()
        for (i in 0 until 80) {
            // A lazy list does not know its length (collection info says -1): scrolling past the end is the stop.
            if (runCatching { tag("model-list").performScrollToIndex(i) }.isFailure) break
            compose.waitForIdle()
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("model-row")).fetchSemanticsNodes().forEach { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { seen += it }
            }
        }
        return seen
    }

    private fun puts() = fake.requestsTo("PUT", "/v1/agent/settings")

    @Test
    fun theFooterNamesTheModelAndTheContext() {
        land()
        waitForText("Context 42 %")
        shot("footer")
        assertTrue(hasTag("chat-footer"))
    }

    @Test
    fun thePickerListsEveryModelGroupedWithReasonsAndMarks() {
        land()
        openPicker()
        shot("picker")
        assertTrue(hasText("Z.AI"))
        assertTrue(hasText("DeepSeek"))
        // The lower groups sit below the fold on a small screen: scroll to each before asserting.
        reveal("Anthropic")
        assertTrue(hasText("Anthropic"))
        reveal("Not available: the steward server has no sign-in for Anthropic.")
        assertTrue("an unavailable model says why", hasText("Not available: the steward server has no sign-in for Anthropic."))
        shot("picker-unavailable")
        val rows = everyRowDescription()
        val marked = rows.count { it.contains("Pay-as-you-go") || it.contains("Billing not known") }
        assertTrue("pay-as-you-go and unclassified models are marked (rows seen: $rows)", marked >= 3)
        assertTrue("a pay-as-you-go row", rows.any { it.contains("Pay-as-you-go") })
        assertTrue("an unclassified row", rows.any { it.contains("Billing not known") })
    }

    @Test
    fun searchNarrowsTheList() {
        land()
        openPicker()
        tag("model-search").performTextInput("deep")
        waitForNoText("GLM 5.3")
        assertTrue(hasText("DeepSeek V4.1 Flash"))
        shot("picker-search")
        tag("model-search").performTextClearance()
        tag("model-search").performTextInput("zzzz")
        waitForTag("model-none")
        shot("picker-none")
    }

    @Test
    fun aSubscriptionModelSwitchesAtOnceWithoutAQuestion() {
        land()
        openPicker()
        click(text("GLM 5.3", substring = false))
        eventually("the model was chosen") { puts().isNotEmpty() }
        assertFalse("no question for a subscription model", hasTag("confirm-dialog"))
        assertEquals("glm-5.3", puts().single().json["model"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("false", puts().single().json["confirmMetered"]!!.jsonPrimitive.content)
        eventually("the footer follows") { hasText("GLM 5.3") && !hasText("GLM 5.3 Flash") }
        assertFalse("the picker closed", hasTag("model-picker"))
        shot("switched")
    }

    @Test
    fun aPayAsYouGoModelAsksOnceAndSendsConfirmMeteredOnlyAfterYes() {
        land()
        openPicker()
        click(text("DeepSeek V4.1 Flash"))
        waitForTag("confirm-dialog")
        waitForText("This model is pay-as-you-go: each message costs money. Use DeepSeek V4.1 Flash?")
        shot("metered-question")
        assertTrue("nothing is sent before the answer", puts().isEmpty())
        clickTag("confirm-no")
        waitForNoText("Use a pay-as-you-go model?")
        assertTrue("declining changes nothing", puts().isEmpty())
        // Again, this time yes.
        clickTag("chat-model")
        waitForTag("model-picker")
        waitForText("DeepSeek V4.1 Flash")
        click(text("DeepSeek V4.1 Flash"))
        waitForTag("confirm-dialog")
        clickTag("confirm-yes")
        eventually("the choice was sent") { puts().isNotEmpty() }
        assertEquals("true", puts().single().json["confirmMetered"]!!.jsonPrimitive.content)
        eventually("the footer follows") { hasText("DeepSeek V4.1 Flash") }
        shot("metered-chosen")
    }

    @Test
    fun anUnavailableModelCannotBeChosen() {
        land()
        openPicker()
        reveal("Sonnet")
        click(text("Sonnet"))
        Thread.sleep(800)
        assertTrue(puts().isEmpty())
        assertFalse(hasTag("confirm-dialog"))
        assertTrue("the picker stays open", hasTag("model-picker"))
    }

    @Test
    fun aBusyStewardIsExplainedAndTheModelStays() {
        land()
        fake.agentBusy = true
        openPicker()
        click(text("GLM 5.3"))
        waitForText("The steward is working. Try again when it has finished.")
        shot("busy")
        assertTrue(hasText("GLM 5.3 Flash"))
    }
}
