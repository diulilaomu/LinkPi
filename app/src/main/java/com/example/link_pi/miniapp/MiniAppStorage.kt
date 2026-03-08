package com.example.link_pi.miniapp

import android.content.Context
import com.example.link_pi.data.model.MiniApp
import org.json.JSONObject
import java.io.File

class MiniAppStorage(private val context: Context) {

    private val appsDir: File
        get() = File(context.filesDir, "miniapps").also { it.mkdirs() }

    fun save(app: MiniApp) {
        val json = JSONObject().apply {
            put("id", app.id)
            put("name", app.name)
            put("description", app.description)
            put("htmlContent", app.htmlContent)
            put("createdAt", app.createdAt)
            put("isWorkspaceApp", app.isWorkspaceApp)
            put("entryFile", app.entryFile)
        }
        File(appsDir, "${app.id}.json").writeText(json.toString())
    }

    fun loadAll(): List<MiniApp> {
        return appsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { loadFromFile(it) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun loadById(id: String): MiniApp? {
        val safeId = id.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        val file = File(appsDir, "$safeId.json")
        return if (file.exists()) loadFromFile(file) else null
    }

    fun delete(id: String) {
        val safeId = id.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        File(appsDir, "$safeId.json").delete()
    }

    private fun loadFromFile(file: File): MiniApp? {
        return try {
            val json = JSONObject(file.readText())
            MiniApp(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.optString("description", ""),
                htmlContent = json.optString("htmlContent", ""),
                createdAt = json.optLong("createdAt", 0),
                isWorkspaceApp = json.optBoolean("isWorkspaceApp", false),
                entryFile = json.optString("entryFile", "index.html")
            )
        } catch (_: Exception) {
            null
        }
    }
}
