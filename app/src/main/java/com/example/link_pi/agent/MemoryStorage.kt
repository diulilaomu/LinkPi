package com.example.link_pi.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Long-term memory storage for the AI agent.
 * Stores memories as JSON files. Supports save, search, list, and delete.
 * NOT injected into chat context — the AI actively queries when needed.
 */
class MemoryStorage(context: Context) {

    private val memoryDir: File =
        File(context.filesDir, "agent_memory").also { it.mkdirs() }

    data class Memory(
        val id: String,
        val content: String,
        val tags: List<String>,
        val createdAt: Long,
        val updatedAt: Long
    )

    @Synchronized
    fun save(content: String, tags: List<String>): Memory {
        val now = System.currentTimeMillis()
        val memory = Memory(
            id = UUID.randomUUID().toString().take(12),
            content = content.trim(),
            tags = tags.map { it.trim().lowercase() },
            createdAt = now,
            updatedAt = now
        )
        writeMemory(memory)
        return memory
    }

    @Synchronized
    fun update(id: String, content: String?, tags: List<String>?): Memory? {
        val existing = loadById(id) ?: return null
        val updated = existing.copy(
            content = content?.trim() ?: existing.content,
            tags = tags?.map { it.trim().lowercase() } ?: existing.tags,
            updatedAt = System.currentTimeMillis()
        )
        writeMemory(updated)
        return updated
    }

    /**
     * Search memories by keyword (content + tags).
     * Returns matches sorted by relevance (tag match > content match, then by recency).
     */
    fun search(query: String, limit: Int = 10): List<Memory> {
        val keywords = query.lowercase().split(Regex("[\\s,;，；]+")).filter { it.isNotBlank() }
        if (keywords.isEmpty()) return listAll().take(limit)

        return listAll().map { memory ->
            val contentLower = memory.content.lowercase()
            val tagSet = memory.tags.toSet()
            // Score: tag exact match = 3, content keyword match = 1
            var score = 0
            for (kw in keywords) {
                if (tagSet.any { it.contains(kw) || kw.contains(it) }) score += 3
                if (contentLower.contains(kw)) score += 1
            }
            memory to score
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }
                .thenByDescending { it.first.updatedAt })
            .take(limit)
            .map { it.first }
    }

    fun listAll(): List<Memory> {
        return memoryDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { loadFromFile(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val safeId = id.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        return File(memoryDir, "$safeId.json").delete()
    }

    fun count(): Int = memoryDir.listFiles()?.count { it.extension == "json" } ?: 0

    private fun loadById(id: String): Memory? {
        val safeId = id.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        val file = File(memoryDir, "$safeId.json")
        return if (file.exists()) loadFromFile(file) else null
    }

    private fun writeMemory(memory: Memory) {
        val json = JSONObject().apply {
            put("id", memory.id)
            put("content", memory.content)
            put("tags", JSONArray(memory.tags))
            put("createdAt", memory.createdAt)
            put("updatedAt", memory.updatedAt)
        }
        File(memoryDir, "${memory.id}.json").writeText(json.toString())
    }

    private fun loadFromFile(file: File): Memory? {
        return try {
            val json = JSONObject(file.readText())
            val tagsArr = json.optJSONArray("tags")
            val tags = mutableListOf<String>()
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) tags.add(tagsArr.getString(i))
            }
            Memory(
                id = json.getString("id"),
                content = json.getString("content"),
                tags = tags,
                createdAt = json.optLong("createdAt", 0),
                updatedAt = json.optLong("updatedAt", json.optLong("createdAt", 0))
            )
        } catch (_: Exception) {
            null
        }
    }
}
