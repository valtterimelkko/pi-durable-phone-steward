package com.example.phonesteward.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.testlab.ChatHost
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/**
 * A15.7 on the host: a copy button on every code block (the code only), every text block (that
 * block's text) and every finished steward round (the round's markdown), with the "Copied"
 * confirmation — the clipboard's content is what each button says it copies.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h830dp-xxhdpi")
class ChatCopyHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val stewardMarkdown =
        "Here is the line you asked for.\n\n```python\nprint(\"hello steward\")\n```\n\nAnd a closing word."

    private fun state() = ScreenFixtures.chatState(com.example.phonesteward.logic.chat.ChatVerbosity.Low).copy(
        chat = listOf(
            AppViewModel.ChatEntry("user", "Print a hello for me", atMs = 1_000L, seq = 1),
            AppViewModel.ChatEntry("assistant", stewardMarkdown, atMs = 2_000L, seq = 2),
        ),
    )

    private fun clipboardText(): String? {
        val cm = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun `the code block's copy button copies the code and nothing else`() {
        compose.setContent { PhoneStewardTheme { com.example.phonesteward.ui.StewardApp(state = state(), tab = com.example.phonesteward.ui.StewardTab.Chat, onTab = {}, space = ScreenFixtures.space, lastWorkerRunMs = 0, onboarding = ScreenFixtures.allGranted, statusActions = StatusActions(), onPair = { _, _, _ -> }, onSend = {}, onDecide = { _, _, _ -> }, onDismissResult = {}, onSaveRules = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("md-code-copy").performClick()
        compose.waitForIdle()
        assertEquals("print(\"hello steward\")", clipboardText())
    }

    @Test
    fun `a text block's copy button copies that block's words`() {
        compose.setContent { PhoneStewardTheme { com.example.phonesteward.ui.StewardApp(state = state(), tab = com.example.phonesteward.ui.StewardTab.Chat, onTab = {}, space = ScreenFixtures.space, lastWorkerRunMs = 0, onboarding = ScreenFixtures.allGranted, statusActions = StatusActions(), onPair = { _, _, _ -> }, onSend = {}, onDecide = { _, _, _ -> }, onDismissResult = {}, onSaveRules = {}) } }
        compose.waitForIdle()
        compose.onAllNodesWithTag("md-text-copy", useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
        assertEquals("Here is the line you asked for.", clipboardText())
    }

    @Test
    fun `the finished round's copy button copies the round's markdown`() {
        compose.setContent { PhoneStewardTheme { com.example.phonesteward.ui.StewardApp(state = state(), tab = com.example.phonesteward.ui.StewardTab.Chat, onTab = {}, space = ScreenFixtures.space, lastWorkerRunMs = 0, onboarding = ScreenFixtures.allGranted, statusActions = StatusActions(), onPair = { _, _, _ -> }, onSend = {}, onDecide = { _, _, _ -> }, onDismissResult = {}, onSaveRules = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("chat-copy-round").performClick()
        compose.waitForIdle()
        assertEquals(stewardMarkdown, clipboardText())
    }

    @Test
    fun `an unfinished round carries no round buttons`() {
        val working = state().copy(
            chat = state().chat + AppViewModel.ChatEntry("system", "", atMs = 0, kind = "run", seq = 3, status = "started"),
        )
        compose.setContent { PhoneStewardTheme { com.example.phonesteward.ui.StewardApp(state = working, tab = com.example.phonesteward.ui.StewardTab.Chat, onTab = {}, space = ScreenFixtures.space, lastWorkerRunMs = 0, onboarding = ScreenFixtures.allGranted, statusActions = StatusActions(), onPair = { _, _, _ -> }, onSend = {}, onDecide = { _, _, _ -> }, onDismissResult = {}, onSaveRules = {}) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("chat-copy-round", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithTag("md-code-copy", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
