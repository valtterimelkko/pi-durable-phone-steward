package com.example.phonesteward.logic.chat

import com.example.phonesteward.ui.AppViewModel.ChatEntry
import com.example.phonesteward.ui.AppViewModel.PhoneUseEntry

enum class ToolState { Running, Done, Error }

/** One tool call as the rows show it. */
data class ToolCallView(
    val name: String,
    val label: String,
    val state: ToolState,
    val argsSummary: String?,
    val resultSummary: String?,
    val durationMs: Long?,
)

sealed interface ChatRow {
    val key: String
}

/** The steward's or the owner's message; the last message of a finished steward round carries its text (A15.6/A15.7). */
data class MessageRow(
    override val key: String,
    val fromOwner: Boolean,
    val text: String,
    val atMs: Long,
    /** A15.7: true on the message that ends a finished steward round (the round's buttons draw here). */
    val roundEnd: Boolean = false,
    /** A15.6/A15.7: the round's text as markdown (all the steward's text for one owner message); non-null only at a round end. */
    val roundText: String? = null,
    /** A15.10: the label of the model that answered the round (small muted text); null when the events named none. */
    val roundModelLabel: String? = null,
    /** A15.10: the honest warning when that model is not the one chosen in Settings; null when they agree. */
    val roundModelWarning: String? = null,
) : ChatRow

/** A quiet system line, for example "Weekly check started". */
data class NoteRow(override val key: String, val text: String, val atMs: Long) : ChatRow

/** High only: the phone's answer to a job, never its data. */
data class PhoneResultRow(override val key: String, val text: String, val isError: Boolean, val atMs: Long) : ChatRow

/** Medium: one run's consecutive tool calls, collapsed to a summary line. */
data class ToolGroupRow(
    override val key: String,
    val calls: List<ToolCallView>,
    val summary: String,
    val hasError: Boolean,
    val running: Boolean,
) : ChatRow

/** High: one tool call as its own expanded card. */
data class ToolCardRow(override val key: String, val call: ToolCallView) : ChatRow

data class ProposalRow(override val key: String, val proposalId: String?, val title: String) : ChatRow

/** High only: a run's start or end, with its timing. */
data class RunRow(
    override val key: String,
    val started: Boolean,
    val reason: String?,
    val atMs: Long,
    val durationMs: Long?,
) : ChatRow

/** A10.9: a fresh start's divider. */
data class DividerRow(override val key: String, val text: String) : ChatRow

/** A13.6: the steward's phone-use request — live Allow / Not now while it is pending, its outcome after. */
data class PhoneUseRequestRow(
    override val key: String,
    val requestId: String,
    val task: String,
    val why: String,
    val status: String,
    /** True only while the server still has this request pending: the live card with buttons. */
    val open: Boolean,
    val atMs: Long,
) : ChatRow

/** A13.6: the session's highlighted question; the owner answers by typing in Chat. */
data class PhoneUseAskRow(override val key: String, val question: String, val atMs: Long) : ChatRow

/** A13.6, High only: one answered screen step. */
data class PhoneUseStepRow(override val key: String, val summary: String, val outcome: String?, val atMs: Long) : ChatRow

/** A13.6, Medium only: one session's consecutive steps, collapsed. */
data class PhoneUseStepGroupRow(override val key: String, val calls: List<PhoneUseStepRow>, val count: Int) : ChatRow

/** A13.6: a session's start and its short result line (the result line carries the server's summary). */
data class PhoneUseStartedRow(
    override val key: String,
    val task: String,
    val atMs: Long,
    /** A15.10: the session's model, shown on the session's first line; null when the server named none. */
    val modelLabel: String? = null,
) : ChatRow
data class PhoneUseEndedRow(override val key: String, val text: String, val summary: String?, val atMs: Long) : ChatRow

/** What the phone-use rows need besides the history: the server's pending request and the service switch. */
data class PhoneUseCards(val pendingRequestId: String?, val serviceOn: Boolean)

/** The quiet "Working…" line while a run is open. */
data class WorkingRow(override val key: String = "working", val label: String = "Working…") : ChatRow

