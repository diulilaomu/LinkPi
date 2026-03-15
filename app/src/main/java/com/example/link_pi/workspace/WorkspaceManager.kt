package com.example.link_pi.workspace

import android.content.Context
import java.io.File

/**
 * Manages per-app file workspaces for multi-file project generation.
 * Each workspace is a directory in the app's internal storage where the AI
 * can create files, folders, and edit content — similar to VS Code / Claude Code.
 */
class WorkspaceManager(private val context: Context) {

    companion object {
        /** Meta files excluded from user file listings and searches. */
        val META_FILES = setOf("APP_INFO.json", "ARCHITECTURE.md")
    }

    // ── Read-before-write tracking (inspired by OpenCode FileTime) ──
    // Key: "appId::relativePath", Value: timestamp of last read
    private val fileReadTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ── Per-file write lock (inspired by OpenCode FileTime.withLock) ──
    private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun lockFor(appId: String, path: String): Any =
        fileLocks.getOrPut("$appId::$path") { Any() }

    /** Record that a file was read in the current session. */
    fun markFileRead(appId: String, relativePath: String) {
        fileReadTimes["$appId::$relativePath"] = System.currentTimeMillis()
    }

    /** Check if a file has been read in the current session. */
    private fun wasFileRead(appId: String, relativePath: String): Boolean {
        return fileReadTimes.containsKey("$appId::$relativePath")
    }

    /** Clear all read tracking (call when switching workspace). */
    fun clearReadTracking() {
        fileReadTimes.clear()
    }

    // ── Truncated output persistence ──
    private val truncatedOutputDir: File
        get() = File(context.cacheDir, "truncated_outputs").also { it.mkdirs() }

    /**
     * Save truncated content to a temp file, return a path AI can reference.
     * Returns the saved filename so AI can use read_workspace_file-like access.
     */
    fun saveTruncatedOutput(content: String, toolName: String): String {
        val ts = System.currentTimeMillis()
        val file = File(truncatedOutputDir, "${toolName}_$ts.txt")
        file.writeText(content)
        // Prune old files (keep last 20)
        truncatedOutputDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        return file.name
    }

    /** Read a previously saved truncated output. */
    fun readTruncatedOutput(filename: String): String? {
        val clean = filename.replace("/", "").replace("\\", "").replace("..", "")
        if (clean.isBlank()) return null
        val file = File(truncatedOutputDir, clean)
        return if (file.exists() && file.isFile && file.canonicalFile.parentFile?.canonicalPath == truncatedOutputDir.canonicalPath)
            file.readText() else null
    }

    private val workspacesRoot: File
        get() = File(context.filesDir, "workspaces").also { it.mkdirs() }

    /** Get or create a workspace directory for a given app ID. */
    fun getWorkspaceDir(appId: String): File {
        val safeId = sanitizeId(appId)
        return File(workspacesRoot, safeId).also { it.mkdirs() }
    }

    /** Create a file with content. Parent directories are created automatically. */
    fun createFile(appId: String, relativePath: String, content: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty. Specify a file path like 'index.html' or 'js/app.js'"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (file.exists()) {
            return "Error: file already exists: $relativePath (use write_file to overwrite)"
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Created: $relativePath (${content.length} chars)"
    }

    /** Write (overwrite) a file with content. Creates if not exists. Auto-snapshots existing files. */
    fun writeFile(appId: String, relativePath: String, content: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty. Specify a file path like 'index.html' or 'js/app.js'"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        synchronized(lockFor(appId, relativePath)) {
            if (file.exists() && file.isFile) snapshotFile(appId, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            return "Written: $relativePath (${content.length} chars)"
        }
    }

    /** Append content to a file. Creates if not exists. */
    fun appendFile(appId: String, relativePath: String, content: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        synchronized(lockFor(appId, relativePath)) {
            file.parentFile?.mkdirs()
            file.appendText(content)
            return "Appended to: $relativePath (+${content.length} chars)"
        }
    }

    /** Read file content. Records read time for read-before-write assertion. */
    fun readFile(appId: String, relativePath: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        if (!file.isFile) return "Error: not a file: $relativePath"
        markFileRead(appId, relativePath)
        val content = file.readText()
        val lines = content.lines()
        // Add line numbers for reference
        val numbered = lines.mapIndexed { i, line -> "${i + 1}| $line" }.joinToString("\n")
        return if (numbered.length > 16000) {
            numbered.take(16000) + "\n...(truncated at line ~${numbered.take(16000).count { it == '\n' }}, total ${lines.size} lines, ${content.length} chars)"
        } else numbered
    }

