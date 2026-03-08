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

    /** Write (overwrite) a file with content. Creates if not exists. */
    fun writeFile(appId: String, relativePath: String, content: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty. Specify a file path like 'index.html' or 'js/app.js'"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
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

    /** Replace exact text in a file (locate-and-edit). Falls back to whitespace-normalized matching. */
    fun replaceInFile(appId: String, relativePath: String, oldText: String, newText: String): String {
        if (relativePath.isBlank()) return "Error: path cannot be empty"
        val file = resolveSecure(appId, relativePath)
            ?: return "Error: invalid path: $relativePath"
        if (!file.exists()) return "Error: file not found: $relativePath"
        val content = file.readText()

        // 1. Exact match
        val count = content.split(oldText).size - 1
        if (count == 1) {
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

    /** Replace lines by line number range (1-indexed, inclusive). */
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
        val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
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

    /** Check if a workspace has any files. */
    fun hasFiles(appId: String): Boolean {
        val dir = getWorkspaceDir(appId)
        return dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
    }

    /** Get all file paths in a workspace recursively. */
    fun getAllFiles(appId: String): List<String> {
        val root = getWorkspaceDir(appId)
        if (!root.exists()) return emptyList()
        val files = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name != "APP_INFO.md" }.forEach { file ->
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
        val normalized = relativePath
            .replace("\\", "/")
            .trimStart('/')
            .replace(Regex("\\.\\./"), "") // strip ../ attempts
            .replace(Regex("//+"), "/")    // collapse multiple slashes

        if (normalized.contains("..")) return null // extra safety
        if (normalized.isEmpty()) return null // resolved to root after normalization

        val resolved = File(root, normalized).canonicalFile
        // Ensure the resolved path is still within the workspace root
        if (!resolved.path.startsWith(root.canonicalPath)) return null
        return resolved
    }
}
