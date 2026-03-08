package com.example.link_pi.network

import com.example.link_pi.agent.ModuleStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatResponse(
    val content: String,
    val thinkingContent: String = ""
)

class AiService(private val config: AiConfig) {

    // Derive from shared client (reuse connection pool), but extend read timeout for LLM calls
    private val client = ModuleStorage.httpClient.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /** Convenience method — returns content string only (for backward compat). */
    suspend fun chat(
        messages: List<Map<String, String>>,
        maxTokens: Int = 65536,
        temperature: Double = 0.7,
        enableThinking: Boolean = false
    ): String = chatFull(messages, maxTokens, temperature, enableThinking).content

    /** Full response including reasoning_content when deep thinking is enabled. */
    suspend fun chatFull(
        messages: List<Map<String, String>>,
        maxTokens: Int = 65536,
        temperature: Double = 0.7,
        enableThinking: Boolean = false
    ): ChatResponse = withContext(Dispatchers.IO) {
        val endpoint = normalizeEndpoint(config.apiEndpoint)

        val jsonMessages = JSONArray()
        for (msg in messages) {
            jsonMessages.put(JSONObject().apply {
                put("role", msg["role"])
                put("content", msg["content"])
            })
        }

        val requestBody = JSONObject().apply {
            put("model", config.modelName)
            put("messages", jsonMessages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (enableThinking) {
                put("enable_thinking", true)
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response")

        if (!response.isSuccessful) {
            throw IOException("API ${response.code} ($endpoint): $body")
        }

        val json = JSONObject(body)
        val message = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
        val content = message.optString("content", "")
        val thinking = message.optString("reasoning_content", "")
        ChatResponse(content, thinking)
    }

    /**
     * Normalize endpoint URL:
     * - trim whitespace
     * - remove trailing slash
     * - if it ends with /v1, append /chat/completions
     * - if it doesn't contain /chat/completions, try to append it
     */
    private fun normalizeEndpoint(raw: String): String {
        var url = raw.trim()
        // Remove trailing slashes
        while (url.endsWith("/")) url = url.dropLast(1)
        // If user only pasted the base URL (e.g. .../v1), append the path
        if (url.endsWith("/v1") || url.endsWith("/compatible-mode/v1")) {
            url = "$url/chat/completions"
        }
        return url
    }
}
