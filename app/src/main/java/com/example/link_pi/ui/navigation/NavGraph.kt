package com.example.link_pi.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.link_pi.ui.chat.ChatScreen
import com.example.link_pi.ui.chat.ChatViewModel
import com.example.link_pi.ui.miniapp.MiniAppListScreen
import com.example.link_pi.ui.miniapp.MiniAppScreen
import com.example.link_pi.ui.miniapp.exportMiniApp
import com.example.link_pi.ui.settings.MemoryScreen
import com.example.link_pi.ui.settings.CredentialScreen
import com.example.link_pi.ui.settings.ModelEditScreen
import com.example.link_pi.ui.settings.ModelManageScreen
import com.example.link_pi.ui.settings.ModuleScreen
import com.example.link_pi.ui.settings.SettingsScreen
import com.example.link_pi.ui.settings.ShareScreen
import com.example.link_pi.ui.permission.PermissionScreen
import com.example.link_pi.ui.permission.allPermissionsGranted
import com.example.link_pi.ui.ssh.SshScreen
import com.example.link_pi.ui.ssh.SshViewModel
import com.example.link_pi.ui.skill.SkillListScreen
import com.example.link_pi.ui.workbench.WorkbenchDetailScreen
import com.example.link_pi.ui.workbench.WorkbenchListScreen
import com.example.link_pi.ui.workbench.WorkbenchViewModel

sealed class Screen(val route: String, val title: String) {
    data object Chat : Screen("chat", "LinkPi")
    data object Apps : Screen("apps", "应用")
    data object Workbench : Screen("workbench", "工作台")
    data object WorkbenchDetail : Screen("workbench/detail", "任务详情")
    data object Settings : Screen("settings", "设置")
    data object MiniApp : Screen("miniapp", "运行应用")
    data object ModelManage : Screen("settings/models", "模型管理")
    data class ModelEdit(val modelId: String) : Screen("settings/models/edit", "编辑模型")
    data object SkillSettings : Screen("settings/skills", "Skill 管理")
    data object MemorySettings : Screen("settings/memory", "长期记忆")
    data object ModuleSettings : Screen("settings/modules", "模块管理")
    data object CredentialSettings : Screen("settings/credentials", "凭据管理")
    data object ShareSettings : Screen("settings/share", "本地分享")
    data object SshMode : Screen("ssh_mode", "SSH Terminal")
}

