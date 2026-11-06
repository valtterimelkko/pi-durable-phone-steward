package com.example.phonesteward.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.BuildConfig
import com.example.phonesteward.android.AppGraph

/** Flow 1, Pair: the real pairing screen against the fake steward. */
@RunWith(AndroidJUnit4::class)
class Flow1PairTest : E2eBase() {
    override val flow = "pair"

    @Test
    fun pairScreenOffersTheServerAddressAlready() {
        launchApp()
        waitForText("Pair with your steward server", substring = true)
        // The server address is not something the owner should have to type on a first run.
        waitForText(AppGraph.DEFAULT_BASE_URL)
        shot("start")
    }

    @Test
    fun wrongCodeShowsTheFriendlyError() {
        launchApp()
        pairThroughUi(code = "ZZZZ-9999")
        waitForText("That code did not work. Ask for a new one.")
        shot("wrong-code")
        // Still on the pairing screen, nothing but the pairing call reached the server.
        assertTrue(hasText("Pair with your steward server", substring = true))
        assertFalse(hasTag("top-bar"))
        val pairs = fake.requestsTo("POST", "/v1/pair")
        assertEquals(1, pairs.size)
        assertEquals("ZZZZ-9999", pairs[0].str("code"))
        assertNotNull(pairs[0].json["deviceName"])
        assertTrue("no authenticated call may happen before pairing", fake.requests.all { it.auth == null })
        assertFalse("the error must be calm, not a stack trace", hasText("Exception", substring = true))
    }

    @Test
    fun rightCodeLandsOnChatAndStatusShowsPaired() {
        launchApp()
        pairAndLand()
        shot("chat-after-pairing")
        assertTrue("the Chat title is Steward", hasText("Steward"))
        // The paired call, then the device-status post with the app's version.
        val pairs = fake.requestsTo("POST", "/v1/pair")
        assertEquals(1, pairs.size)
        assertEquals(FakeSteward.PAIRING_CODE, pairs[0].str("code"))
        eventually("device status posted") { fake.requestsTo("POST", "/v1/device/status").isNotEmpty() }
        val status = fake.requestsTo("POST", "/v1/device/status")[0]
        assertEquals("Bearer ${fake.token}", status.auth)
        assertEquals(BuildConfig.VERSION_NAME, status.str("appVersion"))

        openTab("Status")
        waitForText(fake.baseUrl)
        shot("status-paired")
        assertTrue(hasText("Phone Steward ${BuildConfig.VERSION_NAME}", substring = true))
    }

    @Test
    fun pairingSurvivesClosingAndReopeningTheApp() {
        launchApp()
        pairAndLand()
        relaunchApp()
        waitForTag("top-bar")
        assertFalse(hasText("Pair with your steward server", substring = true))
        openTab("Status")
        waitForText(fake.baseUrl)
    }

    @Test
    fun pairingAgainstAnAddressNobodyAnswersIsCalm() {
        launchApp()
        waitForText("Pair with your steward server", substring = true)
        val dead = fake.baseUrl
        fake.stop()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement(dead)
        fields[1].performTextReplacement(FakeSteward.PAIRING_CODE)
        compose.waitForIdle()
        click(text("Pair"))
        waitForText("Could not reach the server", substring = true)
        shot("unreachable")
        assertFalse("no raw exception text for the owner", hasText("Failed to connect", substring = true))
        assertFalse(hasText("java.", substring = true))
        assertFalse(hasTag("top-bar"))
    }
}
