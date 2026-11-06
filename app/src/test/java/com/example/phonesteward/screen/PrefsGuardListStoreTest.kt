package com.example.phonesteward.screen

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Correction 1 (finding 2): the legacy store helpers follow the same move rule as the picker —
 * adding to one list removes the app from the other, and a removal clears it from both.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PrefsGuardListStoreTest {

    private val store = PrefsGuardListStore(ApplicationProvider.getApplicationContext())

    @Before
    fun fresh() = store.resetToDefaults()

    @Test
    fun `addDeny moves the app out of the allow list`() {
        val after = store.load().let { store.addDeny("com.whatsapp"); store.load() }
        assertTrue("com.whatsapp" in after.deny)
        assertFalse("com.whatsapp" in after.allow)
    }

    @Test
    fun `addAllow moves the app out of the deny list`() {
        store.addDeny("com.android.chrome")
        store.addAllow("com.android.chrome")
        val after = store.load()
        assertTrue("com.android.chrome" in after.allow)
        assertFalse("com.android.chrome" in after.deny)
    }

    @Test
    fun `a removal clears the app from both lists`() {
        val lists = store.load()
        store.save(lists.copy(allow = lists.allow + "com.example.notes", deny = lists.deny + "com.example.notes"))
        store.removeAllow("com.example.notes")
        val after = store.load()
        assertFalse("com.example.notes" in after.allow)
        assertFalse("com.example.notes" in after.deny)
    }

    @Test
    fun `additions are normalised and the other list is otherwise untouched`() {
        val before = store.load()
        store.addDeny("  Com.Example.Notes ")
        val after = store.load()
        assertTrue("com.example.notes" in after.deny)
        assertEquals(before.allow, after.allow)
    }

    @Test
    fun `the move keeps the rest of both lists intact`() {
        val defaults = DefaultGuards.lists()
        store.addAllow("com.example.notes")
        store.addDeny("com.example.notes")
        val after = store.load()
        assertTrue("com.example.notes" in after.deny)
        assertFalse("com.example.notes" in after.allow)
        assertEquals(defaults.allow, after.allow)
        assertEquals(defaults.deny + "com.example.notes", after.deny)
    }
}
