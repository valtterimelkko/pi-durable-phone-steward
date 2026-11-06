package com.example.phonesteward.e2e

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem

/**
 * Flow 9, uninstalls (0.2.4). Really uninstalls on the Test Lab device: three tiny invented helper apps
 * (no code, built by `app/scripts/build-e2e.sh` into the test APK's assets) are installed with `pm install`,
 * the fake steward proposes removing them, and Android's own uninstall dialog is answered with UiAutomator.
 *
 * Live 2026-10-08 the app reported every approved uninstall as "cancelled", because Android's uninstall screen
 * gives no result code. The outcome must be what is on the phone afterwards: these tests assert the fake
 * server received `done` for the apps that went and `cancelled` for the app the owner kept, and the result line.
 */
@RunWith(AndroidJUnit4::class)
class Flow9UninstallTest : E2eBase() {
    override val flow = "uninstall"

    private val appPkg get() = context.packageName
    private val helperNames = listOf("alpha", "beta", "gamma")
    private fun pkgOf(name: String) = "com.example.e2ehelper.$name"

    private fun asset(name: String): ByteArray? =
        runCatching { instrumentation.context.assets.open(name).use { it.readBytes() } }.getOrNull()

    @Before
    fun requireHelpers() {
        assumeTrue(
            "helper-*.apk are not in the test APK: build with app/scripts/build-e2e.sh",
            helperNames.all { asset("helper-$it.apk") != null },
        )
        helperNames.forEach { shell("pm uninstall ${pkgOf(it)}") } // a clean start, whatever an earlier run left
    }

    @After
    fun removeHelpers() {
        helperNames.forEach { runCatching { shell("pm uninstall ${pkgOf(it)}") } }
    }

