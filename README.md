# LinkPi — AI 驱动的迷你应用生成平台

LinkPi 是一款 Android 原生应用，通过自然语言对话即可生成、运行和管理交互式 HTML5 迷你应用。内置 Agent 编排引擎、工具调用系统、长期记忆、动态模块和完整的设备原生桥接能力。

---

## 功能亮点

- **AI 对话生成应用** — 描述需求，自动生成可运行的 HTML5 应用（单文件或多文件工作区）
- **多阶段 Agent 编排** — 规划→生成→工具执行→截断恢复，最多 15 轮工具迭代
- **8 种内置 Skill** — 默认助手、游戏开发、UI 设计、数据可视化、效率工具、3D 创意、教学助手、纯对话
- **自定义 Skill** — 用户可创建并管理自定义 AI 角色（支持编程/闲聊两种模式）
- **长期记忆系统** — 自动从对话中提取关键信息，后续对话注入个性化上下文
- **动态 API 模块** — AI 可创建可复用的 HTTP 服务包装器，迷你应用可直接调用
- **原生桥接** — 迷你应用可访问设备信息、GPS、振动、剪贴板、持久化存储等
- **nativeFetch** — 绕过 CORS 限制的 HTTP 请求 polyfill
- **工作区文件系统** — 支持多文件项目的创建、编辑、组织
- **应用导出** — 一键导出为 ZIP 分享

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

安装到设备后，进入 **设置 → API 配置**，填入你的 LLM API Key 即可使用。

### 支持的 LLM 预设

| 模型 | 提供商 |
|------|--------|
| qwen-max / qwen-plus / qwen-turbo / qwen-long | 阿里百炼 |
| gpt-4o | OpenAI |
| deepseek-chat | DeepSeek |

也可填写任何 OpenAI 兼容 API 端点。

---

## 项目结构

```
com.example.link_pi/
├── MainActivity.kt                      # 单 Activity 入口
├── data/model/
│   ├── ChatMessage.kt                   # 对话消息模型
│   ├── MiniApp.kt                       # 迷你应用模型
│   └── Skill.kt                         # AI 技能定义 + SkillMode 枚举
├── network/
│   ├── AiConfig.kt                      # API 凭据（加密存储）
│   └── AiService.kt                     # LLM 调用客户端
├── agent/
│   ├── AgentOrchestrator.kt             # 多阶段 Agent 编排引擎
│   ├── ToolDef.kt                       # 工具模式定义 & XML 解析
│   ├── ToolExecutor.kt                  # 工具执行器（17+ 工具）
│   ├── MemoryExtractor.kt              # 异步记忆自动提取
│   ├── MemoryStorage.kt                 # 长期记忆持久化
│   └── ModuleStorage.kt                 # 动态 API 模块系统
├── bridge/
│   └── NativeBridge.kt                  # WebView ↔ 原生 Android 桥接
├── miniapp/
│   ├── MiniAppStorage.kt                # 应用持久化
│   └── MiniAppParser.kt                 # 从 AI 响应提取 HTML
├── workspace/
│   └── WorkspaceManager.kt              # 多文件工作区管理
├── skill/
│   ├── SkillStorage.kt                  # 自定义技能持久化
│   └── BuiltInSkills.kt                 # 8 种内置 AI 技能
└── ui/
    ├── navigation/NavGraph.kt           # 导航路由 + 底部导航
    ├── theme/{Color,Theme,Type}.kt      # Material 3 主题
    ├── chat/
    │   ├── ChatViewModel.kt             # 主状态管理 + 编排调度
    │   └── ChatScreen.kt                # 对话界面（Markdown 渲染）
    ├── miniapp/
    │   ├── MiniAppListScreen.kt         # 应用列表
    │   └── MiniAppScreen.kt             # WebView 运行器
    ├── skill/SkillListScreen.kt         # 技能选择 & 管理
    └── settings/
        ├── SettingsScreen.kt            # 设置中心
        ├── ApiSettingsScreen.kt         # API 配置
        ├── MemoryScreen.kt              # 记忆浏览器
        └── ModuleScreen.kt              # 模块管理
```

---

## 核心架构

### 数据流

```
用户输入 → ChatViewModel.sendMessage()
    ↓
buildApiMessages()  ← 注入系统提示词、上下文暗示、最近 20 条消息
    ↓
AgentOrchestrator.run()  ← 多阶段循环（规划 4096 tokens → 生成 65536 tokens）
    ↓
工具调用执行（文件操作、网络请求、设备信息…）
    ↓
MiniApp 提取 或 WorkspaceManager 多文件构建
    ↓
ChatScreen 展示 + 保存/运行/导出操作
    ↓
MemoryExtractor 异步提取记忆（非阻塞）
```

### Agent 编排

`AgentOrchestrator` 实现三阶段推理循环：

1. **规划阶段** (4096 tokens) — AI 分析需求，可选择调用工具
2. **生成阶段** (65536 tokens) — 产出最终回复或应用代码
3. **恢复阶段** — 检测截断响应并自动续写

