package com.example.link_pi.ui.ssh

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.link_pi.agent.SshOrchestrator.CommandStatus
import com.example.link_pi.ui.common.MarkdownText
import com.example.link_pi.ui.sftp.SftpScreen
import com.example.link_pi.ui.sftp.SftpViewModel
import com.example.link_pi.ui.sftp.FileEditorScreen

// ═══════════════════════════════════════
//  DOS-style Dark Color Palette
// ═══════════════════════════════════════

private val TermBg = Color(0xFF0D1117)
private val TermSurface = Color(0xFF161B22)
private val TermCard = Color(0xFF1C2128)
private val TermBorder = Color(0xFF30363D)
private val TermGreen = Color(0xFF3FB950)
private val TermYellow = Color(0xFFD29922)
private val TermRed = Color(0xFFF85149)
private val TermCyan = Color(0xFF58A6FF)
private val TermText = Color(0xFFE6EDF3)
private val TermDim = Color(0xFF8B949E)
private val TermInputBg = Color(0xFF0D1117)

private val MonoFont = FontFamily.Monospace

@Composable
fun SshScreen(
    viewModel: SshViewModel,
    onBack: () -> Unit
) {
    var page by remember { mutableStateOf("ssh") }
    val sftpViewModel: SftpViewModel = viewModel()
    val sessionId by viewModel.sessionId.collectAsState()
    val isManualMode by viewModel.isManualMode.collectAsState()
    var showModeSwitchDialog by remember { mutableStateOf(false) }

    // 右滑确认进入手动模式对话框
    if (showModeSwitchDialog) {
        AlertDialog(
            onDismissRequest = { showModeSwitchDialog = false },
            containerColor = TermSurface,
            title = {
                Text(
                    "切换到手动模式",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, color = TermText)
                )
            },
            text = {
                Text(
                    "手动模式下可直接输入 Shell 命令执行，无 AI 辅助。\n左滑可切回 AI 模式。",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermDim)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showModeSwitchDialog = false
                    viewModel.setManualMode(true)
                }) { Text("确定", color = TermGreen) }
            },
            dismissButton = {
                TextButton(onClick = { showModeSwitchDialog = false }) {
                    Text("取消", color = TermDim)
                }
            }
        )
    }

    when (page) {
        "ssh" -> {
            if (isManualMode) {
                ManualTerminalContent(
                    viewModel = viewModel,
                    onBack = onBack,
                    onOpenSftp = {
                        val sid = sessionId
                        if (sid != null) page = "sftp"
                    },
                    onSwitchToAi = { viewModel.setManualMode(false) }
                )
            } else {
                SshTerminalContent(
                    viewModel = viewModel,
                    onBack = onBack,
                    onOpenSftp = {
                        val sid = sessionId
                        if (sid != null) page = "sftp"
                    },
                    onRequestManualMode = { showModeSwitchDialog = true }
                )
            }
        }
        "sftp" -> {
            val sid = sessionId
            if (sid != null) {
                SftpScreen(
                    viewModel = sftpViewModel,
                    sessionId = sid,
                    onBack = { page = "ssh" },
                    onOpenEditor = { page = "editor" }
                )
            } else {
                page = "ssh"
            }
        }
        "editor" -> FileEditorScreen(
            viewModel = sftpViewModel,
            onBack = { page = "sftp" }
        )
    }
}

