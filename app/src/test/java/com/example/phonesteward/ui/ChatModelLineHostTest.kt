package com.example.phonesteward.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.net.AnsweringModel
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/**
 * A15.10 on the host: each finished steward round names the model that answered it, and a round
 * answered by a model other than the one chosen in Settings carries the plain warning line.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h830dp-xxhdpi")
class ChatModelLineHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val chosen = AnsweringModel("zai", "glm-5.3-flash", "GLM 5.3 Flash")

    private fun stateWith(answering: AnsweringModel?) = ScreenFixtures.chatState(com.example.phonesteward.logic.chat.ChatVerbosity.Medium).copy(
        agent = ScreenFixtures.agentSettings,
        models = ScreenFixtures.modelCatalogue,
        chat = listOf(
            AppViewModel.ChatEntry("user", "How full is my phone?", atMs = 1_000L, seq = 1),
            AppViewModel.ChatEntry("assistant", "Here is what I found.", atMs = 2_000L, seq = 2, answering = answering),
        ),
    )

    private fun show(answering: AnsweringModel?) {
        compose.setContent {
            PhoneStewardTheme {
                StewardApp(
                    state = stateWith(answering),
                    tab = StewardTab.Chat,
                    onTab = {},
                    space = ScreenFixtures.space,
                    lastWorkerRunMs = 0,
                    onboarding = ScreenFixtures.allGranted,
                    statusActions = StatusActions(),
                    onPair = { _, _, _ -> },
                    onSend = {},
                    onDecide = { _, _, _ -> },
                    onDismissResult = {},
                    onSaveRules = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the round names the model that answered it`() {
        show(chosen)
        compose.onNodeWithTag("chat-round-model", useUnmergedTree = true).assertTextEquals("GLM 5.3 Flash")
        assertEquals(0, compose.onAllNodesWithTag("chat-round-model-warning", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a round answered by another model says so plainly`() {
        show(AnsweringModel("openai-codex", "gpt-6.1-sol", "GPT 6.1 Sol"))
        compose.onNodeWithTag("chat-round-model-warning", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Answered by GPT 6.1 Sol, not GLM 5.3 Flash").assertExists()
    }

    @Test
    fun `a round whose event named no model shows no line`() {
        show(null)
        assertEquals(0, compose.onAllNodesWithTag("chat-round-model", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("chat-round-model-warning", useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
