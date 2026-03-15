package com.example.link_pi.data

import android.content.Context
import com.example.link_pi.data.model.ManagedSession
import com.example.link_pi.data.model.SessionSource
import com.example.link_pi.data.model.SessionStatus
import com.example.link_pi.data.model.SessionType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionStorage(private val context: Context) {

    private val sessionsDir: File
        get() = File(context.filesDir, "sessions").also { it.mkdirs() }

    private fun safeId(id: String): String = id.replace(Regex("[^a-zA-Z0-9_\\-]"), "")

    fun save(session: ManagedSession) {
        val json = JSONObject().apply {
            put("id", session.id)
            put("type", session.type.name)
            put("label", session.label)
            put("source", session.source.name)
            put("skillId", session.skillId ?: JSONObject.NULL)
            put("injectedSkillIds", JSONArray(session.injectedSkillIds))
            put("enabledToolGroups", JSONArray(session.enabledToolGroups))
            put("modelId", session.modelId)
            put("status", session.status.name)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
            put("messageCount", session.messageCount)
            put("metadata", JSONObject(session.metadata))
        }
        File(sessionsDir, "${safeId(session.id)}.json").writeText(json.toString())
    }

    fun loadAll(): List<ManagedSession> {
        return sessionsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { loadFromFile(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun loadById(id: String): ManagedSession? {
        val file = File(sessionsDir, "${safeId(id)}.json")
        return if (file.exists()) loadFromFile(file) else null
    }

    fun loadByType(type: SessionType): List<ManagedSession> {
        return loadAll().filter { it.type == type }
    }

    fun delete(id: String) {
        File(sessionsDir, "${safeId(id)}.json").delete()
    }

    fun updateStatus(id: String, status: SessionStatus) {
        val session = loadById(id) ?: return
        save(session.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    fun cleanup(maxAge: Long) {
        val cutoff = System.currentTimeMillis() - maxAge
        sessionsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.forEach { file ->
                val session = loadFromFile(file)
                if (session != null && session.status == SessionStatus.ENDED && session.updatedAt < cutoff) {
                    file.delete()
                }
            }
    }

    private fun loadFromFile(file: File): ManagedSession? {
        return try {
            val json = JSONObject(file.readText())
            val type = SessionType.entries.find { it.name == json.getString("type") } ?: return null
            val source = SessionSource.entries.find { it.name == json.getString("source") } ?: return null
            val status = SessionStatus.entries.find { it.name == json.optString("status", "ACTIVE") }
                ?: SessionStatus.ACTIVE
            ManagedSession(
                id = json.getString("id"),
                type = type,
                label = json.getString("label"),
                source = source,
                skillId = json.optString("skillId").takeIf { it != "null" && it.isNotBlank() },
                injectedSkillIds = json.optJSONArray("injectedSkillIds")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                enabledToolGroups = json.optJSONArray("enabledToolGroups")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                modelId = json.getString("modelId"),
                status = status,
                createdAt = json.optLong("createdAt", 0),
                updatedAt = json.optLong("updatedAt", 0),
                messageCount = json.optInt("messageCount", 0),
                metadata = json.optJSONObject("metadata")?.let { obj ->
                    obj.keys().asSequence().associateWith { obj.getString(it) }
                } ?: emptyMap()
            )
        } catch (_: Exception) {
            null
        }
    }
}
