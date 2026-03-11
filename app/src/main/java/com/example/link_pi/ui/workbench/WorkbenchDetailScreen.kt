package com.example.link_pi.ui.workbench

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.agent.StepType
import com.example.link_pi.network.ModelConfig
import com.example.link_pi.ui.common.RichInputBar
import com.example.link_pi.ui.common.RichInputBarStyle
import com.example.link_pi.workbench.TaskStatus
import com.example.link_pi.workbench.WorkbenchTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detail screen for a single Workbench task.
 * Tab layout: 文件 | 需求 | 运行
 */
@Composable
fun WorkbenchDetailScreen(
    viewModel: WorkbenchViewModel,
    taskId: String,
    onBack: () -> Unit,
    onRunApp: (String) -> Unit,
    onRetry: (String) -> Unit,
    onExport: (String) -> Unit = {}
) {
    val tasks by viewModel.tasks.collectAsState()
    val task = tasks.find { it.id == taskId }
    val allStepsMap by viewModel.engineStepsMap.collectAsState()
    val engineSteps = allStepsMap[taskId] ?: emptyList()
    val isActiveTask = viewModel.activeTaskId.collectAsState().value == taskId

    if (task == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("任务不存在", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var files by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(task.updatedAt) {
        files = withContext(Dispatchers.IO) {
            viewModel.getWorkspaceFiles(task.appId)
        }
    }

    // File viewer/editor state
    var openFilePath by remember { mutableStateOf<String?>(null) }
    var showCreateFileDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                val cursor = context.contentResolver.query(it, null, null, null, null)
                var fileName = "imported_file"
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) fileName = c.getString(idx) ?: fileName
                    }
                }
                val text = context.contentResolver.openInputStream(it)
                    ?.bufferedReader()?.readText()
                if (text != null) {
                    viewModel.writeFileContent(task.appId, fileName, text)
                    val newFiles = viewModel.getWorkspaceFiles(task.appId)
                    withContext(Dispatchers.Main) { files = newFiles }
                }
            }
        }
    }

    val tabTitles = listOf("运行", "工作台", "需求")
    val pagerState = rememberPagerState(pageCount = { tabTitles.size })

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top bar: back + title + status ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLabel(task, actualFileCount = files.size)
            }
        }

        // ── Progress bar (when actively running) ──
        if (task.status in listOf(TaskStatus.PLANNING, TaskStatus.GENERATING, TaskStatus.CHECKING)) {
            LinearProgressIndicator(
                progress = { task.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        }

        // ── Error banner ──
        if (task.status == TaskStatus.FAILED && task.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(task.error.take(300), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // ── Tabs ──
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabPositions ->
                SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title, fontWeight = if (pagerState.currentPage == index) FontWeight.Medium else FontWeight.Normal) }
                )
            }
        }

        // ── Tab content ──
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> RunTabContent(task, onRunApp, onRetry, onExport)
                1 -> WorkspaceTabContent(
                    files = files,
                    engineSteps = engineSteps,
                    isActiveTask = isActiveTask,
                    task = task,
                    viewModel = viewModel,
                    onFileClick = { path -> openFilePath = path },
                    onPickFile = {
                        filePickerLauncher.launch(arrayOf(
                            "text/*", "application/json", "application/javascript",
                            "application/xml", "application/xhtml+xml"
                        ))
                    },
                    onCreateFile = { showCreateFileDialog = true }
                )
                2 -> RequirementTabContent(task)
            }
        }
    }

    // ── File viewer/editor dialog ──
    if (openFilePath != null) {
        FileEditorDialog(
            appId = task.appId,
            filePath = openFilePath!!,
            viewModel = viewModel,
            onDismiss = { openFilePath = null }
        )
    }

    // ── Create file dialog ──
    if (showCreateFileDialog) {
        CreateFileDialog(
            onConfirm = { fileName ->
                showCreateFileDialog = false
                coroutineScope.launch(Dispatchers.IO) {
                    viewModel.writeFileContent(task.appId, fileName, "")
                    val newFiles = viewModel.getWorkspaceFiles(task.appId)
                    withContext(Dispatchers.Main) { files = newFiles }
                }
            },
            onDismiss = { showCreateFileDialog = false }
        )
    }
}

