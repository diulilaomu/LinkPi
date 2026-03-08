# SKILL 系统升级设计文档

> 版本: v3 (Anti-Pollution + AI Intent)  
> 日期: 2026-03-08  
> 状态: ✅ 实施完成

---

## 一、现状问题

| 问题 | 影响 |
|------|------|
| 41 个工具定义**全部注入**，无论用户意图 | AI 在不需要时错误调用工具（如纯对话时尝试创建文件） |
| `NATIVE_BRIDGE_DOCS` 全量注入 14 个 API | "写个贪吃蛇" 时 AI 会在游戏中塞入 `getLocation`、`getBatteryLevel` |
| `CDN_DOCS` 全量注入 8 个库 | Todo App 引入 Three.js，贪吃蛇用 Vue 框架 |
| `APP_GEN_RULES`（含 Create + Modify workflow）在所有场景都注入 | 新建应用时 AI 先去 `list_saved_apps` 找旧应用 |
| `SkillMode` 仅 CHAT/CODING 两档 | 粒度太粗，无法区分"创建 vs 修改 vs 对话" |
| 系统提示在整个 Agent 循环中不变 | Planning 阶段看到 `create_file` → AI 跳过分析直接动手 |

### 污染的真正危害

不只是 token 浪费，更是 **AI 行为被污染**：

1. **幻觉工具调用**：AI 看到 `get_location` → 贪吃蛇里加了个"获取位置"功能
2. **幻觉 API 使用**：NativeBridge 有 `getBatteryLevel` → 计算器里莫名显示电量
3. **流程混淆**："Modify Existing App" 4 步流程 → 新建应用时 AI 先去 `list_saved_apps`
4. **库误用**：CDN 列表里有 Three.js → Todo App 用了 3D 渲染
5. **阶段错乱**：Planning 阶段能看到 `create_file` → AI 跳过思考直接开写

**核心哲学：AI 看不到就不会用错。看到了就一定会用——不管该不该用。**

---

## 二、架构设计：三维注入矩阵

```
                ┌─────────────┐
                │  用户消息    │
                └──────┬──────┘
                       ▼
              ┌─────────────────┐
              │  Intent 分类器   │  ← AI 预分类（~150 tokens，~200ms）
              └────────┬────────┘
                       ▼
    ┌──────────────────────────────────┐
    │       三维注入决策引擎            │
    │                                  │
    │  维度1: Skill  (技能类型)        │
    │  维度2: Intent (用户意图)        │
    │  维度3: Phase  (Agent 阶段)      │
    └──────────────────────────────────┘
                       ▼
    ┌──────────────────────────────────┐
    │  PromptAssembler 按需组装系统提示 │
    │  • 筛选工具子集                   │
    │  • 筛选 NativeBridge API 子集    │
    │  • 筛选 CDN 子集                 │
    │  • 互斥注入 Workflow 指令         │
    └──────────────────────────────────┘
```

---

## 三、维度 1：Intent 意图分类（分场景）

### 枚举

```kotlin
enum class UserIntent {
    CONVERSATION,    // 纯对话："你好"、"什么是量子计算"
    CREATE_APP,      // 新建应用："写一个贪吃蛇"、"做个计算器"
    MODIFY_APP,      // 修改应用："把背景改成蓝色"、"加个排行榜"
    MODULE_MGMT,     // 模块管理："创建一个天气 API 模块"
    MEMORY_OPS       // 记忆操作："你还记得我叫什么吗"
}
```

### 分类策略：纯 AI 分类

用一次超短 AI 调用完成意图识别，不用关键词匹配。

**为什么不用关键词**：

| 用户消息 | 关键词判定 | 正确意图 |
|---------|-----------|----------|
| "来个能看天气的东西" | ❌ CONVERSATION（没命中"写/做/创建"） | CREATE_APP |
| "之前那个计算器不太好用" | ❌ CONVERSATION | MODIFY_APP |
| "贪吃蛇" | ❌ CONVERSATION（只有三个字） | CREATE_APP |
| "snake game" | ❌ CONVERSATION（英文覆盖不全） | CREATE_APP |

