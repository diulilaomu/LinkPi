package com.example.link_pi.data.session

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Persists AppSession data to SharedPreferences as JSON.
 * Each appId maps to one JSON record.
 */
class AppSessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_sessions", Context.MODE_PRIVATE)

    fun save(session: AppSession) {
        val json = JSONObject().apply {
            put("appId", session.appId)
            put("appType", session.appType.name)
            put("lastActiveAt", session.lastActiveAt)
            put("state", JSONObject(session.state))
        }
        prefs.edit().putString(session.appId, json.toString()).apply()
    }

    fun load(appId: String): AppSession? {
        val raw = prefs.getString(appId, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val stateJson = json.optJSONObject("state") ?: JSONObject()
            val state = mutableMapOf<String, String>()
            stateJson.keys().forEach { key -> state[key] = stateJson.getString(key) }
            AppSession(
                appId = json.getString("appId"),
                appType = AppType.valueOf(json.getString("appType")),
                lastActiveAt = json.getLong("lastActiveAt"),
                state = state
            )
        } catch (_: Exception) {
            null
        }
    }

    fun delete(appId: String) {
        prefs.edit().remove(appId).apply()
    }

    fun loadAll(): List<AppSession> {
        return prefs.all.values.mapNotNull { value ->
            try {
                val json = JSONObject(value as String)
                val stateJson = json.optJSONObject("state") ?: JSONObject()
                val state = mutableMapOf<String, String>()
                stateJson.keys().forEach { key -> state[key] = stateJson.getString(key) }
                AppSession(
                    appId = json.getString("appId"),
                    appType = AppType.valueOf(json.getString("appType")),
                    lastActiveAt = json.getLong("lastActiveAt"),
                    state = state
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun deleteExpired(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        loadAll().filter { now - it.lastActiveAt > maxAgeMs }.forEach {
            editor.remove(it.appId)
        }
        editor.apply()
    }
}
