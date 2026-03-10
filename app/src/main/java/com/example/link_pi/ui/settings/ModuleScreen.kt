package com.example.link_pi.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.link_pi.agent.ModuleStorage

@Composable
fun ModuleScreen() {
    val context = LocalContext.current
    val moduleStorage = remember { ModuleStorage(context) }
    var modules by remember { mutableStateOf(moduleStorage.loadAll()) }
    var deleteTarget by remember { mutableStateOf<ModuleStorage.Module?>(null) }
    var editTarget by remember { mutableStateOf<ModuleStorage.Module?>(null) }
    var exportTarget by remember { mutableStateOf<ModuleStorage.Module?>(null) }

    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val jsonStr = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@rememberLauncherForActivityResult
            val imported = moduleStorage.importFromJson(jsonStr)
            if (imported != null) {
                modules = moduleStorage.loadAll()
                Toast.makeText(context, "已导入: ${imported.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "导入失败: JSON 格式无效", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Export launcher
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val m = exportTarget ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(moduleStorage.exportToJson(m).toByteArray())
            }
            Toast.makeText(context, "已导出: ${m.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        exportTarget = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "AI 创建的 API 模块，可供小应用调用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                Text("导入")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (modules.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "暂无模块",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "在聊天中让 AI 创建 API 模块，或点击右上角导入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(modules, key = { it.id }) { module ->
                    ModuleCard(
                        module = module,
                        onEdit = if (module.isBuiltIn) null else {{ editTarget = module }},
                        onDelete = if (module.isBuiltIn) null else {{ deleteTarget = module }},
                        onExport = if (module.isBuiltIn) null else {{
                            exportTarget = module
                            exportLauncher.launch("${module.name}.json")
                        }}
                    )
                }
            }
        }
    }

    // ── Delete dialog ──
    deleteTarget?.let { module ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模块") },
            text = { Text("确定删除「${module.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    moduleStorage.delete(module.id)
                    modules = moduleStorage.loadAll()
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    // ── Edit dialog ──
    editTarget?.let { module ->
        EditModuleDialog(
            module = module,
            onDismiss = { editTarget = null },
            onSave = { name, desc, baseUrl, instructions ->
                moduleStorage.updateModule(
                    module.id,
                    name = name.takeIf { it.isNotBlank() },
                    description = desc.takeIf { it.isNotBlank() },
                    baseUrl = baseUrl.takeIf { it.isNotBlank() },
                    instructions = instructions
                )
                modules = moduleStorage.loadAll()
                editTarget = null
            }
        )
    }
}

@Composable
private fun EditModuleDialog(
    module: ModuleStorage.Module,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, baseUrl: String, instructions: String) -> Unit
) {
    var name by remember { mutableStateOf(module.name) }
    var description by remember { mutableStateOf(module.description) }
    var baseUrl by remember { mutableStateOf(module.baseUrl) }
    var instructions by remember { mutableStateOf(module.instructions) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑模块") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("基础 URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("使用说明 (AI 可读)") },
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description, baseUrl, instructions) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ModuleCard(
    module: ModuleStorage.Module,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onExport: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth().let { m ->
            if (onEdit != null) m.clickable(onClick = onEdit) else m
        },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            module.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (module.protocol != "HTTP") {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    module.protocol,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                    Text(
                        module.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${module.endpoints.size} 端点",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (module.isBuiltIn) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "内置",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    if (onEdit != null) {
                        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "编辑",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }
                    }
                    if (onExport != null) {
                        IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = "导出",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            if (module.description.isNotBlank()) {
                Text(
                    module.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (module.endpoints.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                module.endpoints.forEach { ep ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when (ep.method.uppercase()) {
                                "GET" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                "POST" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                "PUT" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                "DELETE" -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                            }
                        ) {
                            Text(
                                if (ep.encoding == "hex") "${ep.method.uppercase()}/HEX" else ep.method.uppercase(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = when (ep.method.uppercase()) {
                                    "GET" -> MaterialTheme.colorScheme.tertiary
                                    "POST" -> MaterialTheme.colorScheme.primary
                                    "PUT" -> MaterialTheme.colorScheme.secondary
                                    "DELETE" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            ep.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (ep.description.isNotBlank()) {
                            Text(
                                " — ${ep.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
