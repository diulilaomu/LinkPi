package com.example.link_pi.agent

import com.example.link_pi.workspace.WorkspaceManager

/**
 * 架构分析器 — 静态分析工作区代码，生成结构化的架构摘要。
 *
 * 能力：
 * - 解析文件间依赖关系（HTML→JS/CSS引用、JS→JS导入）
 * - 提取每个文件的函数/类/组件签名索引
 * - 识别数据流（NativeBridge调用、事件监听、全局变量）
 * - 生成影响分析（修改某文件会影响哪些其他文件）
 *
 * 输出用于注入系统提示，让AI具备架构级上下文感知。
 */
object ArchitectureAnalyzer {

    /** 文件分析结果 */
    data class FileProfile(
        val path: String,
        val lineCount: Int,
        val type: FileType,
        val exports: List<String>,       // 导出的函数、类、变量
        val imports: List<String>,        // 引用的其他文件（已解析为工作区路径）
        val unresolvedRefs: List<String>, // 未解析的本地引用（可能是缺失的文件）
        val bridgeApis: List<String>,     // 使用的 NativeBridge API
        val eventListeners: List<String>, // DOM 事件监听
        val globalVars: List<String>      // 全局变量/常量声明
    )

    enum class FileType { HTML, JS, CSS, JSON, OTHER }

    /** 项目类型 */
    enum class ProjectType(val label: String) {
        DASHBOARD("数据仪表盘"),
        FORM("表单应用"),
        GAME("游戏"),
        TOOL("工具应用"),
        LANDING("展示页面"),
        DATA_VIZ("数据可视化"),
        REALTIME("实时通信应用"),
        GENERIC("通用应用")
    }

    /** 项目复杂度 */
    enum class ProjectComplexity(val label: String) {
        SIMPLE("简单"),    // ≤3 files, ≤300 total lines
        MEDIUM("中等"),    // 4-8 files, or 300-1000 lines
        COMPLEX("复杂")   // >8 files, or >1000 lines
    }

    /** 架构分析结果 */
    data class ArchitectureProfile(
        val files: List<FileProfile>,
        val dependencyGraph: Map<String, Set<String>>,  // file → files it depends on
        val reverseDeps: Map<String, Set<String>>,      // file → files that depend on it
        val entryPoint: String?,
        val projectType: ProjectType = ProjectType.GENERIC,
        val complexity: ProjectComplexity = ProjectComplexity.SIMPLE
    )

    /**
     * 读取工作区所有文件内容（用于 CSS↔JS 交叉验证等）。
     * 与 analyze() 中的读取逻辑相同，限制 500KB 总量。
     */
    fun readFileContents(wm: WorkspaceManager, appId: String): Map<String, String> {
        val allFiles = wm.getAllFiles(appId)
        val fileContents = mutableMapOf<String, String>()
        var totalChars = 0
        for (path in allFiles) {
            if (totalChars > 500_000) break
            try {
                val content = wm.readEntryFile(appId, path) ?: continue
                fileContents[path] = content
                totalChars += content.length
            } catch (_: Exception) { }
        }
        return fileContents
    }

