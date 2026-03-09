# LinkPi — AI 驱动的迷你应用生成平台

LinkPi 是一款 Android 原生应用，通过自然语言对话即可生成、运行和管理交互式 HTML5 迷你应用。内置 Agent 编排引擎、意图分类、上下文感知工具注入、长期记忆、动态模块（HTTP/TCP/UDP）、完整的设备原生桥接能力、会话离线存储和局域网 P2P 分享。

---

## 功能亮点

- **AI 对话生成应用** — 描述需求，自动生成可运行的 HTML5 应用（单文件或多文件工作区）
- **多阶段 Agent 编排** — 意图分类→规划→生成→工具执行→截断恢复，最多 15 轮工具迭代
- **多模型管理** — 支持添加多个 LLM 配置，运行时一键切换模型
- **深度思考模式** — 支持启用模型思维链（Thinking），逐步推理复杂问题
- **互联网搜索** — 内置 `web_search` 工具，AI 可主动搜索获取实时信息
- **意图分类** — 自动识别对话/创建应用/修改应用/模块管理/记忆操作 5 种意图
- **上下文感知工具注入** — 10 种工具组按意图×阶段动态加载，避免提示词膨胀
- **自定义 Skill** — 用户可创建并管理自定义 AI 角色，支持意图注入
- **意图注入** — 自定义 Skill 可绑定意图，在触发对应意图时自动注入辅助提示词
- **长期记忆系统** — 自动从对话中提取关键信息，后续对话注入个性化上下文
- **会话离线存储** — 对话历史自动保存到本地，支持多会话切换和管理
- **动态 API 模块** — AI 可创建可复用的服务包装器，支持 HTTP/TCP/UDP 三种协议
- **局域网 P2P 分享** — 通过 UDP 发现 + TCP 传输 + PIN 码配对，在局域网内共享 Skill、模块和应用
- **原生桥接** — 迷你应用可访问设备信息、GPS、振动、剪贴板、持久化存储等
- **nativeFetch** — 绕过 CORS 限制的 HTTP 请求 polyfill
- **工作区文件系统** — 支持多文件项目的创建、编辑、快照回滚、代码搜索
- **运行时错误收集** — WebView 自动捕获 JS 错误，Agent 可通过工具查询并修复
- **应用导出** — 一键导出为 ZIP 分享
- **R8 代码压缩** — Release 构建启用 R8 代码缩减和混淆

---

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.0.21 + Java 11 |
| UI | Jetpack Compose + Material 3 |
| 构建 | AGP 8.13.2、Gradle |
| 网络 | OkHttp 4.12.0 |
| 导航 | Navigation Compose 2.8.4 |
| 安全 | EncryptedSharedPreferences (AES-256 GCM) |
| AI API | OpenAI 兼容格式（默认阿里百炼 DashScope） |
| SDK | minSdk 28（Android 9）、targetSdk 36（Android 15） |

---

## 快速开始

### 前置条件

- Android Studio（建议 Ladybug 或更高版本）
- JDK 17+（Android Studio 自带）
- Android SDK 36

### 构建 & 运行

```bash
git clone <repo-url>
cd link-pi
./gradlew assembleDebug
```

安装到设备后，进入 **设置 → 模型管理**，添加或选择预设模型，填入 API Key 即可使用。

### 预设模型

| 预设名称 | 模型 | 提供商 |
|---------|------|--------|
| 阿里百炼 (Qwen-Max) | qwen-max | 阿里云 |
| 阿里百炼 (Qwen-Plus) | qwen-plus | 阿里云 |
| 阿里百炼 (Qwen-Turbo) | qwen-turbo | 阿里云 |
| 阿里百炼 (Qwen-Long) | qwen-long | 阿里云 |
| OpenAI (GPT-4o) | gpt-4o | OpenAI |
| DeepSeek (Chat) | deepseek-chat | DeepSeek |
| DeepSeek (R1) | deepseek-reasoner | DeepSeek |
| Claude (3.5 Sonnet) | claude-3-5-sonnet | Anthropic |

也可自定义填写任何 OpenAI 兼容 API 端点。每个模型配置独立存储名称、端点、密钥、温度、最大 Tokens 和深度思考开关。

---

## 项目结构

**42 个源文件，共约 10,700 行 Kotlin 代码。**

