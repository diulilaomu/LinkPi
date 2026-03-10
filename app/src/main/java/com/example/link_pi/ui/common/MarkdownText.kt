package com.example.link_pi.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke

// ── Markdown Rendering (shared) ──

internal sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Header(val level: Int, val text: String) : MdBlock()
    data class BulletItem(val text: String) : MdBlock()
    data class NumberedItem(val number: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    data object HorizontalRule : MdBlock()
}

internal fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    val buf = StringBuilder()
    val tableLines = mutableListOf<String>()

    fun flushTable() {
        if (tableLines.size >= 2) {
            fun splitRow(line: String): List<String> =
                line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            val headers = splitRow(tableLines[0])
            val isSep = tableLines[1].replace(Regex("[|\\s:-]"), "").isEmpty()
            val dataStart = if (isSep) 2 else 1
            val rows = tableLines.drop(dataStart).map { splitRow(it) }
            blocks.add(MdBlock.Table(headers, rows))
        } else if (tableLines.size == 1) {
            blocks.add(MdBlock.Paragraph(tableLines[0]))
        }
        tableLines.clear()
    }

    fun flush() {
        flushTable()
        if (buf.isNotBlank()) blocks.add(MdBlock.Paragraph(buf.toString().trim()))
        buf.clear()
    }

    var inCodeBlock = false
    var codeBlockLang = ""
    val codeBlockBuf = StringBuilder()

    for (line in lines) {
        val t = line.trimEnd()

        // Handle fenced code blocks (``` or ~~~)
        if (t.matches(Regex("^\\s*(`{3,}|~{3,})(.*)$"))) {
            if (!inCodeBlock) {
                // Opening fence
                flush()
                inCodeBlock = true
                codeBlockLang = t.trimStart().dropWhile { it == '`' || it == '~' }.trim()
                codeBlockBuf.clear()
                continue
            } else {
                // Closing fence
                blocks.add(MdBlock.CodeBlock(codeBlockLang, codeBlockBuf.toString().trimEnd()))
                inCodeBlock = false
                codeBlockLang = ""
                codeBlockBuf.clear()
                continue
            }
        }
        if (inCodeBlock) {
            if (codeBlockBuf.isNotEmpty()) codeBlockBuf.append('\n')
            codeBlockBuf.append(line)
            continue
        }

        if (tableLines.isNotEmpty() && !t.trimStart().startsWith("|")) {
            flushTable()
        }
        when {
            t.trimStart().startsWith("|") -> {
                if (buf.isNotBlank()) {
                    blocks.add(MdBlock.Paragraph(buf.toString().trim()))
                    buf.clear()
                }
                tableLines.add(t)
            }
            t.isBlank() -> flush()
            t.matches(Regex("^#{1,6}\\s+.+")) -> {
                flush()
                val lvl = t.takeWhile { it == '#' }.length
                blocks.add(MdBlock.Header(lvl, t.dropWhile { it == '#' }.trim()))
            }
            t.matches(Regex("^\\s*[-*+]\\s+.+")) -> {
                flush()
                blocks.add(MdBlock.BulletItem(t.trimStart().drop(2)))
            }
            t.matches(Regex("^\\s*\\d+\\.\\s+.+")) -> {
                flush()
                val s = t.trimStart()
                val dot = s.indexOf('.')
                blocks.add(MdBlock.NumberedItem(s.substring(0, dot), s.substring(dot + 1).trimStart()))
            }
            t.matches(Regex("^>\\s?.*")) -> {
                val content = when {
                    t.startsWith("> ") -> t.drop(2)
                    t.startsWith(">") -> t.drop(1)
                    else -> t
                }
                val last = blocks.lastOrNull()
                if (last is MdBlock.Quote && buf.isEmpty()) {
                    blocks[blocks.size - 1] = MdBlock.Quote(last.text + "\n" + content)
                } else {
                    flush()
                    blocks.add(MdBlock.Quote(content))
                }
            }
            t.matches(Regex("^[-*_]{3,}\\s*$")) -> {
                flush()
                blocks.add(MdBlock.HorizontalRule)
            }
            else -> {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(t)
            }
        }
    }
    // Handle unclosed code block
    if (inCodeBlock) {
        blocks.add(MdBlock.CodeBlock(codeBlockLang, codeBlockBuf.toString().trimEnd()))
    }
    flush()
    return blocks
}

private data class InlineSpan(val start: Int, val end: Int, val content: String, val type: String, val url: String = "")

internal fun buildInlineMarkdown(
    text: String,
    baseColor: Color,
    codeColor: Color,
    codeBgColor: Color
): AnnotatedString {
    if (!text.contains('*') && !text.contains('`') && !text.contains('~') && !text.contains('[')) {
        return AnnotatedString(text)
    }

    val spans = mutableListOf<InlineSpan>()
    Regex("`([^`]+)`").findAll(text).forEach {
        spans.add(InlineSpan(it.range.first, it.range.last + 1, it.groupValues[1], "code"))
    }
    Regex("\\*\\*(.+?)\\*\\*").findAll(text).forEach {
        spans.add(InlineSpan(it.range.first, it.range.last + 1, it.groupValues[1], "bold"))
    }
    Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)").findAll(text).forEach {
        spans.add(InlineSpan(it.range.first, it.range.last + 1, it.groupValues[1], "italic"))
    }
    Regex("~~(.+?)~~").findAll(text).forEach {
        spans.add(InlineSpan(it.range.first, it.range.last + 1, it.groupValues[1], "strike"))
    }
    Regex("\\[([^\\]]+)]\\(([^)]+)\\)").findAll(text).forEach {
        spans.add(InlineSpan(it.range.first, it.range.last + 1, it.groupValues[1], "link", it.groupValues[2]))
    }

    if (spans.isEmpty()) return AnnotatedString(text)

    val sorted = spans.sortedBy { it.start }
    val filtered = mutableListOf<InlineSpan>()
    var lastEnd = 0
    for (s in sorted) {
        if (s.start >= lastEnd) { filtered.add(s); lastEnd = s.end }
    }

    return buildAnnotatedString {
        var pos = 0
        for (s in filtered) {
            if (pos < s.start) append(text.substring(pos, s.start))
            when (s.type) {
                "bold" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.content) }
                "italic" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.content) }
                "code" -> withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBgColor,
                    color = codeColor
                )) { append("\u00A0${s.content}\u00A0") }
                "strike" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(s.content) }
                "link" -> {
                    pushStringAnnotation(tag = "URL", annotation = s.url)
                    withStyle(SpanStyle(
                        color = codeColor,
                        textDecoration = TextDecoration.Underline
                    )) { append(s.content) }
                    pop()
                }
            }
            pos = s.end
        }
        if (pos < text.length) append(text.substring(pos))
    }
}

