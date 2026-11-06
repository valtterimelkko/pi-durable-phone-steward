package com.example.phonesteward.logic.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correction 1 (review finding 2): the A11.4 Chat reload has two triggers — the Chat tab becoming visible and the app
 * returning to the foreground while Chat is showing — and a single foreground event must produce one reload, not two.
 * The gate is time-based (the two triggers can fire in the same instant at launch); the clock comes in as a lambda.
 */
class ChatForegroundGateTest {

    @Test
    fun `the first event reloads`() {
        val gate = ChatForegroundGate(nowMs = { 1_000 })
        assertTrue(gate.shouldReload())
    }

    @Test
    fun `a second trigger in the same moment does not`() {
        var now = 1_000L
        val gate = ChatForegroundGate(nowMs = { now }, minGapMs = 1_000)
        assertTrue(gate.shouldReload())
        now = 1_050
        assertFalse("the tab trigger and the resume trigger are one event", gate.shouldReload())
        now = 1_999
        assertFalse(gate.shouldReload())
    }

    @Test
    fun `a later event reloads again`() {
        var now = 1_000L
        val gate = ChatForegroundGate(nowMs = { now }, minGapMs = 1_000)
        assertTrue(gate.shouldReload())
        now = 2_000
        assertTrue(gate.shouldReload())
    }

    @Test
    fun `a refused trigger does not push the window forward for ever`() {
        var now = 1_000L
        val gate = ChatForegroundGate(nowMs = { now }, minGapMs = 1_000)
        assertTrue(gate.shouldReload())
        now = 1_500
        assertFalse(gate.shouldReload())
        now = 1_600
        assertFalse(gate.shouldReload())
        now = 2_000
        assertTrue("the window is measured from the last reload", gate.shouldReload())
    }
}
