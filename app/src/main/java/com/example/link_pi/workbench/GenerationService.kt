package com.example.link_pi.workbench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.link_pi.MainActivity
import com.example.link_pi.R
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.miniapp.MiniAppStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that **owns** task execution.
 *
 * WorkbenchViewModel delegates task launches here so that generation
 * survives ViewModel (and even Activity) destruction.  The service
 * holds WakeLock + WifiLock and runs tasks in its own CoroutineScope.
 */
class GenerationService : Service() {

    companion object {
        private const val TAG = "GenerationService"
        private const val CHANNEL_ID = "generation_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_RUN = "RUN"
        private const val ACTION_MODIFY = "MODIFY"
        private const val ACTION_CANCEL = "CANCEL"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_PROMPT = "prompt"

        /** Observable task list — shared between Service and ViewModel. */
        private val _tasks = MutableStateFlow<List<WorkbenchTask>>(emptyList())
        val tasks: StateFlow<List<WorkbenchTask>> = _tasks.asStateFlow()

        /** Observable agent steps per task. */
        private val _stepsMap = MutableStateFlow<Map<String, List<AgentStep>>>(emptyMap())
        val stepsMap: StateFlow<Map<String, List<AgentStep>>> = _stepsMap.asStateFlow()

        /** Observable historical rounds per task. */
        private val _roundsMap = MutableStateFlow<Map<String, List<WorkbenchRound>>>(emptyMap())
        val roundsMap: StateFlow<Map<String, List<WorkbenchRound>>> = _roundsMap.asStateFlow()

        /** Running task IDs. */
        private val _runningIds = MutableStateFlow<Set<String>>(emptySet())
        val runningIds: StateFlow<Set<String>> = _runningIds.asStateFlow()

        fun refreshTasks(context: Context) {
            _tasks.value = WorkbenchTaskStorage(context).loadAll()
        }

        fun clearSteps(taskId: String) {
            _stepsMap.update { it - taskId }
            _roundsMap.update { it - taskId }
        }

        fun runTask(context: Context, taskId: String) {
            val intent = Intent(context, GenerationService::class.java).apply {
                action = ACTION_RUN
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startForegroundService(intent)
        }

        fun modifyTask(context: Context, taskId: String, prompt: String) {
            val intent = Intent(context, GenerationService::class.java).apply {
                action = ACTION_MODIFY
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_PROMPT, prompt)
            }
            context.startForegroundService(intent)
        }

        fun cancelTask(context: Context, taskId: String) {
            val intent = Intent(context, GenerationService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, taskId)
            }
            // Use startForegroundService so the service can handle it even if not yet running
            try { context.startForegroundService(intent) } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationService::class.java))
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val taskJobs = ConcurrentHashMap<String, Job>()
    private val runningTaskIds = ConcurrentHashMap.newKeySet<String>()

    private lateinit var engine: WorkbenchEngine
    private lateinit var taskStorage: WorkbenchTaskStorage

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        taskStorage = WorkbenchTaskStorage(this)
        engine = WorkbenchEngine(this, taskStorage, MiniAppStorage(this))
        createNotificationChannel()
        val notification = buildNotification("准备就绪")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireLocks()
        requestBatteryOptimizationExemption()
        _tasks.value = taskStorage.loadAll()
        // Forward engine's per-task steps to the static stepsMap for ViewModel observation
        serviceScope.launch {
            engine.stepsMap.collect { _stepsMap.value = it }
        }
        serviceScope.launch {
            engine.roundsMap.collect { _roundsMap.value = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        when (intent?.action) {
            ACTION_RUN -> if (taskId != null) doRun(taskId)
            ACTION_MODIFY -> {
                val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
                if (taskId != null) doModify(taskId, prompt)
            }
            ACTION_CANCEL -> if (taskId != null) doCancel(taskId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Task Execution ──

    private fun doRun(taskId: String) {
        if (taskJobs.containsKey(taskId)) return
        val task = taskStorage.loadById(taskId) ?: return
        if (task.status != TaskStatus.QUEUED && task.status != TaskStatus.FAILED) return

        val reset = task.copy(
            status = TaskStatus.QUEUED, error = null, progress = 0,
            currentStep = "", updatedAt = System.currentTimeMillis()
        )
        taskStorage.save(reset)
        broadcastUpdate(reset)
        markRunning(taskId, true)
        updateNotification()

        val job = serviceScope.launch {
            try {
                val result = engine.execute(reset) { updated ->
                    broadcastUpdate(updated)
                    updateNotification(updated.currentStep)
                }
                // Archive this round's steps as history
                engine.archiveCurrentRound(taskId, result.appId, result.userPrompt)
            } catch (e: Exception) {
                Log.e(TAG, "Task $taskId failed", e)
                val failed = taskStorage.loadById(taskId)
                if (failed != null && failed.status != TaskStatus.COMPLETED && failed.status != TaskStatus.FAILED) {
                    val updated = failed.copy(status = TaskStatus.FAILED, error = e.message, updatedAt = System.currentTimeMillis())
                    taskStorage.save(updated)
                    broadcastUpdate(updated)
                }
            } finally {
                markRunning(taskId, false)
                taskJobs.remove(taskId)
                updateNotification()
                _tasks.value = taskStorage.loadAll()
                stopIfIdle()
            }
        }
        taskJobs[taskId] = job
    }

    private fun doModify(taskId: String, prompt: String) {
        if (taskJobs.containsKey(taskId)) return
        val existing = taskStorage.loadById(taskId) ?: return
        val updated = existing.copy(
            userPrompt = prompt,
            title = "修改：${prompt.take(20)}",
            status = TaskStatus.QUEUED,
            error = null,
            updatedAt = System.currentTimeMillis()
        )
        taskStorage.save(updated)
        broadcastUpdate(updated)
        markRunning(taskId, true)
        updateNotification()

        val job = serviceScope.launch {
            try {
                val result = engine.execute(updated) { step ->
                    broadcastUpdate(step)
                    updateNotification(step.currentStep)
                }
                // Archive this round's steps as history
                engine.archiveCurrentRound(taskId, result.appId, result.userPrompt)
            } catch (e: Exception) {
                Log.e(TAG, "Task $taskId modify failed", e)
                val failed = taskStorage.loadById(taskId)
                if (failed != null && failed.status != TaskStatus.COMPLETED && failed.status != TaskStatus.FAILED) {
                    val updated = failed.copy(status = TaskStatus.FAILED, error = e.message, updatedAt = System.currentTimeMillis())
                    taskStorage.save(updated)
                    broadcastUpdate(updated)
                }
            } finally {
                markRunning(taskId, false)
                taskJobs.remove(taskId)
                updateNotification()
                _tasks.value = taskStorage.loadAll()
                stopIfIdle()
            }
        }
        taskJobs[taskId] = job
    }

    private fun doCancel(taskId: String) {
        taskJobs.remove(taskId)?.cancel()
        markRunning(taskId, false)
        // Update task status so UI reflects the cancellation
        val task = taskStorage.loadById(taskId)
        if (task != null && task.status != TaskStatus.COMPLETED && task.status != TaskStatus.FAILED) {
            val cancelled = task.copy(status = TaskStatus.FAILED, error = "已取消", updatedAt = System.currentTimeMillis())
            taskStorage.save(cancelled)
            broadcastUpdate(cancelled)
        }
        stopIfIdle()
    }

    // ── Helpers ──

    private fun markRunning(taskId: String, running: Boolean) {
        if (running) runningTaskIds.add(taskId) else runningTaskIds.remove(taskId)
        _runningIds.value = runningTaskIds.toSet()
    }

    private fun broadcastUpdate(task: WorkbenchTask) {
        _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
            .let { list -> if (list.none { it.id == task.id }) list + task else list }
    }

    private fun stopIfIdle() {
        if (runningTaskIds.isEmpty()) {
            stopSelf()
        }
    }

    // ── WakeLock / WifiLock ──

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinkPi::Generation").apply {
            acquire(60 * 60 * 1000L)  // 1 hour timeout as safety net; released in onDestroy
        }
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LinkPi::Generation").apply {
            setReferenceCounted(false) // prevent double-release crashes
            acquire()
        }
    }

    /** Request battery optimization exemption so Doze mode doesn't kill network. */
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                @Suppress("BatteryLife")
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot request battery optimization exemption", e)
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "应用生成",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "应用生成任务正在运行" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val count = runningTaskIds.size
        val title = if (count > 0) "正在生成 ($count)" else "LinkPi"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(step: String? = null) {
        val count = runningTaskIds.size
        val text = step?.take(50) ?: if (count > 0) "正在生成 $count 个任务…" else "准备就绪"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
