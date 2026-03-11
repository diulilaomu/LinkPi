# 会话管理（Session Management）实施计划书

> 版本: v1.0  
> 日期: 2026-03-11  
> 范围: 所有发起 AI 对话的会话（不含 SSH 手动终端模式）

---

## 一、目标

为应用中所有 AI 对话类型建立统一的会话管理基础设施，实现：

1. **会话注册** — 每个 AI 会话在创建时注册到 SessionRegistry
2. **来源标签** — 标记会话来源（主聊天、SSH 辅助、Workbench）
3. **上下文可视** — 记录每个会话携带的 Skill、注入的意图、使用的模型
4. **管理操作** — 支持查看、阻断（暂停/终止）、删除
5. **会话隔离** — 保证各会话的 AI 上下文不互相污染

**不纳入管理**: SSH 手动终端模式（纯 PTY 字节流，无 AI 参与）

---

## 二、管理范围

| 会话类型 | 纳入管理 | 说明 |
|---------|---------|------|
| Chat 主对话 | ✅ | 每个 Conversation 对应一个 Session |
| SSH 辅助模式 | ✅ | AI 对话部分纳入，SSH 连接作为附属资源 |
| Workbench 任务 | ✅ | 每个任务执行对应一个 Session |
| SSH 手动终端 | ❌ | 纯终端操作，不涉及 AI |

---

## 三、数据模型设计

### 3.1 核心数据类

```kotlin
// data/model/ManagedSession.kt

data class ManagedSession(
    val id: String = UUID.randomUUID().toString(),
    val type: SessionType,
    val label: String,                          // 用户可见名称（自动生成或用户自定义）
    val source: SessionSource,                  // 来源信息
    val skillId: String?,                       // 绑定的主 Skill ID
    val injectedSkillIds: List<String> = emptyList(),  // 意图注入的额外 Skill
    val enabledToolGroups: List<String> = emptyList(), // 启用的工具组
    val modelId: String,                        // 使用的模型 ID
    val status: SessionStatus = SessionStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,                  // 消息数量快照
    val metadata: Map<String, String> = emptyMap()  // 扩展字段（如 SSH host、任务标题等）
)

enum class SessionType {
    CHAT,           // 主聊天对话
    SSH_ASSIST,     // SSH 辅助模式
    WORKBENCH       // 工作台任务
}

enum class SessionSource {
    USER_CREATED,       // 用户主动创建
    FROM_SSH,           // 从 SSH 连接发起
    FROM_WORKBENCH,     // 从工作台发起
    FROM_SKILL,         // 从 Skill 快捷入口发起
    AUTO_CREATED        // 系统自动创建
}

enum class SessionStatus {
    ACTIVE,     // 活跃中
    PAUSED,     // 已暂停（用户手动阻断）
    ENDED       // 已结束
}
```

### 3.2 与现有数据的关联

| ManagedSession 字段 | 关联关系 |
|---------------------|---------|
| `id` | Chat 类型 → 等于 `Conversation.id`；SSH 类型 → 新建独立 ID；Workbench 类型 → 等于 `WorkbenchTask.id` |
| `skillId` | → `Skill.id`，可追溯到 SkillStorage |
| `modelId` | → `ModelConfig.id`，可追溯到 AiConfig |
| `metadata["sshHost"]` | SSH 类型专用，记录服务器地址 |
| `metadata["taskTitle"]` | Workbench 类型专用，记录任务标题 |

---

## 四、存储层设计

### 4.1 SessionStorage

```
文件: data/SessionStorage.kt
模式: 参照 SkillStorage 的单文件 JSON 模式

存储路径: {appFiles}/sessions/{session-id}.json
```

**API 设计**:

| 方法 | 说明 |
|------|------|
| `save(session: ManagedSession)` | 创建或更新 session JSON 文件 |
| `loadAll(): List<ManagedSession>` | 加载全部 session，按 updatedAt 降序 |
| `loadById(id: String): ManagedSession?` | 按 ID 加载单个 |
| `loadByType(type: SessionType): List<ManagedSession>` | 按类型过滤 |
| `delete(id: String)` | 删除 session 文件 |
| `updateStatus(id: String, status: SessionStatus)` | 更新状态 |
| `cleanup(maxAge: Long)` | 清理超过指定时间的已结束 session |

**安全**: 使用 `safeId()` 过滤路径（同 ConversationStorage）。

### 4.2 SessionRegistry（内存层）

```
文件: data/SessionRegistry.kt
模式: 全局单例，持有活跃 session 的内存索引
```

**职责**:
- 提供当前活跃 session 的快速查询
- 维护 `StateFlow<List<ManagedSession>>` 供 UI 响应式订阅
- 启动时从 SessionStorage 加载，状态变更时回写
- 惰性注册：简单的一轮对话不创建 session，满足条件后自动提升

**注册时机（惰性策略）**:

