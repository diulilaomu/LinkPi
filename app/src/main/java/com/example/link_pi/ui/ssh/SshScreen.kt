package com.example.link_pi.ui.ssh

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.link_pi.ui.theme.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.AttachFile
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf

import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.link_pi.agent.SshOrchestrator.CommandStatus
import com.example.link_pi.agent.SshManager
import com.example.link_pi.data.SavedServer
import com.example.link_pi.ui.common.MarkdownText
import com.example.link_pi.ui.common.RichInputBar
import com.example.link_pi.ui.common.RichInputBarStyle
import com.example.link_pi.network.ModelConfig
import com.example.link_pi.ui.sftp.SftpScreen
import com.example.link_pi.ui.sftp.SftpViewModel
import com.example.link_pi.ui.sftp.FileEditorScreen

@Composable
fun SshScreen(
    viewModel: SshViewModel,
    onBack: () -> Unit,
    onDisconnect: () -> Unit
) {
    var page by remember { mutableStateOf("ssh") }
    val sftpViewModel: SftpViewModel = viewModel()
    val sessionId by viewModel.sessionId.collectAsState()
    val isManualMode by viewModel.isManualMode.collectAsState()

    // 系统返回/边缘滑动 — 双击弹出会话切换器
    var lastBackTime by remember { mutableLongStateOf(0L) }
    var showSwitcherTop by remember { mutableStateOf(false) }
    val context = LocalContext.current
    BackHandler(enabled = page == "ssh") {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < 2000) {
            showSwitcherTop = true
        } else {
            lastBackTime = now
            Toast.makeText(context, "再滑一次打开会话列表", Toast.LENGTH_SHORT).show()
        }
    }

    // Session Switcher triggered by system back / edge swipe
    if (showSwitcherTop) {
        SessionSwitcherSheet(
            viewModel = viewModel,
            currentSessionId = sessionId,
            onDismiss = { showSwitcherTop = false },
            onExit = { showSwitcherTop = false; onBack() }
        )
    }

    val isSessionDropped by viewModel.isSessionDropped.collectAsState()
    val isReconnecting by viewModel.isReconnecting.collectAsState()

    when (page) {
        "ssh" -> {
            if (isManualMode) {
                ManualTerminalContent(
                    viewModel = viewModel,
                    onBack = onBack,
                    onDisconnect = onDisconnect,
                    isSessionDropped = isSessionDropped,
                    isReconnecting = isReconnecting,
                    onReconnect = { viewModel.reconnect() },
                    onOpenSftp = {
                        val sid = sessionId
                        if (sid != null) page = "sftp"
                    }
                )
            } else {
                SshTerminalContent(
                    viewModel = viewModel,
                    onBack = onBack,
                    onDisconnect = onDisconnect,
                    isSessionDropped = isSessionDropped,
                    isReconnecting = isReconnecting,
                    onReconnect = { viewModel.reconnect() },
                    onOpenSftp = {
                        val sid = sessionId
                        if (sid != null) page = "sftp"
                    }
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
    onDisconnect: () -> Unit,
    isSessionDropped: Boolean,
    isReconnecting: Boolean,
    onReconnect: () -> Unit,
    onOpenSftp: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val serverInfo by viewModel.serverInfo.collectAsState()
    val pendingCommands by viewModel.pendingCommands.collectAsState()
    val deepThinking by viewModel.deepThinking.collectAsState()
    val isManualMode by viewModel.isManualMode.collectAsState()
    val models by viewModel.models.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    val pendingAttachments by viewModel.pendingAttachments.collectAsState()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addAttachment(it) }
    }

    val context = LocalContext.current
    // Double horizontal-swipe to show session switcher
    var lastSwipeTime by remember { mutableLongStateOf(0L) }
    var showSwitcher by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val bottomPadding = maxOf(imeBottomDp, navBarBottomDp)

    // Auto-scroll
    LaunchedEffect(messages.size) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, scrollOffset = Int.MAX_VALUE)
        }
    }

    // 键盘弹起时自动滚动到底部
    LaunchedEffect(imeBottomDp) {
        if (imeBottomDp > navBarBottomDp && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, scrollOffset = Int.MAX_VALUE)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBg)
            .statusBarsPadding()
            .padding(bottom = bottomPadding)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (kotlin.math.abs(dragAmount) > 30f) {
                        val now = System.currentTimeMillis()
                        if (now - lastSwipeTime < 2000) {
                            showSwitcher = true
                        } else {
                            lastSwipeTime = now
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, "再滑一次打开会话列表", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
    ) {
        // ── Top Bar ──
        SshTopBar(serverInfo, isManualMode, onBack, { viewModel.setManualMode(!isManualMode) }, onDisconnect, isSessionDropped, isReconnecting, onReconnect, onOpenSftp)

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
        RichInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            onSend = {
                viewModel.sendMessage(inputText)
                inputText = ""
            },
            enabled = !isLoading,
            placeholder = "输入你的需求...",
            disabledPlaceholder = "AI 处理中...",
            maxLines = 3,
            models = models,
            activeModelId = activeModelId,
            onSwitchModel = viewModel::switchModel,
            showModelMenu = showModelMenu,
            onToggleModelMenu = { showModelMenu = !showModelMenu },
            deepThinking = deepThinking,
            onToggleThinking = viewModel::toggleDeepThinking,
            pendingAttachments = pendingAttachments,
            onRemoveAttachment = viewModel::removeAttachment,
            onPickFile = {
                filePickerLauncher.launch(arrayOf(
                    "text/plain", "text/markdown", "text/x-markdown",
                    "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp"
                ))
            },
            style = RichInputBarStyle.Terminal,
            leadingContent = {
                Text(
                    ">_",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TermGreen),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        )
    }

    // Session Switcher Dialog
    if (showSwitcher) {
        SessionSwitcherSheet(
            viewModel = viewModel,
            currentSessionId = viewModel.sessionId.collectAsState().value,
            onDismiss = { showSwitcher = false },
            onExit = { showSwitcher = false; onBack() }
        )
    }
}