    /** `pm install` over stdin (no files on shared storage): returns the tool's answer. */
    private fun install(name: String) {
        val bytes = asset("helper-$name.apk")!!
        val (out, input) = instrumentation.uiAutomation.executeShellCommandRw("pm install -r -S ${bytes.size}")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(bytes) }
        val answer = ParcelFileDescriptor.AutoCloseInputStream(out).bufferedReader().readText()
        assertTrue("pm install of helper-$name said: $answer", answer.contains("Success"))
        assertTrue("helper-$name is installed", isInstalled(pkgOf(name)))
    }

    private fun isInstalled(pkg: String): Boolean =
        shell("pm list packages $pkg").lineSequence().any { it.trim() == "package:$pkg" }

    private fun proposal(vararg names: String) = Proposal(
        proposalId = "pr-helpers",
        createdAtMs = System.currentTimeMillis() - 86_400_000L,
        source = "agent",
        title = "Remove the helper apps",
        reason = "Invented apps for the uninstall test.",
        bytesEstimate = names.size * 1_000_000L,
        status = "open",
        items = names.map {
            ProposalItem(
                itemId = "it-$it", kind = "uninstall_app",
                target = buildJsonObject { put("packageName", pkgOf(it)); put("label", "Invented Helper ${it.replaceFirstChar(Char::uppercase)}") },
                bytesEstimate = 1_000_000L,
            )
        },
    )

    private fun land(p: Proposal) {
        fake.addProposal(p)
        launchApp()
        pairAndLand()
        openTab("Proposals")
        waitForTag("proposals-list")
        waitForText(p.title)
    }

    /** Waits for Android's uninstall dialog for the app with this label (its own UI) and screenshots it. */
    private fun waitForUninstallDialog(name: String) {
        val label = "Invented Helper ${name.replaceFirstChar(Char::uppercase)}"
        waitForPackageNot(appPkg, 30_000)
        eventually("Android's uninstall dialog for $label", 30_000) {
            hasSystemText("Cancel") && (hasSystemText("OK") || hasSystemText("Uninstall")) && hasSystemTextContaining(label)
        }
        shot("android-dialog-$name")
    }

    private fun pressOk() = waitSystemText("OK", "Uninstall").click()
    private fun pressCancel() = waitSystemText("Cancel").click()

    private fun reported(): Map<String, String> {
        eventually("the execution report reached the steward") { fake.requestsTo("POST", "/v1/proposals/pr-helpers/execution").isNotEmpty() }
        return fake.requestsTo("POST", "/v1/proposals/pr-helpers/execution").last().json["items"]!!.jsonArray.associate {
            it.jsonObject["itemId"]!!.jsonPrimitive.content to it.jsonObject["outcome"]!!.jsonPrimitive.content
        }
    }

    @Test
    fun anApprovedUninstallReallyRemovesTheAppAndReportsDone() {
        install("alpha")
        land(proposal("alpha"))
        shot("before")
        click(tag("approve-button"))
        waitForUninstallDialog("alpha")
        pressOk()
        waitForText("Uninstalled the app.", timeoutMs = 60_000)
        shot("result-done")
        assertFalse("the app is really gone", isInstalled(pkgOf("alpha")))
        assertEquals(mapOf("it-alpha" to "done"), reported())
        assertEquals("executed", fake.statusOf("pr-helpers"))
        assertEquals("Uninstalled the app.", resultText())
    }

    @Test
    fun cancellingTheDialogKeepsTheAppAndReportsCancelled() {
        install("alpha")
        land(proposal("alpha"))
        click(tag("approve-button"))
        waitForUninstallDialog("alpha")
        pressCancel()
        waitForText("The app was not uninstalled", substring = true, timeoutMs = 60_000)
        shot("result-cancelled")
        assertTrue("the app is still installed", isInstalled(pkgOf("alpha")))
        assertEquals(mapOf("it-alpha" to "cancelled"), reported())
        // A6.1: nothing done returns the proposal to open, so the owner can approve again.
        eventually("the proposal is back in the list") { hasTag("approve-button") }
        assertEquals("open", fake.statusOf("pr-helpers"))
    }

    @Test
    fun aMixedBatchReportsEachAppByWhatIsOnThePhone() {
        // alpha: the owner presses OK. beta: the owner presses Cancel. gamma: removed by hand before the review (N31: left out).
        install("alpha")
        install("beta")
        land(proposal("alpha", "beta", "gamma"))
        click(tag("approve-button"))
        waitForUninstallDialog("alpha")
        pressOk()
        // The dialogs come one after another; the next one is beta's (gamma is already gone: no dialog).
        waitForUninstallDialog("beta")
        pressCancel()
        // gamma is already gone: the card marks it "Already removed" (N31), so it is not part of the approval and, since the
        // unticked are not reported (S12), the result counts the two apps that were up for decision.
        waitForText("Uninstalled 1 of 2 apps.", timeoutMs = 60_000)
        shot("result-mixed")
        assertFalse("alpha is gone", isInstalled(pkgOf("alpha")))
        assertTrue("beta was kept", isInstalled(pkgOf("beta")))
        assertEquals(mapOf("it-alpha" to "done", "it-beta" to "cancelled"), reported())
        assertEquals("executed", fake.statusOf("pr-helpers"))
    }

    // ---- N31 (A10.8): apps already gone ----

    @Test
    fun anAppAlreadyRemovedIsMarkedAndLeftOutOfTheApproval() {
        // alpha is installed; beta was never installed here (as if the owner removed it by hand).
        install("alpha")
        land(proposal("alpha", "beta"))
        waitForText("Already removed", substring = true)
        shot("already-removed")
        click(tag("approve-button"))
        waitForUninstallDialog("alpha")
        pressOk()
        waitForText("Uninstalled the app.", timeoutMs = 60_000)
        shot("already-removed-result")
        assertFalse("alpha is gone", isInstalled(pkgOf("alpha")))
        // S12: the item that was not approved is not reported at all.
        assertEquals(mapOf("it-alpha" to "done"), reported())
    }

    @Test
    fun aProposalWhoseAppsAreAllGoneSettlesItselfWithoutATapOrADialog() {
        fake.addProposal(proposal("beta", "gamma")) // neither is installed
        launchApp()
        pairAndLand()
        eventually("the proposal settled by itself", 40_000) { fake.statusOf("pr-helpers") == "executed" }
        assertEquals(mapOf("it-beta" to "done", "it-gamma" to "done"), reported())
        assertEquals("no Android dialog opened", appPkg, foregroundPackage())
        openTab("Proposals")
        waitForText("No open proposals")
        assertFalse("no result card for a quiet settlement", hasTag("result-card"))
        shot("settled-quietly")
    }
}
