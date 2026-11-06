package com.example.phonesteward.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.screen.AndroidInstalledApps
import com.example.phonesteward.screen.AppEntry
import com.example.phonesteward.screen.GuardLists
import com.example.phonesteward.screen.PrefsGuardListStore

/**
 * A14 on the real app with a fake phone: the Rules phone-use lists show apps by name, the
 * Add-an-app picker adds by search with one tap (and moves between lists), an always-denied
 * app cannot be added to allow, and an id nothing installed answers "not installed".
 *
 * Interaction is owner-real: reveal by swipe, tap at coordinates, type through the input
 * connection. The Compose test actions are deliberately avoided here — a programmatic
 * performScrollTo (BringIntoView) followed by the search field's own focus BringIntoView
 * crashes Compose 1.7 on the device ("uncancelled requests present"), and perform actions
 * can deadlock on the same animation. Plain semantics polling drives the waits.
 */
@RunWith(AndroidJUnit4::class)
class Flow4RulesAppsTest : E2eBase() {
    override val flow = "rules-apps"

    private val fakeApps = FakeInstalledApps(
        listOf(
            AppEntry("com.android.settings", "Settings"),
            AppEntry("com.android.documentsui", "Files"),
            AppEntry("com.whatsapp", "WhatsApp"),
            AppEntry("org.telegram.messenger", "Telegram"),
            AppEntry("com.android.chrome", "Chrome"),
            AppEntry("com.android.vending", "Play Store"),
            AppEntry("com.mybank.mobile", "Invented Bank"),
            AppEntry("com.example.notes", "Invented Notes"),
        ),
    )

    /** P14 addendum 1: the owner's repro — a launchable list plus one launcher-less app behind the switch. */
    private val fakeAppsWithSystem = FakeInstalledApps(
        listOf(
            AppEntry("com.android.settings", "Settings"),
            AppEntry("com.android.documentsui", "Files"),
            AppEntry("com.whatsapp", "WhatsApp"),
            AppEntry("com.example.notes", "Invented Notes"),
        ),
        systemApps = listOf(AppEntry("com.example.systemtool", "Invented System Tool")),
    )

    private val store get() = PrefsGuardListStore(context)

    /** Seeds the lists before launch: [allow] and [deny] replace the defaults. */
    private fun land(
        allow: Set<String> = setOf("com.whatsapp"),
        deny: Set<String> = setOf("com.example.gone"),
        apps: FakeInstalledApps = fakeApps,
    ) {
        store.resetToDefaults()
        store.save(GuardLists(allow = allow, deny = deny))
        AppGraph.get(context).installedApps = apps
        launchApp()
        pairAndLand()
        openTab("Rules")
        closeKeyboard()
        waitForText("Photo window")
    }

    /** The pairing field leaves the keyboard open; with it up, the swipes would type, not scroll. */
    private fun closeKeyboard() {
        if (shell("dumpsys input_method").contains("mInputShown=true")) {
            device.pressBack()
            Thread.sleep(500)
        }
    }

    @After
    fun tidyLists() {
        // The guard-list prefs are not part of E2eBase's reset; leave the defaults behind.
        runCatching { PrefsGuardListStore(context).resetToDefaults() }
    }

    // ---- owner-real interaction helpers ----

    private fun boundsOf(t: String): Rect = tag(t).fetchSemanticsNode().boundsInWindow

    private fun displayed(t: String): Boolean = runCatching { tag(t).assertIsDisplayed() }.isSuccess

    /** Swipes the Rules column (through its own area, never the save bar or navigation) until [t] is on screen. */
    private fun revealTag(t: String) {
        var tries = 0
        while (!displayed(t) && tries < 15) {
            compose.onRoot().performTouchInput {
                swipe(Offset(centerX, top + height * 0.70f), Offset(centerX, top + height * 0.25f))
            }
            Thread.sleep(400)
            tries++
        }
        if (!displayed(t)) fail("'$t' never came on screen")
    }

    /** A real tap at the centre of [t]'s bounds: no Compose action, no idling. */
    private fun tapTag(t: String) {
        val c = boundsOf(t).center
        device.click(c.x.roundToInt(), c.y.roundToInt())
        Thread.sleep(400)
    }

    /** Taps the search field and types through the focused input connection. */
    private fun typeIntoSearch(text: String) {
        tapTag("app-picker-search")
        instrumentation.sendStringSync(text)
        Thread.sleep(600)
    }

