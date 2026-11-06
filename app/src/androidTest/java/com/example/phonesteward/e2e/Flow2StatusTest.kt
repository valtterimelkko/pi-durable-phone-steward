package com.example.phonesteward.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.android.Permissions
import com.example.phonesteward.android.PowerSettings
import com.example.phonesteward.logic.Onboarding

/**
 * Flow 2, Status: the permission rows show the real device state, each "open" action lands on the
 * right Android screen (seen with UiAutomator) and Back returns to the app, which re-reads the state
 * (N2). The class name sorts before the Proposals flow, which grants all-files access for good.
 */
@RunWith(AndroidJUnit4::class)
class Flow2StatusTest : E2eBase() {
    override val flow = "status"

    private val pkg get() = context.packageName

    private fun snapshot() = Onboarding.Snapshot(
        batteryUnrestricted = PowerSettings.isIgnoringBatteryOptimizations(context),
        usageAccess = Permissions.usageAccessGranted(context),
        allFiles = Permissions.allFilesGranted(),
        mediaRead = Permissions.mediaReadGranted(context),
        manageMedia = Permissions.canManageMedia(context),
        autoModeOn = false,
    )

    private fun inPlace(): Int = Onboarding.rowStates(snapshot()).count { it.second != Onboarding.RowState.ACTION_NEEDED }

    private fun allowedRows() = compose.onAllNodesWithText("Allowed").fetchSemanticsNodes().size

    private fun summary(): String =
        tag("permissions-summary").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun landOnStatus() {
        launchApp()
        pairAndLand()
        openTab("Status")
        waitForTag("permissions-summary")
    }

    @Test
    fun rowsReflectTheRealDeviceState() {
        landOnStatus()
        eventually("summary matches the device") { summary() == "${inPlace()} of 5 in place" }
        assertEquals("one 'Allowed' label per granted row", Onboarding.rowStates(snapshot()).count { it.second == Onboarding.RowState.GRANTED }, allowedRows())
        // Manage media is only needed in auto mode, which is off.
        assertTrue(hasText("Not needed now"))
        shot("rows")
    }

    @Test
    fun allFilesRowOffersADirectButtonNotASettingsPath() {
        landOnStatus()
        // never send the owner to find a button in Settings.
        assertFalse("no 'Settings →' path text on Status", hasText("Settings →", substring = true))
        assertFalse("no menu path text on Status", hasText(" → ", substring = true))
        if (Permissions.allFilesGranted()) {
            // Already allowed on this device: the row is green and its button is not offered.
            assertTrue(hasText("Allowed"))
            assertFalse("a granted row shows no button", hasText("Open all-files access"))
            return
        }
        val before = allowedRows()
        text("Open all-files access").performScrollToIfNeeded()
        clickText("Open all-files access")
        waitForPackageNot(pkg)
        // The deep link lands on this app's own all-files page, not the list of every app.
        eventually("Android's all-files page for Phone Steward") { hasSystemTextContaining("Phone Steward") }
        shot("all-files-settings")
        shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        backToApp()
        eventually("the all-files row turns Allowed after the owner returns") { allowedRows() == before + 1 }
        eventually("the summary follows") { summary() == "${inPlace()} of 5 in place" }
        // Owner rule 2026-10-08: a granted row shows no greyed button.
        assertFalse("the granted all-files row shows no button", hasText("Open all-files access"))
        shot("all-files-allowed")
    }

    @Test
    fun usageAccessButtonOpensUsageAccessAndBackReturns() {
        landOnStatus()
        if (Permissions.usageAccessGranted(context)) {
            assertTrue(hasText("Allowed"))
            return
        }
        val before = allowedRows()
        clickText("Open usage access")
        waitForPackageNot(pkg)
        eventually("Android's usage access screen") { hasSystemTextContaining("Usage access") && hasSystemTextContaining("Phone Steward") }
        shot("usage-access-settings")
        shell("appops set $pkg GET_USAGE_STATS allow")
        backToApp()
        eventually("the usage row turns Allowed") { allowedRows() == before + 1 }
        assertFalse("the granted usage row shows no button", hasText("Open usage access"))
    }

    @Test
    fun batteryButtonOpensAndroidsOwnDialog() {
        landOnStatus()
        if (PowerSettings.isIgnoringBatteryOptimizations(context)) {
            assertTrue(hasText("Allowed"))
            return
        }
        val before = allowedRows()
        clickText("Open battery settings")
        waitForPackageNot(pkg)
        // The direct request dialog (N1), not the long list of every app.
        waitSystemText("Allow", "Deny")
        shot("battery-dialog")
        waitSystemText("Allow").click()
        eventually("back in the app") { foregroundPackage() == pkg }
        eventually("the battery row turns Allowed") { allowedRows() == before + 1 }
        assertFalse("the granted battery row shows no button", hasText("Open battery settings"))
        shot("battery-allowed")
    }

    @Test
    fun mediaReadButtonAsksAndroidForThePermission() {
        landOnStatus()
        if (Permissions.mediaReadGranted(context)) {
            assertTrue(hasText("Allowed"))
            return
        }
        val before = allowedRows()
        clickText("Allow photos and videos")
        waitForPackageNot(pkg)
        shot("media-permission-dialog")
        waitSystemText("Allow", "Allow all", "While using the app").click()
        eventually("back in the app") { foregroundPackage() == pkg }
        eventually("the media row turns Allowed") { allowedRows() == before + 1 }
        assertFalse("the granted media row shows no button", hasText("Allow photos and videos"))
    }

    @Test
    fun refreshNowAsksTheServerAndForgetReturnsToPairing() {
        landOnStatus()
        val proposalCalls = fake.requestsTo("GET", "/v1/proposals").size
        clickText("Refresh now")
        eventually("Refresh now fetched the proposals and the rules again") {
            fake.requestsTo("GET", "/v1/proposals").size > proposalCalls
        }
        clickText("Forget this pairing on the phone")
        waitForText("Pair with your steward server", substring = true)
        shot("forgotten")
        assertFalse(hasTag("top-bar"))
        // A restart stays on the pairing screen: the identity is gone from the phone.
        relaunchApp()
        waitForText("Pair with your steward server", substring = true)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToIfNeeded() {
    runCatching { performScrollTo() }
}