/** F2: maps entries to rows for a verbosity level. Pure; no Android. */
object ChatRows {

    /** An open run older than this is taken as lost (its ended event never arrived). */
    const val WORKING_STALE_MS = 30L * 60_000

    private val PHONE_RESULT = Regex("^\\[phone result ([^\\]\\s]+)\\]\\s+kind=(\\S+)\\s+status=(\\S+)(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val PHONE_EXPIRED = Regex("^\\[phone expired ([^\\]\\s]+)\\]\\s+kind=(\\S+).*$", RegexOption.DOT_MATCHES_ALL)
    private val SCHEDULE = Regex("^\\[schedule (\\S+) ([^\\]]*)\\]")
    private val ERROR_CODE = Regex("error=(\\S+)")
    private const val VIA_SERVER = "[via server] "

    private val PHONE_ASK = setOf("phone_storage_overview", "phone_app_usage", "phone_media_index")
    private val LABELS = mapOf(
        "phone_storage_overview" to "Phone storage overview",
        "phone_app_usage" to "App usage",
        "phone_media_index" to "Media index",
        "phone_snapshot" to "Latest phone snapshot",
        "storage_trend" to "Storage trend",
        "decisions" to "Past decisions",
        "proposals" to "Open proposals",
        "rules" to "Photo rules",
        "propose_cleanup" to "Cleanup proposal",
    )

