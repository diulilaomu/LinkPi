package com.example.link_pi.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

private const val MAX_RETRIES = 3
private val BACKOFF_MS = longArrayOf(2_000, 4_000, 8_000)

data class ChatResponse(
    val content: String,
    val thinkingContent: String = "",
    /** "stop" = normal completion, "length" = max_tokens truncated, "tool_calls" = function calling, null = unknown */
    val finishReason: String? = null,
    /** Structured tool calls from function calling API (empty = none) */
    val toolCalls: List<com.example.link_pi.agent.ToolCall> = emptyList()
)

class AiService(private val config: AiConfig) {

    /**
     * When set, all API calls use this model config instead of config.activeModel.
     * This enables concurrent task execution without global config races.
     */
    @Volatile
    var modelOverride: ModelConfig? = null

    /** Effective model config: override if set, otherwise active from AiConfig. */
    private val effectiveModel: ModelConfig get() = modelOverride ?: config.activeModel

    /** The active model's configured max_tokens ceiling. */
    val modelMaxTokens: Int get() = effectiveModel.maxTokens

    companion object {
        private const val MAX_REASONING_CHARS = 12_000
        private const val REASONING_PREVIEW_CHARS = 2_000
        private const val THINKING_EMIT_STEP = 160
        private const val CONTENT_EMIT_STEP = 160

        /**
         * Singleton OkHttpClient shared across ALL AiService instances.
         * This ensures connection pooling actually works and TCP connections
         * are reused, which is critical when the app is backgrounded.
         */
        val sharedClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(5, 10, TimeUnit.MINUTES))
            .socketFactory(KeepAliveSocketFactory())
            .build()

        /**
         * Streaming-specific client with extended read timeout for SSE.
         * Deep thinking models may pause for minutes between SSE events.
         * Mobile NAT/proxy can drop idle connections, so we use a very
         * long read timeout (15 min) and no call timeout.
         */
        val streamingClient: okhttp3.OkHttpClient = sharedClient.newBuilder()
            .readTimeout(900, TimeUnit.SECONDS)  // 15 min — deep thinking can be slow
            .callTimeout(0, TimeUnit.SECONDS)     // no overall call timeout for streaming
            .build()

        /** Quick check: can we reach any DNS server? Indicates network is available. */
        fun isNetworkReachable(): Boolean = try {
            InetAddress.getByName("8.8.8.8").isReachable(2000)
        } catch (_: Exception) { false }

