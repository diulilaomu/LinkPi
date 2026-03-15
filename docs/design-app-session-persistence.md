# 应用状态保持层设计文档

> 日期: 2026-03-12  
> 状态: 设计阶段，未实现

## 一、现状问题

当前状态持久化是碎片化的，存在 5 个独立机制，没有统一抽象：

| 机制 | 作用域 | 问题 |
|------|--------|------|
| `ShortcutSessionStore` | 仅 SSH + 仅快捷方式启动 | 普通导航不保持状态 |
| `NativeBridge.saveData` | MiniApp JS 层 | JS 端需主动调用，无自动保存 |
| `WebView DOM` | MiniApp 渲染层 | Composable 卸载即丢失，不可恢复 |
| `SessionRegistry` | SSH 会话列表 | 纯内存，进程死亡即丢 |
| `MiniAppStorage` | MiniApp 元数据 | 只存定义，不存运行状态 |

**核心痛点**：用户生成了一个电控系统 MiniApp，每次退出再进入，WebView 从首页重新加载，之前的导航位置、表单数据、页面状态全部丢失。SSH 也只有快捷方式启动才能自动恢复连接。

### MiniApp 切换行为分析

MiniApp A → 返回列表 → 点击 MiniApp B → 返回列表 → 再次打开 MiniApp A：

| 阶段 | WebView | JS 内存 | DOM/滚动/表单 | NativeBridge 数据 |
|------|---------|---------|--------------|-------------------|
| 离开 A | `webView.destroy()` 销毁 | 全部丢失 | 全部丢失 | **磁盘保留** |
| 进入 B | 全新创建 | 全新 | 全新 | 读 B 的数据 |
| 再次进入 A | 全新创建 | 全新 | 全新 | 读 A 的数据 |

关键事实：
- 只有 **一个** `Screen.MiniApp.route`，没有 per-app 路由
- `currentMiniApp` 是 `ChatViewModel` 上的纯内存引用
- WebView 的 `remember {}` 无 appId key，切换 app 时整个 Composable 重建
- `DisposableEffect` 中调用 `webView.destroy()`，彻底销毁

## 二、设计目标

1. **统一抽象** — 内置应用（SSH）和生成应用（MiniApp）共用同一套状态保持 API
2. **热保活** — MiniApp 之间切换时 WebView 不销毁，实现浏览器标签页式的瞬间切换
3. **冷恢复兜底** — 超出保活数量或进程死亡时，通过序列化状态恢复
4. **自动化** — 应用退出/暂停时自动保存，进入时自动恢复，无需用户干预
5. **可扩展** — 未来新增内置应用时，只需实现接口即可获得状态保持能力

## 三、混合策略：Keep-Alive + Save & Restore 降级

```
┌──────────────────────────────────────────────────────────────┐
│                     NavGraph (路由层)                          │
│  MiniApp 切换 → pool.acquire / pool.release                   │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─ WebViewPool (热保活层) ──────────────────────────────┐   │
│  │  LRU 缓存，最多 N 个 WebView 实例                      │   │
│  │  切入: acquire(appId) → 命中池 → attach (瞬间恢复)     │   │
│  │                       → 未命中 → 新建 + 冷恢复         │   │
│  │  切出: release(appId) → detach (WebView 保活)          │   │
│  │  驱逐: evict → collectState() → destroy                │   │
│  └───────────────────────────────┬───────────────────────┘   │
│                                  │ 驱逐时序列化 / 冷恢复读取  │
│  ┌─ AppSessionManager ──────────┴───────────────────────┐   │
│  │  suspend(appId, state) / resume(appId)                │   │
│  │  支持 MiniApp + SSH + 未来内置应用                      │   │
│  └───────────────────────────────┬───────────────────────┘   │
│                                  │                           │
│  ┌─ AppSessionStore (磁盘层) ───┴───────────────────────┐   │
│  │  SharedPreferences("app_sessions")                    │   │
│  │  JSON: { appId, appType, lastActiveAt, state{} }      │   │
│  └───────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

## 四、核心抽象

### 文件结构

```
data/session/
├── AppSession.kt            // 会话数据模型
├── AppSessionStore.kt       // 持久化存储（SharedPreferences JSON）
├── AppSessionManager.kt     // 会话生命周期管理器
miniapp/
├── WebViewPool.kt           // WebView 热保活池
├── WebViewEntry.kt          // 池中条目（WebView + NativeBridge + metadata）
```

### 4.1 AppSession — 会话数据模型

```kotlin
data class AppSession(
    val appId: String,              // MiniApp UUID 或内置应用标识如 "builtin:ssh"
    val appType: AppType,
    val lastActiveAt: Long,         // 最后活跃时间戳
    val state: Map<String, String>  // 灵活 KV 状态
)

