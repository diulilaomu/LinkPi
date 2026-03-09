package com.example.link_pi.skill

import com.example.link_pi.agent.ToolDef
import com.example.link_pi.data.model.Skill

/**
 * 系统提示组装器——三维注入矩阵：Skill × Intent × Phase。
 *
 * 所有模板文本集中在 [BuiltInSkills] 中，本类只负责组装逻辑。
 * - Planning: AI 看到能力目录 + 规划工具 → 输出规划 + 选择
 * - Generation: AI 看到选定工具/文档 → 生成代码
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
     * 组装系统提示。
     */
    fun build(
        skill: Skill,
        intent: UserIntent,
        phase: AgentPhase,
        allTools: List<ToolDef>,
        memorySnapshot: String?,
        aiSelection: CapabilitySelection? = null,
        workspaceSnapshot: String? = null,
        injectedPrompts: List<String> = emptyList()
    ): String {
        // For app GENERATION phase, merge AI selections with base groups
        val groups = if (phase == AgentPhase.GENERATION && intent.needsApp() && aiSelection != null && !aiSelection.isEmpty()) {
            val base = mutableSetOf(ToolGroup.CORE, ToolGroup.APP_CREATE, ToolGroup.APP_READ, ToolGroup.APP_EDIT, ToolGroup.CODING)
            base.addAll(aiSelection.toolGroups)
            base.addAll(skill.extraToolGroups)
            base
        } else {
            resolveToolGroups(intent, phase, skill.extraToolGroups)
        }

        val tools = allTools.filter { val g = TOOL_GROUP_MAP[it.name]; g == null || g in groups }
        val toolDescriptions = tools.joinToString("\n") { it.toPromptString() }

        // Bridge/CDN docs: for generation, use AI selections merged with skill defaults
        val effectiveBridgeGroups = if (aiSelection != null && !aiSelection.isEmpty()) skill.bridgeGroups + aiSelection.bridgeGroups else skill.bridgeGroups
        val effectiveCdnGroups = if (aiSelection != null && !aiSelection.isEmpty()) skill.cdnGroups + aiSelection.cdnGroups else skill.cdnGroups

        // App generation phase needs Bridge/CDN docs (CREATE for full build, MODIFY when adding new features)
        val needFullDocs = intent.needsApp() && phase != AgentPhase.PLANNING
        val bridgeDocs = if (needFullDocs)
            BridgeDocs.build(effectiveBridgeGroups) else ""
        val cdnDocs = if (needFullDocs)
            CdnDocs.build(effectiveCdnGroups) else ""

        val workflow = BuiltInSkills.resolveWorkflow(intent, phase)

        return buildString {
            appendLine(skill.systemPrompt)
            if (injectedPrompts.isNotEmpty()) {
                appendLine()
                appendLine("### 注入的辅助 Skill 指令")
                injectedPrompts.forEach { prompt ->
                    appendLine(prompt)
                    appendLine()
                }
            }
            appendLine()
            appendLine(BuiltInSkills.AGENT_MODE_HEADER)
            appendLine()
            appendLine("### 可用工具")
            appendLine(toolDescriptions)

            if (intent == UserIntent.CREATE_APP && phase == AgentPhase.PLANNING) {
                appendLine()
                appendLine(BuiltInSkills.CAPABILITY_CATALOG)
            }

            appendLine()
            appendLine("---")
            if (workflow.isNotBlank()) {
                appendLine()
                appendLine(workflow)
            }
            appendLine()
            appendLine("### 规则")
            appendLine(BuiltInSkills.RULE_BASE)
            if (intent == UserIntent.CREATE_APP && phase == AgentPhase.PLANNING) {
                appendLine(BuiltInSkills.RULES_PLANNING_APP)
            } else if (intent.needsApp() && phase != AgentPhase.PLANNING) {
                appendLine(BuiltInSkills.RULES_GENERATION_APP)
            } else if (intent == UserIntent.MODIFY_APP && phase == AgentPhase.PLANNING) {
                appendLine(BuiltInSkills.RULES_GENERATION_APP)
            }
            if (bridgeDocs.isNotBlank()) {
                appendLine()
                appendLine(bridgeDocs)
            }
            if (cdnDocs.isNotBlank()) {
                appendLine()
                appendLine(cdnDocs)
            }
            if (!workspaceSnapshot.isNullOrBlank()) {
                appendLine()
                appendLine(workspaceSnapshot)
            }
            appendLine()
            appendLine(BuiltInSkills.buildMemorySection(memorySnapshot, skill.mode))
        }.trimEnd()
    }

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
