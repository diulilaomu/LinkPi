package com.example.link_pi.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.link_pi.agent.ModuleStorage
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.data.model.Skill
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.share.ConnectionState
import com.example.link_pi.share.ReceivedItem
import com.example.link_pi.share.ShareService
import com.example.link_pi.skill.SkillStorage

private enum class ShareTab(val label: String) {
    SKILLS("Skill"), MODULES("模块"), APPS("应用")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen() {
    val context = LocalContext.current
    val shareService = remember { ShareService(context) }
    val connectionState by shareService.connectionState.collectAsState()
    val discoveredDevices by shareService.discoveredDevices.collectAsState()
    val receivedItems by shareService.receivedItems.collectAsState()
    val sentItems by shareService.sentItems.collectAsState()

    DisposableEffect(Unit) {
        shareService.startDiscovery()
        onDispose { shareService.shutdown() }
    }

    when (val state = connectionState) {
        is ConnectionState.Scanning -> ScanningView(
            localIp = shareService.localIp,
            pin = shareService.pin,
            devices = discoveredDevices,
            onConnect = { ip, pin -> shareService.connectTo(ip, pin) }
        )

        is ConnectionState.Connecting -> ConnectingView(ip = state.ip)

        is ConnectionState.IncomingRequest -> IncomingRequestDialog(
            ip = state.ip,
            onAccept = { shareService.acceptConnection() },
            onReject = { shareService.rejectConnection() }
        )

        is ConnectionState.Connected -> ConnectedView(
            peerIp = state.peerIp,
            shareService = shareService,
            sentItems = sentItems,
            receivedItems = receivedItems,
            onFinish = { shareService.finishSharing() }
        )

        is ConnectionState.Finished -> ResultsView(
            sentNames = state.sentNames,
            receivedItems = state.receivedItems
        )
    }
}

// ══════════════════════════════════════
//  Scanning - discover devices
// ══════════════════════════════════════

@Composable
private fun ScanningView(
    localIp: String,
    pin: String,
    devices: List<String>,
    onConnect: (String, String) -> Unit
) {
    var pinDialogIp by remember { mutableStateOf<String?>(null) }
    var pinInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Local IP + PIN info
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Wifi, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("本机地址", style = MaterialTheme.typography.labelMedium)
                    Text(localIp, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("配对码", style = MaterialTheme.typography.labelMedium)
                    Text(pin, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Scanning indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "正在搜索局域网设备…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "等待其他设备打开分享…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { ip ->
                    DeviceCard(ip = ip, onClick = {
                        pinInput = ""
                        pinDialogIp = ip
                    })
                }
            }
        }
    }

