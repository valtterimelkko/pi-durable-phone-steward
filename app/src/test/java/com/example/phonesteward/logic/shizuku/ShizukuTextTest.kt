package com.example.phonesteward.logic.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Status card walks the owner through Shizuku in plain words (A10.2). */
class ShizukuTextTest {

    @Test
    fun `absent offers to get it, with the three steps`() {
        val c = ShizukuText.card(ShizukuState.ABSENT)
        assertEquals(ShizukuText.Action.GET_SHIZUKU, c.primary)
        assertEquals(3, c.steps.size)
        assertTrue(c.summary.startsWith("Not set up."))
    }

    @Test
    fun `not running offers to open Shizuku`() {
        val c = ShizukuText.card(ShizukuState.NOT_RUNNING)
        assertEquals(ShizukuText.Action.OPEN_SHIZUKU, c.primary)
        assertTrue(c.summary.contains("after every restart"))
    }

    @Test
    fun `no permission has exactly one button, Allow`() {
        val c = ShizukuText.card(ShizukuState.NO_PERMISSION)
        assertEquals(ShizukuText.Action.ALLOW_SHIZUKU, c.primary)
        assertNull(c.secondary)
        assertEquals("Allow Shizuku for the steward", ShizukuText.label(ShizukuText.Action.ALLOW_SHIZUKU))
    }

    @Test
    fun `ready has no button and no steps`() {
        val c = ShizukuText.card(ShizukuState.READY)
        assertNull(c.primary)
        assertTrue(c.steps.isEmpty())
        assertTrue(c.summary.startsWith("Ready."))
    }

    @Test
    fun `no state sends the owner to a developer command`() {
        for (s in ShizukuState.entries) {
            val c = ShizukuText.card(s)
            val text = (listOf(c.summary) + c.steps).joinToString(" ")
            assertFalse(text, Regex("\\badb\\b|\\bcurl\\b|bin/steward", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
    }
}
