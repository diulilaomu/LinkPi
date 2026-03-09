package com.example.link_pi.workspace

import android.content.Context
import java.io.File

/**
 * Manages per-app file workspaces for multi-file project generation.
 * Each workspace is a directory in the app's internal storage where the AI
 * can create files, folders, and edit content — similar to VS Code / Claude Code.
 */
class WorkspaceManager(private val context: Context) {

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
        if (file.exists() && file.isFile) snapshotFile(appId, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Written: $relativePath (${content.length} chars)"
    }

    /** Append content to a file. Creates if not exists. */
    fun appendFile(appId: String, relativePath: String, content: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        file.parentFile?.mkdirs()
        file.appendText(content)
        return "Appended to: $relativePath (+${content.length} chars)"
    }

    /** Read file content. */
    fun readFile(appId: String, relativePath: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        if (!file.isFile) return "Error: not a file: $relativePath"
        val content = file.readText()
        val lines = content.lines()
        // Add line numbers for reference
        val numbered = lines.mapIndexed { i, line -> "${i + 1}| $line" }.joinToString("\n")
        return if (numbered.length > 16000) {
            numbered.take(16000) + "\n...(truncated at line ~${numbered.take(16000).count { it == '\n' }}, total ${lines.size} lines, ${content.length} chars)"
        } else numbered
    }

