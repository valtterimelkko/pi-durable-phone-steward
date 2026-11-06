package com.example.phonesteward.logic.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.ui.AppViewModel.ChatEntry

/** F2 / N14: the chat entries mapped to rows at each verbosity level (pure JVM). */
class ChatRowsTest {

    private var seq = 0L
    private fun next() = ++seq

    private fun msg(role: String, text: String, atMs: Long = 0) = ChatEntry(role, text, atMs, seq = next())
    private fun tool(name: String, status: String, callId: String? = null, args: String? = null, result: String? = null, ms: Long? = null) =
        ChatEntry("system", "", 0, "tool", seq = next(), name = name, status = status, callId = callId,
            argsSummary = args, resultSummary = result, durationMs = ms)
    private fun run(status: String, reason: String = "owner", atMs: Long = 0, ms: Long? = null) =
        ChatEntry("system", "", atMs, "run", seq = next(), status = status, reason = reason, durationMs = ms)
    private fun proposal(id: String, title: String) = ChatEntry("system", title, 0, "proposal", seq = next(), proposalId = id)

    private fun history() = listOf(
        msg("user", "What is eating my storage?"),
        run("started"),
        msg("assistant", "Let me look."),
        tool("phone_snapshot", "done", "c1", "no arguments", "snapshot 3 h old", 400),
        tool("storage_trend", "done", "c2", "no arguments", "trend available", 300),
        tool("decisions", "error", "c3", "limit 10", "error: boom", 100),
        msg("assistant", "Here is **what I found**."),
        proposal("pr-1", "Clear temporary files"),
        run("ended", ms = 9_000),
    )

    private fun kinds(rows: List<ChatRow>) = rows.map { it::class.simpleName }

    // ---- Low -------------------------------------------------------------------

    @Test fun lowShowsMessagesAndProposalsOnly() {
        val rows = ChatRows.build(history(), ChatVerbosity.Low, nowMs = 0)
        assertEquals(listOf("MessageRow", "MessageRow", "MessageRow", "ProposalRow"), kinds(rows))
        assertTrue((rows[0] as MessageRow).fromOwner)
        assertFalse((rows[1] as MessageRow).fromOwner)
    }

    @Test fun lowShowsOneWorkingLineWhileARunIsOpen() {
        val rows = ChatRows.build(listOf(msg("user", "hi"), run("started"), tool("rules", "started", "c")), ChatVerbosity.Low, nowMs = 0)
        assertEquals(listOf("MessageRow", "WorkingRow"), kinds(rows))
    }

    @Test fun theFinishedRoundsLastMessageCarriesTheRoundsText() {
        val rows = ChatRows.build(
            listOf(msg("user", "Q"), msg("assistant", "A1"), msg("assistant", "A2")),
            ChatVerbosity.Low, nowMs = 0,
        )
        val first = rows[1] as MessageRow
        val last = rows[2] as MessageRow
        assertFalse(first.roundEnd)
        assertNull(first.roundText)
        assertTrue(last.roundEnd)
        assertEquals("A1\n\nA2", last.roundText)
    }

    @Test fun anUnfinishedRoundCarriesNoRoundText() {
        val rows = ChatRows.build(
            listOf(msg("user", "Q"), run("started"), msg("assistant", "Working")),
            ChatVerbosity.Low, nowMs = 0,
        )
        assertFalse((rows[1] as MessageRow).roundEnd)
        assertNull((rows[1] as MessageRow).roundText)
    }

    @Test fun aFinishedRoundCarriesTheAnsweringModelsLabel() {
        val rows = ChatRows.build(
            listOf(
                msg("user", "Q"),
                msg("assistant", "A").copy(answering = com.example.phonesteward.net.AnsweringModel("zai", "glm-5.3-flash", "GLM 5.3 Flash")),
            ),
            ChatVerbosity.Low, nowMs = 0,
        )
        val row = rows[1] as MessageRow
        assertEquals("GLM 5.3 Flash", row.roundModelLabel)
        assertNull("the chosen model is GLM 5.3 Flash here, so no warning", row.roundModelWarning)
    }

