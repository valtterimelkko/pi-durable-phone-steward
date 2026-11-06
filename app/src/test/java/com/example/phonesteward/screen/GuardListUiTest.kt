package com.example.phonesteward.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Correction 1 (P14): the Rules app lists read and order themselves for the owner — rows by the
 * app name (case-insensitive, falling back to the package id), row text that tells "still
 * resolving" (a just-picked app) apart from "not installed" (a lookup that answered null), and the
 * pick that a reload just added (for the confirmation and bring-into-view).
 */
class GuardListUiTest {

    @Test
    fun `rows order by the app name case-insensitively, falling back to the id`() {
        val labels = mapOf<String, String?>("com.b.bbb" to "zebra", "com.a.aaa" to "Apple")
        assertEquals(
            listOf("com.a.aaa", "com.b.bbb"),
            GuardListUi.sortedEntries(setOf("com.b.bbb", "com.a.aaa"), labels),
        )
    }

    @Test
    fun `a name may sort elsewhere than its package id`() {
        // By id com.z.zzz sorts last; its name "Aardvark" sorts first.
        val labels = mapOf<String, String?>("com.z.zzz" to "Aardvark", "com.a.aaa" to "Zebra")
        assertEquals(
            listOf("com.z.zzz", "com.a.aaa"),
            GuardListUi.sortedEntries(setOf("com.a.aaa", "com.z.zzz"), labels),
        )
    }

    @Test
    fun `unresolved and not-installed entries fall back to the package id`() {
        // com.y.yyy is missing from the map (still resolving); com.m.mmm resolved to null.
        val labels = mapOf<String, String?>("com.m.mmm" to null, "com.z.zzz" to "Zebra")
        assertEquals(
            listOf("com.m.mmm", "com.y.yyy", "com.z.zzz"),
            GuardListUi.sortedEntries(setOf("com.z.zzz", "com.y.yyy", "com.m.mmm"), labels),
        )
    }

    @Test
    fun `the row text tells pending, named and not installed apart`() {
        // Resolved with a label: the name with the id under it.
        assertEquals(
            GuardRowText("WhatsApp", "com.whatsapp"),
            GuardRowText.of("com.whatsapp", "WhatsApp", resolved = true),
        )
        // Resolved without a label: the id and "not installed".
        assertEquals(
            GuardRowText("com.example.gone", GuardRowText.NOT_INSTALLED),
            GuardRowText.of("com.example.gone", null, resolved = true),
        )
        // Still resolving (a just-picked app): the id alone, never a "not installed" flash.
        assertEquals(
            GuardRowText("com.example.new", null),
            GuardRowText.of("com.example.new", null, resolved = false),
        )
        assertEquals(
            GuardRowText("com.example.new", null),
            GuardRowText.of("com.example.new", "Invented", resolved = false),
        )
    }

    @Test
    fun `the added-entry diff names the pick and its list`() {
        val old = GuardLists(allow = setOf("com.a.a"), deny = setOf("com.b.b"))
        assertEquals(
            "com.n.n" to GuardPickerTarget.ALLOW,
            GuardListUi.addedEntry(old, old.copy(allow = old.allow + "com.n.n")),
        )
        assertEquals(
            "com.c.c" to GuardPickerTarget.DENY,
            GuardListUi.addedEntry(old, GuardLists(allow = old.allow, deny = old.deny + "com.c.c")),
        )
        // The move rule (A14.2) reads as an add to the list it moved to.
        assertEquals(
            "com.b.b" to GuardPickerTarget.ALLOW,
            GuardListUi.addedEntry(old, GuardLists(allow = old.allow + "com.b.b", deny = setOf())),
        )
        assertNull(GuardListUi.addedEntry(old, old))
    }
}
