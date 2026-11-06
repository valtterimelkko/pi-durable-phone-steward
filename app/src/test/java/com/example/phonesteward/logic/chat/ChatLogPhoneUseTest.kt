package com.example.phonesteward.logic.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.ChatEvent
import com.example.phonesteward.ui.AppViewModel.ChatEntry

/**
 * A13.6: Chat folds the `phone_use` events (request, answer, started, step, say, ask, ended) into
 * entries — the request card, the session's steps, the ask and the end — and ignores kinds and
 * fields it does not know.
 */
class ChatLogPhoneUseTest {

    private fun event(id: Long, data: kotlinx.serialization.json.JsonObject) = ChatEvent(id, "phone_use", data)

    @Test
    fun `a request event becomes one phone entry with the task and the why`() {
        val e = event(
            10,
            buildJsonObject {
                put("kind", "request"); put("requestId", "pu-1")
                put("task", "Clear WhatsApp's cache in Settings"); put("why", "It holds 1.2 GB of cache")
                put("atMs", 5_000L)
            },
        )
        val entry = ChatLog.entryFor(e)
        assertNotNull(entry)
        val p = entry!!.phone!!
        assertEquals("request", p.kind)
        assertEquals("pu-1", p.requestId)
        assertEquals("Clear WhatsApp's cache in Settings", p.task)
        assertEquals("It holds 1.2 GB of cache", p.why)
        assertEquals("phone_use", entry.kind)
    }

    @Test
    fun `an answer event folds into its request entry`() {
        val request = event(
            10,
            buildJsonObject {
                put("kind", "request"); put("requestId", "pu-1"); put("task", "T"); put("why", "W"); put("atMs", 1L)
            },
        )
        val answer = event(
            11,
            buildJsonObject {
                put("kind", "answer"); put("requestId", "pu-1"); put("allowed", true); put("sessionId", "ls-9"); put("atMs", 2L)
            },
        )
        val list = ChatLog.applyAll(emptyList(), listOf(request, answer))
        val card = list.single { it.kind == "phone_use" }
        assertEquals("allowed", card.phone!!.status)
        assertEquals("ls-9", card.phone!!.sessionId)
        assertEquals(1, list.count { it.kind == "phone_use" })
    }

    @Test
    fun `a decline folds too`() {
        val request = event(10, buildJsonObject { put("kind", "request"); put("requestId", "pu-2"); put("task", "T"); put("why", "W"); put("atMs", 1L) })
        val answer = event(11, buildJsonObject { put("kind", "answer"); put("requestId", "pu-2"); put("allowed", false); put("atMs", 2L) })
        val list = ChatLog.applyAll(emptyList(), listOf(request, answer))
        assertEquals("declined", list.single { it.kind == "phone_use" }.phone!!.status)
    }

    @Test
    fun `an answer without a request is not drawn`() {
        val answer = event(11, buildJsonObject { put("kind", "answer"); put("requestId", "pu-x"); put("allowed", false); put("atMs", 2L) })
        assertTrue(ChatLog.apply(emptyList(), answer).isEmpty())
    }

    @Test
    fun `started and ended become their own entries`() {
        val started = event(12, buildJsonObject { put("kind", "started"); put("sessionId", "ls-9"); put("task", "Clear a cache"); put("atMs", 3L) })
        val ended = event(
            20,
            buildJsonObject {
                put("kind", "ended"); put("sessionId", "ls-9"); put("reason", "finished")
                put("summary", "Cleared 300 MB"); put("steps", 7); put("atMs", 9_000L)
            },
        )
        val list = ChatLog.applyAll(emptyList(), listOf(started, ended))
        val s = list.single { it.phone?.kind == "started" }
        assertEquals("Clear a cache", s.phone!!.task)
        val e = list.single { it.phone?.kind == "ended" }
        assertEquals("finished", e.phone!!.reason)
        assertEquals(7, e.phone!!.steps)
        assertEquals("Cleared 300 MB", e.phone!!.summary)
    }

    @Test
    fun `a step carries the summary, the tool and the outcome`() {
        val step = event(
            13,
            buildJsonObject {
                put("kind", "step"); put("sessionId", "ls-9"); put("tool", "screen_act")
                put("summary", "tap \"Clear cache\""); put("outcome", "ok"); put("atMs", 4L)
            },
        )
        val entry = ChatLog.entryFor(step)!!
        assertEquals("step", entry.phone!!.kind)
        assertEquals("tap \"Clear cache\"", entry.phone!!.summary)
        assertEquals("ok", entry.phone!!.outcome)
        assertEquals("screen_act", entry.phone!!.tool)
    }

