package com.example.link_pi.skill

import com.example.link_pi.data.model.Skill

/**
 * 所有内置 Skill 和系统模板的统一管理。
 *
 * - 角色 Skills（[all]）：用户可选的 AI 角色
 * - 系统模板 Skills（[systemTemplates]）：系统/工作流说明，在 SKILL 管理中展示，由 PromptAssembler 组装时引用
 */
object BuiltInSkills {

    // ═══════════════════════════════════════
    //  系统模板常量（PromptAssembler 按需引用）
    // ═══════════════════════════════════════

    val AGENT_MODE_HEADER = """
## Agent 模式

你是一个 AI Agent，通过 Function Calling 机制调用工具。你可以在一次回复中发起多个工具调用。

### 重要规则
- 必须包含所有必需参数并填写真实值——绝对不要发送空参数。

### 重要：应用创建/修改（仅在普通对话中生效）
当用户在对话中要求创建或修改应用时，**必须**调用 `launch_workbench` 工具弹出工作台引导卡片，不要在对话中直接生成代码。
注意：如果你已经在工作台（应用生成/修改）模式中，则不需要也不应该调用 launch_workbench——直接使用文件工具进行操作。
""".trimIndent()

    val RULE_BASE = "1. 必须包含所有必需参数并填写实际值。当参数必需时，绝对不要传递空参数 `{}`。"

    val RULES_PLANNING_APP = """
2. ⛔ 规划阶段——不要编写任何 HTML/CSS/JS 代码。不要使用 ```html 代码块。只输出纯文本规划。
3. 必须在回复末尾附加 <capability_selection> 块（见上方工作流）。
""".trimIndent()

    val RULES_GENERATION_APP = """
2. 生成应用中需要实时设备数据时，使用 本地NativeBridge API（而不是 Agent 工具）。
3. 输出完整代码——绝对不要截断、省略或使用 "// 其余代码"。
4. 生成的代码将直接在 WebView 中运行——确保完整且可运行。
5. 所有文件写入完成后，必须执行 validate 自检，有错误则立即修复。
6. 编辑现有文件前必须先 read_file 读取（系统强制检查，否则报错）。
7. 工具输出被截断时，可用 read_truncated_output 获取完整内容。
8. 使用 read_plan() 获取规划阶段的架构蓝图或修改计划；使用 inspect_workspace() 获取项目架构分析。
""".trimIndent()

    val CAPABILITY_CATALOG = """
### 能力目录

以下是生成应用时可用的能力。请在规划中选择你需要的。

**工具组**（代码生成时可用的 Agent 工具）：
| 组 | 说明 |
|-----|------|
| NETWORK | fetch_url（HTTP GET）、web_search（互联网搜索）、save_data / load_data（Agent 端键值存储） |
| MODULE | 调用 Python 服务模块（create_module、start_module、stop_module、call_module 等，模块是本地 Python 服务器，支持 HTTP/TCP/UDP） |
| DEVICE | get_device_info、get_battery_level、get_location、vibrate、write_clipboard |
| SSH | ssh_connect、ssh_exec、ssh_disconnect、ssh_upload、ssh_download、ssh_list_remote、ssh_list_sessions、ssh_port_forward — 连接远程服务器，执行命令，SFTP文件传输，端口转发。支持密码/密钥/凭据管理器认证 |

**NativeBridge API**（生成应用的 WebView 中可用的 JavaScript API）：
| 组 | API |
|-----|-----|
| STORAGE | saveData、loadData、removeData、clearData、listKeys、getAppId |
| UI_FEEDBACK | showToast、vibrate、writeClipboard、sendToApp |
| SENSOR | getDeviceInfo、getBatteryLevel、getLocation |
| NETWORK | nativeFetch(url, options) — 绕过 CORS 的 HTTP 请求；callModule(moduleName, path, options) — 调用运行中的 Python 服务模块（Promise）；listModules() — 列出所有模块及运行状态 |
| REALTIME | startServer(port) — 启动 WebSocket 服务器（Promise）；onServerEvent — 服务器事件回调；serverSend/serverBroadcast — 发送消息；stopServer()；getLocalIp() — 获取局域网IP。客户端用标准 new WebSocket('ws://ip:port') 连接 |

**CDN 库**（国内可访问的 CDN 链接，作为文档注入）：
| 组 | 库 |
|-----|------|
| FRAMEWORK | Vue 2/3、React、ReactDOM |
| CHART | Chart.js |
| THREE_D | Three.js |
| UTILS | Axios、Animate.css |

⚡ **模块优先原则**：开发功能时优先使用已有模块（list_modules 查看）。模块不支持的功能，再用上方底层能力组合开发。

注意：基础文件工具（write_file、读取、编辑等）在生成阶段始终可用。你只需选择上方的额外能力。
""".trimIndent()

