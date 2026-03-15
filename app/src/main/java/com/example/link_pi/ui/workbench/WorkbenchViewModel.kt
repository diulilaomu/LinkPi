package com.example.link_pi.ui.workbench

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.network.AiConfig
import com.example.link_pi.network.ModelConfig
import com.example.link_pi.workbench.TaskStatus
import com.example.link_pi.workbench.GenerationService
import com.example.link_pi.workbench.WorkbenchTask
import com.example.link_pi.workbench.WorkbenchTaskStorage
import com.example.link_pi.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorkbenchViewModel(application: Application) : AndroidViewModel(application) {

    private val taskStorage = WorkbenchTaskStorage(application)
    private val miniAppStorage = MiniAppStorage(application)
    private val workspaceManager = WorkspaceManager(application)
    private val aiConfig = AiConfig(application)

    /** Tasks, steps, and running state are all observed from the service's static flows. */
    val tasks: StateFlow<List<WorkbenchTask>> = GenerationService.tasks
    val engineStepsMap: StateFlow<Map<String, List<AgentStep>>> = GenerationService.stepsMap

    private val _activeTaskId = MutableStateFlow<String?>(null)
    val activeTaskId: StateFlow<String?> = _activeTaskId

    // ── Model / thinking state ──
    private val _models = MutableStateFlow(aiConfig.getModels())
    val models: StateFlow<List<ModelConfig>> = _models.asStateFlow()

    private val _activeModelId = MutableStateFlow(aiConfig.activeModelId)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _deepThinking = MutableStateFlow(aiConfig.activeModel.enableThinking)
    val deepThinking: StateFlow<Boolean> = _deepThinking.asStateFlow()

    init {
        GenerationService.refreshTasks(application)
        viewModelScope.launch(Dispatchers.IO) { workspaceManager.cleanupStaleWorkspaces() }
    }

    fun reload() {
        GenerationService.refreshTasks(getApplication())
    }

    fun setActiveTask(taskId: String?) {
        _activeTaskId.value = taskId
    }

    fun deleteTask(taskId: String) {
        val ctx = getApplication<Application>()
        GenerationService.cancelTask(ctx, taskId)
        viewModelScope.launch(Dispatchers.IO) {
            val task = taskStorage.loadById(taskId) ?: return@launch
            taskStorage.delete(taskId)
            workspaceManager.deleteWorkspace(task.appId)
            miniAppStorage.delete(task.appId)
            GenerationService.clearSteps(taskId)
            GenerationService.refreshTasks(ctx)
        }
    }

    /** Create a task and immediately start generation. Returns the task ID. */
    fun createAndRun(
        title: String,
        userPrompt: String,
        modelId: String,
        enableThinking: Boolean,
        appId: String? = null
    ): String {
        val ctx = getApplication<Application>()
        val id = java.util.UUID.randomUUID().toString()
        val task = WorkbenchTask(
            id = id,
            appId = appId ?: id,
            title = title,
            userPrompt = userPrompt,
            modelId = modelId,
            enableThinking = enableThinking
        )
        viewModelScope.launch(Dispatchers.IO) {
            taskStorage.save(task)
            GenerationService.refreshTasks(ctx)
            _activeTaskId.value = id
            GenerationService.runTask(ctx, id)
        }
        return id
    }

    /** Launch generation for a QUEUED or FAILED task. */
    fun runTask(taskId: String) {
        val ctx = getApplication<Application>()
        _activeTaskId.value = taskId
        GenerationService.runTask(ctx, taskId)
    }

    /** Modify an existing task: update its prompt and re-run via the service. */
    fun modifyApp(taskId: String, userPrompt: String) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val existing = taskStorage.loadById(taskId) ?: return@launch
            val updated = existing.copy(
                userPrompt = userPrompt,
                title = "修改：${userPrompt.take(20)}",
                status = TaskStatus.QUEUED,
                error = null,
                modelId = _activeModelId.value ?: "",
                enableThinking = _deepThinking.value
            )
            taskStorage.save(updated)
            GenerationService.refreshTasks(ctx)
            _activeTaskId.value = taskId
            GenerationService.modifyTask(ctx, taskId, userPrompt)
        }
    }

    /** Load a MiniApp by ID for running. */
    fun loadMiniApp(appId: String) = miniAppStorage.loadById(appId)

    /** Update MiniApp name and icon. */
    fun updateMiniAppInfo(appId: String, name: String, iconPath: String) {
        val existing = miniAppStorage.loadById(appId) ?: return
        miniAppStorage.save(existing.copy(name = name, icon = iconPath))
    }

    /** Get workspace files for a task. */
    fun getWorkspaceFiles(appId: String): List<String> =
        try { workspaceManager.getAllFiles(appId) } catch (_: Exception) { emptyList() }

    /** Read raw file content (no line numbers) for the file viewer/editor. */
    fun readFileContent(appId: String, relativePath: String): String? =
        try {
            val dir = java.io.File(workspaceManager.getWorkspacePath(appId))
            val file = java.io.File(dir, relativePath).canonicalFile
            if (file.canonicalPath.startsWith(dir.canonicalPath) && file.isFile)
                file.readText()
            else null
        } catch (_: Exception) { null }

    /** Write raw file content from the editor. */
    fun writeFileContent(appId: String, relativePath: String, content: String): Boolean =
        try {
            workspaceManager.writeFile(appId, relativePath, content)
                .startsWith("Written")
        } catch (_: Exception) { false }

    /** Read APP_INFO.json content for a task. */
    fun getAppInfo(appId: String): String =
        try {
            java.io.File(workspaceManager.getWorkspacePath(appId), "APP_INFO.json").readText()
        } catch (_: Exception) { "" }

    fun refreshModels() {
        aiConfig.reloadModels()
        _models.value = aiConfig.getModels()
        _activeModelId.value = aiConfig.activeModelId
        _deepThinking.value = aiConfig.activeModel.enableThinking
    }

    fun switchModel(modelId: String) {
        aiConfig.activeModelId = modelId
        _activeModelId.value = modelId
        _deepThinking.value = aiConfig.activeModel.enableThinking
    }

    fun toggleDeepThinking() {
        val newVal = !_deepThinking.value
        _deepThinking.value = newVal
        aiConfig.updateModel(aiConfig.activeModel.copy(enableThinking = newVal))
    }
}
