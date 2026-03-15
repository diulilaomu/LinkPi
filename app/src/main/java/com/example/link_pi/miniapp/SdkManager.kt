package com.example.link_pi.miniapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SDK modules that can be injected into MiniApps.
 * Maps to JS files in assets/miniapp_sdk/.
 */
enum class SdkModule(
    val fileName: String,
    val displayName: String,
    val description: String,
    val bridgeGroup: String
) {
    CORE("core.js", "核心", "错误捕获与回调系统", "CORE"),
    DEVICE("device.js", "设备", "Toast、振动、剪贴板、设备信息、电量、定位", "SENSOR"),
    STORAGE("storage.js", "存储", "Key-Value 持久化存储", "STORAGE"),
    NETWORK("network.js", "网络", "HTTP 请求（绕过 CORS）", "NETWORK"),
    MODULES("modules.js", "模块", "Python 服务模块调用", "NETWORK"),
    REALTIME("realtime.js", "实时通信", "WebSocket 局域网通信服务", "REALTIME");

    companion object {
        fun fromFileName(name: String): SdkModule? = entries.find { it.fileName == name }
    }
}

/**
 * Manages SDK module configuration per MiniApp.
 * Tracks which SDK modules each app has enabled, persisted alongside workspace files.
 */
class SdkManager(private val context: Context) {

    companion object {
        private const val SDK_CONFIG_FILE = ".sdk_config.json"
        private const val SDK_DIR = "miniapp_sdk"
        /** Increment when SDK scripts change to trigger re-injection for single-HTML apps. */
        const val SDK_VERSION = 1
    }

    private val workspacesRoot: File
        get() = File(context.filesDir, "workspaces")

    /** Get the enabled SDK modules for an app. Defaults to [CORE] only. */
    fun getEnabledModules(appId: String): Set<SdkModule> {
        val configFile = File(workspacesRoot, "${sanitizeId(appId)}/$SDK_CONFIG_FILE")
        if (!configFile.exists()) return setOf(SdkModule.CORE)
        return try {
            val json = JSONObject(configFile.readText())
            val arr = json.optJSONArray("modules") ?: return setOf(SdkModule.CORE)
            val result = mutableSetOf(SdkModule.CORE)
            for (i in 0 until arr.length()) {
                SdkModule.fromFileName(arr.getString(i))?.let { result.add(it) }
            }
            result
        } catch (_: Exception) {
            setOf(SdkModule.CORE)
        }
    }

    /** Save enabled SDK modules for an app. */
    fun saveEnabledModules(appId: String, modules: Set<SdkModule>) {
        val dir = File(workspacesRoot, sanitizeId(appId))
        dir.mkdirs()
        val configFile = File(dir, SDK_CONFIG_FILE)
        val json = JSONObject()
        json.put("modules", JSONArray(modules.map { it.fileName }))
        configFile.writeText(json.toString(2))
    }

    /** Enable a specific module for an app. Returns the new full set. */
    fun enableModule(appId: String, module: SdkModule): Set<SdkModule> {
        val current = getEnabledModules(appId).toMutableSet()
        current.add(module)
        // MODULES and NETWORK depend on CORE (always present)
        saveEnabledModules(appId, current)
        return current
    }

    /** Disable a specific module for an app. CORE cannot be disabled. */
    fun disableModule(appId: String, module: SdkModule): Set<SdkModule> {
        if (module == SdkModule.CORE) return getEnabledModules(appId)
        val current = getEnabledModules(appId).toMutableSet()
        current.remove(module)
        saveEnabledModules(appId, current)
        return current
    }

    /**
     * Read a SDK JS file from assets.
     * Returns the file content wrapped in a <script> tag for injection.
     */
    fun readSdkScript(module: SdkModule): String {
        return try {
            val content = context.assets.open("$SDK_DIR/${module.fileName}")
                .bufferedReader().use { it.readText() }
            "<script>\n$content\n</script>"
        } catch (_: Exception) {
            "<!-- SDK module ${module.fileName} not found -->"
        }
    }

    /**
     * Build the combined SDK script for a set of modules.
     * CORE is always first, other modules follow.
     */
    fun buildSdkScripts(modules: Set<SdkModule>): String {
        val ordered = modules.sortedBy { it.ordinal }
        return "<!-- LinkPi SDK v$SDK_VERSION -->\n" + ordered.joinToString("\n") { readSdkScript(it) }
    }

    /**
     * Build the SDK scripts for a specific app, based on its saved configuration.
     */
    fun buildSdkScriptsForApp(appId: String): String {
        return buildSdkScripts(getEnabledModules(appId))
    }

    private fun sanitizeId(id: String): String {
        return id.replace(Regex("[^a-zA-Z0-9\\-_]"), "")
    }
}