```
com.example.link_pi/                                          行数
├── MainActivity.kt                      # 单 Activity 入口        18
├── data/model/
│   ├── Conversation.kt                  # 会话元数据模型              7
│   ├── ChatMessage.kt                   # 对话消息模型              18
│   ├── MiniApp.kt                       # 迷你应用模型              12
│   └── Skill.kt                         # 技能定义 + 枚举           38
├── data/
│   └── ConversationStorage.kt           # 会话离线存储（JSON文件）   172
├── network/
│   ├── AiConfig.kt                      # 多模型配置（加密存储）      181
│   └── AiService.kt                     # LLM 调用（流式/非流式）    207
├── agent/
│   ├── AgentOrchestrator.kt             # 多阶段 Agent 编排引擎     636
│   ├── ToolDef.kt                       # 工具模式 & XML 解析       134
│   ├── ToolExecutor.kt                  # 工具执行器（50+ 个工具）    971
│   ├── MemoryExtractor.kt              # 异步记忆自动提取            129
│   ├── MemoryStorage.kt                 # 长期记忆持久化             116
│   └── ModuleStorage.kt                 # 模块系统（HTTP/TCP/UDP）   448
├── bridge/
│   ├── NativeBridge.kt                  # WebView ↔ 原生桥接        253
│   └── RuntimeErrorCollector.kt         # JS 运行时错误收集          24
├── miniapp/
│   ├── MiniAppStorage.kt                # 应用持久化                 53
│   └── MiniAppParser.kt                 # AI 响应提取 HTML          104
├── workspace/
│   └── WorkspaceManager.kt              # 多文件工作区（快照/搜索）   754
├── skill/
│   ├── BuiltInSkills.kt                 # 内置技能 + 系统模板        372
│   ├── SkillStorage.kt                  # 自定义技能持久化            85
│   ├── ToolGroup.kt                     # 10 种工具组 + 可见性规则   152
│   ├── PromptAssembler.kt              # 提示词动态组装（含注入）     134
│   ├── IntentClassifier.kt             # 5 种意图分类器              82
│   ├── BridgeDocs.kt                    # Bridge API 文档           51
│   └── CdnDocs.kt                       # CDN 库文档                43
├── share/
│   └── ShareService.kt                  # 局域网 P2P 分享服务       437
├── util/
│   └── SecurityUtils.kt                 # SSRF 防护工具类            62
└── ui/
    ├── navigation/NavGraph.kt           # 页面路由 + 会话历史面板    342
    ├── theme/Color.kt                   # 颜色定义                    8
    ├── theme/Theme.kt                   # Material 3 主题            41
    ├── theme/Type.kt                    # 字体排版                   16
    ├── chat/
    │   ├── ChatViewModel.kt             # 状态管理 + 会话管理        412
    │   └── ChatScreen.kt                # 对话界面 + Markdown       1591
    ├── miniapp/
    │   ├── MiniAppListScreen.kt         # 应用列表                  239
    │   └── MiniAppScreen.kt             # WebView 运行器            368
    ├── skill/SkillListScreen.kt         # 技能选择 & 管理           731
    └── settings/
        ├── SettingsScreen.kt            # 设置中心                  127
        ├── ModelManageScreen.kt         # 模型管理 + 编辑           619
        ├── MemoryScreen.kt              # 记忆浏览器                291
        ├── ModuleScreen.kt              # 模块管理                  387
        └── ShareScreen.kt              # 局域网分享界面             585
```

---

## 核心架构

### 数据流

```
用户输入 → ChatViewModel.sendMessage()
    ↓
buildApiMessages()  ← 注入系统提示词、上下文暗示、最近 20 条消息
    ↓
IntentClassifier.classify()  ← 5 种意图分类（CONVERSATION / CREATE_APP / MODIFY_APP / MODULE_MGMT / MEMORY_OPS）
    ↓
PromptAssembler.build()  ← 意图×阶段动态组装系统提示词、工具列表、API 文档
    ↓
AgentOrchestrator.run()  ← 多阶段循环（规划 16384 tokens → 生成 65536 tokens）
    ↓
工具调用执行（文件操作、网络请求、搜索、设备信息…）
    ↓
MiniApp 提取 或 WorkspaceManager 多文件构建
    ↓
ChatScreen 展示 + 保存/运行/导出操作
    ↓
MemoryExtractor 异步提取记忆（非阻塞）
```

### 会话离线存储

