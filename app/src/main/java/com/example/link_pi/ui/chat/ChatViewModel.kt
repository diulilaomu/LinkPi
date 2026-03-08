package com.example.link_pi.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.link_pi.agent.AgentOrchestrator
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.agent.MemoryExtractor
import com.example.link_pi.agent.ToolExecutor
import com.example.link_pi.data.model.ChatMessage
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.data.model.Skill
import com.example.link_pi.miniapp.MiniAppParser
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.network.AiConfig
import com.example.link_pi.network.AiService
import com.example.link_pi.skill.BuiltInSkills
import com.example.link_pi.skill.SkillStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val aiConfig = AiConfig(application)
    private val aiService = AiService(aiConfig)
    val miniAppStorage = MiniAppStorage(application)
    val skillStorage = SkillStorage(application)
    private val toolExecutor = ToolExecutor(application, miniAppStorage)
    private val orchestrator = AgentOrchestrator(aiService, toolExecutor)
    private val memoryExtractor = MemoryExtractor(aiService, toolExecutor.memoryStorage)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _agentSteps = MutableStateFlow<List<AgentStep>>(emptyList())
    val agentSteps: StateFlow<List<AgentStep>> = _agentSteps.asStateFlow()

    private val _activeSkill = MutableStateFlow(BuiltInSkills.DEFAULT)
    val activeSkill: StateFlow<Skill> = _activeSkill.asStateFlow()

    private val _deepThinking = MutableStateFlow(false)
    val deepThinking: StateFlow<Boolean> = _deepThinking.asStateFlow()

    var currentMiniApp: MiniApp? = null
        private set

    fun setActiveSkill(skill: Skill) {
        _activeSkill.value = skill
    }

    fun toggleDeepThinking() {
        _deepThinking.value = !_deepThinking.value
    }

    fun sendMessage(userInput: String) {
        if (userInput.isBlank()) return
        if (_isLoading.value) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = userInput
        )
        _messages.value = _messages.value + userMessage
        _error.value = null
        _agentSteps.value = emptyList()

        if (!aiConfig.isConfigured) {
            _error.value = "请先在设置中配置 API 地址和密钥"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val apiMessages = buildApiMessages()
                val collectedSteps = mutableListOf<AgentStep>()

                // Pass latest app hint to ToolExecutor for auto-resolve
                val lastApp = _messages.value.lastOrNull { it.miniApp != null }?.miniApp
                toolExecutor.latestAppHint = lastApp?.id ?: currentMiniApp?.id

                val result = orchestrator.run(apiMessages, _activeSkill.value, _deepThinking.value) { step ->
                    collectedSteps.add(step)
                    _agentSteps.value = collectedSteps.toList()
                }

                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    content = result.finalResponse,
                    miniApp = result.miniApp,
                    agentSteps = collectedSteps.toList(),
                    thinkingContent = result.thinkingContent
                )
                _messages.value = _messages.value + assistantMessage
                _agentSteps.value = emptyList()

                // Async memory extraction — don't block the UI
                viewModelScope.launch {
                    try {
                        memoryExtractor.extract(userInput, result.finalResponse)
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                _error.value = "请求失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setCurrentApp(app: MiniApp) {
        currentMiniApp = app
    }

    fun saveMiniApp(app: MiniApp) {
        miniAppStorage.save(app)
    }

    fun deleteMessage(messageId: String) {
        _messages.value = _messages.value.filter { it.id != messageId }
    }

    fun regenerateLastResponse() {
        if (_isLoading.value) return
        val msgs = _messages.value
        // Find the last user message (skip trailing assistant message)
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val lastUserIdx = msgs.take(lastAssistantIdx).indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) return

        val userInput = msgs[lastUserIdx].content
        // Remove the last assistant message
        _messages.value = msgs.filterIndexed { idx, _ -> idx != lastAssistantIdx }
        // Re-send
        _error.value = null
        _agentSteps.value = emptyList()

        if (!aiConfig.isConfigured) {
            _error.value = "请先在设置中配置 API 地址和密钥"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val apiMessages = buildApiMessages()
                val collectedSteps = mutableListOf<AgentStep>()
                val lastApp = _messages.value.lastOrNull { it.miniApp != null }?.miniApp
                toolExecutor.latestAppHint = lastApp?.id ?: currentMiniApp?.id

                val result = orchestrator.run(apiMessages, _activeSkill.value, _deepThinking.value) { step ->
                    collectedSteps.add(step)
                    _agentSteps.value = collectedSteps.toList()
                }

                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    content = result.finalResponse,
                    miniApp = result.miniApp,
                    agentSteps = collectedSteps.toList(),
                    thinkingContent = result.thinkingContent
                )
                _messages.value = _messages.value + assistantMessage
                _agentSteps.value = emptyList()

                viewModelScope.launch {
                    try { memoryExtractor.extract(userInput, result.finalResponse) } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                _error.value = "请求失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun buildApiMessages(): List<Map<String, String>> {
        val apiMessages = mutableListOf<Map<String, String>>()
        apiMessages.add(mapOf("role" to "system", "content" to _activeSkill.value.systemPrompt))

        // Inject context awareness — help AI understand what the user is likely referring to
        val contextHint = buildContextHint()
        if (contextHint.isNotBlank()) {
            apiMessages.add(mapOf("role" to "system", "content" to contextHint))
        }

        // Keep only recent messages to avoid token overflow
        val recentMessages = _messages.value.takeLast(20)

        for (msg in recentMessages) {
            val content = if (msg.role == "assistant" && msg.miniApp != null) {
                val textPart = MiniAppParser.getDisplayText(msg.content)
                textPart.ifEmpty { "[已生成小应用: ${msg.miniApp.name}]" }
            } else {
                msg.content
            }
            apiMessages.add(mapOf("role" to msg.role, "content" to content))
        }

        return apiMessages
    }

    /**
     * Build a compact context hint — just key facts, no fluff.
     */
    private fun buildContextHint(): String {
        val parts = mutableListOf<String>()

        // Last app in this conversation — highest priority reference
        val lastAppMsg = _messages.value.lastOrNull { it.miniApp != null }
        val lastApp = lastAppMsg?.miniApp
        if (lastApp != null) {
            parts.add("latest_app: ${lastApp.name}|${lastApp.id}")
        }

        // Currently running app
        currentMiniApp?.let { app ->
            if (app.id != lastApp?.id) parts.add("running_app: ${app.name}|${app.id}")
        }

        // Saved apps — just names and IDs, compact
        val savedApps = miniAppStorage.loadAll()
        if (savedApps.isNotEmpty()) {
            val list = savedApps.joinToString("; ") { "${it.name}|${it.id}" }
            parts.add("saved(${ savedApps.size}): $list")
        }

        if (parts.isEmpty()) return ""
        return "[env] ${parts.joinToString(" / ")} / 模糊指代时优先指向latest_app,直接open_app_workspace(app_id=id)"
    }
}
