package com.example.phonesteward.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A14.2/A14.3: the Add-an-app picker's pure logic — sort by name, search by name or id,
 * leave out apps already on the target list, and mark the always-denied floor in the allow
 * picker. A14.1: the row text for an installed and a not-installed entry.
 */
class AppPickerLogicTest {

    private val apps = listOf(
        AppEntry("com.example.notes", "Invented Notes"),
        AppEntry("org.telegram.messenger", "Telegram"),
        AppEntry("com.whatsapp", "WhatsApp"),
        AppEntry("com.android.settings", "Settings"),
        AppEntry("com.mybank.mobile", "Invented Bank"),
    )

    // ---- sorting (A14.2: sorted by name) ----

    @Test
    fun `rows are sorted by label case-insensitively then by id`() {
        val withTie = apps + AppEntry("com.ahead.notes", "invented notes") // same label, ignoring case, as Invented Notes
        val rows = AppPickerLogic.rows(withTie, query = "", exclude = emptySet(), otherList = emptySet(), forAllow = false)
        assertEquals(
            listOf(
                "com.mybank.mobile", // "invented bank"
                "com.ahead.notes", // "invented notes" ties with "Invented Notes"; the id decides
                "com.example.notes",
                "com.android.settings",
                "org.telegram.messenger",
                "com.whatsapp",
            ),
            rows.map { it.app.packageName },
        )
    }

    // ---- search (A14.2: filters by name or id) ----

    @Test
    fun `search matches the name or the id, case-insensitive and trimmed`() {
        assertTrue(AppPickerLogic.matches(AppEntry("org.telegram.messenger", "Telegram"), "  Tele "))
        assertTrue(AppPickerLogic.matches(AppEntry("org.telegram.messenger", "Telegram"), "TELEGRAM"))
        assertTrue(AppPickerLogic.matches(AppEntry("org.telegram.messenger", "Telegram"), "gram.mes"))
        assertFalse(AppPickerLogic.matches(AppEntry("org.telegram.messenger", "Telegram"), "whatsapp"))
        assertFalse(AppPickerLogic.matches(AppEntry("org.telegram.messenger", "Telegram"), "xyz"))
    }

    @Test
    fun `an empty query matches everything`() {
        assertTrue(AppPickerLogic.matches(AppEntry("com.example.notes", "Invented Notes"), ""))
        assertTrue(AppPickerLogic.matches(AppEntry("com.example.notes", "Invented Notes"), "   "))
    }

    // ---- exclusion (A14.2: apps already on that list are left out) ----

    @Test
    fun `apps already on the target list are left out`() {
        val rows = AppPickerLogic.rows(apps, query = "", exclude = setOf("com.whatsapp", "org.telegram.messenger"), otherList = emptySet(), forAllow = false)
        assertEquals(
            setOf("com.example.notes", "com.android.settings", "com.mybank.mobile"),
            rows.map { it.app.packageName }.toSet(),
        )
    }

    // ---- the on-the-other-list marks (Correction 1, finding 1) ----

    @Test
    fun `in the allow picker an app on the deny list is marked and still addable`() {
        val rows = AppPickerLogic.rows(apps, query = "", exclude = emptySet(), otherList = setOf("com.whatsapp"), forAllow = true)
        val whatsapp = rows.first { it.app.packageName == "com.whatsapp" }
        assertTrue(whatsapp.onOtherList)
        assertTrue("the mark never blocks the move", whatsapp.addable)
        val other = rows.first { it.app.packageName == "com.android.settings" }
        assertFalse(other.onOtherList)
    }

    @Test
    fun `in the deny picker an app on the allow list is marked and still addable`() {
        val rows = AppPickerLogic.rows(apps, query = "", exclude = emptySet(), otherList = setOf("org.telegram.messenger"), forAllow = false)
        val telegram = rows.first { it.app.packageName == "org.telegram.messenger" }
        assertTrue(telegram.onOtherList)
        assertTrue(telegram.addable)
        assertFalse(telegram.alwaysDenied)
    }

    @Test
    fun `an always-denied app on the other list stays always denied and not addable in the allow picker`() {
        val rows = AppPickerLogic.rows(apps, query = "", exclude = emptySet(), otherList = setOf("com.mybank.mobile"), forAllow = true)
        val bank = rows.first { it.app.packageName == "com.mybank.mobile" }
        assertTrue(bank.alwaysDenied)
        assertTrue(bank.onOtherList)
        assertFalse(bank.addable)
    }

    // ---- the always-denied floor (A14.3) ----

    @Test
    fun `in the allow picker an always-denied app is marked and not addable`() {
        val rows = AppPickerLogic.rows(apps, query = "", exclude = emptySet(), otherList = emptySet(), forAllow = true)
        val bank = rows.first { it.app.packageName == "com.mybank.mobile" }
        assertTrue(bank.alwaysDenied)
        assertFalse(bank.addable)
        val whatsapp = rows.first { it.app.packageName == "com.whatsapp" }
        assertFalse(whatsapp.alwaysDenied)
        assertTrue(whatsapp.addable)
    }

    @Test
    fun `nothing is always denied in the deny picker`() {
        val rows = AppPickerLogic.rows(apps, query = "", exclude = emptySet(), otherList = emptySet(), forAllow = false)
        assertTrue(rows.none { it.alwaysDenied })
        assertTrue(rows.all { it.addable })
    }

    @Test
    fun `an always-denied app is found by search and still marked`() {
        val rows = AppPickerLogic.rows(apps, query = "bank", exclude = emptySet(), otherList = emptySet(), forAllow = true)
        assertEquals(listOf("com.mybank.mobile"), rows.map { it.app.packageName })
        assertTrue(rows.single().alwaysDenied)
    }

    // ---- A14.1: the row text of a stored entry ----

    @Test
    fun `an installed entry shows the name with the id under it`() {
        assertEquals(GuardRowText("WhatsApp", "com.whatsapp"), GuardRowText.of("com.whatsapp", "WhatsApp"))
    }

    @Test
    fun `a not-installed entry shows the id and not installed`() {
        assertEquals(GuardRowText("com.example.gone", GuardRowText.NOT_INSTALLED), GuardRowText.of("com.example.gone", null))
    }
}