关键词方案的误判率 ~30%，且需要持续维护中英日韩词表。AI 天然支持所有语言，准确率 ~95%+。

```kotlin
object IntentClassifier {
    suspend fun classify(
        message: String,
        hasActiveWorkspace: Boolean,
        skill: Skill,
        aiService: AiService
    ): UserIntent {
        val prompt = """Classify the user's intent into exactly one category.
Reply with ONLY the category name, nothing else.

Categories:
- CONVERSATION: casual chat, questions, not requesting an app
- CREATE_APP: wants to create/build/make a new app, game, tool, or page
- MODIFY_APP: wants to change/fix/update an existing app
- MODULE_MGMT: wants to create/manage API modules or endpoints
- MEMORY_OPS: asking about or managing memories/preferences

Context: has_active_workspace=$hasActiveWorkspace, current_skill=${skill.name}

User message: $message
Category:"""

        return try {
            val result = aiService.chat(prompt, maxTokens = 10)
            UserIntent.valueOf(result.trim().uppercase())
        } catch (_: Exception) {
            UserIntent.CONVERSATION  // 解析失败安全回退
        }
    }
}
```

**设计要点**：
- 总消耗 ~150 tokens（输入 ~60 + 输出 1-2），对比主循环 4000-65000 tokens 可忽略
- 延迟 ~200-400ms，用户无感知（主循环本身就要数秒）
- `maxTokens = 10` 严格限制输出长度
- 注入 `has_active_workspace` 和 `current_skill` 作为上下文辅助判断
- 零维护：不需要关键词表，天然支持所有语言
- **fallback 策略**：解析失败回退到 `CONVERSATION`（宁缺勿滥）

---

## 四、维度 2：工具分组与 Skill 映射

### 4.1 工具分组

| 分组 | 工具名 | 数量 |
|------|--------|------|
| **CORE** | `get_current_time`, `calculate`, `show_toast` | 3 |
| **MEMORY** | `memory_save`, `memory_search`, `memory_list`, `memory_delete`, `memory_update` | 5 |
| **APP_CREATE** | `create_file`, `write_file`, `append_file`, `create_directory` | 4 |
| **APP_READ** | `read_workspace_file`, `list_workspace_files`, `file_info` | 3 |
| **APP_EDIT** | `replace_in_file`, `replace_lines`, `insert_lines`, `rename_file`, `copy_file`, `delete_workspace_file`, `delete_directory` | 7 |
| **APP_NAVIGATE** | `list_saved_apps`, `open_app_workspace` | 2 |
| **CODING** | `grep_file`, `grep_workspace` | 2 |
| **DEVICE** | `get_device_info`, `get_battery_level`, `get_location`, `vibrate`, `write_clipboard` | 5 |
| **NETWORK** | `fetch_url`, `save_data`, `load_data` | 3 |
| **MODULE** | `create_module`, `add_module_endpoint`, `remove_module_endpoint`, `call_module`, `list_modules`, `update_module`, `delete_module` | 7 |

### 4.2 关于 DEVICE / NETWORK 工具组

**v2 中永不注入 DEVICE 组到 agent 工具列表中。**

理由：
- `get_device_info`, `get_battery_level`, `get_location` 是生成代码中通过 NativeBridge 调用的 API，不是 agent 工具
- `vibrate`, `write_clipboard` 几乎没有合理场景需要 agent 自己调（应该是生成的代码在运行时调用）
- 这 5 个工具的存在是 agent 行为污染的最大来源之一

`NETWORK` 组仅在 `MODULE_MGMT` 场景注入。

### 4.3 Intent × Phase → 工具矩阵

#### CREATE_APP

| 工具组 | PLANNING | GENERATION | REFINEMENT |
|--------|:--------:|:----------:|:----------:|
| CORE | ✅ | ✅ | ✅ |
| MEMORY | ✅ | ❌ | ❌ |
| APP_CREATE | ❌ | ✅ | ❌ |
| APP_READ | ❌ | ✅ | ✅ |
| APP_EDIT | ❌ | ✅ | ✅ |
| CODING | ❌ | ✅ | ✅ |
| APP_NAVIGATE | ❌ | ❌ | ❌ |
| DEVICE | ❌ | ❌ | ❌ |
| NETWORK | ❌ | ❌ | ❌ |
| MODULE | ❌ | ❌ | ❌ |

