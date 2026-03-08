package com.example.link_pi.agent

import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.data.model.Skill
import com.example.link_pi.data.model.SkillMode
import com.example.link_pi.miniapp.MiniAppParser
import com.example.link_pi.network.AiService
import com.example.link_pi.skill.AgentPhase
import com.example.link_pi.skill.IntentClassifier
import com.example.link_pi.skill.PromptAssembler
import com.example.link_pi.skill.UserIntent
import java.util.UUID

/**
 * Agent Orchestrator — manages a multi-phase loop between AI reasoning and tool execution.
 *
 * Phases:
 *  - PLANNING: AI analyzes the request and optionally gathers info via tools (low token usage)
 *  - GENERATING: AI produces the final output with full token budget
 *  - REFINING: If the output was truncated, AI continues/fixes it
 *
 * Features:
 *  - Context compression: tool results are summarized in subsequent rounds
 *  - Smart token allocation: planning uses less tokens, generation gets the full budget
 *  - Truncation detection & recovery
 */
class AgentOrchestrator(
    private val aiService: AiService,
    private val toolExecutor: ToolExecutor
) {
    companion object {
        private const val MAX_TOOL_ITERATIONS = 15
        private const val PLANNING_TOKENS = 4096
        private const val GENERATION_TOKENS = 65536
        private val FILE_TOOLS = setOf("create_file", "write_file", "append_file", "replace_in_file", "replace_lines", "insert_lines", "create_directory", "delete_directory", "delete_workspace_file", "rename_file", "copy_file")
    }

    suspend fun run(
        conversationMessages: List<Map<String, String>>,
        skill: Skill,
        enableThinking: Boolean = false,
        onStep: suspend (AgentStep) -> Unit
    ): OrchestratorResult {
        val messages = conversationMessages.toMutableList()

        // Assign a fresh workspace ID; may be overridden by open_app_workspace tool
        toolExecutor.currentAppId = UUID.randomUUID().toString()
        var usedFileTools = false
        var latestThinking = ""

        // ── Intent classification via AI ──
        val userMessage = messages.lastOrNull { it["role"] == "user" }?.get("content") ?: ""
        val hasActiveWorkspace = toolExecutor.latestAppHint != null
        val intent = IntentClassifier.classify(userMessage, hasActiveWorkspace, skill, aiService)
        onStep(AgentStep(StepType.THINKING, "意图识别: $intent"))

        // ── Phase 1: Planning ──
        val planningSnapshot = if (skill.mode == SkillMode.CHAT) buildMemorySnapshot() else null
        val planningPrompt = PromptAssembler.build(skill, intent, AgentPhase.PLANNING, toolExecutor.toolDefs, planningSnapshot)

        val systemIndex = messages.indexOfFirst { it["role"] == "system" }
        if (systemIndex >= 0) {
            messages[systemIndex] = mapOf("role" to "system", "content" to planningPrompt)
        }

        // ── Phase 1: Planning + Tool Use ──
        var toolIterations = 0
        while (toolIterations < MAX_TOOL_ITERATIONS) {
            toolIterations++
            onStep(AgentStep(StepType.THINKING, "正在规划...", "第 $toolIterations 轮"))

            // Use larger token budget when file tools are in play (replace_in_file needs space)
            val planningBudget = if (usedFileTools) GENERATION_TOKENS else PLANNING_TOKENS
            val chatResp = aiService.chatFull(messages, maxTokens = planningBudget, enableThinking = enableThinking)
            val response = chatResp.content
            if (chatResp.thinkingContent.isNotBlank()) latestThinking = chatResp.thinkingContent
            val toolCalls = ToolCall.parseAll(response)

            // Detect truncated tool call (output cut off mid-JSON)
            if (toolCalls.isEmpty() && ToolCall.hasTruncatedToolCall(response)) {
                // Retry with full token budget
                onStep(AgentStep(StepType.THINKING, "工具调用被截断，重试...", "增加token"))
                val retryResp = aiService.chatFull(messages, maxTokens = GENERATION_TOKENS, enableThinking = enableThinking)
                val retryResponse = retryResp.content
                if (retryResp.thinkingContent.isNotBlank()) latestThinking = retryResp.thinkingContent
                val retryToolCalls = ToolCall.parseAll(retryResponse)
                if (retryToolCalls.isNotEmpty()) {
                    // Process the retried tool calls
                    messages.add(mapOf("role" to "assistant", "content" to retryResponse))
                    val resultParts = StringBuilder()
                    for (call in retryToolCalls) {
                        if (call.toolName in FILE_TOOLS) usedFileTools = true
                        onStep(AgentStep(StepType.TOOL_CALL, "调用 ${call.toolName}",
                            call.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }))
                        val result = toolExecutor.execute(call)
                        onStep(AgentStep(StepType.TOOL_RESULT,
                            if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                            result.result.take(200)))
                        resultParts.appendLine("<tool_result name=\"${call.toolName}\" success=\"${result.success}\">")
                        resultParts.appendLine(result.result)
                        resultParts.appendLine("</tool_result>")
                    }
                    messages.add(mapOf("role" to "user", "content" to resultParts.toString()))
                    continue // back to planning loop
                }
                // Retry also failed — fall through to normal empty handling
            }

            if (toolCalls.isEmpty()) {
                // AI decided no more tools needed — check if workspace was already built
                if (usedFileTools) {
                    val wsApp = buildWorkspaceApp(toolExecutor.currentAppId, response)
                    if (wsApp != null) {
                        onStep(AgentStep(StepType.FINAL_RESPONSE, "工作空间应用生成完成", wsApp.name))
                        return cleanResult(response, wsApp, toolIterations, latestThinking)
                    }
                }
                // Check if this is already a final response with HTML
                val miniApp = MiniAppParser.extractMiniApp(response)
                if (miniApp != null) {
                    onStep(AgentStep(StepType.FINAL_RESPONSE, "生成完成", ""))
                    return cleanResult(response, miniApp, toolIterations, latestThinking)
                }
                // No HTML and no tool calls at planning stage — move to generation phase
                val stripped = ToolCall.stripToolCalls(response)
                if (stripped.isNotBlank()) {
                    onStep(AgentStep(StepType.THINKING, stripped.take(200)))
                    messages.add(mapOf("role" to "assistant", "content" to response))
                }
                break
            }

            // Process tool calls
            val displayText = ToolCall.stripToolCalls(response)
            if (displayText.isNotBlank()) {
                onStep(AgentStep(StepType.THINKING, displayText.take(200)))
            }
            messages.add(mapOf("role" to "assistant", "content" to response))

            val resultParts = StringBuilder()
            var hasFailure = false
            for (call in toolCalls) {
                if (call.toolName in FILE_TOOLS) usedFileTools = true

                onStep(AgentStep(StepType.TOOL_CALL, "调用 ${call.toolName}",
                    call.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }))

                val result = toolExecutor.execute(call)
                if (!result.success) hasFailure = true

                onStep(AgentStep(StepType.TOOL_RESULT,
                    if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                    result.result.take(200)))

                resultParts.appendLine("<tool_result name=\"${call.toolName}\" success=\"${result.success}\">")
                resultParts.appendLine(result.result)
                resultParts.appendLine("</tool_result>")
            }
            // When a tool fails, add recovery guidance
            if (hasFailure) {
                resultParts.appendLine("\n[System] A tool call failed. Read the error message carefully. Fix the parameters and retry. Remember: open_app_workspace requires app_id with an actual UUID value from list_saved_apps.")
            }
            messages.add(mapOf("role" to "user", "content" to resultParts.toString()))
        }

        // If all work was done via file tools, check workspace
        if (usedFileTools) {
            val wsApp = buildWorkspaceApp(toolExecutor.currentAppId, "")
            if (wsApp != null) {
                onStep(AgentStep(StepType.FINAL_RESPONSE, "工作空间应用生成完成", wsApp.name))
                return cleanResult(messages.lastOrNull { it["role"] == "assistant" }?.get("content") ?: "", wsApp, toolIterations, latestThinking)
            }
        }

        // ── Phase 2: Generation (full token budget) ──
        onStep(AgentStep(StepType.THINKING, "正在生成应用...", "全量生成"))

        // Rebuild system prompt for GENERATION phase (more tools, Bridge/CDN docs)
        val generationPrompt = PromptAssembler.build(skill, intent, AgentPhase.GENERATION, toolExecutor.toolDefs, planningSnapshot)

        // Compress context
        val compressedMessages = compressContext(messages)

        // Replace system prompt with generation-phase version
        val compSysIdx = compressedMessages.indexOfFirst { it["role"] == "system" }
        if (compSysIdx >= 0) {
            compressedMessages[compSysIdx] = mapOf("role" to "system", "content" to generationPrompt)
        }

        // Add generation instruction — adapted based on whether file tools are available
        val genInstruction = if (usedFileTools) {
            "Continue creating the application. You can use file tools (create_file, write_file, etc.) to write each file separately, or output a single HTML in ```html fences. If you use file tools, make sure index.html is the entry point. Generate COMPLETE code — never truncate."
        } else {
            "Now produce your complete final response. If generating an app, you can either:\n1. Output the FULL HTML code in ```html fences (single-file app)\n2. Use file tools (create_file) to create multiple files for complex apps (HTML, CSS, JS separately)\nDo NOT truncate or abbreviate any code. Output the complete application."
        }
        compressedMessages.add(mapOf("role" to "user", "content" to genInstruction))

        val genResp = aiService.chatFull(compressedMessages, maxTokens = GENERATION_TOKENS, enableThinking = enableThinking)
        val genResponse = genResp.content
        if (genResp.thinkingContent.isNotBlank()) latestThinking = genResp.thinkingContent

        // Check if generation phase used file tools
        val genToolCalls = ToolCall.parseAll(genResponse)
        if (genToolCalls.isNotEmpty()) {
            // Process tool calls from generation phase
            messages.add(mapOf("role" to "assistant", "content" to genResponse))
            val resultParts = StringBuilder()
            for (call in genToolCalls) {
                if (call.toolName in FILE_TOOLS) usedFileTools = true
                val result = toolExecutor.execute(call)
                onStep(AgentStep(StepType.TOOL_CALL, "调用 ${call.toolName}",
                    call.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }))
                onStep(AgentStep(StepType.TOOL_RESULT,
                    if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                    result.result.take(200)))
                resultParts.appendLine("<tool_result name=\"${call.toolName}\" success=\"${result.success}\">")
                resultParts.appendLine(result.result)
                resultParts.appendLine("</tool_result>")
            }

            // Continue iterating if more tool calls are expected
            messages.add(mapOf("role" to "user", "content" to resultParts.toString()))
            var extraIterations = 0
            while (extraIterations < MAX_TOOL_ITERATIONS) {
                extraIterations++
                val contResp = aiService.chatFull(messages, maxTokens = GENERATION_TOKENS, enableThinking = enableThinking)
                val contResponse = contResp.content
                if (contResp.thinkingContent.isNotBlank()) latestThinking = contResp.thinkingContent
                val contToolCalls = ToolCall.parseAll(contResponse)
                if (contToolCalls.isEmpty()) {
                    // Done
                    if (usedFileTools) {
                        val wsApp = buildWorkspaceApp(toolExecutor.currentAppId, contResponse)
                        if (wsApp != null) {
                            onStep(AgentStep(StepType.FINAL_RESPONSE, "工作空间应用生成完成", wsApp.name))
                            return cleanResult(contResponse, wsApp, toolIterations + extraIterations, latestThinking)
                        }
                    }
                    val miniApp = MiniAppParser.extractMiniApp(contResponse)
                    if (miniApp != null) {
                        onStep(AgentStep(StepType.FINAL_RESPONSE, "生成完成", miniApp.name))
                        return cleanResult(contResponse, miniApp, toolIterations + extraIterations, latestThinking)
                    }
                    break
                }
                messages.add(mapOf("role" to "assistant", "content" to contResponse))
                val contResults = StringBuilder()
                for (call in contToolCalls) {
                    if (call.toolName in FILE_TOOLS) usedFileTools = true
                    val result = toolExecutor.execute(call)
                    onStep(AgentStep(StepType.TOOL_CALL, "调用 ${call.toolName}",
                        call.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }))
                    onStep(AgentStep(StepType.TOOL_RESULT,
                        if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                        result.result.take(200)))
                    contResults.appendLine("<tool_result name=\"${call.toolName}\" success=\"${result.success}\">")
                    contResults.appendLine(result.result)
                    contResults.appendLine("</tool_result>")
                }
                messages.add(mapOf("role" to "user", "content" to contResults.toString()))
            }

            // Final check for workspace app
            if (usedFileTools) {
                val wsApp = buildWorkspaceApp(toolExecutor.currentAppId, "")
                if (wsApp != null) {
                    onStep(AgentStep(StepType.FINAL_RESPONSE, "工作空间应用生成完成", wsApp.name))
                    return cleanResult(genResponse, wsApp, toolIterations, latestThinking)
                }
            }
        }

        var miniApp = MiniAppParser.extractMiniApp(genResponse)

        if (miniApp != null) {
            onStep(AgentStep(StepType.FINAL_RESPONSE, "生成完成", "${miniApp.name}"))
            return cleanResult(genResponse, miniApp, toolIterations + 1, latestThinking)
        }

        // ── Phase 3: Check for truncation and attempt recovery ──
        val isTruncated = genResponse.contains("```html", ignoreCase = true) &&
                !genResponse.contains("\n```", ignoreCase = false) &&
                !genResponse.trimEnd().endsWith("```")

        if (isTruncated) {
            onStep(AgentStep(StepType.THINKING, "检测到截断，继续生成...", "修复"))

            val continueMessages = mutableListOf(
                compressedMessages.first(),
                mapOf("role" to "assistant", "content" to genResponse),
                mapOf("role" to "user", "content" to
                    "Your previous response was truncated. Continue EXACTLY from where you left off. Do NOT repeat any code already written. Just output the remaining code, ending with \n``` to close the code block.")
            )

            val continuation = aiService.chat(continueMessages, maxTokens = GENERATION_TOKENS, enableThinking = false)
            val fullResponse = genResponse + "\n" + continuation
            miniApp = MiniAppParser.extractMiniApp(fullResponse)

            if (miniApp != null) {
                onStep(AgentStep(StepType.FINAL_RESPONSE, "拼接完成", miniApp.name))
                return cleanResult(fullResponse, miniApp, toolIterations + 2, latestThinking)
            }
        }

        // Fallback
        onStep(AgentStep(StepType.FINAL_RESPONSE, "生成完成", ""))
        return cleanResult(
            genResponse,
            MiniAppParser.extractMiniApp(genResponse),
            toolIterations + 1,
            latestThinking
        )
    }

    /** Strip <tool_call> tags from response before returning to UI. */
    private fun cleanResult(response: String, miniApp: MiniApp?, steps: Int, thinking: String = ""): OrchestratorResult {
        return OrchestratorResult(ToolCall.stripToolCalls(response), miniApp, steps, thinking)
    }

    /**
     * Build a MiniApp from workspace files if the AI used file tools.
     * If appId matches an existing saved app, preserve its metadata.
     */
    private fun buildWorkspaceApp(appId: String, response: String): MiniApp? {
        val wm = toolExecutor.workspaceManager
        if (!wm.hasFiles(appId)) return null
        val entryContent = wm.readEntryFile(appId) ?: return null

        // Check if this is a modification of an existing app
        val existingApp = toolExecutor.miniAppStorage.loadById(appId)

        // Extract app name from index.html <title> or existing app
        val titleRegex = Regex("<title>(.+?)</title>", RegexOption.IGNORE_CASE)
        val titleFromHtml = titleRegex.find(entryContent)?.groupValues?.get(1)?.trim()
        val name = titleFromHtml ?: existingApp?.name ?: "Multi-File App"

        val files = wm.getAllFiles(appId)
        val description = if (existingApp != null) {
            "${existingApp.description} (已修改, ${files.size} 个文件)"
        } else {
            "多文件应用 (${files.size} 个文件)"
        }

        // Generate/update manifest
        val version = if (existingApp != null) {
            val vRegex = Regex("v(\\d+)")
            val oldManifest = try { java.io.File(wm.getWorkspacePath(appId), "APP_INFO.md").readText() } catch (_: Exception) { "" }
            val oldVer = vRegex.find(oldManifest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            oldVer + 1
        } else 1
        wm.generateManifest(appId, name, version)

        return MiniApp(
            id = appId,
            name = name,
            description = description,
            htmlContent = entryContent,
            createdAt = existingApp?.createdAt ?: System.currentTimeMillis(),
            isWorkspaceApp = true,
            entryFile = "index.html"
        )
    }

    /**
     * Compress conversation context by summarizing tool results to save tokens.
     */
    private fun compressContext(messages: List<Map<String, String>>): MutableList<Map<String, String>> {
        val compressed = mutableListOf<Map<String, String>>()
        for (msg in messages) {
            val content = msg["content"] ?: ""
            val role = msg["role"] ?: ""

            if (role == "user" && content.contains("<tool_result")) {
                // Summarize tool results
                val summary = summarizeToolResults(content)
                compressed.add(mapOf("role" to role, "content" to summary))
            } else if (role == "assistant" && content.contains("<tool_call>")) {
                // Keep only the reasoning text, strip tool call blocks
                val stripped = ToolCall.stripToolCalls(content)
                compressed.add(mapOf("role" to role, "content" to stripped.ifBlank { "(tool calls executed)" }))
            } else {
                compressed.add(msg)
            }
        }
        return compressed
    }

    private fun summarizeToolResults(content: String): String {
        val regex = Regex("<tool_result name=\"(.*?)\"[^>]*>([\\s\\S]*?)</tool_result>")
        val summaries = regex.findAll(content).map { match ->
            val name = match.groupValues[1]
            val result = match.groupValues[2].trim()
            val short = if (result.length > 300) result.take(300) + "..." else result
            "[$name]: $short"
        }.toList()

        return if (summaries.isNotEmpty()) {
            "Tool results:\n" + summaries.joinToString("\n")
        } else {
            content.take(500)
        }
    }

    /**
     * Build a compact memory snapshot to inject into the system prompt.
     * Prioritizes preferences/personal info, keeps token usage low.
     */
    private suspend fun buildMemorySnapshot(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val memories = toolExecutor.memoryStorage.listAll()
        if (memories.isEmpty()) return@withContext ""

        // Separate high-priority (preferences/personal) from general memories
        val prefTags = setOf("偏好", "个人", "习惯", "名字", "姓名", "风格", "主题",
            "preference", "personal", "name", "style", "theme", "habit")
        val (priority, general) = memories.partition { m ->
            m.tags.any { t -> prefTags.any { pt -> t.contains(pt) || pt.contains(t) } }
        }

        val lines = mutableListOf<String>()
        // Always include all preference memories
        priority.take(20).forEach { m ->
            lines.add("- ${m.content}")
        }
        // Fill remaining with general memories (up to ~30 total)
        general.take(30 - lines.size).forEach { m ->
            lines.add("- ${m.content}")
        }

        if (lines.isEmpty()) return@withContext ""
        "\n[已知记忆]\n${lines.joinToString("\n")}\n"
    }


}

data class OrchestratorResult(
    val finalResponse: String,
    val miniApp: com.example.link_pi.data.model.MiniApp?,
    val steps: Int,
    val thinkingContent: String = ""
)
