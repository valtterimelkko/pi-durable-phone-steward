package com.example.phonesteward.testlab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.example.phonesteward.logic.chat.ChatLog
import com.example.phonesteward.logic.chat.ChatRows
import com.example.phonesteward.logic.chat.ChatVerbosity
import com.example.phonesteward.logic.chat.ProposalRow
import com.example.phonesteward.logic.chat.RunRow
import com.example.phonesteward.logic.chat.ToolCardRow
import com.example.phonesteward.logic.chat.ToolGroupRow
import com.example.phonesteward.ui.AppViewModel
import com.example.phonesteward.ui.StatusActions
import com.example.phonesteward.ui.StewardApp
import com.example.phonesteward.ui.StewardTab
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/**
 * F2 Chat checks shared by the host (Robolectric) tests and the Test Lab device tests: the
 * rows each verbosity level shows, the expandable tool group, and the message box, all
 * against a fake host (no server). `shot` saves a screenshot where the caller supports it.
 */
class ChatHost(initial: AppViewModel.AppState) {
    var state by mutableStateOf(initial)
    val sent = mutableListOf<String>()
}

object ChatChecks {

    fun show(rule: ComposeContentTestRule, host: ChatHost, dark: Boolean = false) {
        rule.setContent {
            PhoneStewardTheme(darkTheme = dark) {
                StewardApp(
                    state = host.state,
                    tab = StewardTab.Chat,
                    onTab = {},
                    space = ScreenFixtures.space,
                    lastWorkerRunMs = ScreenFixtures.REVIEW_AT_MS,
                    onboarding = ScreenFixtures.allGranted,
                    statusActions = StatusActions(),
                    onPair = { _, _, _ -> },
                    onSend = {
                        host.sent += it
                        host.state = host.state.copy(chat = ChatLog.withLocalOwner(host.state.chat, it, System.currentTimeMillis()))
                    },
                    onChatVerbosity = { host.state = host.state.copy(chatVerbosity = it) },
                    onDecide = { _, _, _ -> },
                    onDismissResult = {},
                    onSaveRules = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun count(rule: ComposeContentTestRule, tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun scrollTo(rule: ComposeContentTestRule, index: Int) {
        rule.onNodeWithTag("chat-list").performScrollToIndex(index)
        rule.waitForIdle()
    }

    /** What each level shows, checked where the row sits (a lazy list only composes what is on screen). */
    fun checkLevel(rule: ComposeContentTestRule, level: ChatVerbosity, shot: (String) -> Unit = {}) {
        val host = ChatHost(ScreenFixtures.chatState(level))
        show(rule, host)
        val rows = ChatRows.build(host.state.chat, level)
        val tag = level.name.lowercase()
        // The newest row is in view on arrival.
        rule.onNodeWithTag("chat-list").assertIsDisplayed()
        shot("chat-$tag-bottom")
        scrollTo(rule, 0)
        shot("chat-$tag-top")
        when (level) {
            ChatVerbosity.Low -> {
                rows.indices.forEach { i ->
                    scrollTo(rule, i)
                    assertEquals(0, count(rule, "chat-tool-group"))
                    assertEquals(0, count(rule, "chat-tool-card"))
                    assertEquals(0, count(rule, "chat-run-row"))
                    assertEquals(0, count(rule, "chat-phone-result"))
                }
                scrollTo(rule, rows.indexOfFirst { it is ProposalRow })
                assertTrue(count(rule, "chat-proposal-link") >= 1)
            }
            ChatVerbosity.Medium -> {
                val groupAt = rows.indexOfFirst { it is ToolGroupRow }
                scrollTo(rule, groupAt)
                assertTrue(count(rule, "chat-tool-group") >= 1)
                assertEquals("N14: run-ended rows are hidden", 0, count(rule, "chat-run-row"))
                // Collapsed: the individual tool is not listed until the group is opened.
                rule.onAllNodesWithText("Latest phone snapshot").assertCountEquals(0)
                rule.onAllNodesWithTag("chat-tool-group")[0].performClick()
                rule.waitForIdle()
                rule.onNodeWithText("Latest phone snapshot").assertIsDisplayed()
                shot("chat-medium-group-expanded")
            }
            ChatVerbosity.High -> {
                scrollTo(rule, rows.indexOfFirst { it is ToolCardRow })
                assertTrue(count(rule, "chat-tool-card") >= 1)
                assertTrue("High shows the args and result summaries", count(rule, "chat-tool-row") >= 1)
                rule.onNodeWithText("snapshot 3 h old").assertIsDisplayed()
                scrollTo(rule, rows.indexOfFirst { it is RunRow })
                assertTrue(count(rule, "chat-run-row") >= 1)
                shot("chat-high-cards")
            }
        }
    }

    /** "Working…" at every level while a run is open. */
    fun checkWorking(rule: ComposeContentTestRule, level: ChatVerbosity, shot: (String) -> Unit = {}) {
        val host = ChatHost(ScreenFixtures.chatState(level, ScreenFixtures.chatHistoryWorking))
        show(rule, host)
        rule.onNodeWithTag("chat-working").assertIsDisplayed()
        rule.onNodeWithText("Working…").assertIsDisplayed()
        shot("chat-${level.name.lowercase()}-working")
    }

    /** The control switches level and the screen follows. */
    fun checkVerbosityControl(rule: ComposeContentTestRule, shot: (String) -> Unit = {}) {
        val host = ChatHost(ScreenFixtures.chatState(ChatVerbosity.Medium))
        show(rule, host)
        rule.onNodeWithTag("verbosity-control").assertIsDisplayed()
        rule.onNodeWithTag("verbosity-High").performClick()
        rule.waitForIdle()
        assertEquals(ChatVerbosity.High, host.state.chatVerbosity)
        rule.onNodeWithTag("verbosity-Low").performClick()
        rule.waitForIdle()
        assertEquals(ChatVerbosity.Low, host.state.chatVerbosity)
        assertEquals(0, count(rule, "chat-tool-group"))
        shot("chat-verbosity-low-selected")
    }

    private fun inputHeight(rule: ComposeContentTestRule): Float =
        rule.onNode(hasSetTextAction()).bounds().let { it.bottom - it.top }

    /** Send needs text, shows the owner bubble at once, clears the box, and the box grows to 4 lines. */
    fun checkMessageBox(rule: ComposeContentTestRule, shot: (String) -> Unit = {}) {
        val host = ChatHost(ScreenFixtures.chatState(ChatVerbosity.Medium))
        show(rule, host)
        val input = rule.onNode(hasSetTextAction())
        rule.onNodeWithTag("chat-send").assertHasNoClickAction()

        val oneLine = inputHeight(rule)
        input.performTextInput("a\nb\nc\nd")
        rule.waitForIdle()
        val fourLines = inputHeight(rule)
        input.performTextInput("\ne\nf\ng")
        rule.waitForIdle()
        val sevenLines = inputHeight(rule)
        assertTrue("the box grows with its lines ($oneLine -> $fourLines)", fourLines > oneLine)
        assertEquals("the box stops growing at 4 lines", fourLines, sevenLines, 1f)
        shot("chat-box-multiline")

        // Clear and send a real message.
        input.performTextReplacement("How much is free now?")
        rule.waitForIdle()
        rule.onNodeWithTag("chat-send").assertHasClickAction()
        shot("chat-box-typed")
        rule.onNodeWithTag("chat-send").performClick()
        rule.waitForIdle()
        assertEquals(listOf("How much is free now?"), host.sent)
        rule.onNodeWithText("How much is free now?").assertIsDisplayed() // the owner bubble, at once, in view
        val text = rule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("the box is empty after sending", "", text)
        rule.onNodeWithTag("chat-send").assertHasNoClickAction()
        // The box sits above the bottom bar.
        val barTop = rule.onNodeWithTag("bottom-bar").bounds().top
        assertTrue(rule.onNode(hasSetTextAction()).bounds().bottom <= barTop)
        shot("chat-after-send")
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.bounds() = fetchSemanticsNode().boundsInRoot