enum class AppType {
    MINI_APP,       // AI 生成的 WebView 应用
    BUILTIN_SSH,    // SSH 终端
    // BUILTIN_SHARE   // 未来扩展
}
```

**state 字段约定**（按应用类型）：

| AppType | Key | 含义 | 示例值 |
|---------|-----|------|--------|
| MINI_APP | `url_hash` | SPA 路由 hash | `#/config/device-3` |
| MINI_APP | `scroll_y` | 滚动位置 | `1280` |
| MINI_APP | `js_state` | JS 主动保存的业务状态 JSON | `{"tab":"advanced","deviceId":3}` |
| BUILTIN_SSH | `server_id` | 最后连接的服务器 ID | `uuid-xxx` |
| BUILTIN_SSH | `manual_mode` | 是否手动模式 | `true` |

### 4.2 AppSessionStore — 持久化存储

```kotlin
class AppSessionStore(context: Context) {

    // 内部使用 SharedPreferences("app_sessions") 存储 JSON
    // 每个 appId 对应一条 JSON 记录

    fun save(session: AppSession)
    fun load(appId: String): AppSession?
    fun delete(appId: String)
    fun loadAll(): List<AppSession>
    fun deleteExpired(maxAgeMs: Long)   // 清理超过 N 天的旧会话
}
```

存储格式：
```json
{
  "appId": "miniapp-uuid-123",
  "appType": "MINI_APP",
  "lastActiveAt": 1741756800000,
  "state": {
    "url_hash": "#/config/device-3",
    "scroll_y": "1280",
    "js_state": "{\"tab\":\"advanced\"}"
  }
}
```

选择 SharedPreferences 原因：会话数据量小（每个应用 < 1KB），读写频繁，SharedPreferences 的内存缓存比文件 I/O 更高效。

### 4.3 AppSessionManager — 生命周期管理器

```kotlin
class AppSessionManager(
    private val store: AppSessionStore
) {
    /** 应用即将退出/暂停时调用 */
    fun suspend(appId: String, appType: AppType, state: Map<String, String>)

    /** 应用进入/恢复时调用，返回上次保存的状态 */
    fun resume(appId: String): AppSession?

    /** 应用被用户主动删除时清理 */
    fun destroy(appId: String)

    /** 获取最近使用的应用列表（用于"最近使用"排序） */
    fun getRecentApps(limit: Int = 10): List<AppSession>
}
```

### 4.4 WebViewPool — 热保活池

```kotlin
class WebViewPool(
    private val maxAlive: Int = 3,   // 最多保活 3 个 WebView
    private val sessionManager: AppSessionManager
) {
    // appId → WebViewEntry（accessOrder=true 实现 LRU）
    private val pool = LinkedHashMap<String, WebViewEntry>(maxAlive + 1, 0.75f, true)

    /** 获取或创建 WebView */
    fun acquire(appId: String, factory: () -> WebViewEntry): WebViewEntry {
        return pool[appId]?.also {
            it.lastAccess = System.currentTimeMillis()
        } ?: factory().also { entry ->
            if (pool.size >= maxAlive) evictLeastRecent()
            pool[appId] = entry
            val session = sessionManager.resume(appId)
            if (session != null) entry.pendingRestore = session
        }
    }

    /** 切换走时，detach 但不销毁 */
    fun release(appId: String) {
        pool[appId]?.let { entry ->
            (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
        }
    }

    /** 驱逐：序列化状态 → 销毁 WebView */
    private fun evictLeastRecent() {
        val eldest = pool.entries.first()
        val entry = eldest.value
        val state = entry.collectState()
        sessionManager.suspend(eldest.key, AppType.MINI_APP, state)
        entry.destroy()
        pool.remove(eldest.key)
    }

    /** 应用被删除时，彻底清理 */
    fun destroy(appId: String) {
        pool.remove(appId)?.destroy()
        sessionManager.destroy(appId)
    }

    /** Activity.onDestroy 时全部清理 */
    fun destroyAll() {
        pool.values.forEach { it.destroy() }
        pool.clear()
    }
}
```

