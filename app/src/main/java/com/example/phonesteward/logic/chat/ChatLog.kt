package com.example.phonesteward.logic.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import com.example.phonesteward.net.ChatEvent
import com.example.phonesteward.ui.AppViewModel.ChatEntry
import com.example.phonesteward.ui.AppViewModel.PhoneUseEntry

/**
 * Folds chat events (live stream and history, protocol §6) into one ordered list of
 * entries: de-duplicated by the server's event id, sorted by it, a tool call's
 * started and done events merged into one entry, and the owner's own just-sent
 * message replaced by the server's echo of it. Pure and total: junk is ignored.
 */
object ChatLog {

    const val MAX_ENTRIES = 400

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /** The entry one event stands for; null for a heartbeat, an unknown type or an unreadable body. */
    fun entryFor(event: ChatEvent): ChatEntry? {
        val d = event.data
        return when (event.type) {
            "message" -> {
                val role = d.str("role") ?: return null
                val text = d.str("text") ?: return null
                // The server writes an empty assistant message for a turn that only called tools.
                if (text.isBlank()) return null
                ChatEntry(
                    role, text, d.long("atMs") ?: 0, "message",
                    seq = event.id, lastSeq = event.id,
                    // A15.10: the model that produced it, when the event names one.
                    answering = answeringModel(d),
                )
            }
            "tool" -> ChatEntry(
                role = "system",
                text = "",
                atMs = d.long("atMs") ?: 0,
                kind = "tool",
                seq = event.id,
                lastSeq = event.id,
                name = d.str("name") ?: "tool",
                status = d.str("status") ?: "started",
                callId = d.str("callId"),
                argsSummary = d.str("argsSummary"),
                resultSummary = d.str("resultSummary"),
                durationMs = d.long("durationMs"),
            )
            "run" -> ChatEntry(
                role = "system",
                text = "",
                atMs = d.long("atMs") ?: 0,
                kind = "run",
                seq = event.id,
                lastSeq = event.id,
                status = d.str("status") ?: return null,
                reason = d.str("reason"),
                durationMs = d.long("durationMs"),
            )
            "proposal" -> ChatEntry(
                role = "system",
                text = d.str("title") ?: "",
                atMs = d.long("atMs") ?: 0,
                kind = "proposal",
                seq = event.id,
                lastSeq = event.id,
                proposalId = d.str("proposalId"),
            )
            "context" -> {
                // A10.9: a fresh start draws a divider; any other kind of context event is not drawn.
                if ((d.str("kind") ?: "fresh_start") != "fresh_start") return null
                ChatEntry(
                    role = "system",
                    text = d.str("text")?.takeIf { it.isNotBlank() } ?: FRESH_START_TEXT,
                    atMs = d.long("atMs") ?: 0,
                    kind = "context",
                    seq = event.id,
                    lastSeq = event.id,
                )
            }
            "phone_use" -> phoneEntry(event) ?: return null
            else -> null
        }
    }

    /** A15.10: the additive `model` object of an event, when it carries one; anything odd reads as absent. */
    private fun answeringModel(d: JsonObject): com.example.phonesteward.net.AnsweringModel? {
        val obj = d["model"] as? JsonObject ?: return null
        val provider = obj.str("provider") ?: return null
        val id = obj.str("id") ?: return null
        return com.example.phonesteward.net.AnsweringModel(provider, id, obj.str("label").orEmpty())
    }

    /** A13.6: one `phone_use` event as an entry; `say` is an ordinary steward message, an `answer` folds into its card. */
    private fun phoneEntry(event: ChatEvent): ChatEntry? {
        val d = event.data
        val p = PhoneUseEntry(
            kind = d.str("kind") ?: return null,
            requestId = d.str("requestId"),
            sessionId = d.str("sessionId"),
            askId = d.str("askId"),
            task = d.str("task"),
            why = d.str("why"),
            question = d.str("question"),
            summary = d.str("summary"),
            tool = d.str("tool"),
            outcome = d.str("outcome"),
            allowed = when (d.str("allowed")) {
                "true" -> true
                "false" -> false
                else -> null
            },
            reason = d.str("reason"),
            steps = d.str("steps")?.toIntOrNull(),
            model = answeringModel(d),
        )
        if (p.kind !in setOf("request", "answer", "started", "step", "say", "ask", "ended")) return null
        return when (p.kind) {
            "request" -> ChatEntry(
                role = "system", text = "", atMs = d.long("atMs") ?: 0, kind = "phone_use",
                seq = event.id, lastSeq = event.id, phone = p,
            )
            "started" -> ChatEntry(
                role = "system", text = "", atMs = d.long("atMs") ?: 0, kind = "phone_use",
                seq = event.id, lastSeq = event.id, phone = p,
            )
            "step", "ask", "ended", "answer" -> ChatEntry(
                role = "system", text = "", atMs = d.long("atMs") ?: 0, kind = "phone_use",
                seq = event.id, lastSeq = event.id, phone = p,
            )
            // The session model's own text is steward speech.
            "say" -> ChatEntry(
                role = "assistant", text = d.str("text") ?: "", atMs = d.long("atMs") ?: 0, kind = "message",
                seq = event.id, lastSeq = event.id,
            )
            else -> null
        }
    }

