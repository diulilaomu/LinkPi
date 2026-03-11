package com.example.link_pi.ui.ssh

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.link_pi.agent.SshManager
import com.example.link_pi.data.Credential
import com.example.link_pi.data.SavedServer
import com.example.link_pi.ui.theme.*

@Composable
fun SshHomeScreen(
    viewModel: SshHomeViewModel,
    onBack: () -> Unit,
    onEnterSession: (sessionId: String, manualMode: Boolean) -> Unit
) {
    val activeSessions by viewModel.activeSessions.collectAsState()
    val savedServers by viewModel.savedServers.collectAsState()
    val credentials by viewModel.credentials.collectAsState()
    val connectingId by viewModel.connectingId.collectAsState()
    val connectError by viewModel.connectError.collectAsState()
    val connectedSessionId by viewModel.connectedSessionId.collectAsState()
    val fallbackManual by viewModel.fallbackManual.collectAsState()

    // Section collapse state
    var activeSessionsExpanded by remember { mutableStateOf(true) }
    var savedServersExpanded by remember { mutableStateOf(true) }
    var quickConnectExpanded by remember { mutableStateOf(true) }
    var credentialsExpanded by remember { mutableStateOf(true) }

    // Delete confirmation
    var deleteServerId by remember { mutableStateOf<String?>(null) }
    var deleteCredentialId by remember { mutableStateOf<String?>(null) }

    // Credential creation/editing
    var showCredentialForm by remember { mutableStateOf(false) }
    var editingCredential by remember { mutableStateOf<Credential?>(null) }

    // Server editing dialog: "" = new, non-null = editing that ID, null = hidden
    var editingServerId by remember { mutableStateOf<String?>(null) }
    var showServerEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Handle connection result
    LaunchedEffect(connectedSessionId) {
        val sid = connectedSessionId ?: return@LaunchedEffect
        onEnterSession(sid, fallbackManual)
        viewModel.clearConnectResult()
    }

    // Error dialog
    if (connectError != null && connectedSessionId == null) {
        TermConfirmDialog(
            title = "连接失败",
            message = connectError ?: "",
            onConfirm = { viewModel.clearConnectResult() },
            onDismiss = { viewModel.clearConnectResult() },
            confirmText = "确定",
            dismissText = null,
            confirmColor = TermGreen
        )
    }

    // Delete server dialog
    if (deleteServerId != null) {
        TermConfirmDialog(
            title = "删除服务器",
            message = "确定要删除这个保存的服务器吗？",
            onConfirm = {
                viewModel.deleteServer(deleteServerId!!)
                deleteServerId = null
            },
            onDismiss = { deleteServerId = null },
            confirmText = "删除"
        )
    }

    // Delete credential dialog
    if (deleteCredentialId != null) {
        TermConfirmDialog(
            title = "删除凭据",
            message = "确定要删除这个凭据吗？",
            onConfirm = {
                viewModel.deleteCredential(deleteCredentialId!!)
                deleteCredentialId = null
            },
            onDismiss = { deleteCredentialId = null },
            confirmText = "删除"
        )
    }

    // Server edit dialog
    if (showServerEditDialog) {
        ServerEditDialog(
            serverId = editingServerId,
            credentials = credentials,
            viewModel = viewModel,
            onDismiss = {
                showServerEditDialog = false
                editingServerId = null
            }
        )
    }

    Surface(color = TermBg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ═══════════ Section 1: Active Sessions ═══════════
            SectionHeader(
                icon = { Icon(Icons.Filled.Link, null, tint = TermGreen, modifier = Modifier.size(18.dp)) },
                title = "活跃会话",
                badge = if (activeSessions.isNotEmpty()) activeSessions.size.toString() else null,
                expanded = activeSessionsExpanded,
                onToggle = { activeSessionsExpanded = !activeSessionsExpanded }
            )

            AnimatedVisibility(
                visible = activeSessionsExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                if (activeSessions.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        color = TermCard,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, TermBorder)
                    ) {
                        Text(
                            "暂无活跃会话",
                            modifier = Modifier.padding(16.dp),
                            style = TextStyle(fontSize = 13.sp, color = TermDim)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        activeSessions.forEach { session ->
                            ActiveSessionCard(
                                session = session,
                                isConnecting = connectingId == session.sessionId,
                                onClick = { viewModel.reenterSession(session.sessionId) }
                            )
                        }
                    }
                }
            }

            // ═══════════ Section 2: Saved Servers ═══════════
            SectionHeader(
                icon = { Icon(Icons.Outlined.Storage, null, tint = TermYellow, modifier = Modifier.size(18.dp)) },
                title = "常用服务器",
                badge = if (savedServers.isNotEmpty()) savedServers.size.toString() else null,
                expanded = savedServersExpanded,
                onToggle = { savedServersExpanded = !savedServersExpanded },
                action = {
                    IconButton(onClick = {
                        editingServerId = null
                        showServerEditDialog = true
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Add, "添加服务器", modifier = Modifier.size(18.dp), tint = TermGreen)
                    }
                }
            )

            AnimatedVisibility(
                visible = savedServersExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                if (savedServers.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        color = TermCard,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, TermBorder)
                    ) {
                        Text(
                            "暂无保存的服务器",
                            modifier = Modifier.padding(16.dp),
                            style = TextStyle(fontSize = 13.sp, color = TermDim)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        savedServers.forEach { server ->
                            SavedServerCard(
                                server = server,
                                isConnecting = connectingId == server.id,
                                onClick = { viewModel.connectToServer(server) },
                                onEdit = {
                                    editingServerId = server.id
                                    showServerEditDialog = true
                                },
                                onDelete = { deleteServerId = server.id }
                            )
                        }
                    }
                }
            }

            // ═══════════ Section 3: Quick Connect ═══════════
            SectionHeader(
                icon = { Icon(Icons.Outlined.FlashOn, null, tint = TermCyan, modifier = Modifier.size(18.dp)) },
                title = "快速连接",
                expanded = quickConnectExpanded,
                onToggle = { quickConnectExpanded = !quickConnectExpanded }
            )

            AnimatedVisibility(
                visible = quickConnectExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                QuickConnectSection(
                    credentials = credentials,
                    isConnecting = connectingId == "quick",
                    onConnect = { host, port, credId ->
                        viewModel.quickConnect(host, port, credId)
                    }
                )
            }

            // ═══════════ Section 4: Credentials ═══════════
            SectionHeader(
                icon = { Icon(Icons.Outlined.VpnKey, null, tint = TermYellow, modifier = Modifier.size(18.dp)) },
                title = "凭据管理",
                badge = if (credentials.isNotEmpty()) credentials.size.toString() else null,
                expanded = credentialsExpanded,
                onToggle = { credentialsExpanded = !credentialsExpanded },
                action = {
                    IconButton(
                        onClick = {
                            editingCredential = null
                            showCredentialForm = true
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Add, "添加凭据", modifier = Modifier.size(18.dp), tint = TermGreen)
                    }
                }
            )

            AnimatedVisibility(
                visible = credentialsExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    // Inline credential form
                    AnimatedVisibility(
                        visible = showCredentialForm,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        CredentialEditCard(
                            initial = editingCredential,
                            onSave = { cred ->
                                viewModel.saveCredential(cred)
                                showCredentialForm = false
                                editingCredential = null
                            },
                            onCancel = {
                                showCredentialForm = false
                                editingCredential = null
                            }
                        )
                    }

                    if (credentials.isEmpty() && !showCredentialForm) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = TermCard,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, TermBorder)
                        ) {
                            Text(
                                "暂无凭据",
                                modifier = Modifier.padding(16.dp),
                                style = TextStyle(fontSize = 13.sp, color = TermDim)
                            )
                        }
                    } else {
                        credentials.forEach { cred ->
                            CredentialCard(
                                credential = cred,
                                onEdit = {
                                    editingCredential = cred
                                    showCredentialForm = true
                                },
                                onDelete = { deleteCredentialId = cred.id }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── Section Header ──

@Composable
private fun SectionHeader(
    icon: @Composable () -> Unit,
    title: String,
    badge: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            title,
            style = TextStyle(
                fontFamily = MonoFont,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TermText
            )
        )
        if (badge != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TermGreen.copy(alpha = 0.15f),
                modifier = Modifier.height(20.dp)
            ) {
                Text(
                    badge,
                    modifier = Modifier.padding(horizontal = 7.dp),
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TermGreen)
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (action != null) action()
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
            modifier = Modifier.size(20.dp),
            tint = TermDim
        )
    }
}

// ── Active Session Card ──

@Composable
private fun ActiveSessionCard(
    session: SshManager.SshSessionInfo,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = TermCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TermBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Computer,
                contentDescription = null,
                tint = TermGreen,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${session.username}@${session.host}",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TermText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    ":${session.port}  ·  ${formatSessionAge(session.createdAt)}",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermDim)
                )
            }
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = TermGreen)
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TermGreen.copy(alpha = 0.15f)
                ) {
                    Text(
                        "进入",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = TextStyle(fontFamily = MonoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TermGreen)
                    )
                }
            }
        }
    }
}

