package com.example.link_pi.workbench

import android.content.Context
import android.util.Log
import com.example.link_pi.agent.AgentOrchestrator
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.agent.OrchestratorResult
import com.example.link_pi.agent.StepType
import com.example.link_pi.agent.ToolExecutor
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.network.AiConfig
import com.example.link_pi.network.AiService
import com.example.link_pi.skill.BuiltInSkills
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

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

    private val _steps = MutableStateFlow<List<AgentStep>>(emptyList())
    val steps: StateFlow<List<AgentStep>> = _steps

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
        _steps.value = emptyList()

        // Configure AI with the task's model
        val aiConfig = AiConfig(context)
        if (task.modelId.isNotBlank()) {
            aiConfig.setActive(task.modelId)
        }
        val aiService = AiService(aiConfig)
        val toolExecutor = ToolExecutor(context, miniAppStorage)
        // Pre-set workspace ID so files go to the right directory
        toolExecutor.currentAppId = task.appId
        val orchestrator = AgentOrchestrator(aiService, toolExecutor)

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
            // Build messages — simple: system prompt + user prompt
            val messages = listOf(
                mapOf("role" to "system", "content" to BuiltInSkills.DEFAULT.systemPrompt),
                mapOf("role" to "user", "content" to task.userPrompt)
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
                _steps.value = synchronized(collectedSteps) { collectedSteps.toList() }
            }

            val result: OrchestratorResult = orchestrator.run(
                conversationMessages = messages,
                skill = BuiltInSkills.DEFAULT,
                enableThinking = task.enableThinking,
                onStep = { step ->
                    synchronized(collectedSteps) { collectedSteps.add(step) }
                    _steps.value = synchronized(collectedSteps) { collectedSteps.toList() }

                    // Map step types to task progress
                    current = mapStepToProgress(current, step, toolExecutor)
                    taskStorage.save(current)
                    onUpdate(current)
                },
                onStepSync = syncStepCallback
            )

            // Completed — save MiniApp if produced
            val fileCount = try {
                toolExecutor.workspaceManager.getAllFiles(task.appId).size
            } catch (_: Exception) { 0 }

            if (result.miniApp != null) {
                miniAppStorage.save(result.miniApp)
            }

            current = current.copy(
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
            toolExecutor.workspaceManager.getAllFiles(task.appId).size
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
}
