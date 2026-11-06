package com.example.phonesteward.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/** The note under a proposal card describes only what that card's actions really do . */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h830dp-xxhdpi")
class ProposalNotesHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun show(proposal: com.example.phonesteward.net.Proposal) {
        compose.setContent {
            PhoneStewardTheme {
                ProposalsScreen(ScreenFixtures.pairedBase.copy(proposals = listOf(proposal)), onDecide = { _, _, _ -> })
            }
        }
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun `an uninstall card does not talk about the cache screens`() {
        show(ScreenFixtures.uninstallProposal)
        assertEquals(0, count("Storage & cache"))
        assertTrue("it still says what approving does", count("Android's own") > 0)
    }

    @Test
    fun `a media card does not talk about the cache screens`() {
        show(ScreenFixtures.mediaProposal)
        assertEquals(0, count("Storage & cache"))
    }

    @Test
    fun `a files card names the Steward trash and the one question in the app`() {
        show(ScreenFixtures.filesProposal)
        assertTrue(count("Steward trash") > 0)
        assertTrue("it says the files wait 30 days", count("30 days") > 0)
        assertEquals("a files card does not promise Android's own confirmation", 0, count("Android's own"))
        assertEquals(3, count("Move to the Steward trash"))
        assertTrue("the folder is named", count("OfflineMaps/230606") > 0)
    }
}
