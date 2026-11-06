package com.example.phonesteward.logic.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.ChatEvent
import com.example.phonesteward.ui.AppViewModel.ChatEntry

/** A10.9: a fresh start draws a divider in Chat; the chat history itself is untouched. */
class ChatContextEventTest {

    private val divider = "Fresh start: the steward now works from its records, not this conversation."

    private fun context(id: Long, text: String = divider, at: Long = 5_000) = ChatEvent(
        id, "context", buildJsonObject { put("kind", "fresh_start"); put("text", text); put("atMs", at) },
    )

    @Test
    fun `a context event becomes a divider entry in order`() {
        val before = ChatEntry("assistant", "hello", 1, seq = 1, lastSeq = 1)
        val list = ChatLog.apply(listOf(before), context(2))
        assertEquals(2, list.size)
        assertEquals("context", list[1].kind)
        assertEquals(divider, list[1].text)
        assertEquals(5_000L, list[1].atMs)
    }

    @Test
    fun `the same event twice is one divider`() {
        val once = ChatLog.apply(emptyList(), context(2))
        assertEquals(1, ChatLog.apply(once, context(2)).size)
    }

    @Test
    fun `a context event without text falls back to the standard words`() {
        val e = ChatEvent(3, "context", buildJsonObject { put("kind", "fresh_start") })
        assertEquals(divider, ChatLog.apply(emptyList(), e).single().text)
    }

    @Test
    fun `an unknown context kind is ignored`() {
        val e = ChatEvent(3, "context", buildJsonObject { put("kind", "something_else"); put("text", "x") })
        assertTrue(ChatLog.apply(emptyList(), e).isEmpty())
    }

    @Test
    fun `rows show the divider at every verbosity, between the messages around it`() {
        val entries = listOf(
            ChatEntry("user", "before", 1, seq = 1, lastSeq = 1),
            ChatEntry("system", divider, 2, kind = "context", seq = 2, lastSeq = 2),
            ChatEntry("user", "after", 3, seq = 3, lastSeq = 3),
        )
        for (level in ChatVerbosity.entries) {
            val rows = ChatRows.build(entries, level, nowMs = 10)
            assertEquals(level.name, 3, rows.size)
            val d = rows[1] as DividerRow
            assertEquals(divider, d.text)
        }
    }
}
