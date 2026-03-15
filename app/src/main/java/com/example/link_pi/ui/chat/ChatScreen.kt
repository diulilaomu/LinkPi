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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.data.model.Attachment
import com.example.link_pi.data.model.ChatMessage
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.miniapp.MiniAppParser
import com.example.link_pi.ui.miniapp.exportMiniApp
import com.example.link_pi.ui.workbench.AppCreationCard
import com.example.link_pi.ui.common.RichInputBar
import com.example.link_pi.ui.common.RichInputBarStyle
import com.example.link_pi.ui.common.AgentStepsPanel
import com.example.link_pi.ui.theme.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onRunApp: (MiniApp) -> Unit,
    onEnterSshMode: (String) -> Unit = {},
    onNavigateWorkbench: (ChatViewModel.WorkbenchRequest) -> Unit = {}
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val agentSteps by viewModel.agentSteps.collectAsState()
    val activeSkill by viewModel.activeSkill.collectAsState()
    val deepThinking by viewModel.deepThinking.collectAsState()
    val models by viewModel.models.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    var showModelMenu by remember { mutableStateOf(false) }
    val activeModel = models.firstOrNull { it.id == activeModelId } ?: models.firstOrNull()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val pendingAttachments by viewModel.pendingAttachments.collectAsState()
    val pendingWorkbench by viewModel.pendingWorkbench.collectAsState()
    val pendingSshSession by viewModel.pendingSshSession.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addAttachment(it) }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) {
                // scrollOffset 用大值确保滚到 item 底部
                listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
            }
        }
    }

    // Scroll to show SSH prompt card when it appears
    LaunchedEffect(pendingSshSession) {
        if (pendingSshSession != null && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    LaunchedEffect(agentSteps.size, agentSteps.lastOrNull()?.description, isLoading) {
        if (isLoading && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
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

    // 键盘弹起时自动滚动到底部，避免输入区域遮挡消息
    LaunchedEffect(imeBottomDp) {
        if (imeBottomDp > navBarBottomDp && listState.layoutInfo.totalItemsCount > 0) {
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            // scrollOffset 用大值确保滚到 item 底部而非顶部
            listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
        }
    }

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
            pendingWorkbench?.let { wb ->
                item {
                    AppCreationCard(
                        title = wb.title,
                        prompt = wb.userPrompt,
                        onConfirm = {
                            val req = viewModel.confirmWorkbench()
                            if (req != null) onNavigateWorkbench(req)
                        },
                        onDismiss = {
                            viewModel.dismissWorkbench()
                        }
                    )
                }
            }
            // SSH mode prompt card
            pendingSshSession?.let { sessionId ->
                item {
                    SshModePromptCard(
                        sessionId = sessionId,
                        onEnter = {
                            viewModel.dismissSshPrompt()
                            onEnterSshMode(sessionId)
                        },
                        onDismiss = { viewModel.dismissSshPrompt() }
                    )
                }
            }
            if (isLoading) {
                if (agentSteps.isEmpty()) {
                    item { LoadingIndicator() }
                }
            }
        }

        // Agent steps panel (above input bar, like SSH command panel)
        if (agentSteps.isNotEmpty()) {
            AgentStepsPanel(
                steps = agentSteps,
                isLoading = isLoading
            )
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

        // Input area — shared rich input bar
        RichInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            onSend = {
                viewModel.sendMessage(inputText)
                inputText = ""
            },
            enabled = !isLoading,
            placeholder = "描述你想要的应用...",
            models = models,
            activeModelId = activeModelId,
            onSwitchModel = {
                viewModel.switchModel(it)
                showModelMenu = false
            },
            showModelMenu = showModelMenu,
            onToggleModelMenu = { showModelMenu = !showModelMenu },
            deepThinking = deepThinking,
            onToggleThinking = { viewModel.toggleDeepThinking() },
            pendingAttachments = pendingAttachments,
            onRemoveAttachment = { viewModel.removeAttachment(it) },
            onPickFile = {
                filePickerLauncher.launch(arrayOf(
                    "text/plain", "text/markdown", "text/x-markdown",
                    "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp"
                ))
            },
            style = RichInputBarStyle.Material
        )
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
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "欢迎使用灵枢",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
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
    val context = LocalContext.current
    val avatarBitmap = remember {
        val fileName = if (isUser) "user.png" else "ai.png"
        context.assets.open(fileName).use {
            android.graphics.BitmapFactory.decodeStream(it)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Image(
                bitmap = avatarBitmap.asImageBitmap(),
                contentDescription = "AI Avatar",
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = if (isUser) Modifier else Modifier.weight(1f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
        if (isUser) {
            // Image attachments — each in its own bubble
            val imageAttachments = message.attachments.filter { it.base64Data != null }
            val textAttachments = message.attachments.filter { it.base64Data == null }

            imageAttachments.forEachIndexed { index, att ->
                val b64 = att.base64Data!!.substringAfter("base64,")
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val bmp = remember(att.name) {
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bmp != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        modifier = Modifier.widthIn(max = 220.dp)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "附件${index + 1}:图片",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // Text bubble (text attachments labels + message content)
            if (textAttachments.isNotEmpty() || message.content.isNotBlank()) {
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
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (textAttachments.isNotEmpty()) {
                            textAttachments.forEachIndexed { index, att ->
                                Text(
                                    text = "📄 附件${index + 1}:文本",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                )
                            }
                            if (message.content.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        if (message.content.isNotBlank()) {
                            Text(
                                text = message.content,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.bodyMedium
                            )
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
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
            }
        } else {
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

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Image(
                bitmap = avatarBitmap.asImageBitmap(),
                contentDescription = "User Avatar",
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
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

// ── Markdown Rendering (delegated to shared component) ──

@Composable
private fun MarkdownText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    com.example.link_pi.ui.common.MarkdownText(text = text, style = style, color = color)
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
private fun SshModePromptCard(
    sessionId: String,
    onEnter: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = TermBg,
        border = BorderStroke(1.dp, TermGreen.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🖥️", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    "SSH 已连接",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = MonoFont,
                        color = TermGreen
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "已成功连接到服务器。是否进入 SSH 终端模式？\n终端模式下 AI 将专注于命令生成和执行。",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFont,
                    color = TermDim
                )
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    onClick = onEnter,
                    shape = RoundedCornerShape(8.dp),
                    color = TermGreen.copy(alpha = 0.15f),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "进入终端模式",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = MonoFont,
                            color = TermGreen
                        ),
                        modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(8.dp),
                    color = TermBorder,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "留在对话",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = MonoFont,
                            color = TermDim
                        ),
                        modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
