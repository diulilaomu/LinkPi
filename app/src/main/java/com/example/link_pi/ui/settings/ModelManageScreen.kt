package com.example.link_pi.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.link_pi.network.AiConfig
import com.example.link_pi.network.ModelConfig
import kotlin.math.roundToInt

/* ═══════════════════════════════════════════════════════
 *  模型管理主页面 — 3 个 Tab：已设模型 / 预设模型 / 自定义
 * ═══════════════════════════════════════════════════════ */

@Composable
fun ModelManageScreen(
    onNavigateEdit: (modelId: String) -> Unit = {}
) {
    val context = LocalContext.current
    val config = remember { AiConfig(context) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("已设模型", "预设模型", "自定义")

    // Force recomposition after mutations
    var version by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> ActiveModelsTab(config, version, onEdit = onNavigateEdit, onChanged = { version++ })
            1 -> PresetsTab(config, onAdded = { version++; selectedTab = 0 })
            2 -> CustomModelTab(config, onAdded = { version++; selectedTab = 0 })
        }
    }
}

/* ─────────────── Tab 0: 已设模型 ─────────────── */

@Composable
private fun ActiveModelsTab(
    config: AiConfig,
    version: Int,
    onEdit: (String) -> Unit,
    onChanged: () -> Unit
) {
    // Read version to trigger recomposition
    @Suppress("UNUSED_EXPRESSION") version

    val models = config.getModels()
    val activeId = config.activeModelId

    if (models.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "暂无模型配置\n可从「预设模型」添加或「自定义」创建",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var deleteTarget by remember { mutableStateOf<ModelConfig?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(models, key = { it.id }) { model ->
            val isActive = model.id == activeId
            ModelCard(
                model = model,
                isActive = isActive,
                onActivate = { config.setActive(model.id); onChanged() },
                onEdit = { onEdit(model.id) },
                onDelete = { deleteTarget = model }
            )
        }
    }

    // Delete confirmation dialog
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模型") },
            text = { Text("确定删除「${target.name.ifBlank { target.model }}」？") },
            confirmButton = {
                TextButton(onClick = {
                    config.deleteModel(target.id)
                    deleteTarget = null
                    onChanged()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ModelCard(
    model: ModelConfig,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val containerColor by animateColorAsState(
        if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "card"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onActivate)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Active indicator
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name.ifBlank { model.model },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = model.model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("temp=${model.temperature}")
                        append(" · tokens=${model.maxTokens}")
                        if (model.enableThinking) append(" · 思考")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete, contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/* ─────────────── Tab 1: 预设模型 ─────────────── */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsTab(
    config: AiConfig,
    onAdded: () -> Unit
) {
    var needsApiKey by remember { mutableStateOf<AiConfig.Preset?>(null) }
    var keyInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "点击预设快速添加到已设模型，需要填写对应平台的 API 密钥",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Group presets by provider
        val grouped = AiConfig.PRESETS.groupBy { it.endpoint.substringAfter("://").substringBefore("/") }

        grouped.forEach { (host, presets) ->
            val label = when {
                "dashscope" in host -> "阿里百炼"
                "openai" in host -> "OpenAI"
                "deepseek" in host -> "DeepSeek"
                "anthropic" in host -> "Anthropic"
                else -> host
            }
            Text(label, style = MaterialTheme.typography.titleSmall)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    AssistChip(
                        onClick = {
                            needsApiKey = preset
                            keyInput = ""
                        },
                        label = { Text(preset.model) },
                        leadingIcon = {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }
        }
    }

    // API key input dialog
    needsApiKey?.let { preset ->
        AlertDialog(
            onDismissRequest = { needsApiKey = null },
            title = { Text("添加 ${preset.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入 API 密钥", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (keyInput.isNotBlank()) {
                            config.addModel(
                                ModelConfig(
                                    name = preset.name,
                                    endpoint = preset.endpoint,
                                    apiKey = keyInput.trim(),
                                    model = preset.model
                                )
                            )
                            needsApiKey = null
                            onAdded()
                        }
                    }
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { needsApiKey = null }) { Text("取消") }
            }
        )
    }
}

/* ─────────────── Tab 2: 自定义模型 ─────────────── */

@Composable
private fun CustomModelTab(
    config: AiConfig,
    onAdded: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var maxTokens by remember { mutableIntStateOf(65536) }
    var temperature by remember { mutableDoubleStateOf(0.7) }
    var enableThinking by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "支持任何 OpenAI 兼容的 API 接口",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; saved = false },
            label = { Text("显示名称") },
            placeholder = { Text("例如: 我的 GPT-4o") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it; saved = false },
            label = { Text("API 地址") },
            placeholder = { Text("https://api.openai.com/v1/chat/completions") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false },
            label = { Text("API 密钥") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it; saved = false },
            label = { Text("模型名称") },
            placeholder = { Text("gpt-4o / claude-3-5-sonnet / ...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        ParameterSection(
            maxTokens = maxTokens,
            temperature = temperature,
            enableThinking = enableThinking,
            onMaxTokensChange = { maxTokens = it; saved = false },
            onTemperatureChange = { temperature = it; saved = false },
            onThinkingChange = { enableThinking = it; saved = false }
        )

        Spacer(modifier = Modifier.height(4.dp))

        androidx.compose.material3.Button(
            onClick = {
                if (endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()) {
                    config.addModel(
                        ModelConfig(
                            name = name.trim().ifBlank { model.trim() },
                            endpoint = endpoint.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            maxTokens = maxTokens,
                            temperature = temperature,
                            enableThinking = enableThinking
                        )
                    )
                    // Reset
                    name = ""; endpoint = ""; apiKey = ""; model = ""
                    maxTokens = 65536; temperature = 0.7; enableThinking = false
                    saved = true
                    onAdded()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
        ) {
            Text(if (saved) "✓ 已添加" else "添加模型")
        }
    }
}

/* ═══════════════════════════════════════════════════════
 *  模型编辑页面 — 完整参数配置
 * ═══════════════════════════════════════════════════════ */

@Composable
fun ModelEditScreen(
    modelId: String,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val config = remember { AiConfig(context) }
    val original = remember { config.getModels().firstOrNull { it.id == modelId } }

    if (original == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("模型不存在")
        }
        return
    }

    var name by remember { mutableStateOf(original.name) }
    var endpoint by remember { mutableStateOf(original.endpoint) }
    var apiKey by remember { mutableStateOf(original.apiKey) }
    var model by remember { mutableStateOf(original.model) }
    var maxTokens by remember { mutableIntStateOf(original.maxTokens) }
    var temperature by remember { mutableDoubleStateOf(original.temperature) }
    var enableThinking by remember { mutableStateOf(original.enableThinking) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; saved = false },
            label = { Text("显示名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it; saved = false },
            label = { Text("API 地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false },
            label = { Text("API 密钥") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it; saved = false },
            label = { Text("模型名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        ParameterSection(
            maxTokens = maxTokens,
            temperature = temperature,
            enableThinking = enableThinking,
            onMaxTokensChange = { maxTokens = it; saved = false },
            onTemperatureChange = { temperature = it; saved = false },
            onThinkingChange = { enableThinking = it; saved = false }
        )

        Spacer(modifier = Modifier.height(4.dp))

        androidx.compose.material3.Button(
            onClick = {
                config.updateModel(
                    original.copy(
                        name = name.trim(),
                        endpoint = endpoint.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        maxTokens = maxTokens,
                        temperature = temperature,
                        enableThinking = enableThinking
                    )
                )
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
        ) {
            Text(if (saved) "✓ 已保存" else "保存修改")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "密钥仅存储在本地设备，使用加密存储",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/* ═══════════════════════════════════════════════════════
 *  共享组件：参数设置区
 * ═══════════════════════════════════════════════════════ */

@Composable
fun ParameterSection(
    maxTokens: Int,
    temperature: Double,
    enableThinking: Boolean,
    onMaxTokensChange: (Int) -> Unit,
    onTemperatureChange: (Double) -> Unit,
    onThinkingChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("模型参数", style = MaterialTheme.typography.titleSmall)

            // Max Tokens
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Max Tokens", style = MaterialTheme.typography.bodyMedium)
                    var tokenTextFieldValue by remember(maxTokens) { mutableStateOf(maxTokens.toString()) }
                    OutlinedTextField(
                        value = tokenTextFieldValue,
                        onValueChange = { raw ->
                            tokenTextFieldValue = raw.filter { it.isDigit() }
                            val parsed = tokenTextFieldValue.toIntOrNull()
                            if (parsed != null && parsed in 1..131072) {
                                onMaxTokensChange(parsed)
                            }
                        },
                        modifier = Modifier.width(120.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            textAlign = TextAlign.End,
                            color = MaterialTheme.colorScheme.primary
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Slider(
                    value = maxTokens.toFloat(),
                    onValueChange = { onMaxTokensChange(it.roundToInt()) },
                    valueRange = 1024f..131072f,
                    steps = 0
                )
                Text(
                    "控制模型单次回复的最大长度（1024-131072）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Temperature
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Temperature", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "%.2f".format(temperature),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = temperature.toFloat(),
                    onValueChange = { onTemperatureChange(((it * 100).roundToInt() / 100.0)) },
                    valueRange = 0f..2f,
                    steps = 0
                )
                Text(
                    "越高越有创造力，越低越确定性强",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Enable Thinking
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("深度思考", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "启用后模型会先推理再回答，效果更好但更慢",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = enableThinking,
                    onCheckedChange = onThinkingChange
                )
            }
        }
    }
}