// ── Status label (inline text) ──
@Composable
private fun StatusLabel(task: WorkbenchTask, actualFileCount: Int) {
    val (color, _) = when (task.status) {
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary to Icons.Outlined.CheckCircle
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error to Icons.Outlined.ErrorOutline
        TaskStatus.QUEUED -> MaterialTheme.colorScheme.outline to Icons.Outlined.Schedule
        else -> MaterialTheme.colorScheme.tertiary to Icons.Outlined.Code
    }
    val displayFileCount = actualFileCount.takeIf { it > 0 } ?: task.fileCount
    val statusText = when (task.status) {
        TaskStatus.QUEUED -> "等待中"
        TaskStatus.COMPLETED -> "已完成 · $displayFileCount 个文件"
        TaskStatus.FAILED -> "失败"
        // For active states, prefer currentStep if available
        else -> task.currentStep.takeIf { it.isNotBlank() } ?: run {
            val isModify = task.title.startsWith("修改")
            when (task.status) {
                TaskStatus.PLANNING -> if (isModify) "修改中..." else "规划中..."
                TaskStatus.GENERATING, TaskStatus.CHECKING -> if (isModify) "修改中..." else "生成中..."
                else -> ""
            }
        }
    }
    Text(statusText, style = MaterialTheme.typography.labelSmall, color = color)
}

