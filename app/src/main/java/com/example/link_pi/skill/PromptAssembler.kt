package com.example.link_pi.skill

import com.example.link_pi.agent.ToolDef
import com.example.link_pi.data.model.Skill
import com.example.link_pi.data.model.SkillMode

/**
 * Assembles the system prompt using the 3D injection matrix: Skill × Intent × Phase.
 * Only injects tools, docs, and workflows that are relevant to the current context.
 */
object PromptAssembler {

    fun build(
        skill: Skill,
        intent: UserIntent,
        phase: AgentPhase,
        allTools: List<ToolDef>,
        memorySnapshot: String?
    ): String {
        // 1. Filter tools by Intent × Phase × Skill
        val groups = resolveToolGroups(intent, phase, skill.extraToolGroups)
        val tools = allTools.filter { TOOL_GROUP_MAP[it.name] in groups }
        val toolDescriptions = tools.joinToString("\n") { it.toPromptString() }

        // 2. Build NativeBridge + CDN docs (only for app-related intents, not during planning)
        val bridgeDocs = if (intent.needsApp() && phase != AgentPhase.PLANNING)
            BridgeDocs.build(skill.bridgeGroups) else ""
        val cdnDocs = if (intent.needsApp() && phase != AgentPhase.PLANNING)
            CdnDocs.build(skill.cdnGroups) else ""

        // 3. Build workflow section (mutually exclusive)
        val workflow = buildWorkflow(intent, phase)

        // 4. Build memory section
        val memorySection = buildMemorySection(memorySnapshot, skill.mode)

        // 5. Assemble
        return buildString {
            appendLine(skill.systemPrompt)
            appendLine()
            appendLine("## Agent Mode")
            appendLine()
            appendLine("You are an AI agent. Call tools using the XML format below. ALWAYS include required parameters with real values — never send empty args.")
            appendLine()
            appendLine("### Tool Call Syntax")
            appendLine("<tool_call>")
            appendLine("""{"tool": "tool_name", "args": {"param1": "value1", "param2": "value2"}}""")
            appendLine("</tool_call>")
            appendLine()
            appendLine("You may make multiple tool calls per response. You will receive <tool_result> with results.")
            appendLine()
            appendLine("### Available Tools")
            appendLine(toolDescriptions)
            appendLine()
            appendLine("---")
            if (workflow.isNotBlank()) {
                appendLine()
                appendLine(workflow)
            }
            appendLine()
            appendLine("### Rules")
            appendLine("1. ALWAYS include required parameters with actual values. Never pass empty args `{}` when params are needed.")
            if (intent.needsApp()) {
                appendLine("2. For real-time device data in generated apps, use NativeBridge API (not agent tools).")
                appendLine("3. Output COMPLETE code — never truncate, abbreviate, or use \"// rest of code\".")
                appendLine("4. Generated code goes directly into a WebView — ensure it is complete and runnable.")
            }
            if (bridgeDocs.isNotBlank()) {
                appendLine()
                appendLine(bridgeDocs)
            }
            if (cdnDocs.isNotBlank()) {
                appendLine()
                appendLine(cdnDocs)
            }
            appendLine()
            appendLine(memorySection)
        }.trimEnd()
    }

    private fun buildWorkflow(intent: UserIntent, phase: AgentPhase): String = when {
        intent == UserIntent.CREATE_APP && phase == AgentPhase.GENERATION -> CREATE_APP_WORKFLOW
        intent == UserIntent.MODIFY_APP && phase == AgentPhase.PLANNING -> MODIFY_APP_WORKFLOW
        intent == UserIntent.MODULE_MGMT -> MODULE_WORKFLOW
        else -> ""
    }

    private fun buildMemorySection(snapshot: String?, mode: SkillMode): String {
        return if (mode == SkillMode.CHAT && snapshot != null) {
            """
### Long-term Memory

You have a persistent memory system. Memories survive across conversations.
$snapshot
Your known memories are loaded in the [已知记忆] section above — use them naturally in responses (e.g. address user by name, apply their style preferences).

**When to save**: User tells you their preferences, personal info (name, habits), important facts, or you learn something useful.
**When to search**: When you need memories beyond what's shown above, or for specific topics.

Examples:
<tool_call>
{"tool": "memory_save", "args": {"content": "用户喜欢暗色主题，圆角卡片风格", "tags": "偏好,UI,主题"}}
</tool_call>
<tool_call>
{"tool": "memory_search", "args": {"query": "UI 偏好 主题"}}
</tool_call>

IMPORTANT: Always respect the user's known preferences. If [已知记忆] says the user likes dark theme, default to dark theme without asking.
""".trimIndent()
        } else {
            """
### Long-term Memory

You have a persistent memory system but memories are NOT loaded into your context.
Use memory_search to ACTIVELY look up information when you think past knowledge might be relevant (e.g. the user refers to previous preferences, or past conversations).
Use memory_save when the user tells you important facts or preferences.

<tool_call>
{"tool": "memory_search", "args": {"query": "用户偏好"}}
</tool_call>
<tool_call>
{"tool": "memory_save", "args": {"content": "用户喜欢暗色主题", "tags": "偏好,UI"}}
</tool_call>
""".trimIndent()
        }
    }