    /**
     * 分析工作区，生成架构剖面。
     * 纯本地计算，不调用AI、不写文件。
     */
    fun analyze(wm: WorkspaceManager, appId: String): ArchitectureProfile? {
        if (!wm.hasFiles(appId)) return null
        val allFiles = wm.getAllFiles(appId)
        if (allFiles.isEmpty()) return null

        val profiles = mutableListOf<FileProfile>()
        val depGraph = mutableMapOf<String, MutableSet<String>>()
        val fileContents = mutableMapOf<String, String>()

        // 读取所有文件（限制总量避免OOM）
        var totalChars = 0
        for (path in allFiles) {
            if (totalChars > 500_000) break // 500KB 上限
            try {
                val content = wm.readEntryFile(appId, path) ?: continue
                fileContents[path] = content
                totalChars += content.length
            } catch (_: Exception) { }
        }

        // 分析每个文件
        for ((path, content) in fileContents) {
            val type = classifyFile(path)
            val lines = content.lines()
            val deps = mutableSetOf<String>()
            val unresolved = mutableListOf<String>()

            val exports = when (type) {
                FileType.JS -> extractJsExports(content)
                FileType.CSS -> extractCssSelectors(content)
                FileType.HTML -> extractHtmlStructure(content)
                else -> emptyList()
            }

            // 解析依赖（同时收集无法解析的本地引用）
            val rawRefs = when (type) {
                FileType.HTML -> extractRawRefs(content, HTML_REF_EXTRACTORS)
                FileType.JS -> extractRawRefs(content, JS_REF_EXTRACTORS)
                FileType.CSS -> extractRawRefs(content, CSS_REF_EXTRACTORS)
                else -> emptyList()
            }
            for (ref in rawRefs) {
                if (isExternalUrl(ref)) continue
                val resolved = resolveLocalPath(ref, allFiles)
                if (resolved != null) {
                    deps.add(resolved)
                } else {
                    // 这是一个本地引用但工作区中找不到对应文件
                    val cleaned = ref.removePrefix("./").removePrefix("/").split("?")[0].split("#")[0]
                    if (cleaned.isNotBlank()) unresolved.add(cleaned)
                }
            }

            val bridgeApis = extractBridgeApis(content)
            val eventListeners = if (type == FileType.JS || type == FileType.HTML) extractEventListeners(content) else emptyList()
            val globalVars = if (type == FileType.JS) extractGlobalVars(content) else emptyList()

            profiles.add(FileProfile(path, lines.size, type, exports, deps.toList(), unresolved.distinct(), bridgeApis, eventListeners, globalVars))
            depGraph[path] = deps
        }

        // 构建反向依赖图
        val reverseDeps = mutableMapOf<String, MutableSet<String>>()
        for ((file, deps) in depGraph) {
            for (dep in deps) {
                reverseDeps.getOrPut(dep) { mutableSetOf() }.add(file)
            }
        }

        val entryPoint = when {
            "index.html" in allFiles -> "index.html"
            else -> allFiles.firstOrNull { it.endsWith(".html") }
        }

        val complexity = classifyComplexity(profiles)
        val projectType = detectProjectType(fileContents, profiles)

        return ArchitectureProfile(profiles, depGraph, reverseDeps, entryPoint, projectType, complexity)
    }

    /**
     * 生成结构化架构摘要文本（用于注入系统提示）。
     * @param budget 字符预算上限
     */
    fun buildArchitectureSummary(profile: ArchitectureProfile, budget: Int = 6000): String {
        return buildString {
            appendLine("### 架构分析")
            appendLine("项目类型: ${profile.projectType.label} | 复杂度: ${profile.complexity.label}")

            // 1. 文件依赖图（最有价值的信息）
            appendLine()
            appendLine("**文件依赖关系：**")
            if (profile.entryPoint != null) {
                appendLine("入口: ${profile.entryPoint}")
            }
            for (fp in profile.files.sortedBy { it.path }) {
                val deps = profile.dependencyGraph[fp.path] ?: emptySet()
                val revDeps = profile.reverseDeps[fp.path] ?: emptySet()
                val depStr = if (deps.isNotEmpty()) " → 依赖: ${deps.joinToString(", ")}" else ""
                val revStr = if (revDeps.isNotEmpty()) " | 被依赖: ${revDeps.joinToString(", ")}" else ""
                appendLine("  ${fp.path} (${fp.lineCount}行)$depStr$revStr")
            }

            // 2. 每个文件的函数/组件索引
            appendLine()
            appendLine("**代码索引：**")
            var indexBudget = budget - length
            for (fp in profile.files.sortedByDescending { it.exports.size }) {
                if (indexBudget <= 0) break
                val section = buildString {
                    appendLine("  ${fp.path}:")
                    if (fp.exports.isNotEmpty()) {
                        appendLine("    导出: ${fp.exports.joinToString(", ")}")
                    }
                    if (fp.globalVars.isNotEmpty()) {
                        appendLine("    全局: ${fp.globalVars.joinToString(", ")}")
                    }
                    if (fp.bridgeApis.isNotEmpty()) {
                        appendLine("    Bridge: ${fp.bridgeApis.joinToString(", ")}")
                    }
                    if (fp.eventListeners.isNotEmpty()) {
                        appendLine("    事件: ${fp.eventListeners.joinToString(", ")}")
                    }
                }
                if (section.trim().lines().size > 1) { // 至少有一项内容
                    append(section)
                    indexBudget -= section.length
                }
            }

            // 3. 影响分析提示（高度连接的文件）
            val highImpact = profile.reverseDeps.entries
                .filter { it.value.size >= 2 }
                .sortedByDescending { it.value.size }
            if (highImpact.isNotEmpty()) {
                appendLine()
                appendLine("**高影响文件（修改需谨慎）：**")
                for ((file, dependents) in highImpact.take(5)) {
                    appendLine("  ⚠ $file — 被 ${dependents.size} 个文件依赖: ${dependents.joinToString(", ")}")
                }
            }
        }.take(budget).trimEnd()
    }

