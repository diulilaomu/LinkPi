package com.example.link_pi.skill

/**
 * 修改域 (MODIFY_APP) 各阶段的 Prompt 模板。
 *
 * 阶段：规划 → 生成 → 自检
 *
 * generation() 已将原先 AgentOrchestrator 中的 genInstruction（外科手术式修改规则）
 * 合并到域指令中，不再需要运行时额外注入。
 */
object PromptModify {

    // ─────────────────────────────────────
    //  规划阶段
    // ─────────────────────────────────────

    /** 规划阶段域指令 */
    fun planning(): String = """
### 工作流：修改应用（规划阶段）

你当前处于规划阶段。你的任务是**深入理解现有代码**并制定精确的修改计划，而不是直接修改代码。

⚠ 修改的核心原则：**最小化改动、保全现有功能**。只修改实现用户需求所必需的部分，绝不重构或改动无关代码。

**步骤 1 — 确保工作区已打开**
如果工作区为空，先查找并打开目标应用：
→ list_saved_apps()
→ open_app_workspace(app_id: "粘贴实际UUID")

**步骤 2 — 查看架构概览**
使用 inspect_workspace 工具获取项目架构分析（依赖图、导出索引、引用完整性）：
→ inspect_workspace()
这将帮助你理解各文件的职责和依赖关系。

**步骤 3 — 必须读取所有将被修改的文件（完整内容）**
对每个可能需要修改的文件执行完整读取：
→ read_file(path: "js/app.js")
→ read_file(path: "css/style.css")
这一步是**强制的**——跳过读取直接输出计划将导致修改失败。

**步骤 4 — 理解代码结构**
基于 inspect_workspace 的架构分析和文件内容，理解：
- 各文件的职责和相互依赖关系
- 用户要修改的功能涉及哪些函数、CSS类名、DOM结构
- 哪些代码是安全区域（不能动），哪些是修改目标

**步骤 5 — 输出结构化修改计划 `<modification_plan>` 块**（⚠ 必须输出）：

<modification_plan>
修改目标: [一句话描述用户要求的修改]
影响范围:
  - path/file1.js: [具体改哪个函数/哪段代码，改动原因]
  - path/file2.css: [具体改哪些规则，改动原因]
  - path/file3.html: [具体改哪些元素，改动原因]
不修改的文件: [明确列出不需要改动的文件，证明你理解了代码边界]
修改顺序:
  1. [先改被依赖的文件（底层工具函数/配置）]
  2. [再改依赖者（主逻辑/样式）]
  3. [最后改入口文件（HTML结构）]
修改策略:
  - file1.js: edit_file（局部修改函数 X 的实现）
  - file2.css: edit_file（修改 .class-name 的样式）
  - file3.html: write_file（结构变化较大，整体重写）
关键约束:
  - [CSS类名变更需同步更新 JS 中的引用]
  - [函数签名变更需检查所有调用方]
  - [其他需要注意的联动点]
</modification_plan>

**步骤 6 — 附加能力选择**（如果修改需要新增 NativeBridge API 或模块能力）：

<capability_selection>
tools: MODULE
bridge: NETWORK, STORAGE
</capability_selection>

⛛ 规划阶段不要修改任何文件——不要调用 write_file、edit_file 等写入工具。只读取和分析。
⛔ 不要输出任何代码块。只输出规划文本 + modification_plan + capability_selection。
""".trimIndent()

    // ─────────────────────────────────────
    //  生成阶段
    // ─────────────────────────────────────

    /**
     * 生成阶段域指令 — 已合并原 genInstruction 的外科手术式修改规则。
     * 不再需要 AgentOrchestrator 运行时额外注入 genInstruction。
     */
    fun generation(): String = """
### 工作流：修改应用（生成阶段）

你现在处于**修改应用**的生成阶段。

→ 先调用 read_plan() 获取规划阶段的修改计划（modification_plan）
→ 如需了解项目架构，调用 inspect_workspace() 获取依赖图和导出索引

修改计划已确定了要修改的文件和策略。

⚠ 核心原则：外科手术式修改——只改计划中列出的文件和区域，不动其他任何代码。

**确认工作区状态**（首先执行）：

**情况 A：当前工作区已有文件**（应用刚在本次对话中创建，或工作区已打开）
→ 跳过 list_saved_apps 和 open_app_workspace，直接按计划逐文件修改。

**情况 B：当前工作区为空**（需要打开已保存的应用）
→ 先 list_saved_apps 获取 app_id（绝对不要凭空猜测），再 open_app_workspace 打开。
→ 如果 list_saved_apps 中没有目标应用，说明应用尚未保存——直接在当前工作区用 write_file 重建。

**执行规则**（按修改计划逐文件处理）：
1. **按修改计划中的文件顺序**逐个处理，不要跳跃或并行
2. **每个文件修改前必须先 read_file 读取完整最新内容**——每次都要读，不要凭记忆（系统会强制检查，未读取直接编辑会被拒绝）
3. 用 edit_file(command=replace_text) 精确修改（old_text 必须从读取结果中直接复制，包含足够的上下文使匹配唯一）
4. edit_file(command=replace_text) 的 old_text 应包含要修改行前后各 2-3 行的上下文代码
5. edit_file 失败一次 → 重新 read_file 再构造 old_text 重试
6. edit_file(command=replace_text) 连续失败两次 → 改用 edit_file(command=replace_lines) 按行号替换
7. edit_file(command=replace_lines) 仍失败或修改超过60%内容 → 改用 write_file 整体重写该文件
8. 修改CSS类名/JS函数名后，用 search 搜索所有引用处并同步更新
9. 所有修改完成后用 validate 自检

**禁止事项**：
- ❌ 不要重构未被要求修改的代码
- ❌ 不要改变现有的代码风格或命名习惯
- ❌ 不要删除不理解的代码
- ❌ 不要在没有读取文件的情况下构造 edit_file 的 old_text

**辅助工具提示**：
- edit_file(command=replace_text) 内置9级级联匹配（精确→空白容错→块锚点→缩进弹性等），大多数情况能自动匹配
- diff_file 可查看文件与上一版本的差异
- undo_file(list_only=true) 查看文件可用历史版本
- undo_file 可撤销最近一次修改
- 工具输出被截断时，用 read_truncated_output 获取完整内容
""".trimIndent()

    // ─────────────────────────────────────
    //  自检阶段
    // ─────────────────────────────────────

    /** 自检指令 */
    fun selfCheck(): String = """
### 自检阶段

你现在处于自检阶段。检查所有被修改过的文件，验证修改是否正确。

**检查流程**：
1. 对 index.html 执行 validate：
→ validate(path: "index.html")

2. 对修改过的 .js 文件执行 validate：
→ validate(path: "js/app.js")

3. validate 返回 `{"valid": true/false, "errors": [...]}` — 有错误时：
   - 根据 errors 中的**行号**和**描述**定位问题
   - 使用 edit_file 或 write_file 修复
   - 修复后**再次 validate** 确认问题已解决

4. 使用 diff_file 检查关键文件，确认修改符合预期：
→ diff_file(path: "js/app.js")

**修改后常见问题**：
- 函数签名变更导致调用方报错 → search 搜索并同步更新
- CSS 类名修改未同步到 JS → 检查 JS 中的 classList 操作
- HTML 结构变更破坏了 JS querySelector → 更新选择器
""".trimIndent()

}
