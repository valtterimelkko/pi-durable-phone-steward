package com.example.phonesteward.e2e

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.screen.AppEntry
import com.example.phonesteward.screen.PrefsGuardListStore
import com.example.phonesteward.ui.AndroidClipboard
import com.example.phonesteward.ui.ClipboardPort

/**
 * A15.7/A15.8/A15.10/A15.11 end to end, against the fake steward on the device's loopback:
 * the copy buttons put the code block and the whole round on the clipboard, an expired request
 * card drops its buttons and says so, a round names the model that answered it (and warns when
 * it is not the chosen one), the picker shows a model's problem line and its "(alias)" mark,
 * and the picker's Show system apps switch adds an app with no launcher entry to the allow list.
 */
@RunWith(AndroidJUnit4::class)
class FlowK1CopyModelsTest : E2eBase() {
    override val flow = "copy-models"

    /**
     * What the app asked to copy, in order. The test process may not read the clipboard back
     * (since API 29 only the focused app may), so the app's own clipboard port is recorded; the
     * real clipboard is still written, so the write path is exercised too.
     */
    private val copied = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun recordCopies() {
        AppGraph.get(context).clipboard = object : ClipboardPort {
            override fun copy(label: String, text: String): Boolean {
                copied += text
                return AndroidClipboard(context).copy(label, text)
            }
        }
    }

