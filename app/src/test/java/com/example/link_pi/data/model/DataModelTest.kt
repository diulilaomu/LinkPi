package com.example.link_pi.data.model

import org.junit.Assert.*
import org.junit.Test

class DataModelTest {

    // ── Conversation ──

    @Test
    fun `Conversation default timestamps are set`() {
        val conv = Conversation(id = "test-1", title = "Test Chat")
        assertTrue(conv.createdAt > 0)
        assertTrue(conv.updatedAt > 0)
    }

    @Test
    fun `Conversation custom timestamps are preserved`() {
        val conv = Conversation(id = "c1", title = "T", createdAt = 100, updatedAt = 200)
        assertEquals(100L, conv.createdAt)
        assertEquals(200L, conv.updatedAt)
    }

    // ── ChatMessage ──

    @Test
    fun `ChatMessage default values`() {
        val msg = ChatMessage(id = "m1", role = "user", content = "hello")
        assertNull(msg.miniApp)
        assertTrue(msg.agentSteps.isEmpty())
        assertEquals("", msg.thinkingContent)
        assertTrue(msg.attachments.isEmpty())
        assertTrue(msg.timestamp > 0)
    }

    @Test
    fun `ChatMessage with all fields`() {
        val app = MiniApp(id = "a1", name = "App", description = "desc", htmlContent = "<html></html>")
        val msg = ChatMessage(
            id = "m2", role = "assistant", content = "here's your app",
            miniApp = app,
            thinkingContent = "Let me think...",
            attachments = listOf(Attachment("file.txt", "text/plain", textContent = "data"))
        )
        assertEquals("App", msg.miniApp?.name)
        assertEquals("Let me think...", msg.thinkingContent)
        assertEquals(1, msg.attachments.size)
        assertEquals("data", msg.attachments[0].textContent)
    }

    // ── Attachment ──

    @Test
    fun `Attachment text content is nullable`() {
        val att = Attachment(name = "image.png", mimeType = "image/png")
        assertNull(att.textContent)
        assertNull(att.base64Data)
    }

    // ── MiniApp ──

    @Test
    fun `MiniApp default values`() {
        val app = MiniApp(id = "x", name = "X", description = "d", htmlContent = "<div>")
        assertFalse(app.isWorkspaceApp)
        assertEquals("index.html", app.entryFile)
        assertEquals("", app.icon)
        assertTrue(app.createdAt > 0)
    }

    @Test
    fun `MiniApp workspace app flag`() {
        val app = MiniApp(id = "w", name = "W", description = "", htmlContent = "", isWorkspaceApp = true, entryFile = "main.html")
        assertTrue(app.isWorkspaceApp)
        assertEquals("main.html", app.entryFile)
    }

    // ── SkillMode ──

    @Test
    fun `SkillMode fromString parses chat`() {
        assertEquals(SkillMode.CHAT, SkillMode.fromString("chat"))
        assertEquals(SkillMode.CHAT, SkillMode.fromString("CHAT"))
    }

    @Test
    fun `SkillMode fromString defaults to CODING`() {
        assertEquals(SkillMode.CODING, SkillMode.fromString("coding"))
        assertEquals(SkillMode.CODING, SkillMode.fromString("unknown"))
        assertEquals(SkillMode.CODING, SkillMode.fromString(""))
    }

    // ── Skill ──

    @Test
    fun `Skill default bridge groups`() {
        val skill = Skill(id = "s1", name = "S", icon = "⚡", description = "test", systemPrompt = "you are")
        assertEquals(SkillMode.CODING, skill.mode)
        assertFalse(skill.isBuiltIn)
        assertEquals(2, skill.bridgeGroups.size) // STORAGE + UI_FEEDBACK
    }

    // ── ManagedSession ──

    @Test
    fun `ManagedSession defaults`() {
        val session = ManagedSession(
            type = SessionType.CHAT,
            label = "Test",
            source = SessionSource.USER_CREATED,
            modelId = "model-1"
        )
        assertEquals(SessionStatus.ACTIVE, session.status)
        assertTrue(session.id.isNotBlank())
        assertEquals(0, session.messageCount)
        assertTrue(session.metadata.isEmpty())
    }

    @Test
    fun `SessionType has expected values`() {
        assertEquals(3, SessionType.values().size)
        assertNotNull(SessionType.valueOf("CHAT"))
        assertNotNull(SessionType.valueOf("SSH_ASSIST"))
        assertNotNull(SessionType.valueOf("WORKBENCH"))
    }

    @Test
    fun `SessionStatus has expected values`() {
        assertEquals(3, SessionStatus.values().size)
        assertNotNull(SessionStatus.valueOf("ACTIVE"))
        assertNotNull(SessionStatus.valueOf("PAUSED"))
        assertNotNull(SessionStatus.valueOf("ENDED"))
    }

    @Test
    fun `SessionSource has expected values`() {
        assertEquals(5, SessionSource.values().size)
    }
}
