package com.example.phonesteward.e2e

import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.put
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flow C1, context (A10.9): the footer's "Context NN %", the Chat menu's Compact now and Start fresh (one question), the
 * typed `/compact` and `/new`, the fresh-start divider, and a busy steward.
 */
@RunWith(AndroidJUnit4::class)
class FlowC1ContextTest : E2eBase() {
    override val flow = "context"

    private val divider = "Fresh start: the steward now works from its records, not this conversation."

    private fun land() {
        launchApp()
        pairAndLand()
        waitForText("Context 42 %")
    }

    private fun menu(item: String) {
        clickTag("chat-menu")
        waitForText(item)
        clickText(item)
    }

    private fun type(text: String) {
        compose.onNode(androidx.compose.ui.test.hasTestTag("chat-input")).performTextInput(text)
        compose.waitForIdle()
        clickTag("chat-send")
    }

    @Test
    fun compactNowStartsCompactionAndTheFooterFollows() {
        land()
        shot("menu-closed")
        clickTag("chat-menu")
        waitForText("Compact now")
        waitForText("Start fresh")
        shot("menu")
        clickText("Compact now")
        eventually("the route was called") { fake.requestsTo("POST", "/v1/chat/compact").isNotEmpty() }
        waitForText("Compacting the steward's memory of this conversation. The chat itself stays as it is.")
        eventually("the footer shows the smaller context") { hasText("Context 14 %") }
        shot("compacted")
        assertTrue(fake.requestsTo("POST", "/v1/chat/compact").single().body.contains("requestId"))
    }

    @Test
    fun startFreshAsksOnceThenDrawsTheDivider() {
        land()
        fake.say("user", "What is eating my storage?")
        fake.say("assistant", "About 38.7 GB is free.")
        waitForText("About 38.7 GB is free.")
        menu("Start fresh")
        waitForTag("confirm-dialog")
        waitForText("Start fresh?")
        shot("fresh-question")
        assertTrue("nothing is sent before the answer", fake.requestsTo("POST", "/v1/chat/fresh").isEmpty())
        clickTag("confirm-yes")
        eventually("the route was called") { fake.requestsTo("POST", "/v1/chat/fresh").isNotEmpty() }
        waitForText(divider)
        waitForTag("chat-divider")
        assertTrue("the chat above the divider stays", hasText("About 38.7 GB is free."))
        eventually("the footer shows the small context") { hasText("Context 2 %") }
        shot("fresh-divider")
    }

    @Test
    fun decliningStartFreshSendsNothing() {
        land()
        menu("Start fresh")
        waitForTag("confirm-dialog")
        clickTag("confirm-no")
        waitForNoText("Start fresh?")
        Thread.sleep(500)
        assertTrue(fake.requestsTo("POST", "/v1/chat/fresh").isEmpty())
        assertFalse(hasTag("chat-divider"))
    }

    @Test
    fun typedCommandsDoWhatTheMenuDoes() {
        land()
        type("/compact")
        eventually("compaction started") { fake.requestsTo("POST", "/v1/chat/compact").isNotEmpty() }
        assertTrue("a command is not sent as a message", fake.requestsTo("POST", "/v1/chat").isEmpty())
        type("/new")
        waitForTag("confirm-dialog")
        waitForText("Start fresh?")
        clickTag("confirm-yes")
        eventually("fresh start called") { fake.requestsTo("POST", "/v1/chat/fresh").isNotEmpty() }
        waitForText(divider)
        assertTrue(fake.requestsTo("POST", "/v1/chat").isEmpty())
    }

    @Test
    fun aBusyStewardRefusesAndSaysSo() {
        land()
        fake.agentBusy = true
        menu("Compact now")
        waitForText("The steward is working. Try again when it has finished.")
        shot("busy")
        assertEquals("the call went out and was refused", 1, fake.requestsTo("POST", "/v1/chat/compact").size)
    }

    @Test
    fun theDividerSurvivesTheHistoryReload() {
        fake.say("user", "Before")
        fake.emit("context", kotlinx.serialization.json.buildJsonObject {
            put("kind", kotlinx.serialization.json.JsonPrimitive("fresh_start"))
            put("text", kotlinx.serialization.json.JsonPrimitive(divider))
            put("atMs", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
        })
        fake.say("user", "After")
        launchApp()
        pairAndLand()
        waitForText(divider)
        waitForText("After")
        shot("history")
    }
}
