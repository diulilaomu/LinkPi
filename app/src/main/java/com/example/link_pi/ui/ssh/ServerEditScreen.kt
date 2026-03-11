package com.example.link_pi.ui.ssh

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.link_pi.data.Credential
import com.example.link_pi.data.CredentialStorage
import com.example.link_pi.data.SavedServer
import com.example.link_pi.data.SavedServerStorage
import com.example.link_pi.ui.theme.*

@Composable
fun ServerEditScreen(
    serverId: String?,   // null = new
    serverStorage: SavedServerStorage,
    credentialStorage: CredentialStorage,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var selectedCredId by remember { mutableStateOf("") }
    var selectedCredName by remember { mutableStateOf("") }
    var credentials by remember { mutableStateOf<List<Credential>>(emptyList()) }
    var credDropdownExpanded by remember { mutableStateOf(false) }
    val isNew = serverId == null

    val colors = termFieldColors()

    LaunchedEffect(Unit) {
        credentials = credentialStorage.loadAll()
        if (!isNew) {
            val server = serverStorage.findById(serverId!!)
            if (server != null) {
                name = server.name
                host = server.host
                port = server.port.toString()
                selectedCredId = server.credentialId
                selectedCredName = server.credentialName
            }
        }
    }

    Surface(color = TermBg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storage, null, tint = TermYellow, modifier = Modifier.height(20.dp).width(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isNew) "添加服务器" else "编辑服务器",
                    style = TextStyle(fontFamily = MonoFont, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TermText)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = TermCard,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TermBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
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

                    // Host + Port row
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.trim() },
                            label = { Text("主机地址") },
                            placeholder = { Text("IP 或域名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = colors,
                            textStyle = TermFieldStyle,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )
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
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Credential selector
                    Box {
                        OutlinedTextField(
                            value = if (selectedCredId.isNotBlank()) selectedCredName.take(5) else "",
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
                            onDismissRequest = { credDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("不使用凭据", color = TermDim) },
                                onClick = {
                                    selectedCredId = ""
                                    selectedCredName = ""
                                    credDropdownExpanded = false
                                }
                            )
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
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

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
                        serverStorage.findById(serverId!!)?.copy(
                            name = name.trim(),
                            host = trimmedHost,
                            port = portNum,
                            credentialId = selectedCredId,
                            credentialName = selectedCredName
                        ) ?: return@Surface
                    }
                    serverStorage.save(server)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (host.trim().isNotBlank()) TermGreen else TermBorder
            ) {
                Text(
                    if (isNew) "添加" else "保存",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    style = TextStyle(fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TermBg),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
