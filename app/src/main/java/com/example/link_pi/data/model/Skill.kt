package com.example.link_pi.data.model

import com.example.link_pi.skill.BridgeGroup
import com.example.link_pi.skill.CdnGroup
import com.example.link_pi.skill.ToolGroup

/**
 * Knowledge injection mode — controls what context is injected into the AI prompt.
 * - chat: personal info + preferences + memories are injected
 * - coding: ONLY system environment, tools, NativeBridge docs — nothing personal
 */
enum class SkillMode {
    CHAT,   // Casual conversation — inject personal knowledge
    CODING; // Programming mode — only system/tool docs

    companion object {
        fun fromString(s: String): SkillMode = when (s.lowercase()) {
            "chat" -> CHAT
            else -> CODING
        }
    }
}

data class Skill(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val systemPrompt: String,
    val mode: SkillMode = SkillMode.CODING,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** NativeBridge API groups to inject when generating apps. */
    val bridgeGroups: Set<BridgeGroup> = setOf(BridgeGroup.STORAGE, BridgeGroup.UI_FEEDBACK),
    /** CDN library groups to inject when generating apps. */
    val cdnGroups: Set<CdnGroup> = emptySet(),
    /** Extra tool groups beyond the Intent×Phase default. */
    val extraToolGroups: Set<ToolGroup> = emptySet()
)