#### MODIFY_APP

| 工具组 | PLANNING | GENERATION | REFINEMENT |
|--------|:--------:|:----------:|:----------:|
| CORE | ✅ | ✅ | ✅ |
| MEMORY | ✅ | ❌ | ❌ |
| APP_CREATE | ❌ | ✅ | ❌ |
| APP_READ | ✅ | ✅ | ✅ |
| APP_EDIT | ❌ | ✅ | ✅ |
| CODING | ✅ | ✅ | ✅ |
| APP_NAVIGATE | ✅ | ❌ | ❌ |

#### CONVERSATION / MEMORY_OPS

仅 CORE + MEMORY（共 8 个工具）。

#### MODULE_MGMT

仅 CORE + MEMORY + MODULE + NETWORK。

---

## 五、NativeBridge 文档分组

### 5.1 分组定义

| 分组 | APIs |
|------|------|
| **STORAGE** | `saveData`, `loadData`, `removeData`, `clearData`, `listKeys`, `getAppId` |
| **UI_FEEDBACK** | `showToast`, `vibrate`, `writeClipboard`, `sendToApp` |
| **SENSOR** | `getDeviceInfo`, `getBatteryLevel`, `getLocation` |
| **NETWORK** | `nativeFetch` |

### 5.2 Skill → BridgeGroup 映射

| Skill | STORAGE | UI_FEEDBACK | SENSOR | NETWORK |
|-------|:-------:|:-----------:|:------:|:-------:|
| GAME_DEV 🎮 | ✅ | ✅ | ❌ | ❌ |
| UI_DESIGNER 🎨 | ✅ | ✅ | ❌ | ❌ |
| DATA_VIZ 📊 | ✅ | ✅ | ✅ | ✅ |
| PRODUCTIVITY ⚡ | ✅ | ✅ | ❌ | ❌ |
| THREE_D 🌐 | ❌ | ✅ | ❌ | ❌ |
| TEACHER 📚 | ✅ | ✅ | ❌ | ❌ |
| CHAT_ONLY 💬 | ❌ | ❌ | ❌ | ❌ |
| DEFAULT 🤖 | ✅ | ✅ | ✅ | ✅ |

### 5.3 注入条件

NativeBridge 文档**仅在 Intent == CREATE_APP 或 MODIFY_APP 时注入**。纯对话不注入。

---

## 六、CDN 文档分组

### 6.1 分组定义

| 分组 | Libraries |
|------|-----------|
| **FRAMEWORK** | Vue 2/3, React, ReactDOM |
| **CHART** | Chart.js |
| **3D** | Three.js |
| **UTILS** | Axios, Animate.css |

### 6.2 Skill → CdnGroup 映射

| Skill | 注入的 CDN 组 |
|-------|--------------|
| GAME_DEV 🎮 | — (纯 Canvas 原生，不引入框架) |
| UI_DESIGNER 🎨 | FRAMEWORK + UTILS |
| DATA_VIZ 📊 | FRAMEWORK + CHART + UTILS |
| PRODUCTIVITY ⚡ | FRAMEWORK |
| THREE_D 🌐 | 3D |
| TEACHER 📚 | FRAMEWORK |
| CHAT_ONLY 💬 | — |
| DEFAULT 🤖 | 全部 |

---

## 七、Workflow 指令互斥注入

| Workflow 段落 | 注入条件 |
|--------------|---------|
| "Workflow: Create App" | Intent == CREATE_APP，且仅 GENERATION 阶段 |
| "Workflow: Modify Existing App" | Intent == MODIFY_APP，且仅 PLANNING 阶段（4 步流程指导） |
| "Dynamic Modules" 文档 | Intent == MODULE_MGMT |
| Memory 使用说明 | 始终注入（snapshot 仅 CHAT mode 加载） |
| CDN / NativeBridge 文档 | Intent == CREATE_APP 或 MODIFY_APP |

**互斥，不共存。**

---

## 八、Skill 数据结构重构

### 当前

