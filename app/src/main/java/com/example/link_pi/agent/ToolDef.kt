package com.example.link_pi.agent

import org.json.JSONObject

/**
 * Defines a tool that the AI agent can invoke.
 */
data class ToolDef(
    val name: String,
    val description: String,
    val parameters: List<ToolParam>
) {
    fun toPromptString(): String {
        val params = parameters.joinToString(", ") {
            val req = if (it.required) "[required]" else "[optional]"
            "${it.name}: ${it.type} $req — ${it.description}"
        }
        return "- $name($params): $description"
    }
}

data class ToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

/**
 * A parsed tool invocation from the AI response.
 */
data class ToolCall(
    val toolName: String,
    val arguments: Map<String, String>
) {
    companion object {
        /**
         * Parse all <tool_call> blocks from the AI response text.
         */
        fun parseAll(text: String): List<ToolCall> {
            val regex = Regex("<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>")
            return regex.findAll(text).mapNotNull { match ->
                try {
                    val raw = match.groupValues[1].trim()
                    val jsonStr = sanitizeJsonNewlines(raw)
                    val json = JSONObject(jsonStr)
                    val name = json.getString("tool")
                    val args = mutableMapOf<String, String>()
                    val argsJson = json.optJSONObject("args")
                    if (argsJson != null) {
                        for (key in argsJson.keys()) {
                            // Use opt to handle non-string values gracefully
                            args[key] = argsJson.optString(key, "")
                        }
                    }
                    ToolCall(name, args)
                } catch (_: Exception) {
                    null
                }
            }.toList()
        }

        /**
         * Check if text contains a truncated (unclosed) tool_call block.
         */
        fun hasTruncatedToolCall(text: String): Boolean {
            val openCount = Regex("<tool_call>").findAll(text).count()
            val closeCount = Regex("</tool_call>").findAll(text).count()
            return openCount > closeCount
        }

        /**
         * Strip tool_call blocks from text to get the display portion.
         * Also strips truncated (unclosed) tool_call blocks.
         */
        fun stripToolCalls(text: String): String {
            return text
                .replace(Regex("<tool_call>[\\s\\S]*?</tool_call>"), "")
                .replace(Regex("<tool_call>[\\s\\S]*$"), "") // truncated block
                .trim()
        }

        /**
         * Fix literal newlines/tabs inside JSON string values.
         * AI sometimes outputs raw newlines in JSON strings instead of \\n escapes.
         */
        private fun sanitizeJsonNewlines(raw: String): String {
            val sb = StringBuilder()
            var inString = false
            var escaped = false
            for (ch in raw) {
                if (escaped) {
                    sb.append(ch)
                    escaped = false
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    sb.append(ch)
                    continue
                }
                if (ch == '"') {
                    inString = !inString
                    sb.append(ch)
                    continue
                }
                if (inString) {
                    when (ch) {
                        '\n' -> { sb.append("\\n"); continue }
                        '\r' -> continue
                        '\t' -> { sb.append("\\t"); continue }
                    }
                }
                sb.append(ch)
            }
            return sb.toString()
        }
    }
}

/**
 * Result of executing a tool.
 */
data class ToolResult(
    val toolName: String,
    val success: Boolean,
    val result: String
)

/**
 * One step in the agent's execution trace.
 */
data class AgentStep(
    val type: StepType,
    val description: String,
    val detail: String = ""
)

enum class StepType {
    THINKING,
    TOOL_CALL,
    TOOL_RESULT,
    FINAL_RESPONSE
}
