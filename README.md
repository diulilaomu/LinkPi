<p align="center">
  <img src="app/src/main/assets/logo.png" alt="LinkPi Logo" width="200" />
</p>

<p align="center">
  <em>「愿为AI应用探索的后行者」</em>
</p>
# LinkPi — AI 驱动的迷你应用生成平台
<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-blue" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Compose-Material3-purple" alt="Compose" />
  <img src="https://img.shields.io/badge/minSdk-28-orange" alt="minSdk" />
  <img src="https://img.shields.io/badge/License-MIT-brightgreen" alt="MIT" />
</p>

---

LinkPi（灵枢）是一款 Android 原生应用，内置完整的 AI Agent 编排引擎。用户只需描述需求，AI 将自动规划、生成、调试并交付可运行的 HTML5 应用。支持多阶段工具调用循环、5 种意图分类、10 种动态工具组、长期记忆、动态 API 模块（HTTP/TCP/UDP）、完整设备原生桥接、会话离线存储和局域网 P2P 分享。

**42 个 Kotlin 源文件，约 10,700 行代码。**

---

## 目录

- [功能亮点](#功能亮点)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [预设模型](#预设模型)
- [项目结构](#项目结构)
- [核心架构](#核心架构)
  - [数据流总览](#数据流总览)
  - [Agent 编排引擎](#agent-编排引擎)
  - [意图分类系统](#意图分类系统)
  - [工具注入矩阵](#工具注入矩阵)
  - [Skill 系统](#skill-系统)
  - [记忆系统](#记忆系统)
  - [动态 API 模块](#动态-api-模块)
  - [会话离线存储](#会话离线存储)
  - [局域网 P2P 分享](#局域网-p2p-分享)
  - [工作区文件系统](#工作区文件系统)
- [原生桥接 API](#原生桥接-api)
- [Agent 工具列表](#agent-工具列表)
- [CDN 库支持](#cdn-库支持)
- [安全措施](#安全措施)
- [ProGuard / R8 配置](#proguard--r8-配置)
- [关键配置常量](#关键配置常量)
- [权限](#权限)
- [许可证](#许可证)

---

## 功能亮点

| 功能 | 说明 |
|------|------|
| **AI 对话生成应用** | 描述需求，自动生成可运行的 HTML5 应用（单文件或多文件工作区） |
| **多阶段 Agent 编排** | 意图分类→规划→生成→工具执行→截断恢复→自检修复，最多 15 轮迭代 |
| **多模型管理** | 8 种预设模型 + 自定义端点，运行时一键切换，API 密钥 AES-256-GCM 加密 |
| **深度思考** | 支持启用模型思维链（Thinking），流式显示推理过程 |
| **互联网搜索** | 内置 `web_search` 工具（DuckDuckGo），AI 可主动搜索实时信息 |
| **5 种意图分类** | CONVERSATION / CREATE_APP / MODIFY_APP / MODULE_MGMT / MEMORY_OPS |
| **10 种动态工具组** | 按意图×阶段×能力选择动态加载，避免提示词膨胀 |
| **自定义 Skill** | 创建 AI 角色，配置知识模式、桥接组、CDN 组、意图注入 |
| **意图注入** | 自定义 Skill 绑定意图，触发时自动注入辅助提示词（4KB 上限）|
| **长期记忆** | 自动提取最多 200 条记忆，70% 词级去重，后续对话注入个性化上下文 |
| **会话离线存储** | 多会话管理，自动保存/恢复，智能序列化（跳过 base64 图片）|
| **49 个 Agent 工具** | 文件操作、网络请求、设备控制、记忆管理、代码搜索、模块调用等 |
| **动态 API 模块** | AI 创建 HTTP/TCP/UDP 服务包装器，应用和 Agent 均可调用 |
| **局域网 P2P 分享** | UDP 发现 + TCP 传输 + 4 位 PIN 配对，共享 Skill/模块/应用 |
| **15 种原生桥接** | 设备信息、GPS、振动、剪贴板、隔离存储、nativeFetch、模块调用 |
| **工作区文件系统** | 多文件项目 CRUD、快照回滚、grep 搜索、HTML 校验、diff 对比 |
| **自研 Markdown 渲染** | 纯 Compose 实现，支持标题/列表/引用/表格/代码块/内联样式 |
| **运行时错误收集** | WebView JS 错误自动捕获，Agent 可查询并修复 |
| **应用导出** | 一键导出为 HTML 或 ZIP（多文件工作区）|
| **R8 代码压缩** | Release 构建启用代码缩减和混淆 |

---

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin + Java | 2.0.21 / Java 11 |
| UI 框架 | Jetpack Compose + Material 3 + Material Icons Extended | BOM 2024.09.00 |
| 构建 | Android Gradle Plugin | 8.13.2 |
| 网络 | OkHttp | 4.12.0 |
| 安全 | EncryptedSharedPreferences + MasterKey | security-crypto 1.1.0-alpha06 |
| 生命周期 | lifecycle-runtime-ktx | 2.10.0 |
| Activity | activity-compose | 1.12.4 |
| 核心 | core-ktx | 1.17.0 |
| 导航 | Navigation Compose | 2.8.4 |
| AI API | OpenAI 兼容格式（SSE 流式）| — |
| Markdown | 纯 Compose 自研渲染（无第三方库）| — |
| SDK | minSdk 28 / targetSdk 36 | Android 9 ~ Android 15 |

---

## 快速开始

### 前置条件

- Android Studio Ladybug 或更高版本
- JDK 17+（Android Studio 自带）
- Android SDK 36

### 构建 & 运行

```bash
git clone https://github.com/diulilaomu/LinkPi.git
cd LinkPi

# Debug 构建（含 R8，但跳过混淆）
./gradlew assembleDebug

# Release 构建（完整 R8 缩减 + 混淆）
./gradlew assembleRelease
```

安装到设备后：
1. 进入 **设置 → 模型管理**
2. 选择一个预设模型（如阿里百炼 Qwen-Plus）或自定义端点
3. 填入 API Key
4. 返回聊天页，开始对话

---

## 预设模型

| 预设名称 | 模型 ID | 提供商 | API 端点 |
|---------|---------|--------|----------|
| 阿里百炼 (Qwen-Max) | `qwen-max` | 阿里云 | `dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| 阿里百炼 (Qwen-Plus) | `qwen-plus` | 阿里云 | 同上 |
| 阿里百炼 (Qwen-Turbo) | `qwen-turbo` | 阿里云 | 同上 |
| 阿里百炼 (Qwen-Long) | `qwen-long` | 阿里云 | 同上 |
| OpenAI (GPT-4o) | `gpt-4o` | OpenAI | `api.openai.com/v1/chat/completions` |
| DeepSeek (Chat) | `deepseek-chat` | DeepSeek | `api.deepseek.com/v1/chat/completions` |
| DeepSeek (R1) | `deepseek-reasoner` | DeepSeek | `api.deepseek.com/v1/chat/completions` |
| Claude (3.5 Sonnet) | `claude-3-5-sonnet-20241022` | Anthropic | `api.anthropic.com/v1/chat/completions` |

也可自定义填写任何 OpenAI 兼容 API 端点。每个模型配置独立存储：名称、端点、密钥（AES-256-GCM 加密）、温度（默认 0.7）、最大 Tokens（默认 65536）和深度思考开关。

---

## 项目结构

```
com.example.link_pi/                                               行数
├── MainActivity.kt                         # 单 Activity 入口，EdgeToEdge          18
│
├── data/
│   ├── model/
│   │   ├── Conversation.kt                 # 会话元数据（id/title/时间戳）            7
│   │   ├── ChatMessage.kt                  # 消息模型（含附件/AgentStep/思维链）      18
│   │   ├── MiniApp.kt                      # 迷你应用模型（单文件/工作区模式）         12
│   │   └── Skill.kt                        # Skill 定义 + SkillMode 枚举           38
│   └── ConversationStorage.kt              # 会话离线存储（目录/JSON 序列化）         172
│
├── network/
│   ├── AiConfig.kt                         # 多模型配置（加密存储/预设/迁移）         181
│   └── AiService.kt                        # LLM 调用（流式 SSE / 非流式 / 思维链） 207
│
├── agent/
│   ├── AgentOrchestrator.kt                # 三阶段 Agent 编排引擎                  636
│   ├── ToolExecutor.kt                     # 工具执行器（49 工具 + 18 别名）         971
│   ├── ToolDef.kt                          # 工具定义 + XML 解析 + AgentStep        134
│   ├── MemoryExtractor.kt                  # 异步记忆自动提取（去重/上限控制）         129
│   ├── MemoryStorage.kt                    # 长期记忆持久化（按 ID 独立 JSON）        116
│   └── ModuleStorage.kt                    # 动态模块系统（HTTP/TCP/UDP CRUD+调用）  448
│
├── bridge/
│   ├── NativeBridge.kt                     # WebView ↔ 原生桥接（15 个接口）         253
│   └── RuntimeErrorCollector.kt            # JS 运行时错误收集器（按 appId 隔离）      24
│
├── miniapp/
│   ├── MiniAppStorage.kt                   # 应用 JSON 持久化                        53
│   └── MiniAppParser.kt                    # AI 响应提取 HTML + 截断修复             104
│
├── workspace/
│   └── WorkspaceManager.kt                 # 多文件工作区（CRUD/快照/搜索/校验）      754
│
├── skill/
│   ├── BuiltInSkills.kt                    # 内置 Skill + 7 个系统模板               372
│   ├── SkillStorage.kt                     # 自定义 Skill JSON 持久化                 85
│   ├── ToolGroup.kt                        # 枚举定义 + 工具可见性规则               152
│   ├── PromptAssembler.kt                  # 提示词三维矩阵组装（含注入）              134
│   ├── IntentClassifier.kt                 # AI + 本地关键词双重意图分类               82
│   ├── BridgeDocs.kt                       # NativeBridge API 分组文档                51
│   └── CdnDocs.kt                          # CDN 库分组文档（bootcdn.net）            43
│
├── share/
│   └── ShareService.kt                     # 局域网 P2P（UDP 发现 + TCP 传输 + PIN） 437
│
├── util/
│   └── SecurityUtils.kt                    # SSRF 防护（DNS rebinding / 私有 IP）     62
│
└── ui/
    ├── navigation/NavGraph.kt              # 状态路由 + 会话历史侧滑面板             342
    ├── theme/
    │   ├── Color.kt                        # Material 3 颜色                          8
    │   ├── Theme.kt                        # Dynamic Color + 深色模式                41
    │   └── Type.kt                         # 字体排版                                16
    ├── chat/
    │   ├── ChatViewModel.kt                # 状态管理 + 会话 CRUD + Agent 调度       412
    │   └── ChatScreen.kt                   # 对话 UI + 自研 Markdown 渲染           1591
    ├── miniapp/
    │   ├── MiniAppListScreen.kt            # 应用列表（运行/导出/删除）               239
    │   └── MiniAppScreen.kt                # WebView 运行器 + nativeFetch 注入       368
    ├── skill/
    │   └── SkillListScreen.kt              # Skill 管理 + 全屏编辑器                 731
    └── settings/
        ├── SettingsScreen.kt               # 设置中心                                127
        ├── ModelManageScreen.kt            # 模型管理（预设/已添加/自定义）            619
        ├── MemoryScreen.kt                 # 记忆浏览器（搜索/新增/编辑/删除）         291
        ├── ModuleScreen.kt                 # 模块管理（端点详情/导入导出）              387
        └── ShareScreen.kt                  # 局域网分享 UI（发现/配对/选择/传输）      585
```

---

## 核心架构

### 数据流总览

```
用户输入 → ChatViewModel.sendMessage()
    │
    ├── buildApiMessages()            ← 注入系统提示词 + 上下文暗示 + 最近 20 条消息
    │                                   （图片附件 base64 编码为 _images 字段）
    │
    ├── IntentClassifier.classify()   ← AI 分类（10s 超时）→ 本地关键词降级
    │                                   → 5 种意图: CONVERSATION / CREATE_APP / MODIFY_APP
    │                                                MODULE_MGMT / MEMORY_OPS
    │
    ├── SkillStorage.loadAll()        ← 筛选绑定了当前意图的注入 Skill（4KB 上限）
    │
    ├── AgentOrchestrator.run()       ← 三阶段编排循环
    │   │
    │   ├── Phase 1: PLANNING (16384 tokens, 无 thinking)
    │   │   ├── PromptAssembler.build()  ← Skill + 注入提示词 + Agent 模式 + 工具列表
    │   │   │                              + 能力目录 + 工作流模板 + 规则 + 记忆
    │   │   ├── AI 返回 <capability_selection> 块
    │   │   └── 工具调用循环（最多 15 轮）
    │   │
    │   ├── Phase 2: GENERATION (65536 tokens, 支持 thinking)
    │   │   ├── 上下文压缩（工具结果→摘要、文件内容→成功提示）
    │   │   ├── 注入 NativeBridge 文档 + CDN 文档（按能力选择）
    │   │   ├── 继续工具调用循环
    │   │   └── 自检: validate_html + get_runtime_errors（最多 5 轮修复）
    │   │
    │   └── Phase 3: REFINEMENT
    │       └── 检测 HTML 未闭合 → 续写拼接
    │
    ├── MiniApp 提取 或 WorkspaceManager 多文件构建
    │
    ├── saveCurrentConversation()     ← 自动持久化到磁盘
    │
    └── MemoryExtractor.extract()     ← 异步记忆提取（非阻塞，最多 3 条）
```

### Agent 编排引擎

`AgentOrchestrator` 实现完整的三阶段推理循环：

#### Phase 1 — 规划（PLANNING）
- **Token 预算**：16,384（使用文件工具后自动升至 65,536）
- **Deep Thinking**：始终关闭（规划阶段不需要深度推理）
- **记忆预加载**：CHAT 模式或应用相关意图时，加载记忆快照
- **工作区快照**：存在活跃工作区时注入文件列表
- **图片处理**：首轮 API 调用注入 base64 图片，后续轮剥离
- **截断检测**：检测工具调用 XML 被截断，自动重试
- **能力选择**：解析 AI 返回的 `<capability_selection>` 块：
  ```
  tools: NETWORK, MODULE
  bridge: STORAGE, UI_FEEDBACK, SENSOR
  cdn: CHART, FRAMEWORK
  ```

#### Phase 2 — 生成（GENERATION）
- **Token 预算**：65,536
- **Deep Thinking**：按用户配置启用，流式传输思考内容
- **上下文压缩**：`compressContext()` 将工具结果摘要化，减少 token 消耗
- **NativeBridge 文档**：按能力选择的 BridgeGroup 动态注入
- **CDN 文档**：按能力选择的 CdnGroup 动态注入
- **自检修复**：生成后执行 `validate_html` + `get_runtime_errors`，最多 5 轮自动修复
- **工作区模式**：检测到文件工具时，自动从文件构建 MiniApp，提取 `<title>` 作为应用名

#### Phase 3 — 精炼（REFINEMENT）
- 检测 HTML 代码块未闭合（被截断）
- 自动续写并拼接完整内容

#### 工具调用机制
- 格式：`<tool_call>` XML 块
- 最大迭代：**15 轮**
- 别名支持：18 个常用别名自动映射（如 `read_file` → `read_workspace_file`）
- 自动生成 `APP_INFO.md` 清单（版本、文件数、大小、文件树）

### 意图分类系统

`IntentClassifier` 采用 **AI 优先 + 本地降级** 的双重策略：

#### AI 分类
- 轻量级 AI 调用，10 秒超时
- 5 种标签：`CONVERSATION`、`CREATE_APP`、`MODIFY_APP`、`MODULE_MGMT`、`MEMORY_OPS`
- 明确指示 AI："仅当用户明确要求'创建/做/写'某个应用时才选 CREATE_APP"

#### 本地关键词降级

本地分类按优先级执行：

| 优先级 | 匹配条件 | 结果 |
|--------|---------|------|
| 1 | 包含记忆关键词（记忆/记住/忘记/偏好/memory/记录）| MEMORY_OPS |
| 2 | 包含模块关键词（模块/module/端点/endpoint/api）| MODULE_MGMT |
| 3 | 有活跃工作区 + 修改关键词（修改/修复/优化/加一个/fix/update...）| MODIFY_APP |
| 4 | 包含创建关键词（做/写/创建/生成/做一个/页面/app/html/create/build...）| CREATE_APP |
| 5 | 短语关键词「游戏」+ 创建动词 | CREATE_APP |
| 6 | 有活跃工作区（默认） | MODIFY_APP |
| 7 | 兜底 | CONVERSATION |

创建关键词共 30 个，修改关键词共 16 个，特殊处理「游戏」等需搭配动词的短语。

### 工具注入矩阵

`PromptAssembler` 根据 **意图 × 阶段** 组合决定可见工具组。`resolveToolGroups()` 规则：

| 意图 × 阶段 | 可见工具组 |
|-------------|-----------|
| CONVERSATION / MEMORY_OPS | CORE + MEMORY + DEVICE + NETWORK |
| CREATE_APP → PLANNING | CORE + MEMORY（最小集，专注规划）|
| CREATE_APP → GENERATION | CORE + APP_CREATE + APP_READ + APP_EDIT + CODING + NETWORK |
| CREATE_APP → REFINEMENT | CORE + APP_READ + APP_EDIT + CODING |
| MODIFY_APP → PLANNING | CORE + MEMORY + APP_CREATE + APP_READ + APP_EDIT + APP_NAVIGATE + CODING + NETWORK |
| MODIFY_APP → GENERATION | CORE + APP_CREATE + APP_READ + APP_EDIT + APP_NAVIGATE + CODING + NETWORK |
| MODULE_MGMT | CORE + MEMORY + MODULE + NETWORK |

Skill 的 `extraToolGroups` 配置可追加额外工具组（CREATE_APP PLANNING 除外）。

#### 提示词组装顺序

`PromptAssembler.build()` 按以下顺序拼接系统提示词：

1. Skill 的 `systemPrompt`
2. 意图注入辅助 Skill 提示词（`injectedPrompts`，4KB 上限）
3. Agent 模式说明（`<tool_call>` XML 格式）
4. 可用工具列表（按工具组过滤）
5. 能力目录（仅 CREATE_APP + PLANNING）
6. 工作流模板（`resolveWorkflow(intent, phase)`）
7. 通用规则 + 阶段特定规则
8. NativeBridge 文档（仅 GENERATION，按 BridgeGroup）
9. CDN 文档（仅 GENERATION，按 CdnGroup）
10. 工作区快照
11. 记忆系统段

### Skill 系统

#### 数据模型

```kotlin
data class Skill(
    id: String,                                    // "custom_UUID" 或 "builtin_xxx"
    name: String,                                  // 显示名称
    icon: String,                                  // Emoji 图标
    description: String,                           // 简介
    systemPrompt: String,                          // 核心系统提示词
    mode: SkillMode,                               // CODING 或 CHAT
    isBuiltIn: Boolean,                            // 是否内置
    createdAt: Long,                               // 创建时间
    bridgeGroups: Set<BridgeGroup>,                // NativeBridge API 组
    cdnGroups: Set<CdnGroup>,                      // CDN 库组
    extraToolGroups: Set<ToolGroup>,               // 额外工具组
    intentInjections: Set<UserIntent>              // 意图注入绑定
)

enum class SkillMode {
    CODING,  // 只注入系统/工具文档，不注入个人信息
    CHAT     // 注入个人信息 + 偏好 + 记忆快照
}
```

#### 内置 Skill

| 类型 | ID | 名称 | 图标 | 说明 |
|------|-----|------|------|------|
| 角色 | `builtin_default` | 默认助手 | 🤖 | 通用对话 + 应用生成，CODING 模式 |

#### 系统模板（7 个）

| ID | 名称 | 图标 | 用途 |
|-----|------|------|------|
| `sys_agent_mode` | Agent 模式说明 | ⚙️ | `<tool_call>` XML 格式与调用规范 |
| `sys_rules` | 系统规则 | 📋 | 各阶段通用规则（必填参数、安全约束）|
| `sys_capability_catalog` | 能力目录 | 📑 | 规划阶段可选能力组清单（工具/Bridge/CDN）|
| `sys_wf_create` | 创建应用工作流 | 🆕 | 多文件结构、150 行分段、validate_html 自检 |
| `sys_wf_modify` | 修改应用工作流 | ✏️ | list→read→edit 铁律、replace_in_file 失败恢复 |
| `sys_wf_module` | 模块管理工作流 | 🔌 | HTTP/TCP/UDP 协议、占位符 `{{param}}`、encoding |
| `sys_memory` | 记忆系统 | 🧠 | 记忆工具使用规则与注入策略 |

#### 自定义 Skill 功能

- 创建自定义 AI 角色和专用工作流
- 配置知识模式（CODING / CHAT）
- 选择 NativeBridge API 组和 CDN 库组
- 绑定 0~5 种意图实现辅助注入
- 编辑/恢复内置 Skill 默认值
- 全屏编辑器 UI，FlowRow 意图 Chip + 动画模式选择器

### 记忆系统

#### 自动提取
- 每轮对话后由 `MemoryExtractor` 异步分析
- AI 判断是否包含值得记住的偏好/事实/技术栈
- 单次最多提取 **3 条**记忆
- 提取格式：`content|tag1,tag2`

#### 去重与存储
- 词级重叠 >70% 的新记忆自动跳过
- 上限 **200 条**，超出时删除最旧条目
- 存储路径：`filesDir/agent_memory/{id}.json`
- 字段：`id`（12 字符 UUID）、`content`、`tags`、`createdAt`、`updatedAt`
- 所有操作 `@Synchronized` 线程安全

#### 搜索评分
- 标签精确匹配：**+3 分**
- 内容关键词命中：**+1 分**
- 按总分降序返回

#### 注入策略
- **CHAT 模式**：自动注入记忆快照（前 N 条）
- **CODING 模式**：不自动注入，AI 可通过 `memory_search` 工具主动搜索
- Agent 可通过 5 个工具完整管理记忆：`memory_save`、`memory_search`、`memory_list`、`memory_update`、`memory_delete`

### 动态 API 模块

AI 可通过 Agent 工具创建和管理可复用的服务包装器。

#### 模块数据模型

```
Module
├── id, name, description
├── baseUrl / host
├── protocol: HTTP | TCP | UDP
├── defaultHeaders: Map<String, String>
├── allowPrivateNetwork: Boolean        ← 允许局域网 IP
├── instructions: String                ← AI 可读的使用说明
└── endpoints: List<Endpoint>
    ├── name, description
    ├── path (HTTP) / port (TCP/UDP)
    ├── method: GET/POST/PUT/DELETE (HTTP)
    ├── headers: Map<String, String>
    ├── bodyTemplate: String            ← 支持 {{param}} 占位符
    └── encoding: "utf8" | "hex"        ← TCP/UDP 编码
```

#### 协议支持

| 协议 | 超时 | 特性 |
|------|------|------|
| HTTP | 连接 30s / 读取 300s | 路径参数模板、请求头、请求体模板 |
| TCP | 连接 10s / 读取 15s | Socket 连接→发送→接收 |
| UDP | 接收 5s | DatagramSocket 发送→接收 |

#### 调用方式
- **Agent 工具**：`call_module(module_name, endpoint_name, params)`
- **迷你应用**：`window.callModule(moduleName, endpointName, params)` → Promise

### 会话离线存储

#### 存储策略
- **自动保存**：每次 `sendMessage()` 和 `regenerateLastResponse()` 完成后自动执行
- **启动恢复**：App 启动时加载会话列表并恢复最近一个会话
- **异步写入**：`viewModelScope.launch(Dispatchers.IO)` fire-and-forget

#### 目录结构
```
filesDir/conversations/
└── {conversation-id}/
    ├── meta.json           → { id, title, createdAt, updatedAt }
    └── messages.json       → [ { id, role, content, timestamp, miniApp?, agentSteps?, ... } ]
```

#### 智能序列化
- **跳过** base64 图片附件（避免磁盘膨胀）
- **MiniApp 仅存引用**：id + name + description（不存 htmlContent）
- **AgentStep 精简**：type + description + detail
- **文本附件保留**：name + mimeType + textContent

#### 安全
- 所有会话 ID 统一经 `safeId()` 净化（`[^a-zA-Z0-9_-]` 替换），防止路径遍历

### 局域网 P2P 分享

#### 协议

| 层 | 协议 | 端口 | 说明 |
|----|------|------|------|
| 发现 | UDP 广播 | **19876** | 消息格式 `LINKPI_SHARE:<IP>`，广播间隔 1.5s |
| 传输 | TCP | **19877** | JSON 序列化，消息大小上限 5MB |

#### PIN 配对流程
```
发送端                              接收端
  │                                   │
  ├─── UDP 广播 (LINKPI_SHARE:IP) ──→│ 发现设备
  │                                   │
  ├─── TCP CONNECT_REQUEST (pin) ───→│ 显示 PIN 输入框
  │                                   │
  │←── CONNECT_ACCEPT ───────────────┤ 用户输入正确 PIN
  │    或 CONNECT_REJECT (reason)     │
  │                                   │
  ├─── SHARE_ITEM (category+data) ──→│ 接收并保存
  │    ... 多个 ...                    │
  │                                   │
  ├─── DISCONNECT ──────────────────→│ 断开
```

#### 可分享内容
- **Skill**：完整的 Skill 数据（含 systemPrompt）
- **Module**：完整模块配置 + 端点定义
- **App**：迷你应用数据

### 工作区文件系统

`WorkspaceManager` 提供完整的多文件项目管理：

| 能力 | 方法 | 说明 |
|------|------|------|
| 创建文件 | `createFile` | 自动创建父目录 |
| 写入文件 | `writeFile` | 覆盖写入，修改前自动快照 |
| 追加文件 | `appendFile` | 末尾追加内容 |
| 读取文件 | `readFile` / `readFileLines` | 截断上限 16,000 字符 |
| 替换内容 | `replaceInFile` | 3 层容错：精确→空白归一化→滑动窗口 |
| 行替换 | `replaceLines` | 按行号范围替换 |
| 插入行 | `insertLines` | 指定行号插入 |
| 文件列表 | `listFiles` | 递归列出（隐藏 `.snapshots`）|
| 删除文件 | `deleteFile` / `deleteDirectory` | — |
| 重命名/复制 | `renameFile` / `copyFile` | — |
| 搜索 | `grepFile` / `grepWorkspace` | 正则搜索，结果截断 12,000 字符 |
| 快照 | `snapshotFile` / `undoFile` / `listSnapshots` | 修改前自动创建 |
| 对比 | `diffFile` | 文件差异对比 |
| HTML 校验 | `validateHtml` | 标签配对检查 |
| 清单 | `generateManifest` | 生成 APP_INFO.md（版本/文件树/大小）|
| 安全 | `resolveSecure` | 路径遍历检测 |

---

## 原生桥接 API

迷你应用通过 `window.NativeBridge` 访问设备能力（15 个接口）：

| 方法 | 组 | 说明 |
|------|-----|------|
| `showToast(message)` | UI_FEEDBACK | 显示 Toast |
| `vibrate(milliseconds)` | UI_FEEDBACK | 振动反馈（最长 5000ms）|
| `writeClipboard(text)` | UI_FEEDBACK | 写入剪贴板 |
| `sendToApp(data)` | UI_FEEDBACK | 向宿主 Activity 发送数据 |
| `getDeviceInfo()` | SENSOR | 返回 JSON：model/brand/manufacturer/sdkVersion/release |
| `getBatteryLevel()` | SENSOR | 电量百分比 |
| `getLocation()` | SENSOR | GPS 位置 JSON |
| `saveData(key, value)` | STORAGE | SharedPreferences 存储（按 appId 隔离）|
| `loadData(key)` | STORAGE | 读取存储 |
| `removeData(key)` | STORAGE | 删除键 |
| `clearData()` | STORAGE | 清除当前应用所有数据 |
| `listKeys()` | STORAGE | 返回逗号分隔的键列表 |
| `getAppId()` | STORAGE | 返回当前应用 ID |
| `httpRequest(callbackId, url, method, headers, body)` | NETWORK | HTTPS 请求（SSRF 保护，5MB 限制）|
| `listModules()` | — | 返回所有模块 JSON 数组 |
| `callModule(callbackId, moduleName, endpointName, paramsJson)` | — | 异步调用模块端点 |
| `reportError(error)` | — | 上报 JS 运行时错误 |

#### nativeFetch Polyfill

自动注入到 WebView 的全局 API：

```javascript
// HTTP 请求（绕过 CORS）
const resp = await nativeFetch(url, { method, headers, body });
const data = resp.json();

// 调用动态模块
const result = await callModule(moduleName, endpointName, params);

// 列出可用模块
const modules = listModules();
```

回调机制：随机 ID → `window.__nfCbs` 映射 → Base64 编码 JSON 通过 `window.__nfCb(id, base64)` 回传。

---

## Agent 工具列表

共 **49 个工具** + **18 个别名**：

### 核心工具（CORE — 3 个）

| 工具 | 参数 | 说明 |
|------|------|------|
| `get_current_time` | — | 获取当前时间 |
| `calculate` | `expression` | 安全数学表达式计算（递归下降解析器，支持 +-*/% 和括号）|
| `show_toast` | `message` | 显示 Toast 通知 |

### 设备工具（DEVICE — 5 个）

| 工具 | 参数 | 说明 |
|------|------|------|
| `get_device_info` | — | 设备信息 JSON |
| `get_battery_level` | — | 电量百分比 |
| `get_location` | — | GPS 位置 |
| `vibrate` | `milliseconds` | 振动 |
| `write_clipboard` | `text` | 写入剪贴板 |

### 网络工具（NETWORK — 4 个）

| 工具 | 参数 | 说明 |
|------|------|------|
| `fetch_url` | `url`, `method`, `headers`, `body` | HTTPS 请求（SSRF 保护，响应截断 4000 字符）|
| `web_search` | `query` | DuckDuckGo 搜索，正则提取结果 |
| `save_data` | `key`, `value` | 全局键值存储 |
| `load_data` | `key` | 读取全局存储 |

### 文件创建（APP_CREATE — 4 个）

| 工具 | 参数 | 说明 |
|------|------|------|
| `create_file` | `path`, `content` | 创建文件（自动创建父目录）|
| `write_file` | `path`, `content` | 覆盖写入（修改前自动快照）|
| `append_file` | `path`, `content` | 末尾追加 |
| `create_directory` | `path` | 创建目录 |

### 文件读取（APP_READ — 4 个）

| 工具 | 参数 | 说明 |
|------|------|------|
| `read_workspace_file` | `path`, `start_line?`, `end_line?` | 读取文件（截断 16000 字符）|
| `list_workspace_files` | `path?` | 递归列出文件 |
| `file_info` | `path` | 文件元数据 |
| `list_snapshots` | `path` | 列出快照历史 |

### 文件编辑（APP_EDIT — 8 个）

| 工具 | 参数 | 说明 |
|------|------|------|
| `replace_in_file` | `path`, `old_text`, `new_text` | 精确替换（3 层容错）|
| `replace_lines` | `path`, `start_line`, `end_line`, `content` | 按行号替换 |
| `insert_lines` | `path`, `line`, `content` | 指定行插入 |
| `rename_file` | `old_path`, `new_path` | 重命名/移动 |
| `copy_file` | `source`, `destination` | 复制文件 |
| `delete_workspace_file` | `path` | 删除文件 |
| `delete_directory` | `path` | 删除目录 |
| `undo_file` | `path` | 恢复到上一个快照 |

### 应用导航（APP_NAVIGATE — 2 个）

| 工具 | 参数 | 说明 |
|------|------|------|
| `list_saved_apps` | — | 列出所有已保存应用 |
| `open_app_workspace` | `app_id` 或 `app_name` | 打开应用工作区（支持模糊名称匹配）|

### 代码工具（CODING — 5 个）

| 工具 | 参数 | 说明 |
|------|------|------|
| `grep_file` | `path`, `pattern` | 文件内正则搜索 |
| `grep_workspace` | `pattern` | 全工作区搜索（截断 12000 字符）|
| `get_runtime_errors` | — | 获取 JS 运行时错误 |
| `validate_html` | `path?` | HTML 标签配对校验 |
| `diff_file` | `path` | 与上一快照对比差异 |

### 记忆工具（MEMORY — 5 个）

| 工具 | 参数 | 说明 |
|------|------|------|
| `memory_save` | `content`, `tags` | 保存记忆（自动去重）|
| `memory_search` | `query` | 按关键词+标签搜索 |
| `memory_list` | — | 列出所有记忆 |
| `memory_update` | `id`, `content`, `tags` | 更新指定记忆 |
| `memory_delete` | `id` | 删除指定记忆 |

### 模块工具（MODULE — 7 个）

| 工具 | 参数 | 说明 |
|------|------|------|
| `create_module` | `name`, `base_url`, `protocol`, `description`, ... | 创建模块 |
| `add_module_endpoint` | `module_name`, `endpoint_name`, `path`, ... | 添加端点 |
| `remove_module_endpoint` | `module_name`, `endpoint_name` | 移除端点 |
| `call_module` | `module_name`, `endpoint_name`, `params` | 调用模块端点 |
| `list_modules` | — | 列出所有模块 |
| `update_module` | `module_name`, `description?`, `base_url?`, ... | 更新模块 |
| `delete_module` | `module_name` | 删除模块 |

### 工具别名（18 个）

```
read_file / readFile / read        → read_workspace_file
list_files / listFiles             → list_workspace_files
delete_file / deleteFile           → delete_workspace_file
edit_file                          → replace_in_file
search / grep                      → grep_workspace
search_memory / save_memory        → memory_search / memory_save
list_memory / delete_memory        → memory_list / memory_delete
update_memory                      → memory_update
list_apps                          → list_saved_apps
open_workspace                     → open_app_workspace
```

---

## CDN 库支持

迷你应用可使用以下预配置 CDN 库（`cdn.bootcdn.net` 国内镜像）：

| 组 | 库 | 版本 |
|----|-----|------|
| **FRAMEWORK** | Vue 2 | 2.7.14 |
| | Vue 3 | 3.3.4 |
| | React + ReactDOM | 18.2.0 |
| **CHART** | Chart.js | 4.4.0 |
| **THREE_D** | Three.js | r128 |
| **UTILS** | Axios | 1.6.0 |
| | Animate.css | 4.1.1 |

AI 在规划阶段选择所需 CDN 组，生成阶段自动注入对应文档。

---

## 安全措施

| 层面 | 措施 | 说明 |
|------|------|------|
| **密钥存储** | EncryptedSharedPreferences | AES-256-GCM 加密 + AES-256-SIV 密钥加密，失败降级 |
| **SSRF 防护** | SecurityUtils 统一检测 | 阻止 localhost/回环/链路本地/站点本地/CGNAT/ULA/文档保留/基准测试地址 |
| **DNS Rebinding** | 解析后二次校验 | DNS 解析 IP 后再检查是否为私有地址 |
| **HTTPS 强制** | fetch_url + httpRequest | 仅允许 HTTPS 协议 |
| **明文禁止** | AndroidManifest | `usesCleartextTraffic="false"` |
| **路径遍历** | WorkspaceManager | `resolveSecure()` 路径校验 |
| **路径遍历** | ConversationStorage | `safeId()` 统一 ID 净化 |
| **端口校验** | ModuleStorage | TCP/UDP 限制 1-65535 |
| **线程安全** | MemoryStorage / ModuleStorage | `@Synchronized` 关键方法 |
| **应用隔离** | NativeBridge 存储 | 每个应用独立 appId 隔离 SharedPreferences |
| **PIN 配对** | ShareService | 4 位随机 PIN，连接时验证 |
| **注入限制** | PromptAssembler | 意图注入累计 ≤ 4KB |
| **R8 混淆** | Release 构建 | 代码缩减 + 类/方法重命名 |
| **WebView 安全** | MiniAppScreen | `allowContentAccess=false`, `allowFileAccess=false` |
| **callbackId 校验** | NativeBridge | 正则 `^[a-zA-Z0-9_]+$` 白名单 |
| **响应限制** | httpRequest | 5MB 响应大小上限 |

---

## ProGuard / R8 配置

Release 与 Debug 均启用 `isMinifyEnabled = true`（Debug 下自动跳过混淆）。

保留规则摘要：

| 类别 | 规则 |
|------|------|
| 崩溃追踪 | 保留 SourceFile + LineNumberTable |
| 数据模型 | `data.model.*`、`Module`、`Endpoint`、`ReceivedItem`、`ConnectionState` |
| 枚举 valueOf | BridgeGroup、CdnGroup、ToolGroup、UserIntent、SkillMode |
| OkHttp | 全保留 + dontwarn |
| Kotlin 协程 | 全保留 + dontwarn |
| WebView 接口 | `@JavascriptInterface` 标注方法保留 |
| Google Tink | dontwarn（EncryptedSharedPreferences 依赖）|

---

## 关键配置常量

| 配置 | 默认值 | 位置 |
|------|--------|------|
| 默认 API 端点 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | AiConfig |
| 默认模型 | `qwen-max` | AiConfig |
| 默认温度 | 0.7 | AiConfig |
| 默认最大 Tokens | 65,536 | AiConfig |
| API 连接超时 | 30 秒 | AiService |
| API 读取超时 | 300 秒 | AiService |
| 最大工具迭代 | 15 轮 | AgentOrchestrator |
| 规划阶段 tokens | 16,384 | AgentOrchestrator |
| 生成阶段 tokens | 65,536 | AgentOrchestrator |
| 最大记忆条数 | 200 | MemoryStorage |
| 单次最大提取 | 3 条 | MemoryExtractor |
| 记忆去重阈值 | 70% 词级重叠 | MemoryExtractor |
| 上下文消息数 | 最近 20 条 | ChatViewModel |
| 文件读取截断 | 16,000 字符 | WorkspaceManager |
| 搜索结果截断 | 12,000 字符 | WorkspaceManager |
| fetch_url 响应截断 | 4,000 字符 | ToolExecutor |
| httpRequest 响应限制 | 5 MB | NativeBridge |
| 意图分类 AI 超时 | 10 秒 | IntentClassifier |
| 意图注入大小上限 | 4,096 字符 | AgentOrchestrator |
| P2P 发现端口 | 19876 (UDP) | ShareService |
| P2P 传输端口 | 19877 (TCP) | ShareService |
| P2P 广播间隔 | 1.5 秒 | ShareService |
| P2P 消息上限 | 5 MB | ShareService |
| TCP 连接超时 | 10 秒 | ModuleStorage |
| TCP 读取超时 | 15 秒 | ModuleStorage |
| UDP 接收超时 | 5 秒 | ModuleStorage |
| 振动时长上限 | 5,000 ms | NativeBridge |

---

## 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | AI API 调用、WebView 内容加载、web_search、nativeFetch、模块 HTTP/TCP/UDP |
| `VIBRATE` | 迷你应用振动反馈 |
| `ACCESS_FINE_LOCATION` | GPS 精确位置（迷你应用通过 NativeBridge 使用）|
| `ACCESS_COARSE_LOCATION` | 网络粗略定位 |
| `ACCESS_WIFI_STATE` | 局域网 P2P 分享（获取 WiFi 信息）|
| `ACCESS_NETWORK_STATE` | 网络状态检测 |

---

## 许可证

MIT License — © Wenhe X Rnzy 2026.3.2
