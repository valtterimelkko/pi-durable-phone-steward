package com.example.phonesteward.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.logic.chat.ChatRow
import com.example.phonesteward.logic.chat.MessageRow
import com.example.phonesteward.logic.chat.WorkingRow

/** A15.6: what "a round's text" is, and the markdown reduction the speech uses. */
class RoundTextTest {

    private fun owner(key: String, text: String) = MessageRow(key, fromOwner = true, text = text, atMs = 1)
    private fun steward(key: String, text: String) = MessageRow(key, fromOwner = false, text = text, atMs = 2)

    @Test
    fun `a round is every steward message after one owner message`() {
        val rows: List<ChatRow> = listOf(
            owner("o1", "What is eating my storage?"),
            steward("s1", "Let me look."),
            steward("s2", "About 38.7 GB is free."),
        )
        val rounds = RoundText.rounds(rows)
        assertEquals(1, rounds.size)
        assertEquals("s2", rounds[0].key)
        assertEquals("Let me look.\n\nAbout 38.7 GB is free.", rounds[0].text)
        assertTrue(rounds[0].finished)
    }

    @Test
    fun `an open round is marked unfinished while the steward is working`() {
        val rows: List<ChatRow> = listOf(
            owner("o1", "Question"),
            steward("s1", "Looking…"),
            WorkingRow(),
        )
        val rounds = RoundText.rounds(rows)
        assertEquals(1, rounds.size)
        assertEquals(false, rounds[0].finished)
    }

    @Test
    fun `tool rows and owner messages do not enter a round's text`() {
        val rows: List<ChatRow> = listOf(
            owner("o1", "Question"),
            com.example.phonesteward.logic.chat.ToolGroupRow("g1", emptyList(), "checked 2 things", false, false),
            steward("s1", "Here you go."),
            owner("o2", "Thanks"),
        )
        val rounds = RoundText.rounds(rows)
        assertEquals(1, rounds.size)
        assertEquals("Here you go.", rounds[0].text)
    }

    @Test
    fun `reduces markdown to speech`() {
        assertEquals("Heading", RoundText.speechText("# Heading"))
        assertEquals("bold and italic", RoundText.speechText("**bold** and *italic*"))
        assertEquals("the docs", RoundText.speechText("[the docs](https://example.com)"))
        assertEquals("run npm test", RoundText.speechText("run `npm test`"))
        assertEquals("first\nsecond", RoundText.speechText("- first\n- second"))
        assertEquals("a quote", RoundText.speechText("> a quote"))
    }

    @Test
    fun `replaces a fenced code block with the words code block`() {
        val speech = RoundText.speechText("Before.\n```kotlin\nval x = 1\n```\nAfter.")
        assertEquals(true, speech.contains("code block"))
        assertEquals(false, speech.contains("val x"))
    }
}
