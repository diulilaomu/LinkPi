package com.example.link_pi.skill

import android.util.Log
import com.example.link_pi.data.model.Skill
import com.example.link_pi.network.AiService
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Intent classifier with fast local heuristics + AI fallback.
 * AI call has a 6-second timeout; on timeout falls back to local rules.
 */
object IntentClassifier {

    private val CREATE_KEYWORDS = setOf(
        "做", "写", "创建", "生成", "开发", "制作", "搭建", "构建", "实现",
        "帮我做", "帮我写", "帮我开发", "帮我创建",
        "做一个", "写一个", "来一个", "弄一个", "搞一个",
        "工具", "页面", "网页", "应用", "app", "html",
        "create", "build", "make", "generate"
    )
    private val CREATE_PHRASE_KEYWORDS = setOf(
        "游戏"
    )
    // Pattern: creation verb + game noun -> CREATE_APP
    // But "这是什么游戏" (what game is this) -> CONVERSATION
    private val CREATE_VERB_PATTERN = Regex("(做|写|创建|生成|开发|制作|搭建|构建|实现|来一个|弄一个|搞一个|做一个|写一个)")
    private val MODIFY_KEYWORDS = setOf(
        "修改", "修复", "改一下", "更新", "改进", "优化", "调整", "换成",
        "加一个", "加上", "删掉", "去掉", "移除",
        "fix", "update", "change", "modify", "improve"
    )
    private val MODULE_KEYWORDS = setOf(
        "模块", "module", "端点", "endpoint", "api"
    )
    private val MEMORY_KEYWORDS = setOf(
        "记忆", "记住", "忘记", "偏好", "memory", "记录"
    )

    suspend fun classify(
        message: String,
        hasActiveWorkspace: Boolean,
        skill: Skill,
        aiService: AiService
    ): UserIntent {
        // Try AI classification with 10s timeout
        val aiResult = withTimeoutOrNull(10000L) {
            try {
                val prompt = buildPrompt(message, hasActiveWorkspace, skill)
                val result = aiService.chat(prompt, maxTokens = 10)
                UserIntent.valueOf(result.trim().uppercase().replace(" ", "_"))
            } catch (e: Exception) {
                Log.w("IntentClassifier", "AI分类失败", e)
                null
            }
        }
        if (aiResult != null) return aiResult

        // Fallback: local heuristic classification
        Log.w("IntentClassifier", "AI分类超时，使用本地规则")
        return classifyLocal(message, hasActiveWorkspace)
    }

    private fun buildPrompt(message: String, hasActiveWorkspace: Boolean, skill: Skill) = """将用户的意图分类为以下类别之一。
只回复类别名称，不要输出其他任何内容。

类别：
- CONVERSATION: 闲聊、提问、打招呼、询问某事是什么、讨论话题。"这是什么"、"怎么用"、"什么意思"等提问都是 CONVERSATION
- CREATE_APP: 明确要求创建/构建/制作新的应用、游戏、工具、页面或交互功能。必须有创建动词如"做一个"、"写一个"、"帮我开发"
- MODIFY_APP: 想要修改/修复/更新/改进现有应用
- MODULE_MGMT: 想要创建/管理 API 模块或端点
- MEMORY_OPS: 询问或管理记忆/偏好

重要：仅当用户明确要求"创建/做/写"某个应用时才选 CREATE_APP。提问、描述、讨论等都是 CONVERSATION。

上下文：has_active_workspace=$hasActiveWorkspace, current_skill=${skill.name}

用户消息：$message
类别："""

    private fun classifyLocal(message: String, hasActiveWorkspace: Boolean): UserIntent {
        val lower = message.lowercase()
        return when {
            MEMORY_KEYWORDS.any { lower.contains(it) } -> UserIntent.MEMORY_OPS
            MODULE_KEYWORDS.any { lower.contains(it) } -> UserIntent.MODULE_MGMT
            hasActiveWorkspace && MODIFY_KEYWORDS.any { lower.contains(it) } -> UserIntent.MODIFY_APP
            CREATE_KEYWORDS.any { lower.contains(it) } -> UserIntent.CREATE_APP
            // "游戏" etc. only trigger CREATE_APP when combined with a creation verb
            CREATE_PHRASE_KEYWORDS.any { lower.contains(it) } && CREATE_VERB_PATTERN.containsMatchIn(lower) -> UserIntent.CREATE_APP
            hasActiveWorkspace -> UserIntent.MODIFY_APP  // default when workspace is active
            else -> UserIntent.CONVERSATION
        }
    }
}
