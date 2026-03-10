package com.example.link_pi.ui.sftp

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

// ═══════════════════════════════════════
//  Theme — reuse SSH terminal palette
// ═══════════════════════════════════════

private val TermBg = Color(0xFF0D1117)
private val TermSurface = Color(0xFF161B22)
private val TermCard = Color(0xFF1C2128)
private val TermBorder = Color(0xFF30363D)
private val TermGreen = Color(0xFF3FB950)
private val TermYellow = Color(0xFFD29922)
private val TermRed = Color(0xFFF85149)
private val TermCyan = Color(0xFF58A6FF)
private val TermText = Color(0xFFE6EDF3)
private val TermDim = Color(0xFF8B949E)
private val MonoFont = FontFamily.Monospace

// ═══════════════════════════════════════
//  Main Screen
// ═══════════════════════════════════════

@Composable
fun SftpScreen(
    viewModel: SftpViewModel,
    sessionId: String,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit
) {
    val currentPath by viewModel.currentPath.collectAsState()
    val files by viewModel.files.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val uploads by viewModel.uploads.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val isMultiSelect by viewModel.isMultiSelect.collectAsState()
    val selectedNames by viewModel.selectedNames.collectAsState()
    val pendingOp by viewModel.pendingBatchOp.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("下载", "上传", "日志")

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.uploadFile(it) } }

    LaunchedEffect(sessionId) { viewModel.attach(sessionId) }

    BackHandler {
        when {
            isMultiSelect -> viewModel.toggleMultiSelect()
            pendingOp != null -> viewModel.cancelPendingOp()
            else -> {
                val path = currentPath.trimEnd('/')
                if (path.isNotEmpty() && path != "/") viewModel.navigateUp() else onBack()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBg)
            .statusBarsPadding()
    ) {
        // ── Top Bar ──
        SftpTopBar(
            path = currentPath,
            isMultiSelect = isMultiSelect,
            selectedCount = selectedNames.size,
            pendingOp = pendingOp,
            onBack = onBack,
            onRefresh = { viewModel.loadDirectory(currentPath) },
            onToggleMultiSelect = { viewModel.toggleMultiSelect() },
            onBatchDownload = { viewModel.startBatchOp(BatchOp.DOWNLOAD) },
            onBatchCopy = { viewModel.startBatchOp(BatchOp.COPY) },
            onBatchMove = { viewModel.startBatchOp(BatchOp.MOVE) },
            onConfirmPending = { viewModel.confirmPendingOp() },
            onCancelPending = { viewModel.cancelPendingOp() }
        )

        // ── Pending op banner ──
        if (pendingOp != null) {
            val opName = if (pendingOp == BatchOp.COPY) "复制" else "移动"
            Surface(color = TermYellow.copy(alpha = 0.15f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Info, null, tint = TermYellow, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "请导航到目标目录，然后点击 ✓ 确认${opName}",
                        style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermYellow),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── Upper: Remote file list ──
        Column(modifier = Modifier.weight(1f)) {
            PathBar(currentPath) { viewModel.navigateUp() }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TermCyan, modifier = Modifier.size(24.dp))
                }
            } else if (error != null) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(error ?: "", style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermRed))
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount > 80) viewModel.navigateUp()
                        }
                    }
                ) {
                    items(files, key = { it.name }) { file ->
                        RemoteFileRow(
                            file = file,
                            isMultiSelect = isMultiSelect,
                            isSelected = selectedNames.contains(file.name),
                            onToggleSelect = { viewModel.toggleSelection(file.name) },
                            onEnterMultiSelect = { viewModel.enterMultiSelectWith(file.name) },
                            onClickDir = { viewModel.openDir(file.name) },
                            onClickFile = { viewModel.openFileEditor(file.name); onOpenEditor() },
                            onDownload = { viewModel.downloadFile(file.name) },
                            onDelete = { viewModel.deleteFile(file.name) },
                            onCopy = { viewModel.copySingleFile(file.name) },
                            onMove = { viewModel.moveSingleFile(file.name) }
                        )
                    }
                    if (files.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("空目录", style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermDim))
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = TermBorder)

        // ── Lower: Tabs ──
        Column(modifier = Modifier.weight(0.6f)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = TermSurface,
                contentColor = TermCyan,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = TermCyan
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when (index) {
                                        0 -> Icons.Filled.CloudDownload
                                        1 -> Icons.Filled.CloudUpload
                                        else -> Icons.Outlined.Info
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    title,
                                    style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp),
                                    color = if (selectedTab == index) TermCyan else TermDim
                                )
                            }
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> DownloadTab(downloads, viewModel)
                1 -> UploadTab(uploads) { filePickerLauncher.launch(arrayOf("*/*")) }
                2 -> LogTab(logs) { viewModel.clearLogs() }
            }
        }
    }
}