    @Test fun aRoundAnsweredByAnotherModelSaysSoPlainly() {
        val chosen = com.example.phonesteward.net.AnsweringModel("zai", "glm-5.3-flash", "GLM 5.3 Flash")
        val rows = ChatRows.build(
            listOf(
                msg("user", "Q"),
                msg("assistant", "A").copy(answering = com.example.phonesteward.net.AnsweringModel("openai-codex", "gpt-6.1-sol", "GPT 6.1 Sol")),
            ),
            ChatVerbosity.Low, nowMs = 0, chosenModel = chosen,
        )
        val row = rows[1] as MessageRow
        assertEquals("GPT 6.1 Sol", row.roundModelLabel)
        assertEquals("Answered by GPT 6.1 Sol, not GLM 5.3 Flash", row.roundModelWarning)
    }

    @Test fun onlyTheNewestRoundWarnsSoAnOwnersModelSwitchDoesNotFlagOldRounds() {
        // The owner chose Haiku, then switched to Sonnet on purpose: the old Haiku round keeps its
        // label but no warning; only the newest round is compared with the model chosen now.
        val haiku = com.example.phonesteward.net.AnsweringModel("openrouter", "~anthropic/claude-haiku-latest", "Claude Haiku (alias)")
        val sonnet = com.example.phonesteward.net.AnsweringModel("anthropic", "claude-sonnet-5-5", "Claude Sonnet 5.5")
        val sol = com.example.phonesteward.net.AnsweringModel("openai-codex", "gpt-6.1-sol", "GPT 6.1 Sol")
        val rows = ChatRows.build(
            listOf(
                msg("user", "Q1"), msg("assistant", "A1").copy(answering = haiku),
                msg("user", "Q2"), msg("assistant", "A2").copy(answering = sol),
            ),
            ChatVerbosity.Low, nowMs = 0, chosenModel = sonnet,
        ).filterIsInstance<MessageRow>().filter { !it.fromOwner }
        assertEquals("Claude Haiku (alias)", rows[0].roundModelLabel)
        assertNull("an older round never warns", rows[0].roundModelWarning)
        assertEquals("Answered by GPT 6.1 Sol, not Claude Sonnet 5.5", rows[1].roundModelWarning)
    }

    @Test fun aRoundFromTheChosenModelCarriesNoWarning() {
        val chosen = com.example.phonesteward.net.AnsweringModel("zai", "glm-5.3-flash", "GLM 5.3 Flash")
        val rows = ChatRows.build(
            listOf(msg("user", "Q"), msg("assistant", "A").copy(answering = chosen)),
            ChatVerbosity.Low, nowMs = 0, chosenModel = chosen,
        )
        assertNull((rows[1] as MessageRow).roundModelWarning)
    }

    @Test fun aRoundWhoseModelIsUnknownCarriesNoModelLine() {
        val rows = ChatRows.build(listOf(msg("user", "Q"), msg("assistant", "A")), ChatVerbosity.Low, nowMs = 0)
        assertNull((rows[1] as MessageRow).roundModelLabel)
        assertNull((rows[1] as MessageRow).roundModelWarning)
    }

    @Test fun anOwnerMessageNeverCarriesARound() {
        val rows = ChatRows.build(
            listOf(msg("assistant", "A"), msg("user", "Q")),
            ChatVerbosity.Low, nowMs = 0,
        )
        assertFalse((rows[0] as MessageRow).fromOwner)
        assertTrue((rows[0] as MessageRow).roundEnd)
        assertFalse((rows[1] as MessageRow).roundEnd)
    }

    @Test fun lowHidesNotesAndPhoneResults() {
        val entries = listOf(
            msg("user", "[schedule weekly_check 2026-10-07] Run this week's storage check ..."),
            msg("user", "[phone result pj-1] kind=storage_overview status=ok data={}"),
            msg("assistant", "Done."),
        )
        assertEquals(listOf("MessageRow"), kinds(ChatRows.build(entries, ChatVerbosity.Low, nowMs = 0)))
    }

    // ---- Medium ----------------------------------------------------------------

    @Test fun mediumGroupsTheToolCallsAndHidesRunEvents() {
        val rows = ChatRows.build(history(), ChatVerbosity.Medium, nowMs = 0)
        assertEquals(
            listOf("MessageRow", "MessageRow", "ToolGroupRow", "MessageRow", "ProposalRow"),
            kinds(rows),
        )
        val group = rows[2] as ToolGroupRow
        assertEquals(3, group.calls.size)
        assertEquals("Checked 3 things · 1 failed", group.summary)
        assertTrue(group.hasError)
    }

