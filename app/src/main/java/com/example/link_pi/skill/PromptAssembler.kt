package com.example.link_pi.skill

import com.example.link_pi.agent.ToolDef
import com.example.link_pi.data.model.Skill

/**
 * 系统提示组装器 — Gateway + 五大域架构。
 *
 * [buildMessages] 返回多条 system message 列表（Gateway + 域指令 + 工具 + 动态上下文），
 * 支持 Phase 切换时只替换部分消息，减少冗余。
 * - [buildMessages]：新接口，返回多条 system message 列表（Gateway + 域指令 + 工具 + 动态上下文）
 */
object PromptAssembler {

    /**
     * Parsed AI selections from Planning phase.
     */
    data class CapabilitySelection(
        val toolGroups: Set<ToolGroup> = emptySet(),
        val bridgeGroups: Set<BridgeGroup> = emptySet(),
        val cdnGroups: Set<CdnGroup> = emptySet()
    ) {
        fun isEmpty() = toolGroups.isEmpty() && bridgeGroups.isEmpty() && cdnGroups.isEmpty()
    }

    /**
     * 传递给 [buildMessages] 的动态上下文。
     */
    data class DomainContext(
        val memorySnapshot: String? = null,
        val workspaceSnapshot: String? = null,
        val aiSelection: CapabilitySelection? = null,
        val injectedPrompts: List<String> = emptyList()
    )

    // ═══════════════════════════════════════════════════════
    //  新接口：多条 system message
    // ═══════════════════════════════════════════════════════

    /**
     * 构建多条 system message 列表（新架构）。
     *
     * 返回结构：
     * - messages[0] = Gateway（角色 + 通用规则 + 记忆）— 固定层
     * - messages[1] = 域指令（按 intent × phase 选择的工作流文本）
     * - messages[2] = 动态上下文（BridgeDocs + CdnDocs + workspaceSnapshot）
     *
     * 工具列表不再嵌入系统提示，而是通过 [resolveTools] 作为 API tools 参数传递。
     * 空内容的消息会被省略。
     */
    fun buildMessages(
        skill: Skill,
        intent: UserIntent,
        phase: PromptDomain.Phase,
        allTools: List<ToolDef>,
        context: DomainContext
    ): List<Map<String, String>> {

        // [0] Gateway — 固定层（不再包含工具调用格式说明）
        val gateway = PromptGateway.build(
            skillPrompt = skill.systemPrompt,
            memorySnapshot = context.memorySnapshot,
            injectedPrompts = context.injectedPrompts
        )

        // [1] 域指令
        val domainText = resolveDomainText(intent, phase)

        // [2] 动态上下文
        val dynamicText = buildDynamicContext(intent, phase, skill, context)

        // 组装 — 忽略空消息
        return buildList {
            add(mapOf("role" to "system", "content" to gateway))
            if (domainText.isNotBlank()) {
                add(mapOf("role" to "system", "content" to domainText))
            }
            if (dynamicText.isNotBlank()) {
                add(mapOf("role" to "system", "content" to dynamicText))
            }
        }
    }

    /**
     * 解析当前上下文应该传递给 API 的工具列表（Function Calling）。
     * 替代原来嵌入系统提示的文本工具列表。
     */
    fun resolveTools(
        skill: Skill,
        intent: UserIntent,
        phase: PromptDomain.Phase,
        allTools: List<ToolDef>,
        aiSelection: CapabilitySelection? = null
    ): List<ToolDef> {
        val agentPhase = phase.toAgentPhase()
        val groups = if (agentPhase == AgentPhase.GENERATION && intent.needsApp() && aiSelection != null && !aiSelection.isEmpty()) {
            val base = mutableSetOf(ToolGroup.CORE, ToolGroup.APP_CREATE, ToolGroup.APP_READ, ToolGroup.APP_EDIT, ToolGroup.CODING)
            base.addAll(aiSelection.toolGroups)
            base.addAll(skill.extraToolGroups)
            base
        } else {
            resolveToolGroups(intent, agentPhase, skill.extraToolGroups)
        }

        return allTools
            .filter { val g = TOOL_GROUP_MAP[it.name]; g == null || g in groups }
            .let { list -> if (intent.needsApp()) list.filter { it.name != "launch_workbench" } else list }
    }