// ═══════════════════════════════════════
//  Top Bar
// ═══════════════════════════════════════

@Composable
private fun SftpTopBar(
    path: String,
    isMultiSelect: Boolean,
    selectedCount: Int,
    pendingOp: BatchOp?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleMultiSelect: () -> Unit,
    onBatchDownload: () -> Unit,
    onBatchCopy: () -> Unit,
    onBatchMove: () -> Unit,
    onConfirmPending: () -> Unit,
    onCancelPending: () -> Unit
) {
    Surface(color = TermSurface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pendingOp != null) {
                // Pending op mode: cancel + title + confirm
                IconButton(onClick = onCancelPending) {
                    Icon(Icons.Filled.Close, "取消", tint = TermRed, modifier = Modifier.size(20.dp))
                }
                val opName = if (pendingOp == BatchOp.COPY) "复制" else "移动"
                Text(
                    "确认${opName}到此目录",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TermYellow),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onConfirmPending) {
                    Icon(Icons.Filled.Done, "确认", tint = TermGreen, modifier = Modifier.size(22.dp))
                }
            } else if (isMultiSelect) {
                // Multi-select mode: exit + count + batch actions
                IconButton(onClick = onToggleMultiSelect) {
                    Icon(Icons.Filled.Close, "退出多选", tint = TermText, modifier = Modifier.size(20.dp))
                }
                Text(
                    "已选 $selectedCount 项",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TermCyan),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onBatchDownload) {
                    Icon(Icons.Filled.CloudDownload, "批量下载", tint = TermCyan, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onBatchCopy) {
                    Icon(Icons.Filled.ContentCopy, "批量复制", tint = TermGreen, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onBatchMove) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, "批量移动", tint = TermYellow, modifier = Modifier.size(18.dp))
                }
            } else {
                // Normal mode
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TermText, modifier = Modifier.size(20.dp))
                }
                Icon(Icons.Filled.FolderOpen, null, tint = TermYellow, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "SFTP 文件管理",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TermText)
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleMultiSelect) {
                    Icon(Icons.Filled.Checklist, "多选", tint = TermDim, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, "刷新", tint = TermDim, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════
//  Path Bar
// ═══════════════════════════════════════

@Composable
private fun PathBar(path: String, onUp: () -> Unit) {
    Surface(color = TermCard) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onUp,
                shape = RoundedCornerShape(4.dp),
                color = TermBorder.copy(alpha = 0.5f)
            ) {
                Text(
                    " ‹‹ ",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TermCyan),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                path,
                style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermGreen),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ═══════════════════════════════════════
//  Remote File Row
// ═══════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteFileRow(
    file: RemoteFile,
    isMultiSelect: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onEnterMultiSelect: () -> Unit,
    onClickDir: () -> Unit,
    onClickFile: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = TermCard,
            titleContentColor = TermText,
            textContentColor = TermDim,
            title = { Text("确认删除", style = TextStyle(fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)) },
            text = { Text("确定要删除 ${file.name} 吗？${if (file.isDir) "（将递归删除目录内容）" else ""}", style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp)) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("删除", color = TermRed, style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = TermDim, style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp))
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = if (isSelected) TermCyan.copy(alpha = 0.1f) else Color.Transparent) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        when {
                            isMultiSelect -> onToggleSelect()
                            file.isDir -> onClickDir()
                            else -> onClickFile()
                        }
                    },
                    onLongClick = {
                        if (!isMultiSelect) onEnterMultiSelect()
                    }
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isMultiSelect) {
                Icon(
                    if (isSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (isSelected) TermCyan else TermDim,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                if (file.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (file.isDir) TermYellow else TermDim,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = if (file.isDir) TermCyan else TermText),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row {
                    Text(file.permissions, style = TextStyle(fontFamily = MonoFont, fontSize = 9.sp, color = TermDim))
                    if (file.displaySize.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(file.displaySize, style = TextStyle(fontFamily = MonoFont, fontSize = 9.sp, color = TermDim))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(file.displayTime, style = TextStyle(fontFamily = MonoFont, fontSize = 9.sp, color = TermDim))
                }
            }
            if (!isMultiSelect) {
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, "操作", tint = TermDim, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = TermCard
                    ) {
                        if (!file.isDir) {
                            DropdownMenuItem(
                                text = { Text("下载", style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermCyan)) },
                                onClick = { showMenu = false; onDownload() },
                                leadingIcon = { Icon(Icons.Filled.CloudDownload, null, tint = TermCyan, modifier = Modifier.size(16.dp)) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("复制", style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermGreen)) },
                            onClick = { showMenu = false; onCopy() },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null, tint = TermGreen, modifier = Modifier.size(16.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("移动", style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermYellow)) },
                            onClick = { showMenu = false; onMove() },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, tint = TermYellow, modifier = Modifier.size(16.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermRed)) },
                            onClick = { showMenu = false; showDeleteDialog = true },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = TermRed, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════
//  Download Tab
// ═══════════════════════════════════════

@Composable
private fun DownloadTab(downloads: List<TransferRecord>, viewModel: SftpViewModel) {
    val context = LocalContext.current
    if (downloads.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无下载记录", style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermDim))
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(downloads, key = { it.id }) { record ->
                TransferRow(record) {
                    // Share button action
                    val file = viewModel.getLocalFile(record)
                    if (file != null) {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "*/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "分享文件"))
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════
//  Upload Tab
// ═══════════════════════════════════════

@Composable
private fun UploadTab(uploads: List<TransferRecord>, onPickFile: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Upload button
        Surface(
            onClick = onPickFile,
            shape = RoundedCornerShape(8.dp),
            color = TermCyan.copy(alpha = 0.15f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.UploadFile, null, tint = TermCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "选择本地文件上传",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TermCyan)
                )
            }
        }

        if (uploads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无上传记录", style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermDim))
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(uploads, key = { it.id }) { record ->
                    TransferRow(record, onShare = null)
                }
            }
        }
    }
}

// ═══════════════════════════════════════
//  Transfer Row
// ═══════════════════════════════════════

@Composable
private fun TransferRow(record: TransferRecord, onShare: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = TermCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            when (record.status) {
                TransferStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = TermCyan
                )
                TransferStatus.SUCCESS -> Icon(
                    Icons.Outlined.CheckCircle, null, tint = TermGreen, modifier = Modifier.size(16.dp)
                )
                TransferStatus.FAILED -> Icon(
                    Icons.Outlined.Error, null, tint = TermRed, modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.fileName,
                    style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = when (record.status) {
                    TransferStatus.RUNNING -> if (record.isUpload) "上传中..." else "下载中..."
                    TransferStatus.SUCCESS -> {
                        val sizeStr = when {
                            record.size < 1024 -> "${record.size}B"
                            record.size < 1024 * 1024 -> "${record.size / 1024}KB"
                            else -> "${"%.1f".format(record.size / 1024.0 / 1024.0)}MB"
                        }
                        "$sizeStr · ${record.remotePath}"
                    }
                    TransferStatus.FAILED -> record.error
                }
                Text(
                    detail,
                    style = TextStyle(
                        fontFamily = MonoFont, fontSize = 9.sp,
                        color = if (record.status == TransferStatus.FAILED) TermRed else TermDim
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Share button for downloads
            if (onShare != null && record.status == TransferStatus.SUCCESS) {
                IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Share, "分享", tint = TermCyan, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════
//  Log Tab
// ═══════════════════════════════════════

@Composable
private fun LogTab(logs: List<LogEntry>, onClear: () -> Unit) {
    if (logs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无日志", style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = TermDim))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    onClick = onClear,
                    shape = RoundedCornerShape(4.dp),
                    color = TermBorder.copy(alpha = 0.5f)
                ) {
                    Text(
                        "清空",
                        style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = TermDim),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs, key = { it.id }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            entry.time,
                            style = TextStyle(fontFamily = MonoFont, fontSize = 9.sp, color = TermDim)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            entry.message,
                            style = TextStyle(
                                fontFamily = MonoFont, fontSize = 10.sp,
                                color = if (entry.isError) TermRed else TermGreen
                            ),
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
