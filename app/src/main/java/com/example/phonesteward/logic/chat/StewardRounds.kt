package com.example.phonesteward.logic.chat

import com.example.phonesteward.ui.AppViewModel.ChatEntry

/**
 * A15.6/A15.7: one definition of a "finished steward round" — all the steward's text for one
 * owner message, as markdown, with tool lines, phone-use step lines and thinking left out.
 * The steward's session speech (`say`) is steward text too. A round ends where the owner
 * speaks again or a fresh start divides the conversation; its last message carries the round's
 * text, and the round is finished when the steward's turn is over (no run still open, save
 * one older than [ChatRows.WORKING_STALE_MS], which Chat draws no Working line for either).
 * Pure: the same entries [ChatRows.build] draws its rows from.
 */
object StewardRounds {

    /** One round: its text as markdown, the entry seq of the message that ends it, and the model that answered. */
    data class Round(
        val endSeq: Long,
        val markdown: String,
        val finished: Boolean,
        /** A15.10: the model named by the round's last message (null when it names none). */
        val model: com.example.phonesteward.net.AnsweringModel? = null,
    )

    fun build(entries: List<ChatEntry>, nowMs: Long = 0): List<Round> {
        val rounds = mutableListOf<Round>()
        var texts = mutableListOf<String>()
        var endSeq = 0L
        var model: com.example.phonesteward.net.AnsweringModel? = null
        var openRuns = 0
        var lastStartAt = 0L

        fun flush(cut: Boolean) {
            if (texts.isNotEmpty()) {
                val stale = lastStartAt > 0 && nowMs > 0 && nowMs - lastStartAt > ChatRows.WORKING_STALE_MS
                rounds.add(Round(endSeq, texts.joinToString("\n\n"), finished = cut || openRuns == 0 || stale, model = model))
                texts = mutableListOf()
                model = null
            }
        }

        entries.forEach { e ->
            when {
                e.kind == "message" && e.role == "assistant" -> {
                    texts.add(e.text)
                    endSeq = e.seq
                    // A15.10: the round's model is its last message's.
                    e.answering?.let { model = it }
                }
                // The owner speaking again, or a fresh start, closes the round for good.
                (e.kind == "message" && e.role == "user") || e.kind == "context" -> flush(cut = true)
                // A run still open means the steward's turn may not be over.
                e.kind == "run" && e.status == "started" -> {
                    openRuns++
                    lastStartAt = e.atMs
                }
                e.kind == "run" && e.status == "ended" -> openRuns = maxOf(0, openRuns - 1)
                // Tools, proposals and phone use are neither steward text nor a cut.
                else -> Unit
            }
        }
        flush(cut = false)
        return rounds
    }

    /** The rounds by the seq of the message that ends them, for the row views. */
    fun byEndSeq(rounds: List<Round>): Map<Long, Round> = rounds.associateBy { it.endSeq }
}
