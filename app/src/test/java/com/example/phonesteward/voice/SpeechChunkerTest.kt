package com.example.phonesteward.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** A15.6: the sentence chunker, ported from Pi Web UI's `chunkIntoSentences`. */
class SpeechChunkerTest {

    @Test
    fun `splits after sentence terminators`() {
        assertEquals(listOf("One.", "Two!", "Three?"), SpeechChunker.chunkIntoSentences("One. Two! Three?"))
    }

    @Test
    fun `does not split a decimal number`() {
        assertEquals(listOf("It costs 3.5 pounds today"), SpeechChunker.chunkIntoSentences("It costs 3.5 pounds today"))
    }

    @Test
    fun `keeps a closing quote with its sentence`() {
        assertEquals(listOf("He said \"Go.\"", "Then left."), SpeechChunker.chunkIntoSentences("He said \"Go.\" Then left."))
    }

    @Test
    fun `keeps a run of terminators together`() {
        assertEquals(listOf("Really?!", "Yes."), SpeechChunker.chunkIntoSentences("Really?! Yes."))
    }

    @Test
    fun `splits an ellipsis`() {
        assertEquals(listOf("Wait…", "then continue."), SpeechChunker.chunkIntoSentences("Wait… then continue."))
    }

    @Test
    fun `hard-splits an overlong sentence at whitespace`() {
        val long = (1..40).joinToString(" ") { "word$it" }
        val chunks = SpeechChunker.chunkIntoSentences(long, maxLen = 40)
        assertEquals(true, chunks.size > 1)
        assertEquals(true, chunks.all { it.length <= 40 })
        assertEquals(long, chunks.joinToString(" "))
    }

    @Test
    fun `returns nothing for blank text`() {
        assertEquals(emptyList<String>(), SpeechChunker.chunkIntoSentences("   "))
    }
}
