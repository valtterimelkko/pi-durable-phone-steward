package com.example.phonesteward.voice

import com.example.phonesteward.logic.chat.ChatRow
import com.example.phonesteward.logic.chat.MessageRow
import com.example.phonesteward.logic.chat.WorkingRow

/**
 * A15.6: "a round's text" — all the steward's text for one owner message, with tool lines,
 * run lines and thinking left out — reduced to speech. This is a pure function over the Chat
 * rows; the per-round action row is defined beside them.
 */
data class SpeechRound(val key: String, val text: String, val finished: Boolean = true)

object RoundText {

    private val FENCED_CODE = Regex("(?s)```.*?```")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val LINK = Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE)
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")
    private val ITALIC = Regex("(?<![*\\w])\\*([^*]+)\\*(?![*\\w])")
    private val UNDERSCORE = Regex("__([^_]+)__")
    private val BLOCKQUOTE = Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE)
    private val BULLET = Regex("^\\s{0,3}[-*+]\\s+", RegexOption.MULTILINE)
    private val NUMBERED = Regex("^\\s{0,3}\\d+\\.\\s+", RegexOption.MULTILINE)

    /** Markdown reduced to something that reads well aloud; code blocks become "code block". */
    fun speechText(markdown: String): String {
        var text = markdown
        text = FENCED_CODE.replace(text, "code block")
        text = INLINE_CODE.replace(text, "$1")
        text = LINK.replace(text, "$1")
        text = HEADING.replace(text, "")
        text = BOLD.replace(text, "$1")
        text = ITALIC.replace(text, "$1")
        text = UNDERSCORE.replace(text, "$1")
        text = BLOCKQUOTE.replace(text, "")
        text = BULLET.replace(text, "")
        text = NUMBERED.replace(text, "")
        text = text.replace("|", " ")
        return text.trim()
    }

    /**
     * One [SpeechRound] per owner message, keyed by its last steward [MessageRow] (the row the
     * Read aloud button sits under). A round with a [WorkingRow] after it is still unfinished.
     */
    fun rounds(rows: List<ChatRow>): List<SpeechRound> {
        val out = mutableListOf<SpeechRound>()
        var currentText = mutableListOf<String>()
        var currentKey: String? = null
        var workingAfterLast = false

        fun flush() {
            if (currentKey != null && currentText.isNotEmpty()) out.add(SpeechRound(currentKey!!, currentText.joinToString("\n\n")))
            currentText = mutableListOf()
            currentKey = null
        }

        for (row in rows) {
            when {
                row is MessageRow && row.fromOwner -> { flush(); workingAfterLast = false }
                row is MessageRow -> { currentText.add(speechText(row.text)); currentKey = row.key; workingAfterLast = false }
                row is WorkingRow -> workingAfterLast = true
            }
        }
        flush()
        if (workingAfterLast && out.isNotEmpty()) out[out.lastIndex] = out.last().copy(finished = false)
        return out
    }
}
