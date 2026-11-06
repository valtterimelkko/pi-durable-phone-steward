package com.example.phonesteward.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    private data class Emitted(val id: Long?, val type: String?, val data: String)

    private fun parse(text: String): List<Emitted> {
        val out = mutableListOf<Emitted>()
        val parser = SseParser { id, type, data -> out += Emitted(id, type, data) }
        parser.feedAll(text)
        return out
    }

    @Test
    fun `parses the protocol's five event kinds`() {
        val events = parse(
            """
            id: 1
            event: message
            data: {"role": "user", "text": "hi", "atMs": 0}

            id: 2
            event: tool
            data: {"name": "phone_media_index", "status": "started"}

            id: 3
            event: proposal
            data: {"proposalId": "pr-1", "title": "t"}

            id: 4
            event: run
            data: {"status": "started", "reason": "owner"}

            id: 5
            event: heartbeat
            data: {}

            """.trimIndent(),
        )
        assertEquals(5, events.size)
        assertEquals(1L, events[0].id)
        assertEquals("message", events[0].type)
        assertEquals("""{"role": "user", "text": "hi", "atMs": 0}""", events[0].data)
        assertEquals("heartbeat", events[4].type)
        assertEquals("{}", events[4].data)
    }

    @Test
    fun `multi-line data joins with newlines`() {
        val events = parse(
            """
            id: 7
            event: message
            data: {"text": "line1
            data: line2"}

            """.trimIndent(),
        )
        assertEquals(1, events.size)
        // The two data lines join with a real newline between them.
        assertEquals("{\"text\": \"line1\nline2\"}", events.single().data)
        assertEquals(7L, events.single().id)
    }

    @Test
    fun `comment lines and CRLF are tolerated`() {
        val events = parse(
            ": keep-alive\r\nid: 9\r\nevent: heartbeat\r\ndata: {}\r\n\r\n",
        )
        assertEquals(1, events.size)
        assertEquals(9L, events.single().id)
    }

    @Test
    fun `blank input and dataless blocks emit nothing`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("id: 1\n\n").isEmpty())
    }

    @Test
    fun `missing or non-numeric id yields null`() {
        val events = parse("event: heartbeat\ndata: {}\n\n")
        assertNull(events.single().id)
    }

    @Test
    fun `default event type is null when omitted`() {
        val events = parse("id: 2\ndata: {}\n\n")
        assertNull(events.single().type)
    }
}
