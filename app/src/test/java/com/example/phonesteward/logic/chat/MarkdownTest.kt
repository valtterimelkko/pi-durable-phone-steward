package com.example.phonesteward.logic.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.logic.chat.Inline.Bold
import com.example.phonesteward.logic.chat.Inline.Code
import com.example.phonesteward.logic.chat.Inline.Italic
import com.example.phonesteward.logic.chat.Inline.Link
import com.example.phonesteward.logic.chat.Inline.Strike
import com.example.phonesteward.logic.chat.Inline.Text

/** F2 / N13: the Markdown subset the steward writes, parsed to blocks and inlines (pure JVM). */
class MarkdownTest {

    private fun inline(text: String) = Markdown.parseInline(text)

    // ---- inlines ---------------------------------------------------------------

    @Test fun plainText() = assertEquals(listOf<Inline>(Text("hello world")), inline("hello world"))

    @Test fun boldWithStars() =
        assertEquals(listOf(Text("a "), Bold(listOf(Text("b"))), Text(" c")), inline("a **b** c"))

    @Test fun boldWithUnderscores() =
        assertEquals(listOf(Bold(listOf(Text("b")))), inline("__b__"))

    @Test fun italicWithStarAndUnderscore() {
        assertEquals(listOf(Italic(listOf(Text("x")))), inline("*x*"))
        assertEquals(listOf(Text("a "), Italic(listOf(Text("x"))), Text(" b")), inline("a _x_ b"))
    }

    @Test fun underscoresInsideWordsStayLiteral() =
        assertEquals(listOf<Inline>(Text("use snake_case_names here")), inline("use snake_case_names here"))

    @Test fun boldItalicWithThreeStars() =
        assertEquals(listOf(Bold(listOf(Italic(listOf(Text("x")))))), inline("***x***"))

    @Test fun italicInsideBold() =
        assertEquals(listOf(Bold(listOf(Text("a "), Italic(listOf(Text("b")))))), inline("**a *b***"))

    @Test fun strikethrough() = assertEquals(listOf(Strike(listOf(Text("old")))), inline("~~old~~"))

    @Test fun inlineCodeKeepsMarkupLiteral() =
        assertEquals(listOf(Text("id "), Code("pr-**1**"), Text(".")), inline("id `pr-**1**`."))

    @Test fun doubleBacktickCodeMayContainABacktick() =
        assertEquals(listOf<Inline>(Code("a`b")), inline("``a`b``"))

    @Test fun link() =
        assertEquals(
            listOf(Text("see "), Link(listOf(Text("docs")), "https://example.test/x"), Text("!")),
            inline("see [docs](https://example.test/x)!"),
        )

    @Test fun unclosedDelimitersAreLiteral() {
        assertEquals("a **b", inline("a **b").joinToString("") { it.plain() })
        assertEquals("2 * 3 = 6", inline("2 * 3 = 6").joinToString("") { it.plain() })
        assertEquals("a `b", inline("a `b").joinToString("") { it.plain() })
    }

    @Test fun backslashEscapes() = assertEquals(listOf<Inline>(Text("*not italic*")), inline("\\*not italic\\*"))

    @Test fun hardBreakKeepsNewlineSoftBreakBecomesSpace() {
        assertEquals("a b", Markdown.parseInline("a\nb").joinToString("") { it.plain() })
        assertEquals("a\nb", Markdown.parseInline("a  \nb").joinToString("") { it.plain() })
    }

    // ---- blocks ----------------------------------------------------------------

    @Test fun paragraphsSplitOnBlankLines() {
        val blocks = Markdown.parse("one\ntwo\n\nthree")
        assertEquals(2, blocks.size)
        assertEquals("one two", (blocks[0] as MdBlock.Paragraph).inlines.joinToString("") { it.plain() })
    }

    @Test fun headings() {
        val blocks = Markdown.parse("# Big\n## Medium ##\n###### Tiny")
        assertEquals(listOf(1, 2, 6), blocks.map { (it as MdBlock.Heading).level })
        assertEquals("Medium", (blocks[1] as MdBlock.Heading).inlines.joinToString("") { it.plain() })
    }

    @Test fun hashWithoutSpaceIsNotAHeading() {
        assertTrue(Markdown.parse("#hashtag").single() is MdBlock.Paragraph)
    }

    @Test fun fencedCodeBlockKeepsContentAndLanguage() {
        val code = Markdown.parse("before\n```kotlin\nval a = **1**\n\n  indented\n```\nafter")
        assertEquals(3, code.size)
        val block = code[1] as MdBlock.CodeBlock
        assertEquals("kotlin", block.language)
        assertEquals("val a = **1**\n\n  indented", block.text)
    }