    @Test fun mediumNeverShowsRunEndedRows_N14() {
        val entries = (1..4).flatMap { listOf(run("started"), msg("assistant", "answer $it"), run("ended")) }
        val rows = ChatRows.build(entries, ChatVerbosity.Medium, nowMs = 0)
        assertEquals(4, rows.size)
        assertTrue(rows.all { it is MessageRow })
    }

    @Test fun mediumSplitsGroupsAtMessagesAndRuns() {
        val entries = listOf(
            run("started"), tool("rules", "done", "a"), tool("decisions", "done", "b"),
            msg("assistant", "mid"),
            tool("storage_trend", "done", "c"),
            run("ended"),
            run("started"), tool("rules", "done", "d"), run("ended"),
        )
        val rows = ChatRows.build(entries, ChatVerbosity.Medium, nowMs = 0)
        assertEquals(listOf("ToolGroupRow", "MessageRow", "ToolGroupRow", "ToolGroupRow"), kinds(rows))
        assertEquals(listOf(2, 1, 1), rows.filterIsInstance<ToolGroupRow>().map { it.calls.size })
    }

    @Test fun groupSummaryNamesWhatHappened() {
        fun summary(vararg names: String) =
            (ChatRows.build(names.map { tool(it, "done") }, ChatVerbosity.Medium, nowMs = 0).single() as ToolGroupRow).summary
        assertEquals("Checked 1 thing", summary("rules"))
        assertEquals("Checked 3 things", summary("rules", "decisions", "proposals"))
        assertEquals("Asked your phone", summary("phone_storage_overview"))
        assertEquals("Asked your phone 2 times", summary("phone_storage_overview", "phone_app_usage"))
        assertEquals("Wrote a proposal", summary("propose_cleanup"))
        assertEquals(
            "Checked 2 things, asked your phone, wrote a proposal",
            summary("rules", "decisions", "phone_media_index", "propose_cleanup"),
        )
    }

    @Test fun aRunningToolMarksTheGroupRunning() {
        val rows = ChatRows.build(listOf(run("started"), tool("rules", "started", "a")), ChatVerbosity.Medium, nowMs = 0)
        assertEquals(listOf("ToolGroupRow", "WorkingRow"), kinds(rows))
        assertTrue((rows[0] as ToolGroupRow).running)
    }

    @Test fun mediumShowsAQuietNoteForAScheduledRunAndHidesPhoneResults() {
        val entries = listOf(
            msg("user", "[schedule weekly_check 2026-10-07] Run this week's storage check ..."),
            msg("user", "[phone result pj-1] kind=storage_overview status=ok data={\"a\":1}"),
            msg("assistant", "Summary."),
        )
        val rows = ChatRows.build(entries, ChatVerbosity.Medium, nowMs = 0)
        assertEquals(listOf("NoteRow", "MessageRow"), kinds(rows))
        assertEquals("Weekly check started", (rows[0] as NoteRow).text)
    }

    // ---- High ------------------------------------------------------------------

    @Test fun highShowsEveryToolCardRunMarkersAndPhoneResults() {
        val entries = history().toMutableList()
        entries.add(1, msg("user", "[phone result pj-ab12] kind=app_usage status=ok data={}"))
        val rows = ChatRows.build(entries, ChatVerbosity.High, nowMs = 0)
        assertEquals(
            listOf(
                "MessageRow", "PhoneResultRow", "RunRow", "MessageRow",
                "ToolCardRow", "ToolCardRow", "ToolCardRow",
                "MessageRow", "ProposalRow", "RunRow",
            ),
            kinds(rows),
        )
        val card = rows[5] as ToolCardRow
        assertEquals("Storage trend", card.call.label)
        assertEquals("trend available", card.call.resultSummary)
        assertEquals(300L, card.call.durationMs)
        assertEquals(ToolState.Error, (rows[6] as ToolCardRow).call.state)
        val ended = rows.last() as RunRow
        assertFalse(ended.started)
        assertEquals(9_000L, ended.durationMs)
    }

