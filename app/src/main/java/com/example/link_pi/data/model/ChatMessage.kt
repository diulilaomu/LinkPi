package com.example.link_pi.data.model

import com.example.link_pi.agent.AgentStep

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val miniApp: MiniApp? = null,
    val agentSteps: List<AgentStep> = emptyList(),
    val thinkingContent: String = ""
)