    @Test
    fun `an ask is its own entry with the question`() {
        val ask = event(
            14,
            buildJsonObject { put("kind", "ask"); put("sessionId", "ls-9"); put("question", "Open WhatsApp storage?"); put("askId", "a-1"); put("atMs", 5L) },
        )
        val entry = ChatLog.entryFor(ask)!!
        assertEquals("ask", entry.phone!!.kind)
        assertEquals("Open WhatsApp storage?", entry.phone!!.question)
        assertEquals("a-1", entry.phone!!.askId)
    }

    @Test
    fun `say becomes an ordinary steward message`() {
        val say = event(15, buildJsonObject { put("kind", "say"); put("sessionId", "ls-9"); put("text", "Opening Settings."); put("atMs", 6L) })
        val entry = ChatLog.entryFor(say)!!
        assertEquals("message", entry.kind)
        assertEquals("assistant", entry.role)
        assertEquals("Opening Settings.", entry.text)
    }

    @Test
    fun `unknown kinds and unreadable bodies are ignored`() {
        assertNull(ChatLog.entryFor(event(16, buildJsonObject { put("kind", "party"); put("atMs", 1L) })))
        assertNull(ChatLog.entryFor(event(17, buildJsonObject { put("atMs", 1L) })))
        // Unknown extra fields are tolerated.
        val extra = event(
            18,
            buildJsonObject { put("kind", "step"); put("sessionId", "s"); put("tool", "screen_act"); put("summary", "tap"); put("outcome", "ok"); put("wand", 3); put("atMs", 7L) },
        )
        assertEquals("step", ChatLog.entryFor(extra)!!.phone!!.kind)
    }

    @Test
    fun `a replayed answer does not fold twice`() {
        val request = event(10, buildJsonObject { put("kind", "request"); put("requestId", "pu-1"); put("task", "T"); put("why", "W"); put("atMs", 1L) })
        val answer = event(11, buildJsonObject { put("kind", "answer"); put("requestId", "pu-1"); put("allowed", true); put("atMs", 2L) })
        var list = ChatLog.applyAll(emptyList(), listOf(request, answer))
        list = ChatLog.applyAll(list, listOf(answer))
        assertEquals(1, list.count { it.kind == "phone_use" })
        assertEquals("allowed", list.single { it.kind == "phone_use" }.phone!!.status)
    }

    @Test
    fun `the dedupe by event id still applies to phone entries`() {
        val step = event(13, buildJsonObject { put("kind", "step"); put("sessionId", "s"); put("tool", "screen_act"); put("summary", "tap"); put("outcome", "ok"); put("atMs", 1L) })
        val once = ChatLog.applyAll(emptyList(), listOf(step))
        assertEquals(once, ChatLog.applyAll(once, listOf(step)))
    }

    @Test
    fun `an answer fold never duplicates a local owner message (correction 1)`() {
        val request = event(10, buildJsonObject { put("kind", "request"); put("requestId", "pu-1"); put("task", "T"); put("why", "W"); put("atMs", 1L) })
        var list = ChatLog.applyAll(emptyList(), listOf(request))
        // The owner typed an answer in the composer; the server has not echoed it yet (seq == 0).
        list = ChatLog.withLocalOwner(list, "Yes, go ahead", 2_000L)
        val answer = event(11, buildJsonObject { put("kind", "answer"); put("requestId", "pu-1"); put("allowed", true); put("sessionId", "ls-1"); put("atMs", 3_000L) })
        list = ChatLog.apply(list, answer)
        assertEquals("once before the echo", 1, list.count { it.kind == "message" && it.role == "user" && it.text == "Yes, go ahead" })
        // The server's echo arrives: still exactly one.
        val echo = ChatEvent(12, "message", buildJsonObject { put("role", "user"); put("text", "Yes, go ahead"); put("atMs", 4_000L) })
        list = ChatLog.apply(list, echo)
        assertEquals("once after the echo", 1, list.count { it.kind == "message" && it.role == "user" && it.text == "Yes, go ahead" })
    }
}
