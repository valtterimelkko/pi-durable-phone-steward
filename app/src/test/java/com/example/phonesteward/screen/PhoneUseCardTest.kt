package com.example.phonesteward.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.logic.shizuku.RestrictedSettings
import com.example.phonesteward.logic.shizuku.ShellResult
import com.example.phonesteward.logic.shizuku.ShellRunner
import com.example.phonesteward.logic.shizuku.ShizukuAccess
import com.example.phonesteward.logic.shizuku.ShizukuState
import com.example.phonesteward.logic.shizuku.ShizukuText
import com.example.phonesteward.ui.theme.PhoneStewardTheme

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h830dp-xxhdpi")
class PhoneUseCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var stops = 0
    private var appInfo = 0
    private var accessibility = 0
    private val answers = mutableListOf<Boolean>()
    private var allowShizuku = 0
    private val shizukuActions = mutableListOf<ShizukuText.Action>()

    private fun show(
        serviceOn: Boolean,
        ui: PhoneUseUi = PhoneUseUi.Idle,
        asking: ConfirmRequest? = null,
        restricted: RestrictedUi = RestrictedUi(RestrictedSettings.Offer.Hidden),
    ) {
        compose.setContent {
            PhoneStewardTheme {
                PhoneUseCard(
                    serviceOn = serviceOn, ui = ui, asking = asking, restricted = restricted,
                    onStop = { stops++ },
                    onOpenAppInfo = { appInfo++ }, onOpenAccessibility = { accessibility++ },
                    onAllowWithShizuku = { allowShizuku++ }, onShizukuAction = { shizukuActions += it },
                    onAnswer = { allowed, _ -> answers += allowed },
                )
            }
        }
    }

    @Test
    fun `with the service off the card explains the restricted settings path and there is no task box`() {
        show(serviceOn = false)
        compose.onNodeWithTag("phone-use-restricted-note").assertIsDisplayed()
        assertTrue(RESTRICTED_SETTINGS_STEPS.contains("Allow restricted settings"))
        assertTrue(RESTRICTED_SETTINGS_STEPS.contains("three-dot menu"))
        assertTrue(RESTRICTED_SETTINGS_STEPS.contains("outside Google Play"))
        // A13.6: the task box is gone; the owner asks in Chat.
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-task"))
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-start"))
        compose.onNodeWithText("Ask the steward in Chat to use your phone.").assertIsDisplayed()
        compose.onNodeWithTag("phone-use-app-info").performClick()
        compose.onNodeWithTag("phone-use-accessibility").performClick()
        assertEquals(1, appInfo)
        assertEquals(1, accessibility)
    }

    // ---- A12: the steps are in the order that works, and name Allow with Shizuku ----

    @Test
    fun `the steps put Accessibility first, then the app info menu, then Allow with Shizuku`() {
        val steps = RESTRICTED_SETTINGS_STEPS
        val accessibility = steps.indexOf("switch it on")
        val restricted = steps.indexOf("Restricted setting")
        val menu = steps.indexOf("three-dot menu")
        val shizuku = steps.indexOf("Allow with Shizuku")
        assertTrue("every part is there: $steps", listOf(accessibility, restricted, menu, shizuku).all { it >= 0 })
        assertTrue("Accessibility, then Android's answer, then the menu, then Shizuku: $steps", accessibility < restricted && restricted < menu && menu < shizuku)
        assertTrue(steps.contains("Some OEM builds, e.g. OxygenOS, have no three-dot menu"))
        assertFalse("no developer terms", Regex("appops|adb|command", RegexOption.IGNORE_CASE).containsMatchIn(steps))
    }

    @Test
    fun `with Shizuku ready the Allow with Shizuku button shows and asks`() {
        show(serviceOn = false, restricted = RestrictedUi(RestrictedSettings.Offer.Button))
        compose.onNodeWithTag("phone-use-allow-shizuku").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, allowShizuku)
        compose.onNodeWithText("Allow with Shizuku").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-allow-result"))
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-shizuku-note"))
    }

    @Test
    fun `while it runs the button is replaced by a working line`() {
        show(serviceOn = false, restricted = RestrictedUi(RestrictedSettings.Offer.Button, working = true))
        compose.onNodeWithTag("phone-use-allow-working").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-allow-shizuku"))
    }

    @Test
    fun `after success the result says what to do next and the Accessibility button is there`() {
        show(serviceOn = false, restricted = RestrictedUi(RestrictedSettings.Offer.Button, result = RestrictedSettings.Outcome.Allowed))
        compose.onNodeWithTag("phone-use-allow-result").assertIsDisplayed()
        compose.onNodeWithText("Allowed. Now switch Phone Steward on in Accessibility.").assertIsDisplayed()
        compose.onNodeWithTag("phone-use-accessibility").assertIsDisplayed().performClick()
        assertEquals(1, accessibility)
    }

    @Test
    fun `after a failure the result gives the reason and the button stays for another try`() {
        show(serviceOn = false, restricted = RestrictedUi(RestrictedSettings.Offer.Button, result = RestrictedSettings.Outcome.Failed("exit 255: Error: Unknown operation")))
        compose.onNodeWithText("Could not allow it: exit 255: Error: Unknown operation.").assertIsDisplayed()
        compose.onNodeWithTag("phone-use-allow-shizuku").assertIsDisplayed()
    }

    private fun notReady(state: ShizukuState, action: ShizukuText.Action) {
        val offer = RestrictedSettings(
            object : ShizukuAccess {
                override fun state() = state
                override fun shell() = ShellRunner { _, _ -> ShellResult(0, "", "") }
                override fun requestPermission() = Unit
            },
            "com.example.phonesteward", 33,
        ).offer(serviceOn = false)
        show(serviceOn = false, restricted = RestrictedUi(offer))
        compose.onNodeWithTag("phone-use-shizuku-note").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-allow-shizuku"))
        compose.onNodeWithTag("phone-use-shizuku-action").assertIsDisplayed().performClick()
        assertEquals("$state", listOf(action), shizukuActions)
        compose.onNodeWithText(ShizukuText.label(action)).assertIsDisplayed()
    }

    @Test
    fun `Shizuku missing says so and offers Get Shizuku`() = notReady(ShizukuState.ABSENT, ShizukuText.Action.GET_SHIZUKU)

    @Test
    fun `Shizuku stopped says to start it and offers Open Shizuku`() = notReady(ShizukuState.NOT_RUNNING, ShizukuText.Action.OPEN_SHIZUKU)

    @Test
    fun `Shizuku not yet allowed for the steward offers the allow question`() = notReady(ShizukuState.NO_PERMISSION, ShizukuText.Action.ALLOW_SHIZUKU)

    @Test
    fun `when Shizuku cannot be opened the tap is never silent`() {
        show(
            serviceOn = false,
            restricted = RestrictedUi(
                RestrictedSettings.Offer.NotReady(ShizukuState.NOT_RUNNING, "x", ShizukuText.Action.OPEN_SHIZUKU),
                notice = RestrictedSettings.COULD_NOT_OPEN,
            ),
        )
        compose.onNodeWithTag("phone-use-shizuku-notice").assertIsDisplayed()
        compose.onNodeWithText("Could not open Shizuku: open it from your apps.").assertIsDisplayed()
    }

    @Test
    fun `no notice means no notice line`() {
        show(serviceOn = false, restricted = RestrictedUi(RestrictedSettings.Offer.Button))
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-shizuku-notice"))
    }

    @Test
    fun `with the service on there is no Shizuku offer even if one is passed`() {
        show(serviceOn = true, restricted = RestrictedUi(RestrictedSettings.Offer.Button))
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-allow-shizuku"))
    }

    @Test
    fun `below Android 13 there is no Allow with Shizuku button`() {
        show(serviceOn = false, restricted = RestrictedUi(RestrictedSettings.Offer.Hidden))
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-allow-shizuku"))
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-shizuku-note"))
        compose.onNodeWithTag("phone-use-accessibility").assertIsDisplayed()
    }

    @Test
    fun `with the service on the card keeps the state row and the ask-in-Chat line, and nothing to type`() {
        show(serviceOn = true)
        compose.onNodeWithText("Ask the steward in Chat to use your phone.").assertIsDisplayed()
        compose.onNodeWithText("Accessibility service").assertIsDisplayed()
        compose.onNodeWithText("On").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-task"))
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-start"))
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-restricted-note"))
    }

    @Test
    fun `a running session shows its step and a Stop button`() {
        show(serviceOn = true, ui = PhoneUseUi.Running("Open storage", 3))
        compose.onNodeWithText("Running, step 3").assertIsDisplayed()
        compose.onNodeWithTag("phone-use-stop").assertIsDisplayed().performClick()
        assertEquals(1, stops)
        assertEquals(0, compose.onAllNodesWithTagCount("phone-use-start"))
    }

    @Test
    fun `the confirm question shows what the steward wants to tap and answers both ways`() {
        show(serviceOn = true, ui = PhoneUseUi.Running("t", 2, "Uninstall"), asking = ConfirmRequest("tap", "Uninstall", "uninstall", "com.android.settings"))
        compose.onNodeWithText("The steward wants to tap “Uninstall”", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("phone-use-deny").performClick()
        assertEquals(listOf(false), answers)
    }

    @Test
    fun `an ended session says why in plain words`() {
        show(serviceOn = true, ui = PhoneUseUi.Ended("denied", "Stopped at the mail app."))
        compose.onNodeWithTag("phone-use-ended").assertIsDisplayed()
        compose.onNodeWithText("reached an app the steward may not use", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Stopped at the mail app.").assertIsDisplayed()
        assertFalse(PhoneUseState.endText("stopped").isEmpty())
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
