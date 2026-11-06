package com.example.phonesteward.screen

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.ui.RulesScreen
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/**
 * A14 on the Rules screen: the allow and deny lists show apps by name, apps are added from a
 * full-screen picker of installed apps (never a free-text id), an always-denied app cannot be
 * added to allow, an app on the other list is marked, and the lists are saved on each change
 * and resettable. Correction 1: the picker must be on screen the moment it opens. The fake
 * phone's apps are invented names.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h2600dp-xxhdpi")
class PhoneUseRulesTest {

    private class FakeInstalledApps(
        private val apps: List<AppEntry>,
        private val colouredIcons: Boolean = false,
        /** A15.11: the packages without a launcher entry, behind the Show system apps switch. */
        private val systemApps: List<AppEntry> = emptyList(),
    ) : InstalledApps {
        override fun launchable() = apps
        override fun installed() = apps + systemApps
        override fun label(packageName: String) = apps.firstOrNull { it.packageName == packageName }?.label
        override fun icon(packageName: String) =
            if (colouredIcons && apps.any { it.packageName == packageName }) {
                ColorDrawable((packageName.hashCode() and 0xFFFFFF) or 0xFF000000.toInt())
            } else null
    }

    private val fakeApps = FakeInstalledApps(
        listOf(
            AppEntry("com.android.settings", "Settings"),
            AppEntry("com.whatsapp", "WhatsApp"),
            AppEntry("org.telegram.messenger", "Telegram"),
            AppEntry("com.example.notes", "Invented Notes"),
            AppEntry("com.mybank.mobile", "Invented Bank"),
        ),
    )

    /** Correction 1 (P14): ids and names that sort differently, to pin name ordering. */
    private val nameOrderApps = FakeInstalledApps(
        listOf(
            AppEntry("com.a.aaa", "Beast"),
            AppEntry("com.z.zzz", "Aardvark"),
        ),
    )

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store get() = PrefsGuardListStore(ApplicationProvider.getApplicationContext())

    @Before
    fun fresh() {
        store.resetToDefaults()
        store.save(GuardLists(allow = setOf("com.whatsapp", "com.example.gone"), deny = setOf("com.fsck.k9")))
        AppGraph.resetForTest()
        AppGraph.get(ApplicationProvider.getApplicationContext()).installedApps = fakeApps
    }

    /** Renders the real Rules screen; the picker overlay lives there (Correction 1, finding 3). */
    private fun show() = compose.setContent {
        PhoneStewardTheme { RulesScreen(ScreenFixtures.pairedBase, onSave = {}) }
    }

    /** Waits until the named text is on screen (labels load off the main thread). */
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    /**
     * Opens the picker and asserts Correction 1, finding 3: it is usable the moment it opens —
     * the search field and the list are on screen, and the list covers most of the window
     * (the old below-the-fold panel covered a small fraction and failed this).
     */
    private fun openPicker(addTag: String) {
        compose.onNodeWithTag(addTag).assertHasClickAction().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app-picker-search").assertIsDisplayed()
        val rootHeight = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        val pickerHeight = compose.onNodeWithTag("app-picker").fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "the picker list should cover most of the screen (${pickerHeight / rootHeight})",
            pickerHeight >= 0.5f * rootHeight,
        )
    }

    private fun search(query: String) {
        compose.onNodeWithTag("app-picker-search").performTextInput(query)
        compose.waitForIdle()
    }

    private fun pickerItem(pkg: String) = compose.onNodeWithTag("app-picker-item-$pkg")

    private fun pickerOpen(): Boolean = compose.onAllNodesWithTag("app-picker").fetchSemanticsNodes().isNotEmpty()

    // ---- A15.11: allow an app with no launcher entry, for good ----

    @Test
    fun `the Show system apps switch lists packages without a launcher entry and adds one`() {
        AppGraph.get(ApplicationProvider.getApplicationContext()).installedApps =
            FakeInstalledApps(
                listOf(AppEntry("com.android.settings", "Settings")),
                systemApps = listOf(AppEntry("com.google.android.gms", "Google Play services")),
            )
        show()
        openPicker("phone-use-allow-add")
        compose.onNodeWithTag("app-picker-system").assertIsDisplayed()
        assertTrue(
            "the system app is hidden until the switch is on",
            compose.onAllNodesWithTag("app-picker-item-com.google.android.gms").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("app-picker-system").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("app-picker-item-com.google.android.gms").fetchSemanticsNodes().isNotEmpty()
        }
        awaitText("Google Play services")
        pickerItem("com.google.android.gms").performClick()
        compose.waitForIdle()
        assertTrue(store.load().allow.contains("com.google.android.gms"))
    }

    // ---- A14.1: rows by name ----

    @Test
    fun `an installed entry shows the name with the id under it`() {
        show()
        awaitText("WhatsApp")
        awaitText("com.whatsapp")
    }

    @Test
    fun `a not-installed entry shows the id and not installed`() {
        show()
        awaitText("com.example.gone")
        awaitText("com.fsck.k9")
        // Both not-installed rows (one per list) carry the mark.
        compose.waitUntil(5_000) { compose.onAllNodesWithText(GuardRowText.NOT_INSTALLED).fetchSemanticsNodes().size >= 2 }
        assertTrue(compose.onAllNodesWithText(GuardRowText.NOT_INSTALLED).fetchSemanticsNodes().size >= 2)
    }

    // ---- Correction 1, finding 3: the picker is on screen when it opens ----

    @Test
    fun `the picker opens full-screen with the search field and rows on screen`() {
        show()
        awaitText("WhatsApp")
        compose.onNodeWithTag("phone-use-allow-add").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app-picker-search").assertIsDisplayed()
        awaitText("Invented Bank") // the first row of the sorted list, without any scrolling
    }

    // ---- A14.2: the picker instead of the free-text field ----

    @Test
    fun `the free-text add fields are gone and the add buttons open the picker`() {
        show()
        awaitText("WhatsApp")
        compose.onNodeWithText("Add an app to allow").assertDoesNotExist()
        compose.onNodeWithText("Add an app to deny").assertDoesNotExist()
        openPicker("phone-use-allow-add")
        compose.onNodeWithText("Add an app to allow").assertIsDisplayed()
        compose.onNodeWithTag("app-picker-search").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertFalse("the picker closes on Close", pickerOpen())
    }

    @Test
    fun `adding an app from the picker saves it, shows name and id, and closes the picker`() {
        show()
        awaitText("WhatsApp")
        openPicker("phone-use-allow-add")
        search("notes")
        pickerItem("com.example.notes").performClick()
        compose.waitForIdle()
        assertFalse("one tap adds and closes", pickerOpen())
        assertTrue("com.example.notes" in store.load().allow)
        awaitText("Invented Notes")
    }

    @Test
    fun `the picker is sorted by name and leaves out apps already on the list`() {
        show()
        awaitText("WhatsApp")
        openPicker("phone-use-allow-add")
        // WhatsApp is already on the allow list: not in the picker.
        assertTrue(compose.onAllNodesWithTag("app-picker-item-com.whatsapp").fetchSemanticsNodes().isEmpty())
        awaitText("Invented Bank")
        awaitText("Invented Notes")
        awaitText("Settings")
        awaitText("Telegram")
    }

    @Test
    fun `search filters the picker by name or id`() {
        show()
        awaitText("WhatsApp")
        openPicker("phone-use-allow-add")
        search("TELE")
        compose.waitUntil(5_000) { pickerOpen() && compose.onAllNodesWithTag("app-picker-item-org.telegram.messenger").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("app-picker-item-com.example.notes").fetchSemanticsNodes().isEmpty())
        assertEquals(setOf("com.whatsapp", "com.example.gone"), store.load().allow)
    }

    // ---- Correction 1, finding 1: the on-the-other-list marks ----

    @Test
    fun `the allow picker marks an app on the deny list and tapping moves it`() {
        store.save(GuardLists(allow = setOf("com.whatsapp"), deny = setOf("org.telegram.messenger")))
        show()
        awaitText("WhatsApp")
        openPicker("phone-use-allow-add")
        awaitText("on Never allowed")
        pickerItem("org.telegram.messenger").performClick()
        compose.waitForIdle()
        assertFalse(pickerOpen())
        val saved = store.load()
        assertTrue("org.telegram.messenger" in saved.allow)
        assertFalse("org.telegram.messenger" in saved.deny)
    }

    @Test
    fun `the deny picker marks an app on the allow list and tapping moves it`() {
        show()
        awaitText("WhatsApp")
        openPicker("phone-use-deny-add")
        awaitText("on Allowed")
        pickerItem("com.whatsapp").performClick()
        compose.waitForIdle()
        assertFalse(pickerOpen())
        val saved = store.load()
        assertTrue("com.whatsapp" in saved.deny)
        assertFalse("com.whatsapp" in saved.allow)
        awaitText("WhatsApp") // the row now sits under Never allowed, still by name
    }

    // ---- A14.3: always denied ----

    @Test
    fun `an always-denied app is shown in the allow picker, marked, and cannot be added`() {
        show()
        awaitText("WhatsApp")
        openPicker("phone-use-allow-add")
        search("bank")
        awaitText("Invented Bank")
        awaitText("always denied")
        val node = pickerItem("com.mybank.mobile").fetchSemanticsNode()
        assertNull("the always-denied row must not be tappable", node.config.getOrNull(SemanticsActions.OnClick))
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertFalse("com.mybank.mobile" in store.load().allow)
    }

    @Test
    fun `an always-denied app adds to the deny list without a mark`() {
        show()
        awaitText("WhatsApp")
        openPicker("phone-use-deny-add")
        search("bank")
        pickerItem("com.mybank.mobile").performClick()
        compose.waitForIdle()
        assertTrue("com.mybank.mobile" in store.load().deny)
        assertFalse(compose.onAllNodesWithText("always denied").fetchSemanticsNodes().isNotEmpty())
    }

    // ---- Correction 1, finding 4: icons without an intrinsic size must not crash the row ----

    @Test
    fun `a row with an icon that has no intrinsic size renders without crashing`() {
        AppGraph.get(ApplicationProvider.getApplicationContext()).installedApps =
            FakeInstalledApps(fakeApps.let { it.launchable() }, colouredIcons = true)
        show()
        awaitText("WhatsApp")
        openPicker("phone-use-allow-add")
        awaitText("Invented Notes") // the row rendered, its flat-colour icon decoded
    }

    // ---- unchanged: reset, remove ----

    @Test
    fun `reset restores the defaults`() {
        show()
        awaitText("WhatsApp")
        compose.onNodeWithTag("phone-use-reset-lists").performClick()
        compose.waitForIdle()
        assertEquals(DefaultGuards.lists(), store.load())
    }

    @Test
    fun `removing an entry still works`() {
        // One removable row only, so the Remove button is unambiguous.
        store.resetToDefaults()
        store.save(GuardLists(allow = setOf("com.whatsapp"), deny = emptySet()))
        show()
        awaitText("WhatsApp")
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()
        assertFalse("com.whatsapp" in store.load().allow)
    }

    // ---- Correction 1 (P14): rows by name, the Added confirmation ----

    @Test
    fun `the lists order by app name even when the package ids sort differently`() {
        store.save(GuardLists(allow = setOf("com.a.aaa", "com.z.zzz"), deny = setOf("com.example.gone")))
        AppGraph.get(ApplicationProvider.getApplicationContext()).installedApps = nameOrderApps
        show()
        awaitText("Aardvark")
        awaitText("Beast")
        val aardvark = compose.onAllNodesWithText("Aardvark").fetchSemanticsNodes()[0]
        val beast = compose.onAllNodesWithText("Beast").fetchSemanticsNodes()[0]
        assertTrue(
            "Aardvark (com.z.zzz) must sit above Beast (com.a.aaa): name order, not id order",
            aardvark.boundsInRoot.top < beast.boundsInRoot.top,
        )
    }

    @Test
    fun `a pick is confirmed by name`() {
        show()
        awaitText("WhatsApp")
        openPicker("phone-use-allow-add")
        search("notes")
        pickerItem("com.example.notes").performClick()
        compose.waitForIdle()
        assertTrue(store.load().allow.contains("com.example.notes"))
        awaitText("Added Invented Notes to Allowed")
    }

    /** A preview for a quick look (not a gate; Test Lab is). */
    @Test
    fun preview() {
        show()
        awaitText("WhatsApp")
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        if (view.width > 0 && view.height > 0) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val dir = File("build/previews").apply { mkdirs() }
            File(dir, "phone-use-rules.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