### 4.5 WebViewEntry — 池中条目

```kotlin
class WebViewEntry(
    val webView: WebView,
    val nativeBridge: NativeBridge,
    var lastAccess: Long = System.currentTimeMillis(),
    var pendingRestore: AppSession? = null
) {
    /** 收集 WebView 当前状态（驱逐时调用） */
    fun collectState(): Map<String, String> {
        // evaluateJavascript("window.location.hash") → url_hash
        // evaluateJavascript("window.scrollY") → scroll_y
        // evaluateJavascript("window.__onSuspend?.()") → 触发 JS 保存
        // 读取 NativeBridge saveData 的 __session_state → js_state
    }

    fun destroy() {
        nativeBridge.webSocketBridge?.stop()
        webView.removeJavascriptInterface("NativeBridge")
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.destroy()
    }
}
```

## 五、集成点

### 5.1 MiniAppScreen 改造

```kotlin
// ── 之前 ──
val webView = remember {
    WebView(context).apply { ... }
}
DisposableEffect(Unit) {
    onDispose { webView.destroy() }   // 粗暴销毁
}

// ── 之后 ──
val entry = remember(app.id) {
    pool.acquire(app.id) { /* factory: 创建 WebViewEntry */ }
}
DisposableEffect(app.id) {
    onDispose { pool.release(app.id) }  // 只 detach，不销毁
}
// 使用 entry.webView 渲染
// 如果 entry.pendingRestore != null → 冷恢复逻辑
```

### 5.2 NativeBridge JS 回调

```javascript
// JS 端注册暂停回调（WebView 被驱逐时框架自动调用）
window.__onSuspend = function() {
    NativeBridge.saveData("__session_state", JSON.stringify({
        currentPage: "/config/device-3",
        formData: { voltage: 220, current: 15 }
    }));
};

// JS 端启动时检查恢复
window.__onResume = function(sessionState) {
    if (sessionState) {
        const state = JSON.parse(sessionState);
        navigateTo(state.currentPage);
        restoreForm(state.formData);
    }
};
```

后台 WebView 暂停通知：
```javascript
// release() 时注入
document.dispatchEvent(new Event('pause'));
// acquire() 恢复时注入
document.dispatchEvent(new Event('resume'));
```

### 5.3 SSH 集成 — 替换 ShortcutSessionStore

```
SSH 连接成功时:
  sessionManager.suspend("builtin:ssh", BUILTIN_SSH, {
      "server_id" to serverId,
      "manual_mode" to manualMode.toString()
  })

SSH 主动断开时:
  sessionManager.destroy("builtin:ssh")

SSH Home 进入时（不论是否快捷方式启动）:
  val session = sessionManager.resume("builtin:ssh")
  if (session != null) → autoConnect(session.state["server_id"])
```

关键改进：当前 `ShortcutSessionStore` 只在快捷方式启动时恢复。新方案下，普通导航进入 SSH 也能自动恢复上次连接。

### 5.4 NavGraph 集成

- WebViewPool 在 NavGraph 顶层 `remember` 创建
- `Activity.onDestroy` 通过 `DisposableEffect` 调用 `pool.destroyAll()`
- MiniApp 页面进入/退出走 pool.acquire / pool.release

## 六、切换场景分析

### 池内热切换（池容量=3）

| 步骤 | 操作 | Pool 状态 | 用户体验 |
|------|------|-----------|---------|
| 1 | 打开 A | `[A*]` | 正常加载 |
| 2 | 返回列表 | `[A]` (detached, alive) | A 的 JS/WebSocket 仍在跑 |
| 3 | 打开 B | `[A, B*]` | 正常加载 |
| 4 | 返回列表 | `[A, B]` | 两个都 alive |
| 5 | 再打开 A | `[B, A*]` | **瞬间恢复！** DOM/滚动/表单全在 |

### 池满驱逐（池容量=3，已有 A, B, C）

