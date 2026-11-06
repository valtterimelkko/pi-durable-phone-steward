package com.example.phonesteward.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.logic.chat.ChatLog
import com.example.phonesteward.logic.chat.ChatRows
import com.example.phonesteward.logic.chat.ChatVerbosity

/**
 * Flow 5, Chat: sending, a streamed reply (Markdown, a table, tool events with A8 summaries, run
 * start and end), the three verbosity levels, the saved setting, the keyboard and a long history.
 */
@RunWith(AndroidJUnit4::class)
class Flow5ChatTest : E2eBase() {
    override val flow = "chat"

    private val reply = """Here is what I found. About **38.7 GB is free** of 128 GB.

| App | Cache |
|---|---|
| Chrome | 612 MB |
| YouTube | 480 MB |

- Temporary files (`cache`) rebuild themselves
- Nothing in your `Keep` folder is touched"""

    /** The steward's scripted turn: run, two tool calls with A8 summaries, a Markdown reply, run end. */
    private fun scriptedTurn(f: FakeSteward) {
        // Let the server's echo of the owner's message reach the phone before anything else arrives, so the
        // test can look at the owner bubble (a long reply would scroll it out of a small screen).
        Thread.sleep(5_000)
        f.runEvent("started"); Thread.sleep(250)
        f.toolEvent("phone_snapshot", "started", "call_1", "no arguments"); Thread.sleep(250)
        f.toolEvent("phone_snapshot", "done", "call_1", "no arguments", "snapshot 3 h old", 380); Thread.sleep(250)
        f.toolEvent("storage_trend", "started", "call_2", "no arguments"); Thread.sleep(250)
        f.toolEvent("storage_trend", "done", "call_2", "no arguments", "trend available", 290); Thread.sleep(250)
        f.say("assistant", reply); Thread.sleep(250)
        f.runEvent("ended", durationMs = 4200)
    }

    /** The same turn already in the history, plus a phone result and a proposal, for the level checks. */
    private fun seedHistory() {
        val f = fake
        f.say("user", "What is eating my storage?")
        f.runEvent("started")
        f.toolEvent("phone_snapshot", "started", "call_1", "no arguments")
        f.toolEvent("phone_snapshot", "done", "call_1", "no arguments", "snapshot 3 h old", 380)
        f.toolEvent("storage_trend", "started", "call_2", "no arguments")
        f.toolEvent("storage_trend", "done", "call_2", "no arguments", "trend available", 290)
        f.say("user", "[phone result pj-1a2b3c] kind=storage_overview status=ok data={\"freeBytes\":1}")
        f.say("assistant", reply)
        f.proposalEvent("pr-cache", "Clear temporary files")
        f.runEvent("ended", durationMs = 4200)
    }

    private fun land() {
        launchApp()
        pairAndLand()
    }

    private fun rowsAt(level: ChatVerbosity) =
        ChatRows.build(ChatLog.applyAll(emptyList(), fake.events()), level)

    /** True when a node with this tag is on screen at some scroll position of the Chat list. */
    private fun shownSomewhere(tag: String, level: ChatVerbosity): Boolean {
        val rows = rowsAt(level)
        for (i in rows.indices) {
            tag("chat-list").performScrollToIndex(i)
            compose.waitForIdle()
            if (hasTag(tag)) return true
        }
        return false
    }

    private fun select(level: ChatVerbosity) {
        clickTag("verbosity-${level.name}")
        compose.waitForIdle()
        tag("verbosity-${level.name}").assertIsSelected()
    }

    @Test
    fun sendingShowsOneOwnerBubbleAndTheStreamedReplyRenders() {
        fake.onChat = { f, _ -> scriptedTurn(f) }
        land()
        val input = compose.onNode(hasSetTextAction())
        input.performTextInput("How much is free now?")
        shot("typed")
        clickTag("chat-send")
        // The bubble appears at once, from this phone.
        eventually("owner bubble") { tagCount("chat-owner-message") == 1 }
        val posts = fake.requestsTo("POST", "/v1/chat")
        assertEquals(1, posts.size)
        assertEquals("How much is free now?", posts[0].str("text"))
        assertTrue("the send carries a requestId", !posts[0].str("requestId").isNullOrBlank())
        // After the server's echo of the message there is still exactly one owner bubble.
        Thread.sleep(4_000)
        assertEquals("no duplicate after the server echo", 1, tagCount("chat-owner-message"))
        // The reply streams in: Markdown is rendered, not shown raw.
        waitForText("Chrome", timeoutMs = 30_000)
        waitForText("612 MB")
        shot("reply-medium")
        assertFalse("bold markers are not shown", hasText("**", substring = true))
        assertFalse("table pipes are not shown", hasText("|---|", substring = true))
        assertFalse("inline code ticks are not shown", hasText("`", substring = true))
        // Medium: the two tool calls are one collapsed group; run rows are hidden.
        assertTrue(shownSomewhere("chat-tool-group", ChatVerbosity.Medium))
        assertFalse(shownSomewhere("chat-run-row", ChatVerbosity.Medium))
    }