    // ── Workflow templates ──

    private val CREATE_APP_WORKFLOW = """
### Workflow: Create App

**Simple app** — output HTML in ```html fences (single file).

**Complex app** — use file tools (preferred for >100 lines JS or >50 lines CSS):
<tool_call>
{"tool": "create_file", "args": {"path": "index.html", "content": "<!DOCTYPE html>..."}}
</tool_call>
<tool_call>
{"tool": "create_file", "args": {"path": "css/style.css", "content": "body{...}"}}
</tool_call>
<tool_call>
{"tool": "create_file", "args": {"path": "js/app.js", "content": "..."}}
</tool_call>

Rules:
- index.html = entry point, use relative paths: `<link href="css/style.css">`, `<script src="js/app.js">`
- Use append_file to write large files in segments
- Use a descriptive <title> tag for the app name
- Make the app mobile-friendly and responsive (use viewport meta tag)
- Use modern CSS for a polished, beautiful UI
- Prefer multi-file mode when the app has significant CSS (>50 lines) or JavaScript (>100 lines)
""".trimIndent()

    private val MODIFY_APP_WORKFLOW = """
### Workflow: Modify Existing App ⭐ IMPORTANT

Follow these steps IN ORDER:

**Step 1** — List apps to get app_id:
<tool_call>
{"tool": "list_saved_apps", "args": {}}
</tool_call>

**Step 2** — Open workspace (MUST pass the actual app_id from Step 1):
<tool_call>
{"tool": "open_app_workspace", "args": {"app_id": "paste-actual-uuid-here"}}
</tool_call>

**Step 3** — Read the code:
<tool_call>
{"tool": "read_workspace_file", "args": {"path": "index.html"}}
</tool_call>

**Step 4** — Make targeted edits:
<tool_call>
{"tool": "replace_in_file", "args": {"path": "index.html", "old_text": "original code", "new_text": "new code"}}
</tool_call>

⚠ CRITICAL: In Step 2, `app_id` must be the actual UUID string from the list_saved_apps result (e.g. "bdf10b82-715b-4d74-bf20-8a35a5728898"). Never pass empty string or omit it.

Tips:
- read_workspace_file shows line numbers (e.g. "42| code"). Use start_line/end_line for large files
- replace_in_file supports whitespace-tolerant matching
- If replace_in_file fails, use replace_lines with line numbers
- For full rewrites, use write_file
""".trimIndent()

    private val MODULE_WORKFLOW = """
### Dynamic Modules (API Services)

You can create reusable API modules that wrap HTTP endpoints. Modules persist across conversations and can be called by you or by generated mini-apps.

**Create a module:**
<tool_call>
{"tool": "create_module", "args": {"name": "httpbin", "description": "HTTPBin测试API", "base_url": "https://httpbin.org", "endpoints": "[{\"name\":\"get_ip\",\"path\":\"/ip\",\"method\":\"GET\",\"description\":\"获取IP\"},{\"name\":\"post_data\",\"path\":\"/post\",\"method\":\"POST\",\"body_template\":\"{\\\"data\\\":\\\"{{data}}\\\"}\",\"description\":\"POST测试\"}]"}}
</tool_call>

**Add endpoints later:**
<tool_call>
{"tool": "add_module_endpoint", "args": {"module_id": "abc12345", "name": "get_headers", "path": "/headers", "method": "GET", "description": "查看请求头"}}
</tool_call>

**Call a module endpoint:**
<tool_call>
{"tool": "call_module", "args": {"module": "httpbin", "endpoint": "post_data", "params": "{\"data\":\"hello world\"}"}}
</tool_call>

**In generated mini-apps**, modules are available via JS:
```javascript
// List available modules
const modules = listModules();

// Call a module endpoint (returns Promise)
callModule('httpbin', 'get_ip', {}).then(r => console.log(r));
callModule('httpbin', 'post_data', {data: 'hello'}).then(r => console.log(r));
```

Tips:
- Use `{{param}}` placeholders in path, bodyTemplate, and header values
- Params passed at call time will replace the placeholders
- Module names are case-insensitive for matching
- Use list_modules to see all created modules
""".trimIndent()
}