    /** SSH 专用模式系统提示 — 只生成命令，不做其他任何事 */
    val SSH_MODE_SYSTEM_PROMPT = """
你是一个 SSH 终端助手，正在通过 SSH 连接管理远程服务器。

**你的职责：**
1. 根据用户的需求，生成需要执行的 Shell 命令
2. 解释每条命令的作用和风险
3. 在命令执行后，解读命令的输出结果

**输出格式：**
当你需要执行命令时，使用以下 XML 格式输出命令列表：
<ssh_commands>
<cmd desc="命令说明">命令内容</cmd>
<cmd desc="命令说明">命令内容</cmd>
</ssh_commands>

每条命令必须包含 desc 属性来说明命令的目的。

当你需要解释执行结果时，直接用纯文本回复，简洁明了。

**严格规则：**
- 只输出 Shell 命令和命令解释，不要生成代码、应用、或其他内容
- 不要使用 function calling 工具，只使用 <ssh_commands> 格式
- 每条命令应该是独立可执行的
- 危险操作（rm -rf、格式化、重启服务等）必须在 desc 中明确标注风险
- 不要在回复中暴露密码、密钥等敏感信息
- 如果用户的请求不明确，先询问确认再生成命令
- 当命令可能需要较长时间（如 apt install），在 desc 中说明预计耗时
""".trimIndent()

    // ═══════════════════════════════════════
    //  系统与工作流模板 Skills（SKILL 管理中展示）
    // ═══════════════════════════════════════

    private val SYS_AGENT_MODE = Skill(
        id = "sys_agent_mode", name = "Agent 模式说明", icon = "⚙️",
        description = "AI Agent 工具调用格式（Function Calling）",
        systemPrompt = "你是一个 AI Agent，通过 Function Calling 机制调用工具。${PromptGateway.UNIVERSAL_RULES}",
        isBuiltIn = true
    )

    private val SYS_RULES = Skill(
        id = "sys_rules", name = "系统规则", icon = "📋",
        description = "通用规则（已内联到各域指令中）",
        systemPrompt = "通用规则: ${PromptGateway.UNIVERSAL_RULES}",
        isBuiltIn = true
    )

    private val SYS_CAPABILITY_CATALOG = Skill(
        id = "sys_capability_catalog", name = "能力目录", icon = "📑",
        description = "规划阶段 AI 可选择的能力组清单",
        systemPrompt = PromptCreate.CAPABILITY_CATALOG,
        isBuiltIn = true
    )

    private val SYS_WF_CREATE = Skill(
        id = "sys_wf_create", name = "创建应用工作流", icon = "🆕",
        description = "规划 + 生成阶段的创建应用流程",
        systemPrompt = "## 规划阶段\n\n${PromptCreate.planning()}\n\n---\n\n## 生成阶段\n\n${PromptCreate.generation()}",
        isBuiltIn = true
    )

    private val SYS_WF_MODIFY = Skill(
        id = "sys_wf_modify", name = "修改应用工作流", icon = "✏️",
        description = "规划 + 生成阶段的修改应用流程",
        systemPrompt = "## 规划阶段\n\n${PromptModify.planning()}\n\n---\n\n## 生成阶段\n\n${PromptModify.generation()}",
        isBuiltIn = true
    )

    private val SYS_WF_MODULE = Skill(
        id = "sys_wf_module", name = "模块管理工作流", icon = "🔌",
        description = "Python 服务模块的创建、启动与调用",
        systemPrompt = PromptModule.workflow(),
        isBuiltIn = true
    )

    private val SYS_WF_SSH = Skill(
        id = "sys_wf_ssh", name = "SSH远程工作流", icon = "🖥️",
        description = "SSH连接、命令执行、文件传输与端口转发",
        systemPrompt = PromptSsh.workflow(),
        isBuiltIn = true
    )

    private val SYS_MEMORY = Skill(
        id = "sys_memory", name = "记忆系统", icon = "🧠",
        description = "长期记忆的保存、搜索与使用规则",
        systemPrompt = PromptGateway.buildMemorySection(null),
        isBuiltIn = true
    )

    /** 系统与工作流模板（在 SKILL 管理中展示，不可选为活跃角色） */
    val systemTemplates: List<Skill> = listOf(
        SYS_AGENT_MODE, SYS_RULES, SYS_CAPABILITY_CATALOG,
        SYS_WF_CREATE, SYS_WF_MODIFY, SYS_WF_MODULE, SYS_WF_SSH, SYS_MEMORY
    )

    // ═══════════════════════════════════════
    //  角色 Skills（用户可选的 AI 角色）
    // ═══════════════════════════════════════

    val DEFAULT = Skill(
        id = "builtin_default",
        name = "默认助手",
        icon = "🤖",
        description = "通用对话 + 应用生成",
        systemPrompt = """
你是 LinkPi，一个能创建迷你应用的 AI 助手。当用户要求你创建应用、工具、游戏或任何交互功能时，请生成完整的、自包含的 HTML 应用。

如果用户只是提出普通问题（不是请求创建应用），请以对话方式回复，无需生成 HTML 代码。
        """.trimIndent(),
        isBuiltIn = true,
        bridgeGroups = setOf(BridgeGroup.STORAGE, BridgeGroup.UI_FEEDBACK, BridgeGroup.SENSOR, BridgeGroup.NETWORK),
        cdnGroups = setOf(CdnGroup.FRAMEWORK, CdnGroup.CHART, CdnGroup.THREE_D, CdnGroup.UTILS),
        extraToolGroups = setOf(ToolGroup.DEVICE, ToolGroup.NETWORK, ToolGroup.MODULE, ToolGroup.SSH)
    )

    val all: List<Skill> = listOf(DEFAULT)
}
