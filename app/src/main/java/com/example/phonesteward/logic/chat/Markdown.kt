package com.example.phonesteward.logic.chat

/**
 * A small Markdown subset for the steward's chat messages (F2, N13): paragraphs,
 * headings, bullet and numbered lists (nested by indentation), fenced code,
 * tables, block quotes, rules, and inline bold, italic, strike, code and links.
 * Pure Kotlin, no dependency. It never throws: whatever it cannot read stays
 * literal text. Chosen over a Markdown library because the steward writes only
 * this subset and a library would add weight and a Compose-version coupling for
 * no visible gain; the Compose side lives in `ui/chat/MarkdownText.kt`.
 */
sealed interface Inline {
    data class Text(val value: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class Code(val value: String) : Inline
    data class Link(val children: List<Inline>, val url: String) : Inline

    /** The text without markup. */
    fun plain(): String = when (this) {
        is Text -> value
        is Code -> value
        is Bold -> children.joinToString("") { it.plain() }
        is Italic -> children.joinToString("") { it.plain() }
        is Strike -> children.joinToString("") { it.plain() }
        is Link -> children.joinToString("") { it.plain() }
    }
}

enum class Align { Start, Center, End }

sealed interface MdBlock {
    data class Paragraph(val inlines: List<Inline>) : MdBlock
    data class Heading(val level: Int, val inlines: List<Inline>) : MdBlock
    data class CodeBlock(val language: String, val text: String) : MdBlock
    data class ListItem(val blocks: List<MdBlock>)
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MdBlock
    data class Table(
        val header: List<List<Inline>>,
        val rows: List<List<List<Inline>>>,
        val aligns: List<Align>,
    ) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data object Rule : MdBlock
}

object Markdown {

    fun parse(text: String): List<MdBlock> = try {
        parseBlocks(text.replace("\r\n", "\n").replace('\r', '\n').split('\n'))
    } catch (e: Exception) {
        listOf(MdBlock.Paragraph(listOf(Inline.Text(text))))
    }

    fun parseInline(text: String): List<Inline> = try {
        InlineParser(text).parse()
    } catch (e: Exception) {
        listOf(Inline.Text(text))
    }

    // ---- blocks ----------------------------------------------------------------

    private val FENCE = Regex("^\\s{0,3}(```+|~~~+)\\s*([\\w+#.-]*).*$")
    private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$")
    private val RULE = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
    private val LIST_ITEM = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    private fun indentOf(line: String): Int {
        var n = 0
        for (c in line) {
            if (c == ' ') n++ else if (c == '\t') n += 4 else break
        }
        return n
    }

    private fun isBlank(line: String) = line.isBlank()

    private fun startsTable(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        val head = lines[i]
        val sep = lines[i + 1]
        return head.contains('|') && sep.contains('-') && TABLE_SEPARATOR.matches(sep) &&
            (sep.contains('|') || head.trim().startsWith("|") || splitRow(head).size > 1)
    }

    private fun startsBlock(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        return FENCE.matches(line) || HEADING.matches(line) || RULE.matches(line) ||
            LIST_ITEM.matches(line) || QUOTE.matches(line) || startsTable(lines, i)
    }

