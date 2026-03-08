package com.example.link_pi.skill

import android.content.Context
import com.example.link_pi.data.model.Skill
import com.example.link_pi.data.model.SkillMode
import org.json.JSONObject
import java.io.File

class SkillStorage(private val context: Context) {

    private val skillsDir: File
        get() = File(context.filesDir, "skills").also { it.mkdirs() }

    fun save(skill: Skill) {
        val json = JSONObject().apply {
            put("id", skill.id)
            put("name", skill.name)
            put("icon", skill.icon)
            put("description", skill.description)
            put("systemPrompt", skill.systemPrompt)
            put("mode", skill.mode.name)
            put("isBuiltIn", skill.isBuiltIn)
            put("createdAt", skill.createdAt)
        }
        File(skillsDir, "${skill.id}.json").writeText(json.toString())
    }

    fun loadAll(): List<Skill> {
        return skillsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { loadFromFile(it) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun loadById(id: String): Skill? {
        val safeId = id.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
        val file = File(skillsDir, "$safeId.json")
        return if (file.exists()) loadFromFile(file) else null
    }

    fun delete(id: String) {
        val safeId = id.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
        File(skillsDir, "$safeId.json").delete()
    }

    private fun loadFromFile(file: File): Skill? {
        return try {
            val json = JSONObject(file.readText())
            Skill(
                id = json.getString("id"),
                name = json.getString("name"),
                icon = json.optString("icon", "🔧"),
                description = json.optString("description", ""),
                systemPrompt = json.getString("systemPrompt"),
                mode = SkillMode.fromString(json.optString("mode", "CODING")),
                isBuiltIn = json.optBoolean("isBuiltIn", false),
                createdAt = json.optLong("createdAt", 0)
            )
        } catch (_: Exception) {
            null
        }
    }
}
