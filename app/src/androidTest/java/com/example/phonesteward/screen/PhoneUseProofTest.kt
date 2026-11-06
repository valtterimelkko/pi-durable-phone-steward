package com.example.phonesteward.screen

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.MainActivity
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.android.KeystoreIdentityStore
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity
import com.example.phonesteward.screen.FakeLive.Companion.detail
import com.example.phonesteward.screen.FakeLive.Companion.nodeIdWithText
import com.example.phonesteward.screen.FakeLive.Companion.nodeTexts
import com.example.phonesteward.screen.FakeLive.Companion.outcome
import com.example.phonesteward.screen.FakeLive.Companion.treePackage

/**
 * The device proof of A10.3 : the real accessibility service, enabled through UiAutomation's shell
 * (`settings put secure enabled_accessibility_services`), a session started from the app's own Status card
 * against a fake steward, the app's own screen and a Settings screen observed, one real tap, the confirm gate
 * on a committing word, a refused "Clear data", a denied app ending the session, and Stop.
 */
@RunWith(AndroidJUnit4::class)
class PhoneUseProofTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val testPackage: String get() = instrumentation.context.packageName
    private lateinit var fake: FakeLive

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity }
        override fun clear() { identity = null }
    }

    private fun shell(command: String): String =
        ProofShots.automation().executeShellCommand(command).use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }

    private fun eventually(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching { check() }.getOrDefault(false)) return
            Thread.sleep(150)
        }
        fail("Timed out after ${timeoutMs / 1000} s waiting for: $what")
    }

    @Before
    fun setUp() {
        Configurator.getInstance().uiAutomationFlags = android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        context.getSharedPreferences("phone_steward_screen", Context.MODE_PRIVATE).edit().clear().commit()
        PhoneUseState.reset()
        fake = FakeLive().also { it.start() }
        val store = MemoryIdentityStore(PairedIdentity(fake.baseUrl, "proof-token"))
        AppGraph.identityStoreFactory = { store }
        AppGraph.resetForTest()
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        // Enable the service the way a test must: through the shell's secure settings. Clear first, so
        // the write is always a CHANGE (under the test orchestrator each test restarts the process, and
        // an identical value would not re-trigger the bind).
        val component = "${context.packageName}/${StewardAccessibilityService::class.java.name}"
        shell("settings put secure enabled_accessibility_services ''")
        shell("settings put secure accessibility_enabled 1")
        shell("settings put secure enabled_accessibility_services $component")
        eventually("the accessibility service to connect") { StewardAccessibilityService.isConnected() }
    }

    @After
    fun tearDown() {
        runCatching { LiveSessionService.stop(context) }
        runCatching { eventually("the session to end", 10_000) { !PhoneUseState.isActive } }
        runCatching { ConfirmBroker.cancelPending() }
        runCatching { WorkManager.getInstance(context).cancelAllWork().result.get() }
        fake.stop()
        AppGraph.identityStoreFactory = { KeystoreIdentityStore(it) }
        AppGraph.resetForTest()
        PhoneUseState.reset()
    }

    private fun openProofScreen() {
        context.startActivity(
            Intent().setClassName(testPackage, ProofActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        eventually("the proof screen in front") { StewardAccessibilityService.instance?.foreground()?.pkg == testPackage }
    }

    private fun observe(): kotlinx.serialization.json.JsonObject = fake.awaitAnswer(fake.push("screen_observe"))

    private fun observeTexts() = observe().nodeTexts()

    private fun act(vararg args: Pair<String, String>) = fake.awaitAnswer(fake.push("screen_act", args.toMap()))

    /**
     * A13.6 removed the Status task box: a session starts from the request card's Allow in Chat. The proof is
     * about the screen machinery, so it starts the same foreground service directly (as the Chat Allow does)
     * and still shows the Status card while the session runs.
     */
    private fun startSession(scenario: ActivityScenario<MainActivity>, task: String) {
        compose.waitForIdle()
        compose.onAllNodesWithText("Status")[0].performClick()
        compose.waitForIdle()
        ProofShots.save("phone-use-status-card")
        LiveSessionService.start(context, task)
        try {
            eventually("the session to start", 20_000) { fake.startedTask == task && PhoneUseState.state.value is PhoneUseUi.Running }
            // The Status list is lazy: scroll the Phone use card into view so the screen observation
            // (which reads only what is composed) can see it.
            compose.onNodeWithTag("phone-use-stop").performScrollTo()
            compose.waitForIdle()
            ProofShots.save("phone-use-running")
        } catch (e: AssertionError) {
            ProofShots.save("phone-use-start-failed")
            throw AssertionError("${e.message}; state=${PhoneUseState.state.value}; started=${fake.startedTask}; requests=${fake.requests.takeLast(8)}")
        }
    }

    @Test
    fun observesTheAppAndSettings_taps_andStopEndsTheSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue("the service is switched on", StewardAccessibilityService.isEnabled(context))
            startSession(scenario, "Open the Storage settings page")

            // 1. The app's own screen can be read.
            val own = observe()
            assertEquals("ok", own.outcome())
            assertEquals(context.packageName, own.treePackage())
            assertTrue("the Status card is in the tree: ${own.nodeTexts().take(12)}", own.nodeTexts().any { it.contains("Phone use") || it.contains("steward can tap") || it.contains("Accessibility service") })
            ProofShots.save("phone-use-1-own-screen")

            // ... but never operated: a tap here is refused before any node is touched.
            val ownNode = own.nodeIdWithText("Status")
            if (ownNode != null) {
                val refused = act("action" to "tap", "nodeId" to ownNode)
                assertEquals("refused", refused.outcome())
                assertTrue(refused.detail().orEmpty().contains("own app"))
            }

            // 2. Settings opens and is readable.
            assertEquals("ok", act("action" to "open_app", "packageName" to "com.android.settings").outcome())
            var settings = observe()
            assertEquals("com.android.settings", settings.treePackage())
            assertTrue("Settings has readable text: ${settings.nodeTexts()}", settings.nodeTexts().size >= 3)
            ProofShots.save("phone-use-2-settings")

            // 3. One real tap.
            // On a small screen the Storage row is below the fold: scroll (also proves the scroll action).
            var scrolls = 0
            while (settings.nodeIdWithText("Storage") == null && scrolls < 5) {
                assertEquals("ok", act("action" to "scroll", "direction" to "down").outcome())
                settings = observe()
                scrolls++
            }
            val target = settings.nodeIdWithText("Storage") ?: error("no Storage row in Settings after $scrolls scrolls: ${settings.nodeTexts()}")
            val tapped = act("action" to "tap", "nodeId" to target)
            assertEquals("ok", tapped.outcome())
            assertEquals("Storage", tapped["nodeText"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
            val after = observe()
            assertEquals("com.android.settings", after.treePackage())
            assertNotEquals("the screen changed after the tap", settings.nodeTexts(), after.nodeTexts())
            ProofShots.save("phone-use-3-after-tap")

            // 4. Stop (the notification's action) ends the session and the foreground notification goes.
            val nm = context.getSystemService(NotificationManager::class.java)
            assertTrue("the ongoing notification is up", nm.activeNotifications.any { it.id == LiveSessionService.NOTIFICATION_ID })
            LiveSessionService.stop(context)
            assertEquals("stopped", fake.awaitStop())
            eventually("the state to show the session stopped") { (PhoneUseState.state.value as? PhoneUseUi.Ended)?.reason == "stopped" }
            eventually("the ongoing notification to go") { nm.activeNotifications.none { it.id == LiveSessionService.NOTIFICATION_ID } }
            ProofShots.save("phone-use-4-stopped")
        }
    }

    @Test
    fun theConfirmGateAsksOnCommittingWords_andClearDataIsNeverTapped() {
        // The proof screen lives in the test package, so it has to be on the allow list for this run.
        PrefsGuardListStore(context).addAllow(testPackage)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            LiveSessionService.start(context, "Press some buttons on the proof screen")
            eventually("the session to start") { PhoneUseState.state.value is PhoneUseUi.Running }
            openProofScreen()

            val tree = observe()
            assertEquals(testPackage, tree.treePackage())
            val storage = tree.nodeIdWithText("Storage overview")!!
            val clearCache = tree.nodeIdWithText("Clear cache")!!
            val deleteAll = tree.nodeIdWithText("Delete all")!!
            val clearData = tree.nodeIdWithText("Clear data")!!
            ProofShots.save("phone-use-5-proof-screen")

            // An ordinary tap and Clear cache go straight through.
            assertEquals("ok", act("action" to "tap", "nodeId" to storage).outcome())
            assertEquals("ok", act("action" to "tap", "nodeId" to clearCache).outcome())
            val afterTaps = observe().nodeTexts()
            assertEquals("pressed: ${afterTaps.firstOrNull { it.startsWith("pressed:") }}", 1, ProofScreen.pressesIn(afterTaps, "Storage overview"))
            assertEquals(1, ProofScreen.pressesIn(afterTaps, "Clear cache"))
            assertEquals(null, ConfirmBroker.asking.value)

            // A committing word pauses for the owner. First answer: no.
            val deleteStep = fake.push("screen_act", mapOf("action" to "tap", "nodeId" to deleteAll))
            eventually("the question for the owner") { ConfirmBroker.asking.value != null }
            val question = ConfirmBroker.asking.value!!
            assertEquals("Delete all", question.label)
            assertEquals("delete", question.word)
            ProofShots.save("phone-use-6-confirm-gate")
            ConfirmBroker.answer(false, question)
            val declined = fake.awaitAnswer(deleteStep)
            assertEquals("refused", declined.outcome())
            assertEquals("a declined tap never reaches the button", 0, ProofScreen.pressesIn(observeTexts(), "Delete all"))

            // Second answer: yes. Now the tap happens.
            val again = fake.push("screen_act", mapOf("action" to "tap", "nodeId" to deleteAll))
            eventually("the question again") { ConfirmBroker.asking.value != null }
            val questionAgain = ConfirmBroker.asking.value!!
            ConfirmBroker.answer(true, questionAgain)
            assertEquals("ok", fake.awaitAnswer(again).outcome())
            assertEquals(1, ProofScreen.pressesIn(observeTexts(), "Delete all"))

            // Clear data is never tapped, and the owner is not even asked.
            val never = act("action" to "tap", "nodeId" to clearData)
            assertEquals("refused", never.outcome())
            assertTrue(never.detail().orEmpty().contains("never"))
            assertEquals(null, ConfirmBroker.asking.value)
            assertEquals(0, ProofScreen.pressesIn(observeTexts(), "Clear data"))

            // An app on the deny list cannot be opened.
            assertEquals("refused", act("action" to "open_app", "packageName" to "com.google.android.gm").outcome())

            LiveSessionService.stop(context)
            assertEquals("stopped", fake.awaitStop())
        }
    }

    @Test
    fun enteringADeniedAppEndsTheSession() {
        PrefsGuardListStore(context).addDeny(testPackage)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            LiveSessionService.start(context, "Look around")
            eventually("the session to start") { PhoneUseState.state.value is PhoneUseUi.Running }
            // The owner (or the agent) lands in an app on the deny list: the session ends at once.
            openProofScreen()
            assertEquals("denied", fake.awaitStop())
            eventually("the state to show the denial") { (PhoneUseState.state.value as? PhoneUseUi.Ended)?.reason == "denied" }
            ProofShots.save("phone-use-7-denied")
            assertFalse(PhoneUseState.isActive)
        }
    }

    @Suppress("unused")
    private fun hasTag(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