```kotlin
data class Skill(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val systemPrompt: String,    // 内嵌了 APP_GEN_RULES（完整 Bridge + CDN）
    val mode: SkillMode = SkillMode.CODING,
    val isBuiltIn: Boolean = false
)
```

### 升级后

```kotlin
data class Skill(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val systemPrompt: String,    // 仅角色定位 + 设计原则，不含文档
    val mode: SkillMode = SkillMode.CODING,
    val isBuiltIn: Boolean = false,
    // ↓ 新增声明式字段
    val bridgeGroups: Set<BridgeGroup> = setOf(BridgeGroup.STORAGE, BridgeGroup.UI_FEEDBACK),
    val cdnGroups: Set<CdnGroup> = emptySet(),
    val extraToolGroups: Set<ToolGroup> = emptySet()
)
```

`systemPrompt` 只保留角色定位和设计原则。所有工具/文档/workflow 由 `PromptAssembler` 动态组装。

---

## 九、代码架构

### 新增/修改文件

```
skill/
├── BuiltInSkills.kt        ← 重构：Skill 去掉内嵌 APP_GEN_RULES，增加声明式字段
├── IntentClassifier.kt      ← 新增：AI 意图分类器（纯 AI，~150 tokens/次）
├── ToolGroup.kt             ← 新增：工具分组枚举 + 映射表
├── PromptAssembler.kt       ← 新增：三维组装系统提示
├── BridgeDocs.kt            ← 新增：分组的 NativeBridge 文档常量
├── CdnDocs.kt               ← 新增：分组的 CDN 文档常量

data/model/
├── Skill.kt                 ← 修改：增加 bridgeGroups, cdnGroups, extraToolGroups 字段

network/
├── AiService.kt             ← 修改：新增 chat(prompt, maxTokens) 重载（短输出分类用）

agent/
├── AgentOrchestrator.kt     ← 修改：三阶段各自调用 PromptAssembler 重建系统提示
├── ToolExecutor.kt          ← 修改：ToolDef 增加 category: ToolGroup 字段
```

### PromptAssembler 核心逻辑

PromptAssembler 消费 IntentClassifier 的输出，不关心分类是怎么来的：

```kotlin
object PromptAssembler {
    fun build(
        skill: Skill,
        intent: UserIntent,
        phase: AgentPhase,
        allTools: List<ToolDef>,
        memorySnapshot: String?
    ): String {
        // 1. 确定工具子集
        val groups = resolveToolGroups(intent, phase, skill)
        val tools = allTools.filter { it.category in groups }

        // 2. 确定文档子集（仅 CREATE/MODIFY 注入）
        val bridgeDocs = if (intent.needsApp() && phase != AgentPhase.PLANNING)
            BridgeDocs.build(skill.bridgeGroups) else ""
        val cdnDocs = if (intent.needsApp() && phase != AgentPhase.PLANNING)
            CdnDocs.build(skill.cdnGroups) else ""

        // 3. 确定 workflow（互斥）
        val workflow = resolveWorkflow(intent, phase)

        // 4. 组装
        return buildString {
            appendLine(skill.systemPrompt)
            appendLine(buildToolSection(tools))
            if (bridgeDocs.isNotEmpty()) appendLine(bridgeDocs)
            if (cdnDocs.isNotEmpty()) appendLine(cdnDocs)
            if (workflow.isNotEmpty()) appendLine(workflow)
            appendLine(buildMemorySection(memorySnapshot, skill.mode))
        }
    }
}
```

---

## 十、完整数据流示例

### 场景："写一个贪吃蛇"（GAME_DEV Skill）