@Composable
private fun ClickableInlineText(
    annotated: AnnotatedString,
    style: TextStyle,
    color: Color,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    modifier: Modifier = Modifier
) {
    val hasLinks = annotated.getStringAnnotations("URL", 0, annotated.length).isNotEmpty()
    if (hasLinks) {
        @Suppress("DEPRECATION")
        androidx.compose.foundation.text.ClickableText(
            text = annotated,
            style = style.copy(color = color),
            modifier = modifier,
            onClick = { offset ->
                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                    try { uriHandler.openUri(it.item) } catch (_: Exception) { }
                }
            }
        )
    } else {
        Text(text = annotated, style = style, color = color, modifier = modifier)
    }
}

/**
 * Renders markdown text with support for headers, lists, quotes, tables, inline styles.
 * Optionally accepts custom colors for terminal-style themes.
 */
@Composable
fun MarkdownText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
    color: Color = MaterialTheme.colorScheme.onSurface,
    codeColor: Color = MaterialTheme.colorScheme.primary,
    codeBgColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    dividerColor: Color = MaterialTheme.colorScheme.outlineVariant,
    tableBgColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    tableHeaderBgColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
    modifier: Modifier = Modifier
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val uriHandler = LocalUriHandler.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Header -> {
                    val hs = when (block.level) {
                        1 -> style.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                        2 -> style.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                        3 -> style.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
                        else -> style.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    val annotated = buildInlineMarkdown(block.text, color, codeColor, codeBgColor)
                    ClickableInlineText(annotated, hs, color, uriHandler)
                }
                is MdBlock.BulletItem -> {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            "\u2022",
                            style = style,
                            color = color.copy(alpha = 0.5f),
                            modifier = Modifier.width(16.dp),
                            textAlign = TextAlign.Center
                        )
                        val annotated = buildInlineMarkdown(block.text, color, codeColor, codeBgColor)
                        ClickableInlineText(annotated, style, color, uriHandler, Modifier.weight(1f))
                    }
                }
                is MdBlock.NumberedItem -> {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            "${block.number}.",
                            style = style.copy(fontFeatureSettings = "tnum"),
                            color = color.copy(alpha = 0.5f),
                            modifier = Modifier.width(22.dp),
                            textAlign = TextAlign.End
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val annotated = buildInlineMarkdown(block.text, color, codeColor, codeBgColor)
                        ClickableInlineText(annotated, style, color, uriHandler, Modifier.weight(1f))
                    }
                }
                is MdBlock.CodeBlock -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = codeBgColor,
                        border = BorderStroke(0.5.dp, dividerColor)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            if (block.language.isNotBlank()) {
                                Text(
                                    block.language,
                                    style = style.copy(fontSize = 10.sp, color = color.copy(alpha = 0.4f)),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Text(
                                block.code,
                                style = style.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = codeColor,
                                    lineHeight = 18.sp
                                )
                            )
                        }
                    }
                }
                is MdBlock.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .heightIn(min = 20.dp)
                                .background(codeColor.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val annotated = buildInlineMarkdown(block.text, color.copy(alpha = 0.7f), codeColor, codeBgColor)
                        ClickableInlineText(
                            annotated,
                            style.copy(fontStyle = FontStyle.Italic),
                            color.copy(alpha = 0.7f),
                            uriHandler,
                            Modifier.weight(1f)
                        )
                    }
                }
                is MdBlock.Table -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tableBgColor,
                        border = BorderStroke(0.5.dp, dividerColor)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(tableHeaderBgColor)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                block.headers.forEach { header ->
                                    Text(
                                        text = buildInlineMarkdown(header, color, codeColor, codeBgColor),
                                        style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            HorizontalDivider(color = dividerColor, thickness = 0.5.dp)
                            block.rows.forEachIndexed { rowIdx, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    block.headers.forEachIndexed { idx, _ ->
                                        val cell = row.getOrElse(idx) { "" }
                                        Text(
                                            text = buildInlineMarkdown(cell, color, codeColor, codeBgColor),
                                            style = style.copy(fontSize = 13.sp),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                if (rowIdx < block.rows.size - 1) {
                                    HorizontalDivider(color = dividerColor.copy(alpha = 0.5f), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
                MdBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = dividerColor
                    )
                }
                is MdBlock.Paragraph -> {
                    val annotated = buildInlineMarkdown(block.text, color, codeColor, codeBgColor)
                    ClickableInlineText(annotated, style, color, uriHandler)
                }
            }
        }
    }
}
