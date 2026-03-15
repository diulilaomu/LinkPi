package com.example.link_pi.skill

/**
 * 对话域 (CONVERSATION / MEMORY_OPS) 的 Prompt 模板。
 *
 * 对话域没有规划/生成的阶段划分，始终使用同一组工具。
 */
object PromptConversation {

    /** 对话域工具组 — CONVERSATION 和 MEMORY_OPS 共用 */
    val TOOL_GROUPS: Set<ToolGroup> = setOf(
        ToolGroup.CORE, ToolGroup.MEMORY, ToolGroup.DEVICE, ToolGroup.NETWORK
    )
}
