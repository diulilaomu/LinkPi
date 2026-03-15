package com.example.link_pi.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Collects JS runtime errors AND console output from WebView for AI to consume
 * via get_runtime_errors tool. Thread-safe singleton, entries are per-appId.
 *
 * Errors are also persisted to disk so they survive process death.
 * Disk writes are debounced to avoid main-thread I/O jank.
 */
object RuntimeErrorCollector {
    private val errors = mutableMapOf<String, MutableList<String>>()
    private val consoleLogs = mutableMapOf<String, MutableList<String>>()
    private const val MAX_ERRORS_PER_APP = 50
    private const val MAX_LOGS_PER_APP = 100
    private const val SAVE_DEBOUNCE_MS = 2000L

    private var persistDir: File? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingSaves = mutableSetOf<String>()

    /** Initialize with app context for file persistence. Call once from MainActivity.onCreate. */
    fun init(context: Context) {
        persistDir = File(context.filesDir, "runtime_errors").also { it.mkdirs() }
        // Load persisted errors on background thread to avoid blocking startup
        ioExecutor.execute { loadFromDisk() }
    }

    @Synchronized
    fun report(appId: String, error: String) {
        val list = errors.getOrPut(appId) { mutableListOf() }
        if (list.size < MAX_ERRORS_PER_APP) {
            list.add(error)
            scheduleSave(appId)
        }
    }

    /** Record a console message (LOG/WARN/DEBUG level). */
    @Synchronized
    fun log(appId: String, level: String, message: String, lineNumber: Int) {
        val list = consoleLogs.getOrPut(appId) { mutableListOf() }
        if (list.size < MAX_LOGS_PER_APP) {
            list.add("[$level] $message (line $lineNumber)")
        }
    }

    @Synchronized
    fun getErrors(appId: String): List<String> {
        return errors[appId]?.toList() ?: emptyList()
    }

    /** Get recent console logs (non-error). */
    @Synchronized
    fun getLogs(appId: String): List<String> {
        return consoleLogs[appId]?.toList() ?: emptyList()
    }

    @Synchronized
    fun clear(appId: String) {
        errors.remove(appId)
        consoleLogs.remove(appId)
        pendingSaves.remove(appId)
        val dir = persistDir
        if (dir != null) {
            ioExecutor.execute { File(dir, "$appId.json").delete() }
        }
    }

    /** Debounce disk writes: schedule a save after SAVE_DEBOUNCE_MS. */
    private fun scheduleSave(appId: String) {
        if (persistDir == null) return
        synchronized(pendingSaves) {
            if (appId in pendingSaves) return // already scheduled
            pendingSaves.add(appId)
        }
        mainHandler.postDelayed({
            synchronized(pendingSaves) { pendingSaves.remove(appId) }
            val snapshot: List<String>
            synchronized(this) { snapshot = errors[appId]?.toList() ?: return@postDelayed }
            ioExecutor.execute { saveToDiskInternal(appId, snapshot) }
        }, SAVE_DEBOUNCE_MS)
    }

    private fun saveToDiskInternal(appId: String, errorList: List<String>) {
        val dir = persistDir ?: return
        try {
            val json = JSONObject()
            json.put("errors", JSONArray(errorList))
            File(dir, "$appId.json").writeText(json.toString())
        } catch (_: Exception) { /* best-effort */ }
    }

    @Synchronized
    private fun loadFromDisk() {
        val dir = persistDir ?: return
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val arr = json.optJSONArray("errors") ?: return@forEach
                val appId = file.nameWithoutExtension
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                if (list.isNotEmpty()) errors[appId] = list
            } catch (_: Exception) { /* skip corrupt files */ }
        }
    }
}
