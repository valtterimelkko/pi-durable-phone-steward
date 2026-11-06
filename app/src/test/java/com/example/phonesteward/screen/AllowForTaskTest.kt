package com.example.phonesteward.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A15.11: which refusals may be put to the owner as "allow this app for this task". Only the
 * missing allow-list entry; never an always-denied app, the steward's own screen, a sensitive
 * screen, or an app already allowed.
 */
class AllowForTaskTest {

    @Test
    fun `a package that is merely unlisted is offered`() {
        assertTrue(AllowForTask.offerable("com.google.android.gms", GuardPolicy.Access.NOT_LISTED, sensitiveScreen = false))
    }

    @Test
    fun `an allowed, denied or own-screen package is never offered`() {
        assertFalse(AllowForTask.offerable("com.android.settings", GuardPolicy.Access.ALLOWED, sensitiveScreen = false))
        assertFalse(AllowForTask.offerable("com.google.android.gm", GuardPolicy.Access.DENIED, sensitiveScreen = false))
        assertFalse(AllowForTask.offerable("com.example.phonesteward", GuardPolicy.Access.OWN_OBSERVE_ONLY, sensitiveScreen = false))
    }

    @Test
    fun `an always-denied package is never offered even when it is not listed`() {
        // The floor covers banks, wallets, authenticators and password managers (A14.3).
        assertFalse(AllowForTask.offerable("com.mybank.mobile", GuardPolicy.Access.NOT_LISTED, sensitiveScreen = false))
        assertFalse(AllowForTask.offerable("com.example.bitwarden.vault", GuardPolicy.Access.NOT_LISTED, sensitiveScreen = false))
    }

    @Test
    fun `a sensitive screen is never offered`() {
        assertFalse(AllowForTask.offerable("com.example.unlisted", GuardPolicy.Access.NOT_LISTED, sensitiveScreen = true))
    }
}