工具调用通过 `<tool_call>` XML 块传递，最多迭代 15 次。当检测到文件工具（`create_file`、`write_file` 等）被使用时，自动切换为工作区模式构建多文件应用。

### Skill 系统

每个 Skill 定义一套系统提示词和运行模式：

| 模式 | 行为 |
|------|------|
| **CODING** | 注入工具文档、CDN 库列表、应用生成规则；不注入个人记忆 |
| **CHAT** | 注入记忆快照和个性化上下文；适用于闲聊场景 |

内置 8 种 Skill：

| Skill | 图标 | 模式 | 说明 |
|-------|------|------|------|
| 默认助手 | 🤖 | CHAT | 通用对话 + 应用生成 |
| 游戏开发 | 🎮 | CODING | HTML5 游戏（触控、音效、振动） |
| UI设计师 | 🎨 | CODING | 精美界面应用 |
| 数据可视化 | 📊 | CODING | Chart.js 仪表盘 |
| 效率工具 | ⚡ | CODING | 待办/笔记/定时器 |
| 3D创意 | 🌐 | CODING | Three.js 3D 交互 |
| 教学助手 | 📚 | CODING | 游戏化学习 |
| 纯对话 | 💬 | CHAT | 纯文字对话（不生成应用） |

### 记忆系统

- **自动提取**：每轮对话后 `MemoryExtractor` 异步分析，提取最多 3 条记忆
- **去重**：与现有记忆词级重叠 >70% 则跳过
- **上限**：最多 200 条
- **注入**：CHAT 模式下自动注入记忆快照；CODING 模式下需主动搜索
- **存储**：基于 JSON 文件，按 ID 独立存储，线程安全（`@Synchronized`）

### 动态模块

AI 可通过 `create_module` 工具创建可复用的 HTTP 服务包装器：

- 每个模块包含名称、基础 URL、默认请求头、端点列表
- 端点支持路径参数模板替换
- 迷你应用可通过 `callModule()` JavaScript API 直接调用
- 共享 OkHttpClient 实例，高效连接池复用

---

## 原生桥接 API

迷你应用通过 `window.NativeBridge` 访问设备能力：

| 类别 | 方法 |
|------|------|
| **设备信息** | `getDeviceInfo()` → JSON、`getBatteryLevel()`、`getLocation()` |
| **设备控制** | `showToast(msg)`、`vibrate(ms)` |
| **存储（按应用隔离）** | `saveData(key, val)`、`loadData(key)`、`removeData(key)`、`clearData()`、`listKeys()` |
| **剪贴板** | `writeClipboard(text)` |
| **网络** | `httpRequest(id, url, method, headers, body)` 回调模式 |
| **模块** | `callModule(moduleId, endpoint, params)` |

此外注入 `nativeFetch(url, options)` polyfill，提供类 `fetch()` 的 API 以绕过 CORS 限制。

---

## 工具列表

Agent 可调用以下工具：

| 分类 | 工具 |
|------|------|
| **设备** | `get_device_info`、`get_battery_level`、`get_location`、`get_current_time` |
| **控制** | `vibrate`、`show_toast`、`write_clipboard` |
| **数据** | `save_data`、`load_data` |
| **应用** | `list_saved_apps` |
| **网络** | `fetch_url`（仅 HTTPS） |
| **计算** | `calculate` |
| **文件系统** | `create_file`、`write_file`、`append_file`、`read_workspace_file`、`replace_in_file`、`replace_lines`、`create_directory`、`list_workspace_files`、`open_app_workspace`、`delete_directory`、`rename_file`、`copy_file` |
| **模块** | `create_module`、`list_modules`、`update_module` |

---

## 安全措施

- **API 密钥加密** — 使用 `EncryptedSharedPreferences` + `MasterKey` (AES-256 GCM) 存储
- **SSRF 防护** — `NativeBridge.httpRequest()` 拒绝访问私有/内网地址
- **HTTPS 强制** — `fetch_url` 工具仅允许 HTTPS 请求
- **明文流量禁止** — AndroidManifest 设置 `usesCleartextTraffic="false"`
- **路径遍历防护** — `WorkspaceManager` 对所有文件路径做 `resolveSecure` 校验
- **线程安全** — `MemoryStorage` 关键方法使用 `@Synchronized`
- **应用隔离存储** — 每个迷你应用的 `saveData/loadData` 基于独立 appId 隔离

---

## 关键配置常量

| 配置 | 默认值 |
|------|--------|
| 默认 API 端点 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| 默认模型 | `qwen-max` |
| 最大工具迭代 | 15 轮 |
| 规划阶段 tokens | 4096 |
| 生成阶段 tokens | 65536 |
| 最大记忆条数 | 200 |
| 单次最大提取 | 3 条 |
| 上下文消息数 | 最近 20 条 |
| API 超时 | 300 秒 |

---

## 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | AI API 调用、WebView 内容加载 |
| `VIBRATE` | 迷你应用振动反馈 |
| `ACCESS_FINE_LOCATION` | GPS 位置（迷你应用使用） |
| `ACCESS_COARSE_LOCATION` | 网络定位 |

---

## 许可证

私有项目。