    @Test fun highDescribesAPhoneResult() {
        val rows = ChatRows.build(
            listOf(
                msg("user", "[phone result pj-ab12] kind=app_usage status=ok data={\"secret\":\"x\"}"),
                msg("user", "[phone result pj-cd34] kind=media_index status=error error=NOT_ALLOWED nope"),
                msg("user", "[phone expired pj-ef56] kind=storage_overview status=expired"),
            ),
            ChatVerbosity.High,
            nowMs = 0,
        )
        val results = rows.map { it as PhoneResultRow }
        assertEquals("Phone answered: app usage", results[0].text)
        assertFalse(results[0].isError)
        assertEquals("Phone could not answer: media index (NOT_ALLOWED)", results[1].text)
        assertTrue(results[1].isError)
        assertEquals("Phone job expired: storage overview", results[2].text)
        assertTrue(results.none { it.text.contains("secret") })
    }

    // ---- shared ----------------------------------------------------------------

    @Test fun anOwnerMessageViaTheServerLosesItsPrefix() {
        val rows = ChatRows.build(listOf(msg("user", "[via server] hello")), ChatVerbosity.Medium, nowMs = 0)
        assertEquals("hello", (rows.single() as MessageRow).text)
    }

    @Test fun workingShowsWhileARunIsOpenAtEveryLevelAndNotAfterItEnds() {
        for (level in ChatVerbosity.entries) {
            val open = ChatRows.build(listOf(run("started")), level, nowMs = 0)
            assertTrue("$level", open.last() is WorkingRow)
            val closed = ChatRows.build(listOf(run("started"), run("ended")), level, nowMs = 0)
            assertFalse("$level", closed.any { it is WorkingRow })
        }
    }

    @Test fun overlappingRunsStayOpenUntilBothEnd() {
        val rows = ChatRows.build(listOf(run("started"), run("started"), run("ended")), ChatVerbosity.Medium, nowMs = 0)
        assertTrue(rows.last() is WorkingRow)
    }

    @Test fun aRunThatStartedLongAgoIsNotShownAsWorking() {
        val now = 10_000_000L
        val stale = ChatRows.build(listOf(run("started", atMs = now - ChatRows.WORKING_STALE_MS - 1)), ChatVerbosity.Medium, nowMs = now)
        assertTrue(stale.none { it is WorkingRow })
        val fresh = ChatRows.build(listOf(run("started", atMs = now - 60_000)), ChatVerbosity.Medium, nowMs = now)
        assertTrue(fresh.any { it is WorkingRow })
    }

    @Test fun anEndedEventWithoutItsStartDoesNotGoNegative() {
        val rows = ChatRows.build(listOf(run("ended"), run("started")), ChatVerbosity.Medium, nowMs = 0)
        assertTrue(rows.last() is WorkingRow)
    }

    @Test fun emptyHistoryGivesNoRows() {
        for (level in ChatVerbosity.entries) assertEquals(emptyList<ChatRow>(), ChatRows.build(emptyList(), level, nowMs = 0))
    }

    @Test fun rowKeysAreUniqueAndStable() {
        val entries = history()
        for (level in ChatVerbosity.entries) {
            val rows = ChatRows.build(entries, level, nowMs = 0)
            assertEquals(rows.size, rows.map { it.key }.toSet().size)
            // Appending an event must not change the keys of the rows before it.
            val more = ChatRows.build(entries + msg("assistant", "later"), level, nowMs = 0)
            assertEquals(rows.map { it.key }, more.map { it.key }.take(rows.size))
        }
    }

    @Test fun unknownToolsGetAReadableLabel() {
        val card = ChatRows.build(listOf(tool("some_new_tool", "done")), ChatVerbosity.High, nowMs = 0).single() as ToolCardRow
        assertEquals("Some new tool", card.call.label)
        assertNotNull(card.call.state)
    }

    @Test fun verbosityParsesSavedNamesAndDefaultsToMedium() {
        assertEquals(ChatVerbosity.Medium, ChatVerbosity.Default)
        assertEquals(ChatVerbosity.Low, ChatVerbosity.fromName("Low"))
        assertEquals(ChatVerbosity.High, ChatVerbosity.fromName("High"))
        assertEquals(ChatVerbosity.Medium, ChatVerbosity.fromName("bogus"))
        assertEquals(ChatVerbosity.Medium, ChatVerbosity.fromName(null))
    }
}
