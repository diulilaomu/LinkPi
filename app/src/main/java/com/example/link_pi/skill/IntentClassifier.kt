package com.example.link_pi.skill

import com.example.link_pi.data.model.Skill
import com.example.link_pi.network.AiService

/**
 * Pure AI intent classifier.
 * Uses a single ~150-token AI call to determine user intent.
 */
object IntentClassifier {

    suspend fun classify(
        message: String,
        hasActiveWorkspace: Boolean,
        skill: Skill,
        aiService: AiService
    ): UserIntent {
        val prompt = """Classify the user's intent into exactly one category.
Reply with ONLY the category name, nothing else.

Categories:
- CONVERSATION: casual chat, questions, greetings, not requesting an app
- CREATE_APP: wants to create/build/make a new app, game, tool, page, or interactive thing
- MODIFY_APP: wants to change/fix/update/improve an existing app
- MODULE_MGMT: wants to create/manage API modules or endpoints
- MEMORY_OPS: asking about or managing memories/preferences

Context: has_active_workspace=$hasActiveWorkspace, current_skill=${skill.name}

User message: $message
Category:"""

        return try {
            val result = aiService.chat(prompt, maxTokens = 10)
            UserIntent.valueOf(result.trim().uppercase().replace(" ", "_"))
        } catch (_: Exception) {
            UserIntent.CONVERSATION
        }
    }
}
