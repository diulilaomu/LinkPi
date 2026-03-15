package com.example.link_pi.data.model

import java.util.UUID

data class ManagedSession(
    val id: String = UUID.randomUUID().toString(),
    val type: SessionType,
    val label: String,
    val source: SessionSource,
    val skillId: String? = null,
    val injectedSkillIds: List<String> = emptyList(),
    val enabledToolGroups: List<String> = emptyList(),
    val modelId: String,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val metadata: Map<String, String> = emptyMap()
)

enum class SessionType {
    CHAT,
    SSH_ASSIST,
    WORKBENCH
}

enum class SessionSource {
    USER_CREATED,
    FROM_SSH,
    FROM_WORKBENCH,
    FROM_SKILL,
    AUTO_CREATED
}

enum class SessionStatus {
    ACTIVE,
    PAUSED,
    ENDED
}
