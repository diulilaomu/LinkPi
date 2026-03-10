package com.example.link_pi.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * 凭据（Credential）加密存储。
 *
 * 使用 EncryptedSharedPreferences（AES-256-GCM）存放敏感字段。
 * 每条凭据包含：名称、服务地址、用户名/账号名、密钥/Token、备注。
 */
class CredentialStorage(context: Context) {

    val isUsingPlainStorage: Boolean

    private val prefs: SharedPreferences

    init {
        var plain = false
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "credentials_encrypted",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("CredentialStorage", "Encrypted storage unavailable", e)
            plain = true
            context.getSharedPreferences("credentials_fallback", Context.MODE_PRIVATE)
        }
        isUsingPlainStorage = plain
    }

    // ── CRUD ──

    fun loadAll(): List<Credential> {
        val json = prefs.getString("items", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { parseCredential(arr.getJSONObject(it)) }
                .sortedByDescending { it.updatedAt }
        } catch (_: Exception) { emptyList() }
    }

    fun save(credential: Credential) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == credential.id }
        val updated = credential.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) list[idx] = updated else list.add(updated)
        persist(list)
    }

    fun delete(id: String) {
        persist(loadAll().filter { it.id != id })
    }

    fun findById(id: String): Credential? = loadAll().find { it.id == id }

    fun findByName(name: String): Credential? =
        loadAll().find { it.name.equals(name, ignoreCase = true) }

    // ── Serialisation ──

    private fun persist(list: List<Credential>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun toJson(c: Credential) = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("service", c.service)
        put("username", c.username)
        put("secret", c.secret)
        put("note", c.note)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
    }

    private fun parseCredential(o: JSONObject) = Credential(
        id = o.optString("id"),
        name = o.optString("name"),
        service = o.optString("service"),
        username = o.optString("username"),
        secret = o.optString("secret"),
        note = o.optString("note"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )
}

data class Credential(
    val id: String = java.util.UUID.randomUUID().toString().take(12),
    val name: String = "",
    val service: String = "",
    val username: String = "",
    val secret: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
