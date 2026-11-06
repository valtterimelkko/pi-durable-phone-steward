package com.example.phonesteward

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.logic.Onboarding
import com.example.phonesteward.testlab.Layout.bounds
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.testlab.ScreenShots
import com.example.phonesteward.ui.AppViewModel.AppState
import com.example.phonesteward.ui.StatusActions
import com.example.phonesteward.ui.StewardApp
import com.example.phonesteward.ui.StewardTab
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/**
 * Every screen of the L1 foundation, rendered from invented fixture states on a
 * real Android image (no network, no production server). Each test saves a
 * full-device screenshot (system bars included) and asserts the layout facts
 * that matter: the key control is displayed and sits above the bottom bar.
 */
@RunWith(AndroidJUnit4::class)
class ScreensTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        state: AppState,
        tab: StewardTab,
        onboarding: Onboarding.Snapshot = ScreenFixtures.allGranted,
        dark: Boolean = false,
    ) {
        compose.activity.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            PhoneStewardTheme(darkTheme = dark) {
                StewardApp(
                    state = state,
                    tab = tab,
                    onTab = {},
                    space = ScreenFixtures.space,
                    lastWorkerRunMs = ScreenFixtures.REVIEW_AT_MS,
                    onboarding = onboarding,
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

    private fun assertAboveBottomBar(tag: String) {
        val node = compose.onNodeWithTag(tag)
        node.assertIsDisplayed()
        val barTop = compose.onNodeWithTag("bottom-bar").bounds().top
        val bottom = node.bounds().bottom
        assertTrue("$tag bottom ($bottom px) must be above the bottom bar ($barTop px)", bottom <= barTop)
    }

    @Test
    fun pair() {
        show(ScreenFixtures.unpaired, StewardTab.Chat)
        compose.onNodeWithText("Pair").assertIsDisplayed()
        ScreenShots.save("pair")
    }

    @Test
    fun statusAllPermissionsOn() {
        show(ScreenFixtures.pairedBase, StewardTab.Status, ScreenFixtures.allGranted)
        // On a small screen the summary can sit below the first screenful: scroll to it, as the later lines do.
        compose.onNodeWithTag("permissions-summary").performScrollTo().assertIsDisplayed()
        ScreenShots.save("status-permissions-all-on")
        // The last line of the screen can be scrolled clear of the bottom bar (N12 for Status).
        compose.onNodeWithText("Forget this pairing on the phone").performScrollTo().assertIsDisplayed()
        val barTop = compose.onNodeWithTag("bottom-bar").bounds().top
        assertTrue(compose.onNodeWithText("Forget this pairing on the phone").bounds().bottom <= barTop)
        ScreenShots.save("status-permissions-all-on-scrolled")
    }

    @Test
    fun statusSomePermissionsOff() {
        show(ScreenFixtures.pairedBase, StewardTab.Status, ScreenFixtures.someOff)
        compose.onNodeWithText("Open all-files access").performScrollTo().assertIsDisplayed()
        ScreenShots.save("status-permissions-some-off")
        compose.onNodeWithText("Forget this pairing on the phone").performScrollTo().assertIsDisplayed()
        ScreenShots.save("status-permissions-some-off-scrolled")
    }

    @Test
    fun proposalsEmpty() {
        show(ScreenFixtures.noProposals, StewardTab.Proposals)
        compose.onNodeWithText("No open proposals").assertIsDisplayed()
        ScreenShots.save("proposals-empty")
    }

    @Test
    fun proposalsSeveralCards() {
        show(ScreenFixtures.severalProposals, StewardTab.Proposals)
        // N15: the review stamp is on the card.
        compose.onAllNodesWithTag("proposal-card")[0].assertIsDisplayed()
        // The list ends above the bottom bar, not under it.
        assertAboveBottomBar("proposals-list")
        ScreenShots.save("proposals-several-cards")
    }

    @Test
    fun proposalsAfterSuccessfulCacheClear() {
        show(
            ScreenFixtures.noProposals.copy(result = ScreenFixtures.clearedResult),
            StewardTab.Proposals,
        )
        compose.onNodeWithTag("result-card").assertIsDisplayed()
        compose.onNodeWithText(ScreenFixtures.clearedResult.text).assertIsDisplayed()
        ScreenShots.save("proposals-after-cache-clear")
    }

    @Test
    fun proposalsAfterNothingDone() {
        show(
            ScreenFixtures.oneProposal.copy(result = ScreenFixtures.nothingDoneResult),
            StewardTab.Proposals,
        )
        compose.onNodeWithText("Nothing was done — approve again to retry.").assertIsDisplayed()
        compose.onNodeWithTag("approve-button").assertIsDisplayed()
        ScreenShots.save("proposals-after-nothing-done")
    }

    @Test
    fun rules() {
        show(ScreenFixtures.pairedBase, StewardTab.Rules)
        assertAboveBottomBar("save-rules")
        ScreenShots.save("rules")
    }

    @Test
    fun proposalsSeveralCardsDark() {
        show(ScreenFixtures.severalProposals, StewardTab.Proposals, dark = true)
        compose.onAllNodesWithTag("proposal-card")[0].assertIsDisplayed()
        ScreenShots.save("proposals-several-cards-dark")
    }

    @Test
    fun statusDark() {
        show(ScreenFixtures.pairedBase, StewardTab.Status, ScreenFixtures.someOff, dark = true)
        ScreenShots.save("status-dark")
    }

    @Test
    fun statusOffersTheStewardTrashScreen() {
        show(ScreenFixtures.pairedBase, StewardTab.Status)
        compose.onNodeWithTag("open-trash-button").performScrollTo().assertIsDisplayed()
        ScreenShots.save("status-open-trash")
    }

    @Test
    fun trashScreenListsBatchesWithRestoreButtons() {
        show(ScreenFixtures.trashScreenState, StewardTab.Status)
        // A11.3: the top bar names the screen, and the first batch shows its date, title, size and items.
        compose.onNodeWithText("Steward trash").assertIsDisplayed()
        compose.onNodeWithText("8 Oct 2026").assertIsDisplayed()
        compose.onNodeWithText("Move three old map downloads to the Steward trash").assertIsDisplayed()
        compose.onNodeWithText("OfflineMaps/230606").assertIsDisplayed()
        compose.onAllNodesWithTag("restore-item")[0].assertIsDisplayed()
        assertAboveBottomBar("trash-list")
        ScreenShots.save("trash")
    }

    @Test
    fun trashScreenSaysSoWhenItIsEmpty() {
        show(ScreenFixtures.trashScreenEmptyState, StewardTab.Status)
        compose.onNodeWithText(com.example.phonesteward.ui.TrashScreenText.EMPTY_TITLE).assertIsDisplayed()
        ScreenShots.save("trash-empty")
    }

    @Test
    fun trashScreenHoldsTheButtonsWhileARestoreRuns() {
        // Correction 1 (review finding 3): a double tap must not start a second restore.
        show(ScreenFixtures.trashScreenBusyState, StewardTab.Status)
        compose.onAllNodesWithTag("restore-all")[0].assertIsNotEnabled()
        compose.onAllNodesWithTag("restore-item")[0].assertIsNotEnabled()
        ScreenShots.save("trash-restoring")
    }
}
