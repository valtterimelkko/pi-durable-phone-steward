package com.example.phonesteward.e2e

import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Flow 4, Rules: edit, save (the PUT carries the edit), conflicts, validation and "Reset view". */
@RunWith(AndroidJUnit4::class)
class Flow4RulesTest : E2eBase() {
    override val flow = "rules"

    private fun land() {
        launchApp()
        pairAndLand()
        openTab("Rules")
        waitForText("Photo window")
        waitForText("90 days")
    }

    private fun plus(index: Int) = click(compose.onAllNodesWithText("+")[index])

    @Test
    fun editAndSaveSendsTheEditWithTheRuleVersion() {
        land()
        shot("before")
        compose.onAllNodes(isToggleable())[0].performClick() // Rule enabled
        plus(0) // reminder 90 -> 105
        plus(1) // cut-off 120 -> 135
        compose.waitForIdle()
        waitForText("105 days")
        waitForText("135 days")
        shot("edited")
        click(tag("save-rules"))
        waitForText("Rules saved.")
        shot("saved")
        val puts = fake.requestsTo("PUT", "/v1/rules")
        assertEquals(1, puts.size)
        val body = puts[0].json
        assertEquals("3", body["ruleVersion"]!!.jsonPrimitive.content)
        val window = body["photoWindow"]!!.jsonObject
        assertEquals("true", window["enabled"]!!.jsonPrimitive.content)
        assertEquals("105", window["reminderDays"]!!.jsonPrimitive.content)
        assertEquals("135", window["cutoffDays"]!!.jsonPrimitive.content)
        assertEquals("Keep", window["keepMarker"]!!.jsonPrimitive.content)
        // The server's new version is shown.
        waitForText("4")
        assertEquals(4, fake.rules.ruleVersion)
    }

    @Test
    fun resetViewPutsTheSavedValuesBackWithoutAnyRequest() {
        land()
        plus(0)
        waitForText("105 days")
        clickText("Reset view")
        waitForText("90 days")
        assertTrue("Reset view must not call the server", fake.requestsTo("PUT", "/v1/rules").isEmpty())
        shot("reset")
    }

    @Test
    fun aReminderPastTheCutoffIsExplainedAndNotSent() {
        land()
        // Raise the reminder to the cut-off age (90 -> 120).
        plus(0); plus(0)
        waitForText("120 days", timeoutMs = 5_000) // reminder 120 and cut-off 120 both read 120
        waitForText("The reminder must come before the cut-off.")
        shot("invalid")
        click(tag("save-rules"))
        Thread.sleep(800)
        assertTrue("an invalid rule never reaches the server", fake.requestsTo("PUT", "/v1/rules").isEmpty())
    }

    @Test
    fun aRuleChangedElsewhereIsReportedCalmly() {
        land()
        plus(0)
        fake.rulesConflictOnNextPut = true
        click(tag("save-rules"))
        waitForText("The rule changed elsewhere. Review and try again.")
        shot("conflict")
    }
}
