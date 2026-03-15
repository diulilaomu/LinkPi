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
import com.example.link_pi.skill.PromptDomain
import com.example.link_pi.skill.UserIntent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        private const val MAX_REFINEMENT_ROUNDS = 3
        private const val PLANNING_TOKENS_IDEAL = 16384
        private const val GENERATION_TOKENS_IDEAL = 65536
        /** Rough chars-per-token ratio for input token estimation. */
        private const val CHARS_PER_TOKEN = 3.5
        private val FILE_TOOLS = setOf("write_file", "edit_file", "delete_path", "rename_file",
            // Legacy names the AI might hallucinate
            "create_file", "append_file", "replace_in_file", "replace_lines", "insert_lines",
            "create_directory", "delete_directory", "delete_workspace_file", "copy_file")

        // ── OpenCode-inspired constants ──
        /** Consecutive identical tool calls before triggering doom-loop abort. */
        private const val DOOM_LOOP_THRESHOLD = 3
        /** Max lines for a single tool output before truncation. */
        private const val TOOL_OUTPUT_MAX_LINES = 2000
        /** Max bytes for a single tool output before truncation. */
        private const val TOOL_OUTPUT_MAX_BYTES = 50_000
        /** Token threshold below which old tool outputs are protected from pruning. */
        private const val PRUNE_PROTECT_TOKENS = 40_000
        /** Minimum tokens worth of old outputs to prune (don't prune small amounts). */
        private const val PRUNE_MINIMUM_TOKENS = 20_000
        /** Tool outputs that should never be pruned (carry critical context). */
        private val PRUNE_PROTECTED_TOOLS = setOf("validate", "validate_html", "validate_js", "get_runtime_errors")

        /** Patterns in API error messages that indicate context/token overflow (not network errors). */
        private val CONTEXT_OVERFLOW_PATTERNS = listOf(
            "context_length_exceeded",
            "maximum context length",
            "token limit",
            "too many tokens",
            "max_tokens",
            "context window",
            "input too long",
            "request too large",
            "content_too_large",
            "reduce.*prompt",
            "reduce.*input"
        )
    }

    /** Exception indicating the API rejected the request due to context overflow. */
    class ContextOverflowException(message: String) : Exception(message)

    /** Recent tool calls for doom-loop detection: (toolName, argsHash) */
    private val recentToolCalls = mutableListOf<Pair<String, Int>>()

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
        recentToolCalls.clear() // Reset doom-loop state for each new run

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
        val planningLevel = resolveSnapshotLevel(intent, PromptDomain.Phase.PLANNING)
        val workspaceSnapshot = buildWorkspaceSnapshot(intent, planningLevel)
        val planningSystemMsgs = PromptAssembler.buildMessages(
            skill, intent, PromptDomain.Phase.PLANNING, toolExecutor.toolDefs,
            PromptAssembler.DomainContext(
                memorySnapshot = planningSnapshot,
                workspaceSnapshot = workspaceSnapshot,
                injectedPrompts = injectedPrompts
            )
        )

        injectSystemMessages(messages, planningSystemMsgs)

        // Track AI's capability selection from planning
        var aiSelection: PromptAssembler.CapabilitySelection? = null
        // Track generation mode from planning (FAST skips tool-call loops)
        var generationMode = PromptAssembler.GenerationMode.FULL
        // Track AI's architecture blueprint from planning
        var architectureBlueprint: String? = null
        // Track AI's modification plan from planning (MODIFY_APP)
        var modificationPlan: String? = null

        // Re-inject images into last user message so the first planning call can see them
        if (savedImages != null && lastUserIdx >= 0) {
            val msg = messages[lastUserIdx].toMutableMap()
            msg["_images"] = savedImages
            messages[lastUserIdx] = msg
        }

        // ── Phase 1: Planning + Tool Use ──
        // Resolve tools for planning phase (function calling)
        var currentTools = PromptAssembler.resolveTools(skill, intent, PromptDomain.Phase.PLANNING, toolExecutor.toolDefs)
        var currentToolsJson = buildToolsJson(currentTools)

        var toolIterations = 0
        while (toolIterations < MAX_TOOL_ITERATIONS) {
            toolIterations++
            onStep(AgentStep(StepType.THINKING, "正在规划...", "第 $toolIterations 轮"))

            // Budget-aware: compress if accumulated tool results are blowing up context
            val planningBudget = if (usedFileTools) GENERATION_TOKENS else PLANNING_TOKENS
            val inMsgs = ensureTokenBudget(messages, planningBudget)
            val chatResp = callAi(inMsgs, planningBudget, false, "Planning", onStep, onStepSync, currentToolsJson)

            // Strip images after first planning call — they've been sent once, no need to resend
            if (toolIterations == 1 && savedImages != null && lastUserIdx >= 0 && messages[lastUserIdx].containsKey("_images")) {
                messages[lastUserIdx] = messages[lastUserIdx].filterKeys { it != "_images" }.toMutableMap()
            }

            val response = chatResp.content
            var toolCalls = extractToolCalls(chatResp)

            if (chatResp.thinkingContent.isNotBlank() && toolCalls.isNotEmpty()) {
                latestThinking = chatResp.thinkingContent
            }

            // Detect truncated response (max_tokens)
            if (toolCalls.isEmpty() && chatResp.finishReason == "length") {
                // Retry with full token budget
                onStep(AgentStep(StepType.THINKING, "工具调用被截断，重试...", "增加token"))
                val retryResp = callAi(inMsgs, GENERATION_TOKENS, false, "Planning重试", onStep, onStepSync, currentToolsJson)
                if (retryResp.thinkingContent.isNotBlank()) {
                    latestThinking = retryResp.thinkingContent
                }
                val retryToolCalls = extractToolCalls(retryResp)
                if (retryToolCalls.isNotEmpty()) {
                    // Process the retried tool calls
                    messages.add(buildAssistantMessage(retryResp.content, retryToolCalls))
                    for (call in retryToolCalls) {
                        if (call.toolName in FILE_TOOLS) usedFileTools = true
                    }
                    val (results, _, _) = executeToolsBatched(retryToolCalls, onStep)
                    appendToolResults(messages, retryToolCalls, results)
                    continue // back to planning loop
                }
                // Retry also failed — fall through to normal empty handling
            }

            if (toolCalls.isEmpty()) {
                // AI decided no more tools needed — check if workspace was already built
                if (usedFileTools) {
                    // Run self-check before early return (don't skip validation)
                    val earlySysMsgs = PromptAssembler.buildMessages(
                        skill, intent, PromptDomain.Phase.GENERATION, toolExecutor.toolDefs,
                        PromptAssembler.DomainContext(memorySnapshot = planningSnapshot, aiSelection = aiSelection, workspaceSnapshot = buildWorkspaceSnapshot(intent, SnapshotLevel.L1), injectedPrompts = injectedPrompts)
                    )
                    val wsApp = runPostGenerationCheck(messages, toolExecutor.currentAppId, earlySysMsgs, onStep, onStepSync)
                    if (wsApp != null) {
                        onStep(AgentStep(StepType.FINAL_RESPONSE, "工作空间应用生成完成", wsApp.name))
                        return cleanResult(response, wsApp, toolIterations, latestThinking)
                    } else {
                        onStep(AgentStep(StepType.THINKING, "工作区诊断", buildWorkspaceAppDiagnosis(toolExecutor.currentAppId)))
                    }
                }
                // For non-app intents (CONVERSATION etc.), break out of planning loop
                if (!intent.needsApp()) {
                    // Detect if AI output an app creation plan but forgot to call launch_workbench
                    val looksLikeAppPlan = response.length > 100 && run {
                        val lc = response.lowercase()
                        val planSignals = listOf("文件结构", "文件列表", "index.html", "html文件", "css文件", "js文件",
                            "游戏功能", "应用功能", "游戏逻辑", "响应式", "创建一个", "生成一个",
                            "file structure", "game logic", "write_file")
                        planSignals.count { lc.contains(it) } >= 2
                    }
                    if (looksLikeAppPlan && toolIterations <= 2) {
                        onStep(AgentStep(StepType.THINKING, "AI 输出了应用规划但未调用 launch_workbench，提示调用..."))
                        messages.add(mapOf("role" to "assistant", "content" to response))
                        messages.add(mapOf("role" to "user", "content" to
                            "[系统] 你输出了应用创建规划，但没有调用 launch_workbench 工具。" +
                            "请立即调用 launch_workbench 工具弹出工作台引导卡片，让用户在工作台中完成应用生成。" +
                            "不要在聊天中直接生成代码。"))
                        continue
                    }
                    if (chatResp.thinkingContent.isNotBlank()) {
                        latestThinking = chatResp.thinkingContent
                    }
                    var finalResp = response
                    if (finalResp.isBlank() && chatResp.thinkingContent.length > 200) {
                        finalResp = chatResp.thinkingContent
                    }
                    messages.add(mapOf("role" to "assistant", "content" to finalResp))
                    break
                }
                // Move to generation phase
                if (response.isNotBlank()) {
                    onStep(AgentStep(StepType.THINKING, response.take(200)))
                    messages.add(mapOf("role" to "assistant", "content" to response))
                }
                if (intent.needsApp()) {
                    aiSelection = PromptAssembler.parseCapabilitySelection(response)
                    generationMode = PromptAssembler.parseGenerationMode(response)
                    architectureBlueprint = PromptAssembler.parseArchitectureBlueprint(response)
                    modificationPlan = parseModificationPlan(response)
                    // Cache planning artifacts for pull-model retrieval via read_plan tool
                    if (!architectureBlueprint.isNullOrBlank()) {
                        toolExecutor.planStore["architecture"] = architectureBlueprint!!
                    }
                    if (!modificationPlan.isNullOrBlank()) {
                        toolExecutor.planStore["modification_plan"] = modificationPlan!!
                    }
                    val lastAssistantIdx = messages.indexOfLast { it["role"] == "assistant" }
                    if (lastAssistantIdx >= 0) {
                        val cleaned = messages[lastAssistantIdx]["content"]!!
                            .replace(Regex("<capability_selection>[\\s\\S]*?</capability_selection>"), "")
                            .replace(Regex("<generation_mode>[\\s\\S]*?</generation_mode>"), "")
                            .replace(Regex("<architecture>[\\s\\S]*?</architecture>"), "")
                            .replace(Regex("<modification_plan>[\\s\\S]*?</modification_plan>"), "")
                            .trim()
                        messages[lastAssistantIdx] = mapOf("role" to "assistant", "content" to cleaned)
                    }
                }
                break
            }

            // Process tool calls — function calling format
            if (response.isNotBlank()) {
                onStep(AgentStep(StepType.THINKING, response.take(200)))
            }
            messages.add(buildAssistantMessage(response, toolCalls))

            toolExecutor.toolProgressCallback = onStepSync
            for (call in toolCalls) {
                if (call.toolName in FILE_TOOLS) usedFileTools = true
            }
            val (toolResults, hasFailure, allEditsFailed) = executeToolsBatched(toolCalls, onStep)
            toolExecutor.toolProgressCallback = null

            appendToolResults(messages, toolCalls, toolResults)

            // Track consecutive rounds where file edits all failed
            val hadFileEdits = toolCalls.any { it.toolName in FILE_TOOLS }
            if (hadFileEdits && allEditsFailed) {
                consecutiveFailedRounds++
            } else if (hadFileEdits) {
                consecutiveFailedRounds = 0
            }

            // When a tool fails, add recovery guidance as a user message
            if (hasFailure) {
                val guidance = if (consecutiveFailedRounds >= 3) {
                    onStep(AgentStep(StepType.THINKING, "连续修改失败，强制切换策略", "第 $consecutiveFailedRounds 轮"))
                    "[系统] ⚠ 连续 $consecutiveFailedRounds 轮文件修改失败。请立即更换策略：对该文件使用 write_file 整体重写，不要再尝试 replace_in_file。"
                } else {
                    "[系统] 工具调用失败。请仔细阅读错误信息，修正参数后重试。注意：open_app_workspace 的 app_id 必须是 list_saved_apps 返回的实际 UUID 值。replace_in_file 连续失败时改用 replace_lines 或 write_file。"
                }
                messages.add(mapOf("role" to "user", "content" to guidance))
            }

            // ── Check for workbench redirect from launch_workbench tool ──
            toolExecutor.pendingWorkbenchRedirect?.let { redirect ->
                if (intent.needsApp()) {
                    toolExecutor.pendingWorkbenchRedirect = null
                    messages.add(mapOf("role" to "user", "content" to "[系统] 当前已在工作台中，无需调用 launch_workbench。请直接进行规划或生成。"))
                } else {
                    toolExecutor.pendingWorkbenchRedirect = null
                    onStep(AgentStep(StepType.FINAL_RESPONSE, "已弹出工作台引导", redirect.title))
                    return OrchestratorResult(
                        "", null, toolIterations, latestThinking,
                        workbenchRedirect = redirect
                    )
                }
            }
        }

        // If all work was done via file tools, run self-check then return
        if (usedFileTools) {
            val earlyGenSysMsgs = PromptAssembler.buildMessages(
                skill, intent, PromptDomain.Phase.GENERATION, toolExecutor.toolDefs,
                PromptAssembler.DomainContext(memorySnapshot = planningSnapshot, aiSelection = aiSelection, workspaceSnapshot = buildWorkspaceSnapshot(intent, SnapshotLevel.L1), injectedPrompts = injectedPrompts)
            )
            val wsApp = runPostGenerationCheck(messages, toolExecutor.currentAppId, earlyGenSysMsgs, onStep, onStepSync)
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
        onStep(AgentStep(StepType.THINKING, "正在生成应用...", "全量生成"))

        // Rebuild system prompt for GENERATION phase with AI's capability selections
        val genPhase = PromptDomain.Phase.GENERATION
        val genLevel = resolveSnapshotLevel(intent, genPhase)
        val genWorkspaceSnapshot = buildWorkspaceSnapshot(intent, genLevel)

        val genSystemMsgs = PromptAssembler.buildMessages(
            skill, intent, genPhase, toolExecutor.toolDefs,
            PromptAssembler.DomainContext(
                memorySnapshot = planningSnapshot,
                aiSelection = aiSelection,
                workspaceSnapshot = genWorkspaceSnapshot,
                injectedPrompts = injectedPrompts
            )
        )

        // Resolve tools for generation phase
        val genTools = PromptAssembler.resolveTools(skill, intent, genPhase, toolExecutor.toolDefs, aiSelection)
        val genToolsJson = buildToolsJson(genTools)

        // Compress context
        val compressedMessages = compressContext(messages)

        // Replace system messages with generation-phase version
        injectSystemMessages(compressedMessages, genSystemMsgs)

        // ── Multi-round tool call generation ──
        val genInstruction = when {
            intent == UserIntent.MODIFY_APP -> "先调用 read_plan 获取修改计划，然后开始执行精确修改。"
            usedFileTools -> "先调用 read_plan 获取架构规划，继续创建应用。确保 index.html 为入口文件。"
            else -> "先调用 read_plan 获取架构规划，然后生成完整应用。使用文件工具分别创建各文件。"
        }
        val genInstructionMsg = mutableMapOf("role" to "user", "content" to genInstruction)
        compressedMessages.add(genInstructionMsg)

        var genResp: ChatResponse
        try {
            genResp = callAi(compressedMessages, GENERATION_TOKENS, enableThinking, "Generation", onStep, onStepSync, genToolsJson)
        } catch (coe: ContextOverflowException) {
            onStep(AgentStep(StepType.THINKING, "上下文过长，压缩后重试...", ""))
            val shrunk = compressContext(compressedMessages)
            compressedMessages.clear()
            compressedMessages.addAll(shrunk)
            pruneOldToolOutputs(compressedMessages)
            genResp = callAi(compressedMessages, GENERATION_TOKENS, enableThinking, "Generation", onStep, onStepSync, genToolsJson)
        }
        var genResponse = genResp.content

        // If content is empty but we sent enable_thinking, the model may not support it — retry without
        if (genResponse.isBlank() && enableThinking && genResp.toolCalls.isEmpty()) {
            onStep(AgentStep(StepType.THINKING, "响应为空，关闭深度思考重试..."))
            genResp = callAi(compressedMessages, GENERATION_TOKENS, false, "Generation重试", onStep, onStepSync, genToolsJson)
            genResponse = genResp.content
        }

        // Fallback: if content is still empty but thinkingContent has substance, use it
        if (genResponse.isBlank() && genResp.thinkingContent.length > 200 && genResp.toolCalls.isEmpty()) {
            genResponse = genResp.thinkingContent
        }

        if (genResp.thinkingContent.isNotBlank()) {
            latestThinking = genResp.thinkingContent
            onStep(AgentStep(StepType.THINKING, "💡 Generation 思考完成", genResp.thinkingContent.takeLast(2000)))
        }

        // Check if generation phase used tools (function calling or XML fallback)
        var genToolCalls = extractToolCalls(genResp)
        // Also enter tool loop when AI output plan text without tools
        val needsToolNudge = genToolCalls.isEmpty() && intent.needsApp() && !usedFileTools &&
            MiniAppParser.extractMiniApp(genResponse) == null && FileBlockParser.parseFiles(genResponse).isEmpty()
        if (genToolCalls.isNotEmpty() || (genToolCalls.isEmpty() && genResp.finishReason == "length") || needsToolNudge) {
            val genMessages = compressedMessages.toMutableList()

            if (genToolCalls.isNotEmpty()) {
                genMessages.add(buildAssistantMessage(genResponse, genToolCalls))
                for (call in genToolCalls) {
                    if (call.toolName in FILE_TOOLS) usedFileTools = true
                }
                val (genResults, _, _) = executeToolsBatched(genToolCalls, onStep)
                appendToolResults(genMessages, genToolCalls, genResults)
                if (genToolCalls.any { it.toolName in FILE_TOOLS }) {
                    buildWorkspaceChangeSummary()?.let { summary ->
                        genMessages.add(mapOf("role" to "user", "content" to summary))
                    }
                }
            } else if (needsToolNudge) {
                onStep(AgentStep(StepType.THINKING, "AI 输出规划文字未调用工具，提示开始创建文件..."))
                genMessages.add(mapOf("role" to "assistant", "content" to genResponse))
                genMessages.add(mapOf("role" to "user", "content" to
                    "你输出了文字描述但没有调用任何文件工具。请立即使用 write_file 工具逐个创建文件。不需要再解释计划，直接开始创建。第一个文件请创建入口文件。"))
            } else {
                onStep(AgentStep(StepType.THINKING, "生成被截断，继续...", "续写"))
                genMessages.add(mapOf("role" to "assistant", "content" to genResponse))
                genMessages.add(mapOf("role" to "user", "content" to
                    "你的回复被截断了（可能是 token 上限）。请从中断处继续。不要重复已输出的内容。"))
            }

            var extraIterations = 0
            var genConsecFails = 0
            var overflowRetries = 0
            var consecNoProgress = 0
            while (extraIterations < MAX_TOOL_ITERATIONS) {
                extraIterations++
                val contResp: ChatResponse
                try {
                    contResp = callAi(genMessages, GENERATION_TOKENS, enableThinking, "Generation继续", onStep, onStepSync, genToolsJson)
                    overflowRetries = 0
                } catch (coe: ContextOverflowException) {
                    overflowRetries++
                    if (overflowRetries > 2) throw coe
                    onStep(AgentStep(StepType.THINKING, "上下文过长，压缩后重试...", ""))
                    val shrunk = compressContext(genMessages)
                    genMessages.clear()
                    genMessages.addAll(shrunk)
                    pruneOldToolOutputs(genMessages)
                    continue
                }
                val contResponse = contResp.content
                if (contResp.thinkingContent.isNotBlank()) {
                    latestThinking = contResp.thinkingContent
                }
                var contToolCalls = extractToolCalls(contResp)
                if (contToolCalls.isEmpty() && contResp.finishReason == "length") {
                    consecNoProgress++
                    if (consecNoProgress >= 4) {
                        onStep(AgentStep(StepType.THINKING, "连续 $consecNoProgress 轮无有效工具调用，终止生成循环"))
                        break
                    }
                    genMessages.add(mapOf("role" to "assistant", "content" to contResponse))
                    genMessages.add(mapOf("role" to "user", "content" to
                        "你的回复被截断了（可能是 token 上限）。请从中断处继续。不要重复已输出的内容。"))
                    continue
                }
                if (contToolCalls.isEmpty()) {
                    if (usedFileTools) {
                        val wsApp = runPostGenerationCheck(genMessages, toolExecutor.currentAppId, genSystemMsgs, onStep, onStepSync)
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
                genMessages.add(buildAssistantMessage(contResponse, contToolCalls))
                for (call in contToolCalls) {
                    if (call.toolName in FILE_TOOLS) usedFileTools = true
                }
                consecNoProgress = 0
                val (contResults, _, contAllFailed) = executeToolsBatched(contToolCalls, onStep)
                appendToolResults(genMessages, contToolCalls, contResults)
                val contHadEdits = contToolCalls.any { it.toolName in FILE_TOOLS }
                if (contHadEdits && contAllFailed) {
                    genConsecFails++
                    if (genConsecFails >= 3) {
                        genMessages.add(mapOf("role" to "user", "content" to
                            "[系统] ⚠ 连续 $genConsecFails 轮文件修改失败。请立即更换策略：对该文件使用 write_file 整体重写，不要再尝试 edit_file。"))
                    }
                } else if (contHadEdits) {
                    genConsecFails = 0
                }
                if (contHadEdits) {
                    buildWorkspaceChangeSummary()?.let { summary ->
                        genMessages.add(mapOf("role" to "user", "content" to summary))
                    }
                }
            }

            if (usedFileTools) {
                val wsApp = runPostGenerationCheck(genMessages, toolExecutor.currentAppId, genSystemMsgs, onStep, onStepSync)
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

    private fun cleanResult(response: String, miniApp: MiniApp?, steps: Int, thinking: String = ""): OrchestratorResult {
        return OrchestratorResult(response, miniApp, steps, thinking)
    }

    /**
     * Post-generation REFINEMENT phase: diagnose → fix → re-diagnose loop.
     *
     * Up to [MAX_REFINEMENT_ROUNDS] full cycles of:
     *   1. Run all checks (HTML validation, runtime errors, reference integrity)
     *   2. If clean → return
     *   3. Otherwise inject diagnosis, let AI fix (up to 5 tool-call rounds per cycle)
     *   4. Re-run checks on the updated workspace
     *
     * This replaces the old "fire-and-forget" fix that never verified results.
     */
    private suspend fun runPostGenerationCheck(
        messages: MutableList<Map<String, String>>,
        appId: String,
        systemMessages: List<Map<String, String>>,
        onStep: suspend (AgentStep) -> Unit,
        onStepSync: ((AgentStep) -> Unit)?
    ): MiniApp? {
        val wm = toolExecutor.workspaceManager
        if (!wm.hasFiles(appId)) return null

        // Prepare fix context once (compressed from generation history)
        val fixMessages = compressContext(messages)
        injectSystemMessages(fixMessages, systemMessages)

        for (refinementRound in 1..MAX_REFINEMENT_ROUNDS) {
            onStep(AgentStep(StepType.THINKING, "正在自检...", "第 $refinementRound 轮 · 验证HTML + 引用完整性 + 运行时错误"))

            val diagnosis = diagnoseWorkspace(appId)

            if (!diagnosis.hasIssues) {
                onStep(AgentStep(StepType.THINKING, "✓ 自检通过", if (refinementRound == 1) "无问题" else "第 $refinementRound 轮修复后通过"))
                return buildWorkspaceApp(appId, "")
            }

            // Last round — don't attempt another fix; record failure pattern for future learning
            if (refinementRound == MAX_REFINEMENT_ROUNDS) {
                onStep(AgentStep(StepType.THINKING, "⚠ 自检仍有问题，已达修复上限", diagnosis.summary.take(200)))
                recordFailurePattern(diagnosis)
                break
            }

            onStep(AgentStep(StepType.THINKING, "发现问题，自动修复中 (第 $refinementRound 轮)...", diagnosis.summary.take(200)))

            // Inject diagnosis and let AI fix
            fixMessages.add(mapOf("role" to "user", "content" to diagnosis.fixInstruction))

            var fixIterations = 0
            var fixOverflowRetries = 0
            while (fixIterations < 5) {
                fixIterations++
                val budgetedFixMsgs = ensureTokenBudget(fixMessages, GENERATION_TOKENS)
                val fixResp: ChatResponse
                try {
                    fixResp = callAi(budgetedFixMsgs, GENERATION_TOKENS, false, "修复(R$refinementRound)", onStep, onStepSync)
                    fixOverflowRetries = 0
                } catch (coe: ContextOverflowException) {
                    fixOverflowRetries++
                    if (fixOverflowRetries > 2) break // give up fixing this round
                    onStep(AgentStep(StepType.THINKING, "修复阶段上下文过长，压缩...", ""))
                    val shrunk = compressContext(fixMessages)
                    fixMessages.clear()
                    fixMessages.addAll(shrunk)
                    pruneOldToolOutputs(fixMessages)
                    continue  // retry with compressed messages
                }
                val fixResponse = fixResp.content
                val fixToolCalls = extractToolCalls(fixResp)

                if (fixToolCalls.isEmpty()) break // AI done fixing

                fixMessages.add(buildAssistantMessage(fixResponse, fixToolCalls))
                for (call in fixToolCalls) {
                    val result = toolExecutor.execute(call)
                    onStep(AgentStep(StepType.TOOL_CALL, "修复: ${call.toolName}",
                        call.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }))
                    onStep(AgentStep(StepType.TOOL_RESULT,
                        if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                        result.result.take(200)))
                    val callId = call.id ?: "call_${call.toolName}_${System.nanoTime()}"
                    fixMessages.add(buildToolResultMessage(callId, call.toolName, result.result))
                }
            }
            // Loop back to re-diagnose
        }

        return buildWorkspaceApp(appId, "")
    }

    /** Diagnosis result from workspace checks. */
    private data class WorkspaceDiagnosis(
        val hasIssues: Boolean,
        val summary: String,
        val fixInstruction: String
    )

    /** Run all workspace checks and return structured diagnosis. */
    private suspend fun diagnoseWorkspace(appId: String): WorkspaceDiagnosis {
        val wm = toolExecutor.workspaceManager
        val allFiles = wm.getAllFiles(appId)

        // 1. HTML validation
        val entryFile = if ("index.html" in allFiles) "index.html"
            else allFiles.firstOrNull { it.endsWith(".html") }
        val validationResult = if (entryFile != null) wm.validateHtml(appId, entryFile) else null
        val hasValidationIssues = validationResult != null && !validationResult.startsWith("✓")

        // 2. Runtime errors — run headless WebView to actually execute JS
        try {
            HeadlessWebViewRunner.collectRuntimeErrors(toolExecutor.appContext, appId)
        } catch (_: Exception) { /* best-effort: headless run may fail on some devices */ }
        val runtimeErrors = RuntimeErrorCollector.getErrors(appId)
        val hasRuntimeErrors = runtimeErrors.isNotEmpty()
        if (hasRuntimeErrors) RuntimeErrorCollector.clear(appId)

        // 3. Reference integrity
        val archProfile = ArchitectureAnalyzer.analyze(wm, appId)
        val missingRefs = if (archProfile != null) ArchitectureAnalyzer.validateReferences(archProfile) else emptyList()
        val hasMissingRefs = missingRefs.isNotEmpty()

        // 4. JS/CSS syntax validation for external files
        val jsIssues = mutableListOf<String>()
        for (f in allFiles) {
            val result = when {
                f.endsWith(".js", ignoreCase = true) -> wm.validateJs(appId, f)
                f.endsWith(".css", ignoreCase = true) -> wm.validateCss(appId, f)
                else -> null
            }
            if (result != null && !result.startsWith("✓")) {
                jsIssues.add(result)
            }
        }
        val hasJsIssues = jsIssues.isNotEmpty()

        // 5. CSS ↔ JS 交叉验证（CSS 类名与 JS 选择器一致性）
        val fileContents = ArchitectureAnalyzer.readFileContents(wm, appId)
        val cssJsIssues = ArchitectureAnalyzer.validateCssJsConsistency(fileContents)
        val hasCssJsIssues = cssJsIssues.isNotEmpty()

        val hasIssues = hasValidationIssues || hasRuntimeErrors || hasMissingRefs || hasJsIssues
        if (!hasIssues && !hasCssJsIssues) return WorkspaceDiagnosis(false, "", "")

        val summary = StringBuilder()
        val fix = StringBuilder(if (hasIssues) "自检发现以下问题，请修复：\n\n" else "")

        if (hasValidationIssues) {
            summary.append("HTML校验问题; ")
            fix.appendLine("**HTML 校验结果：**")
            fix.appendLine(validationResult)
            fix.appendLine()
        }
        if (hasRuntimeErrors) {
            summary.append("${runtimeErrors.size}个运行时错误; ")
            fix.appendLine("**JS 运行时错误：**")
            runtimeErrors.forEachIndexed { i, e -> fix.appendLine("${i + 1}. $e") }
            fix.appendLine()
        }
        if (hasMissingRefs) {
            summary.append("${missingRefs.size}个引用缺失; ")
            fix.appendLine("**文件引用缺失（会导致 404 错误）：**")
            missingRefs.take(10).forEachIndexed { i, ref -> fix.appendLine("${i + 1}. $ref") }
            fix.appendLine("请创建缺失的文件，或修正引用路径。")
            fix.appendLine()
        }
        if (hasJsIssues) {
            summary.append("JS/CSS语法问题; ")
            fix.appendLine("**JS/CSS 文件语法问题：**")
            jsIssues.forEach { fix.appendLine(it) }
            fix.appendLine()
        }
        if (hasCssJsIssues) {
            summary.append("[提示]CSS↔JS类名建议; ")
            fix.appendLine("**[仅供参考] CSS↔JS 类名建议（不影响运行，可酌情优化）：**")
            cssJsIssues.forEach { fix.appendLine("- ${it.file}: ${it.message}") }
            fix.appendLine()
        }
        if (hasIssues) {
            fix.appendLine("请先 read_file 读取相关文件，然后修复上述问题。修复后用 validate 再次验证。")
        }

        return WorkspaceDiagnosis(hasIssues, summary.toString().trimEnd(' ', ';'), fix.toString())
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
     * Two-phase context compression inspired by OpenCode's compaction + prune model:
     *
     * Phase 1 (Tiered Compression):
     * - System prompt: preserved fully
     * - Recent 2 turns (4 messages): detailed (code read results preserved)
     * - Older turns: tool results summarized, tool calls stripped
     *
     * Phase 2 (Backward Prune — OpenCode pattern):
     * - Scan backward from most recent messages
     * - Skip the last 2 user turns (always protected)
     * - Accumulate token estimates of tool outputs
     * - Once accumulated > PRUNE_PROTECT_TOKENS, erase outputs of older tool results
     * - Protected tools (validation results) are never pruned
     * - Only prune if total prunable exceeds PRUNE_MINIMUM_TOKENS
     */

    /**
     * Replace all leading system messages with new multi-system-message list.
     * This supports the new multi-layer prompt architecture (Gateway + Domain + Tools + Context).
     */
    private fun injectSystemMessages(
        messages: MutableList<Map<String, String>>,
        systemMessages: List<Map<String, String>>
    ) {
        // Remove all consecutive system messages from the start
        while (messages.isNotEmpty() && messages[0]["role"] == "system") {
            messages.removeAt(0)
        }
        // Insert new system messages at the beginning (preserve order)
        for (i in systemMessages.indices.reversed()) {
            messages.add(0, systemMessages[i])
        }
    }

    private fun compressContext(messages: List<Map<String, String>>): MutableList<Map<String, String>> {
        // Phase 1: Tiered compression
        val compressed = mutableListOf<Map<String, String>>()
        val nonSystemMessages = messages.mapIndexedNotNull { i, m -> if (m["role"] != "system") i else null }
        val recentThreshold = if (nonSystemMessages.size > 4) nonSystemMessages[nonSystemMessages.size - 4] else 0

        for ((i, msg) in messages.withIndex()) {
            val content = msg["content"] ?: ""
            val role = msg["role"] ?: ""
            val isRecent = i >= recentThreshold

            if (role == "system") {
                compressed.add(msg)
            } else if (role == "tool") {
                // Function calling tool results — compress old ones
                if (isRecent) {
                    compressed.add(msg)
                } else {
                    val toolName = msg["name"] ?: "unknown"
                    if (toolName in PRUNE_PROTECTED_TOOLS) {
                        compressed.add(msg)
                    } else if (content.length > 500) {
                        compressed.add(msg.toMutableMap().apply { put("content", content.take(200) + "\n... (已压缩)") })
                    } else {
                        compressed.add(msg)
                    }
                }
            } else if (role == "assistant" && msg.containsKey("_tool_calls")) {
                // Assistant messages with tool calls — compress readable text
                val compressedMsg = mutableMapOf("role" to role, "content" to
                    if (isRecent) content.ifBlank { "(工具调用已执行)" }
                    else content.take(200).ifBlank { "(工具调用已执行)" })
                // Keep _tool_calls for recent messages so API can reconstruct the conversation
                if (isRecent) {
                    compressedMsg["_tool_calls"] = msg["_tool_calls"]!!
                }
                compressed.add(compressedMsg)
            } else {
                compressed.add(msg)
            }
        }

        // Phase 2: Backward prune (OpenCode pattern)
        pruneOldToolOutputs(compressed)

        return compressed
    }

    /**
     * OpenCode-inspired backward prune: scan from the end, protect recent turns,
     * then erase old tool outputs that exceed the protection threshold.
     */
    private fun pruneOldToolOutputs(messages: MutableList<Map<String, String>>) {
        var userTurnsSeen = 0
        var accumulatedTokens = 0
        var prunableTokens = 0
        val toPrune = mutableListOf<Int>() // indices to prune

        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            val role = msg["role"] ?: ""
            val content = msg["content"] ?: ""

            if (role == "system") continue
            if (role == "user") {
                userTurnsSeen++
            }
            // Protect the last 2 user turns
            if (userTurnsSeen < 2) continue

            // Prune tool result messages (function calling format)
            if (role == "tool") {
                val estimate = (content.length / CHARS_PER_TOKEN).toInt()
                accumulatedTokens += estimate
                if (accumulatedTokens > PRUNE_PROTECT_TOKENS) {
                    val toolName = msg["name"] ?: ""
                    val hasProtected = toolName in PRUNE_PROTECTED_TOOLS
                    if (!hasProtected) {
                        prunableTokens += estimate
                        toPrune.add(i)
                    }
                }
            }
        }

        // Only prune if there's enough to make a difference
        if (prunableTokens >= PRUNE_MINIMUM_TOKENS) {
            for (idx in toPrune) {
                val msg = messages[idx]
                val toolName = msg["name"] ?: "unknown"
                messages[idx] = mapOf(
                    "role" to "tool",
                    "tool_call_id" to (msg["tool_call_id"] ?: ""),
                    "name" to toolName,
                    "content" to "[已压缩的工具结果: $toolName]"
                )
            }
        }
    }

    /**
     * 构建实时工作区变更摘要，在生成阶段每轮文件工具执行后注入，
     * 让 AI 在后续轮次中感知已创建/修改的文件全貌。
     */
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

    /**
     * 工作区快照级别 — Pull 模型下只注入轻量元信息。
     * 代码预览和架构详情由 AI 通过 inspect_workspace/read_file 按需拉取。
     *
     * L0: 仅应用名+文件列表（对话 / 模块）~500字
     * L1: L0+一行架构摘要+引用问题（创建/修改）~1500字
     */
    enum class SnapshotLevel(val charCap: Int) {
        L0(500), L1(1500)
    }

    /** 根据 intent × phase 自动选择快照级别 */
    private fun resolveSnapshotLevel(intent: UserIntent?, phase: PromptDomain.Phase?): SnapshotLevel = when {
        intent?.needsApp() == true -> SnapshotLevel.L1
        else -> SnapshotLevel.L0
    }

    /** 构建当前工作区快照 — 仅文件列表+轻量元信息，代码由 AI 按需 read_file 拉取 */
    private fun buildWorkspaceSnapshot(intent: UserIntent? = null, level: SnapshotLevel? = null): String {
        val effectiveLevel = level ?: resolveSnapshotLevel(intent, null)
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

                // L1: 一行架构摘要 + 引用完整性预检
                if (effectiveLevel == SnapshotLevel.L1) {
                    val archProfile = ArchitectureAnalyzer.analyze(wm, appId)
                    if (archProfile != null) {
                        appendLine()
                        appendLine("项目类型: ${archProfile.projectType.label} | 复杂度: ${archProfile.complexity.label} | 入口: ${archProfile.entryPoint ?: "未知"}")
                        appendLine("💡 使用 inspect_workspace 工具可获取完整架构分析（依赖图、导出索引等）")

                        val missingRefs = ArchitectureAnalyzer.validateReferences(archProfile)
                        if (missingRefs.isNotEmpty()) {
                            appendLine("**⚠ 引用完整性问题：**")
                            missingRefs.take(3).forEach { appendLine("  - $it") }
                        }
                    }
                }
            } else if (hintHasFiles) {
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
        }.trimEnd().let { snapshot ->
            if (snapshot.length > effectiveLevel.charCap) {
                snapshot.take(effectiveLevel.charCap) + "\n... (快照已截断至 ${effectiveLevel.charCap} 字符)"
            } else snapshot
        }
    }

    /** 从规划阶段响应中提取 <modification_plan> 块 */
    private fun parseModificationPlan(response: String): String? {
        val regex = Regex("""<modification_plan>\s*([\s\S]*?)\s*</modification_plan>""")
        return regex.find(response)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
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
        onStepSync: ((AgentStep) -> Unit)?,
        tools: org.json.JSONArray? = null
    ): ChatResponse {
        val thinkingLabel = "💡 $label 思考中..."
        val maxStreamRetries = 6
        var lastException: Exception? = null
        for (attempt in 0 until maxStreamRetries) {
            try {
                return aiService.chatStream(
                    messages = messages,
                    maxTokens = maxTokens,
                    enableThinking = enableThinking,
                    tools = tools,
                    onThinkingDelta = { accumulated ->
                        onStepSync?.invoke(AgentStep(StepType.THINKING, thinkingLabel, accumulated))
                    },
                    onContentDelta = { accumulated ->
                        onStepSync?.invoke(AgentStep(StepType.THINKING, "$label...", accumulated.takeLast(200)))
                    }
                )
            } catch (e: java.io.IOException) {
                // Classify the error: context overflow should not be retried as network error
                val msg = e.message.orEmpty().lowercase()
                val isContextOverflow = CONTEXT_OVERFLOW_PATTERNS.any { pattern ->
                    Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(msg)
                }
                if (isContextOverflow) {
                    throw ContextOverflowException("上下文过长，API 拒绝请求: ${e.message}")
                }
                lastException = e
                if (attempt < maxStreamRetries - 1) {
                    onStepSync?.invoke(AgentStep(StepType.THINKING, "$label 网络中断，等待网络恢复(${attempt + 1}/${maxStreamRetries - 1})...", ""))
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

    // ── Function Calling helpers ──

    /** 将 ToolDef 列表转换为 API 的 tools JSONArray */
    private fun buildToolsJson(tools: List<ToolDef>): org.json.JSONArray? {
        if (tools.isEmpty()) return null
        return ToolDef.toToolsArray(tools)
    }

    /** 将 ToolCall 列表序列化为可存入 message map 的 JSON 字符串 */
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

    /** 构建 assistant 消息（含 tool_calls 元数据） */
    private fun buildAssistantMessage(content: String, toolCalls: List<ToolCall>): Map<String, String> {
        val msg = mutableMapOf("role" to "assistant", "content" to content)
        if (toolCalls.isNotEmpty()) {
            msg["_tool_calls"] = serializeToolCalls(toolCalls)
        }
        return msg
    }

    /** 构建 tool role 结果消息 */
    private fun buildToolResultMessage(toolCallId: String, toolName: String, result: String): Map<String, String> {
        return mapOf(
            "role" to "tool",
            "tool_call_id" to toolCallId,
            "name" to toolName,
            "content" to result
        )
    }

    /**
     * 从 ChatResponse 中提取工具调用（仅 function calling）。
     */
    private fun extractToolCalls(chatResp: ChatResponse): List<ToolCall> {
        return chatResp.toolCalls
    }

    /**
     * Estimate input token count for a message list.
     * Uses a rough chars/token heuristic — no tokenizer needed on-device.
     * Conservative (overestimates) to avoid silent truncation.
     */
    private fun estimateInputTokens(messages: List<Map<String, String>>): Int {
        val totalChars = messages.sumOf { (it["content"]?.length ?: 0) + 10 } // +10 for role/overhead
        return (totalChars / CHARS_PER_TOKEN).toInt()
    }

    /**
     * Ensure messages fit within a token budget.
     * If estimated input tokens + desired output tokens > model's limit,
     * compress the messages in-place.
     *
     * @return The (possibly compressed) message list.
     */
    private fun ensureTokenBudget(
        messages: MutableList<Map<String, String>>,
        outputTokens: Int
    ): MutableList<Map<String, String>> {
        // Most OpenAI-compatible endpoints have ~128K context. Use 120K as safe cap.
        val contextCap = 120_000
        val inputBudget = contextCap - outputTokens
        val estimated = estimateInputTokens(messages)
        if (estimated <= inputBudget) return messages

        // Compress and check again
        val compressed = compressContext(messages)
        return compressed
    }

    /**
     * Record a failure pattern from REFINEMENT into memory.
     * This enables future PLANNING phases to avoid the same mistakes.
     */
    private fun recordFailurePattern(diagnosis: WorkspaceDiagnosis) {
        try {
            val wm = toolExecutor.workspaceManager
            val appId = toolExecutor.currentAppId
            val archProfile = ArchitectureAnalyzer.analyze(wm, appId)

            val projectInfo = if (archProfile != null) {
                "项目类型:${archProfile.projectType.label}, 复杂度:${archProfile.complexity.label}, 文件数:${archProfile.files.size}"
            } else "未知项目"

            val content = "【生成教训】$projectInfo — 自检修复 ${MAX_REFINEMENT_ROUNDS} 轮后仍存在：${diagnosis.summary}。" +
                "建议：${getFailureAdvice(diagnosis.summary)}"
            toolExecutor.memoryStorage.save(content, listOf("生成教训", "自检失败", "编程"))
        } catch (_: Exception) { /* best-effort */ }
    }

    /** 根据失败摘要生成针对性建议 */
    private fun getFailureAdvice(summary: String): String {
        val advice = mutableListOf<String>()
        if (summary.contains("引用缺失") || summary.contains("404")) {
            advice.add("确保所有 script/link/img 引用的文件都已创建")
        }
        if (summary.contains("运行时错误")) {
            advice.add("检查 JS 初始化顺序、DOM 元素是否存在于页面中")
        }
        if (summary.contains("CSS↔JS")) {
            advice.add("CSS 类名与 JS querySelector/classList 中的类名必须完全一致")
        }
        if (summary.contains("语法问题")) {
            advice.add("检查括号配对、分号遗漏、字符串未闭合")
        }
        return if (advice.isEmpty()) "注意代码完整性和文件引用正确性" else advice.joinToString("；")
    }

    /** Tools that are safe to run in parallel (read-only, no side effects). */
    private val PARALLELIZABLE_TOOLS = setOf(
        "read_file", "list_files", "search", "validate", "diff_file",
        "inspect_workspace", "read_plan",
        // Legacy names
        "read_workspace_file", "list_workspace_files", "grep_file", "grep_workspace",
        "file_info", "list_snapshots", "validate_html", "validate_js",
        // Non-file read-only tools
        "list_saved_apps", "get_device_info", "get_battery_level",
        "get_current_time", "load_data", "memory_search", "memory_list",
        "list_modules"
    )

    /**
     * Execute tool calls with parallelism where safe.
     * Read-only tools run concurrently; mutation tools run sequentially to preserve order.
     *
     * @return Triple(results, hasFailure, allEditsFailed)
     */
    private suspend fun executeToolsBatched(
        toolCalls: List<ToolCall>,
        onStep: suspend (AgentStep) -> Unit
    ): Triple<List<Pair<ToolCall, ToolResult>>, Boolean, Boolean> {
        // If all calls are parallelizable, run them all at once
        val allParallel = toolCalls.all { it.toolName in PARALLELIZABLE_TOOLS }
        // If only 1 call, no point in batching
        if (toolCalls.size <= 1 || !allParallel) {
            return executeToolsSequential(toolCalls, onStep)
        }

        // Parallel execution
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

    /** Sequential fallback — used when mutation tools are present. */
    private suspend fun executeToolsSequential(
        toolCalls: List<ToolCall>,
        onStep: suspend (AgentStep) -> Unit
    ): Triple<List<Pair<ToolCall, ToolResult>>, Boolean, Boolean> {
        val results = mutableListOf<Pair<ToolCall, ToolResult>>()
        var hasFailure = false
        var allEditsFailed = true
        var hasAnyFileTool = false
        for (call in toolCalls) {
            // ── Doom loop detection (OpenCode pattern) ──
            val argsHash = call.arguments.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value}" }.hashCode()
            val signature = call.toolName to argsHash
            recentToolCalls.add(signature)
            if (recentToolCalls.size > DOOM_LOOP_THRESHOLD) {
                recentToolCalls.removeAt(0)
            }
            if (recentToolCalls.size == DOOM_LOOP_THRESHOLD &&
                recentToolCalls.all { it == signature }) {
                // Doom loop detected — force AI to change strategy
                onStep(AgentStep(StepType.THINKING,
                    "⚠ 检测到重复循环: ${call.toolName}", "连续 $DOOM_LOOP_THRESHOLD 次相同调用"))
                val doomResult = ToolResult(call.toolName, false,
                    "Error: 检测到重复循环——连续 $DOOM_LOOP_THRESHOLD 次使用相同参数调用 ${call.toolName}。" +
                    "请更换策略：如果是 replace_in_file 失败，改用 write_file 整体重写；" +
                    "如果是 read_workspace_file，说明你已读取过该文件，请使用已有信息继续。")
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

            // ── Tool output truncation (OpenCode pattern) ──
            val truncatedOutput = truncateToolOutput(result.result, call.toolName)
            val finalResult = if (truncatedOutput != result.result) {
                result.copy(result = truncatedOutput)
            } else result

            onStep(AgentStep(StepType.TOOL_RESULT,
                if (result.success) "✓ ${call.toolName}" else "✗ ${call.toolName}",
                truncatedOutput.take(200)))
            results.add(call to finalResult)
        }
        return Triple(results, hasFailure, allEditsFailed && hasAnyFileTool)
    }

    /**
     * 将工具执行结果添加为 function calling 格式的消息到对话中。
     *
     * @return 结果文本摘要（用于后续判断）
     */
    private fun appendToolResults(
        messages: MutableList<Map<String, String>>,
        toolCalls: List<ToolCall>,
        results: List<Pair<ToolCall, ToolResult>>
    ): String {
        // 1. 添加包含 tool_calls 的 assistant 消息（如果尚未添加
        // 注意：调用方需在此之前确保 assistant 消息已添加

        // 2. 添加每个工具的结果消息
        val summaryParts = StringBuilder()
        for ((call, result) in results) {
            val callId = call.id ?: "call_${call.toolName}_${System.nanoTime()}"
            val truncatedOutput = truncateToolOutput(result.result, call.toolName)
            val statusPrefix = if (result.success) "✓" else "✗"
            summaryParts.appendLine("$statusPrefix ${call.toolName}: ${truncatedOutput.take(100)}")
            messages.add(buildToolResultMessage(callId, call.toolName, truncatedOutput))
        }
        return summaryParts.toString()
    }

    /**
     * Truncate tool output to prevent context explosion (OpenCode pattern).
     * Large outputs (>2000 lines or >50KB) are trimmed with a notice to use
     * offset/limit or grep for targeted reading.
     */
    private fun truncateToolOutput(output: String, toolName: String): String {
        val lines = output.lines()
        val bytes = output.length

        if (lines.size <= TOOL_OUTPUT_MAX_LINES && bytes <= TOOL_OUTPUT_MAX_BYTES) return output

        // Persist full output for later retrieval
        val savedFile = try {
            toolExecutor.workspaceManager.saveTruncatedOutput(output, toolName)
        } catch (_: Exception) { null }

        val keepLines = minOf(TOOL_OUTPUT_MAX_LINES, lines.size)
        val truncated = lines.take(keepLines).joinToString("\n")
        val finalOutput = if (truncated.length > TOOL_OUTPUT_MAX_BYTES) {
            truncated.take(TOOL_OUTPUT_MAX_BYTES)
        } else truncated

        val savedHint = if (savedFile != null) " 完整输出已缓存为 $savedFile，可使用 read_truncated_output('$savedFile') 获取。" else ""
        val hint = when (toolName) {
            "read_file", "read_workspace_file" -> "内容过长已截断。使用 read_file 的 start_line/end_line 参数分段读取，或用 search 搜索特定内容。$savedHint"
            "search", "grep_workspace", "grep_file" -> "匹配结果过多已截断。请使用更精确的搜索模式或添加 file_filter 缩小范围。$savedHint"
            else -> "输出过长已截断（${lines.size} 行, ${bytes / 1024}KB）。$savedHint"
        }
        return "$finalOutput\n\n[截断] $hint"
    }

    /**
     * Retrieve past generation lessons relevant to the current request.
     * Searches memory for failure patterns, returns a compact hint block.
     */
    private fun retrieveRelevantLessons(): String {
        try {
            // Search for past generation lessons
            val lessons = toolExecutor.memoryStorage.search("生成教训", limit = 5)
            if (lessons.isEmpty()) return ""

            val block = StringBuilder("\n### ⚠ 过往生成教训（自动检索）\n")
            lessons.take(3).forEach { m ->
                block.appendLine("- ${m.content.take(200)}")
            }
            return block.toString()
        } catch (_: Exception) { return "" }
    }

}

data class OrchestratorResult(
    val finalResponse: String,
    val miniApp: com.example.link_pi.data.model.MiniApp?,
    val steps: Int,
    val thinkingContent: String = "",
    val workbenchRedirect: WorkbenchRedirect? = null
)