@Composable
fun LinkPiApp() {
    val context = LocalContext.current
    var permissionsHandled by remember { mutableStateOf(allPermissionsGranted(context)) }

    if (!permissionsHandled) {
        PermissionScreen(onAllGranted = { permissionsHandled = true })
        return
    }

    val chatViewModel: ChatViewModel = viewModel()
    val workbenchViewModel: WorkbenchViewModel = viewModel()
    val sshViewModel: SshViewModel = viewModel()
    var currentPage by remember { mutableStateOf<String>(Screen.Chat.route) }
    var editModelId by remember { mutableStateOf("") }
    var activeDetailTaskId by remember { mutableStateOf("") }
    var lastBackTime by remember { mutableLongStateOf(0L) }
    var showConversationList by remember { mutableStateOf(false) }

    // Back handler: sub-pages go back to parent, main page requires double-back to exit
    BackHandler {
        if (showConversationList) {
            showConversationList = false
            return@BackHandler
        }
        when (currentPage) {
            Screen.Chat.route -> {
                val now = System.currentTimeMillis()
                if (now - lastBackTime < 2000) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    lastBackTime = now
                    Toast.makeText(context, "再滑一次退出应用", Toast.LENGTH_SHORT).show()
                }
            }
            Screen.ModelManage.route,
            Screen.SkillSettings.route,
            Screen.MemorySettings.route,
            Screen.ModuleSettings.route,
            Screen.CredentialSettings.route,
            Screen.ShareSettings.route -> currentPage = Screen.Settings.route
            Screen.ModelEdit("").route -> currentPage = Screen.ModelManage.route
            Screen.WorkbenchDetail.route -> currentPage = Screen.Workbench.route
            else -> currentPage = Screen.Chat.route
        }
    }

    // SSH mode: full-screen terminal
    if (currentPage == Screen.SshMode.route) {
        SshScreen(
            viewModel = sshViewModel,
            onBack = {
                sshViewModel.disconnect()
                currentPage = Screen.Chat.route
            }
        )
        return
    }

    // MiniApp has its own full-screen layout
    if (currentPage == Screen.MiniApp.route) {
        val app = chatViewModel.currentMiniApp
        if (app != null) {
            MiniAppScreen(miniApp = app, onBack = { currentPage = Screen.Chat.route })
        }
        return
    }

    // Edge swipe detection for opening conversation list
    val density = LocalDensity.current
    val drawerWidthPx = with(density) { 280.dp.toPx() }
    val edgeThresholdPx = with(density) { 88.dp.toPx() }
    val minSwipeDistancePx = with(density) { 28.dp.toPx() }
    val flingVelocityThresholdPx = with(density) { 900.dp.toPx() }
    val drawerOffset = remember { Animatable(0f) }
    var isDrawerDragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val drawerProgress = if (drawerWidthPx == 0f) 0f else ((drawerOffset.value + drawerWidthPx) / drawerWidthPx).coerceIn(0f, 1f)
    val scrimProgress = ((drawerProgress - 0.65f) / 0.35f).coerceIn(0f, 1f)

    LaunchedEffect(drawerWidthPx) {
        drawerOffset.snapTo(if (showConversationList) 0f else -drawerWidthPx)
    }

    LaunchedEffect(showConversationList, currentPage, isDrawerDragging, drawerWidthPx) {
        if (isDrawerDragging) return@LaunchedEffect
        val target = if (showConversationList && currentPage == Screen.Chat.route) 0f else -drawerWidthPx
        if (abs(drawerOffset.value - target) < 1f) {
            drawerOffset.snapTo(target)
        } else {
            drawerOffset.animateTo(target, tween(durationMillis = if (target == 0f) 240 else 200))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .pointerInput(currentPage, showConversationList) {
                if (currentPage != Screen.Chat.route) return@pointerInput
                var dragEnabled = false
                val velocityTracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        velocityTracker.resetTracking()
                        velocityTracker.addPosition(0L, offset)
                        dragEnabled = if (showConversationList) {
                            offset.x <= drawerWidthPx
                        } else {
                            offset.x <= edgeThresholdPx
                        }
                        isDrawerDragging = dragEnabled
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (!dragEnabled) return@detectHorizontalDragGestures
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch {
                            val nextOffset = (drawerOffset.value + dragAmount).coerceIn(-drawerWidthPx, 0f)
                            drawerOffset.snapTo(nextOffset)
                        }
                    },
                    onDragEnd = {
                        if (!dragEnabled) return@detectHorizontalDragGestures
                        isDrawerDragging = false
                        dragEnabled = false
                        val velocityX = velocityTracker.calculateVelocity().x
                        val shouldOpen = when {
                            velocityX > flingVelocityThresholdPx -> true
                            velocityX < -flingVelocityThresholdPx -> false
                            else -> drawerOffset.value > -drawerWidthPx / 2f ||
                                (drawerProgress > 0.2f && drawerOffset.value > -drawerWidthPx + minSwipeDistancePx)
                        }
                        showConversationList = shouldOpen
                    },
                    onDragCancel = {
                        if (dragEnabled) {
                            isDrawerDragging = false
                            dragEnabled = false
                        }
                    }
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        // ── Fixed Top Bar (hidden on Workbench pages) ──
        if (currentPage != Screen.WorkbenchDetail.route && currentPage != Screen.Workbench.route) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (currentPage) {
                Screen.Chat.route -> {
                    IconButton(onClick = { showConversationList = !showConversationList }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "会话历史")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { currentPage = Screen.Workbench.route }) {
                        Icon(Icons.Outlined.GridView, contentDescription = "工作台")
                    }
                }
                else -> {
                    val title = when (currentPage) {
                        Screen.Workbench.route -> Screen.Workbench.title
                        Screen.Apps.route -> Screen.Apps.title
                        Screen.Settings.route -> Screen.Settings.title
                        Screen.ModelManage.route -> Screen.ModelManage.title
                        Screen.ModelEdit("").route -> Screen.ModelEdit("").title
                        Screen.SkillSettings.route -> Screen.SkillSettings.title
                        Screen.MemorySettings.route -> Screen.MemorySettings.title
                        Screen.ModuleSettings.route -> Screen.ModuleSettings.title
                        Screen.CredentialSettings.route -> Screen.CredentialSettings.title
                        Screen.ShareSettings.route -> Screen.ShareSettings.title
                        else -> ""
                    }
                    val backTarget = when (currentPage) {
                        Screen.ModelManage.route,
                        Screen.SkillSettings.route,
                        Screen.MemorySettings.route,
                        Screen.ModuleSettings.route,
                        Screen.CredentialSettings.route,
                        Screen.ShareSettings.route -> Screen.Settings.route
                        Screen.ModelEdit("").route -> Screen.ModelManage.route
                        else -> Screen.Chat.route
                    }
                    IconButton(onClick = { currentPage = backTarget }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        }

        // ── Content Area (fills remaining space) ──
        Box(modifier = Modifier.fillMaxSize()) {
            // Refresh model list when returning to chat
            if (currentPage == Screen.Chat.route) {
                LaunchedEffect(Unit) { chatViewModel.refreshModels() }
            }
            when (currentPage) {
                Screen.Chat.route -> ChatScreen(
                    viewModel = chatViewModel,
                    onRunApp = { app ->
                        chatViewModel.setCurrentApp(app)
                        currentPage = Screen.MiniApp.route
                    },
                    onEnterSshMode = { sessionId ->
                        sshViewModel.enterSession(sessionId)
                        currentPage = Screen.SshMode.route
                    },
                    onNavigateWorkbench = { req ->
                        val taskId = workbenchViewModel.createAndRun(
                            title = req.title,
                            userPrompt = req.userPrompt,
                            modelId = req.modelId,
                            enableThinking = req.enableThinking,
                            appId = req.appId
                        )
                        activeDetailTaskId = taskId
                        workbenchViewModel.setActiveTask(taskId)
                        currentPage = Screen.WorkbenchDetail.route
                    }
                )
                Screen.Workbench.route -> {
                    LaunchedEffect(Unit) { workbenchViewModel.reload() }
                    WorkbenchListScreen(
                        viewModel = workbenchViewModel,
                        onBack = { currentPage = Screen.Chat.route },
                        onOpenTask = { task ->
                            activeDetailTaskId = task.id
                            workbenchViewModel.setActiveTask(task.id)
                            currentPage = Screen.WorkbenchDetail.route
                        },
                        onNewTask = {
                            currentPage = Screen.Chat.route
                        }
                    )
                }
                Screen.WorkbenchDetail.route -> {
                    WorkbenchDetailScreen(
                        viewModel = workbenchViewModel,
                        taskId = activeDetailTaskId,
                        onBack = { currentPage = Screen.Workbench.route },
                        onRunApp = { appId ->
                            val app = workbenchViewModel.loadMiniApp(appId)
                            if (app != null) {
                                chatViewModel.setCurrentApp(app)
                                currentPage = Screen.MiniApp.route
                            } else {
                                Toast.makeText(context, "应用不存在或未完成生成", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRetry = { taskId ->
                            workbenchViewModel.runTask(taskId)
                        },
                        onExport = { appId ->
                            val app = workbenchViewModel.loadMiniApp(appId)
                            if (app != null) {
                                exportMiniApp(context, app)
                            } else {
                                Toast.makeText(context, "应用不存在", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                Screen.Apps.route -> MiniAppListScreen(
                    storage = chatViewModel.miniAppStorage,
                    onRunApp = { app ->
                        chatViewModel.setCurrentApp(app)
                        currentPage = Screen.MiniApp.route
                    }
                )
                Screen.Settings.route -> SettingsScreen(
                    onNavigate = { route -> currentPage = route }
                )
                Screen.ModelManage.route -> ModelManageScreen(
                    onNavigateEdit = { modelId ->
                        editModelId = modelId
                        currentPage = Screen.ModelEdit("").route
                    }
                )
                Screen.ModelEdit("").route -> ModelEditScreen(
                    modelId = editModelId,
                    onBack = { currentPage = Screen.ModelManage.route }
                )
                Screen.SkillSettings.route -> SkillListScreen(
                    skillStorage = chatViewModel.skillStorage,
                    activeSkillId = chatViewModel.activeSkill.value.id,
                    onSelectSkill = { skill -> chatViewModel.setActiveSkill(skill) }
                )
                Screen.MemorySettings.route -> MemoryScreen()
                Screen.ModuleSettings.route -> ModuleScreen()
                Screen.CredentialSettings.route -> CredentialScreen()
                Screen.ShareSettings.route -> ShareScreen()
            }
        }
    } // end Column

        // ── 会话历史列表覆盖层（跟手拖拽） ──
        if (currentPage == Screen.Chat.route && drawerProgress > 0f) {
            if (scrimProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f * scrimProgress))
                        .clickable { showConversationList = false }
                )
            }

            ConversationListPanel(
                modifier = Modifier.offset { IntOffset(drawerOffset.value.roundToInt(), 0) },
                chatViewModel = chatViewModel,
                onSelect = { convId ->
                    chatViewModel.switchConversation(convId)
                    showConversationList = false
                },
                onDelete = { convId -> chatViewModel.deleteConversation(convId) },
                onNewConversation = {
                    chatViewModel.newConversation()
                    showConversationList = false
                },
                onNavigateSettings = {
                    showConversationList = false
                    currentPage = Screen.Settings.route
                }
            )
        }
    } // end Box
}

@Composable
private fun ConversationListPanel(
    modifier: Modifier = Modifier,
    chatViewModel: ChatViewModel,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewConversation: () -> Unit,
    onNavigateSettings: () -> Unit
) {
    val conversations by chatViewModel.conversations.collectAsState()
    val activeConvId by chatViewModel.activeConversationId.collectAsState()

    Box(modifier = modifier.fillMaxHeight()) {
        Surface(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 上部：会话列表 (90%) ──
                Column(modifier = Modifier.weight(0.9f)) {
                    Text(
                        text = "会话历史",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    if (conversations.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无历史会话",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(conversations, key = { it.id }) { conv ->
                                val isActive = conv.id == activeConvId
                                Card(
                                    onClick = { onSelect(conv.id) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isActive)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = conv.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = formatTime(conv.updatedAt),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                        if (!isActive) {
                                            IconButton(
                                                onClick = { onDelete(conv.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Delete,
                                                    contentDescription = "删除",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 下部：功能图标栏 (10%) ──
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Row(
                    modifier = Modifier
                        .weight(0.1f)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 新建会话
                    Surface(
                        onClick = onNewConversation,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "新对话",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "新对话",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    // 设置
                    Surface(
                        onClick = onNavigateSettings,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = "设置",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "设置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
