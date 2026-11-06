package com.example.phonesteward.logic.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.ChatEvent
import com.example.phonesteward.ui.AppViewModel.ChatEntry

/** F2: stream and history events folded into one ordered, de-duplicated list of entries. */
class ChatLogTest {

    private fun ev(id: Long, type: String, vararg data: Pair<String, Any?>): ChatEvent =
        ChatEvent(
            id,
            type,
            JsonObject(
                data.filter { it.second != null }.associate { (k, v) ->
                    k to when (v) {
                        is Number -> JsonPrimitive(v)
                        else -> JsonPrimitive(v.toString())
                    }
                },
            ),
        )

    private fun message(id: Long, role: String, text: String, atMs: Long = 1_000L * id) =
        ev(id, "message", "role" to role, "text" to text, "atMs" to atMs)

    @Test fun messageEventBecomesAnEntry() {
        val e = ChatLog.entryFor(message(5, "assistant", "hi"))!!
        assertEquals("assistant", e.role)
        assertEquals("hi", e.text)
        assertEquals(5L, e.seq)
        assertEquals("message", e.kind)
    }

    @Test fun anAssistantMessageCarriesTheModelThatAnsweredIt() {
        val e = ChatLog.entryFor(
            ChatEvent(7, "message", kotlinx.serialization.json.buildJsonObject {
                put("role", "assistant")
                put("text", "hi")
                put("atMs", 1_000L)
                put("model", kotlinx.serialization.json.buildJsonObject {
                    put("provider", "anthropic"); put("id", "claude-sonnet-5-5"); put("label", "Claude Sonnet 5.5")
                })
            }),
        )!!
        assertEquals("anthropic", e.answering!!.provider)
        assertEquals("claude-sonnet-5-5", e.answering!!.id)
        assertEquals("Claude Sonnet 5.5", e.answering!!.label)
    }

    @Test fun anAssistantMessageWithoutAModelShowsNone() {
        assertNull(ChatLog.entryFor(message(9, "assistant", "hi"))!!.answering)
    }

    @Test fun aPhoneSessionsStartCarriesItsModel() {
        val e = ChatLog.entryFor(
            ChatEvent(11, "phone_use", kotlinx.serialization.json.buildJsonObject {
                put("kind", "started"); put("sessionId", "ls-1"); put("task", "Clear the cache"); put("atMs", 1L)
                put("model", kotlinx.serialization.json.buildJsonObject {
                    put("provider", "zai"); put("id", "glm-5.3-flash"); put("label", "GLM 5.3 Flash")
                })
            }),
        )!!
        assertEquals("GLM 5.3 Flash", e.phone!!.model!!.label)
    }

    @Test fun heartbeatAndJunkProduceNothing() {
        assertNull(ChatLog.entryFor(ev(1, "heartbeat")))
        assertNull(ChatLog.entryFor(ev(2, "unknown")))
        assertNull(ChatLog.entryFor(ChatEvent(3, "message", JsonObject(emptyMap()))))
    }

    @Test fun anAssistantMessageWithNoTextIsDropped() {
        // The server writes one for a turn that only called tools.
        assertNull(ChatLog.entryFor(message(1, "assistant", "   ")))
    }

    @Test fun toolEventCarriesItsSummaries() {
        val e = ChatLog.entryFor(
            ev(
                3, "tool", "name" to "decisions", "status" to "done", "callId" to "call_1",
                "argsSummary" to "limit 10", "resultSummary" to "3 entries", "durationMs" to 1200, "atMs" to 5000,
            ),
        )!!
        assertEquals("tool", e.kind)
        assertEquals("decisions", e.name)
        assertEquals("done", e.status)
        assertEquals("call_1", e.callId)
        assertEquals("limit 10", e.argsSummary)
        assertEquals("3 entries", e.resultSummary)
        assertEquals(1200L, e.durationMs)
        assertEquals(5000L, e.atMs)
    }

    @Test fun oldServerToolEventStillWorks() {
        val e = ChatLog.entryFor(ev(3, "tool", "name" to "rules", "status" to "started"))!!
        assertEquals("rules", e.name)
        assertNull(e.callId)
        assertNull(e.argsSummary)
    }

    @Test fun runAndProposalEvents() {
        val run = ChatLog.entryFor(ev(1, "run", "status" to "ended", "reason" to "owner", "atMs" to 9, "durationMs" to 4000))!!
        assertEquals("run", run.kind)
        assertEquals("ended", run.status)
        assertEquals("owner", run.reason)
        assertEquals(4000L, run.durationMs)
        val proposal = ChatLog.entryFor(ev(2, "proposal", "proposalId" to "pr-1", "title" to "Clear caches"))!!
        assertEquals("proposal", proposal.kind)
        assertEquals("pr-1", proposal.proposalId)
        assertEquals("Clear caches", proposal.text)
    }

