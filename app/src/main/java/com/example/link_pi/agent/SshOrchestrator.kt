package com.example.link_pi.agent

import com.example.link_pi.network.AiService
import com.example.link_pi.skill.BuiltInSkills

/**
 * SSH 专用编排器 — 简化的 AI 循环:
 * 1. AI 根据用户请求生成 <ssh_commands> 命令列表
 * 2. 命令需要用户确认后才执行
 * 3. 执行后 AI 解读结果
 *
 * 不走 PromptAssembler / ToolExecutor 路径，直接与 SshManager 交互。
 */
class SshOrchestrator(
    private val aiService: AiService,
    private val sshManager: SshManager
) {
    /** 一条待确认或已执行的命令 */
    data class SshCommand(
        val command: String,
        val description: String,
        val status: CommandStatus = CommandStatus.PENDING,
        val output: String = "",
        val exitCode: Int = -1,
        val explanation: String = ""   // AI 生成的执行结果总结
    )

    enum class CommandStatus {
        PENDING,     // 等待用户确认
        RUNNING,     // 执行中
        COMPLETED,   // 执行完成
        FAILED,      // 执行失败
        SKIPPED      // 用户跳过
    }

    data class SshResponse(
        val text: String,
        val commands: List<SshCommand>,
        val thinkingContent: String = ""
    )

    /**
     * 发送用户消息到 AI，获取命令列表或解释文本。
     * @param messages 完整对话历史
     * @param serverContext 服务器信息上下文（注入 system prompt）
     * @param enableThinking 是否启用深度思考
     */
    suspend fun chat(
        messages: List<Map<String, String>>,
        serverContext: String,
        enableThinking: Boolean = false
    ): SshResponse {
        val systemPrompt = buildString {
            appendLine(BuiltInSkills.SSH_MODE_SYSTEM_PROMPT)
            appendLine()
            appendLine("### 当前服务器信息")
            appendLine(serverContext)
        }

        val fullMessages = mutableListOf<Map<String, String>>()
        fullMessages.add(mapOf("role" to "system", "content" to systemPrompt))
        fullMessages.addAll(messages)

        val response = aiService.chatFull(
            messages = fullMessages,
            enableThinking = enableThinking
        )

        val commands = parseCommands(response.content)
        val text = stripCommandBlocks(response.content)

        return SshResponse(text, commands, response.thinkingContent)
    }

    /**
     * 执行单条已确认的命令。
     * @param sessionId SSH 会话 ID
     * @param command 要执行的命令
     * @param onProgress 执行过程中的实时输出回调
     */
    suspend fun executeCommand(
        sessionId: String,
        command: String,
        onProgress: ((String) -> Unit)? = null
    ): SshCommand {
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sshManager.exec(sessionId, command, 300_000, onProgress)
        }

        return if (result.startsWith("Error:")) {
            SshCommand(command, "", CommandStatus.FAILED, result, -1)
        } else {
            try {
                val json = org.json.JSONObject(result)
                val exitCode = json.optInt("exit_code", -1)
                val stdout = json.optString("stdout", "")
                val stderr = json.optString("stderr", "")
                val output = if (stderr.isNotBlank()) "$stdout\n[stderr] $stderr" else stdout
                SshCommand(
                    command, "",
                    if (exitCode == 0) CommandStatus.COMPLETED else CommandStatus.FAILED,
                    output, exitCode
                )
            } catch (_: Exception) {
                SshCommand(command, "", CommandStatus.COMPLETED, result, 0)
            }
        }
    }

    /**
     * 让 AI 解读命令执行结果
     */
    suspend fun explainResult(
        messages: List<Map<String, String>>,
        serverContext: String,
        command: String,
        output: String,
        exitCode: Int,
        enableThinking: Boolean = false
    ): String {
        val systemPrompt = buildString {
            appendLine(BuiltInSkills.SSH_MODE_SYSTEM_PROMPT)
            appendLine()
            appendLine("### 当前服务器信息")
            appendLine(serverContext)
        }

        val fullMessages = mutableListOf<Map<String, String>>()
        fullMessages.add(mapOf("role" to "system", "content" to systemPrompt))
        fullMessages.addAll(messages)
        fullMessages.add(mapOf("role" to "user", "content" to buildString {
            appendLine("命令已执行完毕，请解读结果：")
            appendLine("```")
            appendLine("$ $command")
            appendLine(output.takeLast(4000))
            appendLine("```")
            appendLine("退出码: $exitCode")
            appendLine()
            appendLine("请简洁解读执行结果，说明命令是否成功，以及关键信息。如果需要后续操作，给出下一步命令建议。")
        }))

        val response = aiService.chatFull(
            messages = fullMessages,
            enableThinking = enableThinking
        )

        return stripCommandBlocks(response.content)
    }

    companion object {
        /** 从 AI 响应解析 <ssh_commands> 块 */
        fun parseCommands(text: String): List<SshCommand> {
            val blockRegex = Regex("<ssh_commands>\\s*([\\s\\S]*?)\\s*</ssh_commands>")
            val cmdRegex = Regex("<cmd\\s+desc=\"([^\"]*)\">\\s*([\\s\\S]*?)\\s*</cmd>")

            val commands = mutableListOf<SshCommand>()
            for (block in blockRegex.findAll(text)) {
                for (cmd in cmdRegex.findAll(block.groupValues[1])) {
                    val desc = cmd.groupValues[1].trim()
                    val command = cmd.groupValues[2].trim()
                    if (command.isNotBlank()) {
                        commands.add(SshCommand(command, desc))
                    }
                }
            }
            return commands
        }

        /** 移除 <ssh_commands> 块，保留纯文本部分 */
        fun stripCommandBlocks(text: String): String {
            return text
                .replace(Regex("<ssh_commands>[\\s\\S]*?</ssh_commands>"), "")
                .trim()
        }
    }
}
