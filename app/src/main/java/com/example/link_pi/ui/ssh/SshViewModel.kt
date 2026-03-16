package com.example.link_pi.ui.ssh

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.link_pi.agent.SshManager
import com.example.link_pi.agent.SshOrchestrator
import com.example.link_pi.agent.SshOrchestrator.CommandStatus
import com.example.link_pi.agent.SshOrchestrator.SshCommand
import com.example.link_pi.data.CredentialStorage
import com.example.link_pi.data.SavedServer
import com.example.link_pi.data.SavedServerStorage
import com.example.link_pi.data.SessionRegistry
import com.example.link_pi.data.model.Attachment
import com.example.link_pi.data.model.ManagedSession
import com.example.link_pi.data.model.SessionSource
import com.example.link_pi.data.model.SessionType
import com.example.link_pi.network.AiConfig
import com.example.link_pi.network.AiService
import com.example.link_pi.network.ModelConfig
import com.example.link_pi.ui.theme.TermBg
import com.example.link_pi.ui.theme.TermText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A single message in the SSH terminal conversation. */
data class SshChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,           // "user", "assistant", "system", "result"
    val content: String,
    val commands: List<SshCommand> = emptyList(),
    val thinkingContent: String = "",
    val executedCommands: List<SshCommand> = emptyList()  // for role="result"
)

/** Server info collected after entering SSH mode. */
data class ServerInfo(
    val host: String,
    val port: Int,
    val username: String,
    val osType: String = "",
    val osVersion: String = "",
    val hostname: String = "",
    val kernel: String = ""
)

class SshViewModel(application: Application) : AndroidViewModel(application) {

    private val aiConfig = AiConfig(application)
    private val aiService = AiService(aiConfig)
    val sshManager = SshManager.getInstance(application)
    private val orchestrator = SshOrchestrator(aiService, sshManager)
    private val sessionRegistry = SessionRegistry.getInstance(application)
    private val serverStorage = SavedServerStorage(application)
    private val credentialStorage = CredentialStorage(application)

    // Track whether SSH_ASSIST session has been registered
    private var sshSessionRegistered = false

    // ── Session State ──
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // ── Chat State ──
    private val _messages = MutableStateFlow<List<SshChatMessage>>(emptyList())
    val messages: StateFlow<List<SshChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── Command State ──
    private val _pendingCommands = MutableStateFlow<List<SshCommand>>(emptyList())
    val pendingCommands: StateFlow<List<SshCommand>> = _pendingCommands.asStateFlow()

    private val _executionResults = MutableStateFlow<List<SshCommand>>(emptyList())
    val executionResults: StateFlow<List<SshCommand>> = _executionResults.asStateFlow()

    private val _deepThinking = MutableStateFlow(aiConfig.activeModel.enableThinking)
    val deepThinking: StateFlow<Boolean> = _deepThinking.asStateFlow()

    private val _models = MutableStateFlow(aiConfig.getModels())
    val models: StateFlow<List<ModelConfig>> = _models.asStateFlow()

    private val _activeModelId = MutableStateFlow(aiConfig.activeModelId)
    val activeModelId: StateFlow<String> = _activeModelId.asStateFlow()

    val isModelConfigured: Boolean get() = aiConfig.isConfigured

    // ── Saved Servers (for session switcher) ──
    private val _savedServers = MutableStateFlow<List<SavedServer>>(emptyList())
    val savedServers: StateFlow<List<SavedServer>> = _savedServers.asStateFlow()

    private val _switcherConnecting = MutableStateFlow<String?>(null)
    val switcherConnecting: StateFlow<String?> = _switcherConnecting.asStateFlow()

    private val _switcherError = MutableStateFlow<String?>(null)
    val switcherError: StateFlow<String?> = _switcherError.asStateFlow()

    // ── Attachments ──
    private val _pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<Attachment>> = _pendingAttachments.asStateFlow()

    // ── Manual Mode State ──
    private val _isManualMode = MutableStateFlow(false)
    val isManualMode: StateFlow<Boolean> = _isManualMode.asStateFlow()
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    // ── Shell Terminal State ──
    private val _terminalOutput = MutableStateFlow(AnnotatedString(""))
    val terminalOutput: StateFlow<AnnotatedString> = _terminalOutput.asStateFlow()
    private var shellReadJob: Job? = null
    private var savedMainBuffer: ScreenBuffer? = null  // alternate screen buffer backup
    private val screenBuffer = ScreenBuffer()
    private val bufferLock = Any()   // guards all screenBuffer / savedMainBuffer access
    private var escapeResidue = ""   // incomplete escape sequence from previous TCP read
    private var ptyCols = 80
    private var ptyRows = 40
    private val _isAlternateScreen = MutableStateFlow(false)
    val isAlternateScreen: StateFlow<Boolean> = _isAlternateScreen.asStateFlow()
    private val _cursorShouldBlink = MutableStateFlow(false)
    val cursorShouldBlink: StateFlow<Boolean> = _cursorShouldBlink.asStateFlow()
    private val _statusLine = MutableStateFlow(AnnotatedString(""))
    val statusLine: StateFlow<AnnotatedString> = _statusLine.asStateFlow()

    // ── Session Drop & Reconnect ──
    private val _isSessionDropped = MutableStateFlow(false)
    val isSessionDropped: StateFlow<Boolean> = _isSessionDropped.asStateFlow()
    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()
    private var reconnectJob: Job? = null
    private var reconnectPassword: String? = null  // kept in memory for auto-reconnect

    // ── Input Buffer ──
    // Coalesces rapid keystrokes into batched writes, preventing character loss on slow networks.
    private sealed class ShellInput {
        data class Text(val data: String) : ShellInput()
        data class Bytes(val data: ByteArray) : ShellInput()
    }
    private val inputChannel = Channel<ShellInput>(Channel.UNLIMITED)
    private var inputBufferJob: Job? = null

    private fun ensureInputBuffer() {
        if (inputBufferJob?.isActive == true) return
        inputBufferJob = viewModelScope.launch(Dispatchers.IO) {
            val textBuf = StringBuilder()
            inputChannel.consumeEach { input ->
                when (input) {
                    is ShellInput.Text -> textBuf.append(input.data)
                    is ShellInput.Bytes -> {
                        // Flush any pending text first, then send raw bytes immediately
                        if (textBuf.isNotEmpty()) {
                            flushTextBuffer(textBuf.toString())
                            textBuf.clear()
                        }
                        flushBytesBuffer(input.data)
                    }
                }
                // Drain all pending items before flushing (coalescing)
                while (true) {
                    when (val next = inputChannel.tryReceive().getOrNull() ?: break) {
                        is ShellInput.Text -> textBuf.append(next.data)
                        is ShellInput.Bytes -> {
                            if (textBuf.isNotEmpty()) {
                                flushTextBuffer(textBuf.toString())
                                textBuf.clear()
                            }
                            flushBytesBuffer(next.data)
                        }
                    }
                }
                // Flush remaining text
                if (textBuf.isNotEmpty()) {
                    flushTextBuffer(textBuf.toString())
                    textBuf.clear()
                }
            }
        }
    }

    private fun flushTextBuffer(text: String) {
        val sid = _sessionId.value ?: return
        sshManager.writeToShellWithRetry(sid, text)
    }

    private fun flushBytesBuffer(data: ByteArray) {
        val sid = _sessionId.value ?: return
        sshManager.writeToShellWithRetry(sid, data)
    }

