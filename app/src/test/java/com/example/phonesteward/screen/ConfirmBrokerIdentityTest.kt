package com.example.phonesteward.screen

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * H2 (correction 1): an answer belongs to the question it was drawn for. A stale answer —
 * the card or notification of a question that has already timed out and been replaced —
 * must not settle the newer question; the notification's answer carries the id its intent
 * was posted with; a Stop still cancels whatever waits.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ConfirmBrokerIdentityTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun q(label: String) = ConfirmRequest("tap", label, label, "com.android.settings")

    private fun awaitAsking(label: String) {
        val until = System.currentTimeMillis() + 5_000
        while (ConfirmBroker.asking.value?.label != label && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(label, ConfirmBroker.asking.value?.label)
    }

    @Test
    fun `an answer for a replaced question does not answer the newer one`() {
        // The first question times out unanswered, like a 50 s silence (the runner sees a no).
        assertEquals(false, ConfirmBroker(ctx(), timeoutMs = 250).confirm(q("first")))
        // The runner asks the next question; it stays open.
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val verdict = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread {
            verdict.set(ConfirmBroker(ctx(), timeoutMs = 10_000).confirm(q("second")))
            answered.set(true)
        }
        t.start()
        awaitAsking("second")
        // A tap from the first question's card (drawn a frame ago) arrives now: a no-op,
        // even for an equal-looking request — the broker compares identity, not content.
        ConfirmBroker.answer(true, q("first"))
        assertFalse("the stale answer settled nothing", answered.get())
        assertEquals("second", ConfirmBroker.asking.value?.label)
        // The current question's own answer settles it.
        ConfirmBroker.answer(true, ConfirmBroker.asking.value!!)
        t.join(5_000)
        assertTrue(verdict.get())
        assertNull(ConfirmBroker.asking.value)
    }

    @Test
    fun `a notification answer carries the question id it was posted for`() {
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val verdict = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread {
            verdict.set(ConfirmBroker(ctx(), timeoutMs = 10_000).confirm(q("only")))
            answered.set(true)
        }
        t.start()
        awaitAsking("only")
        val staleId = ConfirmBroker.askingId!! + 1 // an id no pending question holds
        ConfirmBroker.answerById(true, staleId)
        assertFalse("an id that is not pending settles nothing", answered.get())
        ConfirmBroker.answerById(true, ConfirmBroker.askingId!!)
        t.join(5_000)
        assertTrue(verdict.get())
    }

    @Test
    fun `a stop still cancels whatever question waits`() {
        val verdict = java.util.concurrent.atomic.AtomicBoolean(true)
        val t = Thread { verdict.set(ConfirmBroker(ctx(), timeoutMs = 10_000).confirm(q("stopme"))) }
        t.start()
        awaitAsking("stopme")
        ConfirmBroker.cancelPending()
        t.join(5_000)
        assertFalse(verdict.get())
        assertNull(ConfirmBroker.asking.value)
        assertNull(ConfirmBroker.askingId)
    }
}