    @Test
    fun theThreeLevelsShowWhatTheyShould() {
        seedHistory()
        land()
        waitForTag("chat-list")

        select(ChatVerbosity.Low)
        shot("level-low")
        assertFalse("Low hides tool groups", shownSomewhere("chat-tool-group", ChatVerbosity.Low))
        assertFalse("Low hides tool cards", shownSomewhere("chat-tool-card", ChatVerbosity.Low))
        assertFalse("Low hides run rows", shownSomewhere("chat-run-row", ChatVerbosity.Low))
        assertFalse("Low hides phone results", shownSomewhere("chat-phone-result", ChatVerbosity.Low))
        assertTrue("Low keeps the proposal link", shownSomewhere("chat-proposal-link", ChatVerbosity.Low))

        select(ChatVerbosity.Medium)
        assertTrue("Medium shows one tool group", shownSomewhere("chat-tool-group", ChatVerbosity.Medium))
        assertFalse("Medium hides run rows", shownSomewhere("chat-run-row", ChatVerbosity.Medium))
        // The group is collapsed, and opens on a tap.
        tag("chat-list").performScrollToIndex(rowsAt(ChatVerbosity.Medium).indexOfFirst { it is com.example.phonesteward.logic.chat.ToolGroupRow })
        compose.waitForIdle()
        assertFalse("collapsed: the tool is not listed", hasText("Latest phone snapshot"))
        shot("level-medium-collapsed")
        clickTag("chat-tool-group")
        waitForText("Latest phone snapshot")
        shot("level-medium-expanded")

        select(ChatVerbosity.High)
        assertTrue("High shows tool cards", shownSomewhere("chat-tool-card", ChatVerbosity.High))
        assertTrue("High shows run rows", shownSomewhere("chat-run-row", ChatVerbosity.High))
        assertTrue("High shows the phone's answer", shownSomewhere("chat-phone-result", ChatVerbosity.High))
        tag("chat-list").performScrollToIndex(rowsAt(ChatVerbosity.High).indexOfFirst { it is com.example.phonesteward.logic.chat.ToolCardRow })
        compose.waitForIdle()
        waitForText("snapshot 3 h old", substring = true)
        shot("level-high")
    }

    @Test
    fun theLevelSurvivesAnActivityRestartAndAReopen() {
        seedHistory()
        land()
        select(ChatVerbosity.High)
        recreateActivity()
        waitForTag("verbosity-High")
        tag("verbosity-High").assertIsSelected()
        relaunchApp()
        waitForTag("verbosity-High")
        eventually("High is still selected after reopening") {
            tag("verbosity-High").assertIsSelected(); true
        }
        shot("level-restored")
    }

    @Test
    fun theKeyboardKeepsTheBoxVisibleAboveIt() {
        seedHistory()
        shell("settings put secure show_ime_with_hard_keyboard 1")
        land()
        clickTag("chat-input")
        Thread.sleep(1_500)
        shot("keyboard-open")
        val imeBottom = withActivity {
            ViewCompat.getRootWindowInsets(it.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        val screenH = withActivity { it.window.decorView.height }
        tag("chat-input").assertIsDisplayed()
        val inputBottom = tag("chat-input").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("the soft keyboard did not open on this device (ime inset $imeBottom); the check needs it", imeBottom > 0)
        assertTrue("the box ($inputBottom px) must end above the keyboard (top at ${screenH - imeBottom} px)", inputBottom <= screenH - imeBottom)
        tag("chat-send").assertIsDisplayed()
    }

    @Test
    fun aLongHistoryOpensAtTheNewestMessage() {
        for (i in 1..80) {
            fake.say(if (i % 2 == 1) "user" else "assistant", "Message $i of 80 in a long conversation about my storage.")
        }
        land()
        waitForText("Message 80 of 80", substring = true)
        shot("long-newest")
        assertFalse("the oldest message is far off screen", hasText("Message 1 of 80", substring = true))
        // Scrolling back reaches the oldest one.
        tag("chat-list").performScrollToIndex(0)
        waitForText("Message 1 of 80", substring = true)
        shot("long-oldest")
    }
}
