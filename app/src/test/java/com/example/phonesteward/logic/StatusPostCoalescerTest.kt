package com.example.phonesteward.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N29: the pairing path, the first resume and the worker each post the device status; an identical burst posts once. */
class StatusPostCoalescerTest {

    private var now = 1_000_000L
    private fun gate(windowMs: Long = 10_000) = StatusPostCoalescer(windowMs) { now }

    @Test
    fun `the first post is allowed`() {
        assertTrue(gate().allow("a"))
    }

    @Test
    fun `an identical post inside the window is held back`() {
        val g = gate()
        assertTrue(g.allow("a"))
        now += 2_000
        assertFalse(g.allow("a"))
        now += 2_000
        assertFalse(g.allow("a"))
    }

    @Test
    fun `a different status inside the window is posted`() {
        val g = gate()
        assertTrue(g.allow("a"))
        now += 1_000
        assertTrue(g.allow("b"))
    }

    @Test
    fun `an identical post after the window is posted again`() {
        val g = gate()
        assertTrue(g.allow("a"))
        now += 10_001
        assertTrue(g.allow("a"))
    }

    @Test
    fun `a held back post does not extend the window`() {
        val g = gate()
        assertTrue(g.allow("a"))
        now += 9_000
        assertFalse(g.allow("a"))
        now += 1_500
        assertTrue("10.5 s after the post that went out", g.allow("a"))
    }

    @Test
    fun `reset starts over`() {
        val g = gate()
        assertTrue(g.allow("a"))
        g.reset()
        assertTrue(g.allow("a"))
    }
}