@Composable
private fun SshTerminalContent(
    viewModel: SshViewModel,
    onBack: () -> Unit,
    onOpenSftp: () -> Unit,
    onRequestManualMode: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val serverInfo by viewModel.serverInfo.collectAsState()
    val pendingCommands by viewModel.pendingCommands.collectAsState()
    val deepThinking by viewModel.deepThinking.collectAsState()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    val density = LocalDensity.current
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val bottomPadding = maxOf(imeBottomDp, navBarBottomDp)

    // Auto-scroll
    LaunchedEffect(messages.size) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    // 键盘弹起时自动滚动到底部
    LaunchedEffect(imeBottomDp) {
        if (imeBottomDp > navBarBottomDp && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBg)
            .statusBarsPadding()
            .padding(bottom = bottomPadding)
            .pointerInput(Unit) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        if (totalDrag > 200) onRequestManualMode()
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                    onHorizontalDrag = { _, amount -> totalDrag += amount }
                )
            }
    ) {
        // ── Top Bar ──
        SshTopBar(serverInfo, deepThinking, onBack, viewModel::toggleDeepThinking, viewModel::disconnect, onOpenSftp)

        // ── Message List ──
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Server Info Card
            serverInfo?.let { info ->
                if (info.osType.isNotBlank()) {
                    item(key = "server_info") {
                        ServerInfoCard(info)
                    }
                }
            }

            // Messages (user / assistant / system / result)
            items(messages, key = { it.id }) { msg ->
                when (msg.role) {
                    "user" -> UserBubble(msg.content)
                    "assistant" -> AssistantBubble(msg.content, msg.thinkingContent)
                    "system" -> SystemMessage(msg.content)
                    "result" -> {
                        ExecutionResultCard(msg.executedCommands)
                        // Separate explanation card(s) below the result
                        msg.executedCommands.forEach { cmd ->
                            if (cmd.explanation.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                ExplanationCard(cmd)
                            }
                        }
                    }
                }
            }

            // Loading indicator
            if (isLoading) {
                item(key = "loading") {
                    SshLoadingIndicator()
                }
            }
        }

        // ── Pending Commands Panel (collapsible, above input bar) ──
        if (pendingCommands.isNotEmpty()) {
            PendingCommandsPanel(
                commands = pendingCommands,
                onConfirm = viewModel::confirmCommand,
                onConfirmAll = viewModel::confirmAll,
                onSkip = viewModel::skipCommand,
                onEdit = viewModel::editCommand
            )
        }

        // ── Input Area ──
        SshInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            onSend = {
                if (inputText.isNotBlank() && !isLoading) {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                }
            },
            enabled = !isLoading
        )
    }
}

// ═══════════════════════════════════════
//  Top Bar
// ═══════════════════════════════════════

@Composable
private fun SshTopBar(
    serverInfo: ServerInfo?,
    deepThinking: Boolean,
    onBack: () -> Unit,
    onToggleThinking: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSftp: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermSurface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TermText)
        }
        Icon(
            Icons.Outlined.Terminal,
            contentDescription = null,
            tint = TermGreen,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SSH Terminal",
                style = TextStyle(
                    fontFamily = MonoFont,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TermGreen
                )
            )
            if (serverInfo != null) {
                Text(
                    text = "${serverInfo.username}@${serverInfo.host}",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // SFTP button
        IconButton(onClick = onOpenSftp) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = "SFTP",
                tint = TermYellow,
                modifier = Modifier.size(20.dp)
            )
        }

        // Deep Thinking Toggle
        Surface(
            onClick = onToggleThinking,
            shape = RoundedCornerShape(16.dp),
            color = if (deepThinking) TermCyan.copy(alpha = 0.15f) else Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (deepThinking) TermCyan else TermDim
                )
                Text(
                    "深度思考",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp,
                        color = if (deepThinking) TermCyan else TermDim)
                )
            }
        }

        // Disconnect button
        Surface(
            onClick = onDisconnect,
            shape = RoundedCornerShape(16.dp),
            color = TermRed.copy(alpha = 0.1f)
        ) {
            Text(
                "断开",
                style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermRed),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

// ═══════════════════════════════════════
//  Server Info Card
// ═══════════════════════════════════════

@Composable
private fun ServerInfoCard(info: ServerInfo) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = TermCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, TermBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "服务器信息",
                style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TermGreen)
            )
            Spacer(Modifier.height(8.dp))
            InfoRow("主机", "${info.host}:${info.port}")
            InfoRow("用户名", info.username)
            if (info.hostname.isNotBlank()) InfoRow("主机名", info.hostname)
            if (info.osType.isNotBlank()) InfoRow("系统", info.osType)
            if (info.osVersion.isNotBlank()) InfoRow("版本", info.osVersion)
            if (info.kernel.isNotBlank()) InfoRow("内核", info.kernel.take(60))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermDim),
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = value,
            style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermText),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═══════════════════════════════════════
//  Message Bubbles
// ═══════════════════════════════════════

@Composable
private fun UserBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp),
            color = TermCyan.copy(alpha = 0.15f),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Row(modifier = Modifier.padding(10.dp)) {
                Text(
                    "$ ",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, color = TermCyan)
                )
                Text(
                    content,
                    style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, color = TermText)
                )
            }
        }
    }
}

