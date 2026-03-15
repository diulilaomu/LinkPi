package com.example.link_pi.skill

/**
 * 主入口 (Gateway) — 所有域共享的基础层。
 *
 * 包含：角色定义、通用规则、记忆指引。
 * 工具调用现在通过 API 的 function calling 机制原生支持，不再需要 XML 格式说明。
 * 此层内容固定不变，Phase 切换时无需重建。
 */
object PromptGateway {

    /** 通用规则 — 适用于所有域所有阶段 */
    val UNIVERSAL_RULES = "必须包含所有必需参数并填写实际值。当参数必需时，绝对不要传递空参数 `{}`。你可以在一次回复中发起多个工具调用。"

    /** 记忆段：已加载记忆快照时 — 使用占位符 {{SNAPSHOT}} 代替 %s 防止 snapshot 含 % 时崩溃 */
    private val MEMORY_WITH_SNAPSHOT = """
### 长期记忆

你拥有持久化记忆系统，记忆可跨会话保留。
{{SNAPSHOT}}
你的已知记忆已加载在上方[已知记忆]中——请在回复中自然地使用（例如称呼用户姓名、应用其风格偏好）。

**何时保存**：用户告诉你偏好、个人信息（姓名、习惯）、重要事实，或你学到有用信息时。
**何时搜索**：当你需要上方未显示的记忆，或针对特定主题搜索时。

重要：始终尊重用户的已知偏好。如果[已知记忆]表明用户喜欢暗色主题，则默认使用暗色主题，无需询问。
""".trimIndent()

    /** 记忆段：记忆未加载时 */
    private val MEMORY_WITHOUT_SNAPSHOT = """
### 长期记忆

你拥有持久化记忆系统，但记忆未加载到你的上下文中。
当你认为过去的知识可能相关时（例如用户提及之前的偏好或过去的对话），主动使用 memory_search 查找信息。
当用户告诉你重要事实或偏好时，使用 memory_save 保存。
""".trimIndent()

    /** 构建记忆段 */
    fun buildMemorySection(snapshot: String?): String {
        return if (snapshot != null) {
            MEMORY_WITH_SNAPSHOT.replace("{{SNAPSHOT}}", snapshot)
        } else {
            MEMORY_WITHOUT_SNAPSHOT
        }
    }

    /**
     * 构建 Gateway 层系统提示 — 所有域共享的 messages[0]。
     *
     * @param skillPrompt  角色定义（Skill.systemPrompt）
     * @param memorySnapshot  记忆快照内容（null 表示未加载）
     * @param injectedPrompts  辅助 Skill 注入的指令列表（≤4KB）
     */
    fun build(
        skillPrompt: String,
        memorySnapshot: String?,
        injectedPrompts: List<String> = emptyList()
    ): String = buildString {
        // [1] 角色定义
        appendLine(skillPrompt)

        // [2] 辅助 Skill 指令
        if (injectedPrompts.isNotEmpty()) {
            appendLine()
            appendLine("### 辅助指令")
            injectedPrompts.forEach { prompt ->
                appendLine(prompt)
                appendLine()
            }
        }

        // [3] 通用规则
        appendLine()
        appendLine("### 通用规则")
        appendLine("- $UNIVERSAL_RULES")

        // [4] 记忆指引
        appendLine()
        append(buildMemorySection(memorySnapshot))
    }.trimEnd()
}
