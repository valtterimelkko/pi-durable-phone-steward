package com.example.phonesteward.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/** N10: at most one screen refresh per 500 ms; a request inside the gap becomes one trailing refresh. */
class RefreshCoalescerTest {

    private var now = 10_000L
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private var runs = 0

    private val coalescer = RefreshCoalescer(
        minGapMs = 500,
        nowMs = { now },
        scheduleLater = { delayMs, action -> scheduled += delayMs to action },
    )

    private fun request() = coalescer.request { runs += 1 }

    @Test
    fun `the first request runs at once`() {
        request()
        assertEquals(1, runs)
        assertEquals(0, scheduled.size)
    }

    @Test
    fun `a burst of eighteen requests runs once now and once after the gap`() {
        repeat(18) {
            request()
            now += 25 // 18 requests inside 450 ms: the pairing burst
        }
        assertEquals(1, runs)
        assertEquals("one trailing refresh is scheduled, not eighteen", 1, scheduled.size)
        val (delay, action) = scheduled.single()
        assertEquals("the trailing run waits out the rest of the gap", 475L, delay)
        now += delay
        action()
        assertEquals(2, runs)
    }

    @Test
    fun `a request after the gap has passed runs at once again`() {
        request()
        now += 500
        request()
        assertEquals(2, runs)
        assertEquals(0, scheduled.size)
    }

    @Test
    fun `after the trailing run a new burst starts a new window`() {
        request()
        now += 100
        request()
        val (delay, action) = scheduled.single()
        now += delay
        action()
        assertEquals(2, runs)
        now += 50
        request() // inside the gap of the trailing run
        assertEquals(2, runs)
        assertEquals(2, scheduled.size)
    }
}