    /** A copy icon inside a text block: several blocks are on screen, so take the first. */
    private fun clickFirstTag(tag: String) {
        val node = compose.onAllNodesWithTag(tag)[0]
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private val stewardMarkdown = "Here is the line you asked for.\n\n```python\nprint(\"hello steward\")\n```\n\nAnd a closing word."

    private fun landWithStewardMessage() {
        fake.say("assistant", stewardMarkdown)
        launchApp()
        pairAndLand()
        waitForText("Here is the line you asked for.", substring = true)
    }

    // ---- A15.7: copy ----

    @Test
    fun theCopyButtonsPutTheCodeBlockTheTextBlockAndTheRoundOnTheClipboard() {
        recordCopies()
        landWithStewardMessage()

        clickTag("md-code-copy")
        eventually("the code block copied") { copied.contains("print(\"hello steward\")") }
        assertEquals("the code alone, without the fences", "print(\"hello steward\")", copied.last())
        shot("code-copied")

        clickFirstTag("md-text-copy")
        eventually("the text block copied") { copied.contains("Here is the line you asked for.") }
        assertEquals("that block's words alone", "Here is the line you asked for.", copied.last())

        clickTag("chat-copy-round")
        eventually("the round copied") { copied.lastOrNull() == stewardMarkdown }
        shot("round-copied")
    }

    // ---- A15.8 / N40: the expired request card ----

    @Test
    fun anExpiredRequestCardDropsItsButtonsAndSaysSo() {
        enableAccessibilityService()
        fake.ask("Clear WhatsApp's cache", "It holds 1.2 GB")
        fake.notPendingStatus = "expired"
        launchApp()
        pairAndLand()
        waitForTag("chat-phone-request")
        waitForText("Clear WhatsApp's cache")
        eventually("the Allow to be tappable") {
            runCatching { tag("chat-phone-allow").fetchSemanticsNode() }.isSuccess
        }
        clickTag("chat-phone-allow")
        waitForText("This request has expired.")
        shot("expired-card")
        assertTrue("the Allow button is gone", !hasTag("chat-phone-allow"))
        assertTrue("Not now is gone too", !hasTag("chat-phone-notnow"))
        assertEquals("the answer reached the server", listOf("pu-e2e-1" to true), fake.phoneUseAnswers.toList())
    }

    // ---- A15.10: the model is never a surprise ----

    @Test
    fun aRoundAnsweredByAnotherModelNamesItAndWarns() {
        fake.say("assistant", "The cache is 1.2 GB.", model = Triple("openai-codex", "gpt-6.1-sol", "GPT 6.1 Sol"))
        launchApp()
        pairAndLand()
        waitForText("Answered by GPT 6.1 Sol, not GLM 5.3 Flash")
        shot("model-warning")
    }

    @Test
    fun aRoundFromTheChosenModelNamesItWithoutAWarning() {
        fake.say("assistant", "The cache is 1.2 GB.", model = Triple("zai", "glm-5.3-flash", "GLM 5.3 Flash"))
        launchApp()
        pairAndLand()
        waitForText("The cache is 1.2 GB.")
        assertEquals(1, tagCount("chat-round-model"))
        assertEquals(0, tagCount("chat-round-model-warning"))
        shot("model-line")
    }

    @Test
    fun thePickerShowsAProblemLineAndAnAliasMarkAndStillChooses() {
        fake.models = fake.models + listOf(
            FakeSteward.FakeModel(
                "openai-codex", "gpt-6.1-sol", "GPT 6.1 Sol", "subscription",
                problem = "its sign-in on the server has expired",
            ),
            FakeSteward.FakeModel(
                "anthropic", "~claude-haiku-latest", "Claude Haiku (follows the newest Claude Haiku)",
                "subscription", alias = true,
            ),
        )
        launchApp()
        pairAndLand()
        waitForText("GLM 5.3 Flash")
        clickTag("chat-model")
        waitForTag("model-picker")
        tag("model-list").performScrollToNode(androidx.compose.ui.test.hasText("its sign-in on the server has expired"))
        waitForText("its sign-in on the server has expired")
        waitForText("Claude Haiku (follows the newest Claude Haiku) (alias)")
        shot("model-problem-and-alias")
        clickText("GPT 6.1 Sol")
        eventually("the choice to reach the server") { fake.requestsTo("PUT", "/v1/agent/settings").isNotEmpty() }
        val put = fake.requestsTo("PUT", "/v1/agent/settings").last()
        assertTrue("the chosen model was sent", put.body.contains("gpt-6.1-sol"))
    }

    // ---- A15.11: Show system apps in the picker ----

    @Test
    fun thePickerOffersAnAppWithNoLauncherEntryBehindTheSwitch() {
        val store = PrefsGuardListStore(context)
        store.resetToDefaults()
        AppGraph.get(context).installedApps = FakeInstalledApps(
            listOf(AppEntry("com.android.settings", "Settings")),
            systemApps = listOf(AppEntry("com.google.android.gms", "Google Play services")),
        )
        launchApp()
        pairAndLand()
        openTab("Rules")
        closeKeyboard()
        waitForText("Photo window")
        clickTag("phone-use-allow-add")
        waitForTag("app-picker")
        waitForTag("app-picker-system")
        assertEquals("a launcher-less app is not offered while the switch is off", 0, tagCount("app-picker-item-com.google.android.gms"))
        clickTag("app-picker-system")
        waitForTag("app-picker-item-com.google.android.gms")
        waitForText("Google Play services")
        shot("system-apps")
        clickTag("app-picker-item-com.google.android.gms")
        eventually("the app to be allowed") { PrefsGuardListStore(context).load().allow.contains("com.google.android.gms") }
        assertTrue(PrefsGuardListStore(context).load().allow.contains("com.google.android.gms"))
        PrefsGuardListStore(context).resetToDefaults()
    }

    // ---- helpers ----

    @After
    fun switchTheServiceOff() {
        runCatching {
            shell("settings put secure enabled_accessibility_services ''")
            shell("settings put secure accessibility_enabled 0")
        }
        // The guard-list prefs are not part of E2eBase's reset; leave the defaults behind.
        runCatching { PrefsGuardListStore(context).resetToDefaults() }
    }

    /** The pairing field leaves the keyboard open; with it up, a tap on the Rules list would miss. */
    private fun closeKeyboard() {
        if (shell("dumpsys input_method").contains("mInputShown=true")) {
            device.pressBack()
            Thread.sleep(500)
        }
    }

    /** Enables the app's accessibility service through the shell, as Flow J1 does, so Allow is live. */
    private fun enableAccessibilityService() {
        val component = "${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/" +
            "com.example.phonesteward.screen.StewardAccessibilityService"
        shell("settings put secure accessibility_enabled 1")
        shell("settings put secure enabled_accessibility_services $component")
        Thread.sleep(700)
    }
}
