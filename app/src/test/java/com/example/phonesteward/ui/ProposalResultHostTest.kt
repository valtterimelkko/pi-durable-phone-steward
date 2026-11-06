package com.example.phonesteward.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/**
 * N16 on a small phone (found by the L4 sweep on SmallPhone): the owner scrolls down to a card's
 * Approve button, and the result card is the list's first item, so it appeared out of sight. A new
 * result must scroll the list to it: the result shows where the action happened.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h830dp-xxhdpi")
class ProposalResultHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun `a result that arrives while the list is scrolled down is brought into view`() {
        var state by mutableStateOf(ScreenFixtures.severalProposals)
        compose.setContent {
            PhoneStewardTheme { ProposalsScreen(state, onDecide = { _, _, _ -> }) }
        }
        compose.onNodeWithTag("proposals-list").performScrollToIndex(2)
        compose.waitForIdle()
        state = state.copy(result = ScreenFixtures.nothingDoneResult)
        compose.waitForIdle()
        compose.onNodeWithTag("result-card").assertIsDisplayed()
    }
}
