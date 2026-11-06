package com.example.phonesteward.logic.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.ui.AppViewModel.ChatEntry

/**
 * A15.6/A15.7: "round" is defined in one place, [StewardRounds] — all the steward's text for
 * one owner message, tool lines, phone-use step lines and thinking left out. These tests pin
 * the definition.
 */
class StewardRoundsTest {

    private var seq = 0L
    private fun next() = ++seq

    private fun assistant(text: String) = ChatEntry("assistant", text, 0, seq = next())
    private fun owner(text: String) = ChatEntry("user", text, 0, seq = next())
    private fun tool() = ChatEntry("system", "", 0, "tool", seq = next(), name = "phone_snapshot", status = "done", callId = "c")
    private fun runStart() = ChatEntry("system", "", 0, "run", seq = next(), status = "started")
    private fun runEnd() = ChatEntry("system", "", 0, "run", seq = next(), status = "ended")
    private fun divider() = ChatEntry("system", "Fresh start: …", 0, "context", seq = next())
    private fun say(text: String) = ChatEntry("assistant", text, 0, seq = next(), phone = com.example.phonesteward.ui.AppViewModel.PhoneUseEntry("say"))

    @Test
    fun `one round spans the steward's text between owner messages`() {
        val entries = listOf(
            owner("What is eating my storage?"),
            tool(),
            assistant("Let me look."),
            tool(),
            assistant("Here is **what I found**."),
        )
        val rounds = StewardRounds.build(entries)
        assertEquals(1, rounds.size)
        assertEquals("Let me look.\n\nHere is **what I found**.", rounds.single().markdown)
    }

    @Test
    fun `a round's end carries the seq of its last message`() {
        val entries = listOf(owner("Hi"), assistant("First"), assistant("Second"))
        val rounds = StewardRounds.build(entries)
        assertEquals(entries.last().seq, rounds.single().endSeq)
    }

    @Test
    fun `a new owner message starts a new round`() {
        val entries = listOf(owner("A"), assistant("One"), owner("B"), assistant("Two"))
        val rounds = StewardRounds.build(entries)
        assertEquals(2, rounds.size)
        assertEquals("One", rounds[0].markdown)
        assertEquals("Two", rounds[1].markdown)
    }

    @Test
    fun `a fresh-start divider cuts the conversation like an owner message`() {
        val entries = listOf(assistant("Before the restart"), divider(), assistant("After the restart"))
        val rounds = StewardRounds.build(entries)
        assertEquals(2, rounds.size)
        assertEquals("Before the restart", rounds[0].markdown)
        assertEquals("After the restart", rounds[1].markdown)
    }

    @Test
    fun `the session model's own words are steward speech in the round`() {
        val entries = listOf(owner("Go"), say("Opening Photos now."))
        val rounds = StewardRounds.build(entries)
        assertEquals("Opening Photos now.", rounds.single().markdown)
    }

    @Test
    fun `a round is finished when the owner spoke again`() {
        val entries = listOf(owner("A"), assistant("One"), owner("B"), assistant("Two"), runStart())
        val rounds = StewardRounds.build(entries)
        assertTrue(rounds[0].finished)
    }

    @Test
    fun `the last round is finished when no run is open`() {
        val entries = listOf(owner("A"), runStart(), tool(), assistant("Done."), runEnd())
        val rounds = StewardRounds.build(entries)
        assertTrue(rounds.single().finished)
    }

    @Test
    fun `the last round is not finished while a run is still open`() {
        val entries = listOf(owner("A"), runStart(), assistant("Working on it."))
        val rounds = StewardRounds.build(entries)
        assertFalse(rounds.single().finished)
    }

    @Test
    fun `an open run older than the stale window leaves the round finished`() {
        val entries = listOf(owner("A"), run("started", atMs = 1), assistant("Working on it."))
        val rounds = StewardRounds.build(entries, nowMs = ChatRows.WORKING_STALE_MS + 60_000)
        assertTrue(rounds.single().finished)
    }

    @Test
    fun `chat with no steward text draws no rounds`() {
        val entries = listOf(owner("Hello"), tool())
        assertTrue(StewardRounds.build(entries).isEmpty())
    }

    @Test
    fun `the round map gives the finished text for the ending row only`() {
        val entries = listOf(owner("A"), assistant("First"), assistant("Second"), owner("B"), assistant("Third"))
        val byEnd = StewardRounds.byEndSeq(StewardRounds.build(entries))
        assertNull(byEnd[entries[1].seq])
        assertEquals("First\n\nSecond", byEnd[entries[2].seq]?.markdown)
        assertEquals("Third", byEnd[entries[4].seq]?.markdown)
    }

    private fun run(status: String, atMs: Long) = ChatEntry("system", "", atMs, "run", seq = next(), status = status)

    // ---- A15.10: the round carries the model that answered it ----

    @Test
    fun `a round carries the model of its last message`() {
        val first = assistant("One").copy(answering = model("zai", "glm-5.3-flash", "GLM 5.3 Flash"))
        val entries = listOf(owner("Q"), first, assistant("Two").copy(answering = model("anthropic", "sonnet-5-5", "Claude Sonnet 5.5")))
        val round = StewardRounds.build(entries).single()
        assertEquals("Claude Sonnet 5.5", round.model!!.label)
    }

    @Test
    fun `a round with no model words carries none`() {
        assertNull(StewardRounds.build(listOf(owner("Q"), assistant("One"))).single().model)
    }

    private fun model(provider: String, id: String, label: String) =
        com.example.phonesteward.net.AnsweringModel(provider, id, label)
}
