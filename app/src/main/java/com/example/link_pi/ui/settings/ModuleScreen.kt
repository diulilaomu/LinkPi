package com.example.link_pi.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.example.link_pi.agent.ModuleService
import com.example.link_pi.agent.ModuleStorage

@Composable
fun ModuleScreen() {
    val context = LocalContext.current
    val moduleStorage = remember { ModuleStorage(context) }
    var modules by remember { mutableStateOf(moduleStorage.loadAll()) }
    var deleteTarget by remember { mutableStateOf<ModuleStorage.Module?>(null) }
    var editTarget by remember { mutableStateOf<ModuleStorage.Module?>(null) }
    var exportTarget by remember { mutableStateOf<ModuleStorage.Module?>(null) }
    var detailTarget by remember { mutableStateOf<ModuleStorage.Module?>(null) }

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
                "Python 服务模块 (HTTP / TCP / UDP)",
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
                    "在聊天中让 AI 创建模块，或点击右上角导入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(modules, key = { it.id }) { module ->
                    ModuleCard(
                        module = module,
                        moduleStorage = moduleStorage,
                        onClick = { detailTarget = module },
                        onEdit = {{ editTarget = module }},
                        onDelete = {{ deleteTarget = module }},
                        onExport = {{
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
            onSave = { name, desc, instructions ->
                moduleStorage.updateModule(
                    module.id,
                    name = name.takeIf { it.isNotBlank() },
                    description = desc.takeIf { it.isNotBlank() },
                    instructions = instructions
                )
                modules = moduleStorage.loadAll()
                editTarget = null
            }
        )
    }

    // ── Detail dialog ──
    detailTarget?.let { module ->
        ModuleDetailDialog(
            module = module,
            moduleStorage = moduleStorage,
            onDismiss = { detailTarget = null }
        )
    }
}

@Composable
private fun EditModuleDialog(
    module: ModuleStorage.Module,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, instructions: String) -> Unit
) {
    var name by remember { mutableStateOf(module.name) }
    var description by remember { mutableStateOf(module.description) }
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
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("使用说明 (AI 可读)") },
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description, instructions) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── Detail Dialog ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleDetailDialog(
    module: ModuleStorage.Module,
    moduleStorage: ModuleStorage,
    onDismiss: () -> Unit
) {
    val scripts = moduleStorage.listScripts(module.id)
    val readme = moduleStorage.getReadme(module.id)
    val sdf = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(module.name)
                Spacer(modifier = Modifier.width(8.dp))
                TypeBadge(module.serviceType)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Service info
                DetailRow("服务类型", module.serviceType)
                if (module.defaultPort > 0) {
                    DetailRow("默认端口", module.defaultPort.toString())
                }
                DetailRow("入口脚本", module.mainScript)

                // Description
                if (module.description.isNotBlank()) {
                    DetailRow("描述", module.description)
                }

                // Instructions
                if (module.instructions.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Text("使用说明", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    Text(
                        module.instructions.take(300),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Script files
                if (scripts.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Text("脚本文件", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        scripts.forEach { name ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Code,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // README excerpt
                if (readme != null && readme.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("README", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        readme.take(500),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Metadata
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                DetailRow("版本", "v${module.version}")
                DetailRow("创建", sdf.format(module.createdAt))
                DetailRow("更新", sdf.format(module.updatedAt))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TypeBadge(serviceType: String) {
    val (text, color) = when (serviceType.uppercase()) {
        "HTTP" -> "HTTP" to MaterialTheme.colorScheme.primary
        "TCP" -> "TCP" to MaterialTheme.colorScheme.secondary
        "UDP" -> "UDP" to MaterialTheme.colorScheme.tertiary
        else -> serviceType to MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

// ── Module Card ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleCard(
    module: ModuleStorage.Module,
    moduleStorage: ModuleStorage,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onExport: (() -> Unit)?
) {
    val scriptFiles = moduleStorage.listScripts(module.id)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Row 1: Name + type badge + actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        module.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    TypeBadge(module.serviceType)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onEdit != null) {
                        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Edit, contentDescription = "编辑",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        }
                    }
                    if (onExport != null) {
                        IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Share, contentDescription = "导出",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            // Row 2: Description
            if (module.description.isNotBlank()) {
                Text(
                    module.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Row 3: Info chips (port, main script, scripts count)
            val infoParts = mutableListOf<String>()
            if (module.defaultPort > 0) infoParts.add("端口 ${module.defaultPort}")
            infoParts.add(module.mainScript)
            if (scriptFiles.isNotEmpty()) infoParts.add("${scriptFiles.size} 脚本")

            if (infoParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    infoParts.forEach { info ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                info,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