    // PIN input dialog
    pinDialogIp?.let { ip ->
        AlertDialog(
            onDismissRequest = { pinDialogIp = null },
            title = { Text("输入对方配对码") },
            text = {
                Column {
                    Text("请输入 $ip 屏幕上显示的 4 位配对码")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pinInput = it },
                        label = { Text("配对码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pinInput.length == 4) {
                            onConnect(ip, pinInput)
                            pinDialogIp = null
                        }
                    },
                    enabled = pinInput.length == 4
                ) { Text("连接") }
            },
            dismissButton = {
                TextButton(onClick = { pinDialogIp = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DeviceCard(ip: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.PhoneAndroid, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ip, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "点击连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Outlined.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

// ══════════════════════════════════════
//  Connecting
// ══════════════════════════════════════

@Composable
private fun ConnectingView(ip: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在连接 $ip …", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ══════════════════════════════════════
//  Incoming request dialog
// ══════════════════════════════════════

@Composable
private fun IncomingRequestDialog(
    ip: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AlertDialog(
            onDismissRequest = onReject,
            icon = { Icon(Icons.Outlined.DevicesOther, contentDescription = null) },
            title = { Text("连接请求") },
            text = { Text("$ip 请求与你建立分享连接") },
            confirmButton = {
                TextButton(onClick = onAccept) { Text("接受") }
            },
            dismissButton = {
                TextButton(onClick = onReject) { Text("拒绝") }
            }
        )
    }
}

// ══════════════════════════════════════
//  Connected - share page with tabs
// ══════════════════════════════════════

@Composable
private fun ConnectedView(
    peerIp: String,
    shareService: ShareService,
    sentItems: List<String>,
    receivedItems: List<ReceivedItem>,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = ShareTab.entries

    // Load data
    val skills = remember { SkillStorage(context).loadAll().filter { !it.isBuiltIn } }
    val modules = remember { ModuleStorage(context).loadAll() }
    val apps = remember { MiniAppStorage(context).loadAll() }

    // Selection state
    val selectedSkills = remember { mutableStateMapOf<String, Boolean>() }
    val selectedModules = remember { mutableStateMapOf<String, Boolean>() }
    val selectedApps = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Peer info bar
        Card(
            shape = RoundedCornerShape(0.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Link, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "已连接: $peerIp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                if (sentItems.isNotEmpty()) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("已发 ${sentItems.size}") },
                        modifier = Modifier.height(28.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (receivedItems.isNotEmpty()) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("已收 ${receivedItems.size}") },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        // Tabs
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(tab.label) }
                )
            }
        }

        // Tab content
        Box(modifier = Modifier.weight(1f)) {
            when (tabs[selectedTab]) {
                ShareTab.SKILLS -> SkillsList(skills, selectedSkills)
                ShareTab.MODULES -> ModulesList(modules, selectedModules)
                ShareTab.APPS -> AppsList(apps, selectedApps)
            }
        }

        // Bottom action bar
        val selectedCount = when (tabs[selectedTab]) {
            ShareTab.SKILLS -> selectedSkills.count { it.value }
            ShareTab.MODULES -> selectedModules.count { it.value }
            ShareTab.APPS -> selectedApps.count { it.value }
        }

        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("结束分享")
                }
                Button(
                    onClick = {
                        val items = mutableListOf<Pair<String, String>>()
                        selectedSkills.filter { it.value }.keys.forEach { id ->
                            skills.find { it.id == id }?.let {
                                items.add("skill" to shareService.skillToJson(it))
                            }
                        }
                        selectedModules.filter { it.value }.keys.forEach { id ->
                            modules.find { it.id == id }?.let {
                                items.add("module" to ModuleStorage(context).exportToJson(it))
                            }
                        }
                        selectedApps.filter { it.value }.keys.forEach { id ->
                            apps.find { it.id == id }?.let {
                                items.add("app" to shareService.appToJson(it))
                            }
                        }
                        if (items.isNotEmpty()) {
                            shareService.shareItems(items)
                            selectedSkills.clear()
                            selectedModules.clear()
                            selectedApps.clear()
                        }
                    },
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("发送选中 ($selectedCount)")
                }
            }
        }
    }
}

// ── Item lists ──

@Composable
private fun SkillsList(
    skills: List<Skill>,
    selected: MutableMap<String, Boolean>
) {
    if (skills.isEmpty()) {
        EmptyHint("暂无自定义 Skill")
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(skills, key = { it.id }) { skill ->
                ShareItemRow(
                    icon = skill.icon,
                    title = skill.name,
                    subtitle = skill.description,
                    checked = selected[skill.id] == true,
                    onCheckedChange = { selected[skill.id] = it }
                )
            }
        }
    }
}

@Composable
private fun ModulesList(
    modules: List<ModuleStorage.Module>,
    selected: MutableMap<String, Boolean>
) {
    if (modules.isEmpty()) {
        EmptyHint("暂无模块")
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(modules, key = { it.id }) { module ->
                ShareItemRow(
                    icon = "📦",
                    title = module.name,
                    subtitle = module.description,
                    checked = selected[module.id] == true,
                    onCheckedChange = { selected[module.id] = it }
                )
            }
        }
    }
}

@Composable
private fun AppsList(
    apps: List<MiniApp>,
    selected: MutableMap<String, Boolean>
) {
    if (apps.isEmpty()) {
        EmptyHint("暂无应用")
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(apps, key = { it.id }) { app ->
                ShareItemRow(
                    icon = "📱",
                    title = app.name,
                    subtitle = app.description,
                    checked = selected[app.id] == true,
                    onCheckedChange = { selected[app.id] = it }
                )
            }
        }
    }
}

@Composable
private fun ShareItemRow(
    icon: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

// ══════════════════════════════════════
//  Results view
// ══════════════════════════════════════

@Composable
private fun ResultsView(
    sentNames: List<String>,
    receivedItems: List<ReceivedItem>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Sent section
        item {
            Text("已发送", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (sentNames.isEmpty()) {
            item {
                Text(
                    "无", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(sentNames) { name ->
                ResultRow(icon = "↑", name = name, success = true)
            }
        }

        // Received section
        item {
            Spacer(Modifier.height(8.dp))
            Text("已接收", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (receivedItems.isEmpty()) {
            item {
                Text(
                    "无", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(receivedItems) { item ->
                val label = when (item.category) {
                    "skill" -> "Skill"
                    "module" -> "模块"
                    "app" -> "应用"
                    else -> item.category
                }
                ResultRow(
                    icon = if (item.success) "✅" else "❌",
                    name = "${item.name} ($label)",
                    success = item.success
                )
            }
        }
    }
}

@Composable
private fun ResultRow(icon: String, name: String, success: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (success) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error
        )
    }
}
