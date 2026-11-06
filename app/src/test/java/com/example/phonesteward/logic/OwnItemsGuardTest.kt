package com.example.phonesteward.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The guard is the structural safety rule for the test buttons: trash and
 * similar actions may only take URIs this app created itself.
 */
class OwnItemsGuardTest {

    private val ownUri = "content://media/external/images/media/101"
    private val foreignUri = "content://media/external/images/media/999"

    @Test
    fun `refuses a uri the app did not create`() {
        val guard = OwnItemsGuard()
        try {
            guard.requireOwned(foreignUri)
            fail("expected the guard to refuse a foreign uri")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not created by this app"))
        }
    }

    @Test
    fun `allows a uri the app recorded`() {
        val guard = OwnItemsGuard()
        guard.record(ownUri)
        guard.requireOwned(ownUri) // no exception
    }

    @Test
    fun `refuses after the item is forgotten`() {
        val guard = OwnItemsGuard()
        guard.record(ownUri)
        guard.forget(ownUri)
        try {
            guard.requireOwned(ownUri)
            fail("expected the guard to refuse a forgotten uri")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not created by this app"))
        }
    }

    @Test
    fun `records are independent per guard instance`() {
        val guard = OwnItemsGuard()
        guard.record(ownUri)
        val fresh = OwnItemsGuard()
        try {
            fresh.requireOwned(ownUri)
            fail("expected a fresh guard to know nothing")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank uris are never recorded`() {
        val guard = OwnItemsGuard()
        guard.record("  ")
    }

    @Test
    fun `owned list keeps insertion order`() {
        val guard = OwnItemsGuard()
        val a = "content://media/external/images/media/1"
        val b = "content://media/external/images/media/2"
        guard.record(a)
        guard.record(b)
        assertEquals(listOf(a, b), guard.ownedUris)
    }
}
