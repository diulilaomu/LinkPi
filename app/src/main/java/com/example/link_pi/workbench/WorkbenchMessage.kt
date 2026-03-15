package com.example.link_pi.workbench

import com.example.link_pi.agent.AgentStep
import com.example.link_pi.agent.StepType

/**
 * Rich message types for the workbench message center.
 * Derived from [AgentStep] but with UI-oriented structure.
 */
sealed class WorkbenchMessage {
    abstract val id: Int
    abstract val timestamp: Long

    /** AI thinking / reasoning content — collapsible */
    data class Thinking(
        override val id: Int,
        override val timestamp: Long = System.currentTimeMillis(),
        val summary: String,
        val content: String
    ) : WorkbenchMessage()

    /** Tool call (create_file, write_file, replace_in_file, etc.) */
    data class ToolCall(
        override val id: Int,
        override val timestamp: Long = System.currentTimeMillis(),
        val toolName: String,
        val description: String,
        val args: String = ""
    ) : WorkbenchMessage()

    /** Tool result / outcome */
    data class ToolResult(
        override val id: Int,
        override val timestamp: Long = System.currentTimeMillis(),
        val success: Boolean,
        val description: String,
        val detail: String = ""
    ) : WorkbenchMessage()

    /** Code change — shows file path and code diff/content */
    data class CodeChange(
        override val id: Int,
        override val timestamp: Long = System.currentTimeMillis(),
        val filePath: String,
        val operation: String,   // "创建", "修改", "写入"
        val detail: String = ""
    ) : WorkbenchMessage()

    /** Status / phase transition message */
    data class Status(
        override val id: Int,
        override val timestamp: Long = System.currentTimeMillis(),
        val text: String
    ) : WorkbenchMessage()

    companion object {
        /** Convert a list of AgentSteps into WorkbenchMessages for display. */
        fun fromSteps(steps: List<AgentStep>): List<WorkbenchMessage> {
            val messages = mutableListOf<WorkbenchMessage>()
            for ((index, step) in steps.withIndex()) {
                when (step.type) {
                    StepType.THINKING -> {
                        if (step.detail.isNotBlank() && step.detail.length > 50) {
                            messages.add(Thinking(
                                id = index,
                                summary = step.description.take(80),
                                content = step.detail
                            ))
                        } else {
                            messages.add(Status(
                                id = index,
                                text = step.description
                            ))
                        }
                    }
                    StepType.TOOL_CALL -> {
                        val toolName = step.description
                            .removePrefix("调用 ").trim()
                        val isFileOp = toolName in setOf(
                            "create_file", "write_file", "replace_in_file",
                            "replace_lines", "delete_file"
                        )
                        if (isFileOp) {
                            val filePath = extractFilePath(step.detail)
                            val op = when {
                                toolName == "create_file" -> "创建"
                                toolName == "write_file" -> "写入"
                                toolName == "delete_file" -> "删除"
                                else -> "修改"
                            }
                            messages.add(CodeChange(
                                id = index,
                                filePath = filePath,
                                operation = op,
                                detail = step.detail
                            ))
                        } else {
                            messages.add(ToolCall(
                                id = index,
                                toolName = toolName,
                                description = step.description,
                                args = step.detail
                            ))
                        }
                    }
                    StepType.TOOL_RESULT -> {
                        val success = step.description.startsWith("✓")
                        messages.add(ToolResult(
                            id = index,
                            success = success,
                            description = step.description,
                            detail = step.detail
                        ))
                    }
                    StepType.FINAL_RESPONSE -> {
                        messages.add(Status(
                            id = index,
                            text = step.description
                        ))
                    }
                }
            }
            return messages
        }

        private fun extractFilePath(detail: String): String {
            // Try to extract path from tool call detail like "path=css/style.css, ..."
            val pathMatch = Regex("""path\s*=\s*([^\s,]+)""").find(detail)
            if (pathMatch != null) return pathMatch.groupValues[1]
            // Try "file_path=xxx"
            val fileMatch = Regex("""file_path\s*=\s*([^\s,]+)""").find(detail)
            if (fileMatch != null) return fileMatch.groupValues[1]
            return detail.take(60)
        }
    }
}

/**
 * Plan pipeline step — derived from AgentSteps to show progress phases.
 */
data class PlanStep(
    val label: String,
    val status: PlanStepStatus
)

enum class PlanStepStatus {
    PENDING,
    ACTIVE,
    COMPLETED
}

/**
 * Derive plan pipeline steps from agent steps.
 * Produces a simplified phases list: 规划 → 架构设计 → 生成文件 → 自检 → 完成
 */
fun derivePlanSteps(steps: List<AgentStep>, taskStatus: TaskStatus): List<PlanStep> {
    val hasPlanning = steps.any {
        it.description.contains("规划") || it.description.contains("Planning")
    }
    val hasArchitecture = steps.any {
        it.description.contains("架构") || it.description.contains("blueprint") ||
                it.description.contains("Blueprint")
    }
    val hasGeneration = steps.any {
        it.type == StepType.TOOL_CALL || it.description.contains("Generation") ||
                it.description.contains("生成应用")
    }
    val hasFileWrite = steps.any {
        it.type == StepType.TOOL_CALL && (
                it.description.contains("write_file") ||
                        it.description.contains("create_file") ||
                        it.description.contains("已写入")
                )
    }
    val hasCheck = steps.any {
        it.description.contains("自检") || it.description.contains("验证") ||
                it.description.contains("Checking") || it.description.contains("诊断")
    }
    val isComplete = taskStatus == TaskStatus.COMPLETED
    val isFailed = taskStatus == TaskStatus.FAILED

    // Determine the latest active phase
    val latestPhase = when {
        isComplete -> 5
        isFailed -> -1
        hasCheck -> 4
        hasFileWrite -> 3
        hasGeneration -> 3
        hasArchitecture -> 2
        hasPlanning -> 1
        else -> 0
    }

    fun status(phase: Int): PlanStepStatus = when {
        isFailed && phase <= latestPhase -> PlanStepStatus.COMPLETED
        phase < latestPhase -> PlanStepStatus.COMPLETED
        phase == latestPhase -> if (isComplete) PlanStepStatus.COMPLETED else PlanStepStatus.ACTIVE
        else -> PlanStepStatus.PENDING
    }

    return listOf(
        PlanStep("规划", status(1)),
        PlanStep("架构", status(2)),
        PlanStep("生成", status(3)),
        PlanStep("自检", status(4)),
        PlanStep("完成", status(5))
    )
}
