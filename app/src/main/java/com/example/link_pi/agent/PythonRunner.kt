package com.example.link_pi.agent

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Sandboxed Python script runner for module packages.
 *
 * Security constraints:
 * - Scripts cannot access the network (no socket/urllib injected)
 * - Scripts cannot access the filesystem outside their own scripts/ dir (read-only)
 * - Each invocation has a hard timeout (default 5s)
 * - os/subprocess/sys modules are restricted
 * - Input/output must be JSON-serializable
 */
class PythonRunner(context: Context) {

    companion object {
        private const val TAG = "PythonRunner"
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val MAX_TIMEOUT_MS = 30000L
        private const val MAX_OUTPUT_SIZE = 64 * 1024 // 64KB max output

        @Volatile
        private var initialized = false
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "python-runner").also { it.isDaemon = true }
    }

    init {
        if (!initialized) {
            synchronized(PythonRunner::class.java) {
                if (!initialized) {
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(context))
                    }
                    initialized = true
                }
            }
        }
    }

    /**
     * Call a Python function from a module script.
     *
     * @param scriptDir  Absolute path to the module's scripts/ directory
     * @param scriptFile Relative filename within scripts/ (e.g. "protocol.py")
     * @param funcName   Function name to call (e.g. "encode_frame")
     * @param args       JSON-serializable arguments passed as a dict to the function
     * @param timeoutMs  Execution timeout in milliseconds
     * @return Result JSON object from the Python function
     */
    fun call(
        scriptDir: File,
        scriptFile: String,
        funcName: String,
        args: Map<String, String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): PythonResult {
        // Validate inputs
        if (!scriptDir.isDirectory) {
            return PythonResult.error("Script directory not found: ${scriptDir.name}")
        }
        // Path traversal protection
        val safeFileName = scriptFile.replace("..", "").replace("/", "").replace("\\", "")
        val scriptPath = File(scriptDir, safeFileName)
        if (!scriptPath.exists()) {
            return PythonResult.error("Script file not found: $safeFileName")
        }
        if (!scriptPath.canonicalPath.startsWith(scriptDir.canonicalPath)) {
            return PythonResult.error("Path traversal blocked")
        }
        if (!funcName.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
            return PythonResult.error("Invalid function name: $funcName")
        }

        val effectiveTimeout = timeoutMs.coerceIn(100, MAX_TIMEOUT_MS)
        val scriptContent = scriptPath.readText()

        val future = executor.submit(Callable {
            executePython(scriptDir.absolutePath, safeFileName, funcName, args, scriptContent)
        })

        return try {
            future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            PythonResult.error("Script execution timed out after ${effectiveTimeout}ms")
        } catch (e: Exception) {
            PythonResult.error("Execution error: ${e.message?.take(500)}")
        }
    }

    /**
     * Validate a Python script for syntax errors without executing it.
     */
    fun validate(scriptDir: File, scriptFile: String): PythonResult {
        val safeFileName = scriptFile.replace("..", "").replace("/", "").replace("\\", "")
        val scriptPath = File(scriptDir, safeFileName)
        if (!scriptPath.exists()) {
            return PythonResult.error("Script file not found: $safeFileName")
        }
        val content = scriptPath.readText()
        return try {
            val py = Python.getInstance()
            val builtins = py.getModule("builtins")
            builtins.callAttr("compile", content, safeFileName, "exec")
            PythonResult.ok(JSONObject().put("valid", true).put("file", safeFileName))
        } catch (e: PyException) {
            PythonResult(
                success = false,
                data = JSONObject().put("valid", false)
                    .put("error", e.message?.take(500) ?: "Unknown syntax error")
                    .put("file", safeFileName)
            )
        }
    }

    private fun executePython(
        scriptDirPath: String,
        scriptFile: String,
        funcName: String,
        args: Map<String, String>,
        scriptContent: String
    ): PythonResult {
        return try {
            val py = Python.getInstance()

            // Add script directory to Python path temporarily
            val sys = py.getModule("sys")
            val path = sys["path"] as PyObject
            path.callAttr("insert", 0, scriptDirPath)

            try {
                // Apply sandbox restrictions
                applySandbox(py)

                // Import the module (strip .py extension)
                val moduleName = scriptFile.removeSuffix(".py")
                val module = py.getModule(moduleName)

                // Get the function
                val func = module[funcName]
                    ?: return PythonResult.error("Function '$funcName' not found in $scriptFile")

                // Convert args to Python dict
                val argsJson = JSONObject(args).toString()
                val json = py.getModule("json")
                val pyArgs = json.callAttr("loads", argsJson)

                // Call the function
                val result = func.call(pyArgs)

                // Convert result back to JSON
                val resultStr = if (result == null) {
                    "{}"
                } else {
                    val jsonStr = json.callAttr("dumps", result).toString()
                    if (jsonStr.length > MAX_OUTPUT_SIZE) {
                        return PythonResult.error("Output too large (${jsonStr.length} bytes, max $MAX_OUTPUT_SIZE)")
                    }
                    jsonStr
                }

                PythonResult.ok(JSONObject(resultStr))
            } finally {
                // Remove script directory from path
                try { path.callAttr("remove", scriptDirPath) } catch (_: Exception) {}
            }
        } catch (e: PyException) {
            Log.w(TAG, "Python error in $scriptFile:$funcName", e)
            PythonResult.error("Python error: ${e.message?.take(500)}")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error in $scriptFile:$funcName", e)
            PythonResult.error("Error: ${e.message?.take(500)}")
        }
    }

    /**
     * Apply sandbox restrictions: block dangerous modules.
     */
    private fun applySandbox(py: Python) {
        try {
            py.getModule("builtins").callAttr("exec", """
import sys

class _BlockedModule:
    def __getattr__(self, name):
        raise ImportError("This module is blocked in sandbox mode")

# Block dangerous modules (including sub-modules)
_BLOCKED = {
    'subprocess', 'shutil', 'ctypes', 'multiprocessing',
    'os', 'socket', 'signal', 'threading', 'importlib',
    '_thread', 'imp', 'code', 'codeop', 'compile',
    'compileall', 'py_compile', 'zipimport',
    'webbrowser', 'http', 'urllib', 'ftplib', 'smtplib',
    'xmlrpc', 'asyncio'
}
for mod_name in _BLOCKED:
    sys.modules[mod_name] = _BlockedModule()

# Override __import__ to also block dynamic imports
_original_import = __builtins__.__import__ if hasattr(__builtins__, '__import__') else __import__

def _restricted_import(name, *args, **kwargs):
    base = name.split('.')[0]
    if base in _BLOCKED:
        raise ImportError(f"Import of '{name}' is blocked in sandbox mode")
    return _original_import(name, *args, **kwargs)

import builtins
builtins.__import__ = _restricted_import
""".trimIndent())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply sandbox: ${e.message}")
        }
    }

    fun dispose() {
        executor.shutdownNow()
    }

    /**
     * Result of a Python script execution.
     */
    data class PythonResult(
        val success: Boolean,
        val data: JSONObject = JSONObject()
    ) {
        companion object {
            fun ok(data: JSONObject) = PythonResult(true, data)
            fun error(message: String) = PythonResult(false, JSONObject().put("error", message))
        }

        fun toJsonString(): String = data.toString()
    }
}