| 会话类型 | 注册触发条件 |
|---------|-------------|
| Chat | 发送第一条消息时注册（而非 newConversation 时） |
| SSH 辅助 | 进入辅助模式且发送第一条消息时 |
| Workbench | `WorkbenchEngine.execute()` 调用时立即注册 |

---

## 五、ViewModel 集成点

### 5.1 ChatViewModel 改动

```
文件: ui/chat/ChatViewModel.kt
```

| 改动位置 | 操作 |
|---------|------|
| 构造函数 | 注入 `SessionRegistry` |
| `sendMessage()` | 首次发送时调用 `sessionRegistry.register(...)` 构建 Chat 类型 session |
| `switchConversation()` | 同步更新当前 session 的 updatedAt 和 messageCount |
| `deleteConversation()` | 联动删除对应 session：`sessionRegistry.endSession(convId)` |
| `newConversation()` | 无需注册（惰性，等第一条消息） |
| Skill 切换 | 更新当前 session 的 `skillId` |
| 模型切换 | 更新当前 session 的 `modelId` |

### 5.2 SshViewModel 改动

```
文件: ui/ssh/SshViewModel.kt
```

| 改动位置 | 操作 |
|---------|------|
| 构造函数 | 注入 `SessionRegistry` |
| `sendMessage()` | 辅助模式首次发送时注册 SSH_ASSIST 类型 session，metadata 包含 sshHost |
| `disconnect()` | 结束对应 session：`sessionRegistry.endSession(sshSessionId)` |
| `setManualMode(true)` | 切换到手动模式时暂停 session（PAUSED） |
| `setManualMode(false)` | 切回辅助模式时恢复 session（ACTIVE） |

### 5.3 WorkbenchViewModel / WorkbenchEngine 改动

```
文件: workbench/WorkbenchEngine.kt
```

| 改动位置 | 操作 |
|---------|------|
| `execute()` 入口 | 注册 WORKBENCH 类型 session，label = 任务标题 |
| 任务完成/失败 | 结束 session（ENDED） |
| 任务取消 | 结束 session（ENDED） |

---

## 六、UI 层设计

### 6.1 设置入口

```
文件: ui/settings/SettingsScreen.kt
改动: 添加一行 SettingsItem
```

```kotlin
SettingsItem(
    Icons.Outlined.ManageAccounts,  // 或 Hub / AccountTree
    "会话管理",
    "查看和管理所有 AI 会话",
    "settings/sessions"
)
```

### 6.2 会话管理页面

```
新建文件: ui/settings/SessionScreen.kt
路由: settings/sessions
```

**页面结构**:

```
┌─────────────────────────────────┐
│  ← 会话管理            🗑 清理   │  ← TopBar，清理按钮清除已结束的 session
├─────────────────────────────────┤
│  [全部] [聊天] [SSH] [工作台]    │  ← 类型筛选 Tab
├─────────────────────────────────┤
│  ┌─────────────────────────────┐│
│  │ 🟢 关于 Nginx 配置的问题     ││  ← 状态圆点 + label
│  │ 💬 聊天 · Default Skill     ││  ← 类型 + Skill 名称
│  │ deepseek-r1 · 12 条消息     ││  ← 模型 + 消息数
│  │ 3 分钟前                    ││  ← 相对时间
│  │              [暂停] [删除]  ││  ← 操作按钮
│  └─────────────────────────────┘│
│  ┌─────────────────────────────┐│
│  │ 🟢 192.168.1.100 SSH 辅助   ││
│  │ 🖥 SSH · SSH Assistant      ││
│  │ gpt-4o · 8 条消息           ││
│  │ 20 分钟前                   ││
│  │              [暂停] [删除]  ││
│  └─────────────────────────────┘│
│  ┌─────────────────────────────┐│
│  │ ⚫ 天气查询应用              ││  ← ENDED 状态
│  │ 🔨 工作台 · Coding Skill    ││
│  │ claude-4 · 45 条消息        ││
│  │ 2 小时前                    ││
│  │                     [删除]  ││  ← 已结束只能删除
│  └─────────────────────────────┘│
└─────────────────────────────────┘
```

**每个 Session 卡片显示**:
- 状态指示灯（🟢 ACTIVE / 🟡 PAUSED / ⚫ ENDED）
- 会话标签（label）
- 来源类型图标 + Skill 名称
- 模型名称 + 消息条数
- 相对时间
- 操作按钮：暂停/恢复、删除

**操作逻辑**:
- **暂停**: 将 session 状态设为 PAUSED，对应 ViewModel 停止 AI 请求
- **恢复**: 将 session 状态恢复为 ACTIVE
- **删除**: 删除 session 记录。Chat 类型联动询问是否同时删除对话历史

### 6.3 NavGraph 路由注册

```
文件: ui/navigation/NavGraph.kt
改动 4 处:
```

1. **Screen 密封类**: 添加 `data object SessionManage : Screen("settings/sessions", "会话管理")`
2. **BackHandler**: 添加 `Screen.SessionManage.route` → 返回 Settings
3. **title/backTarget 映射**: 添加对应条目
4. **when 内容块**: 添加 `Screen.SessionManage.route -> SessionScreen(...)`