@Composable
private fun AssistantBubble(content: String, thinkingContent: String) {
    var showThinking by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Thinking toggle
        if (thinkingContent.isNotBlank()) {
            Surface(
                onClick = { showThinking = !showThinking },
                shape = RoundedCornerShape(6.dp),
                color = TermCard
            ) {
                Text(
                    text = if (showThinking) "▼ 思考过程" else "▶ 查看思考过程",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermDim),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            AnimatedVisibility(visible = showThinking, enter = expandVertically(), exit = shrinkVertically()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TermCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        thinkingContent.takeLast(2000),
                        style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermDim, lineHeight = 14.sp),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // Main content
        if (content.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp),
                color = TermSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, TermBorder)
            ) {
                MarkdownText(
                    text = content,
                    style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermText, lineHeight = 18.sp),
                    color = TermText,
                    codeColor = TermCyan,
                    codeBgColor = TermCard,
                    dividerColor = TermBorder,
                    tableBgColor = TermCard,
                    tableHeaderBgColor = TermSurface,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun SystemMessage(content: String) {
    Text(
        text = "── $content ──",
        style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermDim),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

// ═══════════════════════════════════════
//  Pending Commands Panel (collapsible, above input bar)
// ═══════════════════════════════════════

@Composable
private fun PendingCommandsPanel(
    commands: List<com.example.link_pi.agent.SshOrchestrator.SshCommand>,
    onConfirm: (Int) -> Unit,
    onConfirmAll: () -> Unit,
    onSkip: (Int) -> Unit,
    onEdit: (Int, String) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 0f else -90f, label = "arrow")
    val hasPending = commands.any { it.status == CommandStatus.PENDING }

    Surface(
        color = TermSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, TermYellow.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            // Header — clickable to toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TermYellow,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "⚡ 待执行命令",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TermYellow)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "(${commands.size})",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermDim)
                )
                Spacer(Modifier.weight(1f))
                if (hasPending && commands.size > 1) {
                    Surface(
                        onClick = onConfirmAll,
                        shape = RoundedCornerShape(6.dp),
                        color = TermGreen.copy(alpha = 0.15f)
                    ) {
                        Text(
                            "全部执行",
                            style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermGreen),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Collapsible content
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    commands.forEachIndexed { index, cmd ->
                        CommandRow(index, cmd, onConfirm, onSkip, onEdit)
                        if (index < commands.size - 1) {
                            HorizontalDivider(color = TermBorder, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    index: Int,
    cmd: com.example.link_pi.agent.SshOrchestrator.SshCommand,
    onConfirm: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onEdit: (Int, String) -> Unit
) {
    val isPending = cmd.status == CommandStatus.PENDING
    // Use TextFieldValue for proper IME composition handling
    var textFieldValue by remember(cmd.command) {
        mutableStateOf(TextFieldValue(cmd.command))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Command description
        if (cmd.description.isNotBlank()) {
            Text(
                cmd.description,
                style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermDim),
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Prompt symbol
            Text(
                "$ ",
                style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermCyan)
            )

            // Editable command text (only when PENDING)
            if (isPending) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onEdit(index, newValue.text)
                    },
                    textStyle = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermText),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(TermGreen),
                    maxLines = 3,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    cmd.command,
                    style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermText),
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            when (cmd.status) {
                CommandStatus.PENDING -> {
                    // Confirm button
                    Surface(
                        onClick = { onConfirm(index) },
                        shape = RoundedCornerShape(4.dp),
                        color = TermGreen.copy(alpha = 0.15f)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "执行",
                            tint = TermGreen,
                            modifier = Modifier.padding(4.dp).size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    // Skip button
                    Surface(
                        onClick = { onSkip(index) },
                        shape = RoundedCornerShape(4.dp),
                        color = TermRed.copy(alpha = 0.1f)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "跳过",
                            tint = TermRed,
                            modifier = Modifier.padding(4.dp).size(16.dp)
                        )
                    }
                }
                CommandStatus.RUNNING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = TermCyan
                    )
                }
                CommandStatus.COMPLETED -> {
                    Icon(Icons.Filled.Check, "完成", tint = TermGreen, modifier = Modifier.size(16.dp))
                }
                CommandStatus.FAILED -> {
                    Text("✗", style = TextStyle(fontSize = 14.sp, color = TermRed))
                }
                CommandStatus.SKIPPED -> {
                    Text("跳过", style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermDim))
                }
            }
        }
    }
}