| 步骤 | 操作 | Pool 状态 | 发生了什么 |
|------|------|-----------|-----------|
| 1 | 打开 D | 驱逐 A → `[B, C, D*]` | A 的状态序列化到 AppSessionStore |
| 2 | 再打开 A | 驱逐 B → `[C, D, A*]` | A 新建 WebView + 从 AppSessionStore 冷恢复 |

## 七、边界处理

| 问题 | 方案 |
|------|------|
| 后台 WebView 耗电（JS 定时器/WebSocket） | `release` 时 dispatch `pause` 事件，JS 端自行暂停轮询 |
| Activity 被系统回收 | `Activity.onDestroy` → `pool.destroyAll()`，全部走冷恢复路径 |
| 内存压力 | 监听 `ComponentCallbacks2.onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` → 提前驱逐 |
| 同一个 app 重复打开 | Pool 用 appId 做 key，同一 app 不会有多个实例 |
| WebView 完整 DOM 恢复 | **不做** — `WebView.saveState(Bundle)` 不可靠且不跨进程 |
| 终端文本缓冲持久化 | **暂不做** — 数据量大，SSH 重连后服务端不返回历史 |
| 会话过期清理 | **30 天自动清理** — 避免无限堆积 |
| 加密存储 | **不需要** — 会话状态不含敏感信息 |
| MiniApp JS auto-save 时机 | 被驱逐时触发 `__onSuspend`；热保活时不需要 |

## 八、迁移计划

| 步骤 | 内容 | 文件影响 |
|------|------|----------|
| 1 | 创建 `AppSession` + `AppSessionStore` + `AppSessionManager` | 新增 3 个文件 |
| 2 | 创建 `WebViewPool` + `WebViewEntry` | 新增 2 个文件 |
| 3 | 改造 `MiniAppScreen` — 用 Pool 管理 WebView 生命周期 | 修改 MiniAppScreen.kt |
| 4 | NativeBridge 新增 `__onSuspend`/`__onResume` + pause/resume 事件 | 修改 NativeBridge.kt |
| 5 | SSH 集成 — 替换 `ShortcutSessionStore` 为 `AppSessionManager` | 修改 NavGraph.kt, SshHomeViewModel.kt |
| 6 | 删除 `ShortcutSessionStore.kt` | 删除文件 |
| 7 | （可选）MiniApp 列表按 `lastActiveAt` 排序 | 修改 MiniAppListScreen.kt |

## 九、受影响组件评估

### CRITICAL — 必须改动，核心路径

| 文件 | 改动量 | 风险 | 具体变更 |
|------|--------|------|----------|
| **NavGraph.kt** | ~50 行 | 🔴 高 | 删 `ShortcutSessionStore` 引用 → 换 `AppSessionManager`；MiniApp 渲染区接入 WebViewPool；SSH auto-connect 改用统一 session；共 6 处调用替换（L82 import, L129 实例化, L203 clear, L449 get, L469 save, L212-230 渲染逻辑） |
| **ShortcutSessionStore.kt** | 整文件 | 🔴 高 | **删除**，功能完全被 `AppSessionManager` 吸收 |
| **MiniAppScreen.kt** | ~40 行 | 🔴 高 | `MiniAppWebView` 和 `WorkspaceMiniAppWebView` 两处 DisposableEffect 从 `webView.destroy()` → `pool.release()`；WebView 创建从 `remember{}` → `pool.acquire()`；CdnProxy 实例随 WebViewEntry 管理 |

### HIGH — 重要改动，影响状态流

| 文件 | 改动量 | 风险 | 具体变更 |
|------|--------|------|----------|
| **NativeBridge.kt** | ~25 行 | 🟠 中高 | 新增 `__onSuspend()` / `__onResume()` JS 注入逻辑；WebSocketBridge 在驱逐前 checkpoint；release 时 dispatch `pause` 事件，acquire 时 dispatch `resume` 事件 |
| **ChatViewModel.kt** | ~15 行 | 🟠 中高 | `currentMiniApp = null`（L145/158/177 三处）前触发 session checkpoint；`setCurrentApp()`（L440）接入 pool lookup 逻辑 |

### MEDIUM — 配合改动，低复杂度

