package com.example.link_pi.ui.workbench

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.workbench.TaskStatus
import com.example.link_pi.workbench.WorkbenchEngine
import com.example.link_pi.workbench.WorkbenchTask
import com.example.link_pi.workbench.WorkbenchTaskStorage
import com.example.link_pi.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class WorkbenchViewModel(application: Application) : AndroidViewModel(application) {

    val taskStorage = WorkbenchTaskStorage(application)
    val miniAppStorage = MiniAppStorage(application)
    val workspaceManager = WorkspaceManager(application)
    private val engine = WorkbenchEngine(application, taskStorage, miniAppStorage)

    private val _tasks = MutableStateFlow<List<WorkbenchTask>>(emptyList())
    val tasks: StateFlow<List<WorkbenchTask>> = _tasks

    /** Currently active task ID (the one being viewed in detail). */
    private val _activeTaskId = MutableStateFlow<String?>(null)
    val activeTaskId: StateFlow<String?> = _activeTaskId

    /** Agent steps for the currently running task. */
    val engineSteps: StateFlow<List<AgentStep>> = engine.steps

    init {
        reload()
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
        viewModelScope.launch(Dispatchers.IO) {
            val task = taskStorage.loadById(taskId) ?: return@launch
            taskStorage.delete(taskId)
            workspaceManager.deleteWorkspace(task.appId)
            miniAppStorage.delete(task.appId)
            _tasks.value = taskStorage.loadAll()
        }
    }

    fun createTask(
        title: String,
        userPrompt: String,
        modelId: String,
        enableThinking: Boolean
    ): WorkbenchTask {
        val id = java.util.UUID.randomUUID().toString()
        val task = WorkbenchTask(
            id = id,
            appId = id,
            title = title,
            userPrompt = userPrompt,
            modelId = modelId,
            enableThinking = enableThinking
        )
        taskStorage.save(task)
        reload()
        return task
    }

    /** Create a task and immediately start generation. */
    fun createAndRun(
        title: String,
        userPrompt: String,
        modelId: String,
        enableThinking: Boolean
    ) {
        val task = createTask(title, userPrompt, modelId, enableThinking)
        runTask(task.id)
    }

    /** Launch generation for a QUEUED or FAILED task. */
    fun runTask(taskId: String) {
        viewModelScope.launch {
            val task = taskStorage.loadById(taskId) ?: return@launch
            if (task.status != TaskStatus.QUEUED && task.status != TaskStatus.FAILED) return@launch
            _activeTaskId.value = taskId
            engine.execute(task) { updated ->
                // Refresh list on each progress update
                viewModelScope.launch(Dispatchers.IO) {
                    _tasks.value = taskStorage.loadAll()
                }
            }
            reload()
        }
    }

    /** Update a task in storage and refresh the list. */
    fun updateTask(task: WorkbenchTask) {
        taskStorage.save(task)
        reload()
    }
}