// ━━━━━━━━━━━━━ Tab 1: 工作台 ━━━━━━━━━━━━━
@Composable
private fun WorkspaceTabContent(
    files: List<String>,
    engineSteps: List<AgentStep>,
    isActiveTask: Boolean,
    task: WorkbenchTask,
    viewModel: WorkbenchViewModel,
    onFileClick: (String) -> Unit = {},
    onPickFile: () -> Unit = {},
    onCreateFile: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val inputBarHeight = 64.dp
    val listBottomPadding = if (imeBottomDp > navBarBottomDp) 0.dp else inputBarHeight + 16.dp

    var inputText by remember { mutableStateOf("") }
    var justSent by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var messagesExpanded by remember { mutableStateOf(false) }

    val isBusy = justSent || task.status in listOf(
        TaskStatus.QUEUED, TaskStatus.PLANNING, TaskStatus.GENERATING, TaskStatus.CHECKING
    )
    LaunchedEffect(task.status) {
        if (task.status in listOf(TaskStatus.QUEUED, TaskStatus.PLANNING, TaskStatus.GENERATING, TaskStatus.CHECKING)) {
            justSent = false
        }
    }

    // Refresh model list whenever this screen is (re)entered
    LaunchedEffect(Unit) { viewModel.refreshModels() }

    val models by viewModel.models.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    val deepThinking by viewModel.deepThinking.collectAsState()
    val activeModel = models.find { it.id == activeModelId }

    // Auto-expand message bar when new steps arrive, auto-collapse when idle
    val stepCount = engineSteps.size
    LaunchedEffect(stepCount) {
        if (stepCount > 0 && isActiveTask) messagesExpanded = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Scrollable content ──
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = listBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (files.isNotEmpty()) {
                item { FileTreeSection(files, onFileClick, onCreateFile) }
            }
            if (files.isEmpty() && engineSteps.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("暂无文件", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(onClick = onCreateFile) {
                                Icon(Icons.Filled.Add, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("新建文件")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = onPickFile) {
                                Icon(Icons.Outlined.AttachFile, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("导入文件")
                            }
                        }
                    }
                }
            }
        }

        // ── Bottom panel: message bar + input bar ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 8.dp + navBarBottomDp
                )
        ) {
            // ── Collapsible message bar ──
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 2.dp
            ) {
                Column {
                    // Header: tap to toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = engineSteps.isNotEmpty()) {
                                messagesExpanded = !messagesExpanded
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusText: String
                        val dotColor: androidx.compose.ui.graphics.Color
                        if (engineSteps.isNotEmpty()) {
                            val latest = engineSteps.last()
                            statusText = latest.description.take(40)
                            dotColor = when (latest.type) {
                                StepType.THINKING -> MaterialTheme.colorScheme.tertiary
                                StepType.TOOL_CALL -> MaterialTheme.colorScheme.primary
                                StepType.TOOL_RESULT -> if (latest.description.startsWith("✓"))
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                StepType.FINAL_RESPONSE -> MaterialTheme.colorScheme.primary
                            }
                        } else {
                            statusText = when (task.status) {
                                TaskStatus.COMPLETED -> "已完成"
                                TaskStatus.FAILED -> "执行失败"
                                TaskStatus.QUEUED -> "排队中"
                                TaskStatus.PLANNING, TaskStatus.GENERATING,
                                TaskStatus.CHECKING -> if (task.title.startsWith("修改")) "修改中…" else "处理中…"
                            }
                            dotColor = when (task.status) {
                                TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                                TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (engineSteps.isNotEmpty()) {
                            Text(
                                text = "${engineSteps.size} 步",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            val expandRotation by animateFloatAsState(
                                targetValue = if (messagesExpanded) 180f else 0f,
                                label = "expand"
                            )
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .rotate(expandRotation),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    // Expanded step list
                    AnimatedVisibility(
                        visible = messagesExpanded && engineSteps.isNotEmpty(),
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        val msgListState = rememberLazyListState()
                        LaunchedEffect(engineSteps.size) {
                            if (engineSteps.isNotEmpty()) {
                                msgListState.animateScrollToItem(engineSteps.size - 1)
                            }
                        }
                        LazyColumn(
                            state = msgListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(engineSteps) { step ->
                                StepItem(step)
                            }
                        }
                    }
                }
            }

            // ── Input bar ──
            RichInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    val prompt = inputText.trim()
                    inputText = ""
                    justSent = true
                    viewModel.modifyApp(task.id, prompt)
                },
                enabled = !isBusy,
                placeholder = "描述你想要的修改...",
                disabledPlaceholder = "正在生成中…",
                models = models,
                activeModelId = activeModelId.orEmpty(),
                onSwitchModel = {
                    viewModel.switchModel(it)
                    showModelMenu = false
                },
                showModelMenu = showModelMenu,
                onToggleModelMenu = { showModelMenu = !showModelMenu },
                deepThinking = deepThinking,
                onToggleThinking = { viewModel.toggleDeepThinking() },
                onPickFile = onPickFile,
                showAttachButton = true,
                style = RichInputBarStyle.Material,
                modifier = Modifier,
                shadowElevation = 6.dp,
            )
        }   // end Column (bottom panel)
    }       // end Box
}

// ━━━━━━━━━━━━━ Tab 2: 需求 ━━━━━━━━━━━━━
@Composable
private fun RequirementTabContent(task: WorkbenchTask) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("原始需求", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(task.userPrompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp)
            }
        }
    }
}