- **自动保存**：每次发送消息和重新生成后，自动持久化当前会话到磁盘
- **目录结构**：`conversations/{conv-id}/meta.json` + `messages.json`
- **智能序列化**：跳过 base64 图片附件（避免磁盘膨胀），MiniApp 仅存引用信息
- **会话管理**：支持新建/切换/删除会话，启动时自动恢复最近会话
- **路径安全**：所有会话 ID 统一经 `safeId()` 净化，防止路径遍历

### Agent 编排

`AgentOrchestrator` 实现三阶段推理循环：

1. **规划阶段** (16384 tokens) — 意图分类后，AI 分析需求、选择能力组、可调用工具
2. **生成阶段** (65536 tokens) — 产出最终回复或应用代码
3. **精炼阶段** — 检测截断响应并自动续写

工具调用通过 `<tool_call>` XML 块传递，最多迭代 15 次。当检测到文件工具（`create_file`、`write_file` 等）被使用时，自动切换为工作区模式构建多文件应用。

### 意图分类 & 工具注入

`IntentClassifier` 通过轻量级 AI 调用将用户消息分为 5 种意图。`PromptAssembler` 根据意图×阶段组合，从 10 种工具组中动态选择可见工具：

| 工具组 | 包含工具 |
|--------|---------|
| CORE | `get_current_time`、`calculate`、`show_toast` |
| MEMORY | `memory_save`、`memory_search`、`memory_list`、`memory_delete`、`memory_update` |
| APP_CREATE | `create_file`、`write_file`、`append_file`、`create_directory` |
| APP_READ | `read_workspace_file`、`list_workspace_files`、`file_info`、`list_snapshots` |
| APP_EDIT | `replace_in_file`、`replace_lines`、`insert_lines`、`rename_file`、`copy_file`、`delete_workspace_file`、`delete_directory`、`undo_file` |
| APP_NAVIGATE | `list_saved_apps`、`open_app_workspace` |
| CODING | `grep_file`、`grep_workspace`、`get_runtime_errors`、`validate_html`、`diff_file` |
| DEVICE | `get_device_info`、`get_battery_level`、`get_location`、`vibrate`、`write_clipboard` |
| NETWORK | `fetch_url`、`web_search`、`save_data`、`load_data` |
| MODULE | `create_module`、`add_module_endpoint`、`remove_module_endpoint`、`call_module`、`list_modules`、`update_module`、`delete_module` |

### 记忆系统

- **自动提取**：每轮对话后 `MemoryExtractor` 异步分析，提取最多 3 条记忆
- **去重**：与现有记忆词级重叠 >70% 则跳过
- **上限**：最多 200 条
- **注入**：CHAT 模式下自动注入记忆快照；CODING 模式下需主动搜索
- **Agent 工具**：AI 可通过 `memory_save`/`memory_search`/`memory_list`/`memory_update`/`memory_delete` 主动管理记忆
- **存储**：基于 JSON 文件，按 ID 独立存储，线程安全（`@Synchronized`）

### 动态模块

AI 可通过 `create_module` 工具创建可复用的服务包装器：

- 支持 **HTTP**、**TCP**、**UDP** 三种协议
- 每个模块包含名称、基础 URL/主机、协议类型、默认请求头、端点列表
- HTTP 端点：路径参数模板替换、请求头、请求体模板
- TCP 端点：Socket 连接→发送→接收，10s 连接超时 / 15s 读取超时
- UDP 端点：DatagramSocket 发送→接收，5s 超时
- 迷你应用可通过 `callModule()` JavaScript API 直接调用
- SSRF 防护：所有协议均禁止访问私有/内网地址
- 共享 OkHttpClient 实例（HTTP），高效连接池复用

### 局域网 P2P 分享

- **设备发现**：通过 UDP 广播在局域网内自动发现其他 LinkPi 设备
- **PIN 码配对**：4 位数字 PIN 码验证，防止未授权传输
- **传输内容**：支持分享 Skill、API 模块和迷你应用
- **传输协议**：TCP 连接，JSON 格式序列化数据
- **安全**：仅在用户主动开启发现服务后可被发现，发送前需接收端确认 PIN

### 意图注入

自定义 Skill 可通过绑定意图实现**辅助注入**——当用户消息触发对应意图时，匹配的 Skill 系统提示词将被注入到主 Skill 的上下文中：

- 每个 Skill 可绑定 0~5 种意图
- 注入内容有 4KB 总大小限制，超出时截断
- 内置 Skill 的意图注入不可修改

---

## 原生桥接 API

迷你应用通过 `window.NativeBridge` 访问设备能力：

