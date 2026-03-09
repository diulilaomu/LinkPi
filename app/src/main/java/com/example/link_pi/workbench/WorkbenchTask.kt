package com.example.link_pi.workbench

/**
 * Represents the current status of a workbench generation task.
 */
enum class TaskStatus {
    /** Waiting to be picked up by the engine. */
    QUEUED,
    /** AI is analyzing the request and planning the approach. */
    PLANNING,
    /** AI is generating code / creating files. */
    GENERATING,
    /** Post-generation validation (validate_html, runtime check). */
    CHECKING,
    /** Successfully completed — app is ready to run. */
    COMPLETED,
    /** Generation failed (error stored in [WorkbenchTask.error]). */
    FAILED
}

/**
 * A single workbench generation task.
 *
 * Each task maps 1:1 to a workspace directory (via [appId]) and optionally
 * to a saved [MiniApp] once generation completes.
 */
data class WorkbenchTask(
    /** Unique task identifier. */
    val id: String,
    /** Workspace ID — doubles as the directory name under `workspaces/`. */
    val appId: String,
    /** Display title (extracted from user prompt or AI planning). */
    val title: String,
    /** Short description of what the app does. */
    val description: String = "",
    /** The original user prompt that triggered this task. */
    val userPrompt: String,
    /** Current task status. */
    val status: TaskStatus = TaskStatus.QUEUED,
    /** Progress percentage (0–100). */
    val progress: Int = 0,
    /** Human-readable label for the current step (e.g. "正在创建 index.html"). */
    val currentStep: String = "",
    /** Model ID from AiConfig to use for this task. */
    val modelId: String = "",
    /** Whether deep thinking is enabled. */
    val enableThinking: Boolean = false,
    /** Timestamp when the task was created. */
    val createdAt: Long = System.currentTimeMillis(),
    /** Timestamp when the task last changed status. */
    val updatedAt: Long = System.currentTimeMillis(),
    /** Error message if status == FAILED. */
    val error: String? = null,
    /** Number of files created so far. */
    val fileCount: Int = 0,
    /** Total generation steps executed. */
    val totalSteps: Int = 0
)
