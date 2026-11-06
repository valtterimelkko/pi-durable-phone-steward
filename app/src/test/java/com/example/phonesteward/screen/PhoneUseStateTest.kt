package com.example.phonesteward.screen

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PhoneUseStateTest {

    @Before
    fun reset() = PhoneUseState.reset()

    @Test
    fun `the listener walks the state from starting to ended`() {
        PhoneUseState.starting("t")
        assertTrue(PhoneUseState.isActive)
        val l = PhoneUseState.listenerFor("t")
        l.onStarted("ls-1", "t")
        assertEquals(PhoneUseUi.Running("t", 0), PhoneUseState.state.value)
        l.onStep(2, "screen_act")
        assertEquals(PhoneUseUi.Running("t", 2), PhoneUseState.state.value)
        l.onWaitingForOwner("Delete")
        assertEquals(PhoneUseUi.Running("t", 2, "Delete"), PhoneUseState.state.value)
        l.onOwnerAnswered(false)
        assertEquals(PhoneUseUi.Running("t", 2), PhoneUseState.state.value)
        l.onEnded(SessionEnd("stopped", "ls-1"))
        assertEquals(PhoneUseUi.Ended("stopped", null), PhoneUseState.state.value)
        assertTrue(!PhoneUseState.isActive)
    }

    @Test
    fun `guard lists survive in preferences and reset to the defaults`() {
        val store = PrefsGuardListStore(ApplicationProvider.getApplicationContext())
        assertEquals(DefaultGuards.lists(), store.load())
        store.addAllow(" Com.Example.App ")
        store.removeAllow("com.whatsapp")
        store.addDeny("com.example.bank2")
        val after = PrefsGuardListStore(ApplicationProvider.getApplicationContext()).load()
        assertTrue("com.example.app" in after.allow)
        assertTrue("com.whatsapp" !in after.allow)
        assertTrue("com.example.bank2" in after.deny)
        store.resetToDefaults()
        assertEquals(DefaultGuards.lists(), store.load())
    }

    @Test
    fun `the confirm broker answers no on a timeout and yes only on a clear yes`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val broker = ConfirmBroker(ctx, timeoutMs = 150)
        val req = ConfirmRequest("tap", "Delete", "delete", "com.android.settings")
        assertEquals(false, broker.confirm(req))
        val t = Thread {
            val until = System.currentTimeMillis() + 2_000
            while (ConfirmBroker.asking.value == null && System.currentTimeMillis() < until) Thread.sleep(10)
            ConfirmBroker.answer(true, ConfirmBroker.asking.value!!)
        }
        t.start()
        assertEquals(true, ConfirmBroker(ctx, timeoutMs = 2_000).confirm(req))
        val t2 = Thread { Thread.sleep(60); ConfirmBroker.cancelPending() }
        t2.start()
        assertEquals(false, ConfirmBroker(ctx, timeoutMs = 2_000).confirm(req))
        assertEquals(null, ConfirmBroker.asking.value)
    }
}
