package com.example.link_pi.agent

import android.util.Log
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the lifecycle of Python-based module services.
 *
 * Each module runs a Python server script (HTTP/TCP/UDP) in a background thread.
 * Port range: 8100–8199 (auto-assigned or manual).
 */
class ModuleService(private val storage: ModuleStorage) {

    companion object {
        private const val TAG = "ModuleService"
        private const val PORT_MIN = 8100
        private const val PORT_MAX = 8199
    }

    data class RunningModule(
        val moduleId: String,
        val port: Int,
        val thread: Thread,
        val startedAt: Long = System.currentTimeMillis()
    )

    private val running = ConcurrentHashMap<String, RunningModule>()

    /**
     * Start a module's Python server script.
     * @return allocated port on success, or error message
     */
    fun start(moduleId: String, port: Int = 0): Result<Int> {
        if (running.containsKey(moduleId)) {
            return Result.failure(IllegalStateException("Module already running on port ${running[moduleId]!!.port}"))
        }

        val module = storage.loadById(moduleId)
            ?: return Result.failure(IllegalArgumentException("Module not found: $moduleId"))

        val scriptsDir = storage.getScriptsDir(moduleId)
            ?: return Result.failure(IllegalStateException("Scripts directory not found"))

        val mainFile = File(scriptsDir, module.mainScript)
        if (!mainFile.exists()) {
            return Result.failure(IllegalStateException("Main script not found: ${module.mainScript}"))
        }

        val allocatedPort = if (port in 1..65535) port
            else if (module.defaultPort in PORT_MIN..PORT_MAX) module.defaultPort
            else allocatePort() ?: return Result.failure(IllegalStateException("No available port in $PORT_MIN-$PORT_MAX"))

        // Check port not already in use by another module
        if (running.values.any { it.port == allocatedPort }) {
            return Result.failure(IllegalStateException("Port $allocatedPort already in use"))
        }

        val scriptContent = mainFile.readText()
        val thread = Thread({
            try {
                runPythonServer(scriptsDir.absolutePath, module.mainScript, allocatedPort, scriptContent)
            } catch (e: Exception) {
                Log.e(TAG, "Module ${module.name} crashed: ${e.message}", e)
            } finally {
                running.remove(moduleId)
            }
        }, "module-${module.id}").apply { isDaemon = true }

        running[moduleId] = RunningModule(moduleId, allocatedPort, thread)
        thread.start()

        // Wait briefly for server to start
        Thread.sleep(500)
        if (!thread.isAlive) {
            running.remove(moduleId)
            return Result.failure(IllegalStateException("Server script exited immediately"))
        }

        Log.i(TAG, "Started module ${module.name} on port $allocatedPort")
        return Result.success(allocatedPort)
    }

    fun stop(moduleId: String): Boolean {
        val rm = running.remove(moduleId) ?: return false
        try {
            rm.thread.interrupt()
            // Give the Python script a chance to clean up
            rm.thread.join(2000)
            if (rm.thread.isAlive) {
                @Suppress("DEPRECATION")
                rm.thread.stop()
            }
        } catch (_: Exception) {}
        Log.i(TAG, "Stopped module $moduleId")
        return true
    }

    fun isRunning(moduleId: String): Boolean =
        running[moduleId]?.thread?.isAlive == true

    fun getPort(moduleId: String): Int? =
        running[moduleId]?.takeIf { it.thread.isAlive }?.port

    fun listRunning(): List<RunningModule> =
        running.values.filter { it.thread.isAlive }.toList()

    fun stopAll() {
        running.keys.toList().forEach { stop(it) }
    }

    /**
     * Call a running HTTP module's endpoint.
     */
    fun callHttp(moduleId: String, path: String, method: String = "GET", body: String? = null): String {
        val port = getPort(moduleId)
            ?: return """{"error":"Module not running. Use start_module first."}"""

        return try {
            val url = URL("http://127.0.0.1:$port${if (path.startsWith("/")) path else "/$path"}")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method.uppercase()
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")

            if (body != null && method.uppercase() !in listOf("GET", "HEAD")) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = conn.responseCode
            val respBody = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
            } catch (_: Exception) { "" }

            val truncated = if (respBody.length > 8000) respBody.take(8000) + "...(truncated)" else respBody
            JSONObject().apply {
                put("status", code)
                put("ok", code in 200..299)
                put("body", truncated)
            }.toString()
        } catch (e: Exception) {
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    /**
     * Get status summary of all modules (running + stopped).
     */
    fun statusJson(): String {
        val modules = storage.loadAll()
        val arr = JSONArray()
        for (m in modules) {
            val isRunning = isRunning(m.id)
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("serviceType", m.serviceType)
                put("running", isRunning)
                if (isRunning) {
                    put("port", getPort(m.id))
                    running[m.id]?.let { put("uptime_sec", (System.currentTimeMillis() - it.startedAt) / 1000) }
                }
                put("description", m.description)
                put("mainScript", m.mainScript)
                put("scripts", JSONArray(storage.listScripts(m.id)))
            })
        }
        return arr.toString(2)
    }

    // ── Internal ──

    private fun allocatePort(): Int? {
        val usedPorts = running.values.map { it.port }.toSet()
        for (p in PORT_MIN..PORT_MAX) {
            if (p in usedPorts) continue
            // Check port is actually free on the system
            try {
                ServerSocket(p).use { return p }
            } catch (_: Exception) { continue }
        }
        return null
    }

    private fun runPythonServer(scriptDirPath: String, scriptFile: String, port: Int, scriptContent: String) {
        val py = Python.getInstance()
        val sys = py.getModule("sys")
        val path = sys["path"]!!
        path.callAttr("insert", 0, scriptDirPath)

        try {
            // Set PORT environment so the script knows which port to bind
            py.getModule("builtins").callAttr("exec", """
import os
os.environ['MODULE_PORT'] = '$port'
os.environ['MODULE_HOST'] = '0.0.0.0'
""".trimIndent())

            // Apply server sandbox (less restrictive than compute sandbox)
            applyServerSandbox(py)

            // Execute the server script (blocks until server stops or thread is interrupted)
            val moduleName = scriptFile.removeSuffix(".py")
            py.getModule(moduleName)
        } finally {
            try { path.callAttr("remove", scriptDirPath) } catch (_: Exception) {}
        }
    }

    /**
     * Server sandbox: allows http.server, socket, threading (needed for servers)
     * but blocks subprocess, shutil, ctypes, etc.
     */
    private fun applyServerSandbox(py: Python) {
        try {
            py.getModule("builtins").callAttr("exec", """
import sys

class _BlockedModule:
    def __getattr__(self, name):
        raise ImportError("This module is blocked in server sandbox")

_BLOCKED = {
    'subprocess', 'shutil', 'ctypes', 'multiprocessing',
    'signal', 'importlib', 'imp', 'code', 'codeop',
    'compile', 'compileall', 'py_compile', 'zipimport',
    'webbrowser', 'ftplib', 'smtplib', 'xmlrpc', 'asyncio'
}
for mod_name in _BLOCKED:
    sys.modules[mod_name] = _BlockedModule()

_original_import = __builtins__.__import__ if hasattr(__builtins__, '__import__') else __import__

def _restricted_import(name, *args, **kwargs):
    base = name.split('.')[0]
    if base in _BLOCKED:
        raise ImportError(f"Import of '{name}' is blocked in server sandbox")
    return _original_import(name, *args, **kwargs)

import builtins
builtins.__import__ = _restricted_import
""".trimIndent())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply server sandbox: ${e.message}")
        }
    }
}
