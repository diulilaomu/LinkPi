package com.example.link_pi.ui.workbench

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.miniapp.ShortcutHelper
import com.example.link_pi.miniapp.SdkManager
import com.example.link_pi.miniapp.SdkModule
import com.example.link_pi.ui.miniapp.PinToHomeDialog
import com.example.link_pi.ui.navigation.Screen
import com.example.link_pi.workbench.TaskStatus
import com.example.link_pi.workbench.WorkbenchTask
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkbenchListScreen(
    viewModel: WorkbenchViewModel,
    onBack: () -> Unit,
    onOpenTask: (WorkbenchTask) -> Unit,
    onNewTask: () -> Unit,
    onOpenSsh: () -> Unit = {},
    onRunApp: ((MiniApp) -> Unit)? = null
) {
    val tasks by viewModel.tasks.collectAsState()
    val tabTitles = listOf("已生成", "工作中")
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editInfoTargetApp by remember { mutableStateOf<MiniApp?>(null) }
    var deleteTargetTask by remember { mutableStateOf<WorkbenchTask?>(null) }
    var permissionTargetApp by remember { mutableStateOf<MiniApp?>(null) }

    val completedTasks = tasks.filter { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.FAILED }
    val workingTasks = tasks.filter { it.status != TaskStatus.COMPLETED && it.status != TaskStatus.FAILED }

    Scaffold(
        floatingActionButton = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FloatingActionButton(
                    onClick = onBack,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface)
                }
                FloatingActionButton(
                    onClick = onNewTask,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新建应用",
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab Row
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) }
                    )
                }
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                if (page == 0) {
                    // "已生成" tab: 4-column icon grid
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // SSH built-in app
                        item(key = "builtin_ssh") {
                            AppGridItem(
                                icon = {
                                    Icon(
                                        Icons.Outlined.Terminal,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                },
                                name = "SSH 终端",
                                onClick = onOpenSsh,
                                menuItems = listOf("固定应用"),
                                onMenuAction = {
                                    ShortcutHelper.pinBuiltInToHomeScreen(context, Screen.SshHome.route, "SSH 终端", "")
                                }
                            )
                        }
                        // Generated apps
                        items(completedTasks, key = { it.id }) { task ->
                            val miniApp = remember(task.appId) { viewModel.loadMiniApp(task.appId) }
                            AppGridItem(
                                icon = { MiniAppIcon(miniApp) },
                                name = miniApp?.name ?: task.title,
                                onClick = {
                                    if (miniApp != null && onRunApp != null) onRunApp(miniApp)
                                    else onOpenTask(task)
                                },
                                menuItems = listOf("修改应用", "固定应用", "权限管理", "编辑信息", "删除应用"),
                                onMenuAction = { action ->
                                    when (action) {
                                        "修改应用" -> onOpenTask(task)
                                        "固定应用" -> {
                                            if (miniApp != null) {
                                                ShortcutHelper.pinToHomeScreen(context, miniApp.id, miniApp.name, miniApp.icon.ifBlank { null })
                                            }
                                        }
                                        "权限管理" -> { permissionTargetApp = miniApp }
                                        "编辑信息" -> { editInfoTargetApp = miniApp }
                                        "删除应用" -> { deleteTargetTask = task }
                                    }
                                }
                            )
                        }
                    }
                } else {
                    // "工作中" tab
                    val pageTasks = workingTasks
                    if (pageTasks.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.Code,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "没有进行中的任务",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(pageTasks, key = { it.id }) { task ->
                                TaskCard(
                                    task = task,
                                    onClick = { onOpenTask(task) },
                                    onDelete = { viewModel.deleteTask(task.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit info dialog
    editInfoTargetApp?.let { app ->
        PinToHomeDialog(
            appId = app.id,
            defaultName = app.name,
            defaultIcon = app.icon,
            onDismiss = { editInfoTargetApp = null },
            onSave = { name, iconPath ->
                viewModel.updateMiniAppInfo(app.id, name, iconPath)
                editInfoTargetApp = null
            },
            onPin = { _, _, _ -> }
        )
    }

    // SDK permission dialog
    permissionTargetApp?.let { app ->
        SdkPermissionDialog(
            appId = app.id,
            appName = app.name,
            onDismiss = { permissionTargetApp = null }
        )
    }

    // Delete confirmation dialog
    deleteTargetTask?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteTargetTask = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${task.title}」及其所有文件吗？此操作不可撤销。") },
            dismissButton = {
                TextButton(onClick = { deleteTargetTask = null }) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTask(task.id)
                    deleteTargetTask = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
private fun TaskCard(
    task: WorkbenchTask,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (task.status) {
            TaskStatus.QUEUED -> MaterialTheme.colorScheme.outline
            TaskStatus.PLANNING, TaskStatus.GENERATING, TaskStatus.CHECKING -> MaterialTheme.colorScheme.primary
            TaskStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
            TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        },
        label = "statusColor"
    )

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Title + status dot
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Status label
            Text(
                text = statusLabel(task),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )

            // Progress bar for active tasks
            if (task.status in listOf(TaskStatus.PLANNING, TaskStatus.GENERATING, TaskStatus.CHECKING)) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                if (task.currentStep.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = task.currentStep,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // File count for completed/in-progress tasks
            if (task.fileCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${task.fileCount} 个文件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Error message for failed tasks
            if (task.status == TaskStatus.FAILED && task.error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = task.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom actions row
            var showDeleteConfirm by remember { mutableStateOf(false) }

            if (showDeleteConfirm) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("确认删除") },
                    text = { Text("确定要删除「${task.title}」及其所有文件吗？此操作不可撤销。") },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("取消")
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showDeleteConfirm = false
                            onDelete()
                        }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timestamp
                Text(
                    text = formatTaskTime(task.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                // Delete button
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

private fun statusLabel(task: WorkbenchTask): String {
    val isModify = task.title.startsWith("修改")
    return when (task.status) {
        TaskStatus.QUEUED -> "排队中"
        TaskStatus.PLANNING -> if (isModify) "修改中" else "规划中"
        TaskStatus.GENERATING, TaskStatus.CHECKING -> if (isModify) "修改中" else "生成中"
        TaskStatus.COMPLETED -> "已完成"
        TaskStatus.FAILED -> "失败"
    }
}

private fun formatTaskTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

@Composable
private fun SdkPermissionDialog(
    appId: String,
    appName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sdkManager = remember { SdkManager(context) }
    val toggles = remember {
        val saved = sdkManager.getEnabledModules(appId)
        mutableStateMapOf(*SdkModule.entries
            .filter { it != SdkModule.CORE }
            .map { it to (it in saved) }
            .toTypedArray())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限管理 · $appName") },
        text = {
            Column {
                Text(
                    "控制此应用可以使用的本地能力。关闭后需重新打开应用生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                SdkModule.entries.filter { it != SdkModule.CORE }.forEach { module ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(module.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                module.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = toggles[module] ?: false,
                            onCheckedChange = { toggles[module] = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val enabled = mutableSetOf(SdkModule.CORE)
                toggles.forEach { (m, on) -> if (on) enabled.add(m) }
                sdkManager.saveEnabledModules(appId, enabled)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGridItem(
    icon: @Composable () -> Unit,
    name: String,
    onClick: () -> Unit,
    menuItems: List<String> = emptyList(),
    onMenuAction: (String) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (menuItems.isNotEmpty()) showMenu = true }
            )
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            menuItems.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        showMenu = false
                        onMenuAction(item)
                    }
                )
            }
        }
    }
}

@Composable
private fun MiniAppIcon(miniApp: MiniApp?) {
    val iconPath = miniApp?.icon ?: ""
    if (iconPath.isNotBlank() && File(iconPath).exists()) {
        val bitmap = remember(iconPath) { BitmapFactory.decodeFile(iconPath) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Outlined.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    } else {
        Icon(
            Icons.Outlined.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )
    }
}