    private fun parseBlocks(lines: List<String>): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (isBlank(line)) {
                i++
                continue
            }
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val language = fence.groupValues[2]
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(marker.take(3))) {
                    body.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // the closing fence
                out.add(MdBlock.CodeBlock(language, body.joinToString("\n")))
                continue
            }
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                out.add(MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2])))
                i++
                continue
            }
            if (RULE.matches(line)) {
                out.add(MdBlock.Rule)
                i++
                continue
            }
            if (startsTable(lines, i)) {
                i = parseTable(lines, i, out)
                continue
            }
            if (QUOTE.matches(line)) {
                val inner = mutableListOf<String>()
                while (i < lines.size) {
                    val q = QUOTE.matchEntire(lines[i]) ?: break
                    inner.add(q.groupValues[1])
                    i++
                }
                out.add(MdBlock.Quote(parseBlocks(inner)))
                continue
            }
            if (LIST_ITEM.matches(line)) {
                i = parseList(lines, i, out)
                continue
            }
            // A paragraph: consecutive lines until a blank line or another block starts.
            val para = mutableListOf(line.trimStart())
            i++
            while (i < lines.size && !isBlank(lines[i]) && !startsBlock(lines, i)) {
                para.add(lines[i].trimStart())
                i++
            }
            out.add(MdBlock.Paragraph(parseInline(joinParagraph(para).trimEnd())))
        }
        return out
    }

    /** Soft breaks become a space; two trailing spaces (or a backslash) make a hard break. */
    private fun joinParagraph(rawLines: List<String>): String = rawLines.joinToString("\n")

    private fun parseList(lines: List<String>, start: Int, out: MutableList<MdBlock>): Int {
        val first = LIST_ITEM.matchEntire(lines[start])!!
        val baseIndent = indentOf(first.groupValues[1])
        val ordered = first.groupValues[2].first().isDigit()
        val startNumber = if (ordered) first.groupValues[2].takeWhile { it.isDigit() }.toIntOrNull() ?: 1 else 1
        val items = mutableListOf<MdBlock.ListItem>()
        var i = start
        while (i < lines.size) {
            val m = LIST_ITEM.matchEntire(lines[i]) ?: break
            val indent = indentOf(m.groupValues[1])
            if (indent != baseIndent) break
            if (m.groupValues[2].first().isDigit() != ordered) break
            val body = mutableListOf(m.groupValues[3])
            i++
            val nested = mutableListOf<String>()
            while (i < lines.size) {
                val next = lines[i]
                if (isBlank(next)) {
                    // A blank line continues the item only when deeper content follows.
                    var j = i
                    while (j < lines.size && isBlank(lines[j])) j++
                    if (j < lines.size && indentOf(lines[j]) > baseIndent) {
                        for (k in i until j) nested.add("")
                        i = j
                        continue
                    }
                    break
                }
                if (indentOf(next) > baseIndent) {
                    nested.add(next)
                    i++
                } else {
                    break
                }
            }
            val minIndent = nested.filter { it.isNotBlank() }.minOfOrNull { indentOf(it) } ?: 0
            val dedented = nested.map { if (it.isBlank()) "" else dropIndent(it, minIndent) }
            val blocks = parseBlocks(body + dedented)
            items.add(MdBlock.ListItem(blocks))
            // A blank line between items keeps the same list.
            var j = i
            while (j < lines.size && isBlank(lines[j])) j++
            if (j > i && j < lines.size) {
                val peek = LIST_ITEM.matchEntire(lines[j])
                if (peek != null && indentOf(peek.groupValues[1]) == baseIndent &&
                    peek.groupValues[2].first().isDigit() == ordered
                ) {
                    i = j
                }
            }
        }
        out.add(MdBlock.ListBlock(ordered, startNumber, items))
        return i
    }

    private fun dropIndent(line: String, columns: Int): String {
        var dropped = 0
        var idx = 0
        while (idx < line.length && dropped < columns) {
            when (line[idx]) {
                ' ' -> dropped++
                '\t' -> dropped += 4
                else -> break
            }
            idx++
        }
        return line.substring(idx)
    }

    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inCode = false
        var k = 0
        while (k < s.length) {
            val c = s[k]
            when {
                c == '\\' && k + 1 < s.length && s[k + 1] == '|' -> {
                    current.append('|')
                    k++
                }
                c == '`' -> {
                    inCode = !inCode
                    current.append(c)
                }
                c == '|' && !inCode -> {
                    cells.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(c)
            }
            k++
        }
        cells.add(current.toString().trim())
        return cells
    }

    private fun parseTable(lines: List<String>, start: Int, out: MutableList<MdBlock>): Int {
        val header = splitRow(lines[start])
        val aligns = splitRow(lines[start + 1]).map { cell ->
            val left = cell.startsWith(":")
            val right = cell.endsWith(":")
            when {
                left && right -> Align.Center
                right -> Align.End
                else -> Align.Start
            }
        }
        val width = header.size
        val rows = mutableListOf<List<List<Inline>>>()
        var i = start + 2
        while (i < lines.size && !isBlank(lines[i]) && (lines[i].contains('|') || !startsBlock(lines, i))) {
            val cells = splitRow(lines[i]).toMutableList()
            while (cells.size < width) cells.add("")
            rows.add(cells.take(width).map { parseInline(it) })
            i++
        }
        out.add(
            MdBlock.Table(
                header = header.map { parseInline(it) },
                rows = rows,
                aligns = List(width) { aligns.getOrElse(it) { Align.Start } },
            ),
        )
        return i
    }

    // ---- inlines ---------------------------------------------------------------

    private class InlineParser(private val src: String) {

        fun parse(): List<Inline> = parseRange(0, src.length)

        private fun isWordChar(c: Char) = c.isLetterOrDigit()

        private fun parseRange(from: Int, to: Int): List<Inline> {
            val out = mutableListOf<Inline>()
            val buf = StringBuilder()
            fun flush() {
                if (buf.isNotEmpty()) {
                    out.add(Inline.Text(buf.toString()))
                    buf.clear()
                }
            }
            var i = from
            while (i < to) {
                val c = src[i]
                when {
                    c == '\\' && i + 1 < to && src[i + 1] in "\\`*_{}[]()#+-.!|~>" -> {
                        buf.append(src[i + 1])
                        i += 2
                    }
                    c == '\n' -> {
                        // A hard break (two spaces or a backslash before the newline) keeps the line break.
                        val hard = buf.endsWith("  ") || (buf.endsWith("\\"))
                        if (hard) {
                            while (buf.isNotEmpty() && buf.last() == ' ') buf.setLength(buf.length - 1)
                            if (buf.endsWith("\\")) buf.setLength(buf.length - 1)
                            buf.append('\n')
                        } else {
                            while (buf.isNotEmpty() && buf.last() == ' ') buf.setLength(buf.length - 1)
                            buf.append(' ')
                        }
                        i++
                    }
                    c == '`' -> {
                        var run = 0
                        while (i + run < to && src[i + run] == '`') run++
                        val close = findBacktickRun(i + run, to, run)
                        if (close < 0) {
                            buf.append(src, i, i + run)
                            i += run
                        } else {
                            flush()
                            var content = src.substring(i + run, close).replace('\n', ' ')
                            if (content.length > 2 && content.startsWith(" ") && content.endsWith(" ") && content.isNotBlank()) {
                                content = content.substring(1, content.length - 1)
                            }
                            out.add(Inline.Code(content))
                            i = close + run
                        }
                    }
                    c == '[' -> {
                        val link = tryLink(i, to)
                        if (link == null) {
                            buf.append(c)
                            i++
                        } else {
                            flush()
                            out.add(Inline.Link(parseRange(i + 1, link.textEnd), link.url))
                            i = link.end
                        }
                    }
                    c == '~' && i + 1 < to && src[i + 1] == '~' -> {
                        val close = findClose("~~", i + 2, to)
                        if (close < 0 || close == i + 2) {
                            buf.append("~~")
                            i += 2
                        } else {
                            flush()
                            out.add(Inline.Strike(parseRange(i + 2, close)))
                            i = close + 2
                        }
                    }
                    c == '*' || c == '_' -> {
                        val handled = tryEmphasis(c, i, to, out, ::flush)
                        if (handled < 0) {
                            buf.append(c)
                            i++
                        } else {
                            i = handled
                        }
                    }
                    else -> {
                        buf.append(c)
                        i++
                    }
                }
            }
            flush()
            return out
        }

        private fun findBacktickRun(from: Int, to: Int, run: Int): Int {
            var i = from
            while (i < to) {
                if (src[i] == '`') {
                    var n = 0
                    while (i + n < to && src[i + n] == '`') n++
                    if (n == run) return i
                    i += n
                } else {
                    i++
                }
            }
            return -1
        }

        private class LinkMatch(val textEnd: Int, val url: String, val end: Int)

        private fun tryLink(open: Int, to: Int): LinkMatch? {
            var depth = 0
            var i = open
            while (i < to) {
                when (src[i]) {
                    '\\' -> i++
                    '`' -> {
                        val close = findBacktickRun(i + 1, to, 1)
                        if (close > 0) i = close
                    }
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            if (i + 1 < to && src[i + 1] == '(') {
                                val close = src.indexOf(')', i + 2)
                                if (close in (i + 2) until to) {
                                    val url = src.substring(i + 2, close).trim().substringBefore(' ')
                                    if (url.isNotEmpty() && i > open + 1) return LinkMatch(i, url, close + 1)
                                }
                            }
                            return null
                        }
                    }
                }
                i++
            }
            return null
        }

        /** The index of the next [marker] at or after [from] that is not preceded by a backslash. */
        private fun findClose(marker: String, from: Int, to: Int): Int {
            var i = from
            while (i + marker.length <= to) {
                if (src[i] == '\\') {
                    i += 2
                    continue
                }
                if (src[i] == '`') {
                    val close = findBacktickRun(i + 1, to, 1)
                    if (close > 0) {
                        i = close + 1
                        continue
                    }
                }
                if (src.startsWith(marker, i) && i > from && !src[i - 1].isWhitespace()) return i
                i++
            }
            return -1
        }

        /**
         * Handles `*`/`_` runs. Returns the index after the construct, or -1 to treat the
         * character as literal. Underscores only open or close at word edges.
         */
        private fun tryEmphasis(
            c: Char,
            i: Int,
            to: Int,
            out: MutableList<Inline>,
            flush: () -> Unit,
        ): Int {
            var run = 0
            while (i + run < to && src[i + run] == c) run++
            val before = if (i > 0) src[i - 1] else ' '
            if (c == '_' && isWordChar(before)) return -1
            val take = minOf(run, 3)
            val afterIdx = i + take
            if (afterIdx >= to || src[afterIdx].isWhitespace()) return -1
            val marker = c.toString().repeat(take)
            var close = findEmphasisClose(c, marker, afterIdx, to)
            var used = take
            if (close < 0 && take == 3) {
                // `***a**` style: fall back to bold.
                used = 2
                close = findEmphasisClose(c, c.toString().repeat(2), i + 2, to)
            }
            if (close < 0 && take >= 2) {
                used = 1
                close = findEmphasisClose(c, c.toString(), i + 1, to)
            }
            if (close < 0) return -1
            flush()
            val inner = parseRange(i + used, close)
            out.add(
                when (used) {
                    3 -> Inline.Bold(listOf(Inline.Italic(inner)))
                    2 -> Inline.Bold(inner)
                    else -> Inline.Italic(inner)
                },
            )
            return close + used
        }

        private fun findEmphasisClose(c: Char, marker: String, from: Int, to: Int): Int {
            var i = from
            while (i + marker.length <= to) {
                val ch = src[i]
                if (ch == '\\') {
                    i += 2
                    continue
                }
                if (ch == '`') {
                    val close = findBacktickRun(i + 1, to, 1)
                    if (close > 0) {
                        i = close + 1
                        continue
                    }
                }
                if (src.startsWith(marker, i) && i > from && !src[i - 1].isWhitespace()) {
                    // The closer must not be the start of a longer run when we want exactly this length,
                    // except for a nested italic closing inside bold ("**a *b***").
                    val afterIdx = i + marker.length
                    val after = if (afterIdx < to) src[afterIdx] else ' '
                    val longerRun = after == c && marker.length < 3
                    if (c == '_' && isWordChar(after)) {
                        i++
                        continue
                    }
                    if (!longerRun || marker.length == 2) return closeOfRun(c, marker, i, to)
                }
                i++
            }
            return -1
        }

        /** For "**a *b***": the bold closer is the LAST two stars of a three-star run. */
        private fun closeOfRun(c: Char, marker: String, at: Int, to: Int): Int {
            var run = 0
            while (at + run < to && src[at + run] == c) run++
            return if (marker.length == 2 && run == 3) at + 1 else at
        }
    }
}
