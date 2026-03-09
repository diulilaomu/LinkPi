package com.example.link_pi.ui.chat

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.link_pi.agent.AgentOrchestrator
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.agent.MemoryExtractor
import com.example.link_pi.agent.ToolExecutor
import com.example.link_pi.data.ConversationStorage
import com.example.link_pi.data.model.Attachment
import com.example.link_pi.data.model.ChatMessage
import com.example.link_pi.data.model.Conversation
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.data.model.Skill
import com.example.link_pi.miniapp.MiniAppParser
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.network.AiConfig
import com.example.link_pi.network.AiService
import com.example.link_pi.network.ModelConfig
import com.example.link_pi.skill.BuiltInSkills
import com.example.link_pi.skill.IntentClassifier
import com.example.link_pi.skill.SkillStorage
import com.example.link_pi.skill.UserIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val aiConfig = AiConfig(application)
    private val aiService = AiService(aiConfig)
    val miniAppStorage = MiniAppStorage(application)
    val skillStorage = SkillStorage(application)
    private val toolExecutor = ToolExecutor(application, miniAppStorage)
    private val orchestrator = AgentOrchestrator(aiService, toolExecutor)
    private val memoryExtractor = MemoryExtractor(aiService, toolExecutor.memoryStorage)
    private val conversationStorage = ConversationStorage(application)

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

    private val _deepThinking = MutableStateFlow(aiConfig.activeModel.enableThinking)
    val deepThinking: StateFlow<Boolean> = _deepThinking.asStateFlow()

    private val _models = MutableStateFlow(aiConfig.getModels())
    val models: StateFlow<List<ModelConfig>> = _models.asStateFlow()

    private val _activeModelId = MutableStateFlow(aiConfig.activeModelId)
    val activeModelId: StateFlow<String> = _activeModelId.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<Attachment>> = _pendingAttachments.asStateFlow()

    // ── 会话管理 ──
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    var currentMiniApp: MiniApp? = null
        private set

    /** Pending workbench request — shown as a confirmation card in chat. */
    data class WorkbenchRequest(
        val userPrompt: String,
        val title: String,
        val modelId: String,
        val enableThinking: Boolean
    )

    private val _pendingWorkbench = MutableStateFlow<WorkbenchRequest?>(null)
    val pendingWorkbench: StateFlow<WorkbenchRequest?> = _pendingWorkbench.asStateFlow()

    init {
        // 启动时加载会话列表并恢复最近会话
        viewModelScope.launch {
            val convs = withContext(Dispatchers.IO) { conversationStorage.loadAllConversations() }
            _conversations.value = convs
            val latest = convs.firstOrNull()
            if (latest != null) {
                _activeConversationId.value = latest.id
                val msgs = withContext(Dispatchers.IO) { conversationStorage.loadMessages(latest.id) }
                _messages.value = msgs
            }
        }
    }

    fun setActiveSkill(skill: Skill) {
        _activeSkill.value = skill
    }

    // ── 会话操作 ──

    /** 新建会话——保存当前会话后切到空白会话 */
    fun newConversation() {
        if (_isLoading.value) return
        saveCurrentConversation()
        val newId = UUID.randomUUID().toString()
        _activeConversationId.value = newId
        _messages.value = emptyList()
        _error.value = null
        _agentSteps.value = emptyList()
        _pendingAttachments.value = emptyList()
        currentMiniApp = null
    }

    /** 切换到历史会话 */
    fun switchConversation(conversationId: String) {
        if (_isLoading.value) return
        if (conversationId == _activeConversationId.value) return
        saveCurrentConversation()
        _activeConversationId.value = conversationId
        _error.value = null
        _agentSteps.value = emptyList()
        _pendingAttachments.value = emptyList()
        currentMiniApp = null
        viewModelScope.launch {
            val msgs = withContext(Dispatchers.IO) { conversationStorage.loadMessages(conversationId) }
            _messages.value = msgs
        }
    }

    /** 删除会话 */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { conversationStorage.deleteConversation(conversationId) }
            _conversations.update { it.filter { c -> c.id != conversationId } }
            // 如果删除的是当前会话，新建一个空的
            if (conversationId == _activeConversationId.value) {
                val newId = UUID.randomUUID().toString()
                _activeConversationId.value = newId
                _messages.value = emptyList()
                _error.value = null
                currentMiniApp = null
            }
        }
    }

    /** 保存当前会话到磁盘 */
    private fun saveCurrentConversation() {
        val convId = _activeConversationId.value ?: return
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        val title = generateTitle(msgs)
        val conv = Conversation(
            id = convId,
            title = title,
            createdAt = msgs.firstOrNull()?.timestamp ?: System.currentTimeMillis(),
            updatedAt = msgs.lastOrNull()?.timestamp ?: System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            conversationStorage.saveConversation(conv)
            conversationStorage.saveMessages(convId, msgs)
        }
        // 更新内存中的会话列表
        _conversations.update { list ->
            val filtered = list.filter { it.id != convId }
            listOf(conv) + filtered
        }
    }

    /** 从首条用户消息提取标题 */
    private fun generateTitle(msgs: List<ChatMessage>): String {
        val firstUser = msgs.firstOrNull { it.role == "user" }?.content ?: "新对话"
        return firstUser.take(30).replace("\n", " ").trim().ifEmpty { "新对话" }
    }

    fun toggleDeepThinking() {
        _deepThinking.value = !_deepThinking.value
    }

    fun switchModel(id: String) {
        aiConfig.setActive(id)
        _activeModelId.value = id
        _deepThinking.value = aiConfig.activeModel.enableThinking
    }

    fun refreshModels() {
        aiConfig.reloadModels()
        _models.value = aiConfig.getModels().toList()
        _activeModelId.value = aiConfig.activeModelId
        _deepThinking.value = aiConfig.activeModel.enableThinking
    }

    fun addAttachment(uri: Uri) {
        val ctx = getApplication<Application>()
        val cr = ctx.contentResolver
        val mimeType = cr.getType(uri) ?: return
        // Resolve display name
        val name = cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: uri.lastPathSegment ?: "file"
        // Validate extension
        val ext = name.substringAfterLast('.', "").lowercase()
        val allowedText = setOf("md", "txt")
        val allowedImage = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        if (ext !in allowedText && ext !in allowedImage && !mimeType.startsWith("image/")) return
        val attachment = if (mimeType.startsWith("image/") || ext in allowedImage) {
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (bytes.size > 4 * 1024 * 1024) return  // 4MB limit
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Attachment(name, mimeType, base64Data = "data:$mimeType;base64,$b64")
        } else {
            val text = cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            if (text.length > 100_000) return  // 100KB text limit
            Attachment(name, mimeType, textContent = text)
        }
        _pendingAttachments.update { it + attachment }
    }

    fun removeAttachment(index: Int) {
        _pendingAttachments.update { list ->
            list.filterIndexed { i, _ -> i != index }
        }
    }

    fun sendMessage(userInput: String) {
        if (userInput.isBlank() && _pendingAttachments.value.isEmpty()) return
        if (_isLoading.value) return

        // Ensure a conversation ID exists
        if (_activeConversationId.value == null) {
            _activeConversationId.value = UUID.randomUUID().toString()
        }

        val attachments = _pendingAttachments.value.toList()
        _pendingAttachments.value = emptyList()

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = userInput,
            attachments = attachments
        )
        _messages.update { it + userMessage }
        _error.value = null
        _agentSteps.value = emptyList()

        if (!aiConfig.isConfigured) {
            _error.value = "请先在设置中配置 API 地址和密钥"
            return
        }

        // Quick local intent check — redirect CREATE_APP to workbench confirmation
        val hasActiveWorkspace = toolExecutor.latestAppHint != null || currentMiniApp != null
        val localIntent = IntentClassifier.classifyLocal(userInput, hasActiveWorkspace)
        if (localIntent == UserIntent.CREATE_APP) {
            val title = userInput.take(30).replace("\n", " ").trim()
            _pendingWorkbench.value = WorkbenchRequest(
                userPrompt = userInput,
                title = title,
                modelId = aiConfig.activeModelId,
                enableThinking = _deepThinking.value
            )
            // Add a system message to indicate the redirect
            val hintMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = "检测到应用创建请求，已准备工作台任务。请确认后开始生成。"
            )
            _messages.update { it + hintMessage }
            saveCurrentConversation()
            return
        }

        launchOrchestrator()
    }

    /** Dismiss the workbench confirmation card without acting. */
    fun dismissWorkbench() {
        _pendingWorkbench.value = null
    }

    /** Confirm the workbench request — returns the WorkbenchRequest for the caller to act on. */
    fun confirmWorkbench(): WorkbenchRequest? {
        val req = _pendingWorkbench.value
        _pendingWorkbench.value = null
        return req
    }

    /** Dismiss workbench card and run the normal orchestrator (called from UI). */
    fun launchOrchestratorPublic() = launchOrchestrator()

    private fun launchOrchestrator() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val apiMessages = buildApiMessages()
                val collectedSteps = mutableListOf<AgentStep>()

                // Pass latest app hint to ToolExecutor for auto-resolve
                val lastApp = _messages.value.lastOrNull { it.miniApp != null }?.miniApp
                toolExecutor.latestAppHint = lastApp?.id ?: currentMiniApp?.id

                val syncStepCallback: (AgentStep) -> Unit = { step ->
                    synchronized(collectedSteps) {
                        val last = collectedSteps.lastOrNull()
                        if (last != null && last.description == step.description) {
                            collectedSteps[collectedSteps.size - 1] = step
                        } else {
                            collectedSteps.add(step)
                        }
                    }
                    _agentSteps.value = synchronized(collectedSteps) { collectedSteps.toList() }
                }

                val injectionSkills = withContext(Dispatchers.IO) {
                    skillStorage.loadAll().filter { it.intentInjections.isNotEmpty() && !it.isBuiltIn }
                }

                val result = orchestrator.run(apiMessages, _activeSkill.value, _deepThinking.value,
                    injectionSkills = injectionSkills,
                    onStep = { step ->
                        synchronized(collectedSteps) { collectedSteps.add(step) }
                        _agentSteps.value = synchronized(collectedSteps) { collectedSteps.toList() }
                    },
                    onStepSync = syncStepCallback
                )

                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    content = result.finalResponse,
                    miniApp = result.miniApp,
                    agentSteps = collectedSteps.toList(),
                    thinkingContent = result.thinkingContent
                )
                _messages.update { it + assistantMessage }
                _agentSteps.value = emptyList()

                saveCurrentConversation()

                val userInput = _messages.value.lastOrNull { it.role == "user" }?.content ?: ""
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
        _messages.update { it.filter { msg -> msg.id != messageId } }
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
        _messages.update { it.filterIndexed { idx, _ -> idx != lastAssistantIdx } }
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

                val syncCb: (AgentStep) -> Unit = { step ->
                    synchronized(collectedSteps) {
                        val last = collectedSteps.lastOrNull()
                        if (last != null && last.description == step.description) {
                            collectedSteps[collectedSteps.size - 1] = step
                        } else {
                            collectedSteps.add(step)
                        }
                    }
                    _agentSteps.value = synchronized(collectedSteps) { collectedSteps.toList() }
                }
                val regenInjectionSkills = withContext(Dispatchers.IO) {
                    skillStorage.loadAll().filter { it.intentInjections.isNotEmpty() && !it.isBuiltIn }
                }
                val result = orchestrator.run(apiMessages, _activeSkill.value, _deepThinking.value,
                    injectionSkills = regenInjectionSkills,
                    onStep = { step ->
                        synchronized(collectedSteps) { collectedSteps.add(step) }
                        _agentSteps.value = synchronized(collectedSteps) { collectedSteps.toList() }
                    },
                    onStepSync = syncCb
                )

                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    content = result.finalResponse,
                    miniApp = result.miniApp,
                    agentSteps = synchronized(collectedSteps) { collectedSteps.toList() },
                    thinkingContent = result.thinkingContent
                )
                _messages.update { it + assistantMessage }
                _agentSteps.value = emptyList()

                saveCurrentConversation()

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
            } else if (msg.role == "user" && msg.attachments.isNotEmpty()) {
                // Embed text file contents into the message
                val textFiles = msg.attachments.filter { it.textContent != null }
                val prefix = if (textFiles.isNotEmpty()) {
                    textFiles.joinToString("\n\n") { att ->
                        "📎 ${att.name}:\n```\n${att.textContent}\n```"
                    } + "\n\n"
                } else ""
                prefix + msg.content
            } else {
                msg.content
            }
            val entry = mutableMapOf("role" to msg.role, "content" to content)
            // Attach image data URLs for multimodal support
            if (msg.role == "user" && msg.attachments.any { it.base64Data != null }) {
                val imageUrls = msg.attachments.mapNotNull { it.base64Data }
                entry["_images"] = imageUrls.joinToString("|||")
            }
            apiMessages.add(entry)
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
            parts.add("最近应用: ${lastApp.name}|${lastApp.id}")
        }

        // Currently running app
        currentMiniApp?.let { app ->
            if (app.id != lastApp?.id) parts.add("运行中: ${app.name}|${app.id}")
        }

        // Saved apps — just names and IDs, compact
        val savedApps = miniAppStorage.loadAll()
        if (savedApps.isNotEmpty()) {
            val list = savedApps.joinToString("; ") { "${it.name}|${it.id}" }
            parts.add("已保存(${ savedApps.size}): $list")
        }

        if (parts.isEmpty()) return ""
        return "[env] ${parts.joinToString(" / ")} / 模糊指代时优先指向最近应用,直接open_app_workspace(app_id=id)"
    }
}
