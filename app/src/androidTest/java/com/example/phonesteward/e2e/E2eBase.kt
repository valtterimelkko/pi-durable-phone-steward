package com.example.phonesteward.e2e

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import com.example.phonesteward.MainActivity
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.android.KeystoreIdentityStore
import com.example.phonesteward.testlab.ScreenShots

/**
 * The base of every L4 end-to-end flow: a fake steward on loopback, the real [MainActivity] with
 * the real Keystore identity store, Compose for the app's own screens, UiAutomator for Android's.
 * Each test starts from a cold, unpaired app and cleans up after itself.
 */
abstract class E2eBase {

    @get:Rule
    val compose = createEmptyComposeRule()

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context: Context get() = instrumentation.targetContext
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    protected lateinit var fake: FakeSteward
    private var scenario: ActivityScenario<MainActivity>? = null

    /** Name used in screenshot file names: `e2e-<flow>-<step>.png`. */
    protected abstract val flow: String

    @Before
    fun e2eSetUp() {
        // Flow J1 enables the real accessibility service through the shell; UiAutomation suppresses
        // accessibility services unless this flag is set, and it is read when UiAutomation connects —
        // which pressHome below is the first to trigger. The proofs set the same flag (PhoneUseProofTest).
        androidx.test.uiautomator.Configurator.getInstance().uiAutomationFlags =
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        listOf("phone_steward_chat", "phone_steward_done_jobs", "phone_steward_app", "phone_steward_update", "phone_steward_identity")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        runCatching { KeystoreIdentityStore(context).clear() }
        AppGraph.identityStoreFactory = { KeystoreIdentityStore(it) }
        AppGraph.resetForTest()
        device.pressHome()
        fake = FakeSteward().also { it.start() }
        // Granted state the flows rely on, unless a flow changes it: nothing special.
    }

    @After
    fun e2eTearDown() {
        runCatching { scenario?.close() }
        scenario = null
        runCatching { WorkManager.getInstance(context).cancelAllWork().result.get() }
        fake.stop()
        runCatching { KeystoreIdentityStore(context).clear() }
        AppGraph.resetForTest()
    }

    // ---- launching ----

    protected fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    /** Restart the activity (a configuration or process-light restart): the saved settings must survive it. */
    protected fun recreateActivity() {
        scenario!!.recreate()
        compose.waitForIdle()
    }

    /** A fresh MainActivity (the old one is closed): what the owner sees after closing and reopening the app. */
    protected fun relaunchApp() {
        runCatching { scenario?.close() }
        AppGraph.resetForTest()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    protected fun <T> withActivity(block: (MainActivity) -> T): T {
        var result: Any? = null
        scenario!!.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // ---- waiting and looking ----

    protected fun eventually(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < end) {
            try {
                // Push the app's frames first: state that changed on a background thread reaches the screen
                // (and the semantics tree) only while the test thread is inside a Compose call.
                compose.waitForIdle()
                if (check()) return
            } catch (t: Throwable) {
                last = t
            }
            Thread.sleep(150)
        }
        fail("Timed out after ${timeoutMs / 1000} s waiting for: $what${last?.let { " (last error: $it)" } ?: ""}\nScreen: ${describeScreen()}")
    }

    protected fun hasTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    protected fun tagCount(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    protected fun hasText(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    protected fun waitForTag(tag: String, timeoutMs: Long = 20_000) = eventually("tag '$tag'", timeoutMs) { hasTag(tag) }
    protected fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 20_000) =
        eventually("text '$text'", timeoutMs) { hasText(text, substring) }
    protected fun waitForNoText(text: String, substring: Boolean = false, timeoutMs: Long = 20_000) =
        eventually("text '$text' to go", timeoutMs) { !hasText(text, substring) }

    protected fun tag(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)
    protected fun text(text: String, substring: Boolean = false): SemanticsNodeInteraction =
        compose.onNodeWithText(text, substring = substring)

    /** Clicks a node, scrolling to it first when it sits in a scrolling column. */
    protected fun click(node: SemanticsNodeInteraction) {
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    /** The text of the result card on Proposals (the card itself is not a merged node). */
    protected fun resultText(): String =
        compose.onNode(
            androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("result-card")) and
                androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text),
        ).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }

