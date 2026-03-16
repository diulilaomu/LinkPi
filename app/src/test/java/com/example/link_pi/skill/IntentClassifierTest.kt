package com.example.link_pi.skill

import org.junit.Assert.*
import org.junit.Test

class IntentClassifierTest {

    // ── MODULE_MGMT intent ──

    @Test
    fun `Chinese module keyword triggers MODULE_MGMT`() {
        assertEquals(UserIntent.MODULE_MGMT, IntentClassifier.classifyLocal("创建一个模块", false))
    }

    @Test
    fun `English module keyword with word boundary triggers MODULE_MGMT`() {
        assertEquals(UserIntent.MODULE_MGMT, IntentClassifier.classifyLocal("create a module for me", false))
    }

    @Test
    fun `endpoint keyword triggers MODULE_MGMT`() {
        assertEquals(UserIntent.MODULE_MGMT, IntentClassifier.classifyLocal("添加一个端点", false))
    }

    @Test
    fun `api keyword triggers MODULE_MGMT`() {
        assertEquals(UserIntent.MODULE_MGMT, IntentClassifier.classifyLocal("I need an api", false))
    }

    @Test
    fun `module substring should NOT trigger - word boundary`() {
        // "modular" contains "module" as substring — word boundary should prevent match
        assertEquals(UserIntent.CONVERSATION, IntentClassifier.classifyLocal("this is a modular design", false))
    }

    // ── MEMORY_OPS intent ──

    @Test
    fun `Chinese memory keyword triggers MEMORY_OPS`() {
        assertEquals(UserIntent.MEMORY_OPS, IntentClassifier.classifyLocal("帮我记住这个偏好", false))
    }

    @Test
    fun `English memory keyword triggers MEMORY_OPS`() {
        assertEquals(UserIntent.MEMORY_OPS, IntentClassifier.classifyLocal("save to memory", false))
    }

    @Test
    fun `forget keyword triggers MEMORY_OPS`() {
        assertEquals(UserIntent.MEMORY_OPS, IntentClassifier.classifyLocal("忘记之前的设置", false))
    }

    @Test
    fun `preference keyword triggers MEMORY_OPS`() {
        assertEquals(UserIntent.MEMORY_OPS, IntentClassifier.classifyLocal("记录我的偏好", false))
    }

    // ── CONVERSATION fallback ──

    @Test
    fun `general question returns CONVERSATION`() {
        assertEquals(UserIntent.CONVERSATION, IntentClassifier.classifyLocal("what is the weather today", false))
    }

    @Test
    fun `empty message returns CONVERSATION`() {
        assertEquals(UserIntent.CONVERSATION, IntentClassifier.classifyLocal("", false))
    }

    @Test
    fun `Chinese casual chat returns CONVERSATION`() {
        assertEquals(UserIntent.CONVERSATION, IntentClassifier.classifyLocal("你好，今天怎么样", false))
    }

    // ── Priority: MEMORY_OPS > MODULE_MGMT ──

    @Test
    fun `memory keyword takes priority over module keyword`() {
        // "记忆" (memory) should win over "模块" (module) since MEMORY is checked first
        assertEquals(UserIntent.MEMORY_OPS, IntentClassifier.classifyLocal("记住这个模块的配置", false))
    }

    // ── Case insensitivity ──

    @Test
    fun `English keywords are case insensitive`() {
        assertEquals(UserIntent.MODULE_MGMT, IntentClassifier.classifyLocal("CREATE A MODULE", false))
    }

    @Test
    fun `mixed case memory keyword works`() {
        assertEquals(UserIntent.MEMORY_OPS, IntentClassifier.classifyLocal("Save to Memory please", false))
    }
}