| 文件 | 改动量 | 风险 | 具体变更 |
|------|--------|------|----------|
| **SshHomeViewModel.kt** | ~2 行 | 🟡 中 | `autoConnectServer()` 验证与新 manager 兼容（实际可能无需改码，NavGraph 上游已适配） |
| **MiniAppListScreen.kt** | ~2 行 | 🟡 中 | 删除 app 时追加 `sessionManager.destroy(appId)` 清理该 app 的会话数据 |
| **WorkspaceManager.kt** | 0 行 | 🟡 低 | 无需改码；需验证 `deleteWorkspace()` 与 session 清理的调用顺序正确 |

### LOW — 无需改动，仅验证

| 文件 | 改动量 | 风险 | 说明 |
|------|--------|------|------|
| **MainActivity.kt** | 0 行 | 🟢 低 | Intent 解析逻辑不变，`launchMiniAppId`/`launchPage` 继续透传给 NavGraph |
| **ShortcutHelper.kt** | 0 行 | 🟢 低 | 快捷方式创建逻辑不变，会话恢复在 NavGraph 层处理 |
| **MiniAppStorage.kt** | 0 行 | 🟢 低 | 纯元数据 CRUD，与会话状态正交 |
| **SessionRegistry.kt** | 0 行 | 🟢 低 | 对话会话管理，与 app 会话是不同概念 |
| **CdnProxy.kt** | 0 行 | 🟢 低 | 内容代理，随 WebViewEntry 管理即可 |
| **WebSocketBridge.kt** | 0 行 | 🟢 低 | 在 NativeBridge/WebViewEntry 层面控制生命周期 |
| **RuntimeErrorCollector.kt** | ~1 行 | 🟢 低 | Pool release 时可选 `clearErrors(appId)` |

### 新增文件

| 文件 | 预估行数 | 说明 |
|------|----------|------|
| `data/session/AppSession.kt` | ~15 行 | 会话数据模型 + `AppType` 枚举 |
| `data/session/AppSessionStore.kt` | ~60 行 | SharedPreferences JSON 持久化 |
| `data/session/AppSessionManager.kt` | ~40 行 | suspend/resume/destroy 生命周期管理 |
| `miniapp/WebViewPool.kt` | ~80 行 | LRU WebView 热保活池 |
| `miniapp/WebViewEntry.kt` | ~50 行 | 池条目：WebView + NativeBridge + collectState + destroy |

### 影响总览

```
改动文件:    5 个（NavGraph, MiniAppScreen, NativeBridge, ChatViewModel, MiniAppListScreen）
删除文件:    1 个（ShortcutSessionStore.kt）
新增文件:    5 个（AppSession, AppSessionStore, AppSessionManager, WebViewPool, WebViewEntry）
无需改动:    8 个（MainActivity, ShortcutHelper, MiniAppStorage, SshHomeViewModel,
               WorkspaceManager, SessionRegistry, CdnProxy, WebSocketBridge）
预估总改动: ~130 行修改 + ~245 行新增
```

### 关键风险点

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| WebView 内存泄漏 | Pool 中 WebView 未正确 detach → OOM | acquire/release 时断言检查 parent；onTrimMemory 主动驱逐 |
| JS 回调悬空 | __onSuspend 中 JS Promise 未 resolve → 驱逐阻塞 | 设置 500ms 超时，超时后强制驱逐 |
| WebSocket 端口占用 | 后台 WebView 的 WebSocket server 占着端口 | release 时 stop bridge；acquire 恢复时重建 |
| 冷恢复不完整 | SPA 依赖的 JS 运行时状态无法序列化 | 属预期降级，hash + js_state 覆盖 80% 场景；文档告知 JS 开发者用 __onSuspend |
| 会话 ↔ 元数据不一致 | App 被删除但 session 残留 | MiniAppListScreen 删除时同步 destroy session |

## 十、收益总结

1. **MiniApp 热切换** — 池内切换零延迟，浏览器标签页体验
2. **MiniApp 冷恢复** — 池外切换自动恢复路由+滚动+业务状态
3. **SSH 始终恢复** — 不论快捷方式还是普通导航，都能自动恢复连接
4. **开发者友好** — 未来新增内置应用只需调用 `sessionManager.suspend/resume`
5. **代码整洁** — 消除 `ShortcutSessionStore` 等单用途类，统一到一个抽象