// ━━━━━━━━━━━━━ Tab 2: 运行 ━━━━━━━━━━━━━
@Composable
private fun RunTabContent(
    task: WorkbenchTask,
    onRunApp: (String) -> Unit,
    onRetry: (String) -> Unit,
    onExport: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section 1: 程序说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("程序说明", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                if (task.description.isNotBlank()) {
                    Text(task.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp)
                } else {
                    Text(
                        if (task.status == TaskStatus.COMPLETED) "暂无程序说明"
                        else "生成完成后可查看程序说明",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        }

        // Section 2: 运行应用
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CHECKING) {
                    Button(
                        onClick = { onRunApp(task.appId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("运行应用")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onExport(task.appId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导出应用")
                    }
                }
                if (task.status == TaskStatus.FAILED || task.status == TaskStatus.QUEUED) {
                    OutlinedButton(
                        onClick = { onRetry(task.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (task.status == TaskStatus.FAILED) "重试" else "开始生成")
                    }
                }
                if (task.status in listOf(TaskStatus.PLANNING, TaskStatus.GENERATING, TaskStatus.CHECKING)) {
                    Text("正在生成中...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FileTreeSection(files: List<String>, onFileClick: (String) -> Unit = {}, onAdd: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(6.dp))
                Text("文件 (${files.size})", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "新建文件",
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onAdd)
                        .padding(3.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    files.forEachIndexed { index, file ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onFileClick(file) }
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                file,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━ File Viewer/Editor Dialog ━━━━━━━━━━━━━
@Composable
private fun FileEditorDialog(
    appId: String,
    filePath: String,
    viewModel: WorkbenchViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var content by remember { mutableStateOf<String?>(null) }
    var editedContent by remember { mutableStateOf("") }
    var isEditing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }

    LaunchedEffect(filePath) {
        val raw = withContext(Dispatchers.IO) {
            viewModel.readFileContent(appId, filePath)
        }
        if (raw != null) {
            content = raw
            editedContent = raw
        } else {
            loadError = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // ── Top bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        filePath.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        filePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!loadError && content != null) {
                    if (isEditing) {
                        IconButton(
                            onClick = {
                                isSaving = true
                                coroutineScope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        viewModel.writeFileContent(appId, filePath, editedContent)
                                    }
                                    isSaving = false
                                    if (ok) {
                                        content = editedContent
                                        isEditing = false
                                    }
                                }
                            },
                            enabled = !isSaving && editedContent != content
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "保存",
                                tint = if (editedContent != content)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        }
                    }
                    IconButton(onClick = {
                        if (isEditing) {
                            editedContent = content ?: ""
                        }
                        isEditing = !isEditing
                    }) {
                        Icon(
                            if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                            contentDescription = if (isEditing) "取消编辑" else "编辑",
                            tint = if (isEditing) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            // ── Content area ──
            when {
                loadError -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("无法读取文件", color = MaterialTheme.colorScheme.error)
                    }
                }
                content == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                isEditing -> {
                    BasicTextField(
                        value = editedContent,
                        onValueChange = { editedContent = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState()),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
                else -> {
                    val scrollState = rememberScrollState()
                    Text(
                        text = content!!,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentStepsSection(steps: List<AgentStep>) {
    var expanded by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new steps arrive
    LaunchedEffect(steps.size) {
        if (steps.isNotEmpty()) {
            listState.animateScrollToItem(steps.size - 1)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("执行日志", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.weight(1f))
                Text("${steps.size} 步", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(steps) { step ->
                        StepItem(step)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(step: AgentStep) {
    var showDetail by remember { mutableStateOf(false) }
    val hasDetail = step.detail.isNotBlank()

    val (dotColor, _) = when (step.type) {
        StepType.THINKING -> MaterialTheme.colorScheme.tertiary to "💡"
        StepType.TOOL_CALL -> MaterialTheme.colorScheme.primary to "🔧"
        StepType.TOOL_RESULT -> if (step.description.startsWith("✓"))
            MaterialTheme.colorScheme.primary to "✓" else
            MaterialTheme.colorScheme.error to "✗"
        StepType.FINAL_RESPONSE -> MaterialTheme.colorScheme.primary to "✅"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasDetail) Modifier.clickable { showDetail = !showDetail } else Modifier)
            .padding(vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    step.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (showDetail) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
                // Show detail line for tool calls (file names, arguments, etc.)
                if (hasDetail && step.type == StepType.TOOL_CALL && !showDetail) {
                    Text(
                        step.detail.take(120),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Expanded detail view
                if (showDetail && hasDetail) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        step.detail.take(500),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            if (hasDetail) {
                Icon(
                    if (showDetail) Icons.Outlined.KeyboardArrowDown
                    else Icons.Outlined.KeyboardArrowRight,
                    contentDescription = if (showDetail) "收起" else "展开",
                    modifier = Modifier.size(16.dp).padding(top = 3.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━ Create File Dialog ━━━━━━━━━━━━━
@Composable
private fun CreateFileDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var fileName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("新建文件", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    placeholder = { Text("例如: style.css") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(fileName.trim()) },
                        enabled = fileName.isNotBlank()
                    ) {
                        Text("创建")
                    }
                }
            }
        }
    }
}
