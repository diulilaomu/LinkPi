package com.example.link_pi.ui.workbench

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import com.example.link_pi.workbench.PlanStep
import com.example.link_pi.workbench.PlanStepStatus
import com.example.link_pi.workbench.TaskStatus
import com.example.link_pi.workbench.WorkbenchMessage
import com.example.link_pi.workbench.WorkbenchRound
import com.example.link_pi.workbench.WorkbenchTask
import com.example.link_pi.workbench.derivePlanSteps
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
    onReloadApp: (String) -> Unit = {},
    onRetry: (String) -> Unit,
    onExport: (String) -> Unit = {}
) {
    val tasks by viewModel.tasks.collectAsState()
    val task = tasks.find { it.id == taskId }
    val allStepsMap by viewModel.engineStepsMap.collectAsState()
    val engineSteps = allStepsMap[taskId] ?: emptyList()
    val allRoundsMap by viewModel.roundsMap.collectAsState()
    val historyRounds = allRoundsMap[taskId] ?: emptyList()
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
                0 -> RunTabContent(
                    task = task,
                    files = files,
                    onRunApp = onRunApp,
                    onReloadApp = onReloadApp,
                    onRetry = onRetry,
                    onExport = onExport,
                    onFileClick = { path -> openFilePath = path },
                    onPickFile = {
                        filePickerLauncher.launch(arrayOf(
                            "text/*", "application/json", "application/javascript",
                            "application/xml", "application/xhtml+xml"
                        ))
                    },
                    onCreateFile = { showCreateFileDialog = true }
                )
                1 -> WorkspaceTabContent(
                    engineSteps = engineSteps,
                    historyRounds = historyRounds,
                    isActiveTask = isActiveTask,
                    task = task,
                    viewModel = viewModel,
                    onPickFile = {
                        filePickerLauncher.launch(arrayOf(
                            "text/*", "application/json", "application/javascript",
                            "application/xml", "application/xhtml+xml"
                        ))
                    }
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

// ━━━━━━━━━━━━━ Tab 1: 工作台 (Message Center) ━━━━━━━━━━━━━
@Composable
private fun WorkspaceTabContent(
    engineSteps: List<AgentStep>,
    historyRounds: List<WorkbenchRound>,
    isActiveTask: Boolean,
    task: WorkbenchTask,
    viewModel: WorkbenchViewModel,
    onPickFile: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    var inputText by remember { mutableStateOf("") }
    var justSent by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }

    val isBusy = justSent || task.status in listOf(
        TaskStatus.QUEUED, TaskStatus.PLANNING, TaskStatus.GENERATING, TaskStatus.CHECKING
    )
    LaunchedEffect(task.status) {
        if (task.status in listOf(TaskStatus.QUEUED, TaskStatus.PLANNING, TaskStatus.GENERATING, TaskStatus.CHECKING)) {
            justSent = false
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshModels() }
    val models by viewModel.models.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    val deepThinking by viewModel.deepThinking.collectAsState()

    // Derive plan steps and current round messages
    val planSteps = remember(engineSteps.size, task.status) {
        derivePlanSteps(engineSteps, task.status)
    }
    val currentMessages = remember(engineSteps.size) {
        WorkbenchMessage.fromSteps(engineSteps)
    }
    val currentDisplayItems = remember(currentMessages.size) {
        groupIntoDisplayItems(currentMessages)
    }

    val msgListState = rememberLazyListState()
    // Auto-scroll to bottom when new messages arrive
    val totalItemCount = historyRounds.size + currentDisplayItems.size
    LaunchedEffect(totalItemCount) {
        if (totalItemCount > 0) {
            msgListState.animateScrollToItem(totalItemCount - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(bottom = navBarBottomDp)
    ) {
        // ── Plan pipeline card ──
        if (engineSteps.isNotEmpty()) {
            PlanPipelineCard(
                steps = planSteps,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        // ── Message list: historical rounds (collapsed) + current round (expanded) ──
        LazyColumn(
            state = msgListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Historical rounds — each is a collapsible card
            items(historyRounds.size, key = { "round_$it" }) { index ->
                HistoryRoundCard(round = historyRounds[index])
            }

            // Current round
            if (currentDisplayItems.isEmpty() && historyRounds.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (isBusy) "等待 AI 开始工作..." else "暂无消息",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            } else if (currentDisplayItems.isNotEmpty()) {
                // Current round header (always visible, not collapsible)
                item(key = "current_header") {
                    val roundNum = historyRounds.size + 1
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "第 $roundNum 轮",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = task.userPrompt.take(60),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Current round messages — expanded
                items(currentDisplayItems.size, key = { "current_$it" }) { index ->
                    when (val item = currentDisplayItems[index]) {
                        is DisplayItem.Single -> AgentMessageRow(item.msg)
                        is DisplayItem.SubAgentGroup -> SubAgentGroupCard(item.messages)
                    }
                }
            }
        }

        // ── Input bar (inline, not floating) ──
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
    }
}

// ━━━━━━━━━━━━━ Plan Pipeline Card ━━━━━━━━━━━━━
@Composable
private fun PlanPipelineCard(steps: List<PlanStep>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            steps.forEachIndexed { index, step ->
                PlanStepChip(step)
                if (index < steps.size - 1) {
                    // Connector line
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .padding(horizontal = 2.dp)
                            .background(
                                when (step.status) {
                                    PlanStepStatus.COMPLETED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    PlanStepStatus.ACTIVE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                    PlanStepStatus.PENDING -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                },
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanStepChip(step: PlanStep) {
    val (bgColor, textColor, dotColor) = when (step.status) {
        PlanStepStatus.COMPLETED -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary
        )
        PlanStepStatus.ACTIVE -> Triple(
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.tertiary
        )
        PlanStepStatus.PENDING -> Triple(
            Color.Transparent,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.outlineVariant
        )
    }

    Row(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (step.status == PlanStepStatus.COMPLETED) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = dotColor
            )
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            step.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = textColor,
            fontWeight = if (step.status == PlanStepStatus.ACTIVE) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ━━━━━━━━━━━━━ History Round Card (two-level collapsible) ━━━━━━━━━━━━━
@Composable
private fun HistoryRoundCard(round: WorkbenchRound) {
    var expanded by remember { mutableStateOf(false) }
    val accentColor = MaterialTheme.colorScheme.outline

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = accentColor.copy(alpha = 0.05f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            // Level 1: Round header — user prompt + summary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = accentColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "第 ${round.index + 1} 轮",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = round.userPrompt.take(60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = round.summary,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = accentColor.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown
                    else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accentColor.copy(alpha = 0.5f)
                )
            }

            // Level 2: Expanded AI process messages (individually collapsible via existing cards)
            if (expanded) {
                HorizontalDivider(
                    color = accentColor.copy(alpha = 0.12f),
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                val displayItems = remember(round.messages.size) {
                    groupIntoDisplayItems(round.messages)
                }
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (item in displayItems) {
                        when (item) {
                            is DisplayItem.Single -> AgentMessageRow(item.msg, showSourceLabel = false)
                            is DisplayItem.SubAgentGroup -> SubAgentGroupCard(item.messages)
                        }
                    }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━ Display Item Grouping ━━━━━━━━━━━━━
private sealed class DisplayItem {
    data class Single(val msg: WorkbenchMessage) : DisplayItem()
    data class SubAgentGroup(val messages: List<WorkbenchMessage>) : DisplayItem()
}

/** Group consecutive sub-agent messages into collapsible groups. */
private fun groupIntoDisplayItems(messages: List<WorkbenchMessage>): List<DisplayItem> {
    val result = mutableListOf<DisplayItem>()
    val subGroup = mutableListOf<WorkbenchMessage>()
    for (msg in messages) {
        if (msg.source == "sub") {
            subGroup.add(msg)
        } else {
            if (subGroup.isNotEmpty()) {
                result.add(DisplayItem.SubAgentGroup(subGroup.toList()))
                subGroup.clear()
            }
            result.add(DisplayItem.Single(msg))
        }
    }
    if (subGroup.isNotEmpty()) {
        result.add(DisplayItem.SubAgentGroup(subGroup.toList()))
    }
    return result
}

// ━━━━━━━━━━━━━ Sub-Agent Group Card ━━━━━━━━━━━━━
@Composable
private fun SubAgentGroupCard(messages: List<WorkbenchMessage>) {
    var expanded by remember { mutableStateOf(false) }
    val agentColor = MaterialTheme.colorScheme.tertiary
    val fileOps = messages.count { it is WorkbenchMessage.CodeChange }
    val toolCalls = messages.count { it is WorkbenchMessage.ToolCall || it is WorkbenchMessage.ToolResult }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = agentColor.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = agentColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "副Agent",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = agentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = buildString {
                        append("${messages.size} 条消息")
                        if (fileOps > 0) append(" · $fileOps 文件操作")
                        if (toolCalls > 0) append(" · $toolCalls 工具调用")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = agentColor.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown
                    else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = agentColor.copy(alpha = 0.5f)
                )
            }

            // Expanded content
            if (expanded) {
                HorizontalDivider(
                    color = agentColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (msg in messages) {
                        AgentMessageRow(msg, showSourceLabel = false)
                    }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━ Agent Chat Message Row ━━━━━━━━━━━━━
@Composable
private fun AgentMessageRow(msg: WorkbenchMessage, showSourceLabel: Boolean = true) {
    val isMain = msg.source != "sub"
    val agentLabel = if (msg.source == "sub") "副Agent" else "主Agent"
    val agentColor = if (isMain)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.tertiary

    // Status messages: compact inline, no card
    if (msg is WorkbenchMessage.Status) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(agentColor.copy(alpha = 0.5f))
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (showSourceLabel) {
                Text(
                    text = agentLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    color = agentColor.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = msg.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    // Thinking messages: collapsible inline (like Status but with expand)
    if (msg is WorkbenchMessage.Thinking) {
        ThinkingCard(msg, agentLabel, agentColor, showSourceLabel)
        return
    }

    // Card-based messages
    Column(modifier = Modifier.fillMaxWidth()) {
        // Agent label header
        if (showSourceLabel) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = agentColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = agentLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = agentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Message content card
        when (msg) {
            is WorkbenchMessage.ToolCall -> ToolCallCard(msg, agentColor)
            is WorkbenchMessage.ToolResult -> ToolResultCard(msg)
            is WorkbenchMessage.CodeChange -> CodeChangeCard(msg)
            is WorkbenchMessage.Thinking -> {} // Handled above
            is WorkbenchMessage.Status -> {} // Handled above
        }
    }
}

@Composable
private fun ThinkingCard(
    msg: WorkbenchMessage.Thinking,
    agentLabel: String,
    agentColor: Color,
    showSourceLabel: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = agentColor.copy(alpha = 0.04f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(agentColor.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (showSourceLabel) {
                    Text(
                        text = agentLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = agentColor.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = "💡 ${msg.summary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowDown
                    else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = agentColor.copy(alpha = 0.4f)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(6.dp)
                        )
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    Text(
                        text = msg.content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(msg: WorkbenchMessage.ToolCall, accentColor: Color) {
    var expanded by remember { mutableStateOf(false) }
    val hasArgs = msg.args.isNotBlank()

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = if (hasArgs) Modifier.clickable { expanded = !expanded } else Modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = msg.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasArgs) {
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowDown
                        else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
            if (expanded && hasArgs) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = msg.args,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ToolResultCard(msg: WorkbenchMessage.ToolResult) {
    var expanded by remember { mutableStateOf(false) }
    val hasDetail = msg.detail.isNotBlank()

    val (bgColor, iconText) = if (msg.success) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) to "✓"
    } else {
        MaterialTheme.colorScheme.error.copy(alpha = 0.06f) to "✗"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        modifier = if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = iconText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (msg.success) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = msg.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasDetail) {
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowDown
                        else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
            if (expanded && hasDetail) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = msg.detail,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun CodeChangeCard(msg: WorkbenchMessage.CodeChange) {
    var expanded by remember { mutableStateOf(false) }
    val hasDetail = msg.detail.isNotBlank()

    val (opColor, opIcon) = when (msg.operation) {
        "创建" -> MaterialTheme.colorScheme.primary to Icons.Filled.Add
        "写入" -> MaterialTheme.colorScheme.tertiary to Icons.Filled.Edit
        "删除" -> MaterialTheme.colorScheme.error to Icons.Filled.Close
        "查看" -> MaterialTheme.colorScheme.secondary to Icons.Outlined.Description
        "列出" -> MaterialTheme.colorScheme.secondary to Icons.Outlined.FolderOpen
        else -> MaterialTheme.colorScheme.secondary to Icons.Outlined.Code
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = opColor.copy(alpha = 0.06f),
        modifier = if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Operation icon
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = opColor.copy(alpha = 0.15f)
                ) {
                    Icon(
                        opIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(3.dp)
                            .size(12.dp),
                        tint = opColor
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = msg.filePath,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = msg.operation,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = opColor.copy(alpha = 0.7f)
                )
                if (hasDetail) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowDown
                        else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(6.dp)
                        )
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    Text(
                        text = msg.detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
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

// ━━━━━━━━━━━━━ Tab 0: 运行 ━━━━━━━━━━━━━
@Composable
private fun RunTabContent(
    task: WorkbenchTask,
    files: List<String>,
    onRunApp: (String) -> Unit,
    onReloadApp: (String) -> Unit = {},
    onRetry: (String) -> Unit,
    onExport: (String) -> Unit = {},
    onFileClick: (String) -> Unit = {},
    onPickFile: () -> Unit = {},
    onCreateFile: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section 1: 文件系统
        if (files.isNotEmpty()) {
            FileTreeSection(files, onFileClick, onCreateFile)
        } else if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.FAILED) {
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
                    Text("暂无文件", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCreateFile) {
                            Icon(Icons.Filled.Add, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("新建文件")
                        }
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

        // Section 2: 程序说明
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

        // Section 3: 运行应用
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
                        onClick = { onReloadApp(task.appId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("重载应用")
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
