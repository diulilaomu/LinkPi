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
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.example.link_pi.bridge.RuntimeErrorCollector
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.util.SecurityUtils
import com.example.link_pi.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
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
    /** Application context for headless operations (e.g., HeadlessWebViewRunner). */
    val appContext: Context get() = context.applicationContext
    /** Current workspace app ID — set before orchestrator runs. */
    var currentAppId: String = "default"
    /** Hint from conversation context — latest relevant app ID for auto-resolve. */
    var latestAppHint: String? = null
    /** Cached result of last list_saved_apps call for auto-resolve. */

    /** Long-term memory storage. */
    val memoryStorage = MemoryStorage(context)
    /** Dynamic module storage. */
    val moduleStorage = ModuleStorage(context)
    /** Python sandbox runner for module scripts. */
    val pythonRunner = PythonRunner(context).also { moduleStorage.pythonRunner = it }
    /** Module service lifecycle manager. */
    val moduleService = ModuleService(moduleStorage)
    /** SSH session manager. */
    val sshManager = SshManager.getInstance(context)
    /** Pending workbench redirect — set when AI calls launch_workbench. */
    var pendingWorkbenchRedirect: WorkbenchRedirect? = null
    /** Plan store — caches planning artifacts for cross-phase retrieval via read_plan tool. */
    val planStore = mutableMapOf<String, String>()
    /** Progress callback for long-running tools (SSH exec). Set by orchestrator. */
    var toolProgressCallback: ((AgentStep) -> Unit)? = null
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Get an HTTPS client that trusts user-approved certificates. */
    private fun getSecureHttpClient(): OkHttpClient {
        val store = com.example.link_pi.data.TrustedCertStore(context)
        val sslCtx = com.example.link_pi.data.TrustedCertStore.buildSSLContext(store)
        val tm = com.example.link_pi.data.TrustedCertStore.buildTrustManager(store)
        return if (sslCtx != null && tm != null) {
            httpClient.newBuilder()
                .sslSocketFactory(sslCtx.socketFactory, tm)
                .hostnameVerifier(com.example.link_pi.data.TrustedCertStore.buildHostnameVerifier(store))
                .build()
        } else httpClient
    }

    /** Common tool name aliases the AI may hallucinate. */
    private val TOOL_ALIASES = mapOf(
        // camelCase aliases
        "readFile" to "read_file",
        "listFiles" to "list_files",
        "deleteFile" to "delete_path",
        "editFile" to "edit_file",
        // Legacy names → kept in when() block for argument compatibility
        // Memory aliases
        "search_memory" to "memory_search",
        "save_memory" to "memory_save",
        "list_memory" to "memory_list",
        "delete_memory" to "memory_delete",
        "update_memory" to "memory_update",
        // App aliases
        "list_apps" to "list_saved_apps",
        "open_workspace" to "open_app_workspace",
        // SSH aliases
        "sshConnect" to "ssh_connect",
        "sshExec" to "ssh_exec",
        "ssh_execute" to "ssh_exec",
        "sshDisconnect" to "ssh_disconnect",
        "ssh_close" to "ssh_disconnect",
        // Workbench aliases
        "create_app" to "launch_workbench",
        "open_workbench" to "launch_workbench",
    )

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
            "web_search", "搜索互联网获取实时信息，当需要最新资讯、不确定的事实或用户明确要求搜索时使用",
            listOf(
                ToolParam("query", "string", "搜索关键词"),
                ToolParam("count", "string", "返回结果数量，默认5，最大10（可选）")
            )
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
            "write_file", "写入文件（不存在则自动创建，含父目录）。默认覆写；mode=append 追加到末尾",
            listOf(
                ToolParam("path", "string", "相对路径，如 index.html 或 css/style.css"),
                ToolParam("content", "string", "文件内容"),
                ToolParam("mode", "string", "写入模式: overwrite（默认）/ append", required = false)
            )
        ),
        ToolDef(
            "read_file", "读取工作空间中的文件内容（带行号）",
            listOf(
                ToolParam("path", "string", "相对路径"),
                ToolParam("start_line", "number", "起始行号（1开始），不填则从头读", required = false),
                ToolParam("end_line", "number", "结束行号（含），不填则读到尾", required = false)
            )
        ),
        ToolDef(
            "edit_file", "编辑文件。三种模式：(1) command=replace_text 定位替换文本，需 old_text+new_text（支持9级级联匹配）；(2) command=replace_lines 按行号替换，需 start_line+end_line+new_content；(3) command=insert 在指定行前插入，需 line+content。⚠ 必须先 read_file 读取文件后使用",
            listOf(
                ToolParam("path", "string", "相对路径"),
                ToolParam("command", "string", "操作: replace_text / replace_lines / insert"),
                ToolParam("old_text", "string", "要替换的原文本（replace_text 时必需）", required = false),
                ToolParam("new_text", "string", "替换后的新文本（replace_text 时必需）", required = false),
                ToolParam("start_line", "number", "起始行号（replace_lines 时必需，1开始，含）", required = false),
                ToolParam("end_line", "number", "结束行号（replace_lines 时必需，含）", required = false),
                ToolParam("new_content", "string", "新内容（replace_lines/insert 时）", required = false),
                ToolParam("line", "number", "插入位置行号（insert 时必需，1开始）", required = false)
            )
        ),
        ToolDef(
            "list_files", "列出工作空间中的文件和文件夹。设 detail=true 获取详细信息（大小、行数等）",
            listOf(
                ToolParam("path", "string", "相对路径，留空列出根目录", required = false),
                ToolParam("detail", "boolean", "true=返回详细信息（大小、行数、修改时间），默认false", required = false)
            )
        ),
        ToolDef(
            "delete_path", "删除文件或文件夹（文件夹会递归删除所有内容）",
            listOf(ToolParam("path", "string", "相对路径"))
        ),
        ToolDef(
            "rename_file", "重命名/移动文件或文件夹。设 copy=true 为复制而非移动",
            listOf(
                ToolParam("old_path", "string", "原路径"),
                ToolParam("new_path", "string", "新路径"),
                ToolParam("copy", "boolean", "true=复制 false=移动（默认）", required = false)
            )
        ),
        ToolDef(
            "undo_file", "撤销文件的最近一次修改。设 list_only=true 仅列出可用快照",
            listOf(
                ToolParam("path", "string", "文件相对路径"),
                ToolParam("list_only", "boolean", "true=仅列出快照版本 false=执行撤销（默认）", required = false)
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
            "read_truncated_output", "读取之前被截断的工具输出的完整内容。当工具输出过长被截断时，截断提示会包含缓存文件名",
            listOf(ToolParam("filename", "string", "缓存文件名（从截断提示中获取）"))
        ),
        ToolDef(
            "memory_update", "更新一条已有记忆的内容或标签",
            listOf(
                ToolParam("id", "string", "记忆ID"),
                ToolParam("content", "string", "新内容（留空则不修改）", required = false),
                ToolParam("tags", "string", "新标签（留空则不修改）", required = false)
            )
        ),

        // ── Module Tools (Python Service Modules) ──
        ToolDef(
            "create_module", "创建一个新的 Python 服务模块。可从内置模板（http_server/tcp_server/udp_server）快速创建，或手动定义",
            listOf(
                ToolParam("name", "string", "模块名称"),
                ToolParam("description", "string", "模块描述"),
                ToolParam("service_type", "string", "服务类型: HTTP（默认）、TCP、UDP", required = false),
                ToolParam("template", "string", "从内置模板创建: http_server, tcp_server, udp_server", required = false),
                ToolParam("port", "string", "默认端口号（0=自动分配 8100-8199）", required = false),
                ToolParam("main_script", "string", "入口脚本文件名（默认 server.py）", required = false),
                ToolParam("instructions", "string", "模块使用说明", required = false)
            )
        ),
        ToolDef(
            "start_module", "启动模块的 Python 服务脚本",
            listOf(
                ToolParam("module", "string", "模块名称或ID"),
                ToolParam("port", "string", "指定端口号（可选，默认自动分配）", required = false)
            )
        ),
        ToolDef(
            "stop_module", "停止运行中的模块服务",
            listOf(ToolParam("module", "string", "模块名称或ID"))
        ),
        ToolDef(
            "call_module", "调用运行中的 HTTP 模块服务的某个路径",
            listOf(
                ToolParam("module", "string", "模块名称或ID"),
                ToolParam("path", "string", "HTTP 路径（如 /hello、/process）"),
                ToolParam("method", "string", "HTTP 方法: GET/POST/PUT/DELETE，默认 GET", required = false),
                ToolParam("body", "string", "请求体 JSON（POST/PUT 时使用）", required = false)
            )
        ),
        ToolDef(
            "list_modules", "列出所有模块及其运行状态",
            emptyList()
        ),
        ToolDef(
            "update_module", "更新模块的基本信息",
            listOf(
                ToolParam("module_id", "string", "模块ID"),
                ToolParam("name", "string", "新名称", required = false),
                ToolParam("description", "string", "新描述", required = false),
                ToolParam("instructions", "string", "新的使用说明", required = false)
            )
        ),
        ToolDef(
            "delete_module", "删除一个模块（会先停止服务）",
            listOf(ToolParam("module_id", "string", "模块ID"))
        ),
        ToolDef(
            "write_module_script", "写入或更新模块包内的Python脚本文件",
            listOf(
                ToolParam("module_id", "string", "模块ID"),
                ToolParam("filename", "string", "脚本文件名（如 server.py, utils.py）"),
                ToolParam("content", "string", "Python脚本内容")
            )
        ),
        ToolDef(
            "read_module_script", "读取模块包内的Python脚本文件内容",
            listOf(
                ToolParam("module_id", "string", "模块ID"),
                ToolParam("filename", "string", "脚本文件名（留空列出所有脚本）", required = false)
            )
        ),
        ToolDef(
            "test_module_script", "在沙箱中测试模块Python脚本的指定函数",
            listOf(
                ToolParam("module_id", "string", "模块ID"),
                ToolParam("filename", "string", "脚本文件名"),
                ToolParam("function", "string", "要测试的函数名"),
                ToolParam("params", "string", "测试参数JSON对象", required = false)
            )
        ),
        ToolDef(
            "list_module_templates", "列出可用的内置模块模板（HTTP/TCP/UDP 服务器）",
            emptyList()
        ),

        // ── Coding Tools ──
        ToolDef(
            "search", "搜索文本或正则表达式。指定 path 搜索单个文件，留空搜索整个工作空间",
            listOf(
                ToolParam("pattern", "string", "搜索模式（正则表达式或纯文本）"),
                ToolParam("path", "string", "限定搜索的文件路径（留空搜索整个工作空间）", required = false),
                ToolParam("is_regex", "boolean", "true=正则匹配 false=纯文本，默认true", required = false),
                ToolParam("file_filter", "string", "文件名过滤（如 *.js），仅搜索工作空间时有效", required = false)
            )
        ),
        ToolDef(
            "validate", "校验文件语法。自动根据扩展名选择校验器：.html→HTML校验，.js→JS校验，.css→CSS校验",
            listOf(ToolParam("path", "string", "文件相对路径"))
        ),
        ToolDef(
            "get_runtime_errors", "获取当前应用WebView中的JS运行时错误和console日志，用于诊断和修复",
            listOf(ToolParam("clear", "boolean", "获取后是否清除，默认true", required = false))
        ),
        ToolDef(
            "diff_file", "对比文件当前内容与最近快照的差异，输出unified diff格式",
            listOf(ToolParam("path", "string", "文件相对路径"))
        ),

        // ── Workspace Inspection Tools ──
        ToolDef(
            "inspect_workspace", "分析工作区架构：文件依赖图、导出函数/类索引、高影响文件、引用完整性检查。不会修改任何文件",
            emptyList()
        ),
        ToolDef(
            "read_plan", "读取规划阶段缓存的架构蓝图或修改计划。规划完成后系统自动缓存，生成阶段可调此工具获取",
            listOf(ToolParam("key", "string", "缓存键: architecture（架构蓝图）、modification_plan（修改计划）。留空返回全部", required = false))
        ),

        // ── Workbench ──
        ToolDef(
            "launch_workbench", "当用户要求创建或修改应用时，调用此工具弹出工作台引导卡片，让用户确认后进入工作台。这是创建/修改应用的唯一入口",
            listOf(
                ToolParam("title", "string", "应用标题（简短概括，如'天气应用'）"),
                ToolParam("prompt", "string", "用户的完整需求描述"),
                ToolParam("app_id", "string", "如果是修改已有应用，传入app_id", required = false)
            )
        ),

        // ── SSH Tools ──
        ToolDef(
            "ssh_connect", "连接SSH服务器。三种认证方式：1)credential_name从凭据管理器获取用户名+密码 2)username+password 3)username+private_key。推荐优先使用凭据管理器",
            listOf(
                ToolParam("host", "string", "SSH服务器地址（IP或域名）"),
                ToolParam("port", "string", "端口号，默认22", required = false),
                ToolParam("username", "string", "用户名（使用credential_name时可省略，会从凭据获取）", required = false),
                ToolParam("password", "string", "密码（与private_key/credential_name三选一）", required = false),
                ToolParam("private_key", "string", "SSH私钥内容（PEM格式）", required = false),
                ToolParam("credential_name", "string", "凭据名称，从凭据管理器自动获取用户名和密码", required = false)
            )
        ),
        ToolDef(
            "ssh_exec", "在SSH服务器上执行命令，返回stdout/stderr和退出码",
            listOf(
                ToolParam("session_id", "string", "SSH会话ID（从ssh_connect获取）"),
                ToolParam("command", "string", "要执行的Shell命令"),
                ToolParam("timeout", "string", "命令超时（毫秒），默认300000，最大600000", required = false)
            )
        ),
        ToolDef(
            "ssh_disconnect", "断开SSH连接",
            listOf(ToolParam("session_id", "string", "SSH会话ID"))
        ),
        ToolDef(
            "ssh_upload", "通过SFTP上传文件内容到远程服务器",
            listOf(
                ToolParam("session_id", "string", "SSH会话ID"),
                ToolParam("content", "string", "要上传的文件内容"),
                ToolParam("remote_path", "string", "远程文件路径（如 /home/user/file.txt）")
            )
        ),
        ToolDef(
            "ssh_download", "通过SFTP从远程服务器下载文件内容",
            listOf(
                ToolParam("session_id", "string", "SSH会话ID"),
                ToolParam("remote_path", "string", "远程文件路径")
            )
        ),
        ToolDef(
            "ssh_list_remote", "列出远程服务器目录内容（SFTP）",
            listOf(
                ToolParam("session_id", "string", "SSH会话ID"),
                ToolParam("path", "string", "远程目录路径，默认当前目录", required = false)
            )
        ),
        ToolDef(
            "ssh_list_sessions", "列出所有活跃的SSH会话",
            emptyList()
        ),
        ToolDef(
            "ssh_port_forward", "设置SSH本地端口转发（SSH隧道）",
            listOf(
                ToolParam("session_id", "string", "SSH会话ID"),
                ToolParam("local_port", "string", "本地监听端口"),
                ToolParam("remote_host", "string", "远程目标主机（通常为127.0.0.1或localhost）"),
                ToolParam("remote_port", "string", "远程目标端口")
            )
        )
    )

    suspend fun execute(call: ToolCall): ToolResult {
        // SSH commands need much longer timeouts (up to 10 min)
        val timeoutMs = if (call.toolName in SSH_LONG_TOOLS) 600_000L else 60_000L
        return withTimeoutOrNull(timeoutMs) {
            executeInternal(call)
        } ?: ToolResult(call.toolName, false, "Error: 工具执行超时 (${timeoutMs / 1000}s)")
    }

    private val SSH_LONG_TOOLS = setOf("ssh_exec")

    private suspend fun executeInternal(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        try {
            // Normalize argument keys to match tool parameter definitions
            val args = normalizeArgs(call.toolName, call.arguments)
            // Auto-correct common tool name aliases the AI may hallucinate
            val toolName = TOOL_ALIASES.getOrDefault(call.toolName, call.toolName)
            val normalizedCall = call.copy(toolName = toolName, arguments = args)
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
                "web_search" -> executeWebSearch(args)
                "get_current_time" -> executeGetTime()
                "calculate" -> executeCalculate(args)
                // ── Merged file system tools ──
                "write_file" -> {
                    val path = args["path"] ?: ""
                    val content = args["content"] ?: ""
                    when (args["mode"]?.lowercase()) {
                        "append" -> workspaceManager.appendFile(currentAppId, path, content)
                        else -> workspaceManager.writeFile(currentAppId, path, content)
                    }
                }
                "read_file" -> {
                    val startLine = args["start_line"]?.toIntOrNull()
                    val endLine = args["end_line"]?.toIntOrNull()
                    if (startLine != null && endLine != null) {
                        workspaceManager.readFileLines(currentAppId, args["path"] ?: "", startLine, endLine)
                    } else {
                        workspaceManager.readFile(currentAppId, args["path"] ?: "")
                    }
                }
                "edit_file" -> {
                    val path = args["path"] ?: ""
                    when (args["command"]?.lowercase()) {
                        "replace_text", "text" -> workspaceManager.replaceInFile(currentAppId, path, args["old_text"] ?: "", args["new_text"] ?: "")
                        "replace_lines", "lines" -> workspaceManager.replaceLines(currentAppId, path, args["start_line"]?.toIntOrNull() ?: 0, args["end_line"]?.toIntOrNull() ?: 0, args["new_content"] ?: "")
                        "insert" -> workspaceManager.insertLines(currentAppId, path, args["line"]?.toIntOrNull() ?: 1, args["new_content"] ?: args["content"] ?: "")
                        else -> "Error: edit_file 的 command 参数必须是 replace_text / replace_lines / insert，收到: ${args["command"]}"
                    }
                }
                "list_files" -> {
                    val path = args["path"] ?: ""
                    if (args["detail"]?.toBooleanStrictOrNull() == true) {
                        workspaceManager.fileInfo(currentAppId, path)
                    } else {
                        workspaceManager.listFiles(currentAppId, path)
                    }
                }
                "delete_path" -> {
                    val path = args["path"] ?: ""
                    val fileResult = workspaceManager.deleteFile(currentAppId, path)
                    if (fileResult.startsWith("Error:")) {
                        val dirResult = workspaceManager.deleteDirectory(currentAppId, path)
                        if (!dirResult.startsWith("Error:")) dirResult else fileResult
                    } else fileResult
                }
                "rename_file" -> {
                    if (args["copy"]?.toBooleanStrictOrNull() == true) {
                        workspaceManager.copyFile(currentAppId, args["old_path"] ?: args["source"] ?: "", args["new_path"] ?: args["destination"] ?: "")
                    } else {
                        workspaceManager.renameFile(currentAppId, args["old_path"] ?: "", args["new_path"] ?: "")
                    }
                }
                "undo_file" -> {
                    val path = args["path"] ?: ""
                    if (args["list_only"]?.toBooleanStrictOrNull() == true) {
                        workspaceManager.listSnapshots(currentAppId, path)
                    } else {
                        workspaceManager.undoFile(currentAppId, path)
                    }
                }
                "open_app_workspace" -> executeOpenAppWorkspace(args)
                // ── Merged coding tools ──
                "search" -> {
                    val path = args["path"]
                    val pattern = args["pattern"] ?: ""
                    val isRegex = args["is_regex"]?.toBooleanStrictOrNull() ?: true
                    if (!path.isNullOrBlank()) {
                        workspaceManager.grepFile(currentAppId, path, pattern, isRegex)
                    } else {
                        workspaceManager.grepWorkspace(currentAppId, pattern, isRegex, args["file_filter"] ?: "")
                    }
                }
                "validate" -> {
                    val path = args["path"] ?: ""
                    when {
                        path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true) -> workspaceManager.validateHtml(currentAppId, path)
                        path.endsWith(".css", ignoreCase = true) -> workspaceManager.validateCss(currentAppId, path)
                        else -> workspaceManager.validateJs(currentAppId, path)
                    }
                }
                "get_runtime_errors" -> executeGetRuntimeErrors(args)
                "diff_file" -> workspaceManager.diffFile(currentAppId, args["path"] ?: "")
                // ── Workspace inspection tools ──
                "inspect_workspace" -> executeInspectWorkspace()
                "read_plan" -> executeReadPlan(args)
                // ── Legacy tool names (backward compat for AI hallucinations) ──
                "create_file" -> workspaceManager.writeFile(currentAppId, args["path"] ?: "", args["content"] ?: "")
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
                "insert_lines" -> workspaceManager.insertLines(currentAppId, args["path"] ?: "", args["line"]?.toIntOrNull() ?: 1, args["content"] ?: "")
                "create_directory" -> workspaceManager.createDirectory(currentAppId, args["path"] ?: "")
                "list_workspace_files" -> workspaceManager.listFiles(currentAppId, args["path"] ?: "")
                "delete_workspace_file" -> workspaceManager.deleteFile(currentAppId, args["path"] ?: "")
                "delete_directory" -> workspaceManager.deleteDirectory(currentAppId, args["path"] ?: "")
                "copy_file" -> workspaceManager.copyFile(currentAppId, args["source"] ?: "", args["destination"] ?: "")
                "file_info" -> workspaceManager.fileInfo(currentAppId, args["path"] ?: "")
                "list_snapshots" -> workspaceManager.listSnapshots(currentAppId, args["path"] ?: "")
                "grep_file" -> workspaceManager.grepFile(currentAppId, args["path"] ?: "", args["pattern"] ?: "", args["is_regex"]?.toBooleanStrictOrNull() ?: true)
                "grep_workspace" -> workspaceManager.grepWorkspace(currentAppId, args["pattern"] ?: "", args["is_regex"]?.toBooleanStrictOrNull() ?: true, args["file_filter"] ?: "")
                "validate_html" -> workspaceManager.validateHtml(currentAppId, args["path"] ?: "")
                "validate_js" -> {
                    val path = args["path"] ?: ""
                    if (path.endsWith(".css", ignoreCase = true)) workspaceManager.validateCss(currentAppId, path) else workspaceManager.validateJs(currentAppId, path)
                }
                // Memory tools
                "memory_save" -> executeMemorySave(args)
                "memory_search" -> executeMemorySearch(args)
                "memory_list" -> executeMemoryList()
                "memory_delete" -> executeMemoryDelete(args)
                "memory_update" -> executeMemoryUpdate(args)
                "read_truncated_output" -> {
                    val filename = args["filename"] ?: return@withContext ToolResult(call.toolName, false, "Error: missing filename parameter")
                    workspaceManager.readTruncatedOutput(filename) ?: "Error: cached output not found: $filename"
                }
                // Module tools
                "create_module" -> executeCreateModule(args)
                "start_module" -> executeStartModule(args)
                "stop_module" -> executeStopModule(args)
                "call_module" -> executeCallModule(args)
                "list_modules" -> moduleService.statusJson()
                "update_module" -> executeUpdateModule(args)
                "delete_module" -> executeDeleteModule(args)
                "write_module_script" -> executeWriteModuleScript(args)
                "read_module_script" -> executeReadModuleScript(args)
                "test_module_script" -> executeTestModuleScript(args)
                "list_module_templates" -> ModuleFactory.listTemplatesJson()
                // Workbench
                "launch_workbench" -> executeLaunchWorkbench(args)
                // SSH tools
                "ssh_connect" -> executeSshConnect(args)
                "ssh_exec" -> executeSshExec(args)
                "ssh_disconnect" -> executeSshDisconnect(args)
                "ssh_upload" -> executeSshUpload(args)
                "ssh_download" -> executeSshDownload(args)
                "ssh_list_remote" -> executeSshListRemote(args)
                "ssh_list_sessions" -> executeSshListSessions()
                "ssh_port_forward" -> executeSshPortForward(args)
                else -> return@withContext ToolResult(normalizedCall.toolName, false, "Error: 未知工具: ${normalizedCall.toolName}")
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
        "write_file", "read_file", "edit_file", "list_files",
        "delete_path", "rename_file", "undo_file", "search",
        "validate", "diff_file",
        // Legacy tool names
        "create_file", "append_file", "read_workspace_file",
        "replace_in_file", "replace_lines", "create_directory",
        "list_workspace_files", "delete_workspace_file", "delete_directory",
        "copy_file", "grep_file", "grep_workspace",
        "insert_lines", "file_info", "list_snapshots",
        "validate_html", "validate_js"
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
        return "$result\n\n[提示] 当前工作空间为空。如需访问已有应用的文件，请先调用 open_app_workspace。已保存的应用: $appList"
    }

    private fun executeGetRuntimeErrors(args: Map<String, String>): String {
        val errors = RuntimeErrorCollector.getErrors(currentAppId)
        val logs = RuntimeErrorCollector.getLogs(currentAppId)
        val shouldClear = args["clear"]?.toBooleanStrictOrNull() ?: true
        if (shouldClear) RuntimeErrorCollector.clear(currentAppId)

        val parts = mutableListOf<String>()
        if (errors.isNotEmpty()) {
            parts.add("━━ 运行时错误 (共${errors.size}个) ━━\n" +
                errors.mapIndexed { i, e -> "${i + 1}. $e" }.joinToString("\n"))
        }
        if (logs.isNotEmpty()) {
            parts.add("━━ Console日志 (共${logs.size}条) ━━\n" +
                logs.takeLast(30).joinToString("\n"))
        }
        if (parts.isEmpty()) return "没有运行时错误或日志"
        return parts.joinToString("\n\n")
    }

    /** inspect_workspace — 返回架构分析结果（依赖图、导出索引、高影响文件、引用检查） */
    private fun executeInspectWorkspace(): String {
        val profile = ArchitectureAnalyzer.analyze(workspaceManager, currentAppId)
            ?: return "工作区为空或无法分析"
        val summary = ArchitectureAnalyzer.buildArchitectureSummary(profile, 4000)
        val missingRefs = ArchitectureAnalyzer.validateReferences(profile)
        return buildString {
            append(summary)
            if (missingRefs.isNotEmpty()) {
                appendLine()
                appendLine("**⚠ 引用完整性问题：**")
                missingRefs.take(10).forEach { appendLine("  - $it") }
            }
        }.trimEnd()
    }

    /** read_plan — 读取规划阶段缓存的架构蓝图或修改计划 */
    private fun executeReadPlan(args: Map<String, String>): String {
        if (planStore.isEmpty()) return "当前没有缓存的规划内容。规划阶段完成后才会有缓存。"
        val key = args["key"]
        if (key.isNullOrBlank()) {
            // 返回所有缓存内容
            return planStore.entries.joinToString("\n\n") { (k, v) ->
                "### $k\n$v"
            }
        }
        return planStore[key] ?: "未找到键 '$key'。可用的键: ${planStore.keys.joinToString(", ")}"
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
            return "未授予位置权限"
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
            "无法获取位置信息"
        }
    }

    private fun executeVibrate(args: Map<String, String>): String {
        val ms = (args["milliseconds"]?.toLongOrNull() ?: 200).coerceIn(0, 5000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        return "已振动 ${ms}ms"
    }

    private suspend fun executeShowToast(args: Map<String, String>): String {
        val message = args["message"] ?: return "Error: 未提供消息内容"
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        return "已显示 Toast: $message"
    }

    private suspend fun executeWriteClipboard(args: Map<String, String>): String {
        val text = args["text"] ?: return "Error: 未提供文本内容"
        withContext(Dispatchers.Main) {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("text", text))
        }
        return "已复制到剪贴板"
    }

    private fun executeSaveData(args: Map<String, String>): String {
        val key = args["key"] ?: return "Error: 未提供 key"
        val value = args["value"] ?: return "Error: 未提供 value"
        val prefKey = "${currentAppId}_$key"
        context.getSharedPreferences("agent_data", Context.MODE_PRIVATE)
            .edit().putString(prefKey, value).apply()
        return "数据已保存: $key"
    }

    private fun executeLoadData(args: Map<String, String>): String {
        val key = args["key"] ?: return "Error: 未提供 key"
        val prefKey = "${currentAppId}_$key"
        val value = context.getSharedPreferences("agent_data", Context.MODE_PRIVATE)
            .getString(prefKey, null)
        return value ?: "(未找到 key: $key 的数据)"
    }

    private fun executeListApps(): String {
        val apps = miniAppStorage.loadAll()
        if (apps.isEmpty()) return "没有已保存的应用"
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
        val url = args["url"] ?: return "Error: 未提供 URL"
        // Security: only allow HTTPS
        if (!url.startsWith("https://")) {
            return "Error: 仅允许 HTTPS URL"
        }
        // Security: block requests to private/loopback IPs (SSRF protection)
        val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
        if (isPrivateHost(host)) {
            return "Error: 不允许访问私有/内网地址"
        }
        val request = Request.Builder().url(url).get().build()
        val response = getSecureHttpClient().newCall(request).execute()
        val body = response.body?.string() ?: ""
        // Limit response size
        return if (body.length > 4000) body.take(4000) + "\n...(已截断)" else body
    }

    private fun executeWebSearch(args: Map<String, String>): String {
        val query = args["query"] ?: return "Error: 未提供搜索关键词"
        val count = (args["count"]?.toIntOrNull() ?: 5).coerceIn(1, 10)
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .get()
            .build()
        val response = httpClient.newCall(request).execute()
        val html = response.body?.string() ?: return "Error: 搜索请求失败"
        return parseDuckDuckGoResults(html, count)
    }

    private fun parseDuckDuckGoResults(html: String, count: Int): String {
        val results = mutableListOf<String>()
        // Match each search result block: title + URL + snippet
        val resultRegex = Regex(
            """<a[^>]+class="result__a"[^>]*href="([^"]*)"[^>]*>(.+?)</a>[\s\S]*?<a[^>]+class="result__snippet"[^>]*>(.*?)</a>"""
        )
        for (match in resultRegex.findAll(html)) {
            if (results.size >= count) break
            val rawUrl = match.groupValues[1]
            val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            val snippet = match.groupValues[3].replace(Regex("<[^>]+>"), "").trim()
            // DuckDuckGo wraps URLs in a redirect; extract the actual URL
            val actualUrl = try {
                val params = java.net.URI(rawUrl).query?.split("&") ?: emptyList()
                val uddg = params.firstOrNull { it.startsWith("uddg=") }
                if (uddg != null) java.net.URLDecoder.decode(uddg.substringAfter("uddg="), "UTF-8") else rawUrl
            } catch (_: Exception) { rawUrl }
            results.add("${results.size + 1}. $title\n   $actualUrl\n   $snippet")
        }
        return if (results.isEmpty()) {
            "未找到相关搜索结果"
        } else {
            "搜索结果:\n\n" + results.joinToString("\n\n")
        }
    }

    private fun executeGetTime(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    /** Check if a hostname resolves to a private/loopback IP range (SSRF protection). */
    private fun isPrivateHost(host: String): Boolean = SecurityUtils.isPrivateHost(host)

    private fun executeCalculate(args: Map<String, String>): String {
        val expr = args["expression"] ?: return "Error: 未提供表达式"
        // Simple safe evaluator for basic math
        return try {
            val sanitized = expr.replace(Regex("[^0-9+\\-*/().%\\s]"), "")
            if (sanitized != expr.trim()) return "Error: 表达式包含无效字符"
            val result = evalMath(sanitized)
            result.toBigDecimal().stripTrailingZeros().toPlainString()
        } catch (e: Exception) {
            "Error: 计算错误: ${e.message}"
        }
    }

    private fun evalMath(expr: String): Double {
        return object {
            var pos = 0
            val str = expr.replace(" ", "")

            fun parse(): Double {
                val result = parseExpr()
                if (pos < str.length) throw RuntimeException("意外的字符: ${str[pos]}")
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
                        '/' -> { pos++; val d = parseFactor(); if (d == 0.0) throw ArithmeticException("除数不能为零"); x /= d }
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
                if (start == pos) throw RuntimeException("位置 $pos 处缺少数字")
                return str.substring(start, pos).toDouble()
            }
        }.parse()
    }

    private fun executeOpenAppWorkspace(args: Map<String, String>): String {
        val appId = args["app_id"]?.takeIf { it.isNotBlank() }
        val appName = args["app_name"]?.takeIf { it.isNotBlank() }

        val allApps = miniAppStorage.loadAll()
        if (allApps.isEmpty()) return "Error: 没有已保存的应用，无法修改。"

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
        return """Error: 需要 app_id。请从以下列表中选择一个并重新调用:
$examples
示例: open_app_workspace(app_id=\"<从上方复制id>\")""".trimIndent()
    }

    private fun openAppById(app: MiniApp): String {
        currentAppId = app.id
        workspaceManager.clearReadTracking()  // Reset read-before-write tracking for new workspace

        if (app.isWorkspaceApp && workspaceManager.hasFiles(app.id)) {
            // Workspace app — files already exist
            val files = workspaceManager.getAllFiles(app.id)
            return "已打开工作空间: ${app.name}\n文件列表:\n${files.joinToString("\n") { "  $it" }}"
        } else if (app.htmlContent.isNotBlank()) {
            // Single-file app — create workspace from htmlContent
            workspaceManager.writeFile(app.id, "index.html", app.htmlContent)
            return "已打开工作空间: ${app.name}\n已将单文件应用转换为工作空间。\n文件列表:\n  index.html"
        }
        return "已打开工作空间: ${app.name} (空)"
    }

    // ── Memory Tool Implementations ──

    private fun executeMemorySave(args: Map<String, String>): String {
        val content = args["content"]?.takeIf { it.isNotBlank() }
            ?: return "Error: content is required"
        val tags = args["tags"]?.split(Regex("[,，;；]+"))
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val memory = memoryStorage.save(content, tags)
        return "记忆已保存 (id=${memory.id}, tags=${memory.tags.joinToString(",")})"
    }

    private fun executeMemorySearch(args: Map<String, String>): String {
        val query = args["query"]?.takeIf { it.isNotBlank() }
            ?: return "Error: query is required"
        val results = memoryStorage.search(query)
        if (results.isEmpty()) return "未找到与“$query”相关的记忆"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return results.joinToString("\n---\n") { m ->
            "[${m.id}] (${m.tags.joinToString(",")}) ${sdf.format(java.util.Date(m.updatedAt))}\n${m.content}"
        }
    }

    private fun executeMemoryList(): String {
        val all = memoryStorage.listAll()
        if (all.isEmpty()) return "还没有保存任何记忆。"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return "总计: ${all.size}\n" + all.joinToString("\n") { m ->
            "[${m.id}] (${m.tags.joinToString(",")}) ${sdf.format(java.util.Date(m.updatedAt))} — ${m.content.take(80)}"
        }
    }

    private fun executeMemoryDelete(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: id is required"
        return if (memoryStorage.delete(id)) "记忆 $id 已删除。"
        else "Error: 记忆 $id 未找到。"
    }

    private fun executeMemoryUpdate(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: id is required"
        val content = args["content"]?.takeIf { it.isNotBlank() }
        val tags = args["tags"]?.split(Regex("[,，;；]+"))
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (content == null && tags == null) return "Error: 请提供 content 或 tags 来更新"
        val updated = memoryStorage.update(id, content, tags)
            ?: return "Error: 记忆 $id 未找到。"
        return "记忆 $id 已更新。tags=${updated.tags.joinToString(",")}"
    }

    // ── Module Tool Implementations ──

    private fun executeCreateModule(args: Map<String, String>): String {
        // Template-based creation
        val templateId = args["template"]?.takeIf { it.isNotBlank() }
        if (templateId != null) {
            val name = args["name"]?.takeIf { it.isNotBlank() } ?: ""
            val overrides = mutableMapOf<String, String>()
            args["description"]?.takeIf { it.isNotBlank() }?.let { overrides["description"] = it }
            args["port"]?.takeIf { it.isNotBlank() }?.let { overrides["port"] = it }
            args["instructions"]?.takeIf { it.isNotBlank() }?.let { overrides["instructions"] = it }
            val module = ModuleFactory.createFromTemplate(moduleStorage, templateId, name, overrides)
                ?: return "Error: 模板 '$templateId' 未找到。可用模板: ${ModuleFactory.templates.keys.joinToString()}"
            val scripts = moduleStorage.listScripts(module.id)
            return "模块已从模板创建: ${module.name} (id=${module.id}, type=${module.serviceType}, port=${module.defaultPort}, ${scripts.size} 个脚本文件)"
        }

        val name = args["name"]?.takeIf { it.isNotBlank() }
            ?: return "Error: name is required"
        val description = args["description"]?.takeIf { it.isNotBlank() } ?: ""
        val serviceType = args["service_type"]?.takeIf { it.isNotBlank() }?.uppercase() ?: "HTTP"
        val port = args["port"]?.toIntOrNull() ?: 0
        val mainScript = args["main_script"]?.takeIf { it.isNotBlank() } ?: "server.py"
        val instructions = args["instructions"]?.takeIf { it.isNotBlank() } ?: ""

        val module = moduleStorage.create(name, description, serviceType, port, mainScript, instructions)
        return "模块已创建: ${module.name} (id=${module.id}, type=${module.serviceType}, port=${module.defaultPort})"
    }

    private fun executeStartModule(args: Map<String, String>): String {
        val moduleRef = args["module"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module name or ID is required"
        val module = moduleStorage.loadById(moduleRef) ?: moduleStorage.findByName(moduleRef)
            ?: return "Error: 模块 '$moduleRef' 未找到"
        val port = args["port"]?.toIntOrNull() ?: 0
        val result = moduleService.start(module.id, port)
        return result.fold(
            onSuccess = { p -> "模块 '${module.name}' 已启动，端口: $p" },
            onFailure = { e -> "Error: ${e.message}" }
        )
    }

    private fun executeStopModule(args: Map<String, String>): String {
        val moduleRef = args["module"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module name or ID is required"
        val module = moduleStorage.loadById(moduleRef) ?: moduleStorage.findByName(moduleRef)
            ?: return "Error: 模块 '$moduleRef' 未找到"
        return if (moduleService.stop(module.id)) "模块 '${module.name}' 已停止"
        else "模块 '${module.name}' 未在运行"
    }

    private fun executeCallModule(args: Map<String, String>): String {
        val moduleRef = args["module"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module name or ID is required"
        val path = args["path"]?.takeIf { it.isNotBlank() } ?: "/"
        val method = args["method"]?.takeIf { it.isNotBlank() }?.uppercase() ?: "GET"
        val body = args["body"]?.takeIf { it.isNotBlank() }

        val module = moduleStorage.loadById(moduleRef) ?: moduleStorage.findByName(moduleRef)
            ?: return "Error: 模块 '$moduleRef' 未找到。请使用 list_modules 查看可用模块。"

        return moduleService.callHttp(module.id, path, method, body)
    }

    private fun executeUpdateModule(args: Map<String, String>): String {
        val moduleId = args["module_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module_id is required"
        val module = moduleStorage.updateModule(
            moduleId,
            name = args["name"]?.takeIf { it.isNotBlank() },
            description = args["description"]?.takeIf { it.isNotBlank() },
            instructions = args["instructions"]?.takeIf { it.isNotBlank() }
        ) ?: return "Error: 模块 '$moduleId' 未找到"
        return "模块 '${module.name}' 已更新 (id=${module.id})"
    }

    private fun executeDeleteModule(args: Map<String, String>): String {
        val moduleId = args["module_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module_id is required"
        // Stop running service first
        moduleService.stop(moduleId)
        return if (moduleStorage.delete(moduleId)) "模块 $moduleId 已删除。"
        else "Error: 模块 $moduleId 未找到。"
    }

    private fun executeWriteModuleScript(args: Map<String, String>): String {
        val moduleId = args["module_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module_id is required"
        val filename = args["filename"]?.takeIf { it.isNotBlank() }
            ?: return "Error: filename is required"
        val content = args["content"] ?: return "Error: content is required"
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return "Error: filename must be a simple name without path separators"
        }
        return try {
            val ok = moduleStorage.writeScript(moduleId, filename, content)
            if (ok) "脚本 '$filename' 已写入模块 '$moduleId'。"
            else "Error: 写入失败 — 模块不存在或文件名无效（必须以 .py 结尾）"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun executeReadModuleScript(args: Map<String, String>): String {
        val moduleId = args["module_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module_id is required"
        val filename = args["filename"]?.takeIf { it.isNotBlank() }
        if (filename == null) {
            val scripts = moduleStorage.listScripts(moduleId)
            return if (scripts.isEmpty()) "模块 '$moduleId' 没有脚本文件。"
            else "模块 '$moduleId' 的脚本文件:\n${scripts.joinToString("\n") { "  - $it" }}"
        }
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return "Error: filename must be a simple name without path separators"
        }
        return try {
            moduleStorage.readScript(moduleId, filename)
                ?: "Error: 脚本 '$filename' 不存在于模块 '$moduleId'。"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private suspend fun executeTestModuleScript(args: Map<String, String>): String {
        val moduleId = args["module_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: module_id is required"
        val filename = args["filename"]?.takeIf { it.isNotBlank() }
            ?: return "Error: filename is required"
        val function = args["function"]?.takeIf { it.isNotBlank() } ?: "main"
        val testInput = args["params"]?.takeIf { it.isNotBlank() } ?: "{}"
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return "Error: filename must be a simple name without path separators"
        }
        val modulesDir = moduleStorage.getScriptsDir(moduleId)
            ?: return "Error: 模块 '$moduleId' 不存在或脚本目录无法访问。"
        if (!modulesDir.exists()) return "Error: 模块 '$moduleId' 脚本目录不存在。"
        val scriptFile = java.io.File(modulesDir, filename)
        if (!scriptFile.exists()) return "Error: 脚本 '$filename' 不存在。"
        return try {
            val inputMap = try {
                val json = JSONObject(testInput)
                json.keys().asSequence().associateWith { json.optString(it, "") }
            } catch (_: Exception) { emptyMap() }
            val result = pythonRunner.call(
                scriptDir = modulesDir,
                scriptFile = filename,
                funcName = function,
                args = inputMap
            )
            if (result.success) {
                "✅ 测试通过\n📤 输出:\n${result.data.toString(2)}"
            } else {
                "❌ 测试失败\n📤 错误:\n${result.data.optString("error", "unknown error")}"
            }
        } catch (e: Exception) {
            "Error: 脚本执行失败 — ${e.message}"
        }
    }

    // ── Workbench Tool ──

    private fun executeLaunchWorkbench(args: Map<String, String>): String {
        val title = args["title"]?.takeIf { it.isNotBlank() } ?: "新应用"
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "Error: prompt is required"
        val appId = args["app_id"]?.takeIf { it.isNotBlank() }
        pendingWorkbenchRedirect = WorkbenchRedirect(title, prompt, appId)
        return "WORKBENCH_REDIRECT"
    }

    // ── SSH Tool Implementations ──

    private fun executeSshConnect(args: Map<String, String>): String {
        val host = args["host"]?.takeIf { it.isNotBlank() }
            ?: return "Error: host is required"
        val port = args["port"]?.toIntOrNull() ?: 22
        val username = args["username"]?.takeIf { it.isNotBlank() } ?: ""
        val password = args["password"]?.takeIf { it.isNotBlank() }
        val privateKey = args["private_key"]?.takeIf { it.isNotBlank() }
        val credentialName = args["credential_name"]?.takeIf { it.isNotBlank() }
        return sshManager.connect(host, port, username, password, privateKey, credentialName)
    }

    private fun executeSshExec(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: session_id is required"
        val command = args["command"]?.takeIf { it.isNotBlank() }
            ?: return "Error: command is required"
        val timeout = args["timeout"]?.toIntOrNull() ?: 300_000
        return sshManager.exec(sessionId, command, timeout.coerceIn(1000, 600_000)) { liveOutput ->
            toolProgressCallback?.invoke(
                AgentStep(StepType.TOOL_RESULT, "ssh> $command", liveOutput)
            )
        }
    }

    private fun executeSshDisconnect(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: session_id is required"
        return sshManager.disconnect(sessionId)
    }

    private fun executeSshUpload(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: session_id is required"
        val content = args["content"] ?: return "Error: content is required"
        val remotePath = args["remote_path"]?.takeIf { it.isNotBlank() }
            ?: return "Error: remote_path is required"
        return sshManager.upload(sessionId, content, remotePath)
    }

    private fun executeSshDownload(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: session_id is required"
        val remotePath = args["remote_path"]?.takeIf { it.isNotBlank() }
            ?: return "Error: remote_path is required"
        return sshManager.download(sessionId, remotePath)
    }

    private fun executeSshListRemote(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: session_id is required"
        val path = args["path"]?.takeIf { it.isNotBlank() } ?: "."
        return sshManager.listRemote(sessionId, path)
    }

    private fun executeSshListSessions(): String = sshManager.listSessions()

    private fun executeSshPortForward(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.takeIf { it.isNotBlank() }
            ?: return "Error: session_id is required"
        val localPort = args["local_port"]?.toIntOrNull()
            ?: return "Error: local_port is required"
        val remoteHost = args["remote_host"]?.takeIf { it.isNotBlank() }
            ?: return "Error: remote_host is required"
        val remotePort = args["remote_port"]?.toIntOrNull()
            ?: return "Error: remote_port is required"
        return sshManager.portForward(sessionId, localPort, remoteHost, remotePort)
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
