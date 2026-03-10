package com.example.link_pi.workbench

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Persists [WorkbenchTask] objects as individual JSON files.
 * Follows the same pattern as MiniAppStorage.
 */
class WorkbenchTaskStorage(private val context: Context) {

    private val tasksDir: File
        get() = File(context.filesDir, "workbench_tasks").also { it.mkdirs() }

    /* ── Write ── */

    fun save(task: WorkbenchTask) {
        val json = JSONObject().apply {
            put("id", task.id)
            put("appId", task.appId)
            put("title", task.title)
            put("description", task.description)
            put("userPrompt", task.userPrompt)
            put("status", task.status.name)
            put("progress", task.progress)
            put("currentStep", task.currentStep)
            put("modelId", task.modelId)
            put("enableThinking", task.enableThinking)
            put("createdAt", task.createdAt)
            put("updatedAt", task.updatedAt)
            put("error", task.error ?: JSONObject.NULL)
            put("fileCount", task.fileCount)
            put("totalSteps", task.totalSteps)
        }
        val target = File(tasksDir, "${sanitizeId(task.id)}.json")
        val tmp = File(tasksDir, "${sanitizeId(task.id)}.json.tmp")
        try {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) {
                // renameTo can fail on some filesystems; fall back to copy
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /* ── Read ── */

    fun loadAll(): List<WorkbenchTask> {
        return tasksDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { loadFromFile(it) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun loadById(id: String): WorkbenchTask? {
        val file = File(tasksDir, "${sanitizeId(id)}.json")
        return if (file.exists()) loadFromFile(file) else null
    }

    /* ── Delete ── */

    fun delete(id: String) {
        File(tasksDir, "${sanitizeId(id)}.json").delete()
    }

    /* ── Internal ── */

    private fun loadFromFile(file: File): WorkbenchTask? {
        return try {
            val j = JSONObject(file.readText())
            WorkbenchTask(
                id = j.getString("id"),
                appId = j.getString("appId"),
                title = j.getString("title"),
                description = j.optString("description", ""),
                userPrompt = j.optString("userPrompt", ""),
                status = try { TaskStatus.valueOf(j.getString("status")) } catch (_: Exception) { TaskStatus.QUEUED },
                progress = j.optInt("progress", 0),
                currentStep = j.optString("currentStep", ""),
                modelId = j.optString("modelId", ""),
                enableThinking = j.optBoolean("enableThinking", false),
                createdAt = j.optLong("createdAt", 0),
                updatedAt = j.optLong("updatedAt", 0),
                error = if (j.isNull("error")) null else j.optString("error"),
                fileCount = j.optInt("fileCount", 0),
                totalSteps = j.optInt("totalSteps", 0)
            )
        } catch (e: Exception) {
            Log.w("WorkbenchTaskStorage", "Failed to load ${file.name}", e)
            null
        }
    }

    private fun sanitizeId(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9\\-_]"), "")
}
