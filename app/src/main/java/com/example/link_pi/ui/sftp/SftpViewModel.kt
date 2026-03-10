package com.example.link_pi.ui.sftp

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.link_pi.agent.SshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RemoteFile(
    val name: String,
    val size: Long,
    val isDir: Boolean,
    val permissions: String,
    val mtime: Long
) {
    val displaySize: String get() = when {
        isDir -> ""
        size < 1024 -> "${size}B"
        size < 1024 * 1024 -> "${size / 1024}KB"
        else -> "${"%.1f".format(size / 1024.0 / 1024.0)}MB"
    }

    val displayTime: String get() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        .format(Date(mtime * 1000))
}

data class TransferRecord(
    val id: String = "t_${System.currentTimeMillis().toString(36)}_${(0..999).random()}",
    val fileName: String,
    val remotePath: String,
    val localPath: String,
    val size: Long = 0,
    val isUpload: Boolean,
    val status: TransferStatus = TransferStatus.RUNNING,
    val error: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class TransferStatus { RUNNING, SUCCESS, FAILED }

data class LogEntry(
    val id: Long = System.nanoTime(),
    val time: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
    val message: String,
    val isError: Boolean = false
)

/** Batch operation type when in multi-select mode */
enum class BatchOp { COPY, MOVE, DOWNLOAD }

class SftpViewModel(application: Application) : AndroidViewModel(application) {

    private val sshManager = SshManager.getInstance(application)
    private var sessionId: String? = null

    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _downloads = MutableStateFlow<List<TransferRecord>>(emptyList())
    val downloads: StateFlow<List<TransferRecord>> = _downloads.asStateFlow()

    private val _uploads = MutableStateFlow<List<TransferRecord>>(emptyList())
    val uploads: StateFlow<List<TransferRecord>> = _uploads.asStateFlow()

    // File editor state
    private val _editingFile = MutableStateFlow<String?>(null)
    val editingFile: StateFlow<String?> = _editingFile.asStateFlow()

    private val _editingContent = MutableStateFlow("")
    val editingContent: StateFlow<String> = _editingContent.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // ── Log state ──
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // ── Multi-select state ──
    private val _isMultiSelect = MutableStateFlow(false)
    val isMultiSelect: StateFlow<Boolean> = _isMultiSelect.asStateFlow()

    private val _selectedNames = MutableStateFlow<Set<String>>(emptySet())
    val selectedNames: StateFlow<Set<String>> = _selectedNames.asStateFlow()

    /** When user picked a batch op, store it + source dir so confirm can execute */
    private val _pendingBatchOp = MutableStateFlow<BatchOp?>(null)
    val pendingBatchOp: StateFlow<BatchOp?> = _pendingBatchOp.asStateFlow()

    private var batchSourceDir: String = "/"
    private var batchSourceFiles: List<String> = emptyList()

    fun attach(sid: String) {
        sessionId = sid
        loadDirectory(_currentPath.value)
    }

    // ═══════════════════════════════════════
    //  Directory navigation
    // ═══════════════════════════════════════

    fun loadDirectory(path: String) {
        val sid = sessionId ?: return
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = sshManager.listRemote(sid, path)
                if (result.startsWith("Error:")) {
                    _error.value = result
                    _isLoading.value = false
                    return@launch
                }
                val arr = JSONArray(result)
                val list = mutableListOf<RemoteFile>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        RemoteFile(
                            name = obj.getString("name"),
                            size = obj.getLong("size"),
                            isDir = obj.getBoolean("is_dir"),
                            permissions = obj.getString("permissions"),
                            mtime = obj.getLong("mtime")
                        )
                    )
                }
                list.sortWith(compareByDescending<RemoteFile> { it.isDir }.thenBy { it.name })
                _files.value = list
                _currentPath.value = if (path.endsWith("/")) path else "$path/"
            } catch (e: Exception) {
                _error.value = "加载失败: ${e.message}"
            }
            _isLoading.value = false
        }
    }

    fun navigateUp() {
        val current = _currentPath.value.trimEnd('/')
        if (current == "" || current == "/") return
        val parent = current.substringBeforeLast('/', "/")
        loadDirectory(if (parent.isEmpty()) "/" else parent)
    }

    fun openDir(dirName: String) {
        val newPath = _currentPath.value + dirName
        loadDirectory(newPath)
    }

    // ═══════════════════════════════════════
    //  Single file operations
    // ═══════════════════════════════════════

    fun downloadFile(fileName: String) {
        val sid = sessionId ?: return
        val remotePath = _currentPath.value + fileName
        val localDir = File(getApplication<Application>().getExternalFilesDir(null), "sftp_downloads")
        val localFile = File(localDir, fileName)

        val record = TransferRecord(
            fileName = fileName,
            remotePath = remotePath,
            localPath = localFile.absolutePath,
            isUpload = false
        )
        _downloads.value = listOf(record) + _downloads.value

        viewModelScope.launch(Dispatchers.IO) {
            val result = sshManager.scpDownload(sid, remotePath, localFile)
            result.fold(
                onSuccess = { size ->
                    _downloads.value = _downloads.value.map {
                        if (it.id == record.id) it.copy(status = TransferStatus.SUCCESS, size = size) else it
                    }
                    addLog("下载成功: $fileName (${formatSize(size)})")
                },
                onFailure = { e ->
                    _downloads.value = _downloads.value.map {
                        if (it.id == record.id) it.copy(status = TransferStatus.FAILED, error = e.message ?: "未知错误") else it
                    }
                    addLog("下载失败: $fileName - ${e.message}", true)
                }
            )
        }
    }

    fun uploadFile(uri: Uri) {
        val sid = sessionId ?: return
        val context = getApplication<Application>()
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "uploaded_file"
        val remotePath = _currentPath.value + fileName
        val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}")

        val record = TransferRecord(
            fileName = fileName, remotePath = remotePath,
            localPath = uri.toString(), isUpload = true
        )
        _uploads.value = listOf(record) + _uploads.value

        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw Exception("无法读取文件")

                sshManager.scpUpload(sid, tempFile, remotePath).fold(
                    onSuccess = { size ->
                        _uploads.value = _uploads.value.map {
                            if (it.id == record.id) it.copy(status = TransferStatus.SUCCESS, size = size) else it
                        }
                        addLog("上传成功: $fileName")
                        loadDirectory(_currentPath.value)
                    },
                    onFailure = { e ->
                        _uploads.value = _uploads.value.map {
                            if (it.id == record.id) it.copy(status = TransferStatus.FAILED, error = e.message ?: "未知错误") else it
                        }
                        addLog("上传失败: $fileName - ${e.message}", true)
                    }
                )
            } catch (e: Exception) {
                _uploads.value = _uploads.value.map {
                    if (it.id == record.id) it.copy(status = TransferStatus.FAILED, error = e.message ?: "未知错误") else it
                }
                addLog("上传失败: $fileName - ${e.message}", true)
            } finally {
                tempFile.delete()
            }
        }
    }

    fun deleteFile(fileName: String) {
        val sid = sessionId ?: return
        val remotePath = _currentPath.value + fileName
        val isDir = _files.value.find { it.name == fileName }?.isDir == true
        viewModelScope.launch(Dispatchers.IO) {
            val result = if (isDir) sshManager.deleteRecursive(sid, remotePath)
                         else sshManager.deleteRemote(sid, remotePath)
            result.fold(
                onSuccess = { addLog("删除成功: $fileName"); loadDirectory(_currentPath.value) },
                onFailure = { addLog("删除失败: $fileName - ${it.message}", true); _error.value = "删除失败: ${it.message}" }
            )
        }
    }

    fun copySingleFile(fileName: String) {
        val sid = sessionId ?: return
        val src = _currentPath.value + fileName
        _pendingBatchOp.value = BatchOp.COPY
        batchSourceDir = _currentPath.value
        batchSourceFiles = listOf(fileName)
        addLog("已标记复制: $fileName ，请导航到目标目录后点击确认")
    }

    fun moveSingleFile(fileName: String) {
        val sid = sessionId ?: return
        val src = _currentPath.value + fileName
        _pendingBatchOp.value = BatchOp.MOVE
        batchSourceDir = _currentPath.value
        batchSourceFiles = listOf(fileName)
        addLog("已标记移动: $fileName ，请导航到目标目录后点击确认")
    }

    // ═══════════════════════════════════════
    //  Multi-select & batch
    // ═══════════════════════════════════════

    fun toggleMultiSelect() {
        _isMultiSelect.value = !_isMultiSelect.value
        if (!_isMultiSelect.value) {
            _selectedNames.value = emptySet()
        }
    }

    fun enterMultiSelectWith(name: String) {
        _isMultiSelect.value = true
        _selectedNames.value = setOf(name)
    }

    fun toggleSelection(name: String) {
        val cur = _selectedNames.value.toMutableSet()
        if (cur.contains(name)) cur.remove(name) else cur.add(name)
        _selectedNames.value = cur
    }

    fun startBatchOp(op: BatchOp) {
        val selected = _selectedNames.value.toList()
        if (selected.isEmpty()) { addLog("未选择任何文件", true); return }

        if (op == BatchOp.DOWNLOAD) {
            // Execute download immediately
            executeBatchDownload(selected)
            return
        }

        // For COPY / MOVE, store pending
        _pendingBatchOp.value = op
        batchSourceDir = _currentPath.value
        batchSourceFiles = selected
        _isMultiSelect.value = false
        _selectedNames.value = emptySet()
        val opName = if (op == BatchOp.COPY) "复制" else "移动"
        addLog("已标记批量${opName} ${selected.size} 项，请导航到目标目录后点击确认")
    }

    fun confirmPendingOp() {
        val op = _pendingBatchOp.value ?: return
        val sid = sessionId ?: return
        val dstDir = _currentPath.value
        val files = batchSourceFiles.toList()
        _pendingBatchOp.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val opName = if (op == BatchOp.COPY) "复制" else "移动"
            for (name in files) {
                val src = batchSourceDir + name
                val dst = dstDir + name
                if (src == dst) {
                    addLog("$opName 跳过 (相同路径): $name", true)
                    continue
                }
                val result = if (op == BatchOp.COPY)
                    sshManager.copyRemote(sid, src, dst)
                else
                    sshManager.moveRemote(sid, src, dst)
                result.fold(
                    onSuccess = { addLog("${opName}成功: $name → $dstDir") },
                    onFailure = { addLog("${opName}失败: $name - ${it.message}", true) }
                )
            }
            loadDirectory(_currentPath.value)
        }
    }

    fun cancelPendingOp() {
        _pendingBatchOp.value = null
        batchSourceFiles = emptyList()
        addLog("已取消操作")
    }

    private fun executeBatchDownload(names: List<String>) {
        val sid = sessionId ?: return
        val localDir = File(getApplication<Application>().getExternalFilesDir(null), "sftp_downloads")
        _isMultiSelect.value = false
        _selectedNames.value = emptySet()

        viewModelScope.launch(Dispatchers.IO) {
            for (name in names) {
                val file = _files.value.find { it.name == name }
                if (file?.isDir == true) {
                    addLog("跳过目录: $name (不支持下载目录)", true)
                    continue
                }
                val remotePath = _currentPath.value + name
                val localFile = File(localDir, name)
                val record = TransferRecord(
                    fileName = name, remotePath = remotePath,
                    localPath = localFile.absolutePath, isUpload = false
                )
                _downloads.value = listOf(record) + _downloads.value

                sshManager.scpDownload(sid, remotePath, localFile).fold(
                    onSuccess = { size ->
                        _downloads.value = _downloads.value.map {
                            if (it.id == record.id) it.copy(status = TransferStatus.SUCCESS, size = size) else it
                        }
                        addLog("下载成功: $name (${formatSize(size)})")
                    },
                    onFailure = { e ->
                        _downloads.value = _downloads.value.map {
                            if (it.id == record.id) it.copy(status = TransferStatus.FAILED, error = e.message ?: "未知错误") else it
                        }
                        addLog("下载失败: $name - ${e.message}", true)
                    }
                )
            }
        }
    }

    // ═══════════════════════════════════════
    //  File editor
    // ═══════════════════════════════════════

    fun openFileEditor(fileName: String) {
        val sid = sessionId ?: return
        val remotePath = _currentPath.value + fileName
        _editingFile.value = remotePath
        _editingContent.value = ""
        viewModelScope.launch(Dispatchers.IO) {
            sshManager.readFileContent(sid, remotePath).fold(
                onSuccess = { _editingContent.value = it },
                onFailure = { _error.value = "读取失败: ${it.message}"; _editingFile.value = null }
            )
        }
    }

    fun updateEditingContent(content: String) { _editingContent.value = content }

    fun saveEditingFile() {
        val sid = sessionId ?: return
        val path = _editingFile.value ?: return
        _isSaving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            sshManager.writeFileContent(sid, path, _editingContent.value).fold(
                onSuccess = { _isSaving.value = false },
                onFailure = { _error.value = "保存失败: ${it.message}"; _isSaving.value = false }
            )
        }
    }

    fun closeEditor() { _editingFile.value = null; _editingContent.value = "" }

    // ═══════════════════════════════════════
    //  Utils
    // ═══════════════════════════════════════

    fun clearError() { _error.value = null }

    fun getLocalFile(record: TransferRecord): File? {
        val f = File(record.localPath)
        return if (f.exists()) f else null
    }

    fun clearLogs() { _logs.value = emptyList() }

    private fun addLog(msg: String, isError: Boolean = false) {
        _logs.value = listOf(LogEntry(message = msg, isError = isError)) + _logs.value
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)}MB"
    }
}
