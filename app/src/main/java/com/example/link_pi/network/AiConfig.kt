package com.example.link_pi.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AiConfig(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ai_config_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Fallback to plain prefs if device doesn't support crypto (shouldn't happen on API 28+)
        context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)
    }

    init {
        // One-time migration: move plaintext data to encrypted storage
        migrateFromPlainPrefs(context)
    }

    var apiEndpoint: String
        get() = prefs.getString("api_endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        set(value) = prefs.edit().putString("api_endpoint", value).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    var modelName: String
        get() = prefs.getString("model_name", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model_name", value).apply()

    /** Migrate existing plaintext prefs to encrypted storage (runs once). */
    private fun migrateFromPlainPrefs(context: Context) {
        val oldPrefs = context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)
        val oldKey = oldPrefs.getString("api_key", null)
        if (oldKey != null && oldKey.isNotBlank() && apiKey.isBlank()) {
            apiEndpoint = oldPrefs.getString("api_endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
            apiKey = oldKey
            modelName = oldPrefs.getString("model_name", DEFAULT_MODEL) ?: DEFAULT_MODEL
            // Clear old plaintext storage
            oldPrefs.edit().clear().apply()
        }
    }

    val isConfigured: Boolean
        get() = apiEndpoint.isNotBlank() && apiKey.isNotBlank()

    fun resetToDefault() {
        prefs.edit()
            .putString("api_endpoint", DEFAULT_ENDPOINT)
            .putString("model_name", DEFAULT_MODEL)
            .apply()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        const val DEFAULT_MODEL = "qwen-max"

        /** Presets for quick configuration */
        val PRESETS = listOf(
            Preset(
                name = "阿里百炼 (Qwen-Max)",
                endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                model = "qwen-max"
            ),
            Preset(
                name = "阿里百炼 (Qwen-Plus)",
                endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                model = "qwen-plus"
            ),
            Preset(
                name = "阿里百炼 (Qwen-Turbo)",
                endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                model = "qwen-turbo"
            ),
            Preset(
                name = "阿里百炼 (Qwen-Long)",
                endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                model = "qwen-long"
            ),
            Preset(
                name = "OpenAI (GPT-4)",
                endpoint = "https://api.openai.com/v1/chat/completions",
                model = "gpt-4"
            ),
            Preset(
                name = "DeepSeek",
                endpoint = "https://api.deepseek.com/v1/chat/completions",
                model = "deepseek-chat"
            )
        )
    }

    data class Preset(
        val name: String,
        val endpoint: String,
        val model: String
    )
}