    /** The fold of an `answer` into its request card: the card keeps its place and shows the outcome. */
    private fun answered(list: List<ChatEntry>, answer: ChatEntry): List<ChatEntry>? {
        val id = answer.phone?.requestId ?: return null
        val idx = list.indexOfFirst { it.kind == "phone_use" && it.phone?.kind == "request" && it.phone?.requestId == id }
        if (idx < 0) return null
        val old = list[idx]
        val updated = old.copy(
            phone = old.phone!!.copy(
                status = if (answer.phone.allowed == true) "allowed" else "declined",
                sessionId = answer.phone.sessionId,
                allowed = answer.phone.allowed,
            ),
        )
        return list.subList(0, idx) + listOf(updated) + list.subList(idx + 1, list.size)
    }

    /** The divider words of A10.9, used when the server's event carries none. */
    const val FRESH_START_TEXT = "Fresh start: the steward now works from its records, not this conversation."

    fun applyAll(list: List<ChatEntry>, events: List<ChatEvent>): List<ChatEntry> =
        events.fold(list) { acc, event -> apply(acc, event) }

    fun apply(list: List<ChatEntry>, event: ChatEvent): List<ChatEntry> {
        val entry = entryFor(event) ?: return list
        if (list.any { it.seq == entry.seq || it.lastSeq == entry.seq }) return list
        // A13.6: an answer updates its request card instead of drawing a row of its own.
        // `answered` returns the whole list (locals included) — re-appending the locals here
        // drew every not-yet-echoed owner message twice (correction 1, finding 2).
        if (entry.kind == "phone_use" && entry.phone?.kind == "answer") {
            return answered(list, entry) ?: list
        }
        val merged = if (entry.kind == "tool") mergeTool(list, entry) else null
        if (merged != null) return merged
        val local = list.filter { it.seq == 0L }.toMutableList()
        if (entry.kind == "message" && entry.role == "user") {
            // The echo of a message typed here: drop its local twin (one for one).
            val twin = local.indexOfFirst { it.kind == "message" && it.role == "user" && it.text == entry.text }
            if (twin >= 0) local.removeAt(twin)
        }
        val server = (list.filter { it.seq != 0L } + entry).sortedBy { it.seq }
        return (server + local).takeLast(MAX_ENTRIES)
    }

    /** A `done`/`error` event closes its call; a replayed `started` for a known call changes nothing. */
    private fun mergeTool(list: List<ChatEntry>, event: ChatEntry): List<ChatEntry>? {
        val idx = if (event.callId != null) {
            list.indexOfFirst { it.kind == "tool" && it.callId == event.callId }
        } else if (event.status != "started" && event.status != "queued") {
            list.indexOfFirst { it.kind == "tool" && it.callId == null && it.name == event.name && (it.status == "started" || it.status == "queued") }
        } else {
            -1
        }
        if (idx < 0) return null
        val old = list[idx]
        val reopened = (event.status == "started" || event.status == "queued") && (old.status == "done" || old.status == "error")
        val updated = old.copy(
            status = if (reopened) old.status else event.status,
            argsSummary = event.argsSummary ?: old.argsSummary,
            resultSummary = event.resultSummary ?: old.resultSummary,
            durationMs = event.durationMs ?: old.durationMs,
            lastSeq = maxOf(old.lastSeq, event.seq),
        )
        return list.toMutableList().also { it[idx] = updated }
    }

    /** The owner's message, shown at once; the server's echo replaces it. */
    fun withLocalOwner(list: List<ChatEntry>, text: String, atMs: Long): List<ChatEntry> =
        (list + ChatEntry("user", text, atMs)).takeLast(MAX_ENTRIES)
}
