package com.example.link_pi.ui.skill

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.link_pi.data.model.Skill
import com.example.link_pi.data.model.SkillMode
import com.example.link_pi.skill.BuiltInSkills
import com.example.link_pi.skill.SkillStorage
import com.example.link_pi.skill.UserIntent
import java.util.UUID

@Composable
fun SkillListScreen(
    skillStorage: SkillStorage,
    activeSkillId: String,
    onSelectSkill: (Skill) -> Unit
) {
    var storedSkills by remember { mutableStateOf(skillStorage.loadAll()) }
    val storedMap = remember(storedSkills) { storedSkills.associateBy { it.id } }

    // 合并：内置 Skill 可被存储版本覆盖
    val builtInIds = remember { (BuiltInSkills.all + BuiltInSkills.systemTemplates).map { it.id }.toSet() }
    val effectiveRoleSkills = remember(storedMap) {
        BuiltInSkills.all.map { storedMap[it.id] ?: it }
    }
    val effectiveSystemSkills = remember(storedMap) {
        BuiltInSkills.systemTemplates.map { storedMap[it.id] ?: it }
    }
    val userSkills = remember(storedSkills) {
        storedSkills.filter { it.id !in builtInIds }
    }

    // 检查是否有任何内置 Skill 被修改过
    val hasOverrides = remember(storedSkills) {
        storedSkills.any { it.id in builtInIds }
    }

    var showEditor by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<Skill?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Skill?>(null) }
    var showDetailSkill by remember { mutableStateOf<Skill?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    fun refreshSkills() { storedSkills = skillStorage.loadAll() }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingSkill = null
                    showEditor = true
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建 Skill")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── 重置按钮 ──
            if (hasOverrides || userSkills.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showResetDialog = true }) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重置全部", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            // ── 内置角色 ──
            item {
                Text(
                    text = "内置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                )
            }
            items(effectiveRoleSkills, key = { it.id }) { skill ->
                val isModified = skill.id in storedMap
                SkillCard(
                    skill = skill,
                    isActive = skill.id == activeSkillId,
                    isModified = isModified,
                    onSelect = { onSelectSkill(skill) },
                    onEdit = {
                        editingSkill = skill
                        showEditor = true
                    },
                    onDelete = if (isModified) {{ showDeleteDialog = skill }} else null
                )
            }
            // ── 系统与工作流模板 ──
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "系统与工作流",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                )
            }
            items(effectiveSystemSkills, key = { it.id }) { skill ->
                val isModified = skill.id in storedMap
                SkillCard(
                    skill = skill,
                    isActive = false,
                    isModified = isModified,
                    onSelect = { showDetailSkill = skill },
                    onEdit = {
                        editingSkill = skill
                        showEditor = true
                    },
                    onDelete = if (isModified) {{ showDeleteDialog = skill }} else null
                )
            }
            // ── 自定义 ──
            if (userSkills.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "自定义",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                    )
                }
                items(userSkills, key = { it.id }) { skill ->
                    SkillCard(
                        skill = skill,
                        isActive = skill.id == activeSkillId,
                        isModified = false,
                        onSelect = { onSelectSkill(skill) },
                        onEdit = {
                            editingSkill = skill
                            showEditor = true
                        },
                        onDelete = { showDeleteDialog = skill }
                    )
                }
            }
        }
    }

    if (showEditor) {
        SkillEditorDialog(
            initial = editingSkill,
            onDismiss = { showEditor = false },
            onSave = { skill ->
                skillStorage.save(skill)
                refreshSkills()
                // 如果正在编辑的是当前活跃 Skill，同步更新
                if (skill.id == activeSkillId) onSelectSkill(skill)
                showEditor = false
            }
        )
    }

    showDeleteDialog?.let { skill ->
        val isBuiltInOverride = skill.id in builtInIds
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(if (isBuiltInOverride) "恢复默认" else "删除 Skill") },
            text = {
                Text(
                    if (isBuiltInOverride) "将「${skill.name}」恢复为内置默认版本？"
                    else "确定要删除「${skill.name}」吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    skillStorage.delete(skill.id)
                    refreshSkills()
                    if (skill.id == activeSkillId) {
                        // 恢复为内置默认
                        val defaultSkill = (BuiltInSkills.all + BuiltInSkills.systemTemplates).find { it.id == skill.id }
                            ?: BuiltInSkills.DEFAULT
                        onSelectSkill(defaultSkill)
                    }
                    showDeleteDialog = null
                }) {
                    Text(
                        if (isBuiltInOverride) "恢复" else "删除",
                        color = if (isBuiltInOverride) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("取消") }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置全部 Skill") },
            text = { Text("将删除所有自定义 Skill 并恢复所有内置 Skill 为默认版本。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    skillStorage.resetAll()
                    refreshSkills()
                    // 如果当前活跃 Skill 是自定义的，切回默认
                    if (activeSkillId !in builtInIds) {
                        onSelectSkill(BuiltInSkills.DEFAULT)
                    } else {
                        val restored = (BuiltInSkills.all + BuiltInSkills.systemTemplates).find { it.id == activeSkillId }
                            ?: BuiltInSkills.DEFAULT
                        onSelectSkill(restored)
                    }
                    showResetDialog = false
                }) {
                    Text("重置", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            }
        )
    }

    showDetailSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = { showDetailSkill = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = skill.icon, style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = skill.name)
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (skill.description.isNotBlank()) {
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    Text(
                        text = skill.systemPrompt,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetailSkill = null }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    isActive: Boolean,
    isModified: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = skill.icon,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (skill.mode == SkillMode.CODING) "编程" else "闲聊",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "使用中",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (isModified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "已修改",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    if (skill.intentInjections.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "注入",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "编辑",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SkillEditorDialog(
    initial: Skill?,
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var icon by remember { mutableStateOf(initial?.icon ?: "\uD83D\uDD27") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var systemPrompt by remember { mutableStateOf(initial?.systemPrompt ?: "") }
    var mode by remember { mutableStateOf(initial?.mode ?: SkillMode.CODING) }
    var intentInjections by remember { mutableStateOf(initial?.intentInjections ?: emptySet()) }

    val isBuiltIn = initial?.isBuiltIn == true
    val canSave = name.isNotBlank() && systemPrompt.isNotBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 顶栏 ──
                TopAppBar(
                    title = {
                        Text(
                            text = if (initial != null) "编辑 Skill" else "新建 Skill",
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                if (canSave) {
                                    onSave(
                                        Skill(
                                            id = initial?.id ?: "custom_${UUID.randomUUID()}",
                                            name = name.trim(),
                                            icon = icon.ifBlank { "\uD83D\uDD27" },
                                            description = description.trim(),
                                            systemPrompt = systemPrompt.trim(),
                                            mode = mode,
                                            isBuiltIn = false,
                                            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                                            bridgeGroups = initial?.bridgeGroups ?: setOf(
                                                com.example.link_pi.skill.BridgeGroup.STORAGE,
                                                com.example.link_pi.skill.BridgeGroup.UI_FEEDBACK
                                            ),
                                            cdnGroups = initial?.cdnGroups ?: emptySet(),
                                            extraToolGroups = initial?.extraToolGroups ?: emptySet(),
                                            intentInjections = if (isBuiltIn) initial?.intentInjections ?: emptySet() else intentInjections
                                        )
                                    )
                                }
                            },
                            enabled = canSave
                        ) {
                            Text("保存", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // ── 表单内容 ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // ── 图标 + 名称 ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 图标预览 + 编辑
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (icon.isNotBlank()) {
                                    Text(
                                        text = icon,
                                        fontSize = 28.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("名称") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = icon,
                                onValueChange = { if (it.length <= 2) icon = it },
                                label = { Text("图标 Emoji") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    // ── 描述 ──
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("描述") },
                        placeholder = { Text("简要说明此 Skill 的用途") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    // ── 知识模式 ──
                    SectionHeader(title = "知识模式")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf(
                            SkillMode.CODING to "编程模式",
                            SkillMode.CHAT to "闲聊模式"
                        ).forEach { (m, label) ->
                            val selected = mode == m
                            val bg by animateColorAsState(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                label = "modeBg"
                            )
                            Surface(
                                onClick = { mode = m },
                                shape = RoundedCornerShape(12.dp),
                                color = bg,
                                border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) else null,
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = if (mode == SkillMode.CODING) "仅注入系统/工具文档" else "注入个人记忆和偏好",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )

                    // ── 意图注入 ──
                    SectionHeader(title = "意图注入")
                    if (isBuiltIn) {
                        Text(
                            text = "内置 Skill 不可配置意图注入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    } else {
                        Text(
                            text = "选中的意图触发时，自动注入此 Skill 的提示词",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val intentLabels = listOf(
                            UserIntent.CONVERSATION to "💬 对话",
                            UserIntent.CREATE_APP to "🆕 创建应用",
                            UserIntent.MODIFY_APP to "✏️ 修改应用",
                            UserIntent.MODULE_MGMT to "📦 模块管理",
                            UserIntent.MEMORY_OPS to "🧠 记忆操作"
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            intentLabels.forEach { (intent, label) ->
                                val selected = intent in intentInjections
                                val bg by animateColorAsState(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    label = "intentBg"
                                )
                                Surface(
                                    onClick = {
                                        intentInjections = if (selected) intentInjections - intent
                                        else intentInjections + intent
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    color = bg,
                                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) else null
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (selected) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                modifier = Modifier.size(16.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .padding(2.dp)
                                                        .size(12.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── 系统提示词 ──
                    SectionHeader(title = "系统提示词")
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        placeholder = { Text("定义 AI 的角色和行为规则…") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}
