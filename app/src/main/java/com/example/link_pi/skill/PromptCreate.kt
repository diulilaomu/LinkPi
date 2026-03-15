package com.example.link_pi.skill

/**
 * 生成域 (CREATE_APP) 各阶段的 Prompt 模板。
 *
 * 阶段：规划 → 生成(FULL) → 生成(FAST) → 自检
 */
object PromptCreate {

    // ─────────────────────────────────────
    //  规划阶段
    // ─────────────────────────────────────

    /** 能力目录 — 规划阶段 AI 选择能力的参考菜单 */
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

    /** 规划阶段域指令 */
    fun planning(): String = """
### 工作流：创建应用 — 规划阶段

你当前处于规划阶段。你的任务是分析和规划，而不是写代码。

1. **检查已有模块**：先用 list_modules 查看是否已有相关模块可复用
2. 审查用户需求以及任何已知记忆（如已加载）
3. **架构规划**（结构化输出）：
   a. **功能清单**：列出用户要求的每个功能点（1-2 句）
   b. **文件结构设计**：规划需要创建的文件及每个文件的职责
      - 例如：`index.html`（页面结构+入口）、`css/style.css`（样式）、`js/app.js`（主逻辑）、`js/utils.js`（工具函数）
   c. **数据流**：说明文件间如何交互（谁引用谁、数据如何流动）
   d. **技术方案**：使用的框架/库、NativeBridge API、模块（遵循上方能力目录中的模块优先原则）
4. 从上方「能力目录」中选择应用所需的能力
5. **输出结构化架构蓝图**（新建应用必须）——在 capability_selection 之前输出：

<architecture>
项目类型: 数据仪表盘 / 表单应用 / 游戏 / 工具 / 展示页面 / 数据可视化 / 实时通信 / 通用应用
文件清单:
  - index.html: [职责描述，如"入口 + 主布局"]
  - css/style.css: [职责描述]
  - js/app.js: [职责描述]
  - js/xxx.js: [职责描述]
依赖关系:
  - index.html → css/style.css, js/app.js
  - js/app.js → js/utils.js
关键交互:
  - [描述核心数据流，如"app.js 初始化时调用 loadData()，数据变化时更新 DOM"]
CSS↔JS 约定:
  - [列出关键 CSS 类名及其用途，如".card → 数据卡片容器, .is-loading → 加载状态"]
</architecture>

7. **必须**在回复末尾附加 `<capability_selection>` 和 `<generation_mode>` 块（缺少将导致生成阶段缺少 API 文档）：

<capability_selection>
tools: NETWORK, MODULE
bridge: STORAGE, UI_FEEDBACK, SENSOR
cdn: CHART, FRAMEWORK
</capability_selection>

<generation_mode>FULL</generation_mode>

- 只列出实际需要的组；没有选择的行可省略
- 基础文件工具（write_file 等）始终可用——无需选择
- 系统会在生成阶段注入你所选能力的完整文档
- generation_mode 始终填 FULL

**规划阶段规则**：
- ⛔ 不要编写任何 HTML/CSS/JS 代码，不要使用代码块
- ⛔ 只输出规划文本 + architecture + capability_selection + generation_mode
""".trimIndent()

    // ─────────────────────────────────────
    //  生成阶段 (FULL)
    // ─────────────────────────────────────

    /** 生成阶段域指令 (FULL模式) */
    fun generation(): String = """
### 工作流：创建应用 — 生成阶段

→ 先调用 read_plan() 获取规划阶段的架构蓝图（architecture）
→ 如需了解已有代码结构，调用 inspect_workspace() 获取依赖图和导出索引

**严格按照架构蓝图生成代码。**
如果规划中定义了文件清单和职责分配，按该结构创建文件；如果规划中定义了 CSS↔JS 约定，确保类名一致。

使用文件工具逐个创建文件（JS 超过 200 行或 CSS 超过 100 行时必须拆分为多个文件）：
→ write_file(path: "xxx.html", content: "<!DOCTYPE html>...")

**规则**：
- ⚡ 模块优先：需要外部 API/设备通信的功能，优先使用 callModule() 调用已有模块
- 📁 文件应拆尽拆：HTML、CSS、JS 必须分离，JS 按功能模块拆分，禁止单文件塞入全部代码
- index.html 为入口文件，使用相对路径引用 CSS/JS
- 应用需移动端友好且响应式（viewport meta 标签）
- 使用现代 CSS 打造精美 UI，使用描述性 <title> 标签
- 生成应用中需要实时设备数据时，使用 NativeBridge API（而不是 Agent 工具）
- 输出完整代码——绝对不要截断、省略或使用 "// 其余代码"
- 工具输出被截断时，可用 read_truncated_output 获取完整内容

**多文件协调**：
- 先创建被依赖的文件（CSS、工具函数），再创建依赖者（主逻辑、入口HTML）
- JS 引用的 CSS 类名必须与 CSS 定义完全一致，状态类用 .is-xxx / .has-xxx 前缀
- <script> 加载顺序保证依赖项在前

**文件大小控制**：
- 每个 write_file 调用写入**一个完整文件**，禁止对同一文件分段写入（易引发语法断裂）
- 若预计单文件超 300 行，在架构规划阶段就拆分为多个独立文件（如 api.js + ui.js + app.js）
- 拆分要按功能边界，每个文件有清晰的职责

**⚠ 生成后自检（所有文件全部写入完成后才能执行，严禁在文件创建中途执行）**：
→ validate(path: "index.html")
→ validate(path: "js/app.js")
validate 返回 `{"valid": true/false, "errors": [...]}` — 有错误时立即修复。
""".trimIndent()

    // ─────────────────────────────────────
    //  自检阶段
    // ─────────────────────────────────────

    /** 自检指令 — 用于 runPostGenerationCheck */
    fun selfCheck(): String = """
### 自检阶段

你现在处于自检阶段。逐一检查所有已生成的文件，发现错误立即修复。

**检查流程**：
1. 对 index.html 执行 validate：
→ validate(path: "index.html")

2. 对每个 .js 文件执行 validate（根据你创建的文件列表逐个检查）：
→ validate(path: "js/app.js")

3. validate 返回 `{"valid": true/false, "errors": [...]}` — 有错误时：
   - 根据 errors 中的**行号**和**描述**定位问题
   - 使用 edit_file 或 write_file 修复
   - 修复后**再次 validate** 确认问题已解决

**常见问题处理**：
- 标签未闭合 → 补全闭合标签
- JS 花括号/圆括号不匹配 → 检查函数定义和条件语句的括号配对
- 引用文件不存在 → 检查路径拼写，或补创建缺失文件
- CSS 类名与 JS 不一致 → 统一为 CSS 中定义的类名
""".trimIndent()

}