    fun labelFor(toolName: String): String =
        LABELS[toolName] ?: toolName.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }.ifEmpty { "Tool" }

    private fun humanKind(kind: String) = kind.replace('_', ' ')

    /** A15.10: how a model is named to the owner — its label, or its id when the server sent none. */
    internal fun modelLabel(model: com.example.phonesteward.net.AnsweringModel): String =
        model.label.ifBlank { model.id }

    /** A13.6: the one short line a session's end draws, in plain words. */
    private fun endedText(p: PhoneUseEntry): String {
        val why = when (p.reason) {
            "finished" -> "done"
            "stopped" -> "you stopped it"
            "budget" -> "it used up its steps or time"
            "denied" -> "it reached an app it may not use"
            "app_lost" -> "the app was closed"
            "interrupted" -> "it was interrupted"
            "error", "server_ended" -> "the connection to the steward failed"
            else -> p.reason?.replace('_', ' ') ?: "it ended"
        }
        return "Phone session ended — $why · ${p.steps ?: 0} steps"
    }

    private fun view(e: ChatEntry) = ToolCallView(
        name = e.name,
        label = labelFor(e.name),
        state = when (e.status) {
            "done" -> ToolState.Done
            "error" -> ToolState.Error
            else -> ToolState.Running
        },
        argsSummary = e.argsSummary,
        resultSummary = e.resultSummary,
        durationMs = e.durationMs,
    )

    internal fun groupSummary(calls: List<ToolCallView>): String {
        val asks = calls.count { it.name in PHONE_ASK }
        val proposals = calls.count { it.name == "propose_cleanup" }
        val checks = calls.size - asks - proposals
        val parts = mutableListOf<String>()
        if (checks > 0) parts.add("checked $checks ${if (checks == 1) "thing" else "things"}")
        if (asks > 0) parts.add(if (asks == 1) "asked your phone" else "asked your phone $asks times")
        if (proposals > 0) parts.add(if (proposals == 1) "wrote a proposal" else "wrote $proposals proposals")
        val text = parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        val failed = calls.count { it.state == ToolState.Error }
        return if (failed > 0) "$text · $failed failed" else text
    }

    private fun keyOf(prefix: String, e: ChatEntry, index: Int) = if (e.seq > 0) "$prefix${e.seq}" else "${prefix}l$index"

    fun build(
        entries: List<ChatEntry>,
        verbosity: ChatVerbosity,
        nowMs: Long = System.currentTimeMillis(),
        phoneUse: PhoneUseCards? = null,
        /** A15.10: the model chosen in Settings, so a round answered by another one says so. */
        chosenModel: com.example.phonesteward.net.AnsweringModel? = null,
    ): List<ChatRow> {
        val rows = mutableListOf<ChatRow>()
        // A15.7: one definition of a steward round, shared by the round's copy button.
        val roundByEnd = StewardRounds.byEndSeq(StewardRounds.build(entries, nowMs))
        // A15.10: only the newest finished round is compared with the model chosen now, so a
        // deliberate model switch does not flag every older round.
        val newestRoundEnd = roundByEnd.values.filter { it.finished }.maxOfOrNull { it.endSeq }
        val warnModel = { round: StewardRounds.Round? -> if (round != null && round.endSeq == newestRoundEnd) chosenModel else null }
        var pending = mutableListOf<ChatEntry>()
        var phoneSteps = mutableListOf<ChatEntry>()
        var phoneStepsSession: String? = null
        var openRuns = 0
        var lastStartAt = 0L

        fun flushPhoneSteps() {
            if (phoneSteps.isEmpty()) return
            val first = phoneSteps.first()
            val lines = phoneSteps.map { PhoneUseStepRow("u${it.seq}", it.phone!!.summary.orEmpty(), it.phone!!.outcome, it.atMs) }
            when (verbosity) {
                ChatVerbosity.Low -> Unit
                ChatVerbosity.Medium -> rows.add(PhoneUseStepGroupRow("u${first.seq}", lines, lines.size))
                ChatVerbosity.High -> rows.addAll(lines)
            }
            phoneSteps = mutableListOf()
            phoneStepsSession = null
        }

        fun flushTools() {
            if (pending.isEmpty()) return
            val first = pending.first()
            when (verbosity) {
                ChatVerbosity.Low -> Unit
                ChatVerbosity.Medium -> {
                    val calls = pending.map(::view)
                    rows.add(
                        ToolGroupRow(
                            key = "g${first.seq}",
                            calls = calls,
                            summary = groupSummary(calls),
                            hasError = calls.any { it.state == ToolState.Error },
                            running = calls.any { it.state == ToolState.Running },
                        ),
                    )
                }
                ChatVerbosity.High -> pending.forEach { rows.add(ToolCardRow("t${it.seq}", view(it))) }
            }
            pending = mutableListOf()
        }

        entries.forEachIndexed { index, e ->
            when (e.kind) {
                "tool" -> {
                    flushPhoneSteps()
                    pending.add(e)
                }
                "phone_use" -> when (val kind = e.phone?.kind) {
                    "step" -> {
                        // A13.6: one collapsed group per session — a run of steps with the same session id.
                        if (phoneStepsSession != null && phoneStepsSession != e.phone?.sessionId) flushPhoneSteps()
                        phoneStepsSession = e.phone?.sessionId
                        phoneSteps.add(e)
                    }
                    "request" -> {
                        flushPhoneSteps()
                        flushTools()
                        val p = e.phone!!
                        val stillPending = p.status == "pending" && phoneUse?.pendingRequestId == p.requestId
                        rows.add(
                            PhoneUseRequestRow(
                                key = keyOf("up", e, index),
                                requestId = p.requestId.orEmpty(),
                                task = p.task.orEmpty(),
                                why = p.why.orEmpty(),
                                status = p.status,
                                open = stillPending,
                                atMs = e.atMs,
                            ),
                        )
                    }
                    "ask" -> {
                        flushPhoneSteps()
                        flushTools()
                        rows.add(PhoneUseAskRow(keyOf("ua", e, index), e.phone!!.question.orEmpty(), e.atMs))
                    }
                    "started" -> {
                        flushPhoneSteps()
                        flushTools()
                        // A15.10: the session's own model is on its first line.
                        rows.add(
                            PhoneUseStartedRow(
                                keyOf("us", e, index),
                                e.phone!!.task.orEmpty(),
                                e.atMs,
                                modelLabel = e.phone!!.model?.let(::modelLabel),
                            ),
                        )
                    }
                    "ended" -> {
                        flushPhoneSteps()
                        flushTools()
                        rows.add(PhoneUseEndedRow(keyOf("ue", e, index), endedText(e.phone!!), e.phone!!.summary, e.atMs))
                    }
                    // "answer" never becomes an entry; anything else was never parsed.
                    else -> {
                        flushPhoneSteps()
                        flushTools()
                        messageRow(e, index, verbosity, roundByEnd[e.seq], warnModel(roundByEnd[e.seq]))?.let(rows::add)
                    }
                }
                "run" -> {
                    flushPhoneSteps()
                    flushTools()
                    if (e.status == "started") {
                        openRuns++
                        lastStartAt = e.atMs
                    } else if (e.status == "ended") {
                        openRuns = maxOf(0, openRuns - 1)
                    }
                    if (verbosity == ChatVerbosity.High) {
                        rows.add(RunRow(keyOf("r", e, index), e.status == "started", e.reason, e.atMs, e.durationMs))
                    }
                }
                "proposal" -> {
                    flushPhoneSteps()
                    flushTools()
                    rows.add(ProposalRow(keyOf("p", e, index), e.proposalId, e.text))
                }
                "context" -> {
                    flushPhoneSteps()
                    flushTools()
                    rows.add(DividerRow(keyOf("c", e, index), e.text))
                }
                else -> {
                    flushPhoneSteps()
                    flushTools()
                    messageRow(e, index, verbosity, roundByEnd[e.seq], warnModel(roundByEnd[e.seq]))?.let(rows::add)
                }
            }
        }
        flushPhoneSteps()
        flushTools()
        val stale = lastStartAt > 0 && nowMs > 0 && nowMs - lastStartAt > WORKING_STALE_MS
        if (openRuns > 0 && !stale) rows.add(WorkingRow())
        return rows
    }

    private fun messageRow(
        e: ChatEntry,
        index: Int,
        verbosity: ChatVerbosity,
        round: StewardRounds.Round?,
        chosenModel: com.example.phonesteward.net.AnsweringModel?,
    ): ChatRow? {
        val key = keyOf("m", e, index)
        if (e.role != "user") {
            // A15.6/A15.7: only the message that ends a finished round carries the round's text.
            val roundEnd = round != null && round.finished
            val answeredBy = if (roundEnd) round!!.model else null
            return MessageRow(
                key, false, e.text, e.atMs,
                roundEnd = roundEnd,
                roundText = if (roundEnd) round!!.markdown else null,
                // A15.10: the model that answered, and the honest warning when it is not the chosen one.
                roundModelLabel = answeredBy?.let { modelLabel(it) },
                roundModelWarning = if (answeredBy != null && chosenModel != null &&
                    (answeredBy.provider != chosenModel.provider || answeredBy.id != chosenModel.id)
                ) {
                    "Answered by ${modelLabel(answeredBy)}, not ${modelLabel(chosenModel)}"
                } else {
                    null
                },
            )
        }
        val text = e.text
        PHONE_RESULT.matchEntire(text.trim())?.let { m ->
            if (verbosity != ChatVerbosity.High) return null
            val kind = humanKind(m.groupValues[2])
            val ok = m.groupValues[3] == "ok"
            val code = ERROR_CODE.find(m.groupValues[4])?.groupValues?.get(1)
            val line = if (ok) "Phone answered: $kind" else "Phone could not answer: $kind${code?.let { " ($it)" } ?: ""}"
            return PhoneResultRow(key, line, !ok, e.atMs)
        }
        PHONE_EXPIRED.matchEntire(text.trim())?.let { m ->
            if (verbosity != ChatVerbosity.High) return null
            return PhoneResultRow(key, "Phone job expired: ${humanKind(m.groupValues[2])}", true, e.atMs)
        }
        SCHEDULE.find(text.trim())?.let { m ->
            if (verbosity == ChatVerbosity.Low) return null
            val name = m.groupValues[1].replace('_', ' ').replaceFirstChar { it.uppercase() }
            return NoteRow(key, "$name started", e.atMs)
        }
        return MessageRow(key, true, text.removePrefix(VIA_SERVER), e.atMs)
    }
}
