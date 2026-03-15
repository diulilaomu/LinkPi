package com.example.link_pi.skill

/**
 * Intent classifier using fast local heuristics.
 */
object IntentClassifier {

    private val MODULE_KEYWORDS = setOf(
        "模块", "module", "端点", "endpoint", "api"
    )
    private val MEMORY_KEYWORDS = setOf(
        "记忆", "记住", "忘记", "偏好", "memory", "记录"
    )

    // English keywords that need word-boundary matching to avoid substring false positives
    private val EN_WORD_BOUNDARY_SET = setOf(
        "app", "html", "api", "module", "endpoint", "memory",
        "fix", "update", "change", "modify", "improve",
        "create", "build", "make", "generate"
    )

    /** Check if keyword is found in text, using word boundary for English words. */
    private fun containsKeyword(text: String, keyword: String): Boolean {
        return if (keyword in EN_WORD_BOUNDARY_SET) {
            Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        } else {
            text.contains(keyword)
        }
    }

    fun classifyLocal(message: String, hasActiveWorkspace: Boolean): UserIntent {
        val lower = message.lowercase()
        return when {
            MEMORY_KEYWORDS.any { containsKeyword(lower, it) } -> UserIntent.MEMORY_OPS
            MODULE_KEYWORDS.any { containsKeyword(lower, it) } -> UserIntent.MODULE_MGMT
            // CREATE_APP / MODIFY_APP no longer classified here;
            // AI will call launch_workbench tool when appropriate
            else -> UserIntent.CONVERSATION
        }
    }
}