    @Test fun unterminatedFenceTakesTheRest() {
        val block = Markdown.parse("```\nstill typing").single() as MdBlock.CodeBlock
        assertEquals("still typing", block.text)
    }

    @Test fun bulletList() {
        val list = Markdown.parse("- one\n- **two**\n* three").single() as MdBlock.ListBlock
        assertEquals(false, list.ordered)
        assertEquals(3, list.items.size)
        val second = (list.items[1].blocks.single() as MdBlock.Paragraph).inlines
        assertEquals(listOf<Inline>(Bold(listOf(Text("two")))), second)
    }

    @Test fun orderedListKeepsItsStartNumber() {
        val list = Markdown.parse("3. c\n4. d").single() as MdBlock.ListBlock
        assertEquals(true, list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test fun nestedListsByIndentation() {
        val list = Markdown.parse("- a\n  - a1\n  - a2\n- b").single() as MdBlock.ListBlock
        assertEquals(2, list.items.size)
        val nested = list.items[0].blocks[1] as MdBlock.ListBlock
        assertEquals(2, nested.items.size)
    }

    @Test fun blankLineBetweenItemsKeepsOneList() {
        val blocks = Markdown.parse("- a\n\n- b")
        assertEquals(1, blocks.size)
        assertEquals(2, (blocks[0] as MdBlock.ListBlock).items.size)
    }

    @Test fun tableWithAlignment() {
        val table = Markdown.parse(
            "| App | Cache |\n|:----|------:|\n| Chrome | 612 MB |\n| YouTube | 480 MB |",
        ).single() as MdBlock.Table
        assertEquals(listOf("App", "Cache"), table.header.map { cell -> cell.joinToString("") { it.plain() } })
        assertEquals(2, table.rows.size)
        assertEquals(listOf(Align.Start, Align.End), table.aligns)
        assertEquals("480 MB", table.rows[1][1].joinToString("") { it.plain() })
    }

    @Test fun tableWithoutOuterPipesAndRaggedRows() {
        val table = Markdown.parse("a | b\n--|--\n1 | 2\n3").single() as MdBlock.Table
        assertEquals(2, table.header.size)
        assertEquals(2, table.rows[1].size) // padded to the header width
    }

    @Test fun inlineMarkupInsideTableCells() {
        val table = Markdown.parse("| x |\n|---|\n| **b** and `c` |").single() as MdBlock.Table
        val cell = table.rows[0][0]
        assertTrue(cell.any { it is Bold })
        assertTrue(cell.any { it is Code })
    }

    @Test fun aPipeLineWithoutSeparatorIsAParagraph() {
        assertTrue(Markdown.parse("a | b").single() is MdBlock.Paragraph)
    }

    @Test fun blockQuote() {
        val quote = Markdown.parse("> quoted **text**\n> more").single() as MdBlock.Quote
        assertTrue(quote.blocks.single() is MdBlock.Paragraph)
    }

    @Test fun horizontalRule() {
        val blocks = Markdown.parse("a\n\n---\n\nb")
        assertEquals(listOf("Paragraph", "Rule", "Paragraph"), blocks.map { it::class.simpleName })
    }

    @Test fun emptyAndBlankInput() {
        assertEquals(emptyList<MdBlock>(), Markdown.parse(""))
        assertEquals(emptyList<MdBlock>(), Markdown.parse("  \n\n "))
    }

    @Test fun windowsLineEndings() {
        assertEquals(2, Markdown.parse("a\r\n\r\nb").size)
    }

    @Test fun theStewardsRealShape() {
        val text = """
            **Weekly storage check — 7 Oct**

            **What changed**
            - Free space is steady at **38.7 GB**
            - `pr-cache` is waiting

            | App | Cache |
            |---|---|
            | Chrome | 612 MB |

            1. Clear temporary files
            2. Remove two old apps
        """.trimIndent()
        val names = Markdown.parse(text).map { it::class.simpleName }
        assertEquals(listOf("Paragraph", "Paragraph", "ListBlock", "Table", "ListBlock"), names)
    }

    @Test fun garbageNeverThrows() {
        val nasty = listOf("**", "`", "```", "[](", "[a](", "| |", "|-|", "> ", "- ", "1.", "~~", "***", "____", "\\", "#", "|\n|-")
        nasty.forEach { Markdown.parse(it) }
        Markdown.parse("*".repeat(500) + "_".repeat(500) + "`".repeat(300))
    }
}
