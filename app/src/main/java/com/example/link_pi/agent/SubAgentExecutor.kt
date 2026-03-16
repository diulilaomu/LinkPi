package com.example.link_pi.agent

import com.example.link_pi.network.AiService
import com.example.link_pi.network.ChatResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Sub-Agent Executor — 独立 context window 执行具体任务。
 *
 * 主 Agent 负责规划和指令分派，SubAgent 负责工具调用循环执行。
 * 每次 execute 调用使用全新对话（不继承主 Agent 历史），确保 context 干净。
 */
class SubAgentExecutor(
    private val aiService: AiService,
    private val toolExecutor: ToolExecutor
) {
    companion object {
        private const val MAX_ITERATIONS = 15
        private const val GENERATION_TOKENS_IDEAL = 65536
        private const val CHARS_PER_TOKEN = 3.5
        private const val CONTEXT_CAP = 120_000
        private const val DOOM_LOOP_THRESHOLD = 3
        private const val TOOL_OUTPUT_MAX_LINES = 2000
        private const val TOOL_OUTPUT_MAX_BYTES = 50_000
        private const val PRUNE_PROTECT_TOKENS = 40_000
        private const val PRUNE_MINIMUM_TOKENS = 20_000
        private val PRUNE_PROTECTED_TOOLS = setOf("validate", "validate_html", "validate_js", "get_runtime_errors")

        private val FILE_TOOLS = setOf(
            "write_file", "edit_file", "delete_path", "rename_file",
            "create_file", "append_file", "replace_in_file", "replace_lines", "insert_lines",
            "create_directory", "delete_directory", "delete_workspace_file", "copy_file"
        )

        private val PARALLELIZABLE_TOOLS = setOf(
            "read_file", "list_files", "search", "validate", "diff_file",
            "inspect_workspace", "read_plan",
            "read_workspace_file", "list_workspace_files", "grep_file", "grep_workspace",
            "file_info", "list_snapshots", "validate_html", "validate_js",
            "list_saved_apps", "get_device_info", "get_battery_level",
            "get_current_time", "load_data", "memory_search", "memory_list",
            "list_modules"
        )

        private val CONTEXT_OVERFLOW_PATTERNS = listOf(
            "context_length_exceeded", "maximum context length",
            "token limit", "too many tokens", "max_tokens",
            "context window", "input too long", "request too large",
            "content_too_large", "reduce.*prompt", "reduce.*input"
        )
    }

    private val modelCap: Int get() = aiService.modelMaxTokens
    private val GENERATION_TOKENS get() = minOf(GENERATION_TOKENS_IDEAL, modelCap)

    /** Doom-loop detection state — reset per execute() call */
    private val recentToolCalls = mutableListOf<Pair<String, Int>>()

    /**
     * 执行一个具体任务。使用全新 context window。
     *
     * @param systemMessages  Sub-Agent 的 system prompt 列表
     * @param taskMessage     任务指令（user message）
     * @param tools           可用工具的 JSON 定义
     * @param enableThinking  是否启用深度思考
     * @param maxIterations   最大工具调用轮次（默认 MAX_ITERATIONS）
     * @param onStep          进度回调
     * @param onStepSync      同步进度回调（用于流式思考更新）
     * @return ExecutionSummary 执行摘要
     */
    suspend fun execute(
        systemMessages: List<Map<String, String>>,
        taskMessage: String,
        tools: org.json.JSONArray?,
        enableThinking: Boolean = false,
        maxIterations: Int = MAX_ITERATIONS,
        onStep: suspend (AgentStep) -> Unit,
        onStepSync: ((AgentStep) -> Unit)?
    ): ExecutionSummary {
        recentToolCalls.clear()
        val messages = mutableListOf<Map<String, String>>()
        messages.addAll(systemMessages)
        messages.add(mapOf("role" to "user", "content" to taskMessage))

        var usedFileTools = false
        var consecutiveFailedRounds = 0
        var overflowRetries = 0

        for (iteration in 1..maxIterations) {
            val resp: ChatResponse
            try {
                val budgeted = ensureTokenBudget(messages)
                resp = callAi(budgeted, enableThinking, "SubAgent", onStep, onStepSync, tools)
                overflowRetries = 0
            } catch (coe: AgentOrchestrator.ContextOverflowException) {
                overflowRetries++
                if (overflowRetries > 2) break
                onStep(AgentStep(StepType.THINKING, "Sub-Agent 上下文过长，压缩...", ""))
                compressMessages(messages)
                continue
            }

            val content = resp.content
            val toolCalls = resp.toolCalls

            // No tool calls → AI is done
            if (toolCalls.isEmpty()) {
                if (content.isNotBlank()) {
                    messages.add(mapOf("role" to "assistant", "content" to content))
                }
                break
            }

            // Process tool calls
            messages.add(buildAssistantMessage(content, toolCalls))
            for (call in toolCalls) {
                if (call.toolName in FILE_TOOLS) usedFileTools = true
            }

            val (results, hasFailure, allEditsFailed) = executeToolsBatched(toolCalls, onStep)
            appendToolResults(messages, toolCalls, results)

            val hadFileEdits = toolCalls.any { it.toolName in FILE_TOOLS }
            if (hadFileEdits && allEditsFailed) {
                consecutiveFailedRounds++
            } else if (hadFileEdits) {
                consecutiveFailedRounds = 0
            }

            if (hasFailure) {
                val guidance = if (consecutiveFailedRounds >= 3) {
                    "[系统] ⚠ 连续 $consecutiveFailedRounds 轮文件修改失败。请立即更换策略：对该文件使用 write_file 整体重写，不要再尝试 edit_file(command=replace_text)。"
                } else {
                    "[系统] 工具调用失败。请仔细阅读错误信息，修正参数后重试。edit_file 连续失败时改用 write_file 整体重写。"
                }
                messages.add(mapOf("role" to "user", "content" to guidance))
            }

            // Workspace change summary after file edits
            if (hadFileEdits) {
                buildWorkspaceChangeSummary()?.let { summary ->
                    messages.add(mapOf("role" to "user", "content" to summary))
                }
            }
        }

        return buildSummary(usedFileTools)
    }

    /** 构建执行摘要 */
    private fun buildSummary(usedFileTools: Boolean): ExecutionSummary {
        val wm = toolExecutor.workspaceManager
        val appId = toolExecutor.currentAppId
        val allFiles = if (wm.hasFiles(appId)) wm.getAllFiles(appId) else emptyList()
        return ExecutionSummary(
            usedFileTools = usedFileTools,
            filesInWorkspace = allFiles
        )
    }

    // ═══════════════════════════════════════════════════════
    //  AI 调用
    // ═══════════════════════════════════════════════════════

    private suspend fun callAi(
        messages: List<Map<String, String>>,
        enableThinking: Boolean,
        label: String,
        onStep: suspend (AgentStep) -> Unit,
        onStepSync: ((AgentStep) -> Unit)?,
        tools: org.json.JSONArray? = null
    ): ChatResponse {
        val maxStreamRetries = 6
        var lastException: Exception? = null
        for (attempt in 0 until maxStreamRetries) {
            try {
                return aiService.chatStream(
                    messages = messages,
                    maxTokens = GENERATION_TOKENS,
                    enableThinking = enableThinking,
                    tools = tools,
                    onThinkingDelta = { accumulated ->
                        onStepSync?.invoke(AgentStep(StepType.THINKING, "💡 $label 思考中...", accumulated))
                    },
                    onContentDelta = { accumulated ->
                        onStepSync?.invoke(AgentStep(StepType.THINKING, "$label...", accumulated.takeLast(200)))
                    }
                )
            } catch (e: java.io.IOException) {
                val msg = e.message.orEmpty().lowercase()
                val isContextOverflow = CONTEXT_OVERFLOW_PATTERNS.any { pattern ->
                    Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(msg)
                }
                if (isContextOverflow) {
                    throw AgentOrchestrator.ContextOverflowException("上下文过长，API 拒绝请求: ${e.message}")
                }

                val isApiError = e is com.example.link_pi.network.ApiErrorException
                    || msg.contains("api ") || msg.contains("sse error")
                    || msg.contains("stream disconnected")

                lastException = e
                if (attempt < maxStreamRetries - 1) {
                    if (isApiError) {
                        onStepSync?.invoke(AgentStep(StepType.THINKING, "$label 服务端异常，正在重试(${attempt + 1})...", ""))
                        kotlinx.coroutines.delay(2000L * (attempt + 1))
                    } else {
                        onStepSync?.invoke(AgentStep(StepType.THINKING, "$label 网络中断，等待恢复(${attempt + 1})...", ""))
                        val recovered = AiService.waitForNetwork(60_000)
                        if (recovered) {
                            kotlinx.coroutines.delay(1000)
                        } else {
                            kotlinx.coroutines.delay(5000)
                        }
                    }
                    continue
                }
                throw e
            }
        }
        throw lastException ?: java.io.IOException("Unexpected stream retry exhaustion")
    }

    // ═══════════════════════════════════════════════════════
    //  工具执行
    // ═══════════════════════════════════════════════════════

    private suspend fun executeToolsBatched(
        toolCalls: List<ToolCall>,
        onStep: suspend (AgentStep) -> Unit
    ): Triple<List<Pair<ToolCall, ToolResult>>, Boolean, Boolean> {
        val allParallel = toolCalls.all { it.toolName in PARALLELIZABLE_TOOLS }
        if (toolCalls.size <= 1 || !allParallel) {
            return executeToolsSequential(toolCalls, onStep)
        }
        val results = coroutineScope {
            toolCalls.map { call ->
                async {
                    onStep(AgentStep(StepType.TOOL_CALL, "调用 ${call.toolName}",
                        call.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }))
                    val result = toolExecutor.execute(call)
                    onStep(AgentStep(StepType.TOOL_RESULT,
                        if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                        result.result.take(200)))
                    call to result
                }
            }.awaitAll()
        }
        var hasFailure = false
        var allEditsFailed = true
        var hasAnyFileTool = false
        for ((call, result) in results) {
            if (!result.success) hasFailure = true
            if (call.toolName in FILE_TOOLS) {
                hasAnyFileTool = true
                if (result.success) allEditsFailed = false
            }
        }
        return Triple(results, hasFailure, allEditsFailed && hasAnyFileTool)
    }

    private suspend fun executeToolsSequential(
        toolCalls: List<ToolCall>,
        onStep: suspend (AgentStep) -> Unit
    ): Triple<List<Pair<ToolCall, ToolResult>>, Boolean, Boolean> {
        val results = mutableListOf<Pair<ToolCall, ToolResult>>()
        var hasFailure = false
        var allEditsFailed = true
        var hasAnyFileTool = false
        for (call in toolCalls) {
            // Doom loop detection
            val argsHash = call.arguments.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value}" }.hashCode()
            val signature = call.toolName to argsHash
            recentToolCalls.add(signature)
            if (recentToolCalls.size > DOOM_LOOP_THRESHOLD) recentToolCalls.removeAt(0)
            if (recentToolCalls.size == DOOM_LOOP_THRESHOLD &&
                recentToolCalls.all { it == signature }
            ) {
                onStep(AgentStep(StepType.THINKING,
                    "⚠ 检测到重复循环: ${call.toolName}", "连续 $DOOM_LOOP_THRESHOLD 次相同调用"))
                val doomResult = ToolResult(call.toolName, false,
                    "Error: 检测到重复循环——连续 $DOOM_LOOP_THRESHOLD 次使用相同参数调用 ${call.toolName}。" +
                    "请更换策略：如果是 edit_file 失败，改用 write_file 整体重写；" +
                    "如果是 read_file，说明你已读取过该文件，请使用已有信息继续。")
                results.add(call to doomResult)
                hasFailure = true
                recentToolCalls.clear()
                continue
            }

            onStep(AgentStep(StepType.TOOL_CALL, "调用 ${call.toolName}",
                call.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }))
            val result = toolExecutor.execute(call)
            if (!result.success) hasFailure = true
            if (call.toolName in FILE_TOOLS) {
                hasAnyFileTool = true
                if (result.success) allEditsFailed = false
            }

            val truncatedOutput = truncateToolOutput(result.result, call.toolName)
            val finalResult = if (truncatedOutput != result.result) result.copy(result = truncatedOutput) else result

            onStep(AgentStep(StepType.TOOL_RESULT,
                if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                truncatedOutput.take(200)))
            results.add(call to finalResult)
        }
        return Triple(results, hasFailure, allEditsFailed && hasAnyFileTool)
    }

    // ═══════════════════════════════════════════════════════
    //  消息构建 & 上下文管理
    // ═══════════════════════════════════════════════════════

    private fun buildAssistantMessage(content: String, toolCalls: List<ToolCall>): Map<String, String> {
        val msg = mutableMapOf("role" to "assistant", "content" to content)
        if (toolCalls.isNotEmpty()) {
            msg["_tool_calls"] = serializeToolCalls(toolCalls)
        }
        return msg
    }

    private fun serializeToolCalls(toolCalls: List<ToolCall>): String {
        val arr = org.json.JSONArray()
        for (tc in toolCalls) {
            arr.put(org.json.JSONObject().apply {
                put("id", tc.id ?: "call_${tc.toolName}_${System.nanoTime()}")
                put("type", "function")
                put("function", org.json.JSONObject().apply {
                    put("name", tc.toolName)
                    val argsObj = org.json.JSONObject()
                    for ((k, v) in tc.arguments) argsObj.put(k, v)
                    put("arguments", argsObj.toString())
                })
            })
        }
        return arr.toString()
    }

    private fun appendToolResults(
        messages: MutableList<Map<String, String>>,
        toolCalls: List<ToolCall>,
        results: List<Pair<ToolCall, ToolResult>>
    ) {
        for ((call, result) in results) {
            val callId = call.id ?: "call_${call.toolName}_${System.nanoTime()}"
            val truncatedOutput = truncateToolOutput(result.result, call.toolName)
            messages.add(mapOf(
                "role" to "tool",
                "tool_call_id" to callId,
                "name" to call.toolName,
                "content" to truncatedOutput
            ))
        }
    }

    private fun ensureTokenBudget(messages: MutableList<Map<String, String>>): MutableList<Map<String, String>> {
        val inputBudget = CONTEXT_CAP - GENERATION_TOKENS
        val estimated = messages.sumOf { (it["content"]?.length ?: 0) + 10 } / CHARS_PER_TOKEN
        if (estimated <= inputBudget) return messages
        compressMessages(messages)
        return messages
    }

    /** 压缩消息 — 删除旧工具结果、压缩 assistant 内容 */
    private fun compressMessages(messages: MutableList<Map<String, String>>) {
        val nonSystem = messages.mapIndexedNotNull { i, m -> if (m["role"] != "system") i else null }
        val recentThreshold = if (nonSystem.size > 4) nonSystem[nonSystem.size - 4] else 0

        for (i in messages.indices) {
            val msg = messages[i]
            val role = msg["role"] ?: ""
            val content = msg["content"] ?: ""
            val isRecent = i >= recentThreshold

            if (role == "system" || isRecent) continue

            if (role == "tool") {
                val toolName = msg["name"] ?: "unknown"
                if (toolName in PRUNE_PROTECTED_TOOLS) continue
                if (content.length > 500) {
                    messages[i] = msg.toMutableMap().apply { put("content", content.take(200) + "\n... (已压缩)") }
                }
            } else if (role == "assistant" && msg.containsKey("_tool_calls")) {
                messages[i] = mutableMapOf(
                    "role" to role,
                    "content" to content.take(200).ifBlank { "(工具调用已执行)" }
                )
            }
        }

        // Backward prune
        var userTurnsSeen = 0
        var accumulatedTokens = 0
        val toPrune = mutableListOf<Int>()
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            val role = msg["role"] ?: ""
            if (role == "system") continue
            if (role == "user") userTurnsSeen++
            if (userTurnsSeen < 2) continue
            if (role == "tool") {
                val est = ((msg["content"]?.length ?: 0) / CHARS_PER_TOKEN).toInt()
                accumulatedTokens += est
                if (accumulatedTokens > PRUNE_PROTECT_TOKENS) {
                    val toolName = msg["name"] ?: ""
                    if (toolName !in PRUNE_PROTECTED_TOOLS) toPrune.add(i)
                }
            }
        }
        if (toPrune.sumOf { ((messages[it]["content"]?.length ?: 0) / CHARS_PER_TOKEN).toInt() } >= PRUNE_MINIMUM_TOKENS) {
            for (idx in toPrune) {
                val msg = messages[idx]
                messages[idx] = mapOf(
                    "role" to "tool",
                    "tool_call_id" to (msg["tool_call_id"] ?: ""),
                    "name" to (msg["name"] ?: "unknown"),
                    "content" to "[已压缩的工具结果: ${msg["name"]}]"
                )
            }
        }
    }

    private fun truncateToolOutput(output: String, toolName: String): String {
        val lines = output.lines()
        val bytes = output.length
        if (lines.size <= TOOL_OUTPUT_MAX_LINES && bytes <= TOOL_OUTPUT_MAX_BYTES) return output

        val savedFile = try {
            toolExecutor.workspaceManager.saveTruncatedOutput(output, toolName)
        } catch (_: Exception) { null }

        val keepLines = minOf(TOOL_OUTPUT_MAX_LINES, lines.size)
        val truncated = lines.take(keepLines).joinToString("\n")
        val finalOutput = if (truncated.length > TOOL_OUTPUT_MAX_BYTES) truncated.take(TOOL_OUTPUT_MAX_BYTES) else truncated

        val savedHint = if (savedFile != null) " 完整输出已缓存为 $savedFile，可使用 read_truncated_output('$savedFile') 获取。" else ""
        val hint = when (toolName) {
            "read_file", "read_workspace_file" -> "内容过长已截断。使用 read_file 的 start_line/end_line 参数分段读取，或用 search 搜索特定内容。$savedHint"
            "search", "grep_workspace", "grep_file" -> "匹配结果过多已截断。请使用更精确的搜索模式或添加 file_filter 缩小范围。$savedHint"
            else -> "输出过长已截断（${lines.size} 行, ${bytes / 1024}KB）。$savedHint"
        }
        return "$finalOutput\n\n[截断] $hint"
    }

    private fun buildWorkspaceChangeSummary(): String? {
        val appId = toolExecutor.currentAppId
        val wm = toolExecutor.workspaceManager
        if (!wm.hasFiles(appId)) return null
        val allFiles = wm.getAllFiles(appId)
        if (allFiles.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine("[工作区实时状态] 当前文件列表:")
        for (path in allFiles) {
            try {
                val content = wm.readEntryFile(appId, path) ?: continue
                val lines = content.lines().size
                sb.appendLine("  - $path ($lines 行)")
            } catch (_: Exception) {
                sb.appendLine("  - $path")
            }
        }
        return sb.toString().trimEnd()
    }
}

/** Sub-Agent 执行摘要 — 从 SubAgent 返回给主 Agent */
data class ExecutionSummary(
    val usedFileTools: Boolean,
    val filesInWorkspace: List<String>
)