        /** Suspend until network appears reachable or timeout (ms) elapses. */
        suspend fun waitForNetwork(timeoutMs: Long = 60_000): Boolean = withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (isNetworkReachable()) return@withContext true
                delay(2000)
            }
            false
        }
    }

    private val client get() = sharedClient

    /** Convenience method — returns content string only (for backward compat). */
    suspend fun chat(
        messages: List<Map<String, String>>,
        maxTokens: Int = effectiveModel.maxTokens,
        temperature: Double = effectiveModel.temperature,
        enableThinking: Boolean = effectiveModel.enableThinking
    ): String = chatFull(messages, maxTokens, temperature, enableThinking).content

    /** Single-prompt shorthand (e.g. for intent classification). Always disables thinking. */
    suspend fun chat(prompt: String, maxTokens: Int = 10): String {
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        return chat(messages, maxTokens = maxTokens, temperature = 0.0, enableThinking = false)
    }

    /** Full response including reasoning_content when deep thinking is enabled. */
    suspend fun chatFull(
        messages: List<Map<String, String>>,
        maxTokens: Int = effectiveModel.maxTokens,
        temperature: Double = effectiveModel.temperature,
        enableThinking: Boolean = effectiveModel.enableThinking,
        tools: JSONArray? = null
    ): ChatResponse = withContext(Dispatchers.IO) {
        val model = effectiveModel
        val endpoint = normalizeEndpoint(model.endpoint)

        val jsonMessages = buildJsonMessages(messages)

        val requestBody = JSONObject().apply {
            put("model", model.model)
            put("messages", jsonMessages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (enableThinking) {
                put("enable_thinking", true)
            }
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${model.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var lastException: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val response = client.newCall(request).execute()
                response.use { resp ->
                    val body = resp.body?.string() ?: throw IOException("Empty response")

                    if (resp.isSuccessful) {
                        val json = JSONObject(body)
                        val choice = json.getJSONArray("choices").getJSONObject(0)
                        val message = choice.getJSONObject("message")
                        val content = if (message.isNull("content")) "" else message.optString("content", "")
                        val thinking = if (message.isNull("reasoning_content")) "" else message.optString("reasoning_content", "")
                        val finishReason = choice.optString("finish_reason", "").let { if (it.isEmpty() || it == "null") null else it }
                        val apiToolCalls = if (!message.isNull("tool_calls")) {
                            com.example.link_pi.agent.ToolCall.fromApiToolCalls(message.getJSONArray("tool_calls"))
                        } else emptyList()
                        return@withContext ChatResponse(content, thinking, finishReason, apiToolCalls)
                    }

                    val code = resp.code
                    if ((code == 429 || code in 500..599) && attempt < MAX_RETRIES - 1) {
                        lastException = IOException("API $code ($endpoint): $body")
                        delay(BACKOFF_MS[attempt])
                    } else {
                        throw IOException("API $code ($endpoint): $body")
                    }
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    // Wait for network recovery instead of blind backoff
                    waitForNetwork(30_000)
                    delay(BACKOFF_MS[attempt])
                    continue
                }
                throw e
            }
        }
        throw lastException as? IOException ?: IOException("Unexpected retry exhaustion")
    }

    /**
     * Streaming chat — delivers thinking tokens in real-time via [onThinkingDelta].
     * The callback is invoked from an IO thread — must be thread-safe.
     */
    suspend fun chatStream(
        messages: List<Map<String, String>>,
        maxTokens: Int = effectiveModel.maxTokens,
        temperature: Double = effectiveModel.temperature,
        enableThinking: Boolean = effectiveModel.enableThinking,
        tools: JSONArray? = null,
        onThinkingDelta: (accumulated: String) -> Unit = {},
        onContentDelta: (accumulated: String) -> Unit = {}
    ): ChatResponse = withContext(Dispatchers.IO) {
        val model = effectiveModel
        val endpoint = normalizeEndpoint(model.endpoint)

        val jsonMessages = buildJsonMessages(messages)

        val requestBody = JSONObject().apply {
            put("model", model.model)
            put("messages", jsonMessages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", true)
            if (enableThinking) {
                put("enable_thinking", true)
            }
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${model.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var successResponse: okhttp3.Response? = null
        var lastException: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val resp = streamingClient.newCall(request).execute()
                if (resp.isSuccessful) {
                    successResponse = resp
                    break
                }
                val code = resp.code
                val body = resp.use { it.body?.string() ?: "" }
                if ((code == 429 || code in 500..599) && attempt < MAX_RETRIES - 1) {
                    lastException = IOException("API $code ($endpoint): $body")
                    delay(BACKOFF_MS[attempt])
                    continue
                }
                throw IOException("API $code ($endpoint): $body")
            } catch (e: IOException) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    waitForNetwork(30_000)
                    delay(BACKOFF_MS[attempt])
                    continue
                }
                throw e
            }
        }
        val safeResponse = successResponse
            ?: throw (lastException as? IOException ?: IOException("Unexpected retry exhaustion"))

        val thinkingBuf = StringBuilder()
        val contentBuf = StringBuilder()
        var pendingThinkingChars = 0
        var pendingContentChars = 0

        // ── Function calling: accumulate streaming tool_calls ──
        // Key: tool call index, Value: (id, name, arguments buffer)
        val toolCallAccumulator = mutableMapOf<Int, Triple<String, String, StringBuilder>>()

        val reader: BufferedReader = safeResponse.body?.byteStream()?.bufferedReader()
            ?: throw IOException("Empty stream body")

        var sseError: String? = null
        var receivedDone = false
        var lastFinishReason: String? = null

        reader.use { r ->
            var line = r.readLine()
            while (line != null) {
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") { receivedDone = true; break }
                    try {
                        val chunk = JSONObject(data)

                        // Detect API error objects returned inside SSE stream
                        if (chunk.has("error")) {
                            val errObj = chunk.optJSONObject("error")
                            sseError = errObj?.optString("message")
                                ?: chunk.optString("error", "Unknown SSE error")
                            break
                        }

                        val choice = chunk.getJSONArray("choices")
                            .getJSONObject(0)

                        // Track finish_reason from the last chunk
                        val fr = choice.optString("finish_reason", "")
                        if (fr.isNotEmpty() && fr != "null") lastFinishReason = fr

                        val delta = choice.optJSONObject("delta")

                        if (delta != null) {
                            val thinkDelta = if (delta.isNull("reasoning_content")) ""
                                else delta.optString("reasoning_content", "")
                            if (thinkDelta.isNotEmpty()) {
                                appendCapped(thinkingBuf, thinkDelta, MAX_REASONING_CHARS)
                                pendingThinkingChars += thinkDelta.length
                            }

                            val contentDelta = if (delta.isNull("content")) ""
                                else delta.optString("content", "")
                            if (contentDelta.isNotEmpty()) {
                                contentBuf.append(contentDelta)
                                pendingContentChars += contentDelta.length
                            }

                            // Accumulate streaming tool_calls deltas
                            val tcArray = delta.optJSONArray("tool_calls")
                            if (tcArray != null) {
                                for (j in 0 until tcArray.length()) {
                                    val tcDelta = tcArray.getJSONObject(j)
                                    val idx = tcDelta.optInt("index", j)
                                    val func = tcDelta.optJSONObject("function")
                                    if (func != null) {
                                        val existing = toolCallAccumulator[idx]
                                        if (existing == null) {
                                            // First chunk for this tool call — extract id and name
                                            val id = tcDelta.optString("id", "call_$idx")
                                            val name = func.optString("name", "")
                                            val argsBuf = StringBuilder(func.optString("arguments", ""))
                                            toolCallAccumulator[idx] = Triple(id, name, argsBuf)
                                        } else {
                                            // Subsequent chunk — append arguments delta
                                            existing.third.append(func.optString("arguments", ""))
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) { /* skip malformed chunks */ }
                }

                // Emit thinking preview in real-time, but cap payload to avoid UI/OOM issues.
                if (pendingThinkingChars >= THINKING_EMIT_STEP) {
                    pendingThinkingChars = 0
                    onThinkingDelta(thinkingBuf.toString().takeLast(REASONING_PREVIEW_CHARS))
                }

                // Emit accumulated content in real-time.
                if (pendingContentChars >= CONTENT_EMIT_STEP) {
                    pendingContentChars = 0
                    onContentDelta(contentBuf.toString())
                }

                line = r.readLine()
            }
        }

        // If an API error was detected in the SSE stream, throw so callers can handle/retry
        if (sseError != null) {
            throw IOException("API SSE error: $sseError")
        }

        // Stream ended without [DONE] — connection was dropped (NAT timeout, proxy reset, etc.)
        if (!receivedDone && contentBuf.isNotEmpty() && toolCallAccumulator.isEmpty()) {
            throw IOException("Stream disconnected without [DONE] (received ${contentBuf.length} chars)")
        }

        // finish_reason == "length" means max_tokens was hit — content is truncated
        // Return it with the flag so callers can handle continuation
        val truncated = lastFinishReason == "length"

        // Final emit of reasoning preview
        if (thinkingBuf.isNotEmpty() && pendingThinkingChars > 0) {
            onThinkingDelta(thinkingBuf.toString().takeLast(REASONING_PREVIEW_CHARS))
        }
        // Final emit of full content
        if (contentBuf.isNotEmpty() && pendingContentChars > 0) {
            onContentDelta(contentBuf.toString())
        }

        // Build structured tool calls from accumulated stream deltas
        val streamToolCalls = toolCallAccumulator.entries
            .sortedBy { it.key }
            .mapNotNull { (_, triple) ->
                val (id, name, argsBuf) = triple
                if (name.isBlank()) return@mapNotNull null
                try {
                    val argsJson = JSONObject(argsBuf.toString().ifBlank { "{}" })
                    val args = mutableMapOf<String, String>()
                    for (key in argsJson.keys()) {
                        args[key] = argsJson.optString(key, "")
                    }
                    com.example.link_pi.agent.ToolCall(name, args, id)
                } catch (_: Exception) {
                    // Arguments JSON may be truncated if stream was cut; still create the call
                    com.example.link_pi.agent.ToolCall(name, emptyMap(), id)
                }
            }

        ChatResponse(contentBuf.toString(), thinkingBuf.toString(), lastFinishReason, streamToolCalls)
    }

    private fun appendCapped(buffer: StringBuilder, delta: String, maxChars: Int) {
        buffer.append(delta)
        val overflow = buffer.length - maxChars
        if (overflow > 0) {
            buffer.delete(0, overflow)
        }
    }

    /**
     * Normalize endpoint URL:
     * - trim whitespace
     * - remove trailing slash
     * - if it ends with /v1, append /chat/completions
     * - if it doesn't contain /chat/completions, try to append it
     */
    /**
     * Build JSON messages array, supporting:
     * - multimodal content via `_images` key
     * - function calling: `role=tool` with `tool_call_id`, and `role=assistant` with `_tool_calls`
     */
    private fun buildJsonMessages(messages: List<Map<String, String>>): JSONArray {
        val arr = JSONArray()
        for (msg in messages) {
            val role = msg["role"] ?: "user"
            arr.put(JSONObject().apply {
                put("role", role)

                // ── tool role: function calling result ──
                if (role == "tool") {
                    put("content", msg["content"] ?: "")
                    msg["tool_call_id"]?.let { put("tool_call_id", it) }
                    msg["name"]?.let { put("name", it) }
                    return@apply
                }

                // ── assistant with tool_calls: function calling request ──
                if (role == "assistant") {
                    val toolCallsJson = msg["_tool_calls"]
                    if (toolCallsJson != null) {
                        // content can be null when only tool_calls are present
                        val content = msg["content"]
                        if (content.isNullOrBlank()) {
                            put("content", JSONObject.NULL)
                        } else {
                            put("content", content)
                        }
                        put("tool_calls", JSONArray(toolCallsJson))
                        return@apply
                    }
                }

                // ── Standard message (user / assistant / system) ──
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
