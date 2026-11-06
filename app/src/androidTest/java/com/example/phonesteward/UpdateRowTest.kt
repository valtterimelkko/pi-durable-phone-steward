package com.example.phonesteward

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.testlab.Layout.bounds
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.testlab.ScreenShots
import com.example.phonesteward.testlab.UpdateFixtures
import com.example.phonesteward.ui.StatusActions
import com.example.phonesteward.ui.StewardApp
import com.example.phonesteward.ui.StewardTab
import com.example.phonesteward.ui.theme.PhoneStewardTheme
import com.example.phonesteward.update.PackageApkInspector
import com.example.phonesteward.update.UpdateUiState
import com.example.phonesteward.update.UpdateVerifier
import com.example.phonesteward.update.installedSigners

/**
 * F1 (A7) on a real Android image: the update row in each state (screenshots), and the pieces of
 * the install path that only a real image can answer: Android's own archive parser reading an APK's
 * package, versionCode and signing certificate, the "Install unknown apps" query, and a
 * PackageInstaller session for this package (created and abandoned, never committed).
 */
@RunWith(AndroidJUnit4::class)
class UpdateRowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(state: UpdateUiState, dark: Boolean = false) {
        compose.activity.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            PhoneStewardTheme(darkTheme = dark) {
                StewardApp(
                    state = ScreenFixtures.pairedBase,
                    tab = StewardTab.Status,
                    onTab = {},
                    space = ScreenFixtures.space,
                    lastWorkerRunMs = ScreenFixtures.REVIEW_AT_MS,
                    onboarding = ScreenFixtures.allGranted,
                    statusActions = StatusActions(),
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
    }

    private fun assertCardAboveBottomBar() {
        val card = compose.onNodeWithTag("update-card")
        card.assertIsDisplayed()
        val barTop = compose.onNodeWithTag("bottom-bar").bounds().top
        assertTrue("update card must end above the bottom bar", card.bounds().bottom <= barTop)
    }

    @Test fun updateUpToDate() {
        show(UpdateFixtures.upToDate)
        assertCardAboveBottomBar()
        ScreenShots.save("update-up-to-date")
    }

    @Test fun updateAvailable() {
        show(UpdateFixtures.available)
        assertCardAboveBottomBar()
        compose.onNodeWithTag("update-button").assertIsDisplayed()
        // The button is a comfortable touch target (48 dp).
        val heightPx = compose.onNodeWithTag("update-button").bounds().height
        assertTrue("Update button too short: $heightPx px", heightPx >= 48 * target.resources.displayMetrics.density - 1)
        ScreenShots.save("update-available")
    }

    @Test fun updateDownloading() {
        show(UpdateFixtures.downloading)
        assertCardAboveBottomBar()
        compose.onNodeWithTag("update-progress").assertIsDisplayed()
        ScreenShots.save("update-downloading")
    }

    @Test fun updateRefused() {
        show(UpdateFixtures.refused)
        assertCardAboveBottomBar()
        compose.onNodeWithTag("update-button").assertIsDisplayed()
        ScreenShots.save("update-refused")
    }

    @Test fun updateNeedsPermission() {
        show(UpdateFixtures.needsPermission)
        assertCardAboveBottomBar()
        ScreenShots.save("update-needs-permission")
    }

    @Test fun updateAvailableDark() {
        show(UpdateFixtures.available, dark = true)
        assertCardAboveBottomBar()
        ScreenShots.save("update-available-dark")
    }

    @Test fun updateRefusedDark() {
        show(UpdateFixtures.refused, dark = true)
        assertCardAboveBottomBar()
        compose.onNodeWithTag("update-button").assertIsDisplayed()
        compose.waitForIdle()
        ScreenShots.save("update-refused-dark")
    }

    /** Android's own archive parser reads this app's installed APK like an update candidate. */
    @Test fun androidReadsAnApkIdentityAndSigner() {
        val apk = File(target.applicationInfo.sourceDir)
        val info = PackageApkInspector(target).inspect(apk)
        assertNotNull("getPackageArchiveInfo returned null for the installed APK", info)
        assertEquals(target.packageName, info!!.packageName)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), info.versionCode)
        assertTrue("no signing certificate read", info.signers.current.isNotEmpty())
        assertTrue(
            "the archive's signer must equal the installed app's",
            UpdateVerifier.sameSigner(installedSigners(target), info.signers),
        )
    }

    @Test fun androidAnswersTheInstallUnknownAppsQuery() {
        // Either answer is fine on a fresh image; the point is that the query works and the
        // manifest permission is declared (otherwise the switch would not exist for this app).
        target.packageManager.canRequestPackageInstalls()
        val declared = target.packageManager
            .getPackageInfo(target.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_PERMISSIONS.toLong()))
            .requestedPermissions.orEmpty()
        assertTrue(declared.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
    }

    @Test fun androidOpensAnInPlaceInstallSessionForThisPackage() {
        val installer = target.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(target.packageName)
        }
        val id = installer.createSession(params)
        try {
            assertEquals(target.packageName, installer.getSessionInfo(id)!!.appPackageName)
        } finally {
            installer.abandonSession(id)
        }
    }
}