    private fun waitUntilTag(t: String, timeoutMs: Long = 10_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (hasTag(t)) return
            Thread.sleep(200)
        }
        fail("tag '$t' never appeared; screen: ${describeScreen()}")
    }

    private fun waitUntilNoTag(t: String, timeoutMs: Long = 10_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!hasTag(t)) return
            Thread.sleep(200)
        }
        fail("tag '$t' stayed; screen: ${describeScreen()}")
    }

    private fun waitUntilText(text: String, timeoutMs: Long = 10_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (hasText(text)) return
            Thread.sleep(200)
        }
        fail("text '$text' never appeared; screen: ${describeScreen()}")
    }

    // ---- the A14 flows ----

    @Test
    fun addBySearchShowsNameAndIdAndClosesThePicker() {
        land()
        shot("list-before")
        revealTag("phone-use-allow-add")
        tapTag("phone-use-allow-add")
        waitUntilTag("app-picker")
        waitForTag("app-picker-search") // full-screen: the field is on screen the moment it opens
        shot("picker-open")
        typeIntoSearch("notes")
        waitUntilTag("app-picker-item-com.example.notes")
        tapTag("app-picker-item-com.example.notes")
        waitUntilNoTag("app-picker")
        waitUntilText("Invented Notes")
        waitUntilText("com.example.notes")
        shot("added")
        assertTrue("the pick must be saved", "com.example.notes" in store.load().allow)
    }

    @Test
    fun theSameAppMovesFromAllowToDeny() {
        land(allow = setOf("com.whatsapp", "com.example.notes"))
        revealTag("phone-use-deny-add")
        tapTag("phone-use-deny-add")
        waitUntilTag("app-picker")
        waitForTag("app-picker-search") // full-screen: the field is on screen the moment it opens
        typeIntoSearch("notes")
        waitUntilTag("app-picker-item-com.example.notes")
        waitUntilText("on Allowed")
        tapTag("app-picker-item-com.example.notes")
        waitUntilNoTag("app-picker")
        waitUntilText("Invented Notes")
        val saved = store.load()
        assertTrue("com.example.notes" in saved.deny)
        assertFalse("the move rule: not on both lists", "com.example.notes" in saved.allow)
        shot("moved")
    }

    @Test
    fun anAlwaysDeniedAppIsMarkedAndNotAddableToAllow() {
        land()
        revealTag("phone-use-allow-add")
        tapTag("phone-use-allow-add")
        waitUntilTag("app-picker")
        waitForTag("app-picker-search") // full-screen: the field is on screen the moment it opens
        typeIntoSearch("bank")
        waitUntilTag("app-picker-item-com.mybank.mobile")
        waitUntilText("always denied")
        shot("always-denied")
        val before = store.load().allow
        tapTag("app-picker-item-com.mybank.mobile") // a real tap on a row with no click action does nothing
        Thread.sleep(600)
        waitUntilTag("app-picker")
        waitForTag("app-picker-search") // full-screen: the field is on screen the moment it opens
        assertTrue("never added to allow", store.load().allow == before)
    }

    @Test
    fun anAlwaysDeniedAppAddsToDenyWithoutTheMark() {
        land()
        revealTag("phone-use-deny-add")
        tapTag("phone-use-deny-add")
        waitUntilTag("app-picker")
        waitForTag("app-picker-search") // full-screen: the field is on screen the moment it opens
        typeIntoSearch("bank")
        waitUntilTag("app-picker-item-com.mybank.mobile")
        assertFalse("no mark in the deny picker", hasText("always denied"))
        tapTag("app-picker-item-com.mybank.mobile")
        waitUntilNoTag("app-picker")
        assertTrue("com.mybank.mobile" in store.load().deny)
    }

    @Test
    fun theAllowPickerMarksAnAppOnTheDenyListAndTappingMovesIt() {
        land(allow = setOf("com.whatsapp"), deny = setOf("org.telegram.messenger"))
        revealTag("phone-use-allow-add")
        tapTag("phone-use-allow-add")
        waitForTag("app-picker")
        typeIntoSearch("tele")
        waitUntilTag("app-picker-item-org.telegram.messenger")
        waitUntilText("on Never allowed")
        shot("other-list-mark")
        tapTag("app-picker-item-org.telegram.messenger")
        waitUntilNoTag("app-picker")
        val saved = store.load()
        assertTrue("org.telegram.messenger" in saved.allow)
        assertFalse("org.telegram.messenger" in saved.deny)
    }

    @Test
    fun aNotInstalledIdShowsNotInstalled() {
        land() // com.example.gone is on the deny list and installed nowhere
        revealTag("phone-use-deny-list")
        waitUntilText("com.example.gone")
        waitUntilText("not installed")
        shot("not-installed")
        // And the picker cannot bring it back: it is not installed.
        revealTag("phone-use-deny-add")
        tapTag("phone-use-deny-add")
        waitUntilTag("app-picker")
        waitForTag("app-picker-search") // full-screen: the field is on screen the moment it opens
        typeIntoSearch("gone")
        Thread.sleep(400)
        assertFalse("a not-installed app is not in the picker", hasTag("app-picker-item-com.example.gone"))
        assertTrue(hasText("No app matches."))
    }

    // ---- P14 addendum 1: with Show system apps on, a pick must refresh the lists ----

    /** The owner's exact repro (2026-10-10): switch on, tap an ordinary app — the row must appear without a restart. */
    @Test
    fun addWithTheSystemSwitchOnShowsTheAppInTheAllowListWithoutARestart() {
        land(apps = fakeAppsWithSystem)
        revealTag("phone-use-allow-add")
        tapTag("phone-use-allow-add")
        waitUntilTag("app-picker")
        waitForTag("app-picker-search")
        tapTag("app-picker-system") // Show system apps on
        waitUntilTag("app-picker-item-com.example.systemtool")
        shot("picker-system-on")
        tapTag("app-picker-item-com.example.notes") // an ordinary, launchable app
        waitUntilNoTag("app-picker")
        waitUntilText("Invented Notes") // the row must be there WITHOUT a restart — the owner's complaint
        waitUntilText("com.example.notes")
        shot("added-system-on")
        assertTrue("the pick must be saved", "com.example.notes" in store.load().allow)
    }

    /** The deny list with a launcher-less app from behind the switch. */
    @Test
    fun addWithTheSystemSwitchOnShowsTheAppInTheDenyListWithoutARestart() {
        land(allow = setOf("com.whatsapp"), deny = setOf("com.example.gone"), apps = fakeAppsWithSystem)
        revealTag("phone-use-deny-add")
        tapTag("phone-use-deny-add")
        waitUntilTag("app-picker")
        waitForTag("app-picker-search")
        tapTag("app-picker-system")
        waitUntilTag("app-picker-item-com.example.systemtool")
        tapTag("app-picker-item-com.example.systemtool")
        waitUntilNoTag("app-picker")
        waitUntilText("Invented System Tool")
        waitUntilText("com.example.systemtool")
        shot("added-system-deny")
        assertTrue("com.example.systemtool" in store.load().deny)
    }

    /** A second pick, after the switch was used once and the picker reopened with it off again. */
    @Test
    fun aSecondPickAfterTheSystemSwitchStillRefreshesTheAllowList() {
        land(apps = fakeAppsWithSystem)
        revealTag("phone-use-allow-add")
        tapTag("phone-use-allow-add")
        waitUntilTag("app-picker")
        tapTag("app-picker-system")
        waitUntilTag("app-picker-item-com.example.systemtool")
        tapTag("app-picker-item-com.example.systemtool")
        waitUntilNoTag("app-picker")
        waitUntilText("Invented System Tool")
        // Reopen: a fresh overlay has the switch off again; pick a launchable app.
        revealTag("phone-use-allow-add")
        tapTag("phone-use-allow-add")
        waitUntilTag("app-picker")
        waitForTag("app-picker-search")
        typeIntoSearch("notes")
        waitUntilTag("app-picker-item-com.example.notes")
        tapTag("app-picker-item-com.example.notes")
        waitUntilNoTag("app-picker")
        waitUntilText("Invented Notes")
        shot("second-pick-refresh")
        val saved = store.load()
        assertTrue("com.example.notes" in saved.allow)
        assertTrue("com.example.systemtool" in saved.allow)
    }

    /**
     * P14 addendum 1, second repro (the first, with the fake adapter, did not reproduce): the
     * owner's phone has the REAL PackageManager behind the picker — hundreds of packages once the
     * switch is on, real labels and icons. This drives the real adapter end to end: switch on,
     * search a real launcher-less package, tap, and the row must appear without a restart.
     * (Google Play services is on the Test Lab image, has no launcher entry, and is not on the
     * seeded lists; the real adapter is forced first so test order cannot leak a fake in.)
     */
    @Test
    fun addARealPackageThroughTheRealAdapterWithTheSystemSwitchOn() {
        store.resetToDefaults()
        AppGraph.get(context).installedApps = AndroidInstalledApps(context)
        launchApp()
        pairAndLand()
        openTab("Rules")
        closeKeyboard()
        waitForText("Photo window")
        revealTag("phone-use-allow-add")
        tapTag("phone-use-allow-add")
        waitUntilTag("app-picker")
        waitForTag("app-picker-search")
        tapTag("app-picker-system")
        Thread.sleep(1_500) // the real adapter loads its whole package list on IO
        typeIntoSearch("play services")
        waitUntilTag("app-picker-item-com.google.android.gms")
        shot("picker-real-adapter")
        tapTag("app-picker-item-com.google.android.gms")
        waitUntilNoTag("app-picker")
        waitUntilText("Google Play services")
        waitUntilText("com.google.android.gms")
        shot("added-real-adapter")
        assertTrue("com.google.android.gms" in store.load().allow)
    }

    /**
     * P14 addendum 1, third repro: the owner scrolled the long real system list (no search) and
     * tapped. The default lists stay seeded, the picker is the real adapter's whole package list,
     * and the row is found by scrolling, then tapped. The row must appear without a restart.
     */
    @Test
    fun addARealPackageFoundByScrollingStillRefreshesTheAllowList() {
        store.resetToDefaults() // the realistic defaults: a long allow and deny list
        AppGraph.get(context).installedApps = AndroidInstalledApps(context)
        launchApp()
        pairAndLand()
        openTab("Rules")
        closeKeyboard()
        waitForText("Photo window")
        revealTag("phone-use-allow-add")
        tapTag("phone-use-allow-add")
        waitUntilTag("app-picker")
        tapTag("app-picker-system")
        // Scroll the real picker until the Play services row is on screen, as the owner did. Slow,
        // short drags (no fling) move less than the picker's height, so a row cannot be skipped;
        // if the list still ends past it, drag back up.
        var tries = 0
        while (!displayed("app-picker-item-com.google.android.gms") && tries < 120) {
            val down = tries < 80
            compose.onRoot().performTouchInput {
                val a = Offset(centerX, top + height * 0.70f)
                val b = Offset(centerX, top + height * 0.55f)
                if (down) swipe(a, b, durationMillis = 600) else swipe(b, a, durationMillis = 600)
            }
            Thread.sleep(300)
            tries++
        }
        waitUntilTag("app-picker-item-com.google.android.gms")
        shot("picker-scrolled")
        tapTag("app-picker-item-com.google.android.gms")
        waitUntilNoTag("app-picker")
        waitUntilText("Google Play services")
        shot("added-scrolled")
        assertTrue("com.google.android.gms" in store.load().allow)
    }

    /**
     * Correction 1 (P14): a pick is confirmed by name ("Added Invented Notes to Allowed") and the
     * new row is brought into view — the owner added it from the Add-an-app button at the bottom
     * of the card, so on the old id-ordered list the row landed above the viewport and the pick
     * looked like it had not worked. The seeded names (Aardvark, Beast) sort before the picked
     * name, so on the name-ordered list the new row is the last one, right above the button.
     */
    @Test
    fun aJustPickedAppIsConfirmedNamedAndBroughtIntoView() {
        land(
            allow = setOf("com.a.aaa", "com.z.zzz"),
            deny = setOf("com.example.gone"),
            apps = FakeInstalledApps(
                listOf(
                    AppEntry("com.a.aaa", "Aardvark"),
                    AppEntry("com.z.zzz", "Beast"),
                    AppEntry("com.example.notes", "Invented Notes"),
                ),
            ),
        )
        revealTag("phone-use-allow-add")
        tapTag("phone-use-allow-add")
        waitUntilTag("app-picker")
        waitForTag("app-picker-search")
        typeIntoSearch("notes")
        waitUntilTag("app-picker-item-com.example.notes")
        tapTag("app-picker-item-com.example.notes")
        waitUntilNoTag("app-picker")
        waitUntilText("Added Invented Notes to Allowed")
        eventually("the new row brought into view without scrolling") {
            displayed("phone-use-allow-row-com.example.notes")
        }
        waitUntilText("Invented Notes") // its name, not a not-installed flash
        shot("added-confirmed")
        assertTrue("com.example.notes" in store.load().allow)
    }
}
