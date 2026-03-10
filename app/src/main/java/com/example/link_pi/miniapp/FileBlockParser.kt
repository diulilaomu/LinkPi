package com.example.link_pi.miniapp

/**
 * Parses structured multi-file output from a single AI response.
 *
 * Expected format — AI outputs one or more file blocks:
 *
 * ```
 * <file path="index.html">
 * <!DOCTYPE html>
 * ...
 * </file>
 *
 * <file path="css/style.css">
 * body { ... }
 * </file>
 * ```
 *
 * Also supports the fallback ```html code fence format for single-file apps.
 */
object FileBlockParser {

    data class ParsedFile(val path: String, val content: String)

    /**
     * Extract all `<file path="...">…</file>` blocks from the response.
     *
     * @return List of parsed files, or empty list if no valid blocks found.
     */
    fun parseFiles(response: String): List<ParsedFile> {
        val files = mutableListOf<ParsedFile>()

        // Primary format: <file path="...">content</file>
        val fileBlockRegex = Regex("""<file\s+path\s*=\s*"([^"]+)"[^>]*>([\s\S]*?)</file>""")
        for (match in fileBlockRegex.findAll(response)) {
            val path = match.groupValues[1].trim()
            val content = match.groupValues[2]
                .removePrefix("\n")   // Strip leading newline after tag
                .removeSuffix("\n")   // Strip trailing newline before closing tag
            if (path.isNotBlank() && content.isNotBlank()) {
                files.add(ParsedFile(sanitizePath(path), content))
            }
        }

        if (files.isNotEmpty()) return files

        // Fallback: try code-fenced blocks with filename comments
        // ```html filename="index.html" or ```css filename="style.css"
        val fencedRegex = Regex("""```\w*\s+filename\s*=\s*"([^"]+)"[^\n]*\n([\s\S]*?)```""")
        for (match in fencedRegex.findAll(response)) {
            val path = match.groupValues[1].trim()
            val content = match.groupValues[2].trimEnd()
            if (path.isNotBlank() && content.isNotBlank()) {
                files.add(ParsedFile(sanitizePath(path), content))
            }
        }

        if (files.isNotEmpty()) return files

        // Final fallback: single ```html block → treat as index.html
        val singleHtml = MiniAppParser.extractHtml(response)
        if (singleHtml != null) {
            files.add(ParsedFile("index.html", singleHtml))
        }

        return files
    }

    /**
     * Sanitize file path — prevent directory traversal, normalize separators.
     */
    private fun sanitizePath(raw: String): String {
        return raw
            .replace("\\", "/")
            .replace("..", "")        // strip traversal
            .removePrefix("/")        // no absolute paths
            .trim()
            .ifBlank { "file.html" }
    }
}
