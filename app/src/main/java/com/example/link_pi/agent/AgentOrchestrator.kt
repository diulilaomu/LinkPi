package com.example.link_pi.agent

import com.example.link_pi.bridge.RuntimeErrorCollector
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.data.model.Skill
import com.example.link_pi.data.model.SkillMode
import com.example.link_pi.miniapp.FileBlockParser
import com.example.link_pi.miniapp.MiniAppParser
import com.example.link_pi.network.AiService
import com.example.link_pi.network.ChatResponse
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
        private const val PLANNING_TOKENS_IDEAL = 16384
        private const val GENERATION_TOKENS_IDEAL = 65536
        private val FILE_TOOLS = setOf("create_file", "write_file", "append_file", "replace_in_file", "replace_lines", "insert_lines", "create_directory", "delete_directory", "delete_workspace_file", "rename_file", "copy_file")
    }

    /** Respect the model's actual max_tokens ceiling so we never send an unsupported value. */
    private val modelCap: Int get() = aiService.modelMaxTokens
    private val PLANNING_TOKENS get() = minOf(PLANNING_TOKENS_IDEAL, modelCap)
    private val GENERATION_TOKENS get() = minOf(GENERATION_TOKENS_IDEAL, modelCap)

    suspend fun run(
        conversationMessages: List<Map<String, String>>,
        skill: Skill,
        enableThinking: Boolean = false,
        injectionSkills: List<Skill> = emptyList(),
        overrideIntent: UserIntent? = null,
        onStep: suspend (AgentStep) -> Unit,
        onStepSync: ((AgentStep) -> Unit)? = null
    ): OrchestratorResult {
        val messages = conversationMessages.toMutableList()

        // ── Extract and strip image data from messages ──
        // Images are large base64 blobs that should not be sent in every API call.
        // Save image data from the last user message, strip _images from all messages.
        val lastUserIdx = messages.indexOfLast { it["role"] == "user" }
        val savedImages = if (lastUserIdx >= 0) messages[lastUserIdx]["_images"] else null
        for (i in messages.indices) {
            if (messages[i].containsKey("_images")) {
                messages[i] = messages[i].filterKeys { it != "_images" }.toMutableMap()
            }
        }

        // Assign a fresh workspace ID only if not pre-configured by caller (e.g. WorkbenchEngine)
        if (toolExecutor.currentAppId == "default") {
            toolExecutor.currentAppId = UUID.randomUUID().toString()
        }
        var usedFileTools = false
        var latestThinking = ""
        var consecutiveFailedRounds = 0   // Track rounds where ALL file-tool calls failed

        // ── Intent classification ──
        val userMessage = messages.lastOrNull { it["role"] == "user" }?.get("content") ?: ""
        val intent = if (overrideIntent != null) {
            onStep(AgentStep(StepType.THINKING, "意图: $overrideIntent (预设)"))
            overrideIntent
        } else {
            val hasActiveWorkspace = toolExecutor.latestAppHint != null
            val classified = IntentClassifier.classifyLocal(userMessage, hasActiveWorkspace)
            onStep(AgentStep(StepType.THINKING, "意图识别: $classified"))
            classified
        }

        // ── Resolve injected skill prompts for this intent ──
        // Cap total injected prompt size to 4KB to prevent context overflow
        val rawInjected = injectionSkills
            .filter { it.id != skill.id && intent in it.intentInjections }
        val injectedPrompts = mutableListOf<String>()
        var injectedSize = 0
        for (s in rawInjected) {
            if (injectedSize + s.systemPrompt.length > 4096) break
            injectedPrompts.add(s.systemPrompt)
            injectedSize += s.systemPrompt.length
        }

        // ── Phase 1: Planning ──
        // Pre-load memory for CHAT mode, or for app intents (user preferences affect app design)
        val planningSnapshot = if (skill.mode == SkillMode.CHAT || intent.needsApp()) buildMemorySnapshot() else null
        val workspaceSnapshot = buildWorkspaceSnapshot()
        val planningPrompt = PromptAssembler.build(skill, intent, AgentPhase.PLANNING, toolExecutor.toolDefs, planningSnapshot, workspaceSnapshot = workspaceSnapshot, injectedPrompts = injectedPrompts)

        val systemIndex = messages.indexOfFirst { it["role"] == "system" }
        if (systemIndex >= 0) {
            messages[systemIndex] = mapOf("role" to "system", "content" to planningPrompt)
        }

        // Track AI's capability selection from planning
        var aiSelection: PromptAssembler.CapabilitySelection? = null
        // Track generation mode from planning (FAST skips tool-call loops)
        var generationMode = PromptAssembler.GenerationMode.FULL

        // Re-inject images into last user message so the first planning call can see them
        if (savedImages != null && lastUserIdx >= 0) {
            val msg = messages[lastUserIdx].toMutableMap()
            msg["_images"] = savedImages
            messages[lastUserIdx] = msg
        }

        // ── Phase 1: Planning + Tool Use ──
        var toolIterations = 0
        while (toolIterations < MAX_TOOL_ITERATIONS) {
            toolIterations++
            onStep(AgentStep(StepType.THINKING, "正在规划...", "第 $toolIterations 轮"))

            // Use larger token budget when file tools are in play (replace_in_file needs space)
            // Planning phase never uses deep thinking — it's just capability selection
            val planningBudget = if (usedFileTools) GENERATION_TOKENS else PLANNING_TOKENS
            val chatResp = callAi(messages, planningBudget, false, "Planning", onStep, onStepSync)

            // Strip images after first planning call — they've been sent once, no need to resend
            if (toolIterations == 1 && savedImages != null && lastUserIdx >= 0 && messages[lastUserIdx].containsKey("_images")) {
                messages[lastUserIdx] = messages[lastUserIdx].filterKeys { it != "_images" }.toMutableMap()
            }

            val response = chatResp.content
            val toolCalls = ToolCall.parseAll(response)

            // Detect truncated tool call (output cut off mid-JSON)
            if (toolCalls.isEmpty() && ToolCall.hasTruncatedToolCall(response)) {
                // Retry with full token budget
                onStep(AgentStep(StepType.THINKING, "工具调用被截断，重试...", "增加token"))
                val retryResp = callAi(messages, GENERATION_TOKENS, false, "Planning重试", onStep, onStepSync)
                val retryResponse = retryResp.content
                if (retryResp.thinkingContent.isNotBlank()) {
                    latestThinking = retryResp.thinkingContent
                }
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
                    // Run self-check before early return (don't skip validation)
                    val earlyPrompt = PromptAssembler.build(skill, intent, AgentPhase.GENERATION, toolExecutor.toolDefs, planningSnapshot, aiSelection, buildWorkspaceSnapshot(), injectedPrompts)
                    val wsApp = runPostGenerationCheck(messages, toolExecutor.currentAppId, earlyPrompt, onStep, onStepSync)
                    if (wsApp != null) {
                        onStep(AgentStep(StepType.FINAL_RESPONSE, "工作空间应用生成完成", wsApp.name))
                        return cleanResult(response, wsApp, toolIterations, latestThinking)
                    } else {
                        onStep(AgentStep(StepType.THINKING, "工作区诊断", buildWorkspaceAppDiagnosis(toolExecutor.currentAppId)))
                    }
                }
                // For non-app intents (CONVERSATION etc.), break out of planning loop
                // Apps are only generated through the workbench, never inline in chat
                if (!intent.needsApp()) {
                    // Capture thinking content if present
                    if (chatResp.thinkingContent.isNotBlank()) {
                        latestThinking = chatResp.thinkingContent
                    }
                    // Fallback: if content is blank but thinking has substance, use it
                    var finalResp = response
                    if (finalResp.isBlank() && chatResp.thinkingContent.length > 200) {
                        finalResp = chatResp.thinkingContent
                    }
                    // Add AI response to messages so it's available after the loop
                    messages.add(mapOf("role" to "assistant", "content" to finalResp))
                    break
                }
                // Move to generation phase (or return text for non-app intents)
                val stripped = ToolCall.stripToolCalls(response)
                if (stripped.isNotBlank()) {
                    onStep(AgentStep(StepType.THINKING, stripped.take(200)))
                    messages.add(mapOf("role" to "assistant", "content" to response))
                }
                // Parse AI's capability selection and generation mode, then clean from context
                if (intent.needsApp()) {
                    aiSelection = PromptAssembler.parseCapabilitySelection(response)
                    generationMode = PromptAssembler.parseGenerationMode(response)
                    // Strip <capability_selection> and <generation_mode> blocks from messages
                    val lastAssistantIdx = messages.indexOfLast { it["role"] == "assistant" }
                    if (lastAssistantIdx >= 0) {
                        val cleaned = messages[lastAssistantIdx]["content"]!!
                            .replace(Regex("<capability_selection>[\\s\\S]*?</capability_selection>"), "")
                            .replace(Regex("<generation_mode>[\\s\\S]*?</generation_mode>"), "")
                            .trim()
                        messages[lastAssistantIdx] = mapOf("role" to "assistant", "content" to cleaned)
                    }
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
            var allEditsFailed = true // Track if every file-edit tool in this round failed
            toolExecutor.toolProgressCallback = onStepSync
            for (call in toolCalls) {
                if (call.toolName in FILE_TOOLS) usedFileTools = true

                onStep(AgentStep(StepType.TOOL_CALL, "调用 ${call.toolName}",
                    call.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }))

                val result = toolExecutor.execute(call)
                if (!result.success) {
                    hasFailure = true
                } else if (call.toolName in FILE_TOOLS) {
                    allEditsFailed = false  // At least one file tool succeeded
                }

                onStep(AgentStep(StepType.TOOL_RESULT,
                    if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                    result.result.take(200)))

                resultParts.appendLine("<tool_result name=\"${call.toolName}\" success=\"${result.success}\">")
                resultParts.appendLine(result.result)
                resultParts.appendLine("</tool_result>")
            }
            toolExecutor.toolProgressCallback = null

            // Track consecutive rounds where file edits all failed
            val hadFileEdits = toolCalls.any { it.toolName in FILE_TOOLS }
            if (hadFileEdits && allEditsFailed) {
                consecutiveFailedRounds++
            } else if (hadFileEdits) {
                consecutiveFailedRounds = 0
            }

            // When a tool fails, add recovery guidance
            if (hasFailure) {
                if (consecutiveFailedRounds >= 3) {
                    // Circuit breaker: force AI to switch strategy
                    resultParts.appendLine("\n[系统] ⚠ 连续 $consecutiveFailedRounds 轮文件修改失败。请立即更换策略：对该文件使用 write_file 整体重写，不要再尝试 replace_in_file。")
                    onStep(AgentStep(StepType.THINKING, "连续修改失败，强制切换策略", "第 $consecutiveFailedRounds 轮"))
                } else {
                    resultParts.appendLine("\n[系统] 工具调用失败。请仔细阅读错误信息，修正参数后重试。注意：open_app_workspace 的 app_id 必须是 list_saved_apps 返回的实际 UUID 值。replace_in_file 连续失败时改用 replace_lines 或 write_file。")
                }
            }
            messages.add(mapOf("role" to "user", "content" to resultParts.toString()))

            // ── Check for workbench redirect from launch_workbench tool ──
            toolExecutor.pendingWorkbenchRedirect?.let { redirect ->
                toolExecutor.pendingWorkbenchRedirect = null
                onStep(AgentStep(StepType.FINAL_RESPONSE, "已弹出工作台引导", redirect.title))
                return OrchestratorResult(
                    "", null, toolIterations, latestThinking,
                    workbenchRedirect = redirect
                )
            }
        }

        // If all work was done via file tools, run self-check then return
        if (usedFileTools) {
            val earlyGenPrompt = PromptAssembler.build(skill, intent, AgentPhase.GENERATION, toolExecutor.toolDefs, planningSnapshot, aiSelection, buildWorkspaceSnapshot(), injectedPrompts)
            val wsApp = runPostGenerationCheck(messages, toolExecutor.currentAppId, earlyGenPrompt, onStep, onStepSync)
            if (wsApp != null) {
                onStep(AgentStep(StepType.FINAL_RESPONSE, "工作空间应用生成完成", wsApp.name))
                return cleanResult(messages.lastOrNull { it["role"] == "assistant" }?.get("content") ?: "", wsApp, toolIterations, latestThinking)
            } else {
                onStep(AgentStep(StepType.THINKING, "工作区诊断", buildWorkspaceAppDiagnosis(toolExecutor.currentAppId)))
            }
        }

        // ── Non-app intents: return planning response directly (no Generation phase, no inline app) ──
        if (!intent.needsApp()) {
            val lastResponse = messages.lastOrNull { it["role"] == "assistant" }?.get("content") ?: ""
            onStep(AgentStep(StepType.FINAL_RESPONSE, "生成完成", ""))
            return cleanResult(lastResponse, null, toolIterations, latestThinking)
        }

        // ── Phase 2: Generation (full token budget) — only for app intents ──
        val isFast = generationMode == PromptAssembler.GenerationMode.FAST && intent == UserIntent.CREATE_APP
        onStep(AgentStep(StepType.THINKING, "正在生成应用...", if (isFast) "快速生成" else "全量生成"))

        // Rebuild system prompt for GENERATION phase with AI's capability selections
        val genWorkspaceSnapshot = buildWorkspaceSnapshot()
        val generationPrompt = PromptAssembler.build(skill, intent, AgentPhase.GENERATION, toolExecutor.toolDefs, planningSnapshot, aiSelection, genWorkspaceSnapshot, injectedPrompts, fast = isFast)

        // Compress context
        val compressedMessages = compressContext(messages)

        // Replace system prompt with generation-phase version
        val compSysIdx = compressedMessages.indexOfFirst { it["role"] == "system" }
        if (compSysIdx >= 0) {
            compressedMessages[compSysIdx] = mapOf("role" to "system", "content" to generationPrompt)
        }

        // ── FAST path: single-pass generation, no tool calls ──
        if (isFast) {
            val fastInstruction = "根据上方的架构规划，现在生成完整应用。使用 <file path=\"...\">content</file> 格式输出所有文件。输出完整、可运行的代码，绝对不要截断或省略。"
            compressedMessages.add(mutableMapOf("role" to "user", "content" to fastInstruction))

            var fastResp = callAi(compressedMessages, GENERATION_TOKENS, enableThinking, "Generation", onStep, onStepSync)
            var fastResponse = fastResp.content

            if (fastResponse.isBlank() && enableThinking) {
                fastResp = callAi(compressedMessages, GENERATION_TOKENS, false, "Generation重试", onStep, onStepSync)
                fastResponse = fastResp.content
            }
            if (fastResponse.isBlank() && fastResp.thinkingContent.length > 200) {
                fastResponse = fastResp.thinkingContent
            }
            if (fastResp.thinkingContent.isNotBlank()) {
                latestThinking = fastResp.thinkingContent
            }

            // Parse structured file output
            val parsedFiles = FileBlockParser.parseFiles(fastResponse)
            if (parsedFiles.isNotEmpty()) {
                val wm = toolExecutor.workspaceManager
                val appId = toolExecutor.currentAppId
                for (f in parsedFiles) {
                    wm.writeFile(appId, f.path, f.content)
                }
                usedFileTools = true
                onStep(AgentStep(StepType.THINKING, "已写入 ${parsedFiles.size} 个文件", parsedFiles.joinToString(", ") { it.path }))

                // Run self-check (validate HTML + runtime errors)
                val fastGenPrompt = PromptAssembler.build(skill, intent, AgentPhase.GENERATION, toolExecutor.toolDefs, planningSnapshot, aiSelection, buildWorkspaceSnapshot(), injectedPrompts)
                val wsApp = runPostGenerationCheck(compressedMessages, appId, fastGenPrompt, onStep, onStepSync)
                if (wsApp != null) {
                    onStep(AgentStep(StepType.FINAL_RESPONSE, "快速生成完成", wsApp.name))
                    return cleanResult(fastResponse, wsApp, toolIterations + 1, latestThinking)
                }
                // Fallback: build workspace app directly
                val fallbackApp = buildWorkspaceApp(appId, fastResponse)
                if (fallbackApp != null) {
                    onStep(AgentStep(StepType.FINAL_RESPONSE, "快速生成完成", fallbackApp.name))
                    return cleanResult(fastResponse, fallbackApp, toolIterations + 1, latestThinking)
                }
            }

            // FAST parse failed — fall through to extract HTML inline or return text
            val miniApp = MiniAppParser.extractMiniApp(fastResponse)
            if (miniApp != null) {
                onStep(AgentStep(StepType.FINAL_RESPONSE, "生成完成", miniApp.name))
                return cleanResult(fastResponse, miniApp, toolIterations + 1, latestThinking)
            }
            onStep(AgentStep(StepType.FINAL_RESPONSE, "生成完成", ""))
            return cleanResult(fastResponse, null, toolIterations + 1, latestThinking)
        }

        // ── FULL path: multi-round tool call generation ──
        // Add generation instruction — references the plan from Planning phase
        // Check intent BEFORE usedFileTools, so MODIFY_APP gets correct instruction even when file tools were used in planning
        val genInstruction = when {
            intent == UserIntent.MODIFY_APP -> "根据上方的规划，现在对现有应用执行所有修改。规则：\n1. 修改前必须先 read_workspace_file 读取最新代码\n2. 用 replace_in_file 精确修改（old_text 必须与文件内容完全匹配）\n3. replace_in_file 失败一次后，改用 replace_lines（按行号替换）\n4. 如果修改超过文件 60% 的内容，直接用 write_file 整体重写\n5. 所有修改完成后用 validate_html 检查"
            usedFileTools -> "根据上方的架构规划，继续创建应用。使用文件工具（create_file、write_file 等）分别写入每个文件。确保 index.html 为入口文件。生成完整代码——绝对不要截断。所有文件写入完成后，用 validate_html 验证 index.html。"
            else -> "根据上方的架构规划，现在生成完整应用。输出方式：\n1. 单文件：在 ```html 代码块中输出完整 HTML 代码\n2. 多文件：使用文件工具（create_file）分别创建 HTML、CSS、JS 文件\n生成完整、可运行的代码。绝对不要截断或省略。"
        }
        val genInstructionMsg = mutableMapOf("role" to "user", "content" to genInstruction)
        compressedMessages.add(genInstructionMsg)

        var genResp = callAi(compressedMessages, GENERATION_TOKENS, enableThinking, "Generation", onStep, onStepSync)
        var genResponse = genResp.content

        // If content is empty but we sent enable_thinking, the model may not support it — retry without
        if (genResponse.isBlank() && enableThinking) {
            onStep(AgentStep(StepType.THINKING, "响应为空，关闭深度思考重试..."))
            genResp = callAi(compressedMessages, GENERATION_TOKENS, false, "Generation重试", onStep, onStepSync)
            genResponse = genResp.content
        }

        // Fallback: if content is still empty but thinkingContent has substance, use it
        if (genResponse.isBlank() && genResp.thinkingContent.length > 200) {
            genResponse = genResp.thinkingContent
        }

        if (genResp.thinkingContent.isNotBlank()) {
            latestThinking = genResp.thinkingContent
            onStep(AgentStep(StepType.THINKING, "💡 Generation 思考完成", genResp.thinkingContent.takeLast(2000)))
        }

        // Check if generation phase used file tools
        val genToolCalls = ToolCall.parseAll(genResponse)
        if (genToolCalls.isNotEmpty()) {
            // Use compressed messages for generation continuation to avoid token waste
            val genMessages = compressedMessages.toMutableList()
            // Process tool calls from generation phase
            genMessages.add(mapOf("role" to "assistant", "content" to genResponse))
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
            genMessages.add(mapOf("role" to "user", "content" to resultParts.toString()))
            var extraIterations = 0
            var genConsecFails = 0
            while (extraIterations < MAX_TOOL_ITERATIONS) {
                extraIterations++
                val contResp = callAi(genMessages, GENERATION_TOKENS, enableThinking, "Generation继续", onStep, onStepSync)
                val contResponse = contResp.content
                if (contResp.thinkingContent.isNotBlank()) {
                    latestThinking = contResp.thinkingContent
                }
                val contToolCalls = ToolCall.parseAll(contResponse)
                if (contToolCalls.isEmpty()) {
                    // Done — run self-check before returning
                    if (usedFileTools) {
                        val wsApp = runPostGenerationCheck(genMessages, toolExecutor.currentAppId, generationPrompt, onStep, onStepSync)
                        if (wsApp != null) {
                            onStep(AgentStep(StepType.FINAL_RESPONSE, "工作空间应用生成完成", wsApp.name))
                            return cleanResult(contResponse, wsApp, toolIterations + extraIterations, latestThinking)
                        } else {
                            onStep(AgentStep(StepType.THINKING, "工作区诊断", buildWorkspaceAppDiagnosis(toolExecutor.currentAppId)))
                        }
                    }
                    val miniApp = MiniAppParser.extractMiniApp(contResponse)
                    if (miniApp != null) {
                        onStep(AgentStep(StepType.FINAL_RESPONSE, "生成完成", miniApp.name))
                        return cleanResult(contResponse, miniApp, toolIterations + extraIterations, latestThinking)
                    }
                    break
                }
                genMessages.add(mapOf("role" to "assistant", "content" to contResponse))
                val contResults = StringBuilder()
                var contAllFailed = true
                for (call in contToolCalls) {
                    if (call.toolName in FILE_TOOLS) usedFileTools = true
                    val result = toolExecutor.execute(call)
                    if (result.success && call.toolName in FILE_TOOLS) contAllFailed = false
                    onStep(AgentStep(StepType.TOOL_CALL, "调用 ${call.toolName}",
                        call.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }))
                    onStep(AgentStep(StepType.TOOL_RESULT,
                        if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                        result.result.take(200)))
                    contResults.appendLine("<tool_result name=\"${call.toolName}\" success=\"${result.success}\">")
                    contResults.appendLine(result.result)
                    contResults.appendLine("</tool_result>")
                }
                val contHadEdits = contToolCalls.any { it.toolName in FILE_TOOLS }
                if (contHadEdits && contAllFailed) {
                    genConsecFails++
                    if (genConsecFails >= 3) {
                        contResults.appendLine("\n[系统] ⚠ 连续 $genConsecFails 轮文件修改失败。请立即更换策略：对该文件使用 write_file 整体重写，不要再尝试 replace_in_file。")
                    }
                } else if (contHadEdits) {
                    genConsecFails = 0
                }
                genMessages.add(mapOf("role" to "user", "content" to contResults.toString()))
            }

            // Final check for workspace app — run self-check before returning
            if (usedFileTools) {
                val wsApp = runPostGenerationCheck(genMessages, toolExecutor.currentAppId, generationPrompt, onStep, onStepSync)
                if (wsApp != null) {
                    onStep(AgentStep(StepType.FINAL_RESPONSE, "工作空间应用生成完成", wsApp.name))
                    return cleanResult(genResponse, wsApp, toolIterations, latestThinking)
                } else {
                    onStep(AgentStep(StepType.THINKING, "工作区诊断", buildWorkspaceAppDiagnosis(toolExecutor.currentAppId)))
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
                    "你上次的回复被截断了。请从上次中断的地方精确继续。不要重复已写的代码。只输出剩余代码，以 \n``` 结尾来关闭代码块。")
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
     * Post-generation self-check: validate HTML + collect runtime errors.
     * If issues found, inject them and let AI fix in one more iteration.
     * Returns the (possibly updated) workspace app.
     */
    private suspend fun runPostGenerationCheck(
        messages: MutableList<Map<String, String>>,
        appId: String,
        generationPrompt: String,
        onStep: suspend (AgentStep) -> Unit,
        onStepSync: ((AgentStep) -> Unit)?
    ): MiniApp? {
        val wm = toolExecutor.workspaceManager
        if (!wm.hasFiles(appId)) return null

        onStep(AgentStep(StepType.THINKING, "正在自检...", "验证HTML + 运行时错误"))

        // 1. Validate HTML — find entry file (index.html or first .html file)
        val allFiles = wm.getAllFiles(appId)
        val entryFile = if ("index.html" in allFiles) {
            "index.html"
        } else {
            allFiles.firstOrNull { it.endsWith(".html") }
        }
        val validationResult = if (entryFile != null) wm.validateHtml(appId, entryFile) else null
        val hasValidationIssues = validationResult != null && !validationResult.startsWith("✓")

        // 2. Collect runtime errors
        val runtimeErrors = RuntimeErrorCollector.getErrors(appId)
        val hasRuntimeErrors = runtimeErrors.isNotEmpty()
        if (hasRuntimeErrors) RuntimeErrorCollector.clear(appId)

        if (!hasValidationIssues && !hasRuntimeErrors) {
            onStep(AgentStep(StepType.THINKING, "✓ 自检通过", "无问题"))
            return buildWorkspaceApp(appId, "")
        }

        // Build fix instruction
        val fixParts = StringBuilder("自检发现以下问题，请修复：\n\n")
        if (hasValidationIssues) {
            fixParts.appendLine("**HTML 校验结果：**")
            fixParts.appendLine(validationResult)
            fixParts.appendLine()
        }
        if (hasRuntimeErrors) {
            fixParts.appendLine("**JS 运行时错误：**")
            runtimeErrors.forEachIndexed { i, e -> fixParts.appendLine("${i + 1}. $e") }
            fixParts.appendLine()
        }
        fixParts.appendLine("请先 read_workspace_file 读取相关文件，然后修复上述问题。修复后用 validate_html 再次验证。")

        onStep(AgentStep(StepType.THINKING, "发现问题，自动修复中...", fixParts.toString().take(200)))

        // Compress and let AI fix
        val fixMessages = compressContext(messages)
        val fixSysIdx = fixMessages.indexOfFirst { it["role"] == "system" }
        if (fixSysIdx >= 0) {
            fixMessages[fixSysIdx] = mapOf("role" to "system", "content" to generationPrompt)
        }
        fixMessages.add(mapOf("role" to "user", "content" to fixParts.toString()))

        // Give AI up to 5 rounds to fix
        var fixIterations = 0
        while (fixIterations < 5) {
            fixIterations++
            val fixResp = callAi(fixMessages, GENERATION_TOKENS, false, "自检修复", onStep, onStepSync)
            val fixResponse = fixResp.content
            val fixToolCalls = ToolCall.parseAll(fixResponse)

            if (fixToolCalls.isEmpty()) break // AI done fixing

            fixMessages.add(mapOf("role" to "assistant", "content" to fixResponse))
            val resultParts = StringBuilder()
            for (call in fixToolCalls) {
                val result = toolExecutor.execute(call)
                onStep(AgentStep(StepType.TOOL_CALL, "修复: ${call.toolName}",
                    call.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }))
                onStep(AgentStep(StepType.TOOL_RESULT,
                    if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                    result.result.take(200)))
                resultParts.appendLine("<tool_result name=\"${call.toolName}\" success=\"${result.success}\">")
                resultParts.appendLine(result.result)
                resultParts.appendLine("</tool_result>")
            }
            fixMessages.add(mapOf("role" to "user", "content" to resultParts.toString()))
        }

        return buildWorkspaceApp(appId, "")
    }

    /**
     * Build a MiniApp from workspace files if the AI used file tools.
     * If appId matches an existing saved app, preserve its metadata.
     */
    private fun buildWorkspaceApp(appId: String, response: String): MiniApp? {
        val wm = toolExecutor.workspaceManager
        if (!wm.hasFiles(appId)) return null
        val files = wm.getAllFiles(appId)
        val entryFile = when {
            "index.html" in files -> "index.html"
            else -> files.firstOrNull { it.endsWith(".html", ignoreCase = true) }
        } ?: return null
        val entryContent = wm.readEntryFile(appId, entryFile) ?: return null

        // Check if this is a modification of an existing app
        val existingApp = toolExecutor.miniAppStorage.loadById(appId)

        // Extract app name from index.html <title> or existing app
        val titleRegex = Regex("<title>(.+?)</title>", RegexOption.IGNORE_CASE)
        val titleFromHtml = titleRegex.find(entryContent)?.groupValues?.get(1)?.trim()
        val name = titleFromHtml ?: existingApp?.name ?: "Multi-File App"

        val description = if (existingApp != null) {
            "${existingApp.description} (已修改, ${files.size} 个文件)"
        } else {
            "多文件应用 (${files.size} 个文件)"
        }

        // Generate/update manifest
        val version = if (existingApp != null) {
            val oldVer = try {
                val text = java.io.File(wm.getWorkspacePath(appId), "APP_INFO.json").readText()
                org.json.JSONObject(text).optInt("version", 0)
            } catch (_: Exception) { 0 }
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
            entryFile = entryFile
        )
    }

    private fun buildWorkspaceAppDiagnosis(appId: String): String {
        val wm = toolExecutor.workspaceManager
        if (!wm.hasFiles(appId)) {
            return "未检测到工作区文件。app_id=$appId"
        }

        val files = wm.getAllFiles(appId)
        val htmlFiles = files.filter { it.endsWith(".html", ignoreCase = true) }
        val entryFile = when {
            "index.html" in files -> "index.html"
            else -> htmlFiles.firstOrNull()
        }
        val entryRead = entryFile?.let { wm.readEntryFile(appId, it) }
        val validation = entryFile?.let { wm.validateHtml(appId, it) }
        val runtimeErrors = RuntimeErrorCollector.getErrors(appId)

        return buildString {
            appendLine("app_id=$appId")
            appendLine("文件数=${files.size}")
            appendLine("文件列表=${if (files.isEmpty()) "(空)" else files.take(20).joinToString(", ")}")
            if (files.size > 20) appendLine("文件列表已截断，剩余 ${files.size - 20} 个")
            appendLine("HTML文件=${if (htmlFiles.isEmpty()) "(无)" else htmlFiles.joinToString(", ")}")
            appendLine("识别入口=${entryFile ?: "(无)"}")
            appendLine("入口读取=${when {
                entryFile == null -> "失败: 未找到 HTML 入口"
                entryRead == null -> "失败: readEntryFile 返回 null"
                entryRead.isBlank() -> "失败: 入口文件为空"
                else -> "成功: ${entryRead.length} chars"
            }}")
            if (validation != null) {
                appendLine("HTML校验=${validation.take(300)}")
            }
            if (runtimeErrors.isNotEmpty()) {
                appendLine("运行时错误=${runtimeErrors.take(5).joinToString(" | ")}")
            }
        }.trimEnd()
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
                compressed.add(mapOf("role" to role, "content" to stripped.ifBlank { "(工具调用已执行)" }))
            } else {
                compressed.add(msg)
            }
        }
        return compressed
    }

    private fun summarizeToolResults(content: String): String {
        val regex = Regex("<tool_result name=\"(.*?)\"[^>]*success=\"(.*?)\"[^>]*>([\\s\\S]*?)</tool_result>")
        val summaries = regex.findAll(content).map { match ->
            val name = match.groupValues[1]
            val success = match.groupValues[2]
            val result = match.groupValues[3].trim()
            // 文件写入类工具只保留成功/失败状态，不保留完整内容
            val short = when {
                name in FILE_TOOLS && success == "true" -> "成功"
                name == "list_workspace_files" || name == "list_saved_apps" -> {
                    if (result.length > 200) result.take(200) + "..." else result
                }
                result.length > 300 -> result.take(300) + "..."
                else -> result
            }
            "[$name=$success]: $short"
        }.toList()

        // 如果新正则没匹配到，用旧格式兼容
        if (summaries.isEmpty()) {
            val fallback = Regex("<tool_result name=\"(.*?)\"[^>]*>([\\s\\S]*?)</tool_result>")
            val fallbackSummaries = fallback.findAll(content).map { match ->
                val name = match.groupValues[1]
                val result = match.groupValues[2].trim()
                val short = if (result.length > 300) result.take(300) + "..." else result
                "[$name]: $short"
            }.toList()
            if (fallbackSummaries.isNotEmpty()) {
                return "工具结果：\n" + fallbackSummaries.joinToString("\n")
            }
        }

        return if (summaries.isNotEmpty()) {
            "工具结果：\n" + summaries.joinToString("\n")
        } else {
            content.take(500)
        }
    }

    /** 构建当前工作区快照，注入到系统提示让 AI 感知工作区状态 */
    private fun buildWorkspaceSnapshot(): String {
        val appId = toolExecutor.currentAppId
        val wm = toolExecutor.workspaceManager
        val hasFiles = wm.hasFiles(appId)
        val savedApps = toolExecutor.miniAppStorage.loadAll()
        val existingApp = savedApps.find { it.id == appId }

        // 检查 latestAppHint 指向的上一轮应用（用户可能要修改它）
        val hintId = toolExecutor.latestAppHint
        val hintApp = if (hintId != null && hintId != appId) savedApps.find { it.id == hintId } else null
        val hintHasFiles = hintId != null && hintId != appId && wm.hasFiles(hintId)

        return buildString {
            appendLine("### 当前工作区状态")
            if (hasFiles) {
                val files = wm.listFiles(appId, "")
                if (existingApp != null) {
                    appendLine("应用: \"${existingApp.name}\" (app_id=${appId}, 已保存)")
                } else {
                    appendLine("应用: 未保存的新应用 (当前会话中创建)")
                }
                appendLine("文件列表:")
                appendLine(files)

                // ── 预注入代码上下文（减少修改场景的探索性工具调用）──
                // 1. ARCHITECTURE.md — 文件职责、关键行号、数据流
                val archFile = java.io.File(wm.getWorkspacePath(appId), "ARCHITECTURE.md")
                if (archFile.exists()) {
                    val archContent = archFile.readText().take(2000)
                    appendLine()
                    appendLine("### 架构概览 (ARCHITECTURE.md)")
                    appendLine(archContent)
                }

                // 2. 每个源文件的代码摘要（前30行 + 行数），总量上限 4000 字符
                val allFiles = wm.getAllFiles(appId)
                val previewBuilder = StringBuilder()
                for (path in allFiles) {
                    if (previewBuilder.length > 4000) break
                    try {
                        val content = wm.readEntryFile(appId, path) ?: continue
                        val lines = content.lines()
                        val preview = lines.take(30).joinToString("\n")
                        val suffix = if (lines.size > 30) "\n... (共 ${lines.size} 行)" else ""
                        previewBuilder.appendLine()
                        previewBuilder.appendLine("#### $path (${lines.size} 行)")
                        previewBuilder.appendLine("```")
                        previewBuilder.appendLine(preview)
                        if (suffix.isNotBlank()) previewBuilder.appendLine(suffix)
                        previewBuilder.appendLine("```")
                    } catch (_: Exception) { }
                }
                if (previewBuilder.isNotBlank()) {
                    appendLine()
                    appendLine("### 代码预览（每文件前30行，修改时仍需 read_workspace_file 获取精确行号）")
                    append(previewBuilder)
                }
            } else if (hintHasFiles) {
                // 当前工作区为空但有上一轮的应用提示
                val hintFiles = wm.listFiles(hintId!!, "")
                appendLine("当前工作区为空（新轮次）")
                appendLine("上一轮操作的应用: \"${hintApp?.name ?: "未知"}\" (app_id=${hintId})")
                appendLine("该应用文件列表:")
                appendLine(hintFiles)
                appendLine("如需修改该应用，请使用 open_app_workspace(app_id=\"$hintId\")")
            } else {
                appendLine("当前工作区为空（新会话）")
            }
            if (savedApps.isNotEmpty()) {
                appendLine("已保存的应用: ${savedApps.joinToString(", ") { "\"${it.name}\"(${it.id})" }}")
            }
        }.trimEnd()
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

    /**
     * Call AI with streaming for real-time UI feedback.
     * When thinking is enabled, thinking tokens are streamed.
     * Content tokens are always streamed so the user sees progress during long waits.
     */
    private suspend fun callAi(
        messages: List<Map<String, String>>,
        maxTokens: Int,
        enableThinking: Boolean,
        label: String,
        onStep: suspend (AgentStep) -> Unit,
        onStepSync: ((AgentStep) -> Unit)?
    ): ChatResponse {
        val thinkingLabel = "💡 $label 思考中..."
        val maxStreamRetries = 6
        var lastException: Exception? = null
        for (attempt in 0..maxStreamRetries) {
            try {
                return aiService.chatStream(
                    messages = messages,
                    maxTokens = maxTokens,
                    enableThinking = enableThinking,
                    onThinkingDelta = { accumulated ->
                        onStepSync?.invoke(AgentStep(StepType.THINKING, thinkingLabel, accumulated))
                    },
                    onContentDelta = { accumulated ->
                        onStepSync?.invoke(AgentStep(StepType.THINKING, "$label...", accumulated.takeLast(200)))
                    }
                )
            } catch (e: java.io.IOException) {
                lastException = e
                if (attempt < maxStreamRetries) {
                    onStepSync?.invoke(AgentStep(StepType.THINKING, "$label 网络中断，等待网络恢复(${attempt + 1}/$maxStreamRetries)...", ""))
                    // Wait for network to come back (up to 60s), then add a small extra delay
                    val recovered = com.example.link_pi.network.AiService.waitForNetwork(60_000)
                    if (recovered) {
                        onStepSync?.invoke(AgentStep(StepType.THINKING, "$label 网络已恢复，重新连接...", ""))
                        kotlinx.coroutines.delay(1000)
                    } else {
                        // Even if not detected, still retry - ping may be blocked
                        kotlinx.coroutines.delay(5000)
                    }
                    continue
                }
                throw e
            }
        }
        throw lastException ?: java.io.IOException("Unexpected stream retry exhaustion")
    }

}

data class OrchestratorResult(
    val finalResponse: String,
    val miniApp: com.example.link_pi.data.model.MiniApp?,
    val steps: Int,
    val thinkingContent: String = "",
    val workbenchRedirect: WorkbenchRedirect? = null
)
