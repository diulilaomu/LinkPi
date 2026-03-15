package com.example.link_pi.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Module storage — AI-created Python service packages.
 *
 * Each module is a Python script that can be started as a local HTTP/TCP/UDP server.
 * Package format: modules/{id}/module.json + scripts/ + README.md
 */
class ModuleStorage(context: Context) {

    val moduleDir: File =
        File(context.filesDir, "modules").also { it.mkdirs() }

    var pythonRunner: PythonRunner? = null

    // ── Data Model ──

    data class Module(
        val id: String,
        val name: String,
        val description: String,
        val serviceType: String = "HTTP",     // HTTP, TCP, UDP
        val defaultPort: Int = 0,             // 0 = auto-assign
        val mainScript: String = "server.py", // entry point in scripts/
        val instructions: String = "",
        val version: Int = 3,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    )

    // ── CRUD ──

    @Synchronized
    fun save(module: Module): Module {
        val m = module.copy(updatedAt = System.currentTimeMillis())
        val pkgDir = File(moduleDir, m.id).also { it.mkdirs() }
        File(pkgDir, "module.json").writeText(moduleToJson(m).toString(2))
        return m
    }

    fun create(
        name: String,
        description: String,
        serviceType: String = "HTTP",
        defaultPort: Int = 0,
        mainScript: String = "server.py",
        instructions: String = ""
    ): Module {
        val module = Module(
            id = UUID.randomUUID().toString().take(12),
            name = name.trim(),
            description = description.trim(),
            serviceType = serviceType.uppercase(),
            defaultPort = defaultPort,
            mainScript = mainScript.trim().ifBlank { "server.py" },
            instructions = instructions.trim()
        )
        return save(module)
    }

    fun loadById(id: String): Module? {
        val safeId = id.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        val pkgDir = File(moduleDir, safeId)
        val manifestFile = File(pkgDir, "module.json")
        if (manifestFile.exists()) return loadFromManifest(manifestFile)
        return null
    }

    fun findByName(name: String): Module? {
        val all = loadAll()
        return all.find { it.name.equals(name, ignoreCase = true) }
            ?: all.find { it.name.contains(name, ignoreCase = true) }
    }

    fun loadAll(): List<Module> {
        return moduleDir.listFiles()
            ?.filter { it.isDirectory && File(it, "module.json").exists() }
            ?.mapNotNull { loadFromManifest(File(it, "module.json")) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val safeId = id.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        val pkgDir = File(moduleDir, safeId)
        return if (pkgDir.isDirectory) pkgDir.deleteRecursively() else false
    }

    fun updateModule(
        id: String,
        name: String? = null,
        description: String? = null,
        defaultPort: Int? = null,
        mainScript: String? = null,
        instructions: String? = null
    ): Module? {
        val module = loadById(id) ?: return null
        val updated = module.copy(
            name = name?.trim() ?: module.name,
            description = description?.trim() ?: module.description,
            defaultPort = defaultPort ?: module.defaultPort,
            mainScript = mainScript?.trim() ?: module.mainScript,
            instructions = instructions?.trim() ?: module.instructions
        )
        return save(updated)
    }

    // ── Script file operations ──

    fun getScriptsDir(moduleId: String): File? {
        val safeId = moduleId.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        val pkgDir = File(moduleDir, safeId)
        if (!pkgDir.isDirectory) return null
        return File(pkgDir, "scripts").also { it.mkdirs() }
    }

    fun writeScript(moduleId: String, fileName: String, content: String): Boolean {
        val scriptsDir = getScriptsDir(moduleId) ?: return false
        val safeName = fileName.replace("..", "").replace("/", "").replace("\\", "")
        if (!safeName.endsWith(".py")) return false
        val file = File(scriptsDir, safeName)
        if (!file.canonicalPath.startsWith(scriptsDir.canonicalPath)) return false
        file.writeText(content)
        return true
    }

    fun readScript(moduleId: String, fileName: String): String? {
        val scriptsDir = getScriptsDir(moduleId) ?: return null
        val safeName = fileName.replace("..", "").replace("/", "").replace("\\", "")
        val file = File(scriptsDir, safeName)
        if (!file.exists() || !file.canonicalPath.startsWith(scriptsDir.canonicalPath)) return null
        return file.readText()
    }

    fun listScripts(moduleId: String): List<String> {
        val scriptsDir = getScriptsDir(moduleId) ?: return emptyList()
        return scriptsDir.listFiles()
            ?.filter { it.isFile && it.extension == "py" }
            ?.map { it.name }
            ?: emptyList()
    }

    fun getReadme(moduleId: String): String? {
        val safeId = moduleId.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        val file = File(File(moduleDir, safeId), "README.md")
        return if (file.exists()) file.readText() else null
    }

    fun writeReadme(moduleId: String, content: String): Boolean {
        val safeId = moduleId.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        val pkgDir = File(moduleDir, safeId)
        if (!pkgDir.isDirectory) return false
        File(pkgDir, "README.md").writeText(content)
        return true
    }

    // ── Serialization ──

    private fun moduleToJson(m: Module): JSONObject {
        return JSONObject().apply {
            put("version", 3)
            put("id", m.id)
            put("name", m.name)
            put("description", m.description)
            put("serviceType", m.serviceType)
            put("defaultPort", m.defaultPort)
            put("mainScript", m.mainScript)
            put("instructions", m.instructions)
            put("createdAt", m.createdAt)
            put("updatedAt", m.updatedAt)
        }
    }

    fun exportToJson(module: Module): String {
        val json = moduleToJson(module)
        val scripts = listScripts(module.id)
        if (scripts.isNotEmpty()) {
            json.put("scriptFiles", JSONObject().apply {
                scripts.forEach { name ->
                    readScript(module.id, name)?.let { put(name, it) }
                }
            })
        }
        getReadme(module.id)?.let { json.put("readme", it) }
        return json.toString(2)
    }

    fun importFromJson(jsonStr: String): Module? {
        return try {
            val json = JSONObject(jsonStr)
            val module = parseModuleJson(json).copy(
                id = UUID.randomUUID().toString().take(12)
            )
            val saved = save(module)
            json.optJSONObject("scriptFiles")?.let { scripts ->
                scripts.keys().forEach { name ->
                    writeScript(saved.id, name, scripts.getString(name))
                }
            }
            json.optString("readme", null)?.let { writeReadme(saved.id, it) }
            saved
        } catch (_: Exception) {
            null
        }
    }

    private fun parseModuleJson(json: JSONObject): Module {
        return Module(
            id = json.getString("id"),
            name = json.getString("name"),
            description = json.optString("description", ""),
            serviceType = json.optString("serviceType", "HTTP"),
            defaultPort = json.optInt("defaultPort", 0),
            mainScript = json.optString("mainScript", "server.py"),
            instructions = json.optString("instructions", ""),
            version = json.optInt("version", 3),
            createdAt = json.optLong("createdAt", 0),
            updatedAt = json.optLong("updatedAt", json.optLong("createdAt", 0))
        )
    }

    private fun loadFromManifest(manifestFile: File): Module? {
        return try {
            parseModuleJson(JSONObject(manifestFile.readText()))
        } catch (_: Exception) { null }
    }
}
