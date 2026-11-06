package com.example.phonesteward.logic.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.ChatEvent
import com.example.phonesteward.ui.AppViewModel.ChatEntry
import com.example.phonesteward.ui.AppViewModel.PhoneUseEntry

/** A13.6: the rows the `phone_use` entries draw at each verbosity. */
class ChatRowsPhoneUseTest {

    private fun entry(seq: Long, kind: String, atMs: Long = 1_000L + seq, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        ChatLog.entryFor(ChatEvent(seq, "phone_use", buildJsonObject { put("kind", kind); put("atMs", atMs); build() }))!!

    private val cards = PhoneUseCards(pendingRequestId = "pu-1", serviceOn = true)

    private fun requestEntry(seq: Long, id: String = "pu-1", status: String = "pending") =
        entry(seq, "request") { put("requestId", id); put("task", "Clear WhatsApp's cache"); put("why", "It holds 1.2 GB") }
            .let { if (status == "pending") it else it.copy(phone = it.phone!!.copy(status = status)) }

    @Test
    fun `a pending request draws an open card`() {
        val rows = ChatRows.build(listOf(requestEntry(10)), ChatVerbosity.Medium, nowMs = 1L, phoneUse = cards)
        val row = rows.filterIsInstance<PhoneUseRequestRow>().single()
        assertTrue(row.open)
        assertEquals("pu-1", row.requestId)
        assertEquals("Clear WhatsApp's cache", row.task)
        assertEquals("It holds 1.2 GB", row.why)
    }

    @Test
    fun `a pending request without the server's pending id shows its outcome instead`() {
        val rows = ChatRows.build(listOf(requestEntry(10)), ChatVerbosity.Medium, nowMs = 1L, phoneUse = PhoneUseCards(pendingRequestId = null, serviceOn = true))
        assertTrue(!rows.filterIsInstance<PhoneUseRequestRow>().single().open)
    }

    @Test
    fun `an answered request shows the outcome, not live buttons`() {
        val rows = ChatRows.build(listOf(requestEntry(10, status = "declined")), ChatVerbosity.Medium, nowMs = 1L, phoneUse = cards)
        val row = rows.filterIsInstance<PhoneUseRequestRow>().single()
        assertTrue(!row.open)
        assertEquals("declined", row.status)
    }

    @Test
    fun `without the cards context a pending request still draws, closed`() {
        val rows = ChatRows.build(listOf(requestEntry(10)), ChatVerbosity.Medium, nowMs = 1L)
        assertTrue(!rows.filterIsInstance<PhoneUseRequestRow>().single().open)
    }

    private fun steps(vararg seqs: Long, sessionId: String = "ls-1") =
        seqs.map { seq ->
            entry(seq, "step") { put("sessionId", sessionId); put("tool", "screen_act"); put("summary", "tap \"Clear cache\""); put("outcome", "ok") }
        }

    @Test
    fun `low verbosity hides the steps`() {
        val rows = ChatRows.build(steps(20, 21), ChatVerbosity.Low, nowMs = 1L)
        assertTrue(rows.none { it is PhoneUseStepRow || it is PhoneUseStepGroupRow })
    }

    @Test
    fun `medium collapses one session's steps into one collapsed group`() {
        val rows = ChatRows.build(steps(20, 21, 22), ChatVerbosity.Medium, nowMs = 1L)
        val groups = rows.filterIsInstance<PhoneUseStepGroupRow>()
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].count)
        assertTrue(groups[0].calls.all { it.summary == "tap \"Clear cache\"" })
        assertEquals("u20", groups[0].key)
    }

    @Test
    fun `high shows each step as its own line`() {
        val rows = ChatRows.build(steps(20, 21), ChatVerbosity.High, nowMs = 1L)
        val lines = rows.filterIsInstance<PhoneUseStepRow>()
        assertEquals(2, lines.size)
        assertEquals("tap \"Clear cache\"", lines[0].summary)
        assertEquals("ok", lines[0].outcome)
    }

    @Test
    fun `two sessions draw two medium groups`() {
        val rows = ChatRows.build(steps(20, 21) + steps(22, 23, sessionId = "ls-2"), ChatVerbosity.Medium, nowMs = 1L)
        assertEquals(2, rows.filterIsInstance<PhoneUseStepGroupRow>().size)
    }

    @Test
    fun `a message between step runs splits the groups`() {
        val say = entry(21, "say") { put("sessionId", "ls-1"); put("text", "Opening Settings."); put("atMs", 2L) }
        val rows = ChatRows.build(listOf(steps(20)[0], say, steps(22)[0]), ChatVerbosity.Medium, nowMs = 1L)
        assertEquals(2, rows.filterIsInstance<PhoneUseStepGroupRow>().size)
        assertTrue(rows.any { it is MessageRow && it.text == "Opening Settings." })
    }

    @Test
    fun `an ask draws a highlighted row with the question`() {
        val ask = entry(30, "ask") { put("sessionId", "ls-1"); put("question", "Open WhatsApp storage?"); put("askId", "a-1") }
        val rows = ChatRows.build(listOf(ask), ChatVerbosity.Low, nowMs = 1L)
        assertEquals("Open WhatsApp storage?", rows.filterIsInstance<PhoneUseAskRow>().single().question)
    }

    @Test
    fun `an ended session draws one short result line at every level`() {
        val ended = entry(40, "ended") { put("sessionId", "ls-1"); put("reason", "stopped"); put("steps", 5); put("atMs", 8L) }
        for (level in listOf(ChatVerbosity.Low, ChatVerbosity.Medium, ChatVerbosity.High)) {
            val rows = ChatRows.build(listOf(ended), level, nowMs = 1L)
            val row = rows.filterIsInstance<PhoneUseEndedRow>().single()
            assertTrue(row.text.contains("stopped"))
            assertTrue(row.text.contains("5"))
        }
    }

    @Test
    fun `an ended session carries the summary under the result line (correction 1)`() {
        val ended = entry(41, "ended") {
            put("sessionId", "ls-1"); put("reason", "finished"); put("summary", "Cleared 300 MB of cache"); put("steps", 7); put("atMs", 8L)
        }
        val rows = ChatRows.build(listOf(ended), ChatVerbosity.Low, nowMs = 1L)
        val row = rows.filterIsInstance<PhoneUseEndedRow>().single()
        assertEquals("Cleared 300 MB of cache", row.summary)
        // Without a summary there is nothing under the line.
        val plain = entry(42, "ended") { put("sessionId", "ls-1"); put("reason", "finished"); put("steps", 2); put("atMs", 8L) }
        assertNull(ChatRows.build(listOf(plain), ChatVerbosity.Low, nowMs = 1L).filterIsInstance<PhoneUseEndedRow>().single().summary)
    }

    @Test
    fun `a started session draws one quiet line`() {
        val started = entry(12, "started") { put("sessionId", "ls-1"); put("task", "Clear a cache") }
        val rows = ChatRows.build(listOf(started), ChatVerbosity.Low, nowMs = 1L)
        assertTrue(rows.filterIsInstance<PhoneUseStartedRow>().single().task == "Clear a cache")
    }

    @Test
    fun `keys are stable per event`() {
        val rows = ChatRows.build(listOf(requestEntry(10)), ChatVerbosity.Medium, nowMs = 1L, phoneUse = cards)
        val again = ChatRows.build(listOf(requestEntry(10)), ChatVerbosity.Medium, nowMs = 1L, phoneUse = cards)
        assertEquals(rows.filterIsInstance<PhoneUseRequestRow>().single().key, again.filterIsInstance<PhoneUseRequestRow>().single().key)
    }
}
