package com.example.link_pi.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes tool calls requested by the AI agent.
 */
class ToolExecutor(
    private val context: Context,
    val miniAppStorage: MiniAppStorage,
    val workspaceManager: WorkspaceManager = WorkspaceManager(context)
) {
    /** Current workspace app ID — set before orchestrator runs. */
    var currentAppId: String = "default"
    /** Hint from conversation context — latest relevant app ID for auto-resolve. */
    var latestAppHint: String? = null
    /** Cached result of last list_saved_apps call for auto-resolve. */
    private var lastListedApps: List<MiniApp> = emptyList()
    /** Long-term memory storage. */
    val memoryStorage = MemoryStorage(context)
    /** Dynamic module storage. */
    val moduleStorage = ModuleStorage(context)
    private val httpClient get() = ModuleStorage.httpClient

    /** All tool definitions the AI can use. */
    val toolDefs: List<ToolDef> = listOf(
        ToolDef(
            "get_device_info", "获取设备基本信息（型号、品牌、系统版本等）",
            emptyList()
        ),
        ToolDef(
            "get_battery_level", "获取当前设备电池电量百分比",
            emptyList()
        ),
        ToolDef(
            "get_location", "获取设备GPS定位（纬度、经度、精度）",
            emptyList()
        ),
        ToolDef(
            "vibrate", "让设备振动",
            listOf(ToolParam("milliseconds", "number", "振动时长(毫秒), 最大5000"))
        ),
        ToolDef(
            "show_toast", "在设备上显示短暂提示消息",
            listOf(ToolParam("message", "string", "要显示的消息"))
        ),
        ToolDef(
            "write_clipboard", "将文本复制到剪贴板",
            listOf(ToolParam("text", "string", "要复制的文本"))
        ),
        ToolDef(
            "save_data", "将数据持久化存储到本地",
            listOf(
                ToolParam("key", "string", "存储键名"),
                ToolParam("value", "string", "存储值")
            )
        ),
        ToolDef(
            "load_data", "从本地读取之前保存的数据",
            listOf(ToolParam("key", "string", "存储键名"))
        ),
        ToolDef(
            "list_saved_apps", "列出用户保存的所有小应用",
            emptyList()
        ),
        ToolDef(
            "fetch_url", "发起HTTP GET请求获取网页或API数据（仅限HTTPS）",
            listOf(ToolParam("url", "string", "目标URL（必须是https://开头）"))
        ),
        ToolDef(
            "get_current_time", "获取当前日期和时间",
            emptyList()
        ),
        ToolDef(
            "calculate", "计算数学表达式",
            listOf(ToolParam("expression", "string", "数学表达式，如 2+3*4"))
        ),

        // ── File System Tools (Workspace) ──
        ToolDef(
            "create_file", "在工作空间中创建新文件（如html, css, js等）",
            listOf(
                ToolParam("path", "string", "相对路径，如 index.html 或 css/style.css"),
                ToolParam("content", "string", "文件内容")
            )
        ),
        ToolDef(
            "write_file", "写入/覆盖文件内容（文件不存在则创建）",
            listOf(
                ToolParam("path", "string", "相对路径"),
                ToolParam("content", "string", "文件完整内容")
            )
        ),
        ToolDef(
            "append_file", "在文件末尾追加内容",
            listOf(
                ToolParam("path", "string", "相对路径"),
                ToolParam("content", "string", "要追加的内容")
            )
        ),
        ToolDef(
            "read_workspace_file", "读取工作空间中的文件内容（带行号）",
            listOf(
                ToolParam("path", "string", "相对路径"),
                ToolParam("start_line", "number", "起始行号（1开始），不填则从头读", required = false),
                ToolParam("end_line", "number", "结束行号（含），不填则读到尾", required = false)
            )
        ),
        ToolDef(
            "replace_in_file", "定位替换文件中的指定文本（支持空白容错匹配）",
            listOf(
                ToolParam("path", "string", "相对路径"),
                ToolParam("old_text", "string", "要替换的原文本"),
                ToolParam("new_text", "string", "替换后的新文本")
            )
        ),
        ToolDef(
            "replace_lines", "按行号范围替换文件内容（当replace_in_file匹配失败时使用）",
            listOf(
                ToolParam("path", "string", "相对路径"),
                ToolParam("start_line", "number", "起始行号（1开始，含）"),
                ToolParam("end_line", "number", "结束行号（含）"),
                ToolParam("new_content", "string", "替换后的新内容")
            )
        ),
        ToolDef(
            "create_directory", "创建文件夹（支持多级目录）",
            listOf(ToolParam("path", "string", "相对路径，如 css 或 src/components"))
        ),
        ToolDef(
            "list_workspace_files", "列出工作空间中的文件和文件夹",
            listOf(ToolParam("path", "string", "相对路径，留空列出根目录", required = false))
        ),
        ToolDef(
            "delete_workspace_file", "删除工作空间中的文件",
            listOf(ToolParam("path", "string", "相对路径"))
        ),
        ToolDef(
            "delete_directory", "递归删除文件夹及其所有内容",
            listOf(ToolParam("path", "string", "相对路径，如 old_folder 或 src/unused"))
        ),
        ToolDef(
            "rename_file", "重命名或移动文件/文件夹",
            listOf(
                ToolParam("old_path", "string", "原路径"),
                ToolParam("new_path", "string", "新路径")
            )
        ),
        ToolDef(
            "copy_file", "复制文件到新路径",
            listOf(
                ToolParam("source", "string", "源文件路径"),
                ToolParam("destination", "string", "目标文件路径")
            )
        ),
        ToolDef(
            "open_app_workspace", "打开一个已保存的应用的工作空间进行修改。可以传app_id或app_name。必须先调用list_saved_apps获取应用信息",
            listOf(
                ToolParam("app_id", "string", "应用ID（从list_saved_apps获取）", required = false),
                ToolParam("app_name", "string", "应用名称（模糊匹配）", required = false)
            )
        ),

        // ── Long-term Memory Tools ──
        ToolDef(
            "memory_save", "保存一条长期记忆（用户偏好、重要信息、经验教训等）。记忆会永久保存，不会随对话消失",
            listOf(
                ToolParam("content", "string", "记忆内容"),
                ToolParam("tags", "string", "标签（逗号分隔，如: 偏好,颜色,UI）")
            )
        ),
        ToolDef(
            "memory_search", "搜索长期记忆。当需要回忆用户偏好、历史信息、之前的经验时使用",
            listOf(
                ToolParam("query", "string", "搜索关键词（支持多个词空格分隔）")
            )
        ),
        ToolDef(
            "memory_list", "列出所有已保存的长期记忆",
            emptyList()
        ),
        ToolDef(
            "memory_delete", "删除一条长期记忆",
            listOf(ToolParam("id", "string", "记忆ID"))
        ),
        ToolDef(
            "memory_update", "更新一条已有记忆的内容或标签",
            listOf(
                ToolParam("id", "string", "记忆ID"),
                ToolParam("content", "string", "新内容（留空则不修改）", required = false),
                ToolParam("tags", "string", "新标签（留空则不修改）", required = false)
            )
        ),

        // ── Module Tools (Dynamic API Services) ──
        ToolDef(
            "create_module", "创建一个新的API模块（后台服务）。模块可封装一组HTTP接口，供后续调用",
            listOf(
                ToolParam("name", "string", "模块名称（如 httpbin, weather）"),
                ToolParam("description", "string", "模块描述"),
                ToolParam("base_url", "string", "基础URL（如 https://httpbin.org）"),
                ToolParam("default_headers", "string", "默认请求头JSON（如 {\"Authorization\":\"Bearer xxx\"}）", required = false),
                ToolParam("endpoints", "string", "端点列表JSON数组，格式: [{\"name\":\"get_ip\",\"path\":\"/ip\",\"method\":\"GET\",\"description\":\"获取IP\"}]", required = false)
            )
        ),
        ToolDef(
            "add_module_endpoint", "给已有模块添加一个新的API端点",
            listOf(
                ToolParam("module_id", "string", "模块ID（从list_modules获取）"),
                ToolParam("name", "string", "端点名称（如 post_data）"),
                ToolParam("path", "string", "请求路径（如 /post，支持{{param}}占位符）"),
                ToolParam("method", "string", "HTTP方法: GET/POST/PUT/DELETE", required = false),
                ToolParam("headers", "string", "额外请求头JSON", required = false),
                ToolParam("body_template", "string", "请求体模板，支持{{param}}占位符", required = false),
                ToolParam("description", "string", "端点描述", required = false)
            )
        ),
        ToolDef(
            "remove_module_endpoint", "从模块中移除一个端点",
            listOf(
                ToolParam("module_id", "string", "模块ID"),
                ToolParam("endpoint_name", "string", "要移除的端点名称")
            )
        ),
        ToolDef(
            "call_module", "调用模块的某个端点执行HTTP请求",
            listOf(
                ToolParam("module", "string", "模块名称或ID"),
                ToolParam("endpoint", "string", "端点名称"),
                ToolParam("params", "string", "参数JSON对象，填充{{param}}占位符", required = false)
            )
        ),
        ToolDef(
            "list_modules", "列出所有已创建的模块及其端点",
            emptyList()
        ),
        ToolDef(
            "update_module", "更新模块的基本信息",
            listOf(
                ToolParam("module_id", "string", "模块ID"),
                ToolParam("name", "string", "新名称", required = false),
                ToolParam("description", "string", "新描述", required = false),
                ToolParam("base_url", "string", "新基础URL", required = false),
                ToolParam("default_headers", "string", "新默认请求头JSON", required = false)
            )
        ),
        ToolDef(
            "delete_module", "删除一个模块",
            listOf(ToolParam("module_id", "string", "模块ID"))
        ),

        // ── Coding Tools ──
        ToolDef(
            "grep_file", "在指定文件中搜索文本或正则表达式，返回匹配行及行号",
            listOf(
                ToolParam("path", "string", "相对路径"),
                ToolParam("pattern", "string", "搜索模式（正则表达式或纯文本）"),
                ToolParam("is_regex", "boolean", "true=正则匹配 false=纯文本匹配，默认true", required = false)
            )
        ),
        ToolDef(
            "grep_workspace", "在工作空间所有文件中搜索文本或正则，返回文件名:行号:匹配内容",
            listOf(
                ToolParam("pattern", "string", "搜索模式（正则表达式或纯文本）"),
                ToolParam("is_regex", "boolean", "true=正则匹配 false=纯文本匹配，默认true", required = false),
                ToolParam("file_filter", "string", "文件名过滤（如 *.js *.html），留空搜索全部", required = false)
            )
        ),
        ToolDef(
            "insert_lines", "在文件指定行号处插入内容（在该行之前插入，不替换原内容）",
            listOf(
                ToolParam("path", "string", "相对路径"),
                ToolParam("line", "number", "插入位置的行号（1开始，内容插入到该行之前）"),
                ToolParam("content", "string", "要插入的内容")
            )
        ),
        ToolDef(
            "file_info", "获取文件或目录的详细信息（大小、行数、字符数、修改时间等）",
            listOf(ToolParam("path", "string", "相对路径"))
        )
    )

    suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        try {
            // Normalize argument keys to match tool parameter definitions
            val args = normalizeArgs(call.toolName, call.arguments)
            val normalizedCall = call.copy(arguments = args)
            val result = when (normalizedCall.toolName) {
                "get_device_info" -> executeGetDeviceInfo()
                "get_battery_level" -> executeGetBattery()
                "get_location" -> executeGetLocation()
                "vibrate" -> executeVibrate(args)
                "show_toast" -> executeShowToast(args)
                "write_clipboard" -> executeWriteClipboard(args)
                "save_data" -> executeSaveData(args)
                "load_data" -> executeLoadData(args)
                "list_saved_apps" -> executeListApps()
                "fetch_url" -> executeFetchUrl(args)
                "get_current_time" -> executeGetTime()
                "calculate" -> executeCalculate(args)
                // File system tools
                "create_file" -> workspaceManager.createFile(currentAppId, args["path"] ?: "", args["content"] ?: "")
                "write_file" -> workspaceManager.writeFile(currentAppId, args["path"] ?: "", args["content"] ?: "")
                "append_file" -> workspaceManager.appendFile(currentAppId, args["path"] ?: "", args["content"] ?: "")
                "read_workspace_file" -> {
                    val startLine = args["start_line"]?.toIntOrNull()
                    val endLine = args["end_line"]?.toIntOrNull()
                    if (startLine != null && endLine != null) {
                        workspaceManager.readFileLines(currentAppId, args["path"] ?: "", startLine, endLine)
                    } else {
                        workspaceManager.readFile(currentAppId, args["path"] ?: "")
                    }
                }
                "replace_in_file" -> workspaceManager.replaceInFile(currentAppId, args["path"] ?: "", args["old_text"] ?: "", args["new_text"] ?: "")
                "replace_lines" -> workspaceManager.replaceLines(currentAppId, args["path"] ?: "", args["start_line"]?.toIntOrNull() ?: 0, args["end_line"]?.toIntOrNull() ?: 0, args["new_content"] ?: "")
                "create_directory" -> workspaceManager.createDirectory(currentAppId, args["path"] ?: "")
                "list_workspace_files" -> workspaceManager.listFiles(currentAppId, args["path"] ?: "")
                "delete_workspace_file" -> workspaceManager.deleteFile(currentAppId, args["path"] ?: "")
                "delete_directory" -> workspaceManager.deleteDirectory(currentAppId, args["path"] ?: "")
                "rename_file" -> workspaceManager.renameFile(currentAppId, args["old_path"] ?: "", args["new_path"] ?: "")
                "copy_file" -> workspaceManager.copyFile(currentAppId, args["source"] ?: "", args["destination"] ?: "")
                "open_app_workspace" -> executeOpenAppWorkspace(args)
                // Coding tools
                "grep_file" -> workspaceManager.grepFile(currentAppId, args["path"] ?: "", args["pattern"] ?: "", args["is_regex"]?.toBooleanStrictOrNull() ?: true)
                "grep_workspace" -> workspaceManager.grepWorkspace(currentAppId, args["pattern"] ?: "", args["is_regex"]?.toBooleanStrictOrNull() ?: true, args["file_filter"] ?: "")
                "insert_lines" -> workspaceManager.insertLines(currentAppId, args["path"] ?: "", args["line"]?.toIntOrNull() ?: 1, args["content"] ?: "")
                "file_info" -> workspaceManager.fileInfo(currentAppId, args["path"] ?: "")
                // Memory tools
                "memory_save" -> executeMemorySave(args)
                "memory_search" -> executeMemorySearch(args)
                "memory_list" -> executeMemoryList()
                "memory_delete" -> executeMemoryDelete(args)
                "memory_update" -> executeMemoryUpdate(args)
                // Module tools
                "create_module" -> executeCreateModule(args)
                "add_module_endpoint" -> executeAddEndpoint(args)
                "remove_module_endpoint" -> executeRemoveEndpoint(args)
                "call_module" -> executeCallModule(args)
                "list_modules" -> executeListModules()
                "update_module" -> executeUpdateModule(args)
                "delete_module" -> executeDeleteModule(args)
                else -> return@withContext ToolResult(normalizedCall.toolName, false, "Unknown tool: ${normalizedCall.toolName}")
            }
            val success = !result.startsWith("Error:")
            // If a file tool failed or returned empty on an uninitialized workspace, hint to open_app_workspace
            val finalResult = if (!success || result == "(empty directory)") {
                appendWorkspaceHint(normalizedCall.toolName, result)
            } else result
            ToolResult(call.toolName, success, finalResult)
        } catch (e: Exception) {
            ToolResult(call.toolName, false, "Error: ${e.message}")
        }
    }

    private val FILE_TOOL_NAMES = setOf(
        "create_file", "write_file", "append_file", "read_workspace_file",
        "replace_in_file", "replace_lines", "create_directory",
        "list_workspace_files", "delete_workspace_file", "delete_directory",
        "rename_file", "copy_file", "grep_file", "grep_workspace",
        "insert_lines", "file_info"
    )

    /**
     * If a file tool fails/returns empty on a workspace with no files,
     * and saved apps exist, append a hint to use open_app_workspace.
     */
    private fun appendWorkspaceHint(toolName: String, result: String): String {
        if (toolName !in FILE_TOOL_NAMES) return result
        if (workspaceManager.hasFiles(currentAppId)) return result

        val savedApps = miniAppStorage.loadAll()
        if (savedApps.isEmpty()) return result

        val appList = savedApps.joinToString(", ") { "'${it.name}'(id=${it.id})" }
        return "$result\n\n[Hint] Current workspace is empty. To access an existing app's files, call open_app_workspace first. Saved apps: $appList"
    }

    private fun executeGetDeviceInfo(): String {
        return JSONObject().apply {
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("manufacturer", Build.MANUFACTURER)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
        }.toString(2)
    }

    private fun executeGetBattery(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "$level%"
    }

    private fun executeGetLocation(): String {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Location permission not granted"
        }
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        return if (loc != null) {
            JSONObject().apply {
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
                put("accuracy", loc.accuracy.toDouble())
            }.toString(2)
        } else {
            "Location unavailable"
        }
    }

    private fun executeVibrate(args: Map<String, String>): String {
        val ms = (args["milliseconds"]?.toLongOrNull() ?: 200).coerceIn(0, 5000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
        return "Vibrated for ${ms}ms"
    }

    private suspend fun executeShowToast(args: Map<String, String>): String {
        val message = args["message"] ?: "No message"
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        return "Toast shown: $message"
    }

    private suspend fun executeWriteClipboard(args: Map<String, String>): String {
        val text = args["text"] ?: return "No text provided"
        withContext(Dispatchers.Main) {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("text", text))
        }
        return "Copied to clipboard"
    }

    private fun executeSaveData(args: Map<String, String>): String {
        val key = args["key"] ?: return "No key provided"
        val value = args["value"] ?: return "No value provided"
        context.getSharedPreferences("agent_data", Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
        return "Data saved: $key"
    }

    private fun executeLoadData(args: Map<String, String>): String {
        val key = args["key"] ?: return "No key provided"
        val value = context.getSharedPreferences("agent_data", Context.MODE_PRIVATE)
            .getString(key, null)
        return value ?: "(no data found for key: $key)"
    }

    private fun executeListApps(): String {
        val apps = miniAppStorage.loadAll()
        lastListedApps = apps
        if (apps.isEmpty()) return "No saved apps"
        val arr = JSONArray()
        for (app in apps) {
            arr.put(JSONObject().apply {
                put("id", app.id)
                put("name", app.name)
                put("description", app.description)
            })
        }
        return arr.toString(2)
    }

    private fun executeFetchUrl(args: Map<String, String>): String {
        val url = args["url"] ?: return "No URL provided"
        // Security: only allow HTTPS
        if (!url.startsWith("https://")) {
            return "Only HTTPS URLs are allowed"
        }
        val request = Request.Builder().url(url).get().build()
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: ""
        // Limit response size
        return if (body.length > 4000) body.take(4000) + "\n...(truncated)" else body
    }

    private fun executeGetTime(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    private fun executeCalculate(args: Map<String, String>): String {
        val expr = args["expression"] ?: return "No expression"
        // Simple safe evaluator for basic math
        return try {
            val sanitized = expr.replace(Regex("[^0-9+\\-*/().%\\s]"), "")
            if (sanitized != expr.trim()) return "Expression contains invalid characters"
            val result = evalMath(sanitized)
            result.toBigDecimal().stripTrailingZeros().toPlainString()
        } catch (e: Exception) {
            "Calculation error: ${e.message}"
        }
    }

    private fun evalMath(expr: String): Double {
        return object {
            var pos = 0
            val str = expr.replace(" ", "")

            fun parse(): Double {
                val result = parseExpr()
                if (pos < str.length) throw RuntimeException("Unexpected: ${str[pos]}")
                return result
            }

            fun parseExpr(): Double {
                var x = parseTerm()
                while (pos < str.length) {
                    when (str[pos]) {
                        '+' -> { pos++; x += parseTerm() }
                        '-' -> { pos++; x -= parseTerm() }
                        else -> break
                    }
                }
                return x
            }

            fun parseTerm(): Double {
                var x = parseFactor()
                while (pos < str.length) {
                    when (str[pos]) {
                        '*' -> { pos++; x *= parseFactor() }
                        '/' -> { pos++; val d = parseFactor(); if (d == 0.0) throw ArithmeticException("Division by zero"); x /= d }
                        '%' -> { pos++; x %= parseFactor() }
                        else -> break
                    }
                }
                return x
            }

            fun parseFactor(): Double {
                if (pos < str.length && str[pos] == '-') { pos++; return -parseFactor() }
                if (pos < str.length && str[pos] == '+') { pos++; return parseFactor() }
                if (pos < str.length && str[pos] == '(') {
                    pos++ // skip '('
                    val v = parseExpr()
                    if (pos < str.length && str[pos] == ')') pos++
                    return v
                }
                val start = pos
                while (pos < str.length && (str[pos].isDigit() || str[pos] == '.')) pos++
                if (start == pos) throw RuntimeException("Expected number at pos $pos")
                return str.substring(start, pos).toDouble()
            }
        }.parse()
    }

    private fun executeOpenAppWorkspace(args: Map<String, String>): String {
        val appId = args["app_id"]?.takeIf { it.isNotBlank() }
        val appName = args["app_name"]?.takeIf { it.isNotBlank() }

        val allApps = miniAppStorage.loadAll()
        if (allApps.isEmpty()) return "Error: no saved apps. Nothing to modify."

        // ── Auto-resolve: no usable args ──
        if (appId == null && appName == null) {
            // Try ALL arg values as identifiers (AI might use wrong key names)
            for ((_, value) in args) {
                val v = value.trim()
                if (v.isBlank()) continue
                val byId = allApps.find { it.id == v }
                if (byId != null) return openAppById(byId)
                val byName = allApps.find { it.name.equals(v, ignoreCase = true) }
                    ?: allApps.find { it.name.contains(v, ignoreCase = true) }
                if (byName != null) return openAppById(byName)
            }
            // Try latestAppHint from conversation context
            latestAppHint?.let { hint ->
                val byHint = allApps.find { it.id == hint }
                if (byHint != null) return openAppById(byHint)
            }
            // Only 1 app → auto-open
            if (allApps.size == 1) return openAppById(allApps[0])
            // Cannot auto-resolve — return actionable error
            return buildAppNotFoundError(allApps)
        }

        // ── Normal matching ──
        val app = when {
            appId != null -> allApps.find { it.id == appId }
            appName != null -> allApps.find { it.name.equals(appName, ignoreCase = true) }
                ?: allApps.find { it.name.contains(appName, ignoreCase = true) }
            else -> null
        }

        if (app != null) return openAppById(app)

        // Try swapped: appId as name, appName as id
        val swapped = allApps.find { it.id == (appName ?: "") }
            ?: allApps.find { it.name.contains(appId ?: "", ignoreCase = true) }
        if (swapped != null) return openAppById(swapped)

        return buildAppNotFoundError(allApps)
    }

    /** Build a clear error message with exact tool_call examples the AI can follow. */
    private fun buildAppNotFoundError(allApps: List<MiniApp>): String {
        val examples = allApps.joinToString("\n") { app ->
            "  - \"${app.name}\" → app_id=\"${app.id}\""
        }
        return """Error: app_id is required. Choose one and call again:
$examples
Example: open_app_workspace(app_id=\"<paste id from above>\")""".trimIndent()
    }

    private fun openAppById(app: MiniApp): String {
        currentAppId = app.id

        if (app.isWorkspaceApp && workspaceManager.hasFiles(app.id)) {
            // Workspace app — files already exist
            val files = workspaceManager.getAllFiles(app.id)
            return "Opened workspace for: ${app.name}\nFiles:\n${files.joinToString("\n") { "  $it" }}"
        } else if (app.htmlContent.isNotBlank()) {
            // Single-file app — create workspace from htmlContent
            workspaceManager.writeFile(app.id, "index.html", app.htmlContent)
            return "Opened workspace for: ${app.name}\nConverted single-file app to workspace.\nFiles:\n  index.html"
        }
        return "Opened workspace for: ${app.name} (empty)"
    }

    // ── Memory Tool Implementations ──

    private fun executeMemorySave(args: Map<String, String>): String {
        val content = args["content"]?.takeIf { it.isNotBlank() }
            ?: return "Error: content is required"
        val tags = args["tags"]?.split(Regex("[,，;；]+"))
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val memory = memoryStorage.save(content, tags)
        return "Memory saved (id=${memory.id}, tags=${memory.tags.joinToString(",")})"
    }

    private fun executeMemorySearch(args: Map<String, String>): String {
        val query = args["query"]?.takeIf { it.isNotBlank() }
            ?: return "Error: query is required"
        val results = memoryStorage.search(query)
        if (results.isEmpty()) return "No memories found for: $query"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return results.joinToString("\n---\n") { m ->
            "[${m.id}] (${m.tags.joinToString(",")}) ${sdf.format(java.util.Date(m.updatedAt))}\n${m.content}"
        }
    }

    private fun executeMemoryList(): String {
        val all = memoryStorage.listAll()
        if (all.isEmpty()) return "No memories saved yet."
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return "Total: ${all.size}\n" + all.joinToString("\n") { m ->
            "[${m.id}] (${m.tags.joinToString(",")}) ${sdf.format(java.util.Date(m.updatedAt))} — ${m.content.take(80)}"
        }
    }

    private fun executeMemoryDelete(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: id is required"
        return if (memoryStorage.delete(id)) "Memory $id deleted."
        else "Error: memory $id not found."
    }

    private fun executeMemoryUpdate(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: id is required"
        val content = args["content"]?.takeIf { it.isNotBlank() }
        val tags = args["tags"]?.split(Regex("[,，;；]+"))
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (content == null && tags == null) return "Error: provide content or tags to update"
        val updated = memoryStorage.update(id, content, tags)
            ?: return "Error: memory $id not found."
        return "Memory $id updated. tags=${updated.tags.joinToString(",")}"
    }

    // ── Module Tool Implementations ──

    private fun executeCreateModule(args: Map<String, String>): String {
        val name = args["name"]?.takeIf { it.isNotBlank() }
            ?: return "Error: name is required"
        val description = args["description"]?.takeIf { it.isNotBlank() } ?: ""
        val baseUrl = args["base_url"]?.takeIf { it.isNotBlank() }
            ?: return "Error: base_url is required"

        val defaultHeaders = mutableMapOf<String, String>()
        args["default_headers"]?.takeIf { it.isNotBlank() }?.let {
            try {
                val hj = JSONObject(it)
                hj.keys().forEach { k -> defaultHeaders[k] = hj.getString(k) }
            } catch (_: Exception) {}
        }

        val endpoints = mutableListOf<ModuleStorage.Endpoint>()
        args["endpoints"]?.takeIf { it.isNotBlank() }?.let {
            try {
                val arr = JSONArray(it)
                for (i in 0 until arr.length()) {
                    val ep = arr.getJSONObject(i)
                    val epHeaders = mutableMapOf<String, String>()
                    ep.optJSONObject("headers")?.let { h ->
                        h.keys().forEach { k -> epHeaders[k] = h.getString(k) }
                    }
                    endpoints.add(ModuleStorage.Endpoint(
                        name = ep.getString("name"),
                        path = ep.optString("path", ""),
                        method = ep.optString("method", "GET"),
                        headers = epHeaders,
                        bodyTemplate = ep.optString("body_template", ep.optString("bodyTemplate", "")),
                        description = ep.optString("description", "")
                    ))
                }
            } catch (_: Exception) {}
        }

        val module = moduleStorage.create(name, description, baseUrl, defaultHeaders, endpoints)
        return "Module created: ${module.name} (id=${module.id}, ${module.endpoints.size} endpoints)"
    }

    private fun executeAddEndpoint(args: Map<String, String>): String {
        val moduleId = args["module_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module_id is required"
        val name = args["name"]?.takeIf { it.isNotBlank() }
            ?: return "Error: endpoint name is required"
        val path = args["path"]?.takeIf { it.isNotBlank() }
            ?: return "Error: path is required"
        val method = args["method"]?.uppercase() ?: "GET"

        val headers = mutableMapOf<String, String>()
        args["headers"]?.takeIf { it.isNotBlank() }?.let {
            try {
                val hj = JSONObject(it)
                hj.keys().forEach { k -> headers[k] = hj.getString(k) }
            } catch (_: Exception) {}
        }

        val bodyTemplate = args["body_template"] ?: ""
        val description = args["description"] ?: ""

        val endpoint = ModuleStorage.Endpoint(name, path, method, headers, bodyTemplate, description)
        val module = moduleStorage.addEndpoint(moduleId, endpoint)
            ?: return "Error: module '$moduleId' not found"
        return "Endpoint '$name' added to module '${module.name}'. Total endpoints: ${module.endpoints.size}"
    }

    private fun executeRemoveEndpoint(args: Map<String, String>): String {
        val moduleId = args["module_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module_id is required"
        val endpointName = args["endpoint_name"]?.takeIf { it.isNotBlank() }
            ?: return "Error: endpoint_name is required"
        val module = moduleStorage.removeEndpoint(moduleId, endpointName)
            ?: return "Error: module '$moduleId' not found"
        return "Endpoint '$endpointName' removed from module '${module.name}'"
    }

    private fun executeCallModule(args: Map<String, String>): String {
        val moduleRef = args["module"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module name or ID is required"
        val endpointName = args["endpoint"]?.takeIf { it.isNotBlank() }
            ?: return "Error: endpoint name is required"

        val module = moduleStorage.loadById(moduleRef) ?: moduleStorage.findByName(moduleRef)
            ?: return "Error: module '$moduleRef' not found. Use list_modules to see available modules."

        val params = mutableMapOf<String, String>()
        args["params"]?.takeIf { it.isNotBlank() }?.let {
            try {
                val pj = JSONObject(it)
                pj.keys().forEach { k -> params[k] = pj.optString(k, "") }
            } catch (_: Exception) {}
        }

        return moduleStorage.callEndpoint(module, endpointName, params)
    }

    private fun executeListModules(): String {
        val modules = moduleStorage.loadAll()
        if (modules.isEmpty()) return "No modules created yet."
        val arr = JSONArray()
        for (m in modules) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("description", m.description)
                put("baseUrl", m.baseUrl)
                put("endpoints", JSONArray().apply {
                    m.endpoints.forEach { ep ->
                        put(JSONObject().apply {
                            put("name", ep.name)
                            put("method", ep.method)
                            put("path", ep.path)
                            put("description", ep.description)
                        })
                    }
                })
            })
        }
        return arr.toString(2)
    }

    private fun executeUpdateModule(args: Map<String, String>): String {
        val moduleId = args["module_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module_id is required"
        val defaultHeaders = mutableMapOf<String, String>()
        args["default_headers"]?.takeIf { it.isNotBlank() }?.let {
            try {
                val hj = JSONObject(it)
                hj.keys().forEach { k -> defaultHeaders[k] = hj.getString(k) }
            } catch (_: Exception) {}
        }
        val module = moduleStorage.updateModule(
            moduleId,
            name = args["name"]?.takeIf { it.isNotBlank() },
            description = args["description"]?.takeIf { it.isNotBlank() },
            baseUrl = args["base_url"]?.takeIf { it.isNotBlank() },
            defaultHeaders = if (defaultHeaders.isNotEmpty()) defaultHeaders else null
        ) ?: return "Error: module '$moduleId' not found"
        return "Module '${module.name}' updated (id=${module.id})"
    }

    private fun executeDeleteModule(args: Map<String, String>): String {
        val moduleId = args["module_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module_id is required"
        return if (moduleStorage.delete(moduleId)) "Module $moduleId deleted."
        else "Error: module $moduleId not found."
    }

    /**
     * Normalize AI argument keys to match tool parameter definitions.
     * Handles cases where AI uses "id" instead of "app_id", "file" instead of "path", etc.
     *
     * Matching priority:
     * 1. Exact match (already correct)
     * 2. Case-insensitive match
     * 3. AI key is a suffix of param name (e.g. "id" matches "app_id")
     * 4. Param name is a suffix of AI key (e.g. "file_path" matches "path")
     * 5. Normalized form match (strip underscores/hyphens)
     */
    private fun normalizeArgs(toolName: String, args: Map<String, String>): Map<String, String> {
        val toolDef = toolDefs.find { it.name == toolName } ?: return args
        if (toolDef.parameters.isEmpty()) return args

        val paramNames = toolDef.parameters.map { it.name }
        val normalized = mutableMapOf<String, String>()

        for ((aiKey, value) in args) {
            // 1. Exact match — keep as is
            if (aiKey in paramNames) {
                normalized[aiKey] = value
                continue
            }

            // Find best matching param name
            val matched = paramNames.find { p ->
                // 2. Case-insensitive
                p.equals(aiKey, ignoreCase = true)
            } ?: paramNames.find { p ->
                // 3. AI key is a suffix of param name: "id" matches "app_id"
                val aiNorm = aiKey.lowercase().replace("-", "_")
                val pNorm = p.lowercase()
                pNorm.endsWith("_$aiNorm") || pNorm.endsWith(aiNorm)
            } ?: paramNames.find { p ->
                // 4. Param name is a suffix of AI key: "file_path" matches "path"
                val aiNorm = aiKey.lowercase().replace("-", "_")
                val pNorm = p.lowercase()
                aiNorm.endsWith("_$pNorm") || aiNorm.endsWith(pNorm)
            } ?: paramNames.find { p ->
                // 5. Same after stripping all separators: "oldtext" matches "old_text"
                val aiNorm = aiKey.lowercase().replace(Regex("[_\\-\\s]"), "")
                val pNorm = p.lowercase().replace(Regex("[_\\-\\s]"), "")
                aiNorm == pNorm
            }

            normalized[matched ?: aiKey] = value
        }

        return normalized
    }
}
