package com.example.link_pi.workbench

import android.content.Context
import android.util.Log
import com.example.link_pi.agent.AgentOrchestrator
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.agent.AppInfoAgent
import com.example.link_pi.agent.OrchestratorResult
import com.example.link_pi.agent.StepType
import com.example.link_pi.agent.ToolExecutor
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.network.AiConfig
import com.example.link_pi.network.AiService
import com.example.link_pi.skill.BuiltInSkills
import com.example.link_pi.skill.UserIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Independent generation engine for Workbench tasks.
 *
 * Reuses [AgentOrchestrator] under the hood but is decoupled from
 * the chat flow — it manages its own AiService, ToolExecutor, and
 * progress tracking.
 */
class WorkbenchEngine(
    private val context: Context,
    private val taskStorage: WorkbenchTaskStorage,
    private val miniAppStorage: MiniAppStorage
) {
    companion object {
        private const val TAG = "WorkbenchEngine"
    }

    private val _stepsMap = MutableStateFlow<Map<String, List<AgentStep>>>(emptyMap())
    val stepsMap: StateFlow<Map<String, List<AgentStep>>> = _stepsMap

    /** Get steps for a specific task. */
    fun stepsFor(taskId: String): List<AgentStep> = _stepsMap.value[taskId] ?: emptyList()

    /** Remove steps for a deleted task to prevent memory leak. */
    fun clearSteps(taskId: String) {
        _stepsMap.update { it - taskId }
    }

    /**
     * Execute a generation task end-to-end.
     *
     * This is a suspending function that runs the full agent pipeline
     * (intent → planning → generation → self-check) while updating
     * the task's status/progress in storage.
     *
     * @param task The task to execute (should be QUEUED).
     * @param onUpdate Called whenever the task is updated (for UI refresh).
     * @return The completed/failed task.
     */
    suspend fun execute(
        task: WorkbenchTask,
        onUpdate: (WorkbenchTask) -> Unit = {}
    ): WorkbenchTask = withContext(Dispatchers.IO) {
        _stepsMap.update { it + (task.id to emptyList()) }

        // Configure AI with the task's model (per-task override, no global mutation)
        val aiConfig = AiConfig(context)
        val aiService = AiService(aiConfig)
        if (task.modelId.isNotBlank()) {
            aiService.modelOverride = aiConfig.getModels().find { it.id == task.modelId }
        }
        val toolExecutor = ToolExecutor(context, miniAppStorage)
        // Pre-set workspace ID so files go to the right directory
        toolExecutor.currentAppId = task.appId
        val orchestrator = AgentOrchestrator(aiService, toolExecutor)

        // Detect modify scenario: workspace already has files → skip intent classification
        val isModify = toolExecutor.workspaceManager.hasFiles(task.appId)

        // Mark as PLANNING
        var current = task.copy(
            status = TaskStatus.PLANNING,
            progress = 5,
            currentStep = "正在分析请求...",
            updatedAt = System.currentTimeMillis()
        )
        taskStorage.save(current)
        onUpdate(current)

        try {
            // Cap user prompt to prevent token overflow squeezing system instructions
            val safePrompt = task.userPrompt.take(2000)

            // Build messages — simple: system prompt + user prompt
            val messages = listOf(
                mapOf("role" to "system", "content" to BuiltInSkills.DEFAULT.systemPrompt),
                mapOf("role" to "user", "content" to safePrompt)
            )

            val collectedSteps = mutableListOf<AgentStep>()

            val syncStepCallback: (AgentStep) -> Unit = { step ->
                synchronized(collectedSteps) {
                    val last = collectedSteps.lastOrNull()
                    if (last != null && last.description == step.description) {
                        collectedSteps[collectedSteps.size - 1] = step
                    } else {
                        collectedSteps.add(step)
                    }
                }
                _stepsMap.update { it + (task.id to synchronized(collectedSteps) { collectedSteps.toList() }) }
            }

            val result: OrchestratorResult = orchestrator.run(
                conversationMessages = messages,
                skill = BuiltInSkills.DEFAULT,
                enableThinking = task.enableThinking,
                overrideIntent = if (isModify) UserIntent.MODIFY_APP else UserIntent.CREATE_APP,
                onStep = { step ->
                    synchronized(collectedSteps) { collectedSteps.add(step) }
                    _stepsMap.update { it + (task.id to synchronized(collectedSteps) { collectedSteps.toList() }) }

                    // Map step types to task progress
                    current = mapStepToProgress(current, step, toolExecutor)
                    taskStorage.save(current)
                    onUpdate(current)
                },
                onStepSync = syncStepCallback
            )

            // Use the actual appId that was used during execution
            // (may differ from task.appId if AI called open_app_workspace)
            val actualAppId = toolExecutor.currentAppId
            val fileCount = try {
                toolExecutor.workspaceManager.getAllFiles(actualAppId).size
            } catch (_: Exception) { 0 }

            // If orchestrator didn't return a miniApp but workspace has files, build one
            val miniApp = result.miniApp ?: if (fileCount > 0) {
                buildFallbackMiniApp(toolExecutor, actualAppId)
            } else null

            if (miniApp != null) {
                // Save miniApp immediately so it's runnable
                miniAppStorage.save(miniApp.copy(id = actualAppId))
            }

            // Mark COMPLETED early — app is usable now, metadata will come async
            current = current.copy(
                appId = actualAppId,
                status = TaskStatus.COMPLETED,
                progress = 95,
                currentStep = "正在完善应用信息...",
                fileCount = fileCount,
                error = null,
                updatedAt = System.currentTimeMillis()
            )
            taskStorage.save(current)
            onUpdate(current)

            // ── Silent check chain: AppInfoAgent reads code and generates metadata ──
            val infoResult = if (miniApp?.isWorkspaceApp == true) {
                withTimeoutOrNull(45_000L) {
                    val agent = AppInfoAgent(aiService, toolExecutor.workspaceManager)
                    agent.run(actualAppId, task.userPrompt)
                }
            } else null

            // Apply agent results (or fallback)
            val finalName = infoResult?.name?.ifBlank { null }
                ?: miniApp?.let { sanitizeAppName(it.name).ifBlank { taskLikeName(task.userPrompt) } }
                ?: current.title
            val finalDesc = infoResult?.description?.ifBlank { null }
                ?: "基于用户需求「${task.userPrompt.take(50)}」生成的应用，包含 $fileCount 个文件。"

            if (miniApp != null) {
                val normalizedApp = miniApp.copy(id = actualAppId, name = finalName)
                miniAppStorage.save(normalizedApp)

                if (normalizedApp.isWorkspaceApp) {
                    // Collect used tools from agent steps
                    val usedTools = synchronized(collectedSteps) {
                        collectedSteps
                            .filter { it.type == StepType.TOOL_CALL }
                            .mapNotNull { step ->
                                val name = step.description.removePrefix("调用 ").trim()
                                name.ifBlank { null }
                            }
                            .distinct()
                    }
                    val (bridgeApis, modules) = detectCapabilities(toolExecutor, actualAppId)

                    toolExecutor.workspaceManager.generateManifest(
                        actualAppId, finalName,
                        readManifestVersion(toolExecutor, actualAppId),
                        finalDesc, usedTools, bridgeApis, modules
                    )

                    // Write architecture doc from agent result
                    infoResult?.architecture?.takeIf { it.isNotBlank() }?.let { arch ->
                        toolExecutor.workspaceManager.generateArchitecture(actualAppId, arch)
                    }
                }
            }

            current = current.copy(
                appId = actualAppId,
                title = finalName,
                description = finalDesc,
                status = TaskStatus.COMPLETED,
                progress = 100,
                currentStep = "完成",
                updatedAt = System.currentTimeMillis(),
                fileCount = fileCount,
                error = null
            )
            taskStorage.save(current)
            onUpdate(current)
            current
        } catch (e: Exception) {
            Log.e(TAG, "Task ${task.id} failed", e)
            current = current.copy(
                status = TaskStatus.FAILED,
                currentStep = "生成失败",
                updatedAt = System.currentTimeMillis(),
                error = e.message ?: "Unknown error"
            )
            taskStorage.save(current)
            onUpdate(current)
            current
        }
    }

    /**
     * Map an [AgentStep] to task progress updates.
     */
    private fun mapStepToProgress(
        task: WorkbenchTask,
        step: AgentStep,
        toolExecutor: ToolExecutor
    ): WorkbenchTask {
        val fileCount = try {
            toolExecutor.workspaceManager.getAllFiles(toolExecutor.currentAppId).size
        } catch (_: Exception) { 0 }

        return when (step.type) {
            StepType.THINKING -> {
                val status = when {
                    step.description.contains("自检") || step.description.contains("验证") ->
                        TaskStatus.CHECKING
                    step.description.contains("生成应用") || step.description.contains("Generation") ->
                        TaskStatus.GENERATING
                    else -> task.status
                }
                val progress = when (status) {
                    TaskStatus.PLANNING -> minOf(task.progress + 3, 30)
                    TaskStatus.GENERATING -> minOf(task.progress + 2, 85)
                    TaskStatus.CHECKING -> minOf(task.progress + 5, 95)
                    else -> task.progress
                }
                task.copy(
                    status = status,
                    progress = progress,
                    currentStep = step.description.take(60),
                    fileCount = fileCount,
                    updatedAt = System.currentTimeMillis()
                )
            }
            StepType.TOOL_CALL -> {
                val progress = if (task.status == TaskStatus.GENERATING)
                    minOf(task.progress + 3, 85) else task.progress
                task.copy(
                    status = if (task.status == TaskStatus.PLANNING) TaskStatus.GENERATING else task.status,
                    progress = progress,
                    currentStep = step.description.take(60),
                    fileCount = fileCount,
                    updatedAt = System.currentTimeMillis()
                )
            }
            StepType.TOOL_RESULT -> {
                task.copy(
                    fileCount = fileCount,
                    updatedAt = System.currentTimeMillis()
                )
            }
            StepType.FINAL_RESPONSE -> {
                task.copy(
                    progress = 95,
                    currentStep = "正在完成...",
                    fileCount = fileCount,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    private fun sanitizeAppName(raw: String): String {
        val firstLine = raw.lineSequence().firstOrNull().orEmpty()
        return firstLine
            .replace("名称：", "")
            .replace("应用名：", "")
            .replace("标题：", "")
            .replace("\"", "")
            .replace("“", "")
            .replace("”", "")
            .replace(Regex("[`*_#]"), "")
            .replace(Regex("""^[0-9.、\-\s]+"""), "")
            .trim()
            .take(12)
    }

    private fun taskLikeName(userPrompt: String): String {
        return userPrompt
            .replace("\n", " ")
            .replace(Regex("^(请|帮我|给我)?(生成|做|写|创建|开发|制作|搭建|构建|实现)(一个|个)?"), "")
            .trim()
            .ifBlank { "新应用" }
            .take(12)
    }

    private fun readManifestVersion(toolExecutor: ToolExecutor, appId: String): Int {
        return try {
            val text = java.io.File(toolExecutor.workspaceManager.getWorkspacePath(appId), "APP_INFO.json").readText()
            org.json.JSONObject(text).optInt("version", 0) + 1
        } catch (_: Exception) {
            1
        }
    }

    /**
     * Scan generated source files to detect NativeBridge API usage and module calls.
     * Returns (bridgeApis, modules).
     */
    private fun detectCapabilities(
        toolExecutor: ToolExecutor, appId: String
    ): Pair<List<String>, List<String>> {
        val bridgeApis = mutableSetOf<String>()
        val modules = mutableSetOf<String>()

        val bridgePatterns = mapOf(
            "saveData" to "STORAGE", "loadData" to "STORAGE", "removeData" to "STORAGE",
            "clearData" to "STORAGE", "listKeys" to "STORAGE", "getAppId" to "STORAGE",
            "showToast" to "UI_FEEDBACK", "vibrate" to "UI_FEEDBACK",
            "writeClipboard" to "UI_FEEDBACK", "sendToApp" to "UI_FEEDBACK",
            "getDeviceInfo" to "SENSOR", "getBatteryLevel" to "SENSOR", "getLocation" to "SENSOR",
            "nativeFetch" to "NETWORK"
        )
        val moduleRegex = Regex("""callModule\s*\(\s*['"]([^'"]+)['"]""")

        try {
            val root = toolExecutor.workspaceManager.getWorkspaceDir(appId)
            root.walkTopDown()
                .filter { it.isFile && (it.extension in listOf("html", "js", "css")) }
                .forEach { file ->
                    val content = file.readText()
                    for ((api, group) in bridgePatterns) {
                        if (content.contains(api)) bridgeApis.add(group)
                    }
                    moduleRegex.findAll(content).forEach { match ->
                        modules.add(match.groupValues[1])
                    }
                }
        } catch (_: Exception) { }

        return bridgeApis.sorted() to modules.sorted()
    }

    /**
     * Build a MiniApp from workspace files when the orchestrator didn't return one.
     */
    private fun buildFallbackMiniApp(toolExecutor: ToolExecutor, appId: String): MiniApp? {
        val wm = toolExecutor.workspaceManager
        if (!wm.hasFiles(appId)) return null
        val files = wm.getAllFiles(appId)
        val entryFile = when {
            "index.html" in files -> "index.html"
            else -> files.firstOrNull { it.endsWith(".html", ignoreCase = true) }
        } ?: return null
        val entryContent = wm.readEntryFile(appId, entryFile) ?: return null

        val existingApp = miniAppStorage.loadById(appId)
        val titleRegex = Regex("<title>(.+?)</title>", RegexOption.IGNORE_CASE)
        val name = titleRegex.find(entryContent)?.groupValues?.get(1)?.trim()
            ?: existingApp?.name ?: "应用"

        return MiniApp(
            id = appId,
            name = name,
            description = existingApp?.description ?: "",
            htmlContent = entryContent,
            createdAt = existingApp?.createdAt ?: System.currentTimeMillis(),
            isWorkspaceApp = true,
            entryFile = entryFile
        )
    }

}
