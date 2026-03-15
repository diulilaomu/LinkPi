package com.example.link_pi.skill

/**
 * 域接口 — 每个域提供各阶段的 prompt 文本与工具组。
 */
object PromptDomain {
    /** 阶段枚举 */
    enum class Phase {
        PLANNING,
        GENERATION,
        SELF_CHECK
    }
}