---

## 七、阻断（暂停）机制设计

当用户在会话管理中点击"暂停"时：

| 会话类型 | 阻断效果 |
|---------|---------|
| Chat | 设置 `_isPaused` flag → `sendMessage()` 检查 flag，拒绝发送并提示"会话已暂停" |
| SSH 辅助 | 设置 `_isPaused` flag → `sendMessage()` 拒绝 → 不影响手动终端操作 |
| Workbench | 取消当前执行的 coroutine Job → 任务进入 PAUSED 状态 |

恢复时清除 flag / 重启 Job。

**实现方式**: SessionRegistry 持有 `pausedSessionIds: StateFlow<Set<String>>`，各 ViewModel 在发送前检查。

---

## 八、会话隔离保障

当前隔离现状与改进：

| 维度 | 现状 | 改进 |
|------|------|------|
| 消息隔离 | Chat: ✅ (per Conversation)；SSH: ✅ (独立 _messages)；Workbench: ✅ (per task) | 无需改动 |
| AI Context 隔离 | Chat: ⚠️ 共用 aiConfig 单例；SSH: ✅ 独立 prompt；Workbench: ✅ per-task AiConfig | Session 元数据记录每个会话独立的 modelId/skillId，切换会话时恢复对应配置 |
| Skill 隔离 | Chat: 全局 _activeSkill 切换影响所有对话 | Session 记录 skillId → 切换对话时自动恢复该对话绑定的 Skill |
| 工具隔离 | Workbench: ✅ per-task ToolExecutor；Chat: 共用 | 暂不改动（Phase 3 范围） |

---

## 九、实施步骤（按优先级排序）

### Phase 1: 数据基础（预计改动 3 个文件 + 新建 3 个文件）

| 步骤 | 文件 | 操作 |
|------|------|------|
| 1.1 | `data/model/ManagedSession.kt` | **新建** — 数据类 + 枚举 |
| 1.2 | `data/SessionStorage.kt` | **新建** — JSON 文件持久化 |
| 1.3 | `data/SessionRegistry.kt` | **新建** — 内存单例 + StateFlow |

### Phase 2: ViewModel 集成（改动 3 个文件）

| 步骤 | 文件 | 操作 |
|------|------|------|
| 2.1 | `ui/chat/ChatViewModel.kt` | **改** — 注入 Registry，sendMessage/switchConv/delete 处 hook |
| 2.2 | `ui/ssh/SshViewModel.kt` | **改** — 注入 Registry，sendMessage/disconnect 处 hook |
| 2.3 | `workbench/WorkbenchEngine.kt` | **改** — execute/complete/cancel 处 hook |

### Phase 3: UI + 路由（新建 1 个文件 + 改动 2 个文件）

| 步骤 | 文件 | 操作 |
|------|------|------|
| 3.1 | `ui/settings/SessionScreen.kt` | **新建** — 会话管理列表页 |
| 3.2 | `ui/settings/SettingsScreen.kt` | **改** — 添加入口项 |
| 3.3 | `ui/navigation/NavGraph.kt` | **改** — 4 处路由注册 |

### Phase 4: 阻断与隔离增强（改动 3-4 个文件）

| 步骤 | 文件 | 操作 |
|------|------|------|
| 4.1 | `data/SessionRegistry.kt` | **改** — 添加 pause/resume API + pausedIds StateFlow |
| 4.2 | `ui/chat/ChatViewModel.kt` | **改** — sendMessage 检查暂停状态 |
| 4.3 | `ui/ssh/SshViewModel.kt` | **改** — sendMessage 检查暂停状态 |
| 4.4 | `workbench/WorkbenchEngine.kt` | **改** — 暂停/恢复 Job |

---

## 十、文件变更清单

| 操作 | 文件路径 | Phase |
|------|---------|-------|
| 新建 | `app/src/main/java/com/example/link_pi/data/model/ManagedSession.kt` | 1 |
| 新建 | `app/src/main/java/com/example/link_pi/data/SessionStorage.kt` | 1 |
| 新建 | `app/src/main/java/com/example/link_pi/data/SessionRegistry.kt` | 1 |
| 新建 | `app/src/main/java/com/example/link_pi/ui/settings/SessionScreen.kt` | 3 |
| 改动 | `app/src/main/java/com/example/link_pi/ui/chat/ChatViewModel.kt` | 2, 4 |
| 改动 | `app/src/main/java/com/example/link_pi/ui/ssh/SshViewModel.kt` | 2, 4 |
| 改动 | `app/src/main/java/com/example/link_pi/workbench/WorkbenchEngine.kt` | 2, 4 |
| 改动 | `app/src/main/java/com/example/link_pi/ui/settings/SettingsScreen.kt` | 3 |
| 改动 | `app/src/main/java/com/example/link_pi/ui/navigation/NavGraph.kt` | 3 |

**总计**: 新建 4 文件，改动 5 文件，分 4 个 Phase 递进实施。