// ═══════════════════════════════════════
//  Execution Result Card (in chat flow)
// ═══════════════════════════════════════

@Composable
private fun ExecutionResultCard(results: List<com.example.link_pi.agent.SshOrchestrator.SshCommand>) {
    if (results.isEmpty()) return

    val hasRunning = results.any { it.status == CommandStatus.RUNNING }
    var showFullScreen by remember { mutableStateOf(false) }

    // Auto-scroll state for the fixed-height card
    val cardScrollState = rememberScrollState()
    val latestOutput = results.lastOrNull()?.output.orEmpty()
    LaunchedEffect(latestOutput, hasRunning) {
        if (hasRunning) cardScrollState.animateScrollTo(cardScrollState.maxValue)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF010409),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (hasRunning) TermCyan.copy(alpha = 0.5f) else TermBorder
        )
    ) {
        Box {
            Column(
                modifier = Modifier
                    .heightIn(max = 200.dp)
                    .verticalScroll(cardScrollState)
                    .padding(10.dp)
                    .padding(top = 14.dp) // space for the expand button
            ) {
                results.forEachIndexed { index, cmd ->
                    ResultBlock(cmd)
                    if (index < results.size - 1) {
                        HorizontalDivider(color = TermBorder.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }

            // Expand fullscreen button — top-right corner
            Surface(
                onClick = { showFullScreen = true },
                shape = RoundedCornerShape(bottomStart = 8.dp),
                color = TermCard.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    Icons.Outlined.OpenInFull,
                    contentDescription = "全屏",
                    tint = TermDim,
                    modifier = Modifier.padding(6.dp).size(14.dp)
                )
            }
        }
    }

    // Fullscreen overlay
    if (showFullScreen) {
        Dialog(
            onDismissRequest = { showFullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            FullScreenResultOverlay(
                results = results,
                hasRunning = hasRunning,
                onDismiss = { showFullScreen = false }
            )
        }
    }
}

@Composable
private fun FullScreenResultOverlay(
    results: List<com.example.link_pi.agent.SshOrchestrator.SshCommand>,
    hasRunning: Boolean,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val latestOutput = results.lastOrNull()?.output.orEmpty()
    LaunchedEffect(latestOutput, hasRunning) {
        if (hasRunning) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBg.copy(alpha = 0.97f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top bar with close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermCard)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = TermCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "执行结果",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TermText)
                )
                if (hasRunning) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = TermCyan
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Transparent
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭",
                        tint = TermDim,
                        modifier = Modifier.padding(4.dp).size(18.dp)
                    )
                }
            }

            // Scrollable content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(12.dp)
            ) {
                results.forEachIndexed { index, cmd ->
                    ResultBlock(cmd)
                    if (index < results.size - 1) {
                        HorizontalDivider(color = TermBorder.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultBlock(cmd: com.example.link_pi.agent.SshOrchestrator.SshCommand) {
    val isRunning = cmd.status == CommandStatus.RUNNING
    val exitColor = if (cmd.exitCode == 0) TermGreen else TermRed
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var showCopied by remember { mutableStateOf(false) }

    LaunchedEffect(showCopied) {
        if (showCopied) {
            kotlinx.coroutines.delay(1500)
            showCopied = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Command header with status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$ ${cmd.command}",
                style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TermCyan),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(4.dp))
            if (!isRunning) {
                Surface(
                    onClick = {
                        val copyText = buildString {
                            appendLine("$ ${cmd.command}")
                            if (cmd.output.isNotBlank()) append(cmd.output)
                        }
                        clipboardManager.setText(AnnotatedString(copyText))
                        showCopied = true
                    },
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Transparent
                ) {
                    Icon(
                        if (showCopied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                        contentDescription = "复制",
                        tint = if (showCopied) TermGreen else TermDim,
                        modifier = Modifier.padding(4.dp).size(14.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "exit: ${cmd.exitCode}",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = exitColor)
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = TermCyan
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "执行中...",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermCyan)
                )
            }
        }

        // Command output — real-time streaming (green) or final (selectable)
        if (cmd.output.isNotBlank()) {
            val outputColor = if (isRunning) TermGreen else TermText.copy(alpha = 0.85f)
            if (isRunning) {
                Text(
                    cmd.output,
                    style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = outputColor, lineHeight = 14.sp),
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                SelectionContainer {
                    Text(
                        cmd.output,
                        style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = outputColor, lineHeight = 14.sp),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

    }
}

// ═══════════════════════════════════════
//  Explanation Card (separated from result)
// ═══════════════════════════════════════

@Composable
private fun ExplanationCard(cmd: com.example.link_pi.agent.SshOrchestrator.SshCommand) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = TermCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, TermBorder.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = TermYellow,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$ ${cmd.command}",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermCyan),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            MarkdownText(
                text = cmd.explanation,
                style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermText.copy(alpha = 0.9f), lineHeight = 16.sp),
                color = TermText.copy(alpha = 0.9f),
                codeColor = TermCyan.copy(alpha = 0.7f),
                codeBgColor = TermBg,
                dividerColor = TermBorder,
                tableBgColor = TermBg,
                tableHeaderBgColor = TermCard,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ═══════════════════════════════════════
//  Loading Indicator
// ═══════════════════════════════════════

@Composable
private fun SshLoadingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = TermGreen
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "AI 正在分析...",
            style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermDim)
        )
    }
}

// ═══════════════════════════════════════
//  Input Bar (DOS-style)
// ═══════════════════════════════════════

@Composable
private fun SshInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = TermSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, TermBorder)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(
                ">_",
                style = TextStyle(fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TermGreen),
                modifier = Modifier.padding(start = 8.dp)
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                placeholder = {
                    Text(
                        if (enabled) "输入你的需求..." else "AI 处理中...",
                        style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, color = TermDim)
                    )
                },
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    cursorColor = TermGreen,
                    disabledBorderColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    disabledTextColor = TermDim
                ),
                textStyle = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, color = TermText)
            )

            val canSend = text.isNotBlank() && enabled
            IconButton(
                onClick = onSend,
                enabled = canSend
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (canSend) TermGreen else TermDim,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════
//  Manual Terminal Content (手动模式)
// ═══════════════════════════════════════

@Composable
private fun ManualTerminalContent(
    viewModel: SshViewModel,
    onBack: () -> Unit,
    onOpenSftp: () -> Unit,
    onSwitchToAi: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val serverInfo by viewModel.serverInfo.collectAsState()
    val listState = rememberLazyListState()
    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    var shortcutBarExpanded by remember { mutableStateOf(true) }

    val density = LocalDensity.current
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val bottomPadding = maxOf(imeBottomDp, navBarBottomDp)

    // Auto-scroll
    LaunchedEffect(messages.size) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    LaunchedEffect(imeBottomDp) {
        if (imeBottomDp > navBarBottomDp && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBg)
            .statusBarsPadding()
            .padding(bottom = bottomPadding)
            .pointerInput(Unit) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        if (totalDrag < -200) onSwitchToAi()
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                    onHorizontalDrag = { _, amount -> totalDrag += amount }
                )
            }
    ) {
        // ── Top Bar (no deep thinking) ──
        ManualTopBar(serverInfo, onBack, viewModel::disconnect, onOpenSftp)

        // ── Message List ──
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            serverInfo?.let { info ->
                if (info.osType.isNotBlank()) {
                    item(key = "server_info") {
                        ServerInfoCard(info)
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                when (msg.role) {
                    "user" -> UserBubble(msg.content)
                    "assistant" -> AssistantBubble(msg.content, msg.thinkingContent)
                    "system" -> SystemMessage(msg.content)
                    "result" -> {
                        ExecutionResultCard(msg.executedCommands)
                        msg.executedCommands.forEach { cmd ->
                            if (cmd.explanation.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                ExplanationCard(cmd)
                            }
                        }
                    }
                }
            }
        }

        // ── Manual Input Bar ──
        ManualInputBar(
            value = inputValue,
            onValueChange = { inputValue = it },
            onSend = {
                if (inputValue.text.isNotBlank()) {
                    viewModel.sendManualCommand(inputValue.text)
                    inputValue = TextFieldValue("")
                }
            }
        )

        // ── Shortcut Key Bar (collapsible, above keyboard) ──
        ShortcutKeyBar(
            expanded = shortcutBarExpanded,
            onToggleExpand = { shortcutBarExpanded = !shortcutBarExpanded },
            onInsertText = { text ->
                val sel = inputValue.selection
                val newText = inputValue.text.substring(0, sel.start) + text +
                        inputValue.text.substring(sel.end)
                inputValue = TextFieldValue(newText, TextRange(sel.start + text.length))
            },
            onSpecialKey = { key ->
                when (key) {
                    "UP" -> {
                        val cmd = viewModel.getHistoryCommand(true)
                        if (cmd != null) inputValue = TextFieldValue(cmd, TextRange(cmd.length))
                    }
                    "DOWN" -> {
                        val cmd = viewModel.getHistoryCommand(false)
                        if (cmd != null) inputValue = TextFieldValue(cmd, TextRange(cmd.length))
                    }
                    "ESC" -> inputValue = TextFieldValue("")
                    "TAB" -> {
                        val sel = inputValue.selection
                        val newText = inputValue.text.substring(0, sel.start) + "\t" +
                                inputValue.text.substring(sel.end)
                        inputValue = TextFieldValue(newText, TextRange(sel.start + 1))
                    }
                }
            }
        )
    }
}