// ── Saved Server Card ──

@Composable
private fun SavedServerCard(
    server: SavedServer,
    isConnecting: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = TermCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TermBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Storage,
                contentDescription = null,
                tint = TermYellow,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name.ifBlank { server.host },
                    style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TermText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${server.host}:${server.port}" +
                            if (server.credentialName.isNotBlank()) "  ·  ${server.credentialName}" else "",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = TermGreen)
            } else {
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Edit, "编辑", modifier = Modifier.size(16.dp), tint = TermDim)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(16.dp), tint = TermRed.copy(alpha = 0.6f))
                }
            }
        }
    }
}

// ── Quick Connect Section ──

@Composable
private fun QuickConnectSection(
    credentials: List<Credential>,
    isConnecting: Boolean,
    onConnect: (host: String, port: Int, credentialId: String) -> Unit
) {
    var hostInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("22") }
    var selectedCredId by remember { mutableStateOf("") }
    var credDropdownExpanded by remember { mutableStateOf(false) }

    val selectedCred = credentials.find { it.id == selectedCredId }
    val colors = termFieldColors()

    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        color = TermCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TermBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Host on its own row
            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it.trim() },
                label = { Text("主机地址") },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = colors,
                textStyle = TextStyle(fontFamily = MonoFont, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Port + Credential on same row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.width(80.dp),
                    colors = colors,
                    textStyle = TextStyle(fontFamily = MonoFont, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    )
                )
                // Credential selector
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = selectedCred?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("凭据") },
                        placeholder = { Text("选择凭据") },
                        colors = colors,
                        textStyle = TextStyle(fontFamily = MonoFont, fontSize = 13.sp),
                        enabled = false
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { credDropdownExpanded = true }
                    )
                    DropdownMenu(
                        expanded = credDropdownExpanded,
                        onDismissRequest = { credDropdownExpanded = false },
                        containerColor = TermCard,
                        border = BorderStroke(1.dp, TermBorder)
                    ) {
                        credentials.forEach { cred ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(cred.name, fontWeight = FontWeight.Medium, color = TermText)
                                        if (cred.username.isNotBlank()) {
                                            Text(cred.username, style = TextStyle(fontSize = 12.sp, color = TermDim))
                                        }
                                    }
                                },
                                onClick = {
                                    selectedCredId = cred.id
                                    credDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Connect button
            Surface(
                onClick = {
                    if (hostInput.isNotBlank() && !isConnecting) {
                        val port = portInput.toIntOrNull() ?: 22
                        onConnect(hostInput, port, selectedCredId)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (hostInput.isNotBlank() && !isConnecting) TermGreen else TermBorder
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = TermBg
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("连接中...", style = TextStyle(fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TermBg))
                    } else {
                        Icon(Icons.Outlined.FlashOn, null, modifier = Modifier.size(16.dp), tint = TermBg)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("连接", style = TextStyle(fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TermBg))
                    }
                }
            }
        }
    }
}

