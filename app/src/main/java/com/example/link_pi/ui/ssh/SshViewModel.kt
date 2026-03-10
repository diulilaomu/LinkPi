package com.example.link_pi.ui.ssh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.link_pi.agent.SshManager
import com.example.link_pi.agent.SshOrchestrator
import com.example.link_pi.agent.SshOrchestrator.CommandStatus
import com.example.link_pi.agent.SshOrchestrator.SshCommand
import com.example.link_pi.network.AiConfig
import com.example.link_pi.network.AiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    // ── Manual Mode State ──
    private val _isManualMode = MutableStateFlow(false)
    val isManualMode: StateFlow<Boolean> = _isManualMode.asStateFlow()
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    /** Enter SSH mode with a given session ID. Probes server info. */
    fun enterSession(sshSessionId: String) {
        // Reload model config from disk to stay in sync with main chat's model selection
        aiConfig.reloadModels()
        _deepThinking.value = aiConfig.activeModel.enableThinking

        _sessionId.value = sshSessionId
        _isConnected.value = true
        _messages.value = emptyList()
        _pendingCommands.value = emptyList()
        _executionResults.value = emptyList()

        val info = sshManager.getSessionInfo(sshSessionId)
        if (info != null) {
            _serverInfo.value = ServerInfo(info.host, info.port, info.username)
        }

        // Probe server info in background
        viewModelScope.launch {
            probeServerInfo(sshSessionId)
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

    /** Send a user message to the SSH AI. */
    fun sendMessage(text: String) {
        if (text.isBlank() || _isLoading.value) return

        val userMsg = SshChatMessage(role = "user", content = text)
        _messages.update { it + userMsg }
        _pendingCommands.value = emptyList()
        _executionResults.value = emptyList()

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val apiMessages = _messages.value
                    .filter { it.role != "system" }
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

    /** Confirm and execute a single command. */
    fun confirmCommand(index: Int) {
        val commands = _pendingCommands.value.toMutableList()
        if (index !in commands.indices) return
        val cmd = commands[index]
        if (cmd.status != CommandStatus.PENDING) return

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

        // Insert a result message for the batch
        val runningCmd = commands.first().copy(status = CommandStatus.RUNNING, output = "")
        val resultMsgId = addResultMessage(runningCmd)

        viewModelScope.launch {
            val executedCmds = mutableListOf<SshCommand>()
            for (i in commands.indices) {
                if (commands[i].status != CommandStatus.PENDING) continue
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
    }

    /** Execute a command directly (manual mode). */
    fun sendManualCommand(command: String) {
        if (command.isBlank()) return
        val sid = _sessionId.value ?: return

        commandHistory.add(command)
        historyIndex = commandHistory.size

        val userMsg = SshChatMessage(role = "user", content = command)
        _messages.update { it + userMsg }

        val runningCmd = SshOrchestrator.SshCommand(
            command = command,
            description = "",
            status = CommandStatus.RUNNING
        )
        val resultMsgId = addResultMessage(runningCmd)

        viewModelScope.launch {
            val result = orchestrator.executeCommand(sid, command) { output ->
                updateResultMessageOutput(resultMsgId, command, output)
            }
            finalizeResultMessage(resultMsgId, result.copy(command = command))
        }
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

    /** Disconnect and exit SSH mode. */
    fun disconnect() {
        _sessionId.value?.let { sshManager.disconnect(it) }
        _sessionId.value = null
        _isConnected.value = false
        _isManualMode.value = false
        _serverInfo.value = null
        _messages.value = emptyList()
        _pendingCommands.value = emptyList()
        _executionResults.value = emptyList()
        commandHistory.clear()
        historyIndex = -1
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
}