    /** Enter SSH mode with a given session ID. Probes server info. */
    fun enterSession(sshSessionId: String) {
        // Reload model config from disk to stay in sync with main chat's model selection
        aiConfig.reloadModels()
        _deepThinking.value = aiConfig.activeModel.enableThinking
        _models.value = aiConfig.getModels()
        _activeModelId.value = aiConfig.activeModelId

        // If re-entering the same active session, preserve assist mode messages
        val isSameSession = sshSessionId == _sessionId.value && _isConnected.value

        _sessionId.value = sshSessionId
        _isConnected.value = true
        _isSessionDropped.value = false
        _isReconnecting.value = false
        reconnectJob?.cancel()
        if (!isSameSession) {
            sshSessionRegistered = false
            _messages.value = emptyList()
            _pendingCommands.value = emptyList()
            _executionResults.value = emptyList()
        }

        val info = sshManager.getSessionInfo(sshSessionId)
        if (info != null) {
            _serverInfo.value = ServerInfo(info.host, info.port, info.username)
            KeepAliveService.addClient(getApplication(), "ssh", "SSH: ${info.host}")
        }

        // Probe server info in background (skip if re-entering same session)
        if (!isSameSession) {
            viewModelScope.launch {
                probeServerInfo(sshSessionId)
            }
        }
    }

    /** Probe OS info from the connected server. */
    private suspend fun probeServerInfo(sid: String) {
        withContext(Dispatchers.IO) {
            try {
                val unameResult = sshManager.exec(sid, "uname -a", 10_000)
                val hostnameResult = sshManager.exec(sid, "hostname", 10_000)
                val osReleaseResult = sshManager.exec(sid, "cat /etc/os-release 2>/dev/null | head -5", 10_000)

                val uname = extractStdout(unameResult)
                val hostname = extractStdout(hostnameResult)
                val osRelease = extractStdout(osReleaseResult)

                // Parse OS info
                var osType = "Linux"
                var osVersion = ""
                var kernel = uname

                if (uname.contains("Darwin", ignoreCase = true)) osType = "macOS"
                else if (uname.contains("MINGW", ignoreCase = true) || uname.contains("MSYS", ignoreCase = true)) osType = "Windows"

                // Parse /etc/os-release for distro name
                val prettyName = Regex("""PRETTY_NAME="?([^"\n]+)"?""").find(osRelease)?.groupValues?.get(1)
                if (prettyName != null) {
                    osType = prettyName
                    val versionId = Regex("""VERSION_ID="?([^"\n]+)"?""").find(osRelease)?.groupValues?.get(1)
                    if (versionId != null) osVersion = versionId
                }

                _serverInfo.update { current ->
                    current?.copy(
                        osType = osType,
                        osVersion = osVersion,
                        hostname = hostname.trim(),
                        kernel = kernel.trim()
                    )
                }

                // Add system welcome message
                val info = _serverInfo.value
                val welcomeMsg = SshChatMessage(
                    role = "system",
                    content = "SSH 会话已建立，服务器信息已获取。输入你的需求，AI 将生成对应的 Shell 命令。"
                )
                _messages.update { it + welcomeMsg }
            } catch (_: Exception) {
                _messages.update { it + SshChatMessage(role = "system", content = "已连接，但无法自动获取服务器信息。") }
            }
        }
    }

    fun addAttachment(uri: Uri) {
        val ctx = getApplication<Application>()
        val cr = ctx.contentResolver
        val mimeType = cr.getType(uri) ?: return
        val name = cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: uri.lastPathSegment ?: "file"
        val ext = name.substringAfterLast('.', "").lowercase()
        val allowedText = setOf("md", "txt")
        val allowedImage = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        if (ext !in allowedText && ext !in allowedImage && !mimeType.startsWith("image/")) return
        val attachment = if (mimeType.startsWith("image/") || ext in allowedImage) {
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (bytes.size > 4 * 1024 * 1024) return
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Attachment(name, mimeType, base64Data = "data:$mimeType;base64,$b64")
        } else {
            val text = cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            if (text.length > 100_000) return
            Attachment(name, mimeType, textContent = text)
        }
        _pendingAttachments.update { it + attachment }
    }

    fun removeAttachment(index: Int) {
        _pendingAttachments.update { list -> list.filterIndexed { i, _ -> i != index } }
    }

