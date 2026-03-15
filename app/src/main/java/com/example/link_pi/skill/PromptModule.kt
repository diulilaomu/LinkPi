package com.example.link_pi.skill

/**
 * 模块域 (MODULE_MGMT) 的 Prompt 模板。
 *
 * 模块管理没有规划/生成阶段划分，使用单一工作流。
 */
object PromptModule {

    /** 模块管理工作流指令 */
    fun workflow(): String = """
### Python 服务模块

你可以创建 Python 脚本模块，每个模块运行一个本地服务器（HTTP/TCP/UDP），对外提供服务。模块跨会话持久化，可被你或生成的迷你应用调用。

**核心概念：**
- 每个模块 = 一个 Python 服务脚本（如 server.py）
- 启动后监听本地端口（范围 8100-8199，自动分配或手动指定）
- AI 和迷你应用通过 HTTP 调用运行中的模块服务

**服务类型：**
| 类型 | 说明 |
|------|------|
| HTTP | Python http.server 实现的 REST 服务 |
| TCP | 基于 socket 的 TCP 服务器 |
| UDP | 基于 socket 的 UDP 服务器 |

**模板快速创建（推荐）：**
使用 `list_module_templates` 查看可用模板，然后用 `template` 参数快速创建：
→ create_module(name: "my_api", template: "http_server")
→ create_module(name: "my_tcp", template: "tcp_server")
→ create_module(name: "my_udp", template: "udp_server")

模板会自动生成：模块配置 + Python 服务器脚本脚手架（含 /health 健康检查端点）。

**手动创建模块：**
→ create_module(name: "my_service", description: "自定义服务", service_type: "HTTP")
→ write_module_script(module_id: "xxx", filename: "server.py", content: "...")

**服务脚本编写规则：**
- 脚本通过环境变量获取端口：`port = int(os.environ.get('MODULE_PORT', '8100'))`
- 必须绑定到 `0.0.0.0`（允许外部访问）
- HTTP 服务推荐使用 `http.server.HTTPServer` + `BaseHTTPRequestHandler`
- TCP/UDP 服务使用 `socket` 模块
- 脚本在主进程中运行，使用 threading 处理并发
- 可用的 Python 标准库：http.server, socket, threading, json, os, re, math, datetime 等
- **禁用模块：** subprocess, shutil, ctypes, multiprocessing, asyncio, signal

**启动和停止：**
→ start_module(module: "my_api")
→ start_module(module: "my_api", port: "8100")
→ stop_module(module: "my_api")

**调用模块（HTTP 服务）：**
→ call_module(module: "my_api", path: "/hello")
→ call_module(module: "my_api", path: "/process", method: "POST", body: "{\"data\":\"hello\"}")

**脚本管理：**
→ write_module_script(module_id: "xxx", filename: "server.py", content: "...")
→ read_module_script(module_id: "xxx", filename: "server.py")
→ test_module_script(module_id: "xxx", filename: "utils.py", function: "process", params: "{\"data\":\"test\"}")

**在生成的迷你应用中调用模块：**
```javascript
// 列出模块及运行状态
const modules = listModules();

// 调用 HTTP 模块（返回 Promise）
callModule('my_api', '/hello').then(r => console.log(r));
callModule('my_api', '/process', {
  method: 'POST',
  body: { data: 'hello' }
}).then(r => console.log(r));
```

**典型工作流：**
1. 用户说"帮我创建一个 XX 服务"
2. 使用模板或手动创建模块
3. 编写 Python 服务脚本（server.py）
4. 启动模块
5. 测试调用验证功能
6. 告知用户服务已就绪

提示：
- 使用 list_modules 查看所有模块及运行状态
- 使用 list_module_templates 查看可用模板
- 模块名称匹配不区分大小写
- 删除模块会自动停止服务
- 端口范围 8100-8199，避免冲突
- 模块是**本地服务器**，Python 脚本就是服务本身
- 完成模块创建/修改/查询后，直接回复结果即可，**不要**继续规划或生成应用代码
""".trimIndent()

    /** 模块域工具组 */
    val TOOL_GROUPS: Set<ToolGroup> = setOf(
        ToolGroup.CORE, ToolGroup.MEMORY, ToolGroup.MODULE, ToolGroup.NETWORK
    )
}