// ── Credential Card ──

@Composable
private fun CredentialCard(
    credential: Credential,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TermCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TermBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.VpnKey,
                contentDescription = null,
                tint = TermYellow,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    credential.name.ifBlank { "未命名" },
                    style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TermText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    credential.username.ifBlank { "-" } + "  ·  " + "••••••••",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = TermDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Edit, "编辑", modifier = Modifier.size(16.dp), tint = TermDim)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(16.dp), tint = TermRed.copy(alpha = 0.6f))
            }
        }
    }
}

// ── Credential Edit Card (inline form) ──

@Composable
private fun CredentialEditCard(
    initial: Credential?,
    onSave: (Credential) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var username by remember(initial) { mutableStateOf(initial?.username ?: "") }
    var secret by remember(initial) { mutableStateOf(initial?.secret ?: "") }
    var secretVisible by remember { mutableStateOf(false) }

    val colors = termFieldColors()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TermSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TermGreen.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (initial == null) "新建凭据" else "编辑凭据",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TermGreen)
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, "取消", modifier = Modifier.size(16.dp), tint = TermDim)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("凭据名称") },
                placeholder = { Text("如: 生产服务器") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = colors,
                textStyle = TextStyle(fontFamily = MonoFont, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                placeholder = { Text("root") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = colors,
                textStyle = TextStyle(fontFamily = MonoFont, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = colors,
                textStyle = TextStyle(fontFamily = MonoFont, fontSize = 14.sp),
                visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { secretVisible = !secretVisible }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            if (secretVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (secretVisible) "隐藏" else "显示",
                            modifier = Modifier.size(18.dp),
                            tint = TermDim
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                onClick = {
                    if (name.isNotBlank() && username.isNotBlank()) {
                        val cred = (initial ?: Credential()).copy(
                            name = name.trim(),
                            username = username.trim(),
                            secret = secret
                        )
                        onSave(cred)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (name.isNotBlank() && username.isNotBlank()) TermGreen else TermBorder
            ) {
                Text(
                    "保存",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    style = TextStyle(fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TermBg),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

// ── Server Edit Dialog ──

@Composable
private fun ServerEditDialog(
    serverId: String?,
    credentials: List<Credential>,
    viewModel: SshHomeViewModel,
    onDismiss: () -> Unit
) {
    val isNew = serverId == null
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var selectedCredId by remember { mutableStateOf("") }
    var selectedCredName by remember { mutableStateOf("") }
    var credDropdownExpanded by remember { mutableStateOf(false) }

    val colors = termFieldColors()

    LaunchedEffect(serverId) {
        if (serverId != null) {
            val server = viewModel.findServer(serverId)
            if (server != null) {
                name = server.name
                host = server.host
                port = server.port.toString()
                selectedCredId = server.credentialId
                selectedCredName = server.credentialName
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TermCard,
        confirmButton = {},
        text = {
            Column {
                // Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Storage, null, tint = TermYellow, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isNew) "添加服务器" else "编辑服务器",
                        style = TextStyle(fontFamily = MonoFont, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TermText)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(16.dp), tint = TermDim)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    placeholder = { Text("例: 生产服务器") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = colors,
                    textStyle = TermFieldStyle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Host - own line
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("主机地址") },
                    placeholder = { Text("IP 或域名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = colors,
                    textStyle = TermFieldStyle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Port + Credential on same row
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.width(80.dp),
                        colors = colors,
                        textStyle = TermFieldStyle,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        )
                    )
                    // Credential selector
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = if (selectedCredId.isNotBlank()) selectedCredName else "",
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("关联凭据") },
                            placeholder = { Text("选择凭据") },
                            colors = colors,
                            textStyle = TermFieldStyle,
                            enabled = false
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { credDropdownExpanded = true }
                        )
                        DropdownMenu(
                            expanded = credDropdownExpanded,
                            onDismissRequest = { credDropdownExpanded = false },
                            containerColor = TermCard,
                            border = BorderStroke(1.dp, TermBorder)
                        ) {
                            credentials.forEach { cred ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(cred.name, fontWeight = FontWeight.Medium, color = TermText)
                                            if (cred.username.isNotBlank()) {
                                                Text(cred.username, style = TextStyle(fontSize = 12.sp, color = TermDim))
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedCredId = cred.id
                                        selectedCredName = cred.name
                                        credDropdownExpanded = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Save button
                Surface(
                    onClick = {
                        val trimmedHost = host.trim()
                        if (trimmedHost.isBlank()) return@Surface
                        val portNum = (port.toIntOrNull() ?: 22).coerceIn(1, 65535)

                        val server = if (isNew) {
                            SavedServer(
                                name = name.trim(),
                                host = trimmedHost,
                                port = portNum,
                                credentialId = selectedCredId,
                                credentialName = selectedCredName
                            )
                        } else {
                            viewModel.findServer(serverId!!)?.copy(
                                name = name.trim(),
                                host = trimmedHost,
                                port = portNum,
                                credentialId = selectedCredId,
                                credentialName = selectedCredName
                            ) ?: return@Surface
                        }
                        viewModel.saveServer(server)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = if (host.trim().isNotBlank()) TermGreen else TermBorder
                ) {
                    Text(
                        if (isNew) "添加" else "保存",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
                        style = TextStyle(fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TermBg),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    )
}

private fun formatSessionAge(createdAt: Long): String {
    val minutes = (System.currentTimeMillis() - createdAt) / 60_000
    return when {
        minutes < 1 -> "刚刚连接"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 1440 -> "${minutes / 60}小时前"
        else -> "${minutes / 1440}天前"
    }
}