    @Test fun aReplayedEventIsNotAddedTwice() {
        val once = ChatLog.apply(emptyList(), message(1, "assistant", "a"))
        val twice = ChatLog.apply(once, message(1, "assistant", "a"))
        assertEquals(1, twice.size)
    }

    @Test fun historyAndStreamInterleaveInSequenceOrder() {
        val streamed = ChatLog.applyAll(emptyList(), listOf(message(10, "assistant", "ten"), message(11, "assistant", "eleven")))
        val merged = ChatLog.applyAll(streamed, listOf(message(8, "user", "eight"), message(9, "assistant", "nine"), message(10, "assistant", "ten")))
        assertEquals(listOf(8L, 9L, 10L, 11L), merged.map { it.seq })
    }

    @Test fun theServersEchoOfMyMessageReplacesTheLocalOne() {
        val local = ChatLog.withLocalOwner(emptyList(), "What is eating my storage?", 100)
        assertEquals(1, local.size)
        assertEquals(0L, local[0].seq)
        val echoed = ChatLog.apply(local, message(4, "user", "What is eating my storage?"))
        assertEquals(1, echoed.size)
        assertEquals(4L, echoed[0].seq)
    }

    @Test fun anUnrelatedServerMessageKeepsTheLocalOneAtTheEnd() {
        val local = ChatLog.withLocalOwner(emptyList(), "hello", 100)
        val next = ChatLog.apply(local, message(4, "assistant", "earlier reply"))
        assertEquals(listOf(4L, 0L), next.map { it.seq })
        assertEquals("hello", next.last().text)
    }

    @Test fun twoIdenticalLocalMessagesAreMatchedOneToOne() {
        var list = ChatLog.withLocalOwner(emptyList(), "ok", 1)
        list = ChatLog.withLocalOwner(list, "ok", 2)
        list = ChatLog.apply(list, message(7, "user", "ok"))
        assertEquals(listOf(7L, 0L), list.map { it.seq })
    }

    @Test fun startedAndDoneMergeByCallId() {
        var list = ChatLog.apply(
            emptyList(),
            ev(1, "tool", "name" to "decisions", "status" to "started", "callId" to "c1", "argsSummary" to "limit 10", "atMs" to 100),
        )
        list = ChatLog.apply(
            list,
            ev(2, "tool", "name" to "decisions", "status" to "done", "callId" to "c1", "resultSummary" to "no entries yet", "durationMs" to 800),
        )
        assertEquals(1, list.size)
        val tool = list.single()
        assertEquals("done", tool.status)
        assertEquals("limit 10", tool.argsSummary) // kept from the start event
        assertEquals("no entries yet", tool.resultSummary)
        assertEquals(800L, tool.durationMs)
        assertEquals(100L, tool.atMs)
    }

    @Test fun twoCallsOfTheSameToolStayApartByCallId() {
        var list = ChatLog.applyAll(
            emptyList(),
            listOf(
                ev(1, "tool", "name" to "decisions", "status" to "started", "callId" to "a"),
                ev(2, "tool", "name" to "decisions", "status" to "started", "callId" to "b"),
                ev(3, "tool", "name" to "decisions", "status" to "error", "callId" to "b"),
            ),
        )
        assertEquals(listOf("started", "error"), list.map { it.status })
    }

    @Test fun withoutACallIdADoneEventClosesTheOldestOpenCallOfThatName() {
        val list = ChatLog.applyAll(
            emptyList(),
            listOf(
                ev(1, "tool", "name" to "rules", "status" to "started"),
                ev(2, "tool", "name" to "decisions", "status" to "started"),
                ev(3, "tool", "name" to "rules", "status" to "done"),
            ),
        )
        assertEquals(listOf("done", "started"), list.map { it.status })
    }

    @Test fun replayingAMergedToolPairAddsNothing() {
        val events = listOf(
            ev(1, "tool", "name" to "x", "status" to "started", "callId" to "c"),
            ev(2, "tool", "name" to "x", "status" to "done", "callId" to "c"),
        )
        val once = ChatLog.applyAll(emptyList(), events)
        val twice = ChatLog.applyAll(once, events)
        assertEquals(1, twice.size)
        assertEquals("done", twice.single().status)
    }

    @Test fun aDoneEventWhoseStartScrolledOffStillShows() {
        val list = ChatLog.apply(emptyList(), ev(9, "tool", "name" to "x", "status" to "done", "callId" to "c"))
        assertEquals("done", list.single().status)
    }

    @Test fun theListIsCapped() {
        var list = emptyList<ChatEntry>()
        for (i in 1L..(ChatLog.MAX_ENTRIES + 50L)) list = ChatLog.apply(list, message(i, "assistant", "m$i"))
        assertEquals(ChatLog.MAX_ENTRIES, list.size)
        assertEquals(ChatLog.MAX_ENTRIES + 50L, list.last().seq)
        assertTrue(list.first().seq > 1)
    }
}
