package com.example.phonesteward.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 13.3 guards: allow list, deny list, built-in sensitive floor, the app's own screen is observe-only. */
class GuardPolicyTest {

    private val own = "com.example.phonesteward"
    private fun policy(lists: GuardLists = DefaultGuards.lists()) = GuardPolicy(lists, own)

    @Test
    fun `the defaults allow Settings Files WhatsApp Telegram Chrome and the Play Store`() {
        val p = policy()
        for (pkg in listOf("com.android.settings", "com.android.documentsui", "com.whatsapp", "org.telegram.messenger", "com.android.chrome", "com.android.vending")) {
            assertEquals(pkg, GuardPolicy.Access.ALLOWED, p.access(pkg))
        }
    }

    @Test
    fun `an app on neither list is not listed`() {
        assertEquals(GuardPolicy.Access.NOT_LISTED, policy().access("com.example.unknown"))
        assertEquals(GuardPolicy.Access.NOT_LISTED, policy().access("com.android.systemui"))
    }

    @Test
    fun `the default deny list covers mail photos authenticators password managers and banking`() {
        val p = policy()
        for (pkg in listOf(
            "com.google.android.gm", "com.google.android.apps.photos", "com.amazon.clouddrive.photos",
            "com.google.android.apps.authenticator2", "com.x8bit.bitwarden", "com.lastpass.lpandroid",
            "com.paypal.android.p2pmobile", "com.revolut.revolut", "com.coinbase.android",
        )) {
            assertEquals(pkg, GuardPolicy.Access.DENIED, p.access(pkg))
        }
    }

    @Test
    fun `a banking package is denied even if the owner put it on the allow list`() {
        val lists = GuardLists(allow = setOf("com.mybank.mobile", "com.android.settings"), deny = emptySet())
        assertEquals(GuardPolicy.Access.DENIED, policy(lists).access("com.mybank.mobile"))
        assertEquals(GuardPolicy.Access.ALLOWED, policy(lists).access("com.android.settings"))
    }

    @Test
    fun `an owner deny entry beats the allow list`() {
        val lists = GuardLists(allow = setOf("com.whatsapp"), deny = setOf("com.whatsapp"))
        assertEquals(GuardPolicy.Access.DENIED, policy(lists).access("com.whatsapp"))
    }

    @Test
    fun `the steward app itself can be observed but never operated`() {
        val p = policy()
        assertEquals(GuardPolicy.Access.OWN_OBSERVE_ONLY, p.access(own))
        assertTrue(p.mayObserve(own))
        assertFalse(p.mayAct(own))
    }

    @Test
    fun `observing needs allowed or own, acting needs allowed`() {
        val p = policy()
        assertTrue(p.mayObserve("com.android.settings"))
        assertTrue(p.mayAct("com.android.settings"))
        assertFalse(p.mayObserve("com.example.unknown"))
        assertFalse(p.mayAct("com.example.unknown"))
        assertFalse(p.mayObserve("com.google.android.gm"))
        assertFalse(p.mayAct("com.google.android.gm"))
    }

    @Test
    fun `lock screen and security settings pages are denied by activity name`() {
        val p = policy()
        for (activity in listOf(
            "com.android.settings.password.ChooseLockGeneric",
            "com.android.settings.Settings\$SecuritySettingsActivity",
            "com.android.settings.biometrics.fingerprint.FingerprintSettings",
            "com.android.settings.password.ConfirmLockPassword",
            "com.android.settings.Settings\$AccessibilitySettingsActivity",
            "com.android.settings.deviceadmin.DeviceAdminSettings",
        )) {
            assertTrue(activity, p.screenDenied("com.android.settings", activity))
        }
        assertFalse(p.screenDenied("com.android.settings", "com.android.settings.Settings\$StorageDashboardActivity"))
        assertFalse(p.screenDenied("com.android.settings", null))
    }

    @Test
    fun `package names are compared exactly, not by prefix, for list entries`() {
        val lists = GuardLists(allow = setOf("com.whatsapp"), deny = emptySet())
        assertEquals(GuardPolicy.Access.NOT_LISTED, policy(lists).access("com.whatsapp.evil"))
    }

    @Test
    fun `list edits normalise blanks and case`() {
        assertEquals(setOf("com.a.b"), GuardLists.normalise(listOf(" com.a.b ", "", "COM.A.B")))
    }

    @Test
    fun `adding to one list removes the app from the other (A14_2)`() {
        val lists = DefaultGuards.lists()
        assertTrue("com.whatsapp" in lists.allow)
        val denied = lists.addingToDeny("com.whatsapp")
        assertTrue("com.whatsapp" in denied.deny)
        assertFalse("com.whatsapp" in denied.allow)
        val allowedAgain = denied.addingToAllow("com.whatsapp")
        assertTrue("com.whatsapp" in allowedAgain.allow)
        assertFalse("com.whatsapp" in allowedAgain.deny)
    }

    @Test
    fun `a picker addition is normalised and the other list keeps its order`() {
        val lists = DefaultGuards.lists()
        val denied = lists.addingToDeny("  Com.Example.Notes ")
        assertTrue("com.example.notes" in denied.deny)
        assertEquals(lists.allow, denied.allow - "com.example.notes")
    }

    @Test
    fun `without removes an app from both lists`() {
        val lists = DefaultGuards.lists()
        val without = lists.copy(deny = lists.deny + "com.whatsapp").without("com.whatsapp")
        assertFalse("com.whatsapp" in without.allow)
        assertFalse("com.whatsapp" in without.deny)
    }

    @Test
    fun `the picker's always-denied floor is GuardPolicy's own floor (A14_3)`() {
        // From GuardPolicyTest's existing floor cases: banks, authenticators, password managers.
        assertTrue(GuardPolicy.alwaysDenied("com.mybank.mobile"))
        assertTrue(GuardPolicy.alwaysDenied("com.google.android.apps.authenticator2"))
        assertTrue(GuardPolicy.alwaysDenied("COM.KEEPASSDROID.app"))
        // On the default deny list but not on the floor: the owner may still move it to allow.
        assertFalse(GuardPolicy.alwaysDenied("com.google.android.gm"))
        assertFalse(GuardPolicy.alwaysDenied("com.whatsapp"))
    }

    // ---- A15.11: the owner may allow one app for this session only ----

    @Test
    fun `an app allowed for this session is allowed, and the stored lists are untouched`() {
        val policy = GuardPolicy(DefaultGuards.lists(), own)
        assertEquals(GuardPolicy.Access.NOT_LISTED, policy.access("com.google.android.gms"))
        policy.allowForThisSession("com.google.android.gms")
        assertEquals(GuardPolicy.Access.ALLOWED, policy.access("com.google.android.gms"))
        assertTrue(policy.mayAct("com.google.android.gms"))
        assertTrue(policy.mayObserve("com.google.android.gms"))
        // The owner's stored lists did not change.
        assertFalse(DefaultGuards.lists().allow.contains("com.google.android.gms"))
    }

    @Test
    fun `the session list never opens a denied app, the floor or the steward itself`() {
        val policy = GuardPolicy(DefaultGuards.lists(), own)
        policy.allowForThisSession("com.google.android.gm")      // on the deny list
        policy.allowForThisSession("com.mybank.mobile")          // on the built-in floor
        policy.allowForThisSession(own)                          // the steward's own app
        assertEquals(GuardPolicy.Access.DENIED, policy.access("com.google.android.gm"))
        assertEquals(GuardPolicy.Access.DENIED, policy.access("com.mybank.mobile"))
        assertEquals(GuardPolicy.Access.OWN_OBSERVE_ONLY, policy.access(own))
    }
}
