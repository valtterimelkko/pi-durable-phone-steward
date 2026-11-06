package com.example.phonesteward.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBudgetTest {

    @Test
    fun `starts with 40 steps and 15 minutes (A13_4)`() {
        assertEquals(40, SessionBudget.MAX_STEPS)
        assertEquals(15 * 60_000L, SessionBudget.MAX_MILLIS)
    }

    @Test
    fun `the 41st step is refused`() {
        var now = 0L
        val b = SessionBudget(clock = { now })
        repeat(40) { assertTrue("step ${it + 1}", b.takeStep()) }
        assertFalse(b.takeStep())
        assertEquals(SessionBudget.Exhausted.STEPS, b.exhausted())
    }

    @Test
    fun `time runs out after fifteen minutes`() {
        var now = 1_000L
        val b = SessionBudget(clock = { now })
        assertEquals(null, b.exhausted())
        now += 15 * 60_000L - 1
        assertEquals(null, b.exhausted())
        now += 1
        assertEquals(SessionBudget.Exhausted.TIME, b.exhausted())
        assertFalse(b.takeStep())
    }
}
