package com.example.link_pi.agent

import org.json.JSONArray
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
            val req = if (it.required) "[必需]" else "[可选]"
            "${it.name}: ${it.type} $req — ${it.description}"
        }
        return "- $name($params): $description"
    }

    /** 精简版：签名 + 一句话说明，省略参数描述，减少 token 占用 */
    fun toCompactPromptString(): String {
        val params = parameters.joinToString(", ") { p ->
            val opt = if (!p.required) "?" else ""
            "${p.name}$opt"
        }
        return "- $name($params): $description"
    }

    /** 转换为 OpenAI function calling 的 tools[] 元素格式 */
    fun toFunctionSchema(): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        for (p in parameters) {
            properties.put(p.name, JSONObject().apply {
                put("type", p.type.toJsonSchemaType())
                put("description", p.description)
            })
            if (p.required) required.put(p.name)
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    if (required.length() > 0) put("required", required)
                })
            })
        }
    }

    companion object {
        /** 将 ToolDef 列表转换为 API 请求的 tools JSONArray */
        fun toToolsArray(tools: List<ToolDef>): JSONArray {
            val arr = JSONArray()
            for (t in tools) arr.put(t.toFunctionSchema())
            return arr
        }
    }
}

private fun String.toJsonSchemaType(): String = when (this) {
    "number" -> "number"
    "boolean" -> "boolean"
    "array" -> "array"
    "integer" -> "integer"
    else -> "string"
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
    val arguments: Map<String, String>,
    /** Function calling tool_call_id（用于将结果关联回请求） */
    val id: String? = null
) {
    companion object {
        /**
         * Parse tool_calls from OpenAI function calling API response.
         */
        fun fromApiToolCalls(toolCallsArray: JSONArray): List<ToolCall> {
            return (0 until toolCallsArray.length()).mapNotNull { i ->
                try {
                    val tc = toolCallsArray.getJSONObject(i)
                    val id = tc.getString("id")
                    val func = tc.getJSONObject("function")
                    val name = func.getString("name")
                    val argsStr = func.optString("arguments", "{}")
                    val argsJson = JSONObject(argsStr)
                    val args = mutableMapOf<String, String>()
                    for (key in argsJson.keys()) {
                        args[key] = argsJson.optString(key, "")
                    }
                    ToolCall(name, args, id)
                } catch (_: Exception) { null }
            }
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
 * Signal from AI to redirect user to the Workbench.
 */
data class WorkbenchRedirect(
    val title: String,
    val prompt: String,
    val appId: String? = null
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