    /** Read file content by line range (1-indexed, inclusive). Records read time. */
    fun readFileLines(appId: String, relativePath: String, startLine: Int, endLine: Int): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        markFileRead(appId, relativePath)
        val lines = file.readText().lines()
        val s = (startLine - 1).coerceIn(0, lines.size)
        val e = endLine.coerceIn(s, lines.size)
        return lines.subList(s, e).mapIndexed { i, line -> "${s + i + 1}| $line" }.joinToString("\n") +
            "\n(showing lines $startLine-$e of ${lines.size})"
    }

    /**
     * Replace text in a file using cascading multi-level matching (inspired by OpenCode/Cline).
     * 9 strategies tried in order: exact → line-trimmed → block-anchor(Levenshtein) →
     * whitespace-normalized → indentation-flexible → escape-normalized → trimmed-boundary →
     * context-aware → multi-occurrence. Auto-snapshots before modifying.
     */
    fun replaceInFile(appId: String, relativePath: String, oldText: String, newText: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        if (oldText == newText) return "Error: old_text and new_text are identical, no change needed"
        // Read-before-write assertion: AI must read the file before editing
        if (!wasFileRead(appId, relativePath)) {
            return "Error: you must read_workspace_file('$relativePath') before editing it. " +
                "This ensures old_text is accurate and avoids match failures."
        }
        synchronized(lockFor(appId, relativePath)) {
            val content = file.readText()

            // Normalize line endings for matching; preserve original line ending style for output
            val lineEnding = if (content.contains("\r\n")) "\r\n" else "\n"
            val normContent = content.replace("\r\n", "\n")
            val normOld = oldText.replace("\r\n", "\n")
            val normNew = newText.replace("\r\n", "\n")

            val result = cascadingReplace(normContent, normOld, normNew)
            if (result != null) {
                if (result.strategy == "ambiguous") {
                    return "Error: old_text matches multiple locations in $relativePath. Add more surrounding context to make it unique."
                }
                snapshotFile(appId, relativePath)
                val output = if (lineEnding == "\r\n") result.content.replace("\n", "\r\n") else result.content
                file.writeText(output)
                return "Replaced in: $relativePath (${result.strategy})"
            }

            // Not found — provide helpful context
            val contentLines = normContent.lines()
            val firstLine = normOld.lines().firstOrNull()?.trim() ?: ""
            val hint = if (firstLine.isNotEmpty()) {
                val lineNum = contentLines.indexOfFirst { it.trim().contains(firstLine) }
                if (lineNum >= 0) " Hint: similar text found near line ${lineNum + 1}. Use read_workspace_file to re-read the file, or use replace_lines with line numbers."
                else " The first line of old_text was not found anywhere. Re-read the file with read_workspace_file and try again."
            } else ""
            return "Error: text not found in $relativePath.$hint"
        }
    }

    /** Result of a successful cascading replace. */
    private data class ReplaceResult(val content: String, val strategy: String)

    /**
     * Try 9 matching strategies in order; return new content on first unique match.
     * Mirrors OpenCode's edit.ts cascading replacer chain.
     */
    private fun cascadingReplace(content: String, oldStr: String, newStr: String): ReplaceResult? {
        data class Match(val start: Int, val end: Int)

        // Helper: apply a single unique match
        fun applyMatch(m: Match, strategy: String): ReplaceResult {
            return ReplaceResult(
                content.substring(0, m.start) + newStr + content.substring(m.end),
                strategy
            )
        }

        // ── Strategy 1: Exact match ──
        run {
            val idx = content.indexOf(oldStr)
            if (idx >= 0) {
                val lastIdx = content.lastIndexOf(oldStr)
                if (idx == lastIdx) return applyMatch(Match(idx, idx + oldStr.length), "exact match")
                return ReplaceResult(content, "ambiguous") // signal ambiguous to caller
            }
        }

        val contentLines = content.lines()
        val searchLines = oldStr.lines().toMutableList()
        if (searchLines.lastOrNull() == "") searchLines.removeAt(searchLines.lastIndex)

        // Helper: convert line range to char range
        fun lineRange(startLine: Int, endLineInclusive: Int): Match {
            var s = 0
            for (k in 0 until startLine) s += contentLines[k].length + 1
            var e = s
            for (k in startLine..endLineInclusive) {
                e += contentLines[k].length
                if (k < endLineInclusive) e += 1
            }
            return Match(s, e)
        }

        // ── Strategy 2: Line-trimmed match (ignore leading/trailing whitespace per line) ──
        run {
            val matches = mutableListOf<Match>()
            if (searchLines.isNotEmpty()) {
                for (i in 0..contentLines.size - searchLines.size) {
                    var ok = true
                    for (j in searchLines.indices) {
                        if (contentLines[i + j].trim() != searchLines[j].trim()) { ok = false; break }
                    }
                    if (ok) matches.add(lineRange(i, i + searchLines.size - 1))
                }
            }
            if (matches.size == 1) return applyMatch(matches[0], "line-trimmed match")
        }

        // ── Strategy 3: Block-anchor + Levenshtein similarity ──
        if (searchLines.size >= 3) run {
            val firstTrimmed = searchLines.first().trim()
            val lastTrimmed = searchLines.last().trim()
            data class Candidate(val startLine: Int, val endLine: Int)
            val candidates = mutableListOf<Candidate>()
            for (i in contentLines.indices) {
                if (contentLines[i].trim() != firstTrimmed) continue
                for (j in i + 2 until contentLines.size) {
                    if (contentLines[j].trim() == lastTrimmed) {
                        candidates.add(Candidate(i, j))
                        break
                    }
                }
            }
            if (candidates.size == 1) {
                val c = candidates[0]
                // Single candidate: accept with relaxed threshold (anchor match is strong signal)
                return applyMatch(lineRange(c.startLine, c.endLine), "block-anchor match (single candidate)")
            }
            if (candidates.size > 1) {
                // Pick best by Levenshtein similarity of middle lines
                var bestCandidate: Candidate? = null
                var bestSim = -1.0
                for (c in candidates) {
                    val actualSize = c.endLine - c.startLine + 1
                    val middleCount = minOf(searchLines.size - 2, actualSize - 2)
                    if (middleCount <= 0) { if (bestSim < 1.0) { bestSim = 1.0; bestCandidate = c }; continue }
                    var simSum = 0.0
                    for (j in 1..middleCount) {
                        val a = contentLines[c.startLine + j].trim()
                        val b = searchLines[j].trim()
                        val maxLen = maxOf(a.length, b.length)
                        simSum += if (maxLen == 0) 1.0 else 1.0 - levenshteinDistance(a, b).toDouble() / maxLen
                    }
                    val avgSim = simSum / middleCount
                    if (avgSim > bestSim) { bestSim = avgSim; bestCandidate = c }
                }
                if (bestCandidate != null && bestSim >= 0.3) {
                    return applyMatch(lineRange(bestCandidate.startLine, bestCandidate.endLine),
                        "block-anchor match (similarity=${String.format("%.2f", bestSim)})")
                }
            }
        }

        // ── Strategy 4: Whitespace-normalized (collapse all whitespace) ──
        run {
            val norm = { s: String -> s.replace(Regex("\\s+"), " ").trim() }
            val normFind = norm(oldStr)
            val matches = mutableListOf<Match>()
            // Single-line match
            for (i in contentLines.indices) {
                if (norm(contentLines[i]) == normFind) {
                    val s = contentLines.take(i).sumOf { it.length + 1 }
                    matches.add(Match(s, s + contentLines[i].length))
                }
            }
            // Multi-line block match
            if (searchLines.size > 1 && matches.isEmpty()) {
                for (i in 0..contentLines.size - searchLines.size) {
                    val block = contentLines.subList(i, i + searchLines.size).joinToString("\n")
                    if (norm(block) == normFind) matches.add(lineRange(i, i + searchLines.size - 1))
                }
            }
            if (matches.size == 1) return applyMatch(matches[0], "whitespace-normalized match")
        }

        // ── Strategy 5: Indentation-flexible (remove common indent, compare) ──
        run {
            val removeIndent = { lines: List<String> ->
                val nonEmpty = lines.filter { it.isNotBlank() }
                val minIndent = nonEmpty.minOfOrNull { it.length - it.trimStart().length } ?: 0
                lines.map { if (it.isBlank()) it else it.substring(minOf(minIndent, it.length)) }.joinToString("\n")
            }
            val normFind = removeIndent(searchLines)
            val matches = mutableListOf<Match>()
            for (i in 0..contentLines.size - searchLines.size) {
                val block = contentLines.subList(i, i + searchLines.size)
                if (removeIndent(block) == normFind) matches.add(lineRange(i, i + searchLines.size - 1))
            }
            if (matches.size == 1) return applyMatch(matches[0], "indentation-flexible match")
        }

        // ── Strategy 6: Escape-normalized (unescape \\n, \\t, etc.) ──
        run {
            val unescape = { s: String ->
                s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r")
                    .replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
            }
            val unescaped = unescape(oldStr)
            if (unescaped != oldStr) {
                val idx = content.indexOf(unescaped)
                if (idx >= 0 && content.lastIndexOf(unescaped) == idx) {
                    return applyMatch(Match(idx, idx + unescaped.length), "escape-normalized match")
                }
            }
        }

        // ── Strategy 7: Trimmed-boundary (trim leading/trailing whitespace of entire block) ──
        run {
            val trimmed = oldStr.trim()
            if (trimmed != oldStr && trimmed.isNotEmpty()) {
                val idx = content.indexOf(trimmed)
                if (idx >= 0 && content.lastIndexOf(trimmed) == idx) {
                    return applyMatch(Match(idx, idx + trimmed.length), "trimmed-boundary match")
                }
                // Also try block-level trim match
                val matches = mutableListOf<Match>()
                for (i in 0..contentLines.size - searchLines.size) {
                    val block = contentLines.subList(i, i + searchLines.size).joinToString("\n")
                    if (block.trim() == trimmed) matches.add(lineRange(i, i + searchLines.size - 1))
                }
                if (matches.size == 1) return applyMatch(matches[0], "trimmed-boundary block match")
            }
        }

        // ── Strategy 8: Context-aware (first+last line anchors + 50% middle similarity) ──
        if (searchLines.size >= 3) run {
            val firstTrimmed = searchLines.first().trim()
            val lastTrimmed = searchLines.last().trim()
            for (i in contentLines.indices) {
                if (contentLines[i].trim() != firstTrimmed) continue
                for (j in i + 2 until contentLines.size) {
                    if (contentLines[j].trim() != lastTrimmed) continue
                    val blockLines = contentLines.subList(i, j + 1)
                    if (blockLines.size == searchLines.size) {
                        var matching = 0; var total = 0
                        for (k in 1 until blockLines.size - 1) {
                            val bl = blockLines[k].trim(); val sl = searchLines[k].trim()
                            if (bl.isNotEmpty() || sl.isNotEmpty()) { total++; if (bl == sl) matching++ }
                        }
                        if (total == 0 || matching.toDouble() / total >= 0.5) {
                            return applyMatch(lineRange(i, j), "context-aware match (${matching}/${total} lines)")
                        }
                    }
                    break
                }
            }
        }

        // ── Strategy 9: Multi-occurrence (if replaceAll semantics could help, still require unique) ──
        // This is a last resort — find all exact matches and report
        run {
            var idx = content.indexOf(oldStr)
            var count = 0
            while (idx >= 0) { count++; idx = content.indexOf(oldStr, idx + oldStr.length) }
            if (count > 1) return ReplaceResult(content, "ambiguous") // signal ambiguous to caller
        }

        return null // not found by any strategy
    }

    /** Levenshtein distance between two strings. */
    private fun levenshteinDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    /** Replace lines by line number range (1-indexed, inclusive). Auto-snapshots before modifying. */
    fun replaceLines(appId: String, relativePath: String, startLine: Int, endLine: Int, newContent: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        // Read-before-write assertion
        if (!wasFileRead(appId, relativePath)) {
            return "Error: you must read_workspace_file('$relativePath') before editing it. " +
                "This ensures line numbers are accurate."
        }
        synchronized(lockFor(appId, relativePath)) {
            val raw = file.readText()
            val lineEnding = if (raw.contains("\r\n")) "\r\n" else "\n"
            val lines = raw.replace("\r\n", "\n").lines().toMutableList()
            if (startLine < 1 || startLine > lines.size) return "Error: start_line $startLine out of range (1-${lines.size})"
            val s = startLine - 1
            val e = endLine.coerceAtMost(lines.size)
            if (e < startLine) return "Error: end_line must be >= start_line"
            snapshotFile(appId, relativePath)
            for (k in s until e) lines.removeAt(s)
            lines.addAll(s, newContent.lines())
            val output = lines.joinToString("\n").let { if (lineEnding == "\r\n") it.replace("\n", "\r\n") else it }
            file.writeText(output)
            return "Replaced lines $startLine-$e with ${newContent.lines().size} new lines in $relativePath"
        }
    }

    /** Create a directory (and any parent directories). */
    fun createDirectory(appId: String, relativePath: String): String {
        val dir = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path"
        if (dir.exists() && dir.isDirectory) return "Directory already exists: $relativePath"
        dir.mkdirs()
        return "Created directory: $relativePath"
    }

    /** List files and directories at a given path. */
    fun listFiles(appId: String, relativePath: String = ""): String {
        val dir = if (relativePath.isBlank()) {
            getWorkspaceDir(appId)
        } else {
            resolveSecure(appId, relativePath)
                ?: return "Error: invalid path"
        }
        if (!dir.exists()) return "Error: path not found: $relativePath"
        if (!dir.isDirectory) return "Error: not a directory: $relativePath"
        val entries = dir.listFiles()?.filter { it.name != ".snapshots" }?.sortedBy { it.name } ?: emptyList()
        if (entries.isEmpty()) return "(empty directory)"
        return entries.joinToString("\n") { entry ->
            val prefix = if (entry.isDirectory) "[DIR]  " else "[FILE] "
            val size = if (entry.isFile) " (${entry.length()} bytes)" else ""
            "$prefix${entry.name}$size"
        }
    }

    /** Delete a file or empty directory. */
    fun deleteFile(appId: String, relativePath: String): String {
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path"
        if (!file.exists()) return "Error: not found: $relativePath"
        if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true)) {
            return "Error: directory not empty: $relativePath (use delete_directory for recursive delete)"
        }
        file.delete()
        return "Deleted: $relativePath"
    }

    /** Delete a directory and all its contents recursively. */
    fun deleteDirectory(appId: String, relativePath: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val dir = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!dir.exists()) return "Error: not found: $relativePath"
        if (!dir.isDirectory) return "Error: not a directory: $relativePath (use delete_workspace_file for files)"
        val count = dir.walkTopDown().count { it.isFile }
        dir.deleteRecursively()
        return "Deleted directory: $relativePath ($count files removed)"
    }

    /** Rename or move a file/directory within the workspace. */
    fun renameFile(appId: String, oldPath: String, newPath: String): String {
        if (oldPath.isBlank()) return "Error: source path cannot be empty"
        if (newPath.isBlank()) return "Error: destination path cannot be empty"
        val src = resolveSecure(appId, oldPath)
            ?: return "Error: invalid source path: $oldPath"
        val dst = resolveSecure(appId, newPath)
            ?: return "Error: invalid destination path: $newPath"
        if (!src.exists()) return "Error: source not found: $oldPath"
        if (dst.exists()) return "Error: destination already exists: $newPath"
        dst.parentFile?.mkdirs()
        val ok = src.renameTo(dst)
        return if (ok) "Renamed: $oldPath → $newPath" else "Error: rename failed"
    }

    /** Copy a file within the workspace. */
    fun copyFile(appId: String, sourcePath: String, destPath: String): String {
        if (sourcePath.isBlank()) return "Error: source path cannot be empty"
        if (destPath.isBlank()) return "Error: destination path cannot be empty"
        val src = resolveSecure(appId, sourcePath)
            ?: return "Error: invalid source path: $sourcePath"
        val dst = resolveSecure(appId, destPath)
            ?: return "Error: invalid destination path: $destPath"
        if (!src.exists()) return "Error: source not found: $sourcePath"
        if (!src.isFile) return "Error: source is not a file: $sourcePath"
        if (dst.exists()) return "Error: destination already exists: $destPath"
        dst.parentFile?.mkdirs()
        src.copyTo(dst)
        return "Copied: $sourcePath → $destPath (${src.length()} bytes)"
    }

    /** Check if a workspace has any user files (excludes .snapshots). */
    fun hasFiles(appId: String): Boolean {
        val dir = getWorkspaceDir(appId)
        return dir.exists() && (dir.listFiles()?.any { it.name != snapshotDirName } == true)
    }

    /** Get all file paths in a workspace recursively. */
    fun getAllFiles(appId: String): List<String> {
        val root = getWorkspaceDir(appId)
        if (!root.exists()) return emptyList()
        val files = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { it.name != ".snapshots" }
            .filter { it.isFile && it.name !in META_FILES }.forEach { file ->
            files.add(file.relativeTo(root).path.replace("\\", "/"))
        }
        return files
    }

    /**
     * Generate or update APP_INFO.json manifest in the workspace.
     * Contains app name, version, description, file structure with sizes.
     */
    fun generateManifest(
        appId: String, appName: String, version: Int = 1,
        description: String = "",
        usedTools: List<String> = emptyList(),
        usedBridgeApis: List<String> = emptyList(),
        usedModules: List<String> = emptyList()
    ) {
        val root = getWorkspaceDir(appId)
        if (!root.exists()) return

        val files = root.walkTopDown()
            .onEnter { it.name != ".snapshots" }
            .filter { it.isFile && it.name !in META_FILES }
            .sortedBy { it.relativeTo(root).path }
            .toList()

        val totalSize = files.sumOf { it.length() }
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val now = sdf.format(java.util.Date())

        val fileList = org.json.JSONArray()
        for (f in files) {
            val obj = org.json.JSONObject()
            obj.put("path", f.relativeTo(root).path.replace("\\", "/"))
            obj.put("size", formatSize(f.length()))
            fileList.put(obj)
        }

        // Merge with existing manifest to preserve custom fields
        val manifestFile = File(root, "APP_INFO.json")
        val json = if (manifestFile.exists()) {
            try { org.json.JSONObject(manifestFile.readText()) } catch (_: Exception) { org.json.JSONObject() }
        } else {
            org.json.JSONObject()
        }
        json.put("name", appName)
        json.put("version", version)
        json.put("description", description)
        json.put("fileCount", files.size)
        json.put("totalSize", formatSize(totalSize))
        json.put("updatedAt", now)
        json.put("files", fileList)
        if (usedTools.isNotEmpty()) json.put("usedTools", org.json.JSONArray(usedTools))
        if (usedBridgeApis.isNotEmpty()) json.put("nativeBridgeApis", org.json.JSONArray(usedBridgeApis))
        if (usedModules.isNotEmpty()) json.put("modules", org.json.JSONArray(usedModules))

        manifestFile.writeText(json.toString(2))
    }

    /**
     * Generate ARCHITECTURE.md — describes core files, key line ranges, and responsibilities.
     * Called with AI-generated content.
     */
    fun generateArchitecture(appId: String, content: String) {
        val root = getWorkspaceDir(appId)
        if (!root.exists()) return
        File(root, "ARCHITECTURE.md").writeText(content)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        }
    }

    /** Read the entry file (index.html) content for WebView loading. */
    fun readEntryFile(appId: String, entryFile: String = "index.html"): String? {
        val file = resolveSecure(appId, entryFile) ?: return null
        return if (file.exists() && file.isFile) file.readText() else null
    }

    /** Get the absolute path to workspace root for file:// URL loading. */
    fun getWorkspacePath(appId: String): String {
        return getWorkspaceDir(appId).absolutePath
    }

    /** Delete an entire workspace. */
    fun deleteWorkspace(appId: String) {
        val dir = getWorkspaceDir(appId)
        if (dir.exists()) dir.deleteRecursively()
    }

    /** Clean up workspace directories that have no files (empty dirs from aborted generations). */
    fun cleanupStaleWorkspaces() {
        val root = workspacesRoot
        if (!root.exists()) return
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val userFiles = dir.listFiles()?.filter { it.name !in META_FILES } ?: emptyList()
                if (userFiles.isEmpty()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    // ── Coding Tools ──

    /** Search for a regex/text pattern in a single file. Returns matching lines with numbers. */
    fun grepFile(appId: String, relativePath: String, pattern: String, isRegex: Boolean = true): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        if (pattern.isBlank()) return "Error: pattern cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        if (!file.isFile) return "Error: not a file: $relativePath"

        val regex = try {
            if (isRegex) Regex(pattern) else Regex(Regex.escape(pattern))
        } catch (e: Exception) {
            return "Error: invalid regex pattern: ${e.message}"
        }

        val lines = file.readText().lines()
        val matches = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            if (regex.containsMatchIn(line)) {
                matches.add("${i + 1}| $line")
            }
        }
        if (matches.isEmpty()) return "No matches found for '$pattern' in $relativePath"
        val result = matches.joinToString("\n")
        return if (result.length > 12000) {
            result.take(12000) + "\n...(truncated, ${matches.size} matches total)"
        } else {
            "${matches.size} match(es) in $relativePath:\n$result"
        }
    }

    /** Search across all files in workspace for a pattern. Returns file:line matches. */
    fun grepWorkspace(appId: String, pattern: String, isRegex: Boolean = true, fileFilter: String = ""): String {
        if (pattern.isBlank()) return "Error: pattern cannot be empty"
        val root = getWorkspaceDir(appId)
        if (!root.exists()) return "Error: workspace not found"

        val regex = try {
            if (isRegex) Regex(pattern) else Regex(Regex.escape(pattern))
        } catch (e: Exception) {
            return "Error: invalid regex pattern: ${e.message}"
        }

        val fileFilterRegex = if (fileFilter.isNotBlank()) {
            try {
                Regex(fileFilter.replace("*", ".*").replace("?", "."))
            } catch (_: Exception) { null }
        } else null

        val allFiles = root.walkTopDown()
            .onEnter { it.name != ".snapshots" }
            .filter { it.isFile && it.name !in META_FILES }
            .filter { f ->
                fileFilterRegex?.containsMatchIn(f.name) ?: true
            }
            .toList()

        val results = mutableListOf<String>()
        var totalMatches = 0
        for (file in allFiles) {
            val relPath = file.relativeTo(root).path.replace("\\", "/")
            // Skip binary files by checking for null bytes in first 512 bytes
            val head = file.inputStream().use { it.readNBytes(512) }
            if (head.any { it == 0.toByte() }) continue

            val lines = file.readText().lines()
            for ((i, line) in lines.withIndex()) {
                if (regex.containsMatchIn(line)) {
                    results.add("$relPath:${i + 1}| $line")
                    totalMatches++
                    if (totalMatches >= 200) break
                }
            }
            if (totalMatches >= 200) break
        }

        if (results.isEmpty()) return "No matches found for '$pattern' in workspace"
        val result = results.joinToString("\n")
        val suffix = if (totalMatches >= 200) "\n...(limited to 200 matches)" else ""
        return "$totalMatches match(es) across ${allFiles.size} files:\n$result$suffix"
    }

    /** Insert content at a specific line number (before the given line). 1-indexed. */
    fun insertLines(appId: String, relativePath: String, lineNumber: Int, content: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        if (!wasFileRead(appId, relativePath)) {
            return "Error: you must read_workspace_file('$relativePath') before editing it. " +
                "This ensures line numbers are accurate."
        }
        synchronized(lockFor(appId, relativePath)) {
            val raw = file.readText()
            val lineEnding = if (raw.contains("\r\n")) "\r\n" else "\n"
            val lines = raw.replace("\r\n", "\n").lines().toMutableList()
            val insertAt = (lineNumber - 1).coerceIn(0, lines.size)
            val newLines = content.lines()
            snapshotFile(appId, relativePath)
            lines.addAll(insertAt, newLines)
            val output = lines.joinToString("\n").let { if (lineEnding == "\r\n") it.replace("\n", "\r\n") else it }
            file.writeText(output)
            return "Inserted ${newLines.size} line(s) at line $lineNumber in $relativePath (total now ${lines.size} lines)"
        }
    }

    /** Get file metadata: size, line count, modification time. */
    fun fileInfo(appId: String, relativePath: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: not found: $relativePath"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return if (file.isFile) {
            val content = file.readText()
            val lineCount = content.lines().size
            val charCount = content.length
            "File: $relativePath\n" +
            "Size: ${formatSize(file.length())}\n" +
            "Lines: $lineCount\n" +
            "Characters: $charCount\n" +
            "Modified: ${sdf.format(java.util.Date(file.lastModified()))}"
        } else {
            val fileCount = file.walkTopDown().count { it.isFile }
            val dirCount = file.walkTopDown().count { it.isDirectory } - 1
            val totalSize = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            "Directory: $relativePath\n" +
            "Files: $fileCount\n" +
            "Subdirectories: $dirCount\n" +
            "Total size: ${formatSize(totalSize)}\n" +
            "Modified: ${sdf.format(java.util.Date(file.lastModified()))}"
        }
    }

    // ── Snapshot & Undo ──

    private val snapshotDirName = ".snapshots"
    private val maxSnapshotsPerFile = 10

    /** Auto-snapshot a file before modification. Stored as .snapshots/{filename}.{timestamp}.bak */
    private fun snapshotFile(appId: String, relativePath: String) {
        try {
            val file = resolveSecure(appId, relativePath) ?: return
            if (!file.exists() || !file.isFile) return
            val root = getWorkspaceDir(appId)
            val snapshotDir = File(root, snapshotDirName)
            snapshotDir.mkdirs()
            val safeName = relativePath.replace("/", "__").replace("\\", "__")
            val timestamp = System.currentTimeMillis()
            val snapFile = File(snapshotDir, "$safeName.$timestamp.bak")
            file.copyTo(snapFile, overwrite = true)
            // Prune old snapshots for this file
            val prefix = "$safeName."
            val existing = snapshotDir.listFiles()
                ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".bak") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            if (existing.size > maxSnapshotsPerFile) {
                existing.drop(maxSnapshotsPerFile).forEach { it.delete() }
            }
        } catch (_: Exception) { /* snapshot is best-effort */ }
    }

    /** Undo: restore file from latest snapshot. */
    fun undoFile(appId: String, relativePath: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val root = getWorkspaceDir(appId)
        val snapshotDir = File(root, snapshotDirName)
        val safeName = relativePath.replace("/", "__").replace("\\", "__")
        val prefix = "$safeName."
        val latest = snapshotDir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".bak") }
            ?.maxByOrNull { it.lastModified() }
            ?: return "Error: 没有找到 $relativePath 的快照，无法撤销"
        val target = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        target.parentFile?.mkdirs()
        latest.copyTo(target, overwrite = true)
        latest.delete() // consume this snapshot
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return "已撤销: $relativePath 已恢复到 ${sdf.format(java.util.Date(latest.lastModified()))} 的版本"
    }

    /** List available snapshots for a file. */
    fun listSnapshots(appId: String, relativePath: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val root = getWorkspaceDir(appId)
        val snapshotDir = File(root, snapshotDirName)
        val safeName = relativePath.replace("/", "__").replace("\\", "__")
        val prefix = "$safeName."
        val snapshots = snapshotDir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".bak") }
            ?.sortedByDescending { it.lastModified() }
            ?: return "$relativePath 没有可用快照"
        if (snapshots.isEmpty()) return "$relativePath 没有可用快照"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return "${snapshots.size} 个快照:\n" + snapshots.mapIndexed { i, f ->
            "${i + 1}. ${sdf.format(java.util.Date(f.lastModified()))} (${formatSize(f.length())})"
        }.joinToString("\n")
    }

    // ── Diff ──

    /** Compare current file with latest snapshot, output unified diff. */
    fun diffFile(appId: String, relativePath: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        val root = getWorkspaceDir(appId)
        val snapshotDir = File(root, snapshotDirName)
        val safeName = relativePath.replace("/", "__").replace("\\", "__")
        val prefix = "$safeName."
        val latest = snapshotDir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".bak") }
            ?.maxByOrNull { it.lastModified() }
            ?: return "Error: 没有找到 $relativePath 的快照，无法对比差异"

        val oldLines = latest.readText().lines()
        val newLines = file.readText().lines()
        val diff = buildUnifiedDiff(relativePath, oldLines, newLines)
        return if (diff.isBlank()) "文件与最近快照无差异" else diff
    }

    /** Simple unified diff implementation using LCS-based approach. */
    private fun buildUnifiedDiff(path: String, oldLines: List<String>, newLines: List<String>): String {
        // Myers-like diff: compute edit script
        val edits = computeEdits(oldLines, newLines)
        if (edits.all { it.type == EditType.EQUAL }) return ""

        val sb = StringBuilder()
        sb.appendLine("--- a/$path (snapshot)")
        sb.appendLine("+++ b/$path (current)")

        // Group edits into hunks with 3 lines of context
        val hunks = groupHunks(edits, context = 3)
        for (hunk in hunks) {
            val oldStart = hunk.first().oldLine + 1
            val newStart = hunk.first().newLine + 1
            val oldCount = hunk.count { it.type == EditType.EQUAL || it.type == EditType.DELETE }
            val newCount = hunk.count { it.type == EditType.EQUAL || it.type == EditType.INSERT }
            sb.appendLine("@@ -$oldStart,$oldCount +$newStart,$newCount @@")
            for (edit in hunk) {
                when (edit.type) {
                    EditType.EQUAL -> sb.appendLine(" ${edit.text}")
                    EditType.DELETE -> sb.appendLine("-${edit.text}")
                    EditType.INSERT -> sb.appendLine("+${edit.text}")
                }
            }
        }
        val result = sb.toString()
        return if (result.length > 12000) result.take(12000) + "\n...(diff truncated)" else result
    }

    private enum class EditType { EQUAL, DELETE, INSERT }
    private data class Edit(val type: EditType, val text: String, val oldLine: Int, val newLine: Int)

    private fun computeEdits(oldLines: List<String>, newLines: List<String>): List<Edit> {
        // LCS using DP (O(n*m) space, acceptable for typical file sizes)
        val n = oldLines.size
        val m = newLines.size
        // For very large files, fall back to simple line-by-line comparison
        if (n.toLong() * m.toLong() > 2_000_000L) {
            return simpleDiff(oldLines, newLines)
        }

        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val edits = mutableListOf<Edit>()
        var i = 0; var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && oldLines[i] == newLines[j] -> {
                    edits.add(Edit(EditType.EQUAL, oldLines[i], i, j)); i++; j++
                }
                j < m && (i >= n || dp[i][j + 1] >= dp[i + 1][j]) -> {
                    edits.add(Edit(EditType.INSERT, newLines[j], i, j)); j++
                }
                else -> {
                    edits.add(Edit(EditType.DELETE, oldLines[i], i, j)); i++
                }
            }
        }
        return edits
    }

    private fun simpleDiff(oldLines: List<String>, newLines: List<String>): List<Edit> {
        val edits = mutableListOf<Edit>()
        val maxLen = maxOf(oldLines.size, newLines.size)
        for (i in 0 until maxLen) {
            val oldLine = oldLines.getOrNull(i)
            val newLine = newLines.getOrNull(i)
            when {
                oldLine == newLine -> edits.add(Edit(EditType.EQUAL, oldLine!!, i, i))
                oldLine != null && newLine != null -> {
                    edits.add(Edit(EditType.DELETE, oldLine, i, i))
                    edits.add(Edit(EditType.INSERT, newLine, i, i))
                }
                oldLine != null -> edits.add(Edit(EditType.DELETE, oldLine, i, newLines.size))
                newLine != null -> edits.add(Edit(EditType.INSERT, newLine, oldLines.size, i))
            }
        }
        return edits
    }

    private fun groupHunks(edits: List<Edit>, context: Int): List<List<Edit>> {
        // Find ranges of changes with context lines around them
        val changeIndices = edits.indices.filter { edits[it].type != EditType.EQUAL }
        if (changeIndices.isEmpty()) return emptyList()

        val hunks = mutableListOf<List<Edit>>()
        var hunkStart = (changeIndices[0] - context).coerceAtLeast(0)
        var hunkEnd = (changeIndices[0] + context).coerceAtMost(edits.size - 1)

        for (idx in 1 until changeIndices.size) {
            val start = (changeIndices[idx] - context).coerceAtLeast(0)
            val end = (changeIndices[idx] + context).coerceAtMost(edits.size - 1)
            if (start <= hunkEnd + 1) {
                hunkEnd = end // merge overlapping hunks
            } else {
                hunks.add(edits.subList(hunkStart, hunkEnd + 1))
                hunkStart = start
                hunkEnd = end
            }
        }
        hunks.add(edits.subList(hunkStart, hunkEnd + 1))
        return hunks
    }

    // ── HTML/JS Validation ──

    /** Validate HTML file for common issues. */
    fun validateHtml(appId: String, relativePath: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        val content = file.readText()
        val issues = mutableListOf<String>()

        // 1. Missing DOCTYPE
        if (!content.trimStart().startsWith("<!DOCTYPE", ignoreCase = true)) {
            issues.add("[警告] 缺少 <!DOCTYPE html> 声明")
        }

        // 2. Check unclosed tags (self-closing tags excluded)
        val selfClosing = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
        val tagStack = mutableListOf<Pair<String, Int>>() // tag name, line number

        // Strip HTML comments, <script> inner content, and <style> inner content
        // to avoid false positives, preserving line structure for accurate line numbers
        var strippedContent = content
            // Strip HTML comments first (may span multiple lines)
            .replace(Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))) { m ->
                "\n".repeat(m.value.count { it == '\n' })
            }
            // Strip <script>...</script> inner content (.*? handles empty blocks too)
            .replace(Regex("(<script(?:\\s[^>]*)?>)(.*?)(</script>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))) { m ->
                m.groupValues[1] + "\n".repeat(m.groupValues[2].count { it == '\n' }) + m.groupValues[3]
            }
            // Strip <style>...</style> inner content
            .replace(Regex("(<style(?:\\s[^>]*)?>)(.*?)(</style>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))) { m ->
                m.groupValues[1] + "\n".repeat(m.groupValues[2].count { it == '\n' }) + m.groupValues[3]
            }

        // Use a proper tag tokenizer that handles quoted attributes containing '>'
        // Process full content (not line-by-line) so multi-line tags with Vue directives are matched correctly
        val tagTokenRegex = Regex("""</?([a-zA-Z][a-zA-Z0-9-]*)(?:\s+(?:[^>"']*|"[^"]*"|'[^']*')*)?\s*/?>|</([a-zA-Z][a-zA-Z0-9-]*)\s*>""")

        // Pre-compute line start offsets for O(log n) line number lookup
        val lineStartOffsets = mutableListOf(0)
        for ((i, ch) in strippedContent.withIndex()) {
            if (ch == '\n') lineStartOffsets.add(i + 1)
        }
        fun offsetToLine(offset: Int): Int {
            val idx = lineStartOffsets.binarySearch(offset)
            return if (idx >= 0) idx + 1 else -(idx + 1)  // 1-based line number
        }

        for (match in tagTokenRegex.findAll(strippedContent)) {
            val raw = match.value
            val lineNum = offsetToLine(match.range.first)
            if (raw.startsWith("</")) {
                // Close tag
                val tag = (match.groupValues[1].ifBlank { match.groupValues[2] }).lowercase()
                val lastIdx = tagStack.indexOfLast { it.first == tag }
                if (lastIdx >= 0) {
                    tagStack.removeAt(lastIdx)
                } else {
                    issues.add("[错误] 第${lineNum}行: 多余的关闭标签 </$tag>")
                }
            } else {
                // Open tag (or self-closing)
                val tag = match.groupValues[1].lowercase()
                if (tag !in selfClosing && !raw.endsWith("/>")) {
                    tagStack.add(tag to lineNum)
                }
            }
        }
        for ((tag, line) in tagStack) {
            issues.add("[错误] 第${line}行: <$tag> 未闭合")
        }

        // 3. Check for empty <script> src or broken references
        val originalLines = content.lines()
        val scriptSrcRegex = Regex("""<script[^>]+src\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        for ((lineIdx, line) in originalLines.withIndex()) {
            for (match in scriptSrcRegex.findAll(line)) {
                val src = match.groupValues[1]
                if (src.isBlank()) {
                    issues.add("[警告] 第${lineIdx + 1}行: <script> src为空")
                } else if (!src.startsWith("http") && !src.startsWith("//") && !src.startsWith("data:")) {
                    val refFile = resolveSecure(appId, src)
                    if (refFile == null) {
                        issues.add("[警告] 第${lineIdx + 1}行: 可疑的脚本引用路径: $src")
                    } else if (!refFile.exists()) {
                        issues.add("[警告] 第${lineIdx + 1}行: 引用的文件不存在: $src")
                    }
                }
            }
        }

        // 4. Check for common JS issues inside <script> blocks — only check brace balance,
        //    stripping strings and comments first to avoid false positives
        val scriptBlockRegex = Regex("""<script(?:\s[^>]*)?>(.+?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (match in scriptBlockRegex.findAll(content)) {
            val jsRaw = match.groupValues[1]
            val jsStartLine = content.substring(0, match.range.first).count { it == '\n' } + 1
            // Strip JS strings (single/double/template), single-line comments, multi-line comments, regex literals
            val jsStripped = jsRaw
                .replace(Regex("""//[^\n]*"""), "")                            // single-line comments
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")  // multi-line comments
                .replace(Regex(""""(?:[^"\\]|\\.)*""""), "")                   // double-quoted strings
                .replace(Regex("""'(?:[^'\\]|\\.)*'"""), "")                   // single-quoted strings
                .replace(Regex("""`(?:[^`\\]|\\.)*`""", RegexOption.DOT_MATCHES_ALL), "")  // template literals
            var braceCount = 0; var parenCount = 0; var bracketCount = 0
            for (ch in jsStripped) {
                when (ch) { '{' -> braceCount++; '}' -> braceCount--; '(' -> parenCount++; ')' -> parenCount--; '[' -> bracketCount++; ']' -> bracketCount-- }
            }
            if (braceCount != 0) issues.add("[警告] 第${jsStartLine}行附近: JS代码中花括号 {} 不匹配 (差${kotlin.math.abs(braceCount)}个)")
            if (parenCount != 0) issues.add("[警告] 第${jsStartLine}行附近: JS代码中圆括号 () 不匹配 (差${kotlin.math.abs(parenCount)}个)")
            if (bracketCount != 0) issues.add("[警告] 第${jsStartLine}行附近: JS代码中方括号 [] 不匹配 (差${kotlin.math.abs(bracketCount)}个)")
        }

        // 5. CSS link href check
        val linkHrefRegex = Regex("""<link[^>]+href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        for ((lineIdx, line) in originalLines.withIndex()) {
            for (match in linkHrefRegex.findAll(line)) {
                val href = match.groupValues[1]
                if (href.isBlank()) {
                    issues.add("[警告] 第${lineIdx + 1}行: <link> href为空")
                } else if (!href.startsWith("http") && !href.startsWith("//") && !href.startsWith("data:")) {
                    val refFile = resolveSecure(appId, href)
                    if (refFile == null) {
                        issues.add("[警告] 第${lineIdx + 1}行: 可疑的样式引用路径: $href")
                    } else if (!refFile.exists()) {
                        issues.add("[警告] 第${lineIdx + 1}行: 引用的样式文件不存在: $href")
                    }
                }
            }
        }

        return if (issues.isEmpty()) {
            "✓ $relativePath 校验通过，未发现问题"
        } else {
            "发现 ${issues.size} 个问题:\n" + issues.joinToString("\n")
        }
    }

    /** Validate a standalone JS file for syntax issues (bracket balance, common errors). */
    fun validateJs(appId: String, relativePath: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        val content = file.readText()
        val issues = mutableListOf<String>()

        // Strip strings and comments to avoid false positives
        val stripped = content
            .replace(Regex("//[^\n]*"), "")                                      // single-line comments
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")    // multi-line comments
            .replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "")             // triple-quoted (rare)
            .replace(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), "")            // double-quoted strings
            .replace(Regex("'(?:[^'\\\\]|\\\\.)*'"), "")                    // single-quoted strings
            .replace(Regex("`(?:[^`\\\\]|\\\\.)*`", RegexOption.DOT_MATCHES_ALL), "") // template literals

        // 1. Bracket balance
        var braces = 0; var parens = 0; var brackets = 0
        for (ch in stripped) {
            when (ch) { '{' -> braces++; '}' -> braces--; '(' -> parens++; ')' -> parens--; '[' -> brackets++; ']' -> brackets-- }
        }
        if (braces != 0) issues.add("[错误] 花括号 {} 不匹配 (差${kotlin.math.abs(braces)}个)")
        if (parens != 0) issues.add("[错误] 圆括号 () 不匹配 (差${kotlin.math.abs(parens)}个)")
        if (brackets != 0) issues.add("[错误] 方括号 [] 不匹配 (差${kotlin.math.abs(brackets)}个)")

        // 2. Common mistake patterns
        val lines = content.lines()
        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            // Detect placeholder/truncated code
            if (trimmed.matches(Regex("//\\s*(\\.{3}|…|其余|rest of|remaining|todo|TODO).*"))) {
                issues.add("[警告] 第${idx + 1}行: 可能是未完成的占位代码: ${trimmed.take(60)}")
            }
        }

        return if (issues.isEmpty()) {
            "✓ $relativePath JS校验通过"
        } else {
            "$relativePath 发现 ${issues.size} 个问题:\n" + issues.joinToString("\n")
        }
    }

    /** Validate a standalone CSS file for basic syntax issues (bracket balance). */
    fun validateCss(appId: String, relativePath: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        val content = file.readText()
        val issues = mutableListOf<String>()

        // Strip comments
        val stripped = content.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

        // Bracket balance
        var braces = 0
        for (ch in stripped) { when (ch) { '{' -> braces++; '}' -> braces-- } }
        if (braces != 0) issues.add("[错误] 花括号 {} 不匹配 (差${kotlin.math.abs(braces)}个)")

        return if (issues.isEmpty()) {
            "✓ $relativePath CSS校验通过"
        } else {
            "$relativePath 发现 ${issues.size} 个问题:\n" + issues.joinToString("\n")
        }
    }

    // ── Security ──

    /** Sanitize app ID to prevent directory traversal. */
    private fun sanitizeId(id: String): String {
        return id.replace(Regex("[^a-zA-Z0-9\\-_]"), "")
    }

    /** Resolve a relative path within the workspace, preventing path traversal. */
    private fun resolveSecure(appId: String, relativePath: String): File? {
        if (relativePath.isBlank()) return null
        val root = getWorkspaceDir(appId)
        // Normalize: strip leading slashes, replace backslashes
        var normalized = relativePath
            .replace("\\", "/")
            .trimStart('/')
        // Repeatedly strip ../ until stable to prevent bypass via ....// etc.
        var prev: String
        do {
            prev = normalized
            normalized = normalized.replace("../", "")
        } while (normalized != prev)
        normalized = normalized.replace(Regex("//+"), "/") // collapse multiple slashes

        if (normalized.contains("..")) return null // extra safety
        if (normalized.isEmpty()) return null // resolved to root after normalization

        val resolved = File(root, normalized).canonicalFile
        // Ensure the resolved path is still within the workspace root
        if (!resolved.path.startsWith(root.canonicalPath)) return null
        return resolved
    }
}
