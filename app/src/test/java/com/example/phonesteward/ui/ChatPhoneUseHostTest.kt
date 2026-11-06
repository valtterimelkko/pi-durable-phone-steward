package com.example.phonesteward.ui

import android.content.ComponentName
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.logic.chat.ChatLog
import com.example.phonesteward.logic.chat.ChatVerbosity
import com.example.phonesteward.net.ChatEvent
import com.example.phonesteward.screen.ConfirmBroker
import com.example.phonesteward.screen.ConfirmRequest
import com.example.phonesteward.screen.PhoneUseState
import com.example.phonesteward.screen.StewardAccessibilityService
import com.example.phonesteward.testlab.ChatHost
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/**
 * A13.6 on the host: the request card (live, answered, service off), the session banner with Stop
 * and the confirm gate's question as a card in Chat.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h830dp-xxhdpi")
class ChatPhoneUseHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val allowed = mutableListOf<String>()
    private val declined = mutableListOf<String>()
    private var openedStatus = 0
    private var stopped = 0

    private val actions = ChatPhoneUseActions(
        onAllow = { allowed += it },
        onDecline = { declined += it },
        onOpenStatus = { openedStatus++ },
        onStopSession = { stopped++ },
    )

    @After
    fun tidy() {
        PhoneUseState.reset()
        ConfirmBroker.cancelPending()
        Settings.Secure.putString(
            compose.activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "",
        )
    }

    private fun serviceOn(on: Boolean) {
        val resolver = compose.activity.contentResolver
        val value = if (on) {
            ComponentName(compose.activity, StewardAccessibilityService::class.java).flattenToString()
        } else {
            ""
        }
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    }

    private fun requestEntry(requestId: String = "pu-1", status: String = "pending") =
        ChatLog.entryFor(
            ChatEvent(
                10,
                "phone_use",
                buildJsonObject {
                    put("kind", "request"); put("requestId", requestId)
                    put("task", "Clear WhatsApp's cache"); put("why", "It holds 1.2 GB"); put("atMs", 1_000L)
                },
            ),
        )!!.let { if (status == "pending") it else it.copy(phone = it.phone!!.copy(status = status)) }

    private var shown: ChatHost? = null
    private var contentReady = false

    /** Renders StewardApp once per test; a second call just updates the host's state. */
    private fun show(state: AppViewModel.AppState) {
        val host = shown ?: ChatHost(state).also { shown = it }
        if (contentReady) {
            host.state = state
            compose.waitForIdle()
            return
        }
        compose.setContent {
            PhoneStewardTheme {
                StewardApp(
                    state = host.state,
                    tab = StewardTab.Chat,
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
                    phoneUse = actions,
                )
            }
        }
        contentReady = true
        compose.waitForIdle()
    }

    private fun stateWith(vararg entries: AppViewModel.ChatEntry, pendingId: String? = "pu-1") =
        ScreenFixtures.chatState(ChatVerbosity.Medium).copy(chat = entries.toList(), phoneUsePendingId = pendingId)

    private fun countOf(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** The confirm gate's questions ride a notification, so an Allow grants it once, as the owner does. */
    private fun grantNotifications() {
        org.robolectric.Shadows.shadowOf(compose.activity.applicationContext as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** The owner's question, asked on its own thread like the runner does; returns the thread. */
    private fun askOnOwnThread(answer: java.util.concurrent.CountDownLatch, verdict: java.util.concurrent.atomic.AtomicBoolean): Thread =
        askOnOwnThread(answer, verdict, ConfirmRequest("tap", "Clear cache", "Clear", "com.android.settings"))

    private fun askOnOwnThread(
        answer: java.util.concurrent.CountDownLatch,
        verdict: java.util.concurrent.atomic.AtomicBoolean,
        request: ConfirmRequest,
    ): Thread {
        val appContext = compose.activity.applicationContext
        return Thread({
            val broker = ConfirmBroker(appContext)
            verdict.set(broker.confirm(request))
            answer.countDown()
        }, "test-confirm")
    }

    @Test
    fun `a pending request draws the live card with Allow and Not now`() {
        grantNotifications()
        serviceOn(true)
        show(stateWith(requestEntry()))
        compose.onNodeWithTag("chat-phone-request").assertIsDisplayed()
        compose.onNodeWithText("Clear WhatsApp's cache").assertIsDisplayed()
        compose.onNodeWithText("It holds 1.2 GB").assertIsDisplayed()
        compose.onNodeWithTag("chat-phone-allow").assertIsEnabled().performClick()
        compose.onNodeWithTag("chat-phone-notnow").performClick()
        compose.waitForIdle()
        assertEquals(listOf("pu-1"), allowed)
        assertEquals(listOf("pu-1"), declined)
    }

    @Test
    fun `with the service off Allow is disabled with a reason and a link to Status`() {
        serviceOn(false)
        show(stateWith(requestEntry()))
        compose.onNodeWithTag("chat-phone-allow").assertIsNotEnabled()
        compose.onNodeWithTag("chat-phone-allow-reason").assertIsDisplayed()
        compose.onNodeWithTag("chat-phone-goto-status").performClick()
        compose.waitForIdle()
        assertEquals(1, openedStatus)
        assertEquals(0, allowed.size)
    }

    @Test
    fun `an answered request shows its outcome without buttons`() {
        serviceOn(true)
        show(stateWith(requestEntry(status = "declined"), pendingId = null))
        compose.onNodeWithTag("chat-phone-outcome").assertIsDisplayed()
        compose.onNodeWithText("Not now — nothing was started.").assertIsDisplayed()
        assertEquals(0, countOf("chat-phone-allow"))
        assertEquals(0, countOf("chat-phone-notnow"))
    }

    @Test
    fun `a stale request card drops its buttons and says it has expired (N40)`() {
        serviceOn(true)
        show(stateWith(requestEntry(status = "expired"), pendingId = null))
        compose.onNodeWithTag("chat-phone-outcome").assertIsDisplayed()
        compose.onNodeWithText("This request has expired.").assertIsDisplayed()
        assertEquals(0, countOf("chat-phone-allow"))
        assertEquals(0, countOf("chat-phone-notnow"))
    }

    @Test
    fun `a request the server no longer holds pending is closed`() {
        serviceOn(true)
        show(stateWith(requestEntry(), pendingId = "pu-other"))
        compose.onNodeWithTag("chat-phone-outcome").assertIsDisplayed()
        assertEquals(0, countOf("chat-phone-allow"))
    }

    @Test
    fun `a running session shows the banner with the task and a working Stop`() {
        serviceOn(true)
        PhoneUseState.running("Clear WhatsApp's cache", 3)
        show(stateWith())
        compose.onNodeWithTag("chat-phone-banner").assertIsDisplayed()
        compose.onNodeWithText("The steward is using your phone").assertIsDisplayed()
        compose.onNodeWithTag("chat-phone-stop").performClick()
        compose.waitForIdle()
        assertEquals(1, stopped)
    }

    @Test
    fun `the confirm gate's question draws as a card in Chat and answers both ways`() {
        serviceOn(true)
        val done = java.util.concurrent.CountDownLatch(1)
        val verdict = java.util.concurrent.atomic.AtomicBoolean(false)
        askOnOwnThread(done, verdict).start()
        val until = System.currentTimeMillis() + 10_000
        while (ConfirmBroker.asking.value == null && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue("the confirm to be asked", ConfirmBroker.asking.value != null)
        show(stateWith())
        compose.onNodeWithTag("chat-confirm-card").assertIsDisplayed()
        compose.onNodeWithText("Allow this tap?").assertIsDisplayed()
        compose.onNodeWithTag("chat-confirm-allow").performClick()
        compose.waitForIdle()
        assertTrue("the answer to reach the broker", done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(verdict.get())
    }

    @Test
    fun `while the confirm shows, the screen behind it is invisible to accessibility (correction 1)`() {
        show(stateWith())
        // Prove the background content is composed and reachable before the dialog opens.
        assertTrue("the background list is on screen", countOf("chat-list") >= 1)
        assertTrue(
            "the empty-chat line is on screen",
            compose.onAllNodes(hasText("Ask your steward anything", substring = true)).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "the composer field is on screen",
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty(),
        )
        // The in-app confirmation (the trash, Start fresh, a metered model) opens over the same window.
        show(stateWith().copy(confirm = AppViewModel.PendingConfirm("Start fresh?", "The steward starts again from its records.", "Start fresh")))
        compose.onNodeWithTag("confirm-dialog").assertIsDisplayed()
        compose.onNodeWithTag("confirm-yes").assertIsDisplayed()
        compose.onNodeWithTag("confirm-no").assertIsDisplayed()
        // Nothing behind the scrim is left in the semantics tree: no text to read, no field to type
        // into, so assistive input can reach only the question and its two answers. Checked on text
        // and actions, not test tags: a cleared node keeps its tag but nothing assistive uses.
        assertEquals(
            0,
            compose.onAllNodes(hasText("Ask your steward anything", substring = true)).fetchSemanticsNodes().size,
        )
        assertEquals(0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `the deny button answers the confirm gate with a no`() {
        serviceOn(true)
        val done = java.util.concurrent.CountDownLatch(1)
        val verdict = java.util.concurrent.atomic.AtomicBoolean(true)
        askOnOwnThread(done, verdict).start()
        val until = System.currentTimeMillis() + 10_000
        while (ConfirmBroker.asking.value == null && System.currentTimeMillis() < until) Thread.sleep(50)
        show(stateWith())
        compose.onNodeWithTag("chat-confirm-deny").performClick()
        compose.waitForIdle()
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(!verdict.get())
    }

    @Test
    fun `the allow-for-this-task question asks about the app and answers both ways`() {
        serviceOn(true)
        val done = java.util.concurrent.CountDownLatch(1)
        val verdict = java.util.concurrent.atomic.AtomicBoolean(false)
        askOnOwnThread(
            done,
            verdict,
            ConfirmRequest(
                action = "allow_app",
                label = "Google Play services",
                word = "com.google.android.gms",
                pkg = "com.google.android.gms",
                timeoutMs = 30_000L,
            ),
        ).start()
        val until = System.currentTimeMillis() + 10_000
        while (ConfirmBroker.asking.value == null && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue("the question to be asked", ConfirmBroker.asking.value != null)
        show(stateWith())
        compose.onNodeWithTag("chat-confirm-card").assertIsDisplayed()
        compose.onNodeWithText("Allow this app for this task?").assertIsDisplayed()
        compose.onNodeWithText("The steward wants to use “Google Play services” for this task. It is not on your allow list. Only this task may use it.")
            .assertIsDisplayed()
        compose.onNodeWithText("Allow for this task").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        compose.onNodeWithTag("chat-confirm-allow").performClick()
        compose.waitForIdle()
        assertTrue("the answer to reach the broker", done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("the answer to be a yes", verdict.get())
    }
}
