package com.example.link_pi.network

import android.content.Context
import android.content.SharedPreferences
import com.example.link_pi.util.createEncryptedPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Multi-model configuration manager.
 *
 * Stores a list of [ModelConfig] items and tracks which one is active.
 * Backward-compatible: migrates legacy single-model config on first run.
 */
class AiConfig(context: Context) {
    /** True if encrypted storage failed and plain SharedPreferences is used. */
    val isUsingPlainStorage: Boolean

    private val prefs: SharedPreferences

    init {
        val (p, plain) = createEncryptedPrefs(context, "ai_config")
        prefs = p
        isUsingPlainStorage = plain
        migrateFromPlainPrefs(context)
        migrateFromLegacySingle()
    }

    /* ──────────── active model shortcut (read-only, delegates to active ModelConfig) ──────────── */

    val apiEndpoint: String get() = activeModel.endpoint
    val apiKey: String get() = activeModel.apiKey
    val modelName: String get() = activeModel.model
    val isConfigured: Boolean get() = apiEndpoint.isNotBlank() && apiKey.isNotBlank()

    /* ──────────── model list ──────────── */

    private var _models: MutableList<ModelConfig>? = null

    fun getModels(): List<ModelConfig> {
        if (_models == null) _models = loadModels().toMutableList()
        return _models!!.toList()
    }

    /** Clear in-memory cache and reload from SharedPreferences. */
    fun reloadModels() {
        _models = null
    }

    private fun mutModels(): MutableList<ModelConfig> {
        if (_models == null) _models = loadModels().toMutableList()
        return _models!!
    }

    var activeModelId: String
        get() = prefs.getString("active_model_id", "") ?: ""
        set(value) { prefs.edit().putString("active_model_id", value).apply() }

    val activeModel: ModelConfig
        get() {
            val list = getModels()
            return list.firstOrNull { it.id == activeModelId } ?: list.firstOrNull() ?: ModelConfig()
        }

    fun addModel(model: ModelConfig) {
        mutModels().add(model)
        saveModels()
        if (activeModelId.isBlank()) activeModelId = model.id
    }

    fun updateModel(model: ModelConfig) {
        val list = mutModels()
        val idx = list.indexOfFirst { it.id == model.id }
        if (idx >= 0) { list[idx] = model; saveModels() }
    }

    fun deleteModel(id: String) {
        val list = mutModels()
        list.removeAll { it.id == id }
        saveModels()
        if (activeModelId == id) {
            activeModelId = list.firstOrNull()?.id ?: ""
        }
    }

    fun setActive(id: String) {
        activeModelId = id
    }

    /* ──────────── persistence ──────────── */

    private fun loadModels(): List<ModelConfig> {
        val raw = prefs.getString("models_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { ModelConfig.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveModels() {
        val arr = JSONArray()
        for (m in mutModels()) arr.put(m.toJson())
        prefs.edit().putString("models_json", arr.toString()).apply()
    }

    /* ──────────── migration ──────────── */

    /** One-time: plaintext → encrypted */
    private fun migrateFromPlainPrefs(context: Context) {
        if (isUsingPlainStorage) return  // Both are the same prefs; no migration needed
        val oldPrefs = context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)
        val oldKey = oldPrefs.getString("api_key", null)
        if (oldKey != null && oldKey.isNotBlank()) {
            val ep = oldPrefs.getString("api_endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
            val mn = oldPrefs.getString("model_name", DEFAULT_MODEL) ?: DEFAULT_MODEL
            // Write into encrypted prefs first, then clear old prefs
            val success = prefs.edit()
                .putString("api_endpoint", ep)
                .putString("api_key", oldKey)
                .putString("model_name", mn)
                .commit()
            if (success) {
                oldPrefs.edit().clear().apply()
            }
        }
    }

    /** One-time: legacy single fields → models_json */
    private fun migrateFromLegacySingle() {
        if (prefs.getString("models_json", null) != null) return
        val key = prefs.getString("api_key", null)
        if (key != null && key.isNotBlank()) {
            val ep = prefs.getString("api_endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
            val mn = prefs.getString("model_name", DEFAULT_MODEL) ?: DEFAULT_MODEL
            val model = ModelConfig(
                name = mn, endpoint = ep, apiKey = key, model = mn
            )
            _models = mutableListOf(model)
            saveModels()
            activeModelId = model.id
        }
    }

    /* ──────────── presets & defaults ──────────── */

    companion object {
        const val DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        const val DEFAULT_MODEL = "qwen-max"

        val PRESETS = listOf(
            Preset("阿里百炼 (Qwen-Max)",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-max"),
            Preset("阿里百炼 (Qwen-Plus)",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus"),
            Preset("阿里百炼 (Qwen-Turbo)",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-turbo"),
            Preset("阿里百炼 (Qwen-Long)",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-long"),
            Preset("OpenAI (GPT-4o)",
                "https://api.openai.com/v1/chat/completions", "gpt-4o"),
            Preset("DeepSeek (Chat)",
                "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
            Preset("DeepSeek (R1)",
                "https://api.deepseek.com/v1/chat/completions", "deepseek-reasoner"),
            Preset("Claude (3.5 Sonnet)",
                "https://api.anthropic.com/v1/chat/completions", "claude-3-5-sonnet-20241022"),
        )
    }

    data class Preset(val name: String, val endpoint: String, val model: String)
}

/* ──────────── per-model config ──────────── */

data class ModelConfig(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val name: String = "",
    val endpoint: String = AiConfig.DEFAULT_ENDPOINT,
    val apiKey: String = "",
    val model: String = AiConfig.DEFAULT_MODEL,
    val maxTokens: Int = 65536,
    val temperature: Double = 0.7,
    val enableThinking: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("endpoint", endpoint)
        put("apiKey", apiKey)
        put("model", model)
        put("maxTokens", maxTokens)
        put("temperature", temperature)
        put("enableThinking", enableThinking)
    }

    companion object {
        fun fromJson(j: JSONObject) = ModelConfig(
            id = j.optString("id", java.util.UUID.randomUUID().toString().take(8)),
            name = j.optString("name", ""),
            endpoint = j.optString("endpoint", AiConfig.DEFAULT_ENDPOINT),
            apiKey = j.optString("apiKey", ""),
            model = j.optString("model", AiConfig.DEFAULT_MODEL),
            maxTokens = j.optInt("maxTokens", 65536),
            temperature = j.optDouble("temperature", 0.7),
            enableThinking = j.optBoolean("enableThinking", false),
        )
    }
}
