package com.example.link_pi.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolDefTest {

    // ── ToolDef prompt string ──

    @Test
    fun `toPromptString formats correctly`() {
        val tool = ToolDef(
            name = "read_file",
            description = "Read a file",
            parameters = listOf(
                ToolParam("path", "string", "File path", required = true),
                ToolParam("encoding", "string", "Encoding", required = false)
            )
        )
        val result = tool.toPromptString()
        assertTrue(result.contains("read_file"))
        assertTrue(result.contains("[必需]"))
        assertTrue(result.contains("[可选]"))
        assertTrue(result.contains("Read a file"))
    }

    @Test
    fun `toCompactPromptString marks optional with question mark`() {
        val tool = ToolDef(
            name = "test",
            description = "desc",
            parameters = listOf(
                ToolParam("required_param", "string", "r", required = true),
                ToolParam("optional_param", "string", "o", required = false)
            )
        )
        val result = tool.toCompactPromptString()
        assertTrue(result.contains("required_param,"))
        assertTrue(result.contains("optional_param?"))
    }

    // ── ToolDef JSON schema ──

    @Test
    fun `toFunctionSchema produces valid OpenAI tool schema`() {
        val tool = ToolDef(
            name = "write_file",
            description = "Write content to file",
            parameters = listOf(
                ToolParam("path", "string", "File path", required = true),
                ToolParam("content", "string", "Content", required = true),
                ToolParam("mode", "string", "Write mode", required = false)
            )
        )
        val schema = tool.toFunctionSchema()
        assertEquals("function", schema.getString("type"))
        val func = schema.getJSONObject("function")
        assertEquals("write_file", func.getString("name"))
        val params = func.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        val props = params.getJSONObject("properties")
        assertTrue(props.has("path"))
        assertTrue(props.has("content"))
        assertTrue(props.has("mode"))
        // Required array should only have required params
        val required = params.getJSONArray("required")
        val requiredList = (0 until required.length()).map { required.getString(it) }
        assertTrue("path" in requiredList)
        assertTrue("content" in requiredList)
        assertFalse("mode" in requiredList)
    }

    @Test
    fun `toToolsArray creates JSONArray of schemas`() {
        val tools = listOf(
            ToolDef("a", "desc a", emptyList()),
            ToolDef("b", "desc b", emptyList())
        )
        val arr = ToolDef.toToolsArray(tools)
        assertEquals(2, arr.length())
    }

    // ── ToolParam type mapping ──

    @Test
    fun `ToolDef schema maps number type correctly`() {
        val tool = ToolDef("t", "d", listOf(ToolParam("n", "number", "a number")))
        val schema = tool.toFunctionSchema()
        val propType = schema.getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("n")
            .getString("type")
        assertEquals("number", propType)
    }

    @Test
    fun `ToolDef schema maps boolean type correctly`() {
        val tool = ToolDef("t", "d", listOf(ToolParam("b", "boolean", "a bool")))
        val propType = tool.toFunctionSchema()
            .getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("b")
            .getString("type")
        assertEquals("boolean", propType)
    }

    @Test
    fun `ToolDef schema defaults unknown type to string`() {
        val tool = ToolDef("t", "d", listOf(ToolParam("x", "custom", "unknown")))
        val propType = tool.toFunctionSchema()
            .getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("x")
            .getString("type")
        assertEquals("string", propType)
    }
}

class ToolCallTest {

    @Test
    fun `fromApiToolCalls parses standard OpenAI tool_calls array`() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "call_123")
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "read_file")
                    put("arguments", """{"path":"index.html"}""")
                })
            })
        }
        val calls = ToolCall.fromApiToolCalls(arr)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].toolName)
        assertEquals("index.html", calls[0].arguments["path"])
        assertEquals("call_123", calls[0].id)
    }

    @Test
    fun `fromApiToolCalls handles multiple tool calls`() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "call_1")
                put("function", JSONObject().apply {
                    put("name", "tool_a")
                    put("arguments", """{"x":"1"}""")
                })
            })
            put(JSONObject().apply {
                put("id", "call_2")
                put("function", JSONObject().apply {
                    put("name", "tool_b")
                    put("arguments", """{"y":"2"}""")
                })
            })
        }
        val calls = ToolCall.fromApiToolCalls(arr)
        assertEquals(2, calls.size)
        assertEquals("tool_a", calls[0].toolName)
        assertEquals("tool_b", calls[1].toolName)
    }

    @Test
    fun `fromApiToolCalls skips malformed entries`() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "call_ok")
                put("function", JSONObject().apply {
                    put("name", "good_tool")
                    put("arguments", """{"a":"b"}""")
                })
            })
            put(JSONObject().apply {
                put("bad_field", "no function key")
            })
        }
        val calls = ToolCall.fromApiToolCalls(arr)
        assertEquals(1, calls.size)
        assertEquals("good_tool", calls[0].toolName)
    }

    @Test
    fun `fromApiToolCalls handles empty arguments`() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "call_x")
                put("function", JSONObject().apply {
                    put("name", "no_args_tool")
                    put("arguments", "{}")
                })
            })
        }
        val calls = ToolCall.fromApiToolCalls(arr)
        assertEquals(1, calls.size)
        assertTrue(calls[0].arguments.isEmpty())
    }

    @Test
    fun `fromApiToolCalls handles missing arguments field`() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "call_y")
                put("function", JSONObject().apply {
                    put("name", "tool_no_args")
                })
            })
        }
        val calls = ToolCall.fromApiToolCalls(arr)
        assertEquals(1, calls.size)
        assertTrue(calls[0].arguments.isEmpty())
    }

    @Test
    fun `fromApiToolCalls returns empty list for empty array`() {
        assertTrue(ToolCall.fromApiToolCalls(JSONArray()).isEmpty())
    }
}

class AgentStepTest {

    @Test
    fun `StepType enum has expected values`() {
        assertEquals(4, StepType.values().size)
        assertNotNull(StepType.valueOf("THINKING"))
        assertNotNull(StepType.valueOf("TOOL_CALL"))
        assertNotNull(StepType.valueOf("TOOL_RESULT"))
        assertNotNull(StepType.valueOf("FINAL_RESPONSE"))
    }

    @Test
    fun `AgentStep default values`() {
        val step = AgentStep(StepType.THINKING, "Planning next steps")
        assertEquals("", step.detail)
        assertEquals("", step.source)
    }
}
