package com.example.phonesteward.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/** N31 (A10.8): the card shows "Already removed" for an app that is gone, unticked and not tickable. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h830dp-xxhdpi")
class AlreadyRemovedHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var decided: Triple<Proposal, Boolean, List<String>>? = null

    private fun show(removed: Set<String>) {
        compose.setContent {
            PhoneStewardTheme {
                ProposalsScreen(
                    ScreenFixtures.pairedBase.copy(proposals = listOf(ScreenFixtures.uninstallProposal), removedItemIds = removed),
                    onDecide = { p, a, ids -> decided = Triple(p, a, ids) },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a removed app says so, is unticked and cannot be ticked`() {
        show(setOf("it-app-1"))
        assertEquals(1, compose.onAllNodesWithText("Already removed", substring = true).fetchSemanticsNodes().size)
        val boxes = compose.onAllNodes(isToggleable())
        boxes[0].assertIsOff().assertIsNotEnabled()
        boxes[1].assertIsOn().assertIsEnabled()
    }

    @Test
    fun `approving sends only the apps that are still there`() {
        show(setOf("it-app-1"))
        compose.onNodeWithTag("approve-button").performClick()
        assertEquals(listOf("it-app-2"), decided!!.third)
    }

    @Test
    fun `with nothing removed both apps are ticked and no line says removed`() {
        show(emptySet())
        assertEquals(0, compose.onAllNodesWithText("Already removed", substring = true).fetchSemanticsNodes().size)
        compose.onAllNodes(isToggleable())[0].assertIsOn()
        compose.onAllNodes(isToggleable())[1].assertIsOn()
    }
}
