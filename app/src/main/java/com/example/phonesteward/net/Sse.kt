package com.example.phonesteward.net

/**
 * Pure line-based Server-Sent Events parser for the chat stream (§6).
 * Feed it the raw text lines; it emits one callback per complete event.
 * Handles `id:`, `event:`, `data:` (multi-line, joined with \n), comment
 * lines starting with `:`, and CRLF endings.
 */
class SseParser(private val onEvent: (id: Long?, type: String?, data: String) -> Unit) {

    private var idLine: String? = null
    private var eventLine: String? = null
    private val dataLines = mutableListOf<String>()

    fun feedLine(rawLine: String) {
        val line = rawLine.trimEnd('\r')
        when {
            line.isEmpty() -> dispatch()
            line.startsWith(":") -> Unit // comment / keep-alive
            else -> {
                val colon = line.indexOf(':')
                val field = if (colon < 0) line else line.substring(0, colon)
                var value = if (colon < 0) "" else line.substring(colon + 1)
                if (value.startsWith(" ")) value = value.substring(1)
                when (field) {
                    "id" -> idLine = value
                    "event" -> eventLine = value
                    "data" -> dataLines += value
                    // `retry:` and unknown fields are ignored per the SSE spec.
                }
            }
        }
    }

    fun feedAll(text: String) {
        text.lineSequence().forEach { feedLine(it) }
    }

    private fun dispatch() {
        if (dataLines.isEmpty()) {
            // An event with no data lines carries nothing; reset and skip.
            reset()
            return
        }
        val id = idLine?.trim()?.toLongOrNull()
        onEvent(id, eventLine, dataLines.joinToString("\n"))
        reset()
    }

    private fun reset() {
        idLine = null
        eventLine = null
        dataLines.clear()
    }
}
