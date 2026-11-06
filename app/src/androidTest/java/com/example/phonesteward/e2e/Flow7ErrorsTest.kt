package com.example.phonesteward.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Flow 7, offline and errors: an unreachable server is a calm message, a revoked token is a way back to Pair. */
@RunWith(AndroidJUnit4::class)
class Flow7ErrorsTest : E2eBase() {
    override val flow = "errors"

    private val pkg get() = context.packageName

    private fun land() {
        launchApp()
        pairAndLand()
    }

    @Test
    fun anUnreachableServerIsACalmMessageNotACrash() {
        land()
        fake.stop()
        compose.onNode(hasSetTextAction()).performTextInput("Are you there?")
        clickTag("chat-send")
        // The owner is told, in plain words, that the message did not get through.
        waitForText("Could not reach the steward", substring = true)
        shot("send-offline")
        assertFalse("no raw exception text", hasText("Failed to connect", substring = true))
        assertFalse(hasText("java.", substring = true))
        // Every tab still opens; Refresh now neither crashes nor logs the owner out.
        openTab("Proposals")
        waitForTag("proposals-list")
        openTab("Status")
        clickText("Refresh now")
        Thread.sleep(1_500)
        assertEquals(pkg, foregroundPackage())
        assertTrue("still paired", hasTag("top-bar"))
        shot("status-offline")
    }

    @Test
    fun aFailingServerIsNotHammered() {
        land()
        val before = fake.jobPolls.get()
        // Like a proxy answering 503 while the backend restarts: every job poll fails at once.
        fake.jobsStatus = 503
        Thread.sleep(8_000)
        val polls = fake.jobPolls.get() - before
        assertTrue("the app polled $polls times in 8 s against a failing server; it should back off", polls <= 4)
        // And it recovers by itself when the server is back.
        fake.jobsStatus = 200
        assertEquals(pkg, foregroundPackage())
    }

    @Test
    fun aRevokedTokenSendsTheAppBackToPairWithTheFriendlyNotice() {
        land()
        fake.revoked = true
        // Nothing is tapped: the app notices by itself while it is open.
        try {
            waitForText("Pair with your steward server", substring = true, timeoutMs = 45_000)
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}\nfake: revokedAnswers=${fake.revokedAnswers.get()} jobPolls=${fake.jobPolls.get()} streamPolls=${fake.streamPolls.get()}", e)
        }
        waitForText("The server no longer accepts this device. Pair again.")
        shot("revoked")
        assertFalse(hasTag("top-bar"))
        // Pairing again works and lands on Chat.
        fake.revoked = false
        pairAndLand()
        shot("repaired")
    }

    @Test
    fun aRevokedTokenFoundByRefreshAlsoGoesBackToPair() {
        land()
        openTab("Status")
        waitForTag("permissions-summary")
        fake.revoked = true
        // The app may already have noticed by itself (the poller), so the tap can find the Pair screen.
        runCatching { clickText("Refresh now") }
        waitForText("The server no longer accepts this device. Pair again.", timeoutMs = 45_000)
        waitForText("Pair with your steward server", substring = true)
    }
}
