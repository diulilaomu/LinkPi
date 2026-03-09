package com.example.link_pi.data.model

import com.example.link_pi.agent.AgentStep

data class Attachment(
    val name: String,
    val mimeType: String,
    val textContent: String? = null,
    val base64Data: String? = null   // data:image/png;base64,... for images
)

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val miniApp: MiniApp? = null,
    val agentSteps: List<AgentStep> = emptyList(),
    val thinkingContent: String = "",
    val attachments: List<Attachment> = emptyList()
)
