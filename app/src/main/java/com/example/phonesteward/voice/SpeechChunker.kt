package com.example.phonesteward.voice

/**
 * A15.6: split speech into sentence-sized chunks, ported from Pi Web UI's
 * `client/src/lib/speechArbiter.ts` `chunkIntoSentences`. Splits after a run of `.` `!` `?` `…`
 * (plus closing quotes) when whitespace or the end follows — so "3.5" never splits — and
 * hard-splits any piece longer than [maxLen] at whitespace.
 */
object SpeechChunker {

    private val TERMINATORS = setOf('.', '!', '?', '…')
    private val CLOSERS = setOf('"', '\'', ')', ']', '”', '’')

    fun chunkIntoSentences(text: String, maxLen: Int = 280): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val pieces = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < trimmed.length) {
            if (trimmed[i] !in TERMINATORS) { i++; continue }
            var end = i + 1
            while (end < trimmed.length && trimmed[end] in TERMINATORS) end++
            while (end < trimmed.length && trimmed[end] in CLOSERS) end++
            if (end >= trimmed.length || trimmed[end].isWhitespace()) {
                val piece = trimmed.substring(start, end).trim()
                if (piece.isNotEmpty()) pieces.add(piece)
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start < trimmed.length) {
            val tail = trimmed.substring(start).trim()
            if (tail.isNotEmpty()) pieces.add(tail)
        }

        val out = mutableListOf<String>()
        for (piece in pieces) {
            if (piece.length <= maxLen) { out.add(piece); continue }
            var rest = piece
            while (rest.length > maxLen) {
                var cut = rest.lastIndexOf(' ', maxLen)
                if (cut < maxLen / 2) cut = maxLen
                out.add(rest.substring(0, cut).trim())
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out.add(rest)
        }
        return out
    }
}