    /**
     * 影响分析：给定要修改的文件列表，返回可能需要同步修改的文件。
     */
    fun impactAnalysis(profile: ArchitectureProfile, targetFiles: Set<String>): Set<String> {
        val affected = mutableSetOf<String>()
        val queue = ArrayDeque(targetFiles)
        while (queue.isNotEmpty()) {
            val file = queue.removeFirst()
            val dependents = profile.reverseDeps[file] ?: continue
            for (dep in dependents) {
                if (dep !in targetFiles && affected.add(dep)) {
                    // 不递归太深，只扩展一层
                }
            }
        }
        return affected
    }

    /**
     * 验证文件引用完整性：检查所有引用的文件是否都存在于工作区中。
     * 返回缺失引用列表。
     */
    fun validateReferences(profile: ArchitectureProfile): List<String> {
        val missing = mutableListOf<String>()
        for (fp in profile.files) {
            for (ref in fp.unresolvedRefs) {
                missing.add("${fp.path} 引用了不存在的文件: $ref")
            }
        }
        return missing
    }

    // ═══════════════════════════════════════════════
    //  内部解析方法
    // ═══════════════════════════════════════════════

    private fun classifyFile(path: String): FileType {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> FileType.HTML
            "js", "mjs" -> FileType.JS
            "css" -> FileType.CSS
            "json" -> FileType.JSON
            else -> FileType.OTHER
        }
    }

    /** 提取 JS 中的函数名、类名、导出等 */
    private fun extractJsExports(content: String): List<String> {
        val exports = mutableListOf<String>()
        // function name()
        JS_FUNC_DECL_RE.findAll(content).forEach { m ->
            exports.add("fn:${m.groupValues[1]}")
        }
        // const/let/var name = ... (top-level looking)
        JS_CONST_RE.findAll(content).forEach { m ->
            val name = m.groupValues[1]
            val value = m.groupValues[2].trim()
            when {
                value.startsWith("function") || value.startsWith("(") || value.startsWith("async") ->
                    exports.add("fn:$name")
                value.startsWith("class") -> exports.add("class:$name")
                value.startsWith("{") || value.startsWith("[") -> exports.add("var:$name")
                else -> exports.add("var:$name")
            }
        }
        // class Name
        JS_CLASS_RE.findAll(content).forEach { m ->
            exports.add("class:${m.groupValues[1]}")
        }
        // export default / export function
        JS_EXPORT_RE.findAll(content).forEach { m ->
            val name = m.groupValues[1]
            if (name.isNotBlank() && exports.none { it.endsWith(":$name") }) {
                exports.add("export:$name")
            }
        }
        return exports.distinct().take(30) // 限制数量
    }

    /** 提取 CSS 中的关键选择器 */
    private fun extractCssSelectors(content: String): List<String> {
        val selectors = mutableListOf<String>()
        CSS_SELECTOR_RE.findAll(content).forEach { m ->
            val sel = m.groupValues[1].trim()
            if (sel.isNotBlank() && !sel.startsWith("@") && sel.length < 60) {
                selectors.add(sel)
            }
        }
        return selectors.distinct().take(20)
    }

    /** 提取 HTML 的页面结构 */
    private fun extractHtmlStructure(content: String): List<String> {
        val structure = mutableListOf<String>()
        // <title>
        HTML_TITLE_RE.find(content)?.let { structure.add("title:${it.groupValues[1].trim()}") }
        // 主要容器 id
        HTML_ID_RE.findAll(content).forEach { m ->
            structure.add("id:${m.groupValues[1]}")
        }
        // Vue/React 特征
        if (content.contains("v-") || content.contains("Vue.")) structure.add("framework:Vue")
        if (content.contains("React") || content.contains("ReactDOM")) structure.add("framework:React")
        return structure.take(15)
    }

    /** 提取代码中使用的 NativeBridge API */
    private fun extractBridgeApis(content: String): List<String> {
        val apis = mutableListOf<String>()
        for (api in BRIDGE_API_NAMES) {
            if (content.contains(api)) apis.add(api)
        }
        return apis
    }

    /** 提取事件监听 */
    private fun extractEventListeners(content: String): List<String> {
        val listeners = mutableSetOf<String>()
        EVENT_LISTENER_RE.findAll(content).forEach { m ->
            listeners.add(m.groupValues[1])
        }
        return listeners.toList().take(10)
    }

    /** 提取全局变量声明 */
    private fun extractGlobalVars(content: String): List<String> {
        val globals = mutableListOf<String>()
        // 只取行首的 const/let/var（大概率是全局的）
        JS_GLOBAL_VAR_RE.findAll(content).forEach { m ->
            globals.add(m.groupValues[2])
        }
        return globals.distinct().take(10)
    }

    /** 将引用路径解析为工作区中的实际文件路径 */
    private fun resolveLocalPath(ref: String, allFiles: List<String>): String? {
        if (isExternalUrl(ref)) return null
        val cleaned = ref.removeSurrounding("'").removeSurrounding("\"")
            .removePrefix("./").removePrefix("/").split("?")[0].split("#")[0].trim()
        if (cleaned.isBlank()) return null
        // 精确匹配
        if (cleaned in allFiles) return cleaned
        // 忽略大小写匹配
        allFiles.find { it.equals(cleaned, ignoreCase = true) }?.let { return it }
        // 路径后缀匹配（确保是完整路径段：/cleaned 或恰好相等）
        allFiles.find { it.endsWith("/$cleaned") }?.let { return it }
        return null
    }

    private fun isExternalUrl(ref: String): Boolean {
        val lower = ref.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
               lower.startsWith("//") || lower.startsWith("data:")
    }

    /** 从内容中提取所有原始引用路径（不做解析） */
    private fun extractRawRefs(content: String, extractors: List<Regex>): List<String> {
        val refs = mutableListOf<String>()
        for (re in extractors) {
            re.findAll(content).forEach { m ->
                val ref = m.groupValues[1].ifBlank { m.groupValues.getOrNull(2) ?: "" }
                    .removeSurrounding("'").removeSurrounding("\"").trim()
                if (ref.isNotBlank()) refs.add(ref)
            }
        }
        return refs
    }

    // 各文件类型的引用提取正则组
    private val HTML_REF_EXTRACTORS by lazy { listOf(SCRIPT_SRC_RE, LINK_HREF_RE, IMG_SRC_RE) }
    private val JS_REF_EXTRACTORS by lazy { listOf(JS_IMPORT_RE, JS_REQUIRE_RE) }
    private val CSS_REF_EXTRACTORS by lazy { listOf(CSS_IMPORT_RE, CSS_URL_RE) }

    // ═══ 预编译正则 ═══
    private val SCRIPT_SRC_RE = Regex("""<script[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val LINK_HREF_RE = Regex("""<link[^>]+href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val IMG_SRC_RE = Regex("""<img[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val JS_IMPORT_RE = Regex("""import\s+.*?\s+from\s+['"]([^'"]+)['"]""")
    private val JS_REQUIRE_RE = Regex("""require\s*\(\s*['"]([^'"]+)['"]\s*\)""")
    private val CSS_IMPORT_RE = Regex("""@import\s+(?:url\(\s*['"]?([^'")\s]+)['"]?\s*\)|['"]([^'"]+)['"]\s*)""", RegexOption.IGNORE_CASE)
    private val CSS_URL_RE = Regex("""url\(\s*([^)]+)\s*\)""")
    private val JS_FUNC_DECL_RE = Regex("""(?:^|[\n;])\s*(?:async\s+)?function\s+(\w+)""")
    private val JS_CONST_RE = Regex("""(?:^|[\n;])\s*(?:export\s+)?(?:const|let|var)\s+(\w+)\s*=\s*(.{0,30})""")
    private val JS_CLASS_RE = Regex("""(?:^|[\n;])\s*(?:export\s+)?class\s+(\w+)""")
    private val JS_EXPORT_RE = Regex("""export\s+(?:default\s+)?(?:function|class|const|let|var)\s+(\w+)""")
    private val CSS_SELECTOR_RE = Regex("""(?:^|[}\n])\s*([^{@/\n][^{]*?)\s*\{""")
    private val HTML_TITLE_RE = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE)
    private val HTML_ID_RE = Regex("""\bid\s*=\s*["']([^"']+)["']""")
    private val EVENT_LISTENER_RE = Regex("""addEventListener\s*\(\s*['"](\w+)['"]""")
    private val JS_GLOBAL_VAR_RE = Regex("""^(const|let|var)\s+(\w+)""", RegexOption.MULTILINE)

    private val BRIDGE_API_NAMES = listOf(
        "saveData", "loadData", "removeData", "clearData", "listKeys", "getAppId",
        "showToast", "vibrate", "writeClipboard", "sendToApp",
        "getDeviceInfo", "getBatteryLevel", "getLocation",
        "nativeFetch", "callModule", "listModules",
        "startServer", "stopServer", "serverSend", "serverBroadcast", "getLocalIp"
    )

    // ═══════════════════════════════════════════════
    //  项目类型检测 & 复杂度分类
    // ═══════════════════════════════════════════════

    /** 根据文件数量和总行数判断项目复杂度 */
    private fun classifyComplexity(profiles: List<FileProfile>): ProjectComplexity {
        val codeFiles = profiles.filter { it.type in setOf(FileType.HTML, FileType.JS, FileType.CSS) }
        val totalLines = codeFiles.sumOf { it.lineCount }
        return when {
            codeFiles.size > 8 || totalLines > 1000 -> ProjectComplexity.COMPLEX
            codeFiles.size > 3 || totalLines > 300 -> ProjectComplexity.MEDIUM
            else -> ProjectComplexity.SIMPLE
        }
    }

    /** 基于内容特征检测项目类型 */
    private fun detectProjectType(
        fileContents: Map<String, String>,
        profiles: List<FileProfile>
    ): ProjectType {
        val allCode = fileContents.values.joinToString("\n")
        val allCodeLower = allCode.lowercase()

        // 按优先级匹配（先匹配更具体的模式）
        val scores = mutableMapOf<ProjectType, Int>()

        // REALTIME — WebSocket / 实时通信
        if (profiles.any { p -> p.bridgeApis.any { it in setOf("startServer", "serverSend", "serverBroadcast") } }
            || allCodeLower.contains("websocket") || allCodeLower.contains("realtime")) {
            scores[ProjectType.REALTIME] = (scores[ProjectType.REALTIME] ?: 0) + 3
        }

        // GAME — canvas, requestAnimationFrame, game loop
        if (allCodeLower.contains("canvas") && (allCodeLower.contains("requestanimationframe")
                    || allCodeLower.contains("gameloop") || allCodeLower.contains("game_loop"))) {
            scores[ProjectType.GAME] = (scores[ProjectType.GAME] ?: 0) + 3
        }
        if (GAME_KEYWORDS_RE.containsMatchIn(allCodeLower)) {
            scores[ProjectType.GAME] = (scores[ProjectType.GAME] ?: 0) + 2
        }

        // DATA_VIZ — Chart.js, echarts, SVG charts, D3
        if (allCodeLower.contains("chart.js") || allCodeLower.contains("echarts")
            || allCodeLower.contains("d3.js") || allCodeLower.contains("d3.min.js")
            || (allCodeLower.contains("<svg") && allCodeLower.contains("plot"))) {
            scores[ProjectType.DATA_VIZ] = (scores[ProjectType.DATA_VIZ] ?: 0) + 3
        }

        // DASHBOARD — 多卡片/面板布局 + 数据
        if (DASHBOARD_KEYWORDS_RE.containsMatchIn(allCodeLower)) {
            scores[ProjectType.DASHBOARD] = (scores[ProjectType.DASHBOARD] ?: 0) + 2
        }
        if (allCodeLower.contains("grid") && (allCodeLower.contains("card") || allCodeLower.contains("panel"))) {
            scores[ProjectType.DASHBOARD] = (scores[ProjectType.DASHBOARD] ?: 0) + 1
        }

        // FORM — 表单元素密集
        val formElementCount = FORM_ELEMENTS_RE.findAll(allCodeLower).count()
        if (formElementCount >= 3) {
            scores[ProjectType.FORM] = (scores[ProjectType.FORM] ?: 0) + 2
        }
        if (allCodeLower.contains("submit") && allCodeLower.contains("validation")) {
            scores[ProjectType.FORM] = (scores[ProjectType.FORM] ?: 0) + 1
        }

        // TOOL — 计算器、转换器、编辑器
        if (TOOL_KEYWORDS_RE.containsMatchIn(allCodeLower)) {
            scores[ProjectType.TOOL] = (scores[ProjectType.TOOL] ?: 0) + 2
        }

        // LANDING — 简单展示页面（少量 JS，多 CSS/HTML）
        val jsFiles = profiles.filter { it.type == FileType.JS }
        val cssFiles = profiles.filter { it.type == FileType.CSS }
        val jsLines = jsFiles.sumOf { it.lineCount }
        val cssLines = cssFiles.sumOf { it.lineCount }
        if (jsLines < 50 && cssLines > jsLines && profiles.any { it.type == FileType.HTML }) {
            scores[ProjectType.LANDING] = (scores[ProjectType.LANDING] ?: 0) + 2
        }

        return scores.maxByOrNull { it.value }?.key ?: ProjectType.GENERIC
    }

    // ═══════════════════════════════════════════════
    //  CSS ↔ JS 交叉验证
    // ═══════════════════════════════════════════════

    /** CSS 和 JS 间的不一致问题 */
    data class CssJsIssue(val file: String, val message: String)

    /**
     * 交叉验证 CSS 类名和 JS 选择器的一致性。
     * 返回 JS 引用了但 CSS 未定义的类名列表。
     */
    fun validateCssJsConsistency(fileContents: Map<String, String>): List<CssJsIssue> {
        // 1. 收集所有"已知"类名——CSS 定义 + HTML 标记中使用的
        val definedClasses = mutableSetOf<String>()

        // 1a. 从 CSS 文件中提取（包括复合选择器中的每个类）
        for ((path, content) in fileContents) {
            if (!path.endsWith(".css", ignoreCase = true)) continue
            extractAllCssClasses(content, definedClasses)
        }
        // 1b. 从 HTML 内嵌 <style> 中提取
        for ((_, content) in fileContents) {
            INLINE_STYLE_RE.findAll(content).forEach { styleMatch ->
                extractAllCssClasses(styleMatch.groupValues[1], definedClasses)
            }
        }
        // 1c. 从 HTML class="xxx yyy" 属性中提取（这些类存在于 DOM 中，JS querySelector 可以查到）
        for ((path, content) in fileContents) {
            if (!path.endsWith(".html", ignoreCase = true) && !path.endsWith(".htm", ignoreCase = true)) continue
            HTML_CLASS_ATTR_RE.findAll(content).forEach { m ->
                val classValue = m.groupValues[1]
                classValue.split(Regex("\\s+")).forEach { cls ->
                    if (cls.isNotBlank()) definedClasses.add(cls)
                }
            }
        }

        if (definedClasses.isEmpty()) return emptyList()

        // 2. 只检查 classList.add/remove/toggle — 这些操作的类名需要有对应 CSS 样式才有意义
        //    querySelector 仅用于查找 DOM 元素，类名不一定需要 CSS 定义
        val issues = mutableListOf<CssJsIssue>()
        for ((path, content) in fileContents) {
            if (!path.endsWith(".js", ignoreCase = true) && !path.endsWith(".html", ignoreCase = true)) continue

            val referencedClasses = mutableSetOf<String>()

            // classList.add('xxx') / remove / toggle — 这些才真正需要 CSS 定义
            JS_CLASSLIST_RE.findAll(content).forEach { m ->
                referencedClasses.add(m.groupValues[1])
            }

            for (className in referencedClasses) {
                if (className.isNotBlank() && className !in definedClasses
                    && !className.contains(" ")
                    && className.length in 1..39) {
                    issues.add(CssJsIssue(path, "JS 动态添加了未定义的 CSS 类: .$className"))
                }
            }
        }

        return issues.distinctBy { it.message }.take(10)
    }

    /** 从 CSS 文本中提取所有类名（支持复合选择器 .a.b、后代选择器 .a .b 等） */
    private fun extractAllCssClasses(cssContent: String, output: MutableSet<String>) {
        CSS_ALL_CLASSES_RE.findAll(cssContent).forEach { m ->
            output.add(m.groupValues[1])
        }
    }

    // ═══ 项目类型检测正则 ═══
    private val GAME_KEYWORDS_RE = Regex("""(gameloop|game[-_]?over|score|player|sprite|collision|level\d)""")
    private val DASHBOARD_KEYWORDS_RE = Regex("""(dashboard|widget|metric|kpi|stat[-_]?card|overview)""")
    private val FORM_ELEMENTS_RE = Regex("""<(input|select|textarea)\b""")
    private val TOOL_KEYWORDS_RE = Regex("""(calculator|converter|editor|generator|formatter|encoder|decoder)""")
    private val CSS_CLASS_DEF_RE = Regex("""\.([\w-]+)\s*[{,]""")
    private val CSS_ALL_CLASSES_RE = Regex("""\.([\w-]+)""")
    private val HTML_CLASS_ATTR_RE = Regex("""\bclass\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val INLINE_STYLE_RE = Regex("""<style[^>]*>([\s\S]*?)</style>""", RegexOption.IGNORE_CASE)
    private val JS_QUERY_SELECTOR_RE = Regex("""querySelector(?:All)?\s*\(\s*['"]\.([a-zA-Z][\w-]*)['"]""")
    private val JS_GET_BY_CLASS_RE = Regex("""getElementsByClassName\s*\(\s*['"]([a-zA-Z][\w-]*)['"]""")
    private val JS_CLASSLIST_RE = Regex("""classList\.(?:add|remove|toggle|contains)\s*\(\s*['"]([a-zA-Z][\w-]*)['"]""")
}