| 类别 | 方法 |
|------|------|
| **设备信息** | `getDeviceInfo()` → JSON、`getBatteryLevel()`、`getLocation()` |
| **设备控制** | `showToast(msg)`、`vibrate(ms)` |
| **存储（按应用隔离）** | `saveData(key, val)`、`loadData(key)`、`removeData(key)`、`clearData()`、`listKeys()`、`getAppId()` |
| **剪贴板** | `writeClipboard(text)` |
| **网络** | `httpRequest(callbackId, url, method, headers, body)` 回调模式 |
| **模块** | `listModules()`、`callModule(callbackId, moduleName, endpointName, paramsJson)` |
| **通信** | `sendToApp(data)` — WebView 向宿主发送数据 |
| **调试** | `reportError(error)` — 上报 JS 运行时错误 |

此外注入 `nativeFetch(url, options)` polyfill，提供类 `fetch()` 的 API 以绕过 CORS 限制。

---

## 工具列表

Agent 共可调用 **47 个工具**：

| 分类 | 工具 |
|------|------|
| **核心** | `get_current_time`、`calculate`、`show_toast` |
| **设备** | `get_device_info`、`get_battery_level`、`get_location`、`vibrate`、`write_clipboard` |
| **网络** | `fetch_url`（仅 HTTPS）、`web_search`（互联网搜索）、`save_data`、`load_data` |
| **文件创建** | `create_file`、`write_file`、`append_file`、`create_directory` |
| **文件读取** | `read_workspace_file`、`list_workspace_files`、`file_info`、`list_snapshots` |
| **文件编辑** | `replace_in_file`、`replace_lines`、`insert_lines`、`rename_file`、`copy_file`、`delete_workspace_file`、`delete_directory`、`undo_file` |
| **应用导航** | `list_saved_apps`、`open_app_workspace` |
| **代码工具** | `grep_file`、`grep_workspace`、`get_runtime_errors`、`validate_html`、`diff_file` |
| **记忆** | `memory_save`、`memory_search`、`memory_list`、`memory_delete`、`memory_update` |
| **模块** | `create_module`、`add_module_endpoint`、`remove_module_endpoint`、`call_module`、`list_modules`、`update_module`、`delete_module` |

---

## 安全措施

- **API 密钥加密** — 使用 `EncryptedSharedPreferences` + `MasterKey` (AES-256 GCM) 存储
- **SSRF 防护** — 统一 `SecurityUtils` 工具类，`NativeBridge`、`fetch_url`、模块系统（HTTP/TCP/UDP）均拒绝访问私有/内网地址，含 DNS 解析防重绑定
- **HTTPS 强制** — `fetch_url` 工具仅允许 HTTPS 请求
- **明文流量禁止** — AndroidManifest 设置 `usesCleartextTraffic="false"`
- **路径遍历防护** — `WorkspaceManager` 做 `resolveSecure` 校验，`ConversationStorage` 统一 `safeId()` 净化
- **端口范围校验** — TCP/UDP 模块端点限制有效端口范围 1-65535
- **线程安全** — `MemoryStorage`、`ModuleStorage` 关键方法使用 `@Synchronized`
- **应用隔离存储** — 每个迷你应用的 `saveData/loadData` 基于独立 appId 隔离
- **PIN 配对** — 局域网分享需 4 位 PIN 码验证，防止未授权传输
- **注入大小限制** — 意图注入提示词累计不超过 4KB，防止上下文膨胀
- **R8 混淆** — Release 构建启用代码缩减和混淆

---

## 关键配置常量

| 配置 | 默认值 |
|------|--------|
| 默认 API 端点 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| 默认模型 | `qwen-max` |
| 最大工具迭代 | 15 轮 |
| 规划阶段 tokens | 16384 |
| 生成阶段 tokens | 65536 |
| 最大记忆条数 | 200 |
| 单次最大提取 | 3 条 |
| 上下文消息数 | 最近 20 条 |
| API 超时 | 300 秒 |
| TCP 连接超时 | 10 秒 |
| TCP 读取超时 | 15 秒 |
| UDP 接收超时 | 5 秒 |

---

## 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | AI API 调用、WebView 内容加载、web_search、模块 HTTP/TCP/UDP |
| `VIBRATE` | 迷你应用振动反馈 |
| `ACCESS_FINE_LOCATION` | GPS 位置（迷你应用使用） |
| `ACCESS_COARSE_LOCATION` | 网络定位 |

---

## 许可证

MIT License — © Wenhe X Rnzy 2026.3.2
