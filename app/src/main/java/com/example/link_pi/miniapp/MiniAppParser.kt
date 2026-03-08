package com.example.link_pi.miniapp

import com.example.link_pi.data.model.MiniApp
import java.util.UUID

object MiniAppParser {

    fun extractMiniApp(response: String): MiniApp? {
        val html = extractHtml(response) ?: return null
        val name = extractTitle(html) ?: "Mini App"
        val description = extractDescription(response)

        return MiniApp(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            htmlContent = html
        )
    }

    fun extractHtml(response: String): String? {
        // Try complete ```html ... ``` blocks first
        val htmlBlockRegex = Regex("```html\\s*\\n([\\s\\S]*?)\\n```")
        val match = htmlBlockRegex.find(response)
        if (match != null) return match.groupValues[1].trim()

        // Try generic complete ``` blocks that look like HTML
        val codeBlockRegex = Regex("```\\s*\\n([\\s\\S]*?)\\n```")
        val codeMatch = codeBlockRegex.find(response)
        if (codeMatch != null) {
            val code = codeMatch.groupValues[1].trim()
            if (code.contains("<html", ignoreCase = true) ||
                code.contains("<!DOCTYPE", ignoreCase = true)
            ) {
                return code
            }
        }

        // Handle truncated response: opening ```html but no closing ```
        val truncatedHtmlRegex = Regex("```html\\s*\\n([\\s\\S]+)")
        val truncatedMatch = truncatedHtmlRegex.find(response)
        if (truncatedMatch != null) {
            val code = truncatedMatch.groupValues[1].trim()
                .removeSuffix("```").trim() // Remove trailing ``` if partially present
            if (code.contains("<", ignoreCase = true)) {
                return repairHtml(code)
            }
        }

        // Handle truncated generic code blocks
        val truncatedCodeRegex = Regex("```\\s*\\n([\\s\\S]+)")
        val truncatedCodeMatch = truncatedCodeRegex.find(response)
        if (truncatedCodeMatch != null) {
            val code = truncatedCodeMatch.groupValues[1].trim()
                .removeSuffix("```").trim()
            if (code.contains("<html", ignoreCase = true) ||
                code.contains("<!DOCTYPE", ignoreCase = true)
            ) {
                return repairHtml(code)
            }
        }

        return null
    }

    /**
     * Attempt to repair truncated HTML by closing open tags.
     */
    private fun repairHtml(html: String): String {
        var result = html
        // Close unclosed script tags
        val scriptOpens = Regex("<script", RegexOption.IGNORE_CASE).findAll(result).count()
        val scriptCloses = Regex("</script>", RegexOption.IGNORE_CASE).findAll(result).count()
        repeat(scriptOpens - scriptCloses) { result += "\n</script>" }

        // Close unclosed style tags
        val styleOpens = Regex("<style", RegexOption.IGNORE_CASE).findAll(result).count()
        val styleCloses = Regex("</style>", RegexOption.IGNORE_CASE).findAll(result).count()
        repeat(styleOpens - styleCloses) { result += "\n</style>" }

        // Close body/html if missing
        if (!result.contains("</body>", ignoreCase = true) &&
            result.contains("<body", ignoreCase = true)
        ) {
            result += "\n</body>"
        }
        if (!result.contains("</html>", ignoreCase = true) &&
            result.contains("<html", ignoreCase = true)
        ) {
            result += "\n</html>"
        }
        return result
    }

    private fun extractTitle(html: String): String? {
        val titleRegex = Regex("<title>(.+?)</title>", RegexOption.IGNORE_CASE)
        return titleRegex.find(html)?.groupValues?.get(1)?.trim()
    }

    private fun extractDescription(response: String): String {
        val textBeforeCode = response.substringBefore("```").trim()
        return if (textBeforeCode.length > 200) {
            textBeforeCode.take(200) + "..."
        } else {
            textBeforeCode
        }
    }

    fun getDisplayText(response: String): String {
        var text = response
        // Remove complete code blocks
        text = text.replace(Regex("```html\\s*\\n[\\s\\S]*?\\n```"), "")
        text = text.replace(Regex("```\\s*\\n[\\s\\S]*?\\n```"), "")
        // Remove truncated code blocks (opening ``` without closing)
        text = text.replace(Regex("```html\\s*\\n[\\s\\S]*"), "")
        text = text.replace(Regex("```\\s*\\n[\\s\\S]*"), "")
        return text.trim()
    }
}
