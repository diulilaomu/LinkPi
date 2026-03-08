package com.example.link_pi.agent

import com.example.link_pi.network.AiService
import org.json.JSONArray

/**
 * Automatic memory producer — runs after each conversation round to extract
 * noteworthy information (preferences, facts, lessons) and persist them.
 *
 * Runs asynchronously so it never blocks the chat UI.
 */
class MemoryExtractor(
    private val aiService: AiService,
    private val memoryStorage: MemoryStorage
) {
    companion object {
        private const val MAX_TOKENS = 512
        /** Skip extraction for very short exchanges — unlikely to contain memorable info. */
        private const val MIN_MESSAGE_LENGTH = 15
        /** Don't let memories pile up infinitely — stop auto-extracting beyond this. */
        private const val MAX_MEMORY_COUNT = 200
    }

    /**
     * Analyze a user–assistant exchange and auto-save any new memories.
     * Returns the number of memories saved (0 if nothing noteworthy).
     */
    suspend fun extract(userMessage: String, assistantResponse: String): Int {
        // Quick filters — skip trivial exchanges
        if (userMessage.length < MIN_MESSAGE_LENGTH && assistantResponse.length < MIN_MESSAGE_LENGTH) return 0
        if (memoryStorage.count() >= MAX_MEMORY_COUNT) return 0

        // Gather existing memories for deduplication context
        val existing = memoryStorage.listAll().take(30)
        val existingSummary = if (existing.isNotEmpty()) {
            existing.joinToString("\n") { "- [${it.tags.joinToString(",")}] ${it.content.take(80)}" }
        } else ""

        val prompt = buildPrompt(userMessage, assistantResponse, existingSummary)

        return try {
            val response = aiService.chat(
                listOf(
                    mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                    mapOf("role" to "user", "content" to prompt)
                ),
                maxTokens = MAX_TOKENS
            )
            parseAndSave(response)
        } catch (_: Exception) {
            0
        }
    }

    private fun buildPrompt(user: String, assistant: String, existing: String): String {
        val sb = StringBuilder()
        sb.appendLine("## 对话内容")
        sb.appendLine("用户: ${user.take(500)}")
        sb.appendLine("助手: ${assistant.take(1000)}")
        if (existing.isNotBlank()) {
            sb.appendLine("\n## 已有记忆（避免重复）")
            sb.appendLine(existing)
        }
        return sb.toString()
    }

    /**
     * Parse AI response and save extracted memories.
     * Expected format: JSON array of {content, tags} objects,
     * or the literal "NONE" if nothing to remember.
     */
    private fun parseAndSave(response: String): Int {
        val trimmed = response.trim()
        if (trimmed.equals("NONE", ignoreCase = true) || trimmed == "[]") return 0

        // Extract JSON array — may be wrapped in ```json fences
        val jsonStr = extractJsonArray(trimmed) ?: return 0

        return try {
            val arr = JSONArray(jsonStr)
            var saved = 0
            for (i in 0 until arr.length().coerceAtMost(3)) {
                val obj = arr.getJSONObject(i)
                val content = obj.optString("content", "").trim()
                val tagsStr = obj.optString("tags", "")
                if (content.length < 5) continue

                val tags = tagsStr.split(Regex("[,，;；]+"))
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() } + listOf("auto")

                // Skip if highly similar to existing memory
                val similar = memoryStorage.search(content, limit = 1)
                if (similar.isNotEmpty() && isSimilar(content, similar[0].content)) continue

                memoryStorage.save(content, tags)
                saved++
            }
            saved
        } catch (_: Exception) {
            0
        }
    }

    private fun extractJsonArray(text: String): String? {
        // Try direct parse
        if (text.startsWith("[")) return text
        // Try extracting from ```json ... ``` fences
        val fenceRegex = Regex("```(?:json)?\\s*\\n?(\\[.*?])\\s*```", RegexOption.DOT_MATCHES_ALL)
        fenceRegex.find(text)?.let { return it.groupValues[1] }
        // Try finding bare array in text
        val arrRegex = Regex("\\[\\s*\\{.*}\\s*]", RegexOption.DOT_MATCHES_ALL)
        arrRegex.find(text)?.let { return it.value }
        return null
    }

    /** Simple similarity check — if >70% of words overlap, consider it duplicate. */
    private fun isSimilar(a: String, b: String): Boolean {
        val wordsA = a.lowercase().split(Regex("[\\s,;，；。！？]+")).filter { it.length > 1 }.toSet()
        val wordsB = b.lowercase().split(Regex("[\\s,;，；。！？]+")).filter { it.length > 1 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val overlap = wordsA.intersect(wordsB).size
        val minSize = minOf(wordsA.size, wordsB.size)
        return overlap.toDouble() / minSize > 0.7
    }
}

private const val SYSTEM_PROMPT = """你是一个记忆提取器。分析用户与AI助手的对话，提取值得长期记住的信息。

提取以下类型的信息：
1. 用户偏好（UI风格、颜色、框架、语言等）
2. 用户的个人信息或习惯
3. 重要的技术决策或约定
4. 用户明确要求记住的事项
5. 有价值的经验教训

规则：
- 只提取**新的、有价值的**信息，不要提取显而易见的内容
- 如果对话只是普通问答或闲聊，没有值得记住的内容，返回 NONE
- 不要重复"已有记忆"中已存在的信息
- 每条记忆要简洁明了（一两句话）
- 最多提取3条记忆

输出格式：JSON数组，或 NONE
```json
[{"content": "记忆内容", "tags": "标签1,标签2"}]
```"""
