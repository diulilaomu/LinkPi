package com.example.link_pi.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.agent.StepType
import com.example.link_pi.data.model.ChatMessage
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.miniapp.MiniAppParser
import com.example.link_pi.ui.miniapp.exportMiniApp

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onRunApp: (MiniApp) -> Unit
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val agentSteps by viewModel.agentSteps.collectAsState()
    val activeSkill by viewModel.activeSkill.collectAsState()
    val deepThinking by viewModel.deepThinking.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    LaunchedEffect(agentSteps.size, isLoading) {
        if (isLoading && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    val density = LocalDensity.current
    val imeBottomDp = with(density) {
        WindowInsets.ime.getBottom(density).toDp()
    }
    val navBarBottomDp = with(density) {
        WindowInsets.Companion.navigationBars.getBottom(density).toDp()
    }
    // Take the larger of IME or nav bar, avoid double padding
    val bottomPadding = maxOf(imeBottomDp, navBarBottomDp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = bottomPadding)
    ) {
        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    WelcomeCard()
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    isLastAssistant = !isLoading && message.role == "assistant" && message == messages.lastOrNull { it.role == "assistant" },
                    onRunApp = { app ->
                        viewModel.setCurrentApp(app)
                        onRunApp(app)
                    },
                    onSaveApp = { app -> viewModel.saveMiniApp(app) },
                    onExportApp = { app -> exportMiniApp(context, app) },
                    onDelete = { viewModel.deleteMessage(message.id) },
                    onRegenerate = { viewModel.regenerateLastResponse() }
                )
            }
            if (isLoading) {
                if (agentSteps.isNotEmpty()) {
                    item { AgentStepsCard(agentSteps) }
                } else {
                    item { LoadingIndicator() }
                }
            }
        }

        // Error banner
        error?.let { errorMsg ->
            Text(
                text = errorMsg,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Input area — DeepSeek style unified card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp
        ) {
            Column {
                // Text input — borderless inside the card
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    placeholder = {
                        Text(
                            "描述你想要的应用...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                // Bottom toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Deep thinking toggle
                    Surface(
                        onClick = { viewModel.toggleDeepThinking() },
                        shape = RoundedCornerShape(16.dp),
                        color = if (deepThinking)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Lightbulb,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (deepThinking)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = "深度思考",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (deepThinking)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Skill badge
                    Text(
                        text = "  ${activeSkill.icon} ${activeSkill.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Send button
                    Surface(
                        onClick = {
                            if (inputText.isNotBlank() && !isLoading) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && !isLoading,
                        shape = RoundedCornerShape(24.dp),
                        color = if (inputText.isNotBlank() && !isLoading)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "发送",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (inputText.isNotBlank() && !isLoading)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    } // Column
}

// ── Welcome ──

@Composable
private fun WelcomeCard() {
    val context = LocalContext.current
    val logoBitmap = remember {
        context.assets.open("logo.png").use {
            android.graphics.BitmapFactory.decodeStream(it)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = logoBitmap.asImageBitmap(),
            contentDescription = "LinkPi Logo",
            modifier = Modifier.size(96.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "愿为AI应用探索的后行者",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Message Bubble ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    isLastAssistant: Boolean = false,
    onRunApp: (MiniApp) -> Unit,
    onSaveApp: (MiniApp) -> Unit,
    onExportApp: (MiniApp) -> Unit = {},
    onDelete: () -> Unit = {},
    onRegenerate: () -> Unit = {}
) {
    val isUser = message.role == "user"
    var saved by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Box {
                Surface(
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showMenu = true }
                        )
                ) {
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        } else {
            // Agent steps (collapsed)
            if (message.agentSteps.isNotEmpty()) {
                AgentStepsTimeline(steps = message.agentSteps)
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Deep thinking block (collapsed)
            if (message.thinkingContent.isNotBlank()) {
                ThinkingBlock(content = message.thinkingContent)
                Spacer(modifier = Modifier.height(6.dp))
            }

            val segments = remember(message.content) {
                parseResponseSegments(message.content)
            }

            // AI response — with long press menu
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 24.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showMenu = true }
                        )
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        segments.forEachIndexed { index, segment ->
                            when (segment) {
                                is ResponseSegment.TextBlock -> {
                                    if (segment.text.isNotBlank()) {
                                        MarkdownText(text = segment.text.trim())
                                    }
                                }
                                is ResponseSegment.CodeBlock -> {
                                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                                    CollapsibleCodeBlock(
                                        language = segment.language,
                                        code = segment.code,
                                        isHtml = segment.language.equals("html", ignoreCase = true)
                                    )
                                    if (index < segments.size - 1) Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }

                        if (segments.isEmpty() && message.content.isNotBlank()) {
                            val display = if (message.miniApp != null) {
                                MiniAppParser.getDisplayText(message.content).ifEmpty { "应用已生成" }
                            } else message.content
                            MarkdownText(text = display)
                        }
                    }
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            showMenu = false
                        }
                    )
                    if (isLastAssistant) {
                        DropdownMenuItem(
                            text = { Text("重新生成") },
                            onClick = { showMenu = false; onRegenerate() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }

            // Mini app actions
            message.miniApp?.let { app ->
                Spacer(modifier = Modifier.height(6.dp))
                MiniAppActionBar(
                    app = app,
                    onRun = { onRunApp(app) },
                    onSave = {
                        onSaveApp(app)
                        saved = true
                    },
                    onExport = { onExportApp(app) },
                    saved = saved
                )
            }

            // Regenerate button on last assistant message
            if (isLastAssistant) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    onClick = onRegenerate,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "重新生成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── Response parsing ──

private sealed class ResponseSegment {
    data class TextBlock(val text: String) : ResponseSegment()
    data class CodeBlock(val language: String, val code: String) : ResponseSegment()
}

private fun parseResponseSegments(content: String): List<ResponseSegment> {
    val segments = mutableListOf<ResponseSegment>()
    val codeBlockRegex = Regex("```(\\w*)\\s*\\n([\\s\\S]*?)\\n```")
    var lastEnd = 0

    codeBlockRegex.findAll(content).forEach { match ->
        if (match.range.first > lastEnd) {
            val textBefore = content.substring(lastEnd, match.range.first)
            if (textBefore.isNotBlank()) {
                segments.add(ResponseSegment.TextBlock(textBefore))
            }
        }
        segments.add(ResponseSegment.CodeBlock(
            language = match.groupValues[1].ifBlank { "code" },
            code = match.groupValues[2]
        ))
        lastEnd = match.range.last + 1
    }

    if (lastEnd < content.length) {
        val remaining = content.substring(lastEnd)
        val truncatedMatch = Regex("```(\\w*)\\s*\\n([\\s\\S]+)").find(remaining)
        if (truncatedMatch != null) {
            val textBefore = remaining.substring(0, truncatedMatch.range.first)
            if (textBefore.isNotBlank()) {
                segments.add(ResponseSegment.TextBlock(textBefore))
            }
            segments.add(ResponseSegment.CodeBlock(
                language = truncatedMatch.groupValues[1].ifBlank { "code" },
                code = truncatedMatch.groupValues[2]
            ))
        } else if (remaining.isNotBlank()) {
            segments.add(ResponseSegment.TextBlock(remaining))
        }
    }

    return segments
}

// ── Markdown Rendering ──

private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Header(val level: Int, val text: String) : MdBlock()
    data class BulletItem(val text: String) : MdBlock()
    data class NumberedItem(val number: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    data object HorizontalRule : MdBlock()
}

private fun parseMarkdownBlocks(text: String): List<MdBlock> {
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

    for (line in lines) {
        val t = line.trimEnd()
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
    flush()
    return blocks
}

private fun buildInlineMarkdown(
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

private data class InlineSpan(val start: Int, val end: Int, val content: String, val type: String, val url: String = "")

@Composable
private fun MarkdownText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeColor = MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    val annotated = buildInlineMarkdown(block.text, color, codeColor, codeBg)
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
                        val annotated = buildInlineMarkdown(block.text, color, codeColor, codeBg)
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
                        val annotated = buildInlineMarkdown(block.text, color, codeColor, codeBg)
                        ClickableInlineText(annotated, style, color, uriHandler, Modifier.weight(1f))
                    }
                }
                is MdBlock.Quote -> {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .heightIn(min = 20.dp)
                                .background(codeColor.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val annotated = buildInlineMarkdown(block.text, color.copy(alpha = 0.7f), codeColor, codeBg)
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
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(0.5.dp, dividerColor)
                    ) {
                        Column {
                            // Header row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                block.headers.forEach { header ->
                                    Text(
                                        text = buildInlineMarkdown(header, color, codeColor, codeBg),
                                        style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            HorizontalDivider(color = dividerColor, thickness = 0.5.dp)
                            // Data rows
                            block.rows.forEachIndexed { rowIdx, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    block.headers.forEachIndexed { idx, _ ->
                                        val cell = row.getOrElse(idx) { "" }
                                        Text(
                                            text = buildInlineMarkdown(cell, color, codeColor, codeBg),
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
                    val annotated = buildInlineMarkdown(block.text, color, codeColor, codeBg)
                    ClickableInlineText(annotated, style, color, uriHandler)
                }
            }
        }
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

// ── Collapsible Code Block ──

@Composable
private fun CollapsibleCodeBlock(
    language: String,
    code: String,
    isHtml: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "arrow")
    val lineCount = code.lines().size
    val previewLines = 5
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column {
            Surface(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = language.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (isHtml) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Copy button
                    Surface(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(code))
                            copied = true
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "复制代码",
                                modifier = Modifier.size(12.dp),
                                tint = if (copied) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Text(
                                text = if (copied) "已复制" else "复制",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = if (copied) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (expanded) "收起" else "$lineCount 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

            if (!expanded && lineCount > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = code.lines().take(previewLines).joinToString("\n") +
                                if (lineCount > previewLines) "\n..." else "",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = previewLines + 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── Deep Thinking Block ──

@Composable
private fun ThinkingBlock(content: String) {
    var expanded by remember { mutableStateOf(false) }
    val accentColor = MaterialTheme.colorScheme.tertiary

    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = accentColor.copy(alpha = 0.06f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accentColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "深度思考",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = accentColor
                )
                Spacer(modifier = Modifier.weight(1f))
                val lines = remember(content) { content.lines().size }
                Text(
                    text = if (expanded) "收起 ▲" else "$lines 行 ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor.copy(alpha = 0.5f)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        )
                    }
                }
            }
        }
    }
}

// ── Agent Steps Timeline ──

@Composable
private fun AgentStepsTimeline(steps: List<AgentStep>) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "思考过程",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${steps.size} 步 ${if (expanded) "▲" else "▼"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    steps.forEachIndexed { idx, step ->
                        AgentStepRow(step, isLast = idx == steps.size - 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentStepRow(step: AgentStep, isLast: Boolean) {
    val accentColor = when (step.type) {
        StepType.THINKING -> MaterialTheme.colorScheme.tertiary
        StepType.TOOL_CALL -> MaterialTheme.colorScheme.primary
        StepType.TOOL_RESULT -> MaterialTheme.colorScheme.secondary
        StepType.FINAL_RESPONSE -> MaterialTheme.colorScheme.primary
    }

    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.7f))
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(accentColor.copy(alpha = 0.2f))
                )
            }
        }

        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = step.description,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── Mini App Action Bar ──

@Composable
private fun MiniAppActionBar(
    app: MiniApp,
    onRun: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    saved: Boolean
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onRun, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "运行",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = onSave,
                enabled = !saved,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = if (saved) "已保存" else "保存",
                    modifier = Modifier.size(18.dp),
                    tint = if (saved) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = "导出",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Loading & Agent Steps Card ──

@Composable
private fun LoadingIndicator() {
    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = "正在思考...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun AgentStepsCard(steps: List<AgentStep>) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp
                )
                Text(
                    text = "正在工作...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
            Column(modifier = Modifier.animateContentSize()) {
                steps.forEachIndexed { idx, step ->
                    AgentStepRow(step, isLast = idx == steps.size - 1)
                }
            }
        }
    }
}