// ═══════════════════════════════════════
//  Session Switcher Dialog
// ═══════════════════════════════════════

@Composable
private fun SessionSwitcherSheet(
    viewModel: SshViewModel,
    currentSessionId: String?,
    onDismiss: () -> Unit,
    onExit: () -> Unit
) {
    val savedServers by viewModel.savedServers.collectAsState()
    val switcherConnecting by viewModel.switcherConnecting.collectAsState()
    val switcherError by viewModel.switcherError.collectAsState()

    // Refresh data on open
    LaunchedEffect(Unit) { viewModel.refreshSavedServers() }
    val activeSessions = remember { viewModel.fetchActiveSessions() }

    // Auto-dismiss when session switches (after connectAndSwitch succeeds)
    val liveSessionId by viewModel.sessionId.collectAsState()
    LaunchedEffect(liveSessionId) {
        if (liveSessionId != null && liveSessionId != currentSessionId && switcherConnecting == null) {
            onDismiss()
        }
    }
    // Map sessionId → SshSessionInfo for quick lookup
    val activeSessionMap = remember(activeSessions) {
        activeSessions.associateBy { "${it.host}:${it.port}:${it.username}" }
    }
    val activeSessionIds = remember(activeSessions) { activeSessions.map { it.sessionId }.toSet() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = TermSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, TermBorder),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 480.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = TermGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "SSH 会话",
                        style = TextStyle(fontFamily = MonoFont, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TermGreen)
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "关闭",
                            tint = TermDim,
                            modifier = Modifier.size(20.dp).padding(2.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Error message
                switcherError?.let { err ->
                    Text(
                        text = err,
                        style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TermRed.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Server list
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (savedServers.isEmpty() && activeSessions.isEmpty()) {
                        Text(
                            "暂无已保存的服务器",
                            style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermDim),
                            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    // Active sessions (not from saved servers)
                    val savedServerKeys = savedServers.map { "${it.host}:${it.port}" }.toSet()
                    val orphanSessions = activeSessions.filter { "${it.host}:${it.port}" !in savedServerKeys }
                    if (orphanSessions.isNotEmpty()) {
                        Text(
                            "活跃会话",
                            style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TermYellow)
                        )
                        orphanSessions.forEach { session ->
                            SessionServerRow(
                                label = "${session.username}@${session.host}:${session.port}",
                                isConnected = true,
                                isCurrent = session.sessionId == currentSessionId,
                                isConnecting = false,
                                onClick = {
                                    if (session.sessionId != currentSessionId) {
                                        viewModel.switchToSession(session.sessionId)
                                        onDismiss()
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    // Saved servers
                    if (savedServers.isNotEmpty()) {
                        Text(
                            "服务器列表",
                            style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TermYellow)
                        )
                        savedServers.forEach { server ->
                            val key = "${server.host}:${server.port}:${server.credentialName}"
                            // Find if this server has an active session
                            val matchedSession = activeSessions.find {
                                it.host == server.host && it.port == server.port
                            }
                            val isConn = matchedSession != null
                            val isCurr = matchedSession?.sessionId == currentSessionId && isConn
                            val isLoading = switcherConnecting == server.id

                            SessionServerRow(
                                label = if (server.name.isNotBlank()) server.name else "${server.host}:${server.port}",
                                subtitle = if (server.name.isNotBlank()) "${server.credentialName}@${server.host}:${server.port}" else server.credentialName.ifBlank { null },
                                isConnected = isConn,
                                isCurrent = isCurr,
                                isConnecting = isLoading,
                                onClick = {
                                    if (isCurr) return@SessionServerRow
                                    if (matchedSession != null) {
                                        viewModel.switchToSession(matchedSession.sessionId)
                                        onDismiss()
                                    } else {
                                        viewModel.connectAndSwitch(server)
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Exit button
                Surface(
                    onClick = onExit,
                    shape = RoundedCornerShape(12.dp),
                    color = TermRed.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "返回上一级",
                        style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, color = TermRed, fontWeight = FontWeight.Medium),
                        modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionServerRow(
    label: String,
    subtitle: String? = null,
    isConnected: Boolean,
    isCurrent: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isCurrent) TermGreen.copy(alpha = 0.1f) else TermCard,
        border = if (isCurrent) androidx.compose.foundation.BorderStroke(1.dp, TermGreen.copy(alpha = 0.4f)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, color = TermText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermDim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = TermYellow
                )
            } else if (isCurrent) {
                Text(
                    "当前",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermGreen, fontWeight = FontWeight.Bold)
                )
            } else if (isConnected) {
                Text(
                    "已连接",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermCyan)
                )
            }
        }
    }
}

// ═══════════════════════════════════════
//  Connection Status Button (shared)
// ═══════════════════════════════════════

@Composable
private fun ConnectionStatusButton(
    isSessionDropped: Boolean,
    isReconnecting: Boolean,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    if (isSessionDropped) {
        Surface(
            onClick = onReconnect,
            shape = RoundedCornerShape(16.dp),
            color = TermYellow.copy(alpha = 0.15f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isReconnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = TermYellow
                    )
                }
                Text(
                    if (isReconnecting) "重连中" else "重连",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermYellow)
                )
            }
        }
    } else {
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
//  Top Bar
// ═══════════════════════════════════════

@Composable
private fun SshTopBar(
    serverInfo: ServerInfo?,
    isManualMode: Boolean,
    onBack: () -> Unit,
    onToggleMode: () -> Unit,
    onDisconnect: () -> Unit,
    isSessionDropped: Boolean,
    isReconnecting: Boolean,
    onReconnect: () -> Unit,
    onOpenSftp: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

        // AI/Manual Mode Toggle
        Surface(
            onClick = onToggleMode,
            shape = RoundedCornerShape(16.dp),
            color = if (isManualMode) TermYellow.copy(alpha = 0.15f) else TermCyan.copy(alpha = 0.15f)
        ) {
            Text(
                if (isManualMode) "辅助" else "手动",
                style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp,
                    color = if (isManualMode) TermYellow else TermCyan),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        ConnectionStatusButton(isSessionDropped, isReconnecting, onReconnect, onDisconnect)
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
        shape = RoundedCornerShape(20.dp),
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
//  Manual Terminal Content (手动模式 · PuTTY Style)
// ═══════════════════════════════════════

@Composable
private fun ManualTerminalContent(
    viewModel: SshViewModel,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    isSessionDropped: Boolean,
    isReconnecting: Boolean,
    onReconnect: () -> Unit,
    onOpenSftp: () -> Unit
) {
    val terminalOutput by viewModel.terminalOutput.collectAsState()
    val serverInfo by viewModel.serverInfo.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var activeModifiers by remember { mutableStateOf(setOf<String>()) }
    val context = LocalContext.current

    // Double horizontal-swipe to show session switcher
    var lastSwipeTime by remember { mutableLongStateOf(0L) }
    var showSwitcher by remember { mutableStateOf(false) }

    // Cursor blink: blink in alternate screen (vim/nano), steady in normal terminal
    val isAltScreen by viewModel.isAlternateScreen.collectAsState()
    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(isAltScreen) {
        if (isAltScreen) {
            while (true) {
                delay(530)
                cursorVisible = !cursorVisible
            }
        } else {
            cursorVisible = true  // Steady cursor in normal terminal
        }
    }
    // Reset blink phase on new output
    LaunchedEffect(terminalOutput) {
        if (isAltScreen) cursorVisible = true
    }
    val displayOutput = remember(terminalOutput, cursorVisible) {
        viewModel.renderOutput(cursorVisible)
    }
    // Status line (vim bottom bar) — rendered separately
    val statusLine by viewModel.statusLine.collectAsState()
    val displayStatus = remember(statusLine, cursorVisible) {
        viewModel.renderStatusOutput(cursorVisible)
    }
    val scrollState = rememberScrollState()
    val hScrollState = rememberScrollState()

    // Padding chars — always kept in hidden field so backspace has something to delete
    val pad = "\u200B\u200B\u200B\u200B" // 4 zero-width spaces
    var hiddenInput by remember { mutableStateOf(TextFieldValue(pad, TextRange(pad.length))) }

    val density = LocalDensity.current
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val bottomPadding = maxOf(imeBottomDp, navBarBottomDp)

    // Terminal font size and column measurement
    val textMeasurer = rememberTextMeasurer()
    val baseFontSizeSp = 8f
    val termPaddingPx = with(density) { 6.dp.toPx() * 2 }

    // Dynamic font size: use smaller font in alt screen (TUI mode)
    val effectiveFontSizeSp = if (isAltScreen) 7f else baseFontSizeSp

    val charWidthPx = remember(density, effectiveFontSizeSp) {
        val sample = 80
        textMeasurer.measure(
            "M".repeat(sample),
            style = TextStyle(fontFamily = MonoFont, fontSize = effectiveFontSizeSp.sp),
            maxLines = 1
        ).size.width.toFloat() / sample
    }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val termCols = remember(containerWidthPx, charWidthPx) {
        if (containerWidthPx > 0 && charWidthPx > 0f) {
            ((containerWidthPx - termPaddingPx) / charWidthPx).toInt().coerceIn(20, 300)
        } else 80
    }
    val termRows = 40

    LaunchedEffect(termCols) {
        viewModel.updateTerminalSize(termCols, termRows)
    }

    // When leaving alternate screen (vim/nano exit), scroll to bottom
    LaunchedEffect(isAltScreen) {
        if (!isAltScreen) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    // Auto-scroll to bottom when output changes — but only when:
    // 1. Not in alternate screen (vim/nano manage their own viewport)
    // 2. User was already near the bottom (don't yank away if scrolled up)
    LaunchedEffect(terminalOutput) {
        if (!isAltScreen) {
            val nearBottom = scrollState.maxValue - scrollState.value < 200
            if (nearBottom) {
                scrollState.scrollTo(scrollState.maxValue)
            }
        }
    }
    LaunchedEffect(imeBottomDp) {
        if (imeBottomDp > navBarBottomDp && !isAltScreen) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Process modifier+key combo — send control chars directly to shell
    fun processCombo(mods: Set<String>, key: String) {
        val hasCtrl = "CTRL" in mods
        when {
            hasCtrl && key.length == 1 && key[0].isLetter() -> {
                viewModel.sendControlChar(key.uppercase()[0])
            }
            hasCtrl && key == "↑" -> scope.launch { scrollState.scrollTo(0) }
            hasCtrl && key == "↓" -> scope.launch { scrollState.scrollTo(scrollState.maxValue) }
        }
    }

    // Handle direct key press — send escape sequences to shell
    fun processDirectKey(key: String) {
        when (key) {
            "ESC"  -> {
                viewModel.sendEscape()
            }
            "TAB"  -> viewModel.sendRawToShell("\t")
            "/", "|", "-", ":" -> viewModel.sendRawToShell(key)
            "HOME" -> viewModel.sendRawToShell("\u001b[H")
            "END"  -> viewModel.sendRawToShell("\u001b[F")
            "↑"    -> viewModel.sendRawToShell("\u001b[A")
            "↓"    -> viewModel.sendRawToShell("\u001b[B")
            "←"    -> viewModel.sendRawToShell("\u001b[D")
            "→"    -> viewModel.sendRawToShell("\u001b[C")
            "F1"   -> viewModel.sendRawToShell("\u001bOP")
            "F2"   -> viewModel.sendRawToShell("\u001bOQ")
            "F3"   -> viewModel.sendRawToShell("\u001bOR")
            "F4"   -> viewModel.sendRawToShell("\u001bOS")
            "F5"   -> viewModel.sendRawToShell("\u001b[15~")
            "F6"   -> viewModel.sendRawToShell("\u001b[17~")
            "F7"   -> viewModel.sendRawToShell("\u001b[18~")
            "F8"   -> viewModel.sendRawToShell("\u001b[19~")
            "F9"   -> viewModel.sendRawToShell("\u001b[20~")
            "F10"  -> viewModel.sendRawToShell("\u001b[21~")
            "F11"  -> viewModel.sendRawToShell("\u001b[23~")
            "F12"  -> viewModel.sendRawToShell("\u001b[24~")
            "PGUP" -> {
                if (isAltScreen) viewModel.sendRawToShell("\u001b[5~")
                else scope.launch { scrollState.animateScrollTo((scrollState.value - 500).coerceAtLeast(0)) }
            }
            "PGDN" -> {
                if (isAltScreen) viewModel.sendRawToShell("\u001b[6~")
                else scope.launch { scrollState.animateScrollTo((scrollState.value + 500).coerceAtMost(scrollState.maxValue)) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBg)
            .statusBarsPadding()
            .padding(bottom = bottomPadding)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (kotlin.math.abs(dragAmount) > 30f) {
                        val now = System.currentTimeMillis()
                        if (now - lastSwipeTime < 2000) {
                            showSwitcher = true
                        } else {
                            lastSwipeTime = now
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, "再滑一次打开会话列表", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
    ) {
        // ── Minimal Top Bar ──
        ManualTopBar(
            serverInfo = serverInfo,
            isModelConfigured = viewModel.isModelConfigured,
            onSwitchToAssist = {
                if (viewModel.isModelConfigured) {
                    viewModel.setManualMode(false)
                } else {
                    Toast.makeText(context, "模型未设置或网络异常", Toast.LENGTH_SHORT).show()
                }
            },
            onDisconnect = onDisconnect,
            isSessionDropped = isSessionDropped,
            isReconnecting = isReconnecting,
            onReconnect = onReconnect,
            onOpenSftp = onOpenSftp
        )

        // ── Terminal Output with blinking cursor ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { containerWidthPx = it.width }
                .horizontalScroll(hScrollState)
                .verticalScroll(scrollState)
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                }
        ) {
            SelectionContainer {
                Text(
                    text = displayOutput,
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = TextStyle(fontFamily = MonoFont, fontSize = effectiveFontSizeSp.sp, color = TermText),
                    softWrap = false
                )
            }
        }

        // ── Hidden input field (captures keyboard, invisible) ──
        BasicTextField(
            value = hiddenInput,
            onValueChange = { newValue ->
                // During IME composition, just update the field but don't
                // send anything to the shell — wait for the commit.
                if (newValue.composition != null) {
                    hiddenInput = newValue
                    return@BasicTextField
                }

                // Extract real (non-padding) characters from old and new text
                val oldReal = hiddenInput.text.replace("\u200B", "")
                val newReal = newValue.text.replace("\u200B", "")

                // Detect modifier combo from keyboard
                if (activeModifiers.isNotEmpty() && newReal.length > oldReal.length) {
                    val added = newReal.substring(oldReal.length)
                    added.forEach { ch -> processCombo(activeModifiers, ch.toString()) }
                    activeModifiers = emptySet()
                    hiddenInput = TextFieldValue(pad, TextRange(pad.length))
                    return@BasicTextField
                }

                when {
                    // New real characters typed (includes space, punctuation, etc.)
                    newReal.length > oldReal.length -> {
                        val added = newReal.substring(oldReal.length).replace("\n", "\r")
                        if (added.isNotEmpty()) {
                            viewModel.sendRawToShell(added)
                        }
                    }
                    // Same real length but content changed (IME replaced chars)
                    newReal.length == oldReal.length && newReal != oldReal -> {
                        // Find the differing portion and send it
                        val diff = newReal.replace("\n", "\r")
                        if (diff.isNotEmpty()) {
                            viewModel.sendRawToShell(diff)
                        }
                    }
                    // Real characters deleted = backspace
                    newReal.length < oldReal.length -> {
                        val count = oldReal.length - newReal.length
                        repeat(count) { viewModel.sendRawToShell("\u007F") }
                    }
                }

                // Detect backspace consumed padding (no real char change but total length decreased)
                if (newReal.length == oldReal.length && newReal == oldReal) {
                    val totalOld = hiddenInput.text.length
                    val totalNew = newValue.text.length
                    if (totalNew < totalOld) {
                        val count = totalOld - totalNew
                        repeat(count) { viewModel.sendRawToShell("\u007F") }
                    }
                }

                // Always reset to pad after processing committed input
                hiddenInput = TextFieldValue(pad, TextRange(pad.length))
            },
            modifier = Modifier
                .height(1.dp)
                .fillMaxWidth()
                .focusRequester(focusRequester),
            textStyle = TextStyle(fontSize = 1.sp, color = Color.Transparent),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    viewModel.sendRawToShell("\r")
                    hiddenInput = TextFieldValue(pad, TextRange(pad.length))
                }
            )
        )

        // ── Modifier indicator ──
        if (activeModifiers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermSurface)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    activeModifiers.sorted().joinToString("+"),
                    style = TextStyle(fontFamily = MonoFont, fontSize = 6.sp, color = TermYellow)
                )
            }
        }

        // ── Vim Status Bar (shown in alternate screen mode) ──
        if (isAltScreen && displayStatus.isNotEmpty()) {
            val statusFontSizeSp = effectiveFontSizeSp * 0.75f  // status bar slightly smaller
            Surface(color = TermSurface) {
                Text(
                    text = displayStatus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(hScrollState)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = TextStyle(fontFamily = MonoFont, fontSize = statusFontSizeSp.sp, color = TermText),
                    maxLines = 2
                )
            }
        }

        // ── Terminal Shortcut Bar ──
        TerminalShortcutBar(
            activeModifiers = activeModifiers,
            onModifierToggle = { mod ->
                activeModifiers = if (mod in activeModifiers) activeModifiers - mod else activeModifiers + mod
            },
            onKeyPress = { key ->
                if (activeModifiers.isNotEmpty()) {
                    processCombo(activeModifiers, key)
                    activeModifiers = emptySet()
                } else {
                    processDirectKey(key)
                }
            },
            onToggleKeyboard = {
                if (imeBottomDp > navBarBottomDp) {
                    focusManager.clearFocus()
                } else {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
            }
        )
    }

    // Session Switcher Dialog
    if (showSwitcher) {
        SessionSwitcherSheet(
            viewModel = viewModel,
            currentSessionId = viewModel.sessionId.collectAsState().value,
            onDismiss = { showSwitcher = false },
            onExit = { showSwitcher = false; onBack() }
        )
    }
}

// ═══════════════════════════════════════
//  Manual Mode Top Bar
// ═══════════════════════════════════════

@Composable
private fun ManualTopBar(
    serverInfo: ServerInfo?,
    isModelConfigured: Boolean,
    onSwitchToAssist: () -> Unit,
    onDisconnect: () -> Unit,
    isSessionDropped: Boolean,
    isReconnecting: Boolean,
    onReconnect: () -> Unit,
    onOpenSftp: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

        // "辅助" mode switch badge
        Surface(
            onClick = onSwitchToAssist,
            shape = RoundedCornerShape(16.dp),
            color = if (isModelConfigured) TermGreen.copy(alpha = 0.15f) else TermDim.copy(alpha = 0.15f)
        ) {
            Text(
                "辅助",
                style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp,
                    color = if (isModelConfigured) TermGreen else TermDim),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        ConnectionStatusButton(isSessionDropped, isReconnecting, onReconnect, onDisconnect)
    }
}

// ═══════════════════════════════════════
//  Terminal Shortcut Bar (Termux-style)
// ═══════════════════════════════════════

@Composable
private fun TerminalShortcutBar(
    activeModifiers: Set<String>,
    onModifierToggle: (String) -> Unit,
    onKeyPress: (String) -> Unit,
    onToggleKeyboard: () -> Unit
) {
    val isFnActive = "FN" in activeModifiers
    val row1 = if (isFnActive)
        listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "←")
    else
        listOf("ESC", "/", "|", "-", "↑", "HOME", "END", "PGUP", "FN")
    val row2 = if (isFnActive)
        listOf("F9", "F10", "F11", "F12", "", "", "", "", "⌨")
    else
        listOf("TAB", "CTRL", "ALT", "←", "↓", "→", "PGDN", ":", "⌨")
    val modifierKeys = setOf("CTRL", "ALT", "FN")

    Surface(color = TermSurface) {
        Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                row1.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(Modifier.weight(1f))
                    } else if (isFnActive && key == "←") {
                        // Back arrow exits FN mode
                        TermKeyButton(
                            label = "←",
                            isActive = true,
                            modifier = Modifier.weight(1f),
                            onClick = { onModifierToggle("FN") }
                        )
                    } else {
                        val isModifier = key in modifierKeys
                        val isActive = key in activeModifiers
                        TermKeyButton(
                            label = key,
                            isActive = isActive,
                            modifier = Modifier.weight(1f),
                            onClick = { if (isModifier) onModifierToggle(key) else onKeyPress(key) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                row2.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val isModifier = key in modifierKeys
                        val isActive = key in activeModifiers
                        TermKeyButton(
                            label = key,
                            isActive = isActive,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                when {
                                    key == "⌨" -> onToggleKeyboard()
                                    isModifier -> onModifierToggle(key)
                                    else -> onKeyPress(key)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TermKeyButton(
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .background(
                if (isActive) TermGreen.copy(alpha = 0.25f) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = MonoFont,
                fontSize = 10.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) TermGreen else TermText
            ),
            maxLines = 1
        )
    }
}
