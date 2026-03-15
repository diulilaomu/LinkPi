package com.example.link_pi.data

import android.content.Context
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.agent.StepType
import com.example.link_pi.data.model.Attachment
import com.example.link_pi.data.model.ChatMessage
import com.example.link_pi.data.model.Conversation
import com.example.link_pi.data.model.MiniApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话离线存储——每个会话一个子目录，包含 meta.json + messages.json。
 *
 * 目录结构：
 *   conversations/
 *     {conv-id}/
 *       meta.json       → Conversation 元数据
 *       messages.json    → 消息列表（不含 base64 图片附件，避免磁盘爆炸）
 */
class ConversationStorage(private val context: Context) {

    private val rootDir: File
        get() = File(context.filesDir, "conversations").also { it.mkdirs() }

    /** 净化 ID，防止路径遍历 */
    private fun safeId(id: String): String = id.replace(Regex("[^a-zA-Z0-9_\\-]"), "")

    // ── 会话元数据 ──

    fun saveConversation(conv: Conversation) {
        val dir = File(rootDir, safeId(conv.id)).also { it.mkdirs() }
        val json = JSONObject().apply {
            put("id", conv.id)
            put("title", conv.title)
            put("createdAt", conv.createdAt)
            put("updatedAt", conv.updatedAt)
        }
        atomicWrite(File(dir, "meta.json"), json.toString())
    }

    fun loadAllConversations(): List<Conversation> {
        return rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { loadConversationMeta(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun deleteConversation(id: String) {
        File(rootDir, safeId(id)).deleteRecursively()
    }

    // ── 消息持久化 ──

    fun saveMessages(conversationId: String, messages: List<ChatMessage>) {
        val dir = File(rootDir, safeId(conversationId)).also { it.mkdirs() }
        val arr = JSONArray()
        for (msg in messages) {
            arr.put(messageToJson(msg))
        }
        atomicWrite(File(dir, "messages.json"), arr.toString())
    }

    /** Write to temp file then rename — survives process kill mid-write. */
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo may fail on some filesystems; fallback to copy + delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun loadMessages(conversationId: String): List<ChatMessage> {
        val file = File(rootDir, "${safeId(conversationId)}/messages.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                try { jsonToMessage(arr.getJSONObject(i)) } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── JSON 序列化 ──

    private fun messageToJson(msg: ChatMessage): JSONObject = JSONObject().apply {
        put("id", msg.id)
        put("role", msg.role)
        put("content", msg.content)
        put("timestamp", msg.timestamp)
        put("thinkingContent", msg.thinkingContent)
        // MiniApp — 只存引用 id + name，不存完整 htmlContent
        msg.miniApp?.let { app ->
            put("miniApp", JSONObject().apply {
                put("id", app.id)
                put("name", app.name)
                put("description", app.description)
                put("isWorkspaceApp", app.isWorkspaceApp)
                put("entryFile", app.entryFile)
            })
        }
        // AgentSteps — 精简存储
        if (msg.agentSteps.isNotEmpty()) {
            put("agentSteps", JSONArray().apply {
                msg.agentSteps.forEach { step ->
                    put(JSONObject().apply {
                        put("type", step.type.name)
                        put("description", step.description)
                        put("detail", step.detail)
                    })
                }
            })
        }
        // Attachments — 存文本附件，跳过 base64 图片（太大）
        if (msg.attachments.isNotEmpty()) {
            put("attachments", JSONArray().apply {
                msg.attachments.forEach { att ->
                    put(JSONObject().apply {
                        put("name", att.name)
                        put("mimeType", att.mimeType)
                        if (att.textContent != null) put("textContent", att.textContent)
                        // base64Data 不持久化
                    })
                }
            })
        }
    }

    private fun jsonToMessage(json: JSONObject): ChatMessage {
        val miniApp = json.optJSONObject("miniApp")?.let { app ->
            MiniApp(
                id = app.getString("id"),
                name = app.getString("name"),
                description = app.optString("description", ""),
                htmlContent = "",  // 不存 htmlContent，运行时从 MiniAppStorage 加载
                isWorkspaceApp = app.optBoolean("isWorkspaceApp", false),
                entryFile = app.optString("entryFile", "index.html")
            )
        }
        val agentSteps = json.optJSONArray("agentSteps")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val s = arr.getJSONObject(i)
                    AgentStep(
                        type = StepType.valueOf(s.getString("type")),
                        description = s.getString("description"),
                        detail = s.optString("detail", "")
                    )
                } catch (_: Exception) { null }
            }
        } ?: emptyList()
        val attachments = json.optJSONArray("attachments")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val a = arr.getJSONObject(i)
                    Attachment(
                        name = a.getString("name"),
                        mimeType = a.getString("mimeType"),
                        textContent = if (a.has("textContent")) a.getString("textContent") else null
                    )
                } catch (_: Exception) { null }
            }
        } ?: emptyList()

        return ChatMessage(
            id = json.getString("id"),
            role = json.getString("role"),
            content = json.getString("content"),
            timestamp = json.optLong("timestamp", 0),
            miniApp = miniApp,
            agentSteps = agentSteps,
            thinkingContent = json.optString("thinkingContent", ""),
            attachments = attachments
        )
    }

    private fun loadConversationMeta(dir: File): Conversation? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val json = JSONObject(metaFile.readText())
            Conversation(
                id = json.getString("id"),
                title = json.optString("title", "新对话"),
                createdAt = json.optLong("createdAt", 0),
                updatedAt = json.optLong("updatedAt", 0)
            )
        } catch (_: Exception) {
            null
        }
    }
}
