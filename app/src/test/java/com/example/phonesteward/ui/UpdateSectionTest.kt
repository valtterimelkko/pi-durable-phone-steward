package com.example.phonesteward.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.testlab.UpdateFixtures
import com.example.phonesteward.ui.theme.PhoneStewardTheme
import com.example.phonesteward.update.UpdateUiState

/**
 * F1: the update row on Status in each state, rendered through the real [StewardApp] from
 * fixtures. Also writes host previews to `app/build/previews/update-*.png` (not a gate; the
 * Test Lab run is).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h830dp-xxhdpi")
class UpdateSectionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var updates = 0
    private var checks = 0

    private fun show(state: UpdateUiState, name: String, dark: Boolean = false) {
        compose.setContent {
            PhoneStewardTheme(darkTheme = dark) {
                StewardApp(
                    state = ScreenFixtures.pairedBase,
                    tab = StewardTab.Status,
                    onTab = {},
                    space = ScreenFixtures.space,
                    lastWorkerRunMs = ScreenFixtures.REVIEW_AT_MS,
                    onboarding = ScreenFixtures.allGranted,
                    statusActions = StatusActions(onUpdate = { updates += 1 }, onCheckUpdate = { checks += 1 }),
                    onPair = { _, _, _ -> },
                    onSend = {},
                    onDecide = { _, _, _ -> },
                    onDismissResult = {},
                    onSaveRules = {},
                    update = state,
                )
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/previews").also { it.mkdirs() }
        File(dir, "update-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `up to date shows the version and no button`() {
        show(UpdateFixtures.upToDate, "up-to-date")
        compose.onNodeWithTag("update-version").assertTextContains("App version 0.2.3")
        compose.onNodeWithTag("update-status").assertTextContains("Up to date")
        compose.onNodeWithTag("update-button").assertDoesNotExist()
    }

    @Test fun `available offers one Update button that taps through`() {
        show(UpdateFixtures.available, "available")
        compose.onNodeWithTag("update-status").assertTextContains("Update available: 0.2.4")
        compose.onNodeWithTag("update-button").assertIsDisplayed().assertIsEnabled().assertTextContains("Update")
        compose.onNodeWithTag("update-button").performClick()
        assertEquals(1, updates)
    }

    @Test fun `downloading shows progress and no button`() {
        show(UpdateFixtures.downloading, "downloading")
        compose.onNodeWithTag("update-status").assertTextContains("Downloading 0.2.4")
        compose.onNodeWithTag("update-progress").assertIsDisplayed()
        compose.onNodeWithTag("update-button").assertDoesNotExist()
    }

    @Test fun `refused names the reason and offers a retry`() {
        show(UpdateFixtures.refused, "refused")
        compose.onNodeWithTag("update-status").assertTextContains("refused", substring = true)
        compose.onNodeWithTag("update-help").assertTextContains("Nothing was installed", substring = true)
        compose.onNodeWithTag("update-button").assertTextContains("Try again")
    }

    @Test fun `needs permission explains the one switch`() {
        show(UpdateFixtures.needsPermission, "needs-permission")
        compose.onNodeWithTag("update-help").assertTextContains("allow installs from Phone Steward", substring = true)
        compose.onNodeWithTag("update-button").performClick()
        assertEquals(1, updates)
    }

    @Test fun `installing waits on Android with no button`() {
        show(UpdateFixtures.installing, "installing")
        compose.onNodeWithTag("update-status").assertTextContains("Waiting for Android's confirmation")
        compose.onNodeWithTag("update-button").assertDoesNotExist()
    }

    @Test fun `a failed check offers Check again`() {
        show(UpdateFixtures.checkFailed, "check-failed")
        compose.onNodeWithTag("update-check-button").performClick()
        assertEquals(1, checks)
    }

    @Test fun `a hidden update row leaves Status as before`() {
        compose.setContent {
            PhoneStewardTheme {
                StatusScreen(
                    state = ScreenFixtures.pairedBase,
                    space = ScreenFixtures.space,
                    lastWorkerRunMs = 0,
                    onboarding = ScreenFixtures.allGranted,
                    onOpenBattery = {}, onOpenUsageAccess = {}, onOpenAllFiles = {}, onRequestMediaRead = {},
                    onOpenManageMedia = {}, onUnpair = {}, onRefresh = {},
                )
            }
        }
        compose.onNodeWithTag("update-card").assertDoesNotExist()
        compose.onNodeWithText("Refresh now").assertExists()
    }

    @Test fun `dark theme available preview`() = show(UpdateFixtures.available, "available-dark", dark = true)

    @Test fun `dark theme refused preview`() = show(UpdateFixtures.refused, "refused-dark", dark = true)
}