```
用户消息: "写一个贪吃蛇"
  │
  ▼
IntentClassifier.classify() → AI 返回 "CREATE_APP" (~150 tokens, ~200ms)
  │
  ▼
══ PLANNING 阶段 ═════════════════════════════════════
  工具: CORE(3) + MEMORY(5) = 8 个
  文档: 无 Bridge, 无 CDN
  Workflow: 无
  → AI 分析需求、查记忆偏好
  │
  ▼
══ GENERATION 阶段 ════════════════════════════════════
  工具: CORE(3) + APP_CREATE(4) + APP_READ(3) + APP_EDIT(7) + CODING(2) = 19 个
  Bridge: STORAGE(6) + UI_FEEDBACK(4) = 10 个 API（无 getLocation/getBatteryLevel/getDeviceInfo）
  CDN: 无（GAME_DEV 不注入 CDN，用纯 Canvas）
  Workflow: "Create App" 段落
  → AI 生成贪吃蛇代码
  │
  ▼
══ REFINEMENT 阶段（如需）════════════════════════════
  工具: CORE(3) + APP_READ(3) + APP_EDIT(7) + CODING(2) = 15 个
  → 修复截断/补全代码
```

**对比当前**：41 个工具 + 14 个 Bridge API + 8 个 CDN + Create/Modify 两段 Workflow → 全量轰炸

### 场景："你好"（任何 Skill）

```
IntentClassifier.classify() → AI 返回 "CONVERSATION"
  工具: CORE(3) + MEMORY(5) = 8 个
  文档: 无
  Workflow: 无
```

**对比当前**：同样 41 个工具全量注入

### 场景："来个能看天气的东西"（DEFAULT Skill）

```
IntentClassifier.classify() → AI 返回 "CREATE_APP"
  （关键词方案会误判为 CONVERSATION，AI 正确识别）
```

### 场景："贪吃蛇"（GAME_DEV Skill）

```
IntentClassifier.classify() → AI 返回 "CREATE_APP"
  （仅两个字，关键词方案无法匹配，AI 结合 current_skill=GAME_DEV 正确识别）
```

---

## 十一、实施计划

**单期完成**（Phase 维度对降噪至关重要，不分期）：

| 步骤 | 内容 | 涉及文件 |
|------|------|---------|
| 1 | 创建 `ToolGroup` 枚举，41 个工具打标签 | `ToolGroup.kt`, `ToolExecutor.kt` |
| 2 | 创建 `BridgeDocs` / `CdnDocs` 分组常量 | `BridgeDocs.kt`, `CdnDocs.kt` |
| 3 | `AiService` 新增 `chat(prompt, maxTokens)` 重载 | `AiService.kt` |
| 4 | 创建 `IntentClassifier`（纯 AI 分类） | `IntentClassifier.kt` |
| 5 | `Skill` 数据类增加声明式字段 | `Skill.kt` |
| 6 | 创建 `PromptAssembler` 三维组装器 | `PromptAssembler.kt` |
| 7 | 重构 `BuiltInSkills` 去掉内嵌文档 | `BuiltInSkills.kt` |
| 8 | 改造 `AgentOrchestrator` 三阶段重建提示 | `AgentOrchestrator.kt` |
| 9 | 更新 `ChatViewModel` 传递完整 Skill 对象 | `ChatViewModel.kt` |
| 10 | 编译验证 | BUILD SUCCESSFUL |

### 实施记录

**已完成所有步骤** (编译通过，零错误)：

- `ToolGroup.kt` — 新建，含 ToolGroup/BridgeGroup/CdnGroup/UserIntent/AgentPhase 枚举 + TOOL_GROUP_MAP(41工具) + resolveToolGroups()
- `BridgeDocs.kt` — 新建，4 分组常量 + build() 按需组装
- `CdnDocs.kt` — 新建，4 分组常量 + build() 按需组装
- `AiService.kt` — 新增 chat(prompt, maxTokens) 单消息重载，temperature=0
- `IntentClassifier.kt` — 新建，纯 AI 分类 ~150 tokens/次，fallback→CONVERSATION
- `Skill.kt` — 新增 bridgeGroups/cdnGroups/extraToolGroups 声明式字段
- `PromptAssembler.kt` — 新建，三维组装器，工具过滤 + Bridge/CDN 注入 + 工作流互斥 + 记忆段
- `BuiltInSkills.kt` — 完全重写，8 个技能只保留角色+设计原则，声明三维参数
- `AgentOrchestrator.kt` — run() 签名改 Skill，Phase 1/2 用 PromptAssembler.build()，删除旧 buildAgentSystemPrompt() (~120行)
- `ChatViewModel.kt` — 两处 orchestrator.run() 改传完整 Skill 对象
