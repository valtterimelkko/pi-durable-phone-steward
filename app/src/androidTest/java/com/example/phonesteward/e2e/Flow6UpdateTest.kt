package com.example.phonesteward.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.BuildConfig
import com.example.phonesteward.android.AppGraph

/**
 * Flow 6, self-update (F1, A7). The app under test is versionCode 5 (0.2.3); the "newer" APK is
 * versionCode 6 (0.2.4) with the same debug certificate, shipped as a test asset by
 * `app/scripts/build-e2e.sh`, as is a copy signed by someone else. The fake steward announces
 * 0.2.4 and serves the file. Android's install confirmation is screenshotted and CANCELLED, never
 * confirmed: replacing the package would kill the instrumentation. The final replacement is proved
 * on a real phone.
 */
@RunWith(AndroidJUnit4::class)
class Flow6UpdateTest : E2eBase() {
    override val flow = "update"

    private val pkg get() = context.packageName
    private val newer: ByteArray? by lazy { asset("newer.apk") }
    private val otherSigner: ByteArray? by lazy { asset("other-signer.apk") }

    private fun asset(name: String): ByteArray? =
        runCatching { instrumentation.context.assets.open(name).use { it.readBytes() } }.getOrNull()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun requireAssets() {
        assumeTrue("newer.apk / other-signer.apk are not in the test APK: build with app/scripts/build-e2e.sh", newer != null && otherSigner != null)
        assertEquals("the app under test must be built with -PstewardVersionCode=5", 5, BuildConfig.VERSION_CODE)
        assertEquals("0.2.3", BuildConfig.VERSION_NAME)
    }

    private fun announce(bytes: ByteArray, versionCode: Long = 6, sha: String = sha256(bytes)) {
        fake.latest = buildJsonObject {
            put("versionName", "0.2.4"); put("versionCode", versionCode); put("sha256", sha)
            put("sizeBytes", bytes.size.toLong()); put("publishedAtMs", System.currentTimeMillis())
        }
        fake.apkBytes = { code -> if (code == versionCode) bytes else null }
    }

    private fun landOnStatus() {
        launchApp()
        pairAndLand()
        openTab("Status")
        waitForTag("update-card")
    }

    private fun assertPairingIntact() {
        assertTrue("the identity is still stored", AppGraph.get(context).pairing.isPaired())
        val installed = context.packageManager.getPackageInfo(pkg, 0).longVersionCode
        assertEquals("nothing was installed: still versionCode 5", 5L, installed)
    }

    @Test
    fun theUpdateRowOffersTheNewerVersion() {
        announce(newer!!)
        landOnStatus()
        eventually("Update available: 0.2.4") { hasText("Update available: 0.2.4") }
        tag("update-button").assertExists()
        shot("available")
        assertTrue("the app asked the server, with its token", fake.requestsTo("GET", "/v1/app/latest").all { it.auth == "Bearer ${fake.token}" } && fake.requestsTo("GET", "/v1/app/latest").isNotEmpty())
    }

    @Test
    fun noReleaseSaysUpToDate() {
        landOnStatus()
        eventually("Up to date") { hasText("Up to date") }
        shot("up-to-date")
    }

    /** Android's install confirmation: screenshot it, then press Cancel (never Install), and check nothing changed. */
    private fun cancelAndroidsConfirmationAndCheckNothingChanged() {
        eventually("Android's install confirmation", 90_000) {
            foregroundPackage() != pkg && hasSystemText("Cancel")
        }
        shot("install-confirmation")
        waitSystemText("Cancel").click()
        // Cancel closes Android's installer; it may leave the owner on the home screen, so open the app again.
        eventually("Android's installer closed") { foregroundPackage() != null && !(foregroundPackage() ?: "").contains("packageinstaller") }
        if (foregroundPackage() != pkg) {
            context.startActivity(
                context.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        eventually("the app in front again", 20_000) { foregroundPackage() == pkg && hasTag("top-bar") }
        waitForText("Cancelled. Nothing was changed.", timeoutMs = 30_000)
        shot("cancelled")
        assertPairingIntact()
        // The server saw the checks: latest, then the APK for versionCode 6, both with the token.
        assertTrue(fake.requestsTo("GET", "/v1/app/apk").any { it.path.contains("versionCode=6") && it.auth == "Bearer ${fake.token}" })
        // Reopening the app is still paired.
        relaunchApp()
        waitForTag("top-bar")
        assertFalse(hasText("Pair with your steward server", substring = true))
        assertPairingIntact()
    }

    /**
     * Tap Update. Where Android already allows installs from this app (Test Lab images do), the file is
     * downloaded, verified and handed to Android, whose confirmation is cancelled. Where it does not, the
     * app sends the owner straight to the one switch for this app ; flipping it from here would make
     * Android kill the app and with it this test, so the test only looks at that screen and comes back.
     */
    @Test
    fun happyPathStopsAtAndroidsConfirmationAndCancelKeepsEverything() {
        announce(newer!!)
        landOnStatus()
        eventually("Update available: 0.2.4") { hasText("Update available: 0.2.4") }
        val allowed = context.packageManager.canRequestPackageInstalls()
        clickTag("update-button")
        if (!allowed) {
            waitForPackageNot(pkg, 30_000)
            eventually("the switch screen of Phone Steward") { hasSystemTextContaining("Phone Steward") }
            shot("unknown-apps-switch")
            device.pressBack()
            backToApp()
            waitForText("One switch first")
            shot("one-switch-first")
            return
        }
        cancelAndroidsConfirmationAndCheckNothingChanged()
    }

    /**
     * Flipping "Install unknown apps" makes Android kill the app, so the in-memory update state is gone when
     * the owner returns. The app remembers the trip: on the restart it lands on Status and carries on.
     */
    @Test
    fun afterTheSwitchRestartedTheAppTheUpdateCarriesOnWithoutASecondTap() {
        assumeTrue("needs Android to allow installs from this app already", context.packageManager.canRequestPackageInstalls())
        announce(newer!!)
        launchApp()
        pairAndLand()
        com.example.phonesteward.update.UpdatePrefs.markAwaitingPermission(context)
        relaunchApp()
        waitForTag("update-card")
        shot("restarted-on-status")
        cancelAndroidsConfirmationAndCheckNothingChanged()
    }

    private fun refusalCase(step: String, expectedLine: String) {
        shell("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        landOnStatus()
        eventually("Update available: 0.2.4") { hasText("Update available: 0.2.4") }
        clickTag("update-button")
        waitForText("Update 0.2.4 refused", timeoutMs = 60_000)
        waitForText("Nothing was installed.", substring = true)
        waitForText(expectedLine, substring = true)
        shot(step)
        assertEquals("no Android installer UI for a refused download", pkg, foregroundPackage())
        assertPairingIntact()
        // It offers to try again.
        tag("update-button").assertExists()
    }

    @Test
    fun aWrongChecksumIsRefused() {
        announce(newer!!, sha = "0".repeat(64))
        refusalCase("refused-sha256", "did not match its checksum")
    }

    @Test
    fun aWrongVersionCodeIsRefused() {
        // The server announces 6 but serves this app's own APK (versionCode 5).
        val own = File(context.applicationInfo.sourceDir).readBytes()
        announce(own, versionCode = 6)
        refusalCase("refused-version", "not the version the server announced")
    }

    @Test
    fun aDifferentSignerIsRefused() {
        announce(otherSigner!!)
        refusalCase("refused-signer", "signed by someone else")
    }
}
