package com.example.phonesteward.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N36: the session notification names each action, short and without codes. */
class ActionTextTest {

    @Test
    fun `each action is named in words`() {
        assertEquals("Looking at the screen", ActionText.describe("screen_observe", emptyMap(), null))
        assertEquals("Tapping “Storage”", ActionText.describe("screen_act", mapOf("action" to "tap"), "Storage"))
        assertEquals("Typing in “Search”", ActionText.describe("screen_act", mapOf("action" to "type"), "Search"))
        assertEquals("Scrolling down", ActionText.describe("screen_act", mapOf("action" to "scroll", "direction" to "down"), null))
        assertEquals("Scrolling up", ActionText.describe("screen_act", mapOf("action" to "scroll", "direction" to "up"), null))
        assertEquals("Going back", ActionText.describe("screen_act", mapOf("action" to "back"), null))
        assertEquals("Going to the home screen", ActionText.describe("screen_act", mapOf("action" to "home"), null))
        assertEquals("Opening com.android.settings", ActionText.describe("screen_act", mapOf("action" to "open_app", "packageName" to "com.android.settings"), null))
    }

    @Test
    fun `a tap without a label still reads naturally`() {
        assertEquals("Tapping an item", ActionText.describe("screen_act", mapOf("action" to "tap"), null))
        assertEquals("Typing in a field", ActionText.describe("screen_act", mapOf("action" to "type"), "  "))
    }

    @Test
    fun `codes are masked and long labels are cut`() {
        val text = ActionText.describe("screen_act", mapOf("action" to "tap"), "Your verification code is 48213")
        assertFalse(text, text.contains("48213"))
        val long = ActionText.describe("screen_act", mapOf("action" to "tap"), "x".repeat(200))
        assertTrue(long, long.length < 60)
    }

    @Test
    fun `an unknown action is described without echoing the model's text`() {
        assertEquals("Working on the screen", ActionText.describe("screen_act", mapOf("action" to "rm -rf /"), null))
    }
}
