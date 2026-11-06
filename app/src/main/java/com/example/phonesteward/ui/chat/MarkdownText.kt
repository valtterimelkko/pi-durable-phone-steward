package com.example.phonesteward.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.phonesteward.logic.chat.Align
import com.example.phonesteward.logic.chat.Inline
import com.example.phonesteward.logic.chat.Markdown
import com.example.phonesteward.logic.chat.MdBlock

/** The few colours the Pi Web UI gives Markdown that the Material scheme has no slot for. */
internal data class MarkdownColors(
    val codeChipBackground: Color,
    val codeChipText: Color,
    val blockBackground: Color,
    val border: Color,
    val headerBackground: Color,
    val stripe: Color,
)

@Composable
internal fun markdownColors(): MarkdownColors {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    // Claude Code's terracotta entity chips, as in the Pi Web UI stylesheet.
    return if (light) {
        MarkdownColors(
            codeChipBackground = Color(0xFFFCEDEA),
            codeChipText = Color(0xFFB93829),
            blockBackground = Color(0xFFFAFAF9),
            border = Color(0xFFE2E2DF),
            headerBackground = Color(0xFFF9FAFB),
            stripe = Color(0xFFFAFAF9),
        )
    } else {
        MarkdownColors(
            codeChipBackground = Color(0xFF3A2224),
            codeChipText = Color(0xFFF28B82),
            blockBackground = Color(0xFF111827),
            border = Color(0xFF334155),
            headerBackground = Color(0xFF1E293B),
            stripe = Color(0xFF1B2332),
        )
    }
}

/** A Markdown message body: paragraphs, headings, lists, code, tables, quotes. A15.7: every code and text block carries a copy button. */
@Composable
fun MarkdownContent(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    val colors = markdownColors()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { MarkdownBlockView(it, colors, MaterialTheme.typography.bodyMedium) }
    }
}

/** A15.7: the one copy button for a block; copies exactly that block's text. */
@Composable
private fun CopyBlockButton(
    text: String,
    description: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.IconButton(
        onClick = { com.example.phonesteward.ui.ClipboardCopy.copy(context, "steward", text) },
        modifier = modifier.size(28.dp).testTag(tag),
    ) {
        Icon(
            com.example.phonesteward.ui.theme.StewardIcons.Copy,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** The block's words without markup. */
private fun plainText(inlines: List<Inline>): String = inlines.joinToString("") { it.plain() }

@Composable
private fun MarkdownBlockView(block: MdBlock, colors: MarkdownColors, bodyStyle: TextStyle) {
    when (block) {
        is MdBlock.Paragraph -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { InlineText(block.inlines, colors, bodyStyle) }
            CopyBlockButton(plainText(block.inlines), "Copy text", "md-text-copy")
        }
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> bodyStyle.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                2 -> bodyStyle.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                else -> bodyStyle.copy(fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) {
                    InlineText(block.inlines, colors, style, Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp))
                }
                CopyBlockButton(plainText(block.inlines), "Copy text", "md-text-copy")
            }
        }
        is MdBlock.CodeBlock -> CodeBlockView(block, colors)
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        if (block.ordered) "${block.start + index}." else "•",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(if (block.ordered) 28.dp else 20.dp).padding(start = 4.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.blocks.forEach { MarkdownBlockView(it, colors, bodyStyle) }
                    }
                }
            }
        }
        is MdBlock.Table -> TableView(block, colors, bodyStyle)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(colors.border))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.blocks.forEach {
                    MarkdownBlockView(it, colors, bodyStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic))
                }
            }
        }
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.border)
    }
}

@Composable
private fun InlineText(inlines: List<Inline>, colors: MarkdownColors, style: TextStyle, modifier: Modifier = Modifier) {
    val link = MaterialTheme.colorScheme.primary
    val text = remember(inlines, colors, link) { annotate(inlines, colors, link) }
    Text(text, style = style, modifier = modifier)
}

private fun isSafeUrl(url: String): Boolean {
    val lower = url.trim().lowercase()
    return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")
}

internal fun annotate(inlines: List<Inline>, colors: MarkdownColors, linkColor: Color): AnnotatedString =
    buildAnnotatedString { append(inlines, colors, linkColor) }

private fun AnnotatedString.Builder.append(inlines: List<Inline>, colors: MarkdownColors, linkColor: Color) {
    inlines.forEach { inline ->
        when (inline) {
            is Inline.Text -> append(inline.value)
            is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline.children, colors, linkColor) }
            is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline.children, colors, linkColor) }
            is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inline.children, colors, linkColor) }
            is Inline.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.88.em,
                    background = colors.codeChipBackground,
                    color = colors.codeChipText,
                ),
            ) { append(" ${inline.value} ".replace(' ', ' ')) }
            is Inline.Link -> if (isSafeUrl(inline.url)) {
                withLink(
                    LinkAnnotation.Url(
                        inline.url,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(inline.children, colors, linkColor) }
            } else {
                append(inline.children, colors, linkColor)
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock, colors: MarkdownColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.blockBackground)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .testTag("md-code-block"),
    ) {
        Text(
            block.text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
        )
        // A15.7: the block's code and nothing else (no fences) on the clipboard, as in the Pi Web UI.
        CopyBlockButton(block.text, "Copy code", "md-code-copy", Modifier.align(Alignment.TopEnd))
    }
}

/** Widest cell text per column, in characters, mapped to a width a phone can scroll. */
internal fun columnWidthsDp(table: MdBlock.Table): List<Int> =
    table.header.indices.map { col ->
        val longest = (listOf(table.header[col]) + table.rows.map { it[col] })
            .maxOf { cell -> cell.joinToString("") { it.plain() }.length }
        (longest.coerceIn(4, 30) * 8 + 24)
    }

@Composable
private fun TableView(table: MdBlock.Table, colors: MarkdownColors, bodyStyle: TextStyle) {
    val natural = remember(table) { columnWidthsDp(table) }
    val shape = RoundedCornerShape(8.dp)
    val cellStyle = bodyStyle.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val link = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .testTag("md-table"),
    ) {
        // Like the Pi Web UI's w-full table: narrow tables stretch to the width, wide ones scroll.
        val total = natural.sum()
        val scale = if (total in 1 until maxWidth.value.toInt()) maxWidth.value / total else 1f
        val widths = natural.map { it * scale }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            val all = listOf(table.header) + table.rows
            all.forEachIndexed { rowIndex, cells ->
                val isHeader = rowIndex == 0
                if (rowIndex > 0) HorizontalDivider(color = colors.border)
                Row(
                    Modifier
                        .height(IntrinsicSize.Min)
                        .background(if (isHeader) colors.headerBackground else if (rowIndex % 2 == 0) colors.stripe else Color.Transparent),
                ) {
                    cells.forEachIndexed { col, cell ->
                        if (col > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
                        val text = remember(cell, colors, link) { annotate(cell, colors, link) }
                        Text(
                            text,
                            style = cellStyle.copy(
                                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = when (table.aligns.getOrElse(col) { Align.Start }) {
                                    Align.Start -> TextAlign.Start
                                    Align.Center -> TextAlign.Center
                                    Align.End -> TextAlign.End
                                },
                            ),
                            modifier = Modifier.width(widths[col].dp).padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
}
