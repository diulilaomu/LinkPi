package com.example.link_pi.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatResponse(
    val content: String,
    val thinkingContent: String = ""
)

class AiService(private val config: AiConfig) {

    // Separate client for AI API calls — allows redirects (some API gateways redirect)
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Convenience method — returns content string only (for backward compat). */
    suspend fun chat(
        messages: List<Map<String, String>>,
        maxTokens: Int = config.activeModel.maxTokens,
        temperature: Double = config.activeModel.temperature,
        enableThinking: Boolean = config.activeModel.enableThinking
    ): String = chatFull(messages, maxTokens, temperature, enableThinking).content

    /** Single-prompt shorthand (e.g. for intent classification). Always disables thinking. */
    suspend fun chat(prompt: String, maxTokens: Int = 10): String {
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        return chat(messages, maxTokens = maxTokens, temperature = 0.0, enableThinking = false)
    }

    /** Full response including reasoning_content when deep thinking is enabled. */
    suspend fun chatFull(
        messages: List<Map<String, String>>,
        maxTokens: Int = config.activeModel.maxTokens,
        temperature: Double = config.activeModel.temperature,
        enableThinking: Boolean = config.activeModel.enableThinking
    ): ChatResponse = withContext(Dispatchers.IO) {
        val endpoint = normalizeEndpoint(config.apiEndpoint)

        val jsonMessages = buildJsonMessages(messages)

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
        val content = if (message.isNull("content")) "" else message.optString("content", "")
        val thinking = if (message.isNull("reasoning_content")) "" else message.optString("reasoning_content", "")
        ChatResponse(content, thinking)
    }

    /**
     * Streaming chat — delivers thinking tokens in real-time via [onThinkingDelta].
     * The callback is invoked from an IO thread — must be thread-safe.
     */
    suspend fun chatStream(
        messages: List<Map<String, String>>,
        maxTokens: Int = config.activeModel.maxTokens,
        temperature: Double = config.activeModel.temperature,
        enableThinking: Boolean = config.activeModel.enableThinking,
        onThinkingDelta: (accumulated: String) -> Unit = {},
        onContentDelta: (accumulated: String) -> Unit = {}
    ): ChatResponse = withContext(Dispatchers.IO) {
        val endpoint = normalizeEndpoint(config.apiEndpoint)

        val jsonMessages = buildJsonMessages(messages)

        val requestBody = JSONObject().apply {
            put("model", config.modelName)
            put("messages", jsonMessages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", true)
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
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            throw IOException("API ${response.code} ($endpoint): $body")
        }

        val thinkingBuf = StringBuilder()
        val contentBuf = StringBuilder()
        var lastEmitLen = 0
        var lastContentEmitLen = 0

        val reader: BufferedReader = response.body?.byteStream()?.bufferedReader()
            ?: throw IOException("Empty stream body")

        reader.use { r ->
            var line = r.readLine()
            while (line != null) {
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break   // stream finished — exit immediately
                    try {
                        val chunk = JSONObject(data)
                        val delta = chunk.getJSONArray("choices")
                            .getJSONObject(0)
                            .optJSONObject("delta")

                        if (delta != null) {
                            val thinkDelta = if (delta.isNull("reasoning_content")) ""
                                else delta.optString("reasoning_content", "")
                            if (thinkDelta.isNotEmpty()) {
                                thinkingBuf.append(thinkDelta)
                            }

                            val contentDelta = if (delta.isNull("content")) ""
                                else delta.optString("content", "")
                            if (contentDelta.isNotEmpty()) {
                                contentBuf.append(contentDelta)
                            }
                        }
                    } catch (_: Exception) { /* skip malformed chunks */ }
                }

                // Emit accumulated thinking in real-time (every ~50 chars of new thinking)
                if (thinkingBuf.length - lastEmitLen >= 50) {
                    lastEmitLen = thinkingBuf.length
                    onThinkingDelta(thinkingBuf.toString())
                }

                // Emit accumulated content in real-time (every ~80 chars)
                if (contentBuf.length - lastContentEmitLen >= 80) {
                    lastContentEmitLen = contentBuf.length
                    onContentDelta(contentBuf.toString())
                }

                line = r.readLine()
            }
        }

        // Final emit of full thinking
        if (thinkingBuf.isNotEmpty() && thinkingBuf.length > lastEmitLen) {
            onThinkingDelta(thinkingBuf.toString())
        }
        // Final emit of full content
        if (contentBuf.isNotEmpty() && contentBuf.length > lastContentEmitLen) {
            onContentDelta(contentBuf.toString())
        }

        ChatResponse(contentBuf.toString(), thinkingBuf.toString())
    }

    /**
     * Normalize endpoint URL:
     * - trim whitespace
     * - remove trailing slash
     * - if it ends with /v1, append /chat/completions
     * - if it doesn't contain /chat/completions, try to append it
     */
    /**
     * Build JSON messages array, supporting multimodal content via `_images` key.
     */
    private fun buildJsonMessages(messages: List<Map<String, String>>): JSONArray {
        val arr = JSONArray()
        for (msg in messages) {
            arr.put(JSONObject().apply {
                put("role", msg["role"])
                val images = msg["_images"]
                if (images != null) {
                    val contentArray = JSONArray()
                    contentArray.put(JSONObject().put("type", "text").put("text", msg["content"] ?: ""))
                    for (dataUrl in images.split("|||")) {
                        if (dataUrl.isNotBlank()) {
                            contentArray.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().put("url", dataUrl))
                            })
                        }
                    }
                    put("content", contentArray)
                } else {
                    put("content", msg["content"])
                }
            })
        }
        return arr
    }

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