    /** Read file content by line range (1-indexed, inclusive). */
    fun readFileLines(appId: String, relativePath: String, startLine: Int, endLine: Int): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        val lines = file.readText().lines()
        val s = (startLine - 1).coerceIn(0, lines.size)
        val e = endLine.coerceIn(s, lines.size)
        return lines.subList(s, e).mapIndexed { i, line -> "${s + i + 1}| $line" }.joinToString("\n") +
            "\n(showing lines $startLine-$e of ${lines.size})"
    }

    /** Replace exact text in a file (locate-and-edit). Falls back to whitespace-normalized matching. Auto-snapshots before modifying. */
    fun replaceInFile(appId: String, relativePath: String, oldText: String, newText: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        val content = file.readText()

        // 1. Exact match
        val count = content.split(oldText).size - 1
        if (count == 1) {
            snapshotFile(appId, relativePath)
            file.writeText(content.replace(oldText, newText))
            return "Replaced in: $relativePath (1 occurrence, exact match)"
        }
        if (count > 1) return "Error: text appears $count times in $relativePath, be more specific"

        // 2. Whitespace-normalized match (collapse spaces/tabs, normalize line endings)
        val normalizeWs = { s: String -> s.replace(Regex("[ \\t]+"), " ").replace(Regex("\\r\\n?"), "\n").trim() }
        val oldNorm = normalizeWs(oldText)
        val contentLines = content.lines()
        val oldLines = oldText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        if (oldLines.isNotEmpty()) {
            // Sliding window search over file lines
            val windowSize = oldLines.size
            var matchStart = -1
            var matchEnd = -1
            var matches = 0
            for (i in 0..contentLines.size - windowSize) {
                var isMatch = true
                for (j in oldLines.indices) {
                    if (contentLines[i + j].trim() != oldLines[j]) {
                        isMatch = false
                        break
                    }
                }
                if (isMatch) {
                    matchStart = i
                    matchEnd = i + windowSize
                    matches++
                }
            }
            if (matches == 1) {
                snapshotFile(appId, relativePath)
                val newLines = contentLines.toMutableList()
                val replacement = newText.lines()
                for (k in matchStart until matchEnd) newLines.removeAt(matchStart)
                newLines.addAll(matchStart, replacement)
                file.writeText(newLines.joinToString("\n"))
                return "Replaced in: $relativePath (lines ${matchStart + 1}-$matchEnd, normalized match)"
            }
            if (matches > 1) return "Error: text matches $matches locations in $relativePath, be more specific"
        }

        // 3. Not found — provide helpful context
        val firstLine = oldText.lines().firstOrNull()?.trim() ?: ""
        val hint = if (firstLine.isNotEmpty()) {
            val lineNum = contentLines.indexOfFirst { it.trim().contains(firstLine) }
            if (lineNum >= 0) " Hint: similar text found near line ${lineNum + 1}. Use read_workspace_file to re-read the file, or use replace_lines with line numbers."
            else " The first line of old_text was not found anywhere. Re-read the file with read_workspace_file and try again."
        } else ""
        return "Error: text not found in $relativePath.$hint"
    }

    /** Replace lines by line number range (1-indexed, inclusive). Auto-snapshots before modifying. */
    fun replaceLines(appId: String, relativePath: String, startLine: Int, endLine: Int, newContent: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        val lines = file.readText().lines().toMutableList()
        if (startLine < 1 || startLine > lines.size) return "Error: start_line $startLine out of range (1-${lines.size})"
        val s = startLine - 1
        val e = endLine.coerceAtMost(lines.size)
        if (e < startLine) return "Error: end_line must be >= start_line"
        snapshotFile(appId, relativePath)
        for (k in s until e) lines.removeAt(s)
        lines.addAll(s, newContent.lines())
        file.writeText(lines.joinToString("\n"))
        return "Replaced lines $startLine-$e with ${newContent.lines().size} new lines in $relativePath"
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
            .filter { it.isFile && it.name != "APP_INFO.md" }.forEach { file ->
            files.add(file.relativeTo(root).path.replace("\\", "/"))
        }
        return files
    }

    /**
     * Generate or update APP_INFO.md manifest in the workspace.
     * Contains app name, version, file structure with sizes.
     */
    fun generateManifest(appId: String, appName: String, version: Int = 1) {
        val root = getWorkspaceDir(appId)
        if (!root.exists()) return

        val files = root.walkTopDown()
            .onEnter { it.name != ".snapshots" }
            .filter { it.isFile && it.name != "APP_INFO.md" }
            .sortedBy { it.relativeTo(root).path }
            .toList()

        val totalSize = files.sumOf { it.length() }
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val now = sdf.format(java.util.Date())

        val sb = StringBuilder()
        sb.appendLine("# $appName")
        sb.appendLine()
        sb.appendLine("- **版本**: v$version")
        sb.appendLine("- **文件数**: ${files.size}")
        sb.appendLine("- **总大小**: ${formatSize(totalSize)}")
        sb.appendLine("- **更新时间**: $now")
        sb.appendLine()
        sb.appendLine("## 文件结构")
        sb.appendLine()
        sb.appendLine("```")
        sb.appendLine(buildFileTree(root, files))
        sb.appendLine("```")

        File(root, "APP_INFO.md").writeText(sb.toString())
    }

    private fun buildFileTree(root: File, files: List<File>): String {
        val sb = StringBuilder()
        // Collect all directories
        val dirs = mutableSetOf<String>()
        val fileEntries = mutableListOf<Pair<String, Long>>() // path, size
        for (f in files) {
            val rel = f.relativeTo(root).path.replace("\\", "/")
            fileEntries.add(rel to f.length())
            val parent = f.parentFile
            if (parent != null && parent != root) {
                var d = parent
                while (d != null && d != root) {
                    dirs.add(d.relativeTo(root).path.replace("\\", "/"))
                    d = d.parentFile
                }
            }
        }

        // Build tree lines sorted
        data class Entry(val path: String, val isDir: Boolean, val size: Long)
        val all = mutableListOf<Entry>()
        for (d in dirs) all.add(Entry(d, true, 0))
        for ((p, s) in fileEntries) all.add(Entry(p, false, s))
        all.sortBy { it.path }

        for (entry in all) {
            val depth = entry.path.count { it == '/' }
            val indent = "│   ".repeat(depth)
            val name = entry.path.substringAfterLast('/')
            if (entry.isDir) {
                sb.appendLine("$indent├── $name/")
            } else {
                sb.appendLine("$indent├── $name  (${formatSize(entry.size)})")
            }
        }
        return sb.toString().trimEnd()
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
            .filter { it.isFile && it.name != "APP_INFO.md" }
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
        val lines = file.readText().lines().toMutableList()
        val insertAt = (lineNumber - 1).coerceIn(0, lines.size)
        val newLines = content.lines()
        lines.addAll(insertAt, newLines)
        file.writeText(lines.joinToString("\n"))
        return "Inserted ${newLines.size} line(s) at line $lineNumber in $relativePath (total now ${lines.size} lines)"
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
        val openTagRegex = Regex("<([a-zA-Z][a-zA-Z0-9]*)(?:\\s[^>]*)?>")
        val closeTagRegex = Regex("</([a-zA-Z][a-zA-Z0-9]*)\\s*>")
        val tagStack = mutableListOf<Pair<String, Int>>() // tag name, line number

        // Strip <script>...</script> and <style>...</style> inner content to avoid false positives,
        // but preserve line structure so line numbers stay accurate
        val strippedContent = content
            .replace(Regex("(<script(?:\\s[^>]*)?>)(.+?)(</script>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)) ) { m ->
                m.groupValues[1] + "\n".repeat(m.groupValues[2].count { it == '\n' }) + m.groupValues[3]
            }
            .replace(Regex("(<style(?:\\s[^>]*)?>)(.+?)(</style>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)) ) { m ->
                m.groupValues[1] + "\n".repeat(m.groupValues[2].count { it == '\n' }) + m.groupValues[3]
            }
        val lines = strippedContent.lines()
        for ((lineIdx, line) in lines.withIndex()) {
            for (match in openTagRegex.findAll(line)) {
                val tag = match.groupValues[1].lowercase()
                if (tag !in selfClosing && !match.value.endsWith("/>")) {
                    tagStack.add(tag to (lineIdx + 1))
                }
            }
            for (match in closeTagRegex.findAll(line)) {
                val tag = match.groupValues[1].lowercase()
                val lastIdx = tagStack.indexOfLast { it.first == tag }
                if (lastIdx >= 0) {
                    tagStack.removeAt(lastIdx)
                } else {
                    issues.add("[错误] 第${lineIdx + 1}行: 多余的关闭标签 </$tag>")
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
                    // Check if local file exists
                    val refFile = resolveSecure(appId, src)
                    if (refFile != null && !refFile.exists()) {
                        issues.add("[警告] 第${lineIdx + 1}行: 引用的文件不存在: $src")
                    }
                }
            }
        }

        // 4. Check for common JS issues inside <script> blocks
        val scriptBlockRegex = Regex("""<script(?:\s[^>]*)?>(.+?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (match in scriptBlockRegex.findAll(content)) {
            val jsContent = match.groupValues[1]
            val jsStartLine = content.substring(0, match.range.first).count { it == '\n' } + 1
            // Check for unmatched braces
            var braceCount = 0; var parenCount = 0; var bracketCount = 0
            for (ch in jsContent) {
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
                    if (refFile != null && !refFile.exists()) {
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
