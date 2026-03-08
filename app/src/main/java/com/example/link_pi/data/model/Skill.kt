package com.example.link_pi.data.model

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
    val createdAt: Long = System.currentTimeMillis()
)