    protected fun clickText(text: String, substring: Boolean = false) = click(text(text, substring))
    protected fun clickTag(tag: String) = click(tag(tag))

    protected fun openTab(label: String) {
        compose.onAllNodesWithText(label)[0].performClick()
        compose.waitForIdle()
    }

    /**
     * The test clock drives Compose's frames, so a state change reaches the real screen only while the
     * test thread is inside a Compose call: settle first, or the screenshot shows the previous frame.
     */
    protected fun shot(step: String) {
        runCatching {
            compose.waitForIdle()
            Thread.sleep(250)
            compose.waitForIdle()
            Thread.sleep(250)
        }
        ScreenShots.save("e2e-$flow-$step")
    }

    // ---- pairing through the real screen ----

    protected fun pairThroughUi(code: String = FakeSteward.PAIRING_CODE) {
        waitForText("Pair with your steward server", substring = true)
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement(fake.baseUrl)
        fields[1].performTextInput(code)
        compose.waitForIdle()
        click(text("Pair"))
    }

    /** Pairs and waits for the Chat screen. */
    protected fun pairAndLand() {
        pairThroughUi()
        waitForTag("top-bar")
        waitForTag("chat-list")
    }

    // ---- UiAutomator and shell ----

    protected fun shell(command: String): String =
        // The "do not suppress" connection: a plain UiAutomation switches every other accessibility
        // service off while it is connected (see ProofShots), which would end Flow J1's session.
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command).use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
            }

    /** Every visible text and content description on the current screen, for failure messages. */
    protected fun describeScreen(): String = try {
        val out = ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        val xml = out.toString(Charsets.UTF_8)
        val pkg = Regex("package=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)
        val texts = Regex("(?:text|content-desc)=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.distinct().take(60).toList()
        "pkg=$pkg texts=$texts"
    } catch (t: Throwable) {
        "(screen dump failed: $t)"
    }

    /** The package of the window that has focus, from the window manager (UiAutomator's own answer is unreliable here). */
    protected fun foregroundPackage(): String? {
        val out = shell("dumpsys window")
        val line = out.lineSequence().firstOrNull { it.contains("mCurrentFocus=") && !it.contains("=null") } ?: return null
        return Regex("([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+").find(line)?.groupValues?.get(1)
    }

    protected fun waitForPackageNot(pkg: String, timeoutMs: Long = 15_000) =
        eventually("a screen other than $pkg", timeoutMs) { foregroundPackage() != pkg }

    protected fun waitForPackage(pkg: String, timeoutMs: Long = 15_000) =
        eventually("package $pkg in front (was ${foregroundPackage()})", timeoutMs) { foregroundPackage() == pkg }

    /** Waits for any of [labels] (case-insensitive, whole text) in the system UI and returns the first match. */
    protected fun waitSystemText(vararg labels: String, timeoutMs: Long = 20_000): androidx.test.uiautomator.UiObject2 {
        val selectors = labels.map { By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(it), java.util.regex.Pattern.CASE_INSENSITIVE)) }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            for (s in selectors) device.findObject(s)?.let { return it }
            Thread.sleep(200)
        }
        fail("None of ${labels.toList()} appeared in the system UI within ${timeoutMs / 1000} s. Screen: ${describeScreen()}")
        error("unreachable")
    }

    protected fun hasSystemText(text: String): Boolean =
        device.hasObject(By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(text), java.util.regex.Pattern.CASE_INSENSITIVE)))

    protected fun hasSystemTextContaining(text: String): Boolean =
        device.hasObject(By.textContains(text)) || device.hasObject(By.descContains(text))

    /** Returns to the app from a system screen and waits for the app's top bar. */
    protected fun backToApp() {
        var tries = 0
        while (foregroundPackage() != context.packageName && tries < 4) {
            device.pressBack()
            Thread.sleep(700)
            tries++
        }
        eventually("the app back in front", 15_000) { foregroundPackage() == context.packageName && hasTag("top-bar") }
    }
}