    /** Send a user message to the SSH AI. */
    fun sendMessage(text: String) {
        if (text.isBlank() && _pendingAttachments.value.isEmpty()) return
        if (_isLoading.value) return

        // Check if session is paused
        val sid = _sessionId.value
        if (sid != null && sessionRegistry.isPaused(sid)) return

        // Lazy session registration: register on first AI message
        if (!sshSessionRegistered && sid != null) {
            val info = _serverInfo.value
            sessionRegistry.register(
                ManagedSession(
                    id = sid,
                    type = SessionType.SSH_ASSIST,
                    label = "${info?.host ?: "SSH"} 辅助",
                    source = SessionSource.FROM_SSH,
                    modelId = _activeModelId.value,
                    messageCount = 1,
                    metadata = buildMap {
                        info?.host?.let { put("sshHost", "${it}:${info.port}") }
                    }
                )
            )
            sshSessionRegistered = true
        }

        val attachments = _pendingAttachments.value.toList()
        _pendingAttachments.value = emptyList()

        // Build content with attachments
        val contentParts = buildString {
            if (text.isNotBlank()) append(text)
            attachments.forEach { att ->
                if (att.textContent != null) {
                    if (isNotEmpty()) append("\n\n")
                    append("[附件: ${att.name}]\n${att.textContent}")
                }
            }
        }

        val userMsg = SshChatMessage(role = "user", content = contentParts)
        _messages.update { it + userMsg }
        _pendingCommands.value = emptyList()
        _executionResults.value = emptyList()

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val apiMessages = _messages.value
                    .filter { it.role != "system" && it.role != "result" }
                    .map { mapOf("role" to it.role, "content" to it.content) }

                val response = orchestrator.chat(
                    messages = apiMessages,
                    serverContext = buildServerContext(),
                    enableThinking = _deepThinking.value
                )

                val aiMsg = SshChatMessage(
                    role = "assistant",
                    content = response.text,
                    commands = response.commands,
                    thinkingContent = response.thinkingContent
                )
                _messages.update { it + aiMsg }

                if (response.commands.isNotEmpty()) {
                    _pendingCommands.value = response.commands
                }
            } catch (e: Exception) {
                _messages.update { it + SshChatMessage(role = "system", content = "请求失败: ${e.message}") }
            } finally {
                _isLoading.value = false
            }
        }
    }

    // TUI programs that need interactive PTY (alternate screen)
    private val tuiCommands = setOf(
        "vim", "vi", "nvim", "nano", "emacs", "pico", "mcedit",
        "htop", "btop", "top", "atop", "iftop", "iotop", "nmon", "glances",
        "less", "more", "most",
        "tmux", "screen", "byobu",
        "mc", "ranger", "nnn", "lf",
        "cfdisk", "cgdisk", "nmtui", "dialog", "whiptail",
        "mutt", "neomutt", "alpine",
        "w3m", "lynx", "links",
        "man", "info"
    )

    /** Check if a command invokes a TUI program. */
    private fun isTuiCommand(command: String): Boolean {
        // Extract the base command (handle sudo, env, pipes etc.)
        val stripped = command.trim()
            .removePrefix("sudo ").trim()
            .removePrefix("env ").trim()
        // Handle VARIABLE=value prefix
        val parts = stripped.split(" ")
        val base = parts.firstOrNull { !it.contains('=') } ?: return false
        // Get just the program name (handle paths like /usr/bin/vim)
        val program = base.substringAfterLast('/')
        return program in tuiCommands
    }

    // State for TUI delegation: when we switch to manual mode for a TUI command,
    // we want to auto-return to assist mode when user exits the TUI.
    private var tuiDelegationActive = false
    private var tuiDelegationCommand = ""
    private var tuiDelegationMsgId = ""
    private var altScreenWatcher: Job? = null

    /** Execute a TUI command by switching to manual mode PTY. */
    private fun executeTuiCommand(index: Int) {
        val commands = _pendingCommands.value.toMutableList()
        val cmd = commands[index]
        val sid = _sessionId.value ?: return

        commands[index] = cmd.copy(status = CommandStatus.RUNNING)
        _pendingCommands.value = commands

        // Insert a result message
        val runningCmd = cmd.copy(status = CommandStatus.RUNNING, output = "正在交互模式中运行...")
        tuiDelegationMsgId = addResultMessage(runningCmd)
        tuiDelegationCommand = cmd.command
        tuiDelegationActive = true

        // Switch to manual mode (opens PTY shell)
        _isManualMode.value = true
        openShellSession()

        // Send the command to the PTY shell once shell is ready
        viewModelScope.launch {
            // Wait for shell to be open (poll with timeout)
            val ready = withContext(Dispatchers.IO) {
                var attempts = 0
                while (attempts < 50) { // up to 5 seconds
                    if (sshManager.isShellOpen(sid)) return@withContext true
                    delay(100)
                    attempts++
                }
                false
            }
            if (!ready) {
                // Shell failed to open — abort TUI delegation
                tuiDelegationActive = false
                val commands2 = _pendingCommands.value.toMutableList()
                if (index in commands2.indices) {
                    commands2[index] = commands2[index].copy(
                        status = CommandStatus.FAILED, output = "[Shell 打开失败]", exitCode = -1)
                    _pendingCommands.value = commands2
                }
                finalizeResultMessage(tuiDelegationMsgId,
                    SshCommand(cmd.command, "", CommandStatus.FAILED, "[Shell 打开失败]", -1))
                _isManualMode.value = false
                return@launch
            }

            // Extra small delay for shell prompt to arrive
            delay(200)
            sendRawToShell(cmd.command + "\n")

            // Watch for alt screen exit → return to assist mode
            altScreenWatcher?.cancel()
            altScreenWatcher = viewModelScope.launch {
                var wasAltScreen = false
                _isAlternateScreen.collect { isAlt ->
                    if (isAlt) {
                        wasAltScreen = true
                    } else if (wasAltScreen && tuiDelegationActive) {
                        // User exited the TUI — return to assist mode
                        wasAltScreen = false
                        delay(200)  // let final output render
                        returnFromTuiDelegation(index)
                    }
                }
            }
        }
    }

    /** Return from TUI delegation back to assist mode. */
    private fun returnFromTuiDelegation(commandIndex: Int) {
        if (!tuiDelegationActive) return
        tuiDelegationActive = false
        altScreenWatcher?.cancel()
        altScreenWatcher = null

        // Collect the last few lines of terminal output as context
        val termOut = synchronized(bufferLock) { screenBuffer.render() }
        val lastLines = termOut.lines().takeLast(20).joinToString("\n")

        // Mark command as completed in pending list
        val commands = _pendingCommands.value.toMutableList()
        if (commandIndex in commands.indices) {
            commands[commandIndex] = commands[commandIndex].copy(
                status = CommandStatus.COMPLETED,
                output = "[交互式程序已退出]\n$lastLines",
                exitCode = 0
            )
            _pendingCommands.value = commands
        }

        // Update the result message
        finalizeResultMessage(tuiDelegationMsgId, commands.getOrNull(commandIndex)
            ?: SshCommand(tuiDelegationCommand, "", CommandStatus.COMPLETED, "[交互式程序已退出]", 0))

        // Switch back to assist mode
        _isManualMode.value = false
        suspendShellReader()

        // Submit context to AI
        viewModelScope.launch {
            explainLastResult(tuiDelegationMsgId, commands.getOrNull(commandIndex)
                ?: SshCommand(tuiDelegationCommand, "", CommandStatus.COMPLETED, lastLines, 0))
        }
    }

    /** Confirm and execute a single command. */
    fun confirmCommand(index: Int) {
        val commands = _pendingCommands.value.toMutableList()
        if (index !in commands.indices) return
        val cmd = commands[index]
        if (cmd.status != CommandStatus.PENDING) return

        // TUI commands need interactive PTY
        if (isTuiCommand(cmd.command)) {
            executeTuiCommand(index)
            return
        }

        val sid = _sessionId.value ?: return
        commands[index] = cmd.copy(status = CommandStatus.RUNNING)
        _pendingCommands.value = commands

        // Insert a running result message into chat immediately
        val runningCmd = cmd.copy(status = CommandStatus.RUNNING, output = "")
        val resultMsgId = addResultMessage(runningCmd)

        viewModelScope.launch {
            val result = orchestrator.executeCommand(sid, cmd.command) { output ->
                // Update the result message's output in real-time
                updateResultMessageOutput(resultMsgId, cmd.command, output)
            }
            val finishedCmd = result.copy(description = cmd.description, command = cmd.command)
            commands[index] = finishedCmd
            _pendingCommands.value = commands.toList()
            _executionResults.update { it + finishedCmd }

            // Finalize the result message with exit code and status
            finalizeResultMessage(resultMsgId, finishedCmd)

            // Auto-explain result — updates the result message in chat
            explainLastResult(resultMsgId, finishedCmd)
        }
    }

    /** Confirm and execute all pending commands in sequence. */
    fun confirmAll() {
        val commands = _pendingCommands.value.toMutableList()
        val sid = _sessionId.value ?: return

        // If only one command and it's a TUI, delegate to single confirm
        val pendingIndices = commands.indices.filter { commands[it].status == CommandStatus.PENDING }
        if (pendingIndices.size == 1 && isTuiCommand(commands[pendingIndices[0]].command)) {
            confirmCommand(pendingIndices[0])
            return
        }

        // Insert a result message for the batch
        val runningCmd = commands.first().copy(status = CommandStatus.RUNNING, output = "")
        val resultMsgId = addResultMessage(runningCmd)

        viewModelScope.launch {
            val executedCmds = mutableListOf<SshCommand>()
            for (i in commands.indices) {
                if (commands[i].status != CommandStatus.PENDING) continue
                // Skip TUI commands in batch execution
                if (isTuiCommand(commands[i].command)) {
                    commands[i] = commands[i].copy(status = CommandStatus.SKIPPED,
                        output = "[交互式程序，请单独确认执行]")
                    _pendingCommands.value = commands.toList()
                    continue
                }
                commands[i] = commands[i].copy(status = CommandStatus.RUNNING)
                _pendingCommands.value = commands.toList()

                val result = orchestrator.executeCommand(sid, commands[i].command) { output ->
                    // Update result message with current command's live output
                    updateResultMessageOutput(resultMsgId, commands[i].command, output)
                }
                commands[i] = result.copy(description = commands[i].description, command = commands[i].command)
                _pendingCommands.value = commands.toList()
                _executionResults.update { it + commands[i] }
                executedCmds.add(commands[i])

                // Update the result message with all finished commands so far
                updateResultMessageCommands(resultMsgId, executedCmds.toList())

                // Stop on failure
                if (result.exitCode != 0) break
            }

            // Explain the last result
            if (executedCmds.isNotEmpty()) {
                explainLastResult(resultMsgId, executedCmds.last())
            }
        }
    }

    /** Skip a pending command. */
    fun skipCommand(index: Int) {
        val commands = _pendingCommands.value.toMutableList()
        if (index !in commands.indices) return
        commands[index] = commands[index].copy(status = CommandStatus.SKIPPED)
        _pendingCommands.value = commands
    }

    /** Edit a pending command's text. */
    fun editCommand(index: Int, newCommand: String) {
        val commands = _pendingCommands.value.toMutableList()
        if (index !in commands.indices) return
        if (commands[index].status != CommandStatus.PENDING) return
        commands[index] = commands[index].copy(command = newCommand)
        _pendingCommands.value = commands
    }

    /** Add a result message to chat, returns its ID for later updates. */
    private fun addResultMessage(vararg cmds: SshCommand): String {
        val resultMsg = SshChatMessage(
            role = "result",
            content = "",
            executedCommands = cmds.toList()
        )
        _messages.update { it + resultMsg }
        return resultMsg.id
    }

    /** Update a result message's live output for a specific running command. */
    private fun updateResultMessageOutput(msgId: String, command: String, output: String) {
        _messages.update { msgs ->
            msgs.map { msg ->
                if (msg.id == msgId) {
                    val updatedCmds = msg.executedCommands.map { entry ->
                        if (entry.command == command && entry.status == CommandStatus.RUNNING) {
                            entry.copy(output = output)
                        } else entry
                    }
                    msg.copy(executedCommands = updatedCmds)
                } else msg
            }
        }
    }

    /** Finalize a single-command result message with completed data. */
    private fun finalizeResultMessage(msgId: String, cmd: SshCommand) {
        _messages.update { msgs ->
            msgs.map { msg ->
                if (msg.id == msgId) msg.copy(executedCommands = listOf(cmd))
                else msg
            }
        }
    }

    /** Update a result message with a full list of executed commands (batch mode). */
    private fun updateResultMessageCommands(msgId: String, cmds: List<SshCommand>) {
        _messages.update { msgs ->
            msgs.map { msg ->
                if (msg.id == msgId) msg.copy(executedCommands = cmds)
                else msg
            }
        }
    }

    /** Let AI explain the execution result — updates the result message in chat. */
    private suspend fun explainLastResult(msgId: String, cmd: SshCommand) {
        _isLoading.value = true
        try {
            val apiMessages = _messages.value
                .filter { it.role != "system" && it.role != "result" }
                .map { mapOf("role" to it.role, "content" to it.content) }

            val explanation = orchestrator.explainResult(
                messages = apiMessages,
                serverContext = buildServerContext(),
                command = cmd.command,
                output = cmd.output,
                exitCode = cmd.exitCode,
                enableThinking = _deepThinking.value
            )

            // Update the target result message
            _messages.update { msgs ->
                msgs.map { msg ->
                    if (msg.id == msgId) {
                        val updatedCmds = msg.executedCommands.map { entry ->
                            if (entry.command == cmd.command && entry.explanation.isBlank()) {
                                entry.copy(explanation = explanation)
                            } else entry
                        }
                        msg.copy(executedCommands = updatedCmds)
                    } else msg
                }
            }
        } catch (_: Exception) {
            // Silently skip explanation on failure
        } finally {
            _isLoading.value = false
        }
    }

    fun setManualMode(enabled: Boolean) {
        _isManualMode.value = enabled
        if (enabled) {
            openShellSession()
            _sessionId.value?.let { sessionRegistry.pauseSession(it) }
        } else {
            suspendShellReader()
            _sessionId.value?.let { sessionRegistry.resumeSession(it) }
        }
    }

    /** Store password in memory for auto-reconnect. */
    fun setReconnectPassword(password: String?) {
        reconnectPassword = password
    }

    /** Exit SSH screen without disconnecting — session stays alive in background.
     *  If in manual mode, keep the shell reader running so terminal state stays current
     *  (especially important for alternate-screen programs like htop/vim). */
    fun exitScreen() {
        // Don't suspend shell reader — let it keep processing output in background
        // so screenBuffer / isAlternateScreen / savedMainBuffer stay up to date.
        reconnectJob?.cancel()
        reconnectJob = null
    }

    // ── Session Switcher ──

    /** Refresh saved servers list for switcher. */
    fun refreshSavedServers() {
        _savedServers.value = serverStorage.loadAll()
        _switcherError.value = null
    }

    /** Get all active SSH sessions from SshManager. */
    fun fetchActiveSessions(): List<SshManager.SshSessionInfo> = sshManager.getActiveSessions()

    /** Switch to an existing active session. */
    fun switchToSession(targetSessionId: String) {
        if (targetSessionId == _sessionId.value) return
        // Pause current shell reader, but keep connection alive
        closeShellSession()
        // Enter new session
        enterSession(targetSessionId)
    }

    /** Connect to a saved server and enter the new session. */
    fun connectAndSwitch(server: SavedServer) {
        if (_switcherConnecting.value != null) return
        _switcherConnecting.value = server.id
        _switcherError.value = null

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val cred = if (server.credentialId.isNotBlank()) {
                        credentialStorage.findById(server.credentialId)
                    } else null

                    sshManager.connect(
                        host = server.host,
                        port = server.port,
                        username = cred?.username ?: "",
                        password = cred?.secret,
                        credentialName = if (cred == null && server.credentialName.isNotBlank())
                            server.credentialName else null
                    )
                }

                if (result.startsWith("Error:")) {
                    _switcherError.value = result.removePrefix("Error: ")
                } else {
                    val json = org.json.JSONObject(result)
                    val newSid = json.getString("session_id")
                    closeShellSession()
                    enterSession(newSid)
                }
            } catch (e: Exception) {
                _switcherError.value = e.message ?: "连接失败"
            } finally {
                _switcherConnecting.value = null
            }
        }
    }

    fun clearSwitcherError() { _switcherError.value = null }

    /** Manually trigger reconnect. */
    fun reconnect() {
        val sid = _sessionId.value ?: return
        if (_isReconnecting.value) return
        reconnectJob?.cancel()
        startReconnectLoop(sid)
    }

    private fun startReconnectLoop(sessionId: String) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            _isReconnecting.value = true
            var attempts = 0
            val maxAttempts = 20 // ~60s total
            while (isActive && attempts < maxAttempts) {
                attempts++
                val result = withContext(Dispatchers.IO) {
                    sshManager.reconnect(sessionId, reconnectPassword)
                }
                if (!result.startsWith("Error:")) {
                    // Reconnect succeeded
                    try {
                        val json = org.json.JSONObject(result)
                        val newSid = json.getString("session_id")
                        _sessionId.value = newSid
                        _isSessionDropped.value = false
                        _isReconnecting.value = false
                        _isConnected.value = true
                        appendTerminalOutput("\n[已重连]\n")
                        // Re-open shell if was in manual mode
                        if (_isManualMode.value) {
                            openShellSession()
                        }
                        return@launch
                    } catch (_: Exception) {}
                }
                // Wait 3 seconds before retrying
                delay(3000)
            }
            // Max attempts reached — give up
            if (isActive) {
                _isReconnecting.value = false
                appendTerminalOutput("\n[重连失败，已放弃自动重连。请手动重连或断开连接。]\n")
            }
        }
    }

    private fun openShellSession() {
        val sid = _sessionId.value ?: return
        // If shell is already open and reader is running, nothing to do —
        // the background reader keeps terminal state up to date.
        if (sshManager.isShellOpen(sid)) {
            if (shellReadJob?.isActive != true) {
                startShellReader(sid)
            }
            return
        }
        synchronized(bufferLock) { screenBuffer.clear() }
        _terminalOutput.value = AnnotatedString("")
        viewModelScope.launch(Dispatchers.IO) {
            if (sshManager.openShell(sid, ptyCols, ptyRows)) {
                startShellReader(sid)
            } else {
                _terminalOutput.value = AnnotatedString("[Shell 打开失败]\n")
            }
        }
    }

    private fun startShellReader(sessionId: String) {
        shellReadJob?.cancel()
        shellReadJob = viewModelScope.launch(Dispatchers.IO) {
            val inputStream = sshManager.getShellInputStream(sessionId) ?: return@launch
            val buffer = ByteArray(4096)
            var consecutiveEof = 0
            try {
                while (isActive) {
                    val len = inputStream.read(buffer)
                    if (len > 0) {
                        consecutiveEof = 0
                        try {
                            appendTerminalOutput(String(buffer, 0, len, Charsets.UTF_8))
                        } catch (e: Exception) {
                            android.util.Log.e("SshViewModel", "process error: ${e.message}", e)
                        }
                    } else if (len == -1) {
                        consecutiveEof++
                        // Check if the SSH session itself is dead (network drop)
                        if (!sshManager.isSessionAlive(sessionId)) {
                            appendTerminalOutput("\n[连接已断开，正在尝试重连...]\n")
                            handleSessionDrop(sessionId)
                            return@launch
                        }
                        if (!sshManager.isShellOpen(sessionId)) {
                            if (consecutiveEof >= 3) {
                                appendTerminalOutput("\n[会话已关闭]\n")
                                break
                            }
                        }
                        delay(200)
                    }
                }
            } catch (e: java.io.IOException) {
                if (isActive) {
                    appendTerminalOutput("\n[连接已断开，正在尝试重连...]\n")
                    handleSessionDrop(sessionId)
                    return@launch
                }
            } catch (e: Exception) {
                if (isActive) appendTerminalOutput("\n[错误: ${e.message}]\n")
            }
        }
    }

    private fun handleSessionDrop(sessionId: String) {
        _isSessionDropped.value = true
        _isConnected.value = false
        startReconnectLoop(sessionId)
    }

    /** Stop reading shell output without closing the shell channel. */
    private fun suspendShellReader() {
        shellReadJob?.cancel()
        shellReadJob = null
    }

    /** Close shell channel completely (only on disconnect). */
    private fun closeShellSession() {
        shellReadJob?.cancel()
        shellReadJob = null
        _sessionId.value?.let { sshManager.closeShell(it) }
    }

    private fun appendTerminalOutput(raw: String) {
        // Handle alternate screen buffer switching.
        // We need to split the raw data around the escape sequences so that
        // content before "enter alt" goes to the main buffer, alt screen content
        // stays in the alt buffer, and content after "leave alt" goes back to main.
        val enterSeqs = listOf("\u001B[?1049h", "\u001B[?47h", "\u001B[?1047h")
        val leaveSeqs = listOf("\u001B[?1049l", "\u001B[?47l", "\u001B[?1047l")

        synchronized(bufferLock) {
            // Prepend any leftover from a previous TCP read that ended mid-escape
            val data = escapeResidue + raw
            escapeResidue = ""

            // Peel off a trailing incomplete escape sequence so we don't mis-parse it.
            // An incomplete sequence starts with ESC and has no final byte yet.
            val finalData: String
            val lastEsc = data.lastIndexOf('\u001B')
            if (lastEsc >= 0 && lastEsc >= data.length - 20) {
                val tail = data.substring(lastEsc)
                if (isIncompleteEscape(tail)) {
                    escapeResidue = tail
                    finalData = data.substring(0, lastEsc)
                } else {
                    finalData = data
                }
            } else {
                finalData = data
            }

            var remaining = finalData

            // Check for enter alt screen
            val enterIdx = enterSeqs.map { remaining.indexOf(it) }.filter { it >= 0 }.minOrNull()
            if (enterIdx != null) {
                val seq = enterSeqs.first { remaining.indexOf(it) == enterIdx }
                // Process content before the enter sequence on the main buffer
                val before = remaining.substring(0, enterIdx)
                if (before.isNotEmpty()) screenBuffer.process(before)
                // Save main buffer and switch to alt screen
                savedMainBuffer = screenBuffer.snapshot()
                screenBuffer.clear()
                _isAlternateScreen.value = true
                remaining = remaining.substring(enterIdx + seq.length)
            }

            // Check for leave alt screen
            val leaveIdx = leaveSeqs.map { remaining.indexOf(it) }.filter { it >= 0 }.minOrNull()
            if (leaveIdx != null) {
                val seq = leaveSeqs.first { remaining.indexOf(it) == leaveIdx }
                // Process content before the leave sequence on the alt buffer (for rendering)
                val before = remaining.substring(0, leaveIdx)
                if (before.isNotEmpty()) screenBuffer.process(before)
                // Restore main buffer
                savedMainBuffer?.let { screenBuffer.restoreFrom(it) }
                savedMainBuffer = null
                _isAlternateScreen.value = false
                remaining = remaining.substring(leaveIdx + seq.length)
            }

            // Process remaining content on current buffer
            if (remaining.isNotEmpty()) screenBuffer.process(remaining)

            _cursorShouldBlink.value = screenBuffer.cursorBlink
            if (_isAlternateScreen.value) {
                _terminalOutput.value = screenBuffer.renderAnnotated(showCursor = true, excludeBottomRows = 2)
                _statusLine.value = screenBuffer.renderStatusLines(showCursor = true, bottomRows = 2)
            } else {
                _terminalOutput.value = screenBuffer.renderAnnotated(showCursor = true)
                _statusLine.value = AnnotatedString("")
            }
        }
    }

    /** Check if a string starting with ESC is an incomplete escape sequence. */
    private fun isIncompleteEscape(s: String): Boolean {
        if (s.length == 1 && s[0] == '\u001B') return true  // bare ESC
        if (s.length < 2 || s[0] != '\u001B') return false
        return when (s[1]) {
            '[' -> {
                // CSI: ESC [ params intermediates final-byte
                // Final byte is in 0x40..0x7E. If none found, it's incomplete.
                for (i in 2 until s.length) {
                    val c = s[i]
                    if (c in '0'..'9' || c == ';' || c == '?' || c == '>') continue  // param
                    if (c in '\u0020'..'\u002F') continue  // intermediate
                    return false  // found final byte → complete
                }
                true  // no final byte → incomplete
            }
            ']' -> {
                // OSC: ESC ] ... BEL  or  ESC ] ... ESC backslash
                !s.contains('\u0007') && !s.contains("\u001B\\")
            }
            else -> false  // other 2-char sequences are always complete
        }
    }

    /** Re-render the screen buffer with cursor visible or hidden (for blink). */
    fun renderOutput(showCursor: Boolean): AnnotatedString {
        synchronized(bufferLock) {
            return if (_isAlternateScreen.value) {
                screenBuffer.renderAnnotated(showCursor = showCursor, excludeBottomRows = 2)
            } else {
                screenBuffer.renderAnnotated(showCursor = showCursor)
            }
        }
    }

    /** Re-render the status lines with cursor visible or hidden (for blink). */
    fun renderStatusOutput(showCursor: Boolean): AnnotatedString {
        synchronized(bufferLock) {
            return if (_isAlternateScreen.value) {
                screenBuffer.renderStatusLines(showCursor = showCursor, bottomRows = 2)
            } else {
                AnnotatedString("")
            }
        }
    }

    /**
     * Terminal cell: a character with foreground/background color and style attributes.
     */
    data class TermCell(
        var ch: Char = ' ',
        var fg: Color = Color.Unspecified,
        var bg: Color = Color.Unspecified,
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var dim: Boolean = false
    )

    /**
     * Grid-based terminal screen buffer with ANSI color/style support.
     */
    class ScreenBuffer(
        private var cols: Int = 120,
        private var rows: Int = 40
    ) {
        companion object {
            // Standard 8 ANSI colors (normal)
            private val ANSI_COLORS = arrayOf(
                Color(0xFF000000), // 0 black
                Color(0xFFCC0000), // 1 red
                Color(0xFF00CC00), // 2 green
                Color(0xFFCCCC00), // 3 yellow
                Color(0xFF5577FF), // 4 blue
                Color(0xFFCC00CC), // 5 magenta
                Color(0xFF00CCCC), // 6 cyan
                Color(0xFFCCCCCC)  // 7 white
            )
            // Bright variants
            private val ANSI_BRIGHT = arrayOf(
                Color(0xFF555555), // 0 bright black (gray)
                Color(0xFFFF5555), // 1 bright red
                Color(0xFF55FF55), // 2 bright green
                Color(0xFFFFFF55), // 3 bright yellow
                Color(0xFF5577FF), // 4 bright blue
                Color(0xFFFF55FF), // 5 bright magenta
                Color(0xFF55FFFF), // 6 bright cyan
                Color(0xFFFFFFFF)  // 7 bright white
            )

            fun color256(n: Int): Color = when {
                n < 8 -> ANSI_COLORS[n]
                n < 16 -> ANSI_BRIGHT[n - 8]
                n < 232 -> {
                    val idx = n - 16
                    val r = (idx / 36) * 51
                    val g = ((idx % 36) / 6) * 51
                    val b = (idx % 6) * 51
                    Color(r, g, b)
                }
                else -> {
                    val gray = 8 + (n - 232) * 10
                    Color(gray, gray, gray)
                }
            }
        }

        // Each line is a list of TermCells
        private var lines = mutableListOf(mutableListOf<TermCell>())
        private var cursorRow = 0
        private var cursorCol = 0

        // Current style state (applied to new characters)
        private var curFg: Color = Color.Unspecified
        private var curBg: Color = Color.Unspecified
        private var curBold = false
        private var curItalic = false
        private var curUnderline = false
        private var curDim = false

        // Cursor style (DECSCUSR): false = steady, true = blinking
        var cursorBlink = false
            private set

        // Scroll region (DECSTBM)
        private var scrollTop = 0
        private var scrollBottom = rows - 1

        // Saved cursor
        private var savedRow = 0
        private var savedCol = 0
        private var savedFg: Color = Color.Unspecified
        private var savedBg: Color = Color.Unspecified
        private var savedBold = false

        /** Process raw PTY data (may contain ANSI sequences). */
        fun process(raw: String) {
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                when {
                    ch == '\u001B' && i + 1 < raw.length -> {
                        val next = raw[i + 1]
                        when {
                            next == '[' -> { i += 2; i = processCsi(raw, i) }
                            next == ']' -> {
                                i += 2
                                while (i < raw.length && raw[i] != '\u0007' &&
                                    !(raw[i] == '\u001B' && i + 1 < raw.length && raw[i + 1] == '\\')) i++
                                if (i < raw.length) { if (raw[i] == '\u0007') i++ else i += 2 }
                            }
                            next == '(' || next == ')' -> { i += 3 }
                            next == '=' || next == '>' -> { i += 2 }
                            next == '7' -> { savedRow = cursorRow; savedCol = cursorCol; savedFg = curFg; savedBg = curBg; savedBold = curBold; i += 2 }
                            next == '8' -> { cursorRow = savedRow; cursorCol = savedCol; curFg = savedFg; curBg = savedBg; curBold = savedBold; i += 2 }
                            else -> { i += 2 }
                        }
                    }
                    ch == '\r' && i + 1 < raw.length && raw[i + 1] == '\n' -> { newLine(); i += 2 }
                    ch == '\r' -> { cursorCol = 0; i++ }
                    ch == '\n' -> { newLine(); i++ }
                    ch == '\u0008' -> { if (cursorCol > 0) cursorCol--; i++ }
                    ch == '\u0007' -> { i++ }
                    ch == '\t' -> {
                        val nextStop = minOf(((cursorCol / 8) + 1) * 8, cols)
                        while (cursorCol < nextStop) { putChar(' '); cursorCol++ }
                        i++
                    }
                    else -> {
                        putChar(ch); cursorCol++
                        if (cursorCol >= cols) { newLine() }
                        i++
                    }
                }
            }
        }

        private fun processCsi(raw: String, startIdx: Int): Int {
            var i = startIdx
            val paramBuf = StringBuilder()
            while (i < raw.length) {
                val c = raw[i]
                if (c in '0'..'9' || c == ';' || c == '?' || c == '>') { paramBuf.append(c); i++ }
                else break
            }
            // Collect intermediate bytes (0x20-0x2F, e.g. space in DECSCUSR)
            var intermediate = '\u0000'
            if (i < raw.length && raw[i] in '\u0020'..'\u002F') {
                intermediate = raw[i]; i++
            }
            if (i >= raw.length) return i
            val finalChar = raw[i]; i++
            val paramStr = paramBuf.toString()

            // DECSCUSR: ESC [ Ps SP q — Set cursor style
            if (intermediate == ' ' && finalChar == 'q') {
                val ps = paramStr.toIntOrNull() ?: 0
                cursorBlink = ps == 0 || ps % 2 == 1  // 0,1,3,5 = blink; 2,4,6 = steady
                return i
            }

            // Private mode
            if (paramStr.startsWith("?")) {
                val nums = paramStr.removePrefix("?").split(";").mapNotNull { it.toIntOrNull() }
                when (finalChar) {
                    'h' -> {
                        if (12 in nums) cursorBlink = true   // ATT610 blink mode
                        // 1049/47/1047 handled externally
                    }
                    'l' -> {
                        if (12 in nums) cursorBlink = false  // ATT610 steady mode
                        // 1049/47/1047 handled externally
                    }
                }
                return i
            }

            val params = paramStr.split(";").map { it.toIntOrNull() ?: 0 }
            val p0 = params.getOrElse(0) { 0 }
            val p1 = params.getOrElse(1) { 0 }

            when (finalChar) {
                'H', 'f' -> {
                    cursorRow = ((if (p0 == 0) 1 else p0) - 1).coerceAtLeast(0)
                    cursorCol = ((if (p1 == 0) 1 else p1) - 1).coerceAtLeast(0)
                    ensureRow(cursorRow)
                }
                'A' -> cursorRow = (cursorRow - maxOf(p0, 1)).coerceAtLeast(0)
                'B' -> { cursorRow += maxOf(p0, 1); ensureRow(cursorRow) }
                'C' -> cursorCol = (cursorCol + maxOf(p0, 1)).coerceAtMost(cols - 1)
                'D' -> cursorCol = (cursorCol - maxOf(p0, 1)).coerceAtLeast(0)
                'G' -> cursorCol = (maxOf(p0, 1) - 1).coerceAtLeast(0)
                'd' -> { cursorRow = (maxOf(p0, 1) - 1).coerceAtLeast(0); ensureRow(cursorRow) }

                'J' -> { // Erase in display
                    ensureRow(cursorRow)
                    when (p0) {
                        0 -> {
                            val line = lines[cursorRow]
                            for (c in cursorCol until line.size) line[c] = TermCell()
                            for (r in cursorRow + 1 until lines.size) lines[r] = mutableListOf()
                        }
                        1 -> {
                            for (r in 0 until cursorRow) lines[r] = mutableListOf()
                            val line = lines[cursorRow]
                            for (c in 0 until minOf(cursorCol, line.size)) line[c] = TermCell()
                        }
                        2, 3 -> {
                            for (r in 0 until lines.size) lines[r] = mutableListOf()
                        }
                    }
                }
                'K' -> { // Erase in line
                    ensureRow(cursorRow)
                    val line = lines[cursorRow]
                    when (p0) {
                        0 -> { for (c in cursorCol until line.size) line[c] = TermCell() }
                        1 -> { for (c in 0 until minOf(cursorCol + 1, line.size)) line[c] = TermCell() }
                        2 -> { line.clear() }
                    }
                }
                'L' -> { // Insert lines at cursor within scroll region
                    ensureRow(cursorRow)
                    repeat(maxOf(p0, 1)) {
                        lines.add(cursorRow, mutableListOf())
                        ensureRow(scrollBottom + 1)
                        if (scrollBottom + 1 < lines.size) lines.removeAt(scrollBottom + 1)
                    }
                }
                'M' -> { // Delete lines at cursor within scroll region
                    ensureRow(cursorRow)
                    repeat(maxOf(p0, 1)) {
                        if (cursorRow < lines.size) lines.removeAt(cursorRow)
                        lines.add(scrollBottom.coerceAtMost(lines.size), mutableListOf())
                    }
                }
                'P' -> { // Delete characters
                    ensureRow(cursorRow)
                    val line = lines[cursorRow]
                    val count = maxOf(p0, 1)
                    repeat(minOf(count, (line.size - cursorCol).coerceAtLeast(0))) {
                        if (cursorCol < line.size) line.removeAt(cursorCol)
                    }
                }
                '@' -> { // Insert characters
                    ensureRow(cursorRow)
                    val line = lines[cursorRow]
                    val count = maxOf(p0, 1)
                    if (cursorCol <= line.size) {
                        repeat(count) { line.add(cursorCol, TermCell()) }
                    }
                }
                'S' -> { // Scroll up within scroll region
                    repeat(maxOf(p0, 1)) {
                        ensureRow(scrollBottom)
                        if (scrollTop < lines.size) lines.removeAt(scrollTop)
                        lines.add(scrollBottom.coerceAtMost(lines.size), mutableListOf())
                    }
                }
                'T' -> { // Scroll down within scroll region
                    repeat(maxOf(p0, 1)) {
                        ensureRow(scrollBottom)
                        if (scrollBottom < lines.size) lines.removeAt(scrollBottom)
                        lines.add(scrollTop.coerceAtMost(lines.size), mutableListOf())
                    }
                }
                's' -> { savedRow = cursorRow; savedCol = cursorCol }
                'u' -> { cursorRow = savedRow; cursorCol = savedCol }
                'r' -> { // DECSTBM — Set scroll region
                    scrollTop = if (p0 > 0) p0 - 1 else 0
                    scrollBottom = if (p1 > 0) p1 - 1 else rows - 1
                    scrollTop = scrollTop.coerceIn(0, rows - 1)
                    scrollBottom = scrollBottom.coerceIn(scrollTop, rows - 1)
                    cursorRow = 0; cursorCol = 0
                }

                'm' -> { // SGR — Select Graphic Rendition
                    if (params.isEmpty() || (params.size == 1 && p0 == 0)) {
                        resetStyle(); return i
                    }
                    var pi = 0
                    while (pi < params.size) {
                        when (val code = params[pi]) {
                            0 -> resetStyle()
                            1 -> curBold = true
                            2 -> curDim = true
                            3 -> curItalic = true
                            4 -> curUnderline = true
                            22 -> { curBold = false; curDim = false }
                            23 -> curItalic = false
                            24 -> curUnderline = false
                            in 30..37 -> curFg = ANSI_COLORS[code - 30]
                            38 -> { // Extended fg
                                if (pi + 1 < params.size && params[pi + 1] == 5 && pi + 2 < params.size) {
                                    curFg = color256(params[pi + 2]); pi += 2
                                } else if (pi + 1 < params.size && params[pi + 1] == 2 && pi + 4 < params.size) {
                                    curFg = Color(params[pi + 2], params[pi + 3], params[pi + 4]); pi += 4
                                }
                            }
                            39 -> curFg = Color.Unspecified
                            in 40..47 -> curBg = ANSI_COLORS[code - 40]
                            48 -> { // Extended bg
                                if (pi + 1 < params.size && params[pi + 1] == 5 && pi + 2 < params.size) {
                                    curBg = color256(params[pi + 2]); pi += 2
                                } else if (pi + 1 < params.size && params[pi + 1] == 2 && pi + 4 < params.size) {
                                    curBg = Color(params[pi + 2], params[pi + 3], params[pi + 4]); pi += 4
                                }
                            }
                            49 -> curBg = Color.Unspecified
                            in 90..97 -> curFg = ANSI_BRIGHT[code - 90]
                            in 100..107 -> curBg = ANSI_BRIGHT[code - 100]
                        }
                        pi++
                    }
                }
                else -> { }
            }
            return i
        }

        private fun resetStyle() {
            curFg = Color.Unspecified; curBg = Color.Unspecified
            curBold = false; curItalic = false; curUnderline = false; curDim = false
        }

        private fun newLine() {
            cursorCol = 0
            if (cursorRow == scrollBottom) {
                // At bottom of scroll region: scroll the region up
                ensureRow(scrollBottom)
                if (scrollTop < lines.size) lines.removeAt(scrollTop)
                lines.add(scrollBottom.coerceAtMost(lines.size), mutableListOf())
            } else {
                cursorRow++
                ensureRow(cursorRow)
            }
            // No scrollback trimming — unlimited history as long as session is alive.
        }

        private fun putChar(ch: Char) {
            ensureRow(cursorRow)
            val line = lines[cursorRow]
            while (line.size <= cursorCol) line.add(TermCell())
            line[cursorCol] = TermCell(ch, curFg, curBg, curBold, curItalic, curUnderline, curDim)
        }

        private fun ensureRow(row: Int) {
            while (lines.size <= row) lines.add(mutableListOf())
        }

        // Default terminal text color (matches TermText in SshScreen)
        private val defaultFg = TermText

        /** Render as AnnotatedString with colors and optional cursor.
         *  @param excludeBottomRows  when >0, skip the last N rows of the terminal (for separate status bar rendering) */
        fun renderAnnotated(showCursor: Boolean = true, excludeBottomRows: Int = 0): AnnotatedString {
            ensureRow(cursorRow)
            var lastNonEmpty = lines.size - 1
            while (lastNonEmpty > cursorRow && lastNonEmpty < lines.size &&
                lines[lastNonEmpty].all { it.ch == ' ' || it.ch == '\u0000' }) lastNonEmpty--
            lastNonEmpty = maxOf(lastNonEmpty, cursorRow)

            // If we need to exclude bottom rows, cap the rendering
            val renderEnd = if (excludeBottomRows > 0) {
                (rows - 1 - excludeBottomRows).coerceAtMost(lastNonEmpty)
            } else {
                lastNonEmpty
            }

            return buildAnnotatedString {
                for (r in 0..minOf(renderEnd, lines.size - 1)) {
                    if (r > 0) append('\n')
                    val line = lines[r]

                    // Find trimmed end for non-cursor rows
                    val trimEnd = if (r == cursorRow) {
                        maxOf(line.size, cursorCol + 1)
                    } else {
                        var e = line.size - 1
                        while (e >= 0 && (line[e].ch == ' ' || line[e].ch == '\u0000')) e--
                        e + 1
                    }

                    var col = 0
                    while (col < trimEnd) {
                        if (r == cursorRow && col == cursorCol && showCursor) {
                            // Render cursor: inverted block style
                            val cellUnder = if (col < line.size) line[col] else TermCell()
                            val cursorFg = if (cellUnder.bg != Color.Unspecified) cellUnder.bg else TermBg
                            val cursorBg = if (cellUnder.fg != Color.Unspecified) cellUnder.fg else defaultFg
                            pushStyle(SpanStyle(color = cursorFg, background = cursorBg,
                                fontWeight = FontWeight.Bold))
                            val ch = if (col < line.size && line[col].ch != ' ' && line[col].ch != '\u0000') line[col].ch else ' '
                            append(ch)
                            pop()
                            col++
                            continue
                        }

                        val cell = if (col < line.size) line[col] else TermCell()
                        // Batch consecutive cells with same style
                        val startCol = col
                        val style = cellStyle(cell)
                        val batch = StringBuilder()
                        batch.append(if (cell.ch == '\u0000') ' ' else cell.ch)
                        col++
                        while (col < trimEnd && !(r == cursorRow && col == cursorCol)) {
                            val next = if (col < line.size) line[col] else TermCell()
                            val nextStyle = cellStyle(next)
                            if (nextStyle != style) break
                            batch.append(if (next.ch == '\u0000') ' ' else next.ch)
                            col++
                        }
                        if (style != SpanStyle()) {
                            pushStyle(style)
                            append(batch)
                            pop()
                        } else {
                            append(batch)
                        }
                    }
                }
            }
        }

        private fun cellStyle(cell: TermCell): SpanStyle {
            val fg = when {
                cell.fg != Color.Unspecified && cell.dim -> cell.fg.copy(alpha = 0.6f)
                cell.fg != Color.Unspecified -> cell.fg
                cell.bold -> Color(0xFFFFFFFF) // bright white for bold with default color
                cell.dim -> defaultFg.copy(alpha = 0.6f)
                else -> Color.Unspecified // use Text's default color
            }
            val bg = cell.bg
            val weight = if (cell.bold) FontWeight.Bold else null
            val fontStyle = if (cell.italic) FontStyle.Italic else null
            val decoration = if (cell.underline) TextDecoration.Underline else null
            return SpanStyle(
                color = fg,
                background = if (bg != Color.Unspecified) bg else Color.Unspecified,
                fontWeight = weight,
                fontStyle = fontStyle,
                textDecoration = decoration
            )
        }

        /** Render the bottom N rows of the terminal as a separate AnnotatedString (for vim status/command line). */
        fun renderStatusLines(showCursor: Boolean = true, bottomRows: Int = 2): AnnotatedString {
            val startRow = rows - bottomRows
            val endRow = rows - 1
            if (startRow < 0 || startRow >= lines.size) return AnnotatedString("")
            ensureRow(endRow)
            return buildAnnotatedString {
                for (r in startRow..endRow) {
                    if (r > startRow) append('\n')
                    val line = lines[r]

                    val trimEnd = if (r == cursorRow) {
                        maxOf(line.size, cursorCol + 1)
                    } else {
                        var e = line.size - 1
                        while (e >= 0 && (line[e].ch == ' ' || line[e].ch == '\u0000')) e--
                        e + 1
                    }

                    var col = 0
                    while (col < trimEnd) {
                        if (r == cursorRow && col == cursorCol && showCursor) {
                            val cellUnder = if (col < line.size) line[col] else TermCell()
                            val cursorFg = if (cellUnder.bg != Color.Unspecified) cellUnder.bg else TermBg
                            val cursorBg = if (cellUnder.fg != Color.Unspecified) cellUnder.fg else defaultFg
                            pushStyle(SpanStyle(color = cursorFg, background = cursorBg, fontWeight = FontWeight.Bold))
                            val ch = if (col < line.size && line[col].ch != ' ' && line[col].ch != '\u0000') line[col].ch else ' '
                            append(ch)
                            pop()
                            col++
                            continue
                        }

                        val cell = if (col < line.size) line[col] else TermCell()
                        val style = cellStyle(cell)
                        val batch = StringBuilder()
                        batch.append(if (cell.ch == '\u0000') ' ' else cell.ch)
                        col++
                        while (col < trimEnd && !(r == cursorRow && col == cursorCol)) {
                            val next = if (col < line.size) line[col] else TermCell()
                            val nextStyle = cellStyle(next)
                            if (nextStyle != style) break
                            batch.append(if (next.ch == '\u0000') ' ' else next.ch)
                            col++
                        }
                        if (style != SpanStyle()) {
                            pushStyle(style)
                            append(batch)
                            pop()
                        } else {
                            append(batch)
                        }
                    }
                }
            }
        }

        /** Plain text render (for backward compat). */
        fun render(): String {
            var lastNonEmpty = lines.size - 1
            while (lastNonEmpty > 0 && lines[lastNonEmpty].all { it.ch == ' ' || it.ch == '\u0000' }) lastNonEmpty--
            return buildString {
                for (r in 0..lastNonEmpty) {
                    if (r > 0) append('\n')
                    val line = lines[r]
                    var e = line.size - 1
                    while (e >= 0 && (line[e].ch == ' ' || line[e].ch == '\u0000')) e--
                    for (c in 0..e) append(if (line[c].ch == '\u0000') ' ' else line[c].ch)
                }
            }
        }

        fun clear() {
            lines.clear(); lines.add(mutableListOf())
            cursorRow = 0; cursorCol = 0; resetStyle()
            scrollTop = 0; scrollBottom = rows - 1
            cursorBlink = false
        }

        /** Resize the terminal grid. */
        fun resize(newCols: Int, newRows: Int) {
            cols = newCols
            rows = newRows
            scrollTop = 0
            scrollBottom = newRows - 1
        }

        fun snapshot(): ScreenBuffer {
            val copy = ScreenBuffer(cols, rows)
            copy.lines = lines.map { row -> row.map { it.copy() }.toMutableList() }.toMutableList()
            copy.cursorRow = cursorRow; copy.cursorCol = cursorCol
            copy.curFg = curFg; copy.curBg = curBg; copy.curBold = curBold
            copy.scrollTop = scrollTop; copy.scrollBottom = scrollBottom
            return copy
        }

        fun restoreFrom(other: ScreenBuffer) {
            lines = other.lines.map { row -> row.map { it.copy() }.toMutableList() }.toMutableList()
            cursorRow = other.cursorRow; cursorCol = other.cursorCol
            curFg = other.curFg; curBg = other.curBg; curBold = other.curBold
            scrollTop = other.scrollTop; scrollBottom = other.scrollBottom
        }
    }

    /** Update terminal dimensions (called from UI after measuring screen). */
    fun updateTerminalSize(cols: Int, rows: Int) {
        if (cols == ptyCols && rows == ptyRows) return
        ptyCols = cols
        ptyRows = rows
        synchronized(bufferLock) { screenBuffer.resize(cols, rows) }
        val sid = _sessionId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sshManager.resizePty(sid, cols, rows)
        }
    }

    /** Send control character to shell (e.g. Ctrl+C = 'C'). */
    fun sendControlChar(char: Char) {
        _sessionId.value ?: return
        val code = char.uppercaseChar().code - 64
        if (code in 0..31) {
            ensureInputBuffer()
            inputChannel.trySend(ShellInput.Bytes(byteArrayOf(code.toByte())))
        }
    }

    fun interruptRunningCommand() {
        sendControlChar('C')
    }

    fun clearTerminal() {
        synchronized(bufferLock) { screenBuffer.clear() }
        _terminalOutput.value = AnnotatedString("")
        sendControlChar('L')
    }

    /** Send raw text to shell (no newline appended). Buffered for reliability. */
    fun sendRawToShell(text: String) {
        _sessionId.value ?: return
        ensureInputBuffer()
        inputChannel.trySend(ShellInput.Text(text))
    }

    /** Send ESC (0x1B) to shell. */
    fun sendEscape() {
        _sessionId.value ?: return
        ensureInputBuffer()
        inputChannel.trySend(ShellInput.Bytes(byteArrayOf(0x1B)))
    }

    /** Navigate command history. Returns the command text or null. */
    fun getHistoryCommand(up: Boolean): String? {
        if (commandHistory.isEmpty()) return null
        historyIndex = if (up) {
            (historyIndex - 1).coerceAtLeast(0)
        } else {
            (historyIndex + 1).coerceAtMost(commandHistory.size)
        }
        return if (historyIndex < commandHistory.size) commandHistory[historyIndex] else ""
    }

    fun toggleDeepThinking() {
        _deepThinking.value = !_deepThinking.value
    }

    fun switchModel(id: String) {
        aiConfig.setActive(id)
        _activeModelId.value = id
        _deepThinking.value = aiConfig.activeModel.enableThinking
    }

    /** Disconnect and exit SSH mode. */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _isSessionDropped.value = false
        _isReconnecting.value = false
        closeShellSession()
        _sessionId.value?.let {
            sessionRegistry.endSession(it)
            sshManager.disconnect(it)
        }
        KeepAliveService.removeClient(getApplication(), "ssh")
        sshSessionRegistered = false
        _sessionId.value = null
        _isConnected.value = false
        _isManualMode.value = false
        _serverInfo.value = null
        _messages.value = emptyList()
        screenBuffer.clear()
        _terminalOutput.value = AnnotatedString("") 
        _pendingCommands.value = emptyList()
        _executionResults.value = emptyList()
        commandHistory.clear()
        historyIndex = -1
        reconnectPassword = null
    }

    private fun buildServerContext(): String {
        val info = _serverInfo.value ?: return "服务器信息未知"
        return buildString {
            appendLine("| 项目 | 值 |")
            appendLine("|------|----|")
            appendLine("| 主机 | ${info.host}:${info.port} |")
            appendLine("| 用户名 | ${info.username} |")
            if (info.hostname.isNotBlank()) appendLine("| 主机名 | ${info.hostname} |")
            if (info.osType.isNotBlank()) appendLine("| 操作系统 | ${info.osType} |")
            if (info.osVersion.isNotBlank()) appendLine("| 版本 | ${info.osVersion} |")
            if (info.kernel.isNotBlank()) appendLine("| 内核 | ${info.kernel} |")
        }
    }

    private fun extractStdout(jsonResult: String): String {
        return try {
            val json = org.json.JSONObject(jsonResult)
            json.optString("stdout", "")
        } catch (_: Exception) {
            ""
        }
    }

    override fun onCleared() {
        super.onCleared()
        reconnectJob?.cancel()
        shellReadJob?.cancel()
        inputBufferJob?.cancel()
        // Only remove keepalive client if there are no active SSH sessions left.
        // If sessions are still alive in SshManager, KeepAliveService must keep running
        // so the process stays alive and sessions can be restored when Activity is recreated.
        if (sshManager.getActiveSessions().isEmpty()) {
            KeepAliveService.removeClient(getApplication(), "ssh")
        }
    }
}