    /** 按 intent × phase 选择域指令文本 */
    private fun resolveDomainText(intent: UserIntent, phase: PromptDomain.Phase): String = when {
        // BUILD_APP
        intent == UserIntent.BUILD_APP && phase == PromptDomain.Phase.PLANNING ->
            PromptApp.CAPABILITY_CATALOG + "\n\n" + PromptApp.planning()
        intent == UserIntent.BUILD_APP && phase == PromptDomain.Phase.GENERATION ->
            PromptApp.generation()
        intent == UserIntent.BUILD_APP && phase == PromptDomain.Phase.SUB_EXECUTION ->
            PromptApp.subAgentSystem()
        intent == UserIntent.BUILD_APP && phase == PromptDomain.Phase.SELF_CHECK ->
            PromptApp.selfCheck()
        // MODULE
        intent == UserIntent.MODULE_MGMT -> PromptModule.workflow()
        // CONVERSATION / MEMORY_OPS — 无专属工作流
        else -> ""
    }

    /** 构建动态上下文消息（BridgeDocs + CdnDocs + workspaceSnapshot） */
    private fun buildDynamicContext(
        intent: UserIntent,
        phase: PromptDomain.Phase,
        skill: Skill,
        context: DomainContext
    ): String {
        val needFullDocs = intent.needsApp() && phase != PromptDomain.Phase.PLANNING
        val effectiveBridgeGroups = if (context.aiSelection != null && !context.aiSelection.isEmpty())
            skill.bridgeGroups + context.aiSelection.bridgeGroups else skill.bridgeGroups
        val effectiveCdnGroups = if (context.aiSelection != null && !context.aiSelection.isEmpty())
            skill.cdnGroups + context.aiSelection.cdnGroups else skill.cdnGroups

        val bridgeDocs = if (needFullDocs) BridgeDocs.build(effectiveBridgeGroups) else ""
        val cdnDocs = if (needFullDocs) CdnDocs.build(effectiveCdnGroups) else ""

        return buildString {
            if (bridgeDocs.isNotBlank()) appendLine(bridgeDocs)
            if (cdnDocs.isNotBlank()) appendLine(cdnDocs)
            if (!context.workspaceSnapshot.isNullOrBlank()) appendLine(context.workspaceSnapshot)
        }.trimEnd()
    }

    /** PromptDomain.Phase → AgentPhase 映射 */
    private fun PromptDomain.Phase.toAgentPhase(): AgentPhase = when (this) {
        PromptDomain.Phase.PLANNING -> AgentPhase.PLANNING
        PromptDomain.Phase.GENERATION -> AgentPhase.GENERATION
        PromptDomain.Phase.SUB_EXECUTION -> AgentPhase.GENERATION
        PromptDomain.Phase.SELF_CHECK -> AgentPhase.REFINEMENT
    }

    // ═══════════════════════════════════════════════════════
    //  解析工具
    // ═══════════════════════════════════════════════════════

    /**
     * Parse the AI's capability selection from its planning response.
     */
    fun parseCapabilitySelection(planningResponse: String): CapabilitySelection? {
        val regex = Regex("<capability_selection>\\s*([\\s\\S]*?)\\s*</capability_selection>")
        val match = regex.find(planningResponse) ?: return null
        val block = match.groupValues[1]

        val toolGroups = mutableSetOf<ToolGroup>()
        val bridgeGroups = mutableSetOf<BridgeGroup>()
        val cdnGroups = mutableSetOf<CdnGroup>()

        val toolLine = Regex("tools:\\s*(.+)", RegexOption.IGNORE_CASE).find(block)
        toolLine?.groupValues?.get(1)?.split(",")?.forEach { token ->
            val t = token.trim().uppercase()
            try { toolGroups.add(ToolGroup.valueOf(t)) } catch (_: Exception) {}
        }

        val bridgeLine = Regex("bridge:\\s*(.+)", RegexOption.IGNORE_CASE).find(block)
        bridgeLine?.groupValues?.get(1)?.split(",")?.forEach { token ->
            val t = token.trim().uppercase()
            try { bridgeGroups.add(BridgeGroup.valueOf(t)) } catch (_: Exception) {}
        }

        val cdnLine = Regex("cdn:\\s*(.+)", RegexOption.IGNORE_CASE).find(block)
        cdnLine?.groupValues?.get(1)?.split(",")?.forEach { token ->
            val t = token.trim().uppercase()
            try { cdnGroups.add(CdnGroup.valueOf(t)) } catch (_: Exception) {}
        }

        return CapabilitySelection(toolGroups, bridgeGroups, cdnGroups)
    }

}
