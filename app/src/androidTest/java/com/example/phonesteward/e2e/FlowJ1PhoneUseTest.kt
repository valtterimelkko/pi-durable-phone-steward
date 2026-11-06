package com.example.phonesteward.e2e

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.screen.ConfirmBroker
import com.example.phonesteward.screen.ConfirmRequest
import com.example.phonesteward.screen.StewardAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Flow J1, phone use from Chat (A13.6): the request card with Allow / Not now, the session banner and
 * the step lines, the ask, the short end line, Allow disabled while the service is off, the confirm
 * gate's question also as a card in Chat, and the Status card without the task box.
 */
@RunWith(AndroidJUnit4::class)
class FlowJ1PhoneUseTest : E2eBase() {

    override val flow = "phoneuse"

    private val targetContext: android.content.Context get() = instrumentation.targetContext

    @Before
    fun grantNotifications() {
        shell("pm grant ${targetContext.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
    }

    @After
    fun disableService() {
        shell("settings put secure enabled_accessibility_services ''")
    }

    private fun enableAccessibility() {
        // The proof's pattern (PhoneUseProofTest.setUp): set the UiAutomation flag immediately before the
        // shell puts, so the instrumentation does not suppress the service it is about to enable, and put
        // the switch first and the list last (the last write is what the system reconciles).
        androidx.test.uiautomator.Configurator.getInstance().uiAutomationFlags =
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val component = "${targetContext.packageName}/${StewardAccessibilityService::class.java.name}"
        shell("settings put secure accessibility_enabled 1")
        shell("settings put secure enabled_accessibility_services $component")
        // If the first write is not acted on, say it once more: the put is cheap, the wait is not.
        eventually("the accessibility service to connect", 20_000) {
            if (!StewardAccessibilityService.isConnected()) {
                shell("settings put secure enabled_accessibility_services $component")
            }
            StewardAccessibilityService.isConnected()
        }
    }

    private fun ask(task: String, why: String) = fake.ask(task, why)

    private fun land() {
        launchApp()
        pairAndLand()
        waitForTag("chat-list")
    }

    @Test
    fun aRequestAllowBannerStepsAskAndEnd() {
        enableAccessibility()
        land()
        val task = "Clear WhatsApp's cache in Settings"
        ask(task, "It holds 1.2 GB of cache.")
        waitForTag("chat-phone-request")
        waitForText(task)
        waitForText("It holds 1.2 GB of cache.")
        // The live buttons appear once the pending route confirms the request (A13.2 redraw).
        waitForTag("chat-phone-allow")
        shot("request-card")

        // Allow: the answer route, then the service joins the session the server started.
        clickTag("chat-phone-allow")
        eventually("the answer route was called") {
            fake.phoneUseAnswers.any { it.first == "pu-e2e-1" && it.second }
        }
        eventually("the session banner with the task") {
            hasTag("chat-phone-banner") && hasText("The steward is using your phone") && hasText(task)
        }
        assertTrue("the old start route was never called", fake.requestsTo("POST", "/v1/live/sessions").isEmpty())
        waitForTag("chat-phone-started")
        shot("banner")

        // Two answered screen steps become step lines in the conversation (Medium: one collapsed group).
        fake.pushStep("screen_observe")
        fake.pushStep("screen_observe")
        waitForTag("chat-phone-step-group")
        eventually("both steps answered") { fake.liveStepAnswers.size == 2 }
        eventually("the group counts both") { hasText("Used the phone · 2 steps") }
        shot("steps-collapsed")
        clickTag("chat-phone-step-group")
        // The expanded lines are texts inside the group's merged node, so assert them by text.
        waitForText("observe the screen (st-e2e-1)")
        waitForText("observe the screen (st-e2e-2)")
        shot("steps-expanded")

        // The session asks; the owner answers by typing normally.
        fake.phoneUseEvent("ask") {
            put("sessionId", fake.phoneUseSessionId); put("question", "Open WhatsApp's storage page?"); put("askId", "a-1"); put("atMs", System.currentTimeMillis())
        }
        waitForTag("chat-phone-ask")
        waitForText("Open WhatsApp's storage page?")
        shot("ask")
        compose.onNode(hasTestTag("chat-input")).performTextInput("Yes, go ahead")
        compose.waitForIdle()
        clickTag("chat-send")
        eventually("the answer went out as a normal message") {
            fake.requestsTo("POST", "/v1/chat").any { it.body.contains("Yes, go ahead") }
        }

        // A15.2: answering a session's question by typing hands the screen back to the app behind,
        // so the owner's next tap lands in the app they were using rather than in Chat.
        eventually("the task to hand the screen back (A15.2)") {
            device.currentPackageName != context.packageName
        }
        shot("handed-back")
        // The owner comes back to Steward the way they left it; the app works from its records.
        relaunchApp()
        eventually("Steward in front again") { device.currentPackageName == context.packageName }

        // The session ends: the banner goes and one short result line stays.
        fake.liveEnd = buildJsonObject {
            put("ended", true); put("reason", "finished"); put("summary", "Cleared 300 MB")
        }
        fake.phoneUseEvent("ended") {
            put("sessionId", fake.phoneUseSessionId); put("reason", "finished"); put("summary", "Cleared 300 MB")
            put("steps", 2); put("atMs", System.currentTimeMillis())
        }
        eventually("the banner to go") { !hasTag("chat-phone-banner") }
        waitForTag("chat-phone-ended")
        eventually("the end line says why and how many steps") {
            hasText("Phone session ended — done · 2 steps", substring = true)
        }
        // The ended row is the one end line: the server's summary sits under it (correction 1, finding 4).
        waitForTag("chat-phone-ended-summary")
        waitForText("Cleared 300 MB")
        shot("ended")
    }

    @Test
    fun notNowDeclinesAndStartsNothing() {
        land()
        ask("Clear a cache", "It is large.")
        waitForTag("chat-phone-request")
        waitForTag("chat-phone-notnow")
        clickTag("chat-phone-notnow")
        eventually("the decline was sent") { fake.phoneUseAnswers.any { it.first == "pu-e2e-1" && !it.second } }
        waitForTag("chat-phone-outcome")
        waitForText("Not now — nothing was started.")
        Thread.sleep(500)
        assertTrue("no session started", fake.requestsTo("POST", "/v1/live/sessions").isEmpty())
        assertTrue("no live session was polled", fake.liveNextPolls.get() == 0L)
        shot("not-now")
    }

    @Test
    fun allowIsDisabledWhileTheServiceIsOff() {
        land()
        ask("Clear a cache", "It is large.")
        waitForTag("chat-phone-request")
        waitForTag("chat-phone-allow")
        compose.onNodeWithTag("chat-phone-allow").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("chat-phone-allow-reason").assertIsDisplayed()
        shot("allow-disabled")
        compose.onNodeWithTag("chat-phone-goto-status").performClick()
        compose.waitForIdle()
        waitForTag("phone-use-ask-in-chat")
        waitForText("Ask the steward in Chat to use your phone.")
        compose.onNodeWithTag("phone-use-ask-in-chat").performScrollTo()
        compose.waitForIdle()
        shot("status-card")
    }

    @Test
    fun theStatusCardHasNoTaskBoxEvenWhileIdle() {
        land()
        openTab("Status")
        waitForTag("phone-use-ask-in-chat")
        assertTrue(!hasTag("phone-use-task"))
        assertTrue(!hasTag("phone-use-start"))
        assertTrue("no start wording either", !hasText("Let the steward use the phone"))
        waitForText("Ask the steward in Chat to use your phone.")
        compose.onNodeWithTag("phone-use-ask-in-chat").performScrollTo()
        compose.waitForIdle()
        shot("status-idle")
    }

    @Test
    fun theConfirmGateQuestionAlsoAppearsInChat() {
        land()
        val done = CountDownLatch(1)
        val verdict = AtomicBoolean(false)
        val appContext = targetContext
        Thread({
            val broker = ConfirmBroker(appContext)
            verdict.set(broker.confirm(ConfirmRequest("tap", "Clear cache", "Clear", "com.android.settings")))
            done.countDown()
        }, "flow-confirm").start()
        val until = System.currentTimeMillis() + 10_000
        while (ConfirmBroker.asking.value == null && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue("the question to be asked", ConfirmBroker.asking.value != null)
        eventually("the confirm card in Chat") { hasTag("chat-confirm-card") }
        compose.onNodeWithText("Allow this tap?").assertIsDisplayed()
        shot("confirm-card")
        clickTag("chat-confirm-allow")
        assertTrue("the answer reached the broker", done.await(10, TimeUnit.SECONDS))
        assertTrue(verdict.get())
        eventually("the card to go") { !hasTag("chat-confirm-card") }
    }
}
