package com.example.link_pi.ui.workbench

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.network.AiConfig
import com.example.link_pi.network.ModelConfig
import com.example.link_pi.workbench.TaskStatus
import com.example.link_pi.workbench.WorkbenchEngine
import com.example.link_pi.workbench.WorkbenchTask
import com.example.link_pi.workbench.WorkbenchTaskStorage
import com.example.link_pi.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections

class WorkbenchViewModel(application: Application) : AndroidViewModel(application) {

    private val taskStorage = WorkbenchTaskStorage(application)
    private val miniAppStorage = MiniAppStorage(application)
    private val workspaceManager = WorkspaceManager(application)
    private val engine = WorkbenchEngine(application, taskStorage, miniAppStorage)
    private val aiConfig = AiConfig(application)

    private val _tasks = MutableStateFlow<List<WorkbenchTask>>(emptyList())
    val tasks: StateFlow<List<WorkbenchTask>> = _tasks

    private val _activeTaskId = MutableStateFlow<String?>(null)
    val activeTaskId: StateFlow<String?> = _activeTaskId

    val engineStepsMap: StateFlow<Map<String, List<AgentStep>>> = engine.stepsMap

    private val _runningTaskIds = Collections.synchronizedSet(mutableSetOf<String>())

    // ── Model / thinking state ──
    private val _models = MutableStateFlow(aiConfig.getModels())
    val models: StateFlow<List<ModelConfig>> = _models.asStateFlow()

    private val _activeModelId = MutableStateFlow(aiConfig.activeModelId)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _deepThinking = MutableStateFlow(aiConfig.activeModel.enableThinking)
    val deepThinking: StateFlow<Boolean> = _deepThinking.asStateFlow()

    init {
        reload()
        viewModelScope.launch(Dispatchers.IO) { workspaceManager.cleanupStaleWorkspaces() }
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            _tasks.value = taskStorage.loadAll()
        }
    }

    fun setActiveTask(taskId: String?) {
        _activeTaskId.value = taskId
    }

    fun deleteTask(taskId: String) {
        if (taskId in _runningTaskIds) return  // don't delete a running task
        viewModelScope.launch(Dispatchers.IO) {
            val task = taskStorage.loadById(taskId) ?: return@launch
            taskStorage.delete(taskId)
            workspaceManager.deleteWorkspace(task.appId)
            miniAppStorage.delete(task.appId)
            engine.clearSteps(taskId)
            _tasks.value = taskStorage.loadAll()
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
            _tasks.value = taskStorage.loadAll()
            _runningTaskIds.add(id)
            _activeTaskId.value = id
            try {
                engine.execute(task) { updated ->
                    _tasks.value = _tasks.value.map { if (it.id == updated.id) updated else it }
                }
            } finally {
                _runningTaskIds.remove(id)
            }
            reload()
        }
        return id
    }

    /** Launch generation for a QUEUED or FAILED task. */
    fun runTask(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val task = taskStorage.loadById(taskId) ?: return@launch
            if (task.status != TaskStatus.QUEUED && task.status != TaskStatus.FAILED) return@launch

            // Reset task state so the UI immediately reflects the retry
            val reset = task.copy(
                status = TaskStatus.QUEUED,
                error = null,
                progress = 0,
                currentStep = "",
                updatedAt = System.currentTimeMillis()
            )
            taskStorage.save(reset)
            _tasks.value = _tasks.value.map { if (it.id == taskId) reset else it }

            _runningTaskIds.add(taskId)
            _activeTaskId.value = taskId
            try {
                engine.execute(reset) { updated ->
                    _tasks.value = _tasks.value.map { if (it.id == updated.id) updated else it }
                }
            } finally {
                _runningTaskIds.remove(taskId)
            }
            reload()
        }
    }

    /** Modify an existing task: update its prompt and re-run the engine. */
    fun modifyApp(taskId: String, userPrompt: String) {
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
            _tasks.value = _tasks.value.map { if (it.id == taskId) updated else it }

            _runningTaskIds.add(taskId)
            _activeTaskId.value = taskId
            try {
                engine.execute(updated) { step ->
                    _tasks.value = _tasks.value.map { if (it.id == step.id) step else it }
                }
            } finally {
                _runningTaskIds.remove(taskId)
            }
            reload()
        }
    }

    /** Load a MiniApp by ID for running. */
    fun loadMiniApp(appId: String) = miniAppStorage.loadById(appId)

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