// ═══════════════════════════════════════
//  Manual Mode Top Bar
// ═══════════════════════════════════════

@Composable
private fun ManualTopBar(
    serverInfo: ServerInfo?,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSftp: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermSurface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TermText)
        }
        Icon(
            Icons.Outlined.Terminal,
            contentDescription = null,
            tint = TermGreen,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SSH Terminal",
                style = TextStyle(
                    fontFamily = MonoFont,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TermGreen
                )
            )
            if (serverInfo != null) {
                Text(
                    text = "${serverInfo.username}@${serverInfo.host}",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // "手动" mode badge
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = TermYellow.copy(alpha = 0.15f)
        ) {
            Text(
                "手动",
                style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermYellow),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // SFTP button
        IconButton(onClick = onOpenSftp) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = "SFTP",
                tint = TermYellow,
                modifier = Modifier.size(20.dp)
            )
        }

        // Disconnect button
        Surface(
            onClick = onDisconnect,
            shape = RoundedCornerShape(16.dp),
            color = TermRed.copy(alpha = 0.1f)
        ) {
            Text(
                "断开",
                style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermRed),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

// ═══════════════════════════════════════
//  Manual Input Bar (direct command)
// ═══════════════════════════════════════

@Composable
private fun ManualInputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = TermSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, TermBorder)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(
                "$ ",
                style = TextStyle(fontFamily = MonoFont, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, color = TermGreen),
                modifier = Modifier.padding(start = 8.dp)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                textStyle = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, color = TermText),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(TermGreen),
                maxLines = 3,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(
                                "输入命令...",
                                style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, color = TermDim)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            val canSend = value.text.isNotBlank()
            IconButton(
                onClick = onSend,
                enabled = canSend
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "执行",
                    tint = if (canSend) TermGreen else TermDim,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════
//  Shortcut Key Bar (collapsible)
// ═══════════════════════════════════════

@Composable
private fun ShortcutKeyBar(
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onInsertText: (String) -> Unit,
    onSpecialKey: (String) -> Unit
) {
    val row1Special = listOf("↑" to "UP", "↓" to "DOWN", "Tab" to "TAB", "Esc" to "ESC")
    val row1Symbols = listOf("|", ">", "<", "&", ";", "~", "/", "\\")
    val row2 = listOf("`", "#", "$", "*", "?", "@", "!", "^", "(", ")", "[", "]", "{", "}", "=", "\"", "'", "_")

    Surface(
        color = TermSurface,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, TermBorder.copy(alpha = 0.5f))
    ) {
        Column {
            // Toggle bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = TermDim,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "快捷键",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermDim)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    // Row 1: special keys + common shell symbols
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        row1Special.forEach { (label, key) ->
                            ShortcutButton(label) { onSpecialKey(key) }
                        }
                        row1Symbols.forEach { symbol ->
                            ShortcutButton(symbol) { onInsertText(symbol) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Row 2: more symbols
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        row2.forEach { symbol ->
                            ShortcutButton(symbol) { onInsertText(symbol) }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun ShortcutButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = TermCard,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, TermBorder)
    ) {
        Text(
            label,
            style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermText),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
