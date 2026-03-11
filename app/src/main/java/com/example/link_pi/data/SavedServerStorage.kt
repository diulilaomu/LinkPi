package com.example.link_pi.data

import android.content.Context
import android.content.SharedPreferences
import com.example.link_pi.util.createEncryptedPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已保存服务器列表的持久化存储。
 */
data class SavedServer(
    val id: String = java.util.UUID.randomUUID().toString().take(12),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val credentialId: String = "",   // 关联的凭据 ID
    val credentialName: String = "", // 凭据名称（显示用）
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

class SavedServerStorage(context: Context) {

    private val prefs: SharedPreferences

    init {
        val (p, _) = createEncryptedPrefs(context, "saved_servers")
        prefs = p
    }

    fun loadAll(): List<SavedServer> {
        val json = prefs.getString("items", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
                .sortedByDescending { it.updatedAt }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun save(server: SavedServer) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        val updated = server.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) list[idx] = updated else list.add(updated)
        persist(list)
    }

    @Synchronized
    fun delete(id: String) {
        persist(loadAll().filter { it.id != id })
    }

    fun findById(id: String): SavedServer? = loadAll().find { it.id == id }

    private fun persist(list: List<SavedServer>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun toJson(s: SavedServer) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("host", s.host)
        put("port", s.port)
        put("credentialId", s.credentialId)
        put("credentialName", s.credentialName)
        put("createdAt", s.createdAt)
        put("updatedAt", s.updatedAt)
    }

    private fun parse(o: JSONObject) = SavedServer(
        id = o.optString("id"),
        name = o.optString("name"),
        host = o.optString("host"),
        port = o.optInt("port", 22),
        credentialId = o.optString("credentialId"),
        credentialName = o.optString("credentialName"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )
}
