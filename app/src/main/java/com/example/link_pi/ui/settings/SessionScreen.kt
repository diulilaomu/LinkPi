package com.example.link_pi.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.link_pi.data.SessionRegistry
import com.example.link_pi.data.model.ManagedSession
import com.example.link_pi.data.model.SessionStatus
import com.example.link_pi.data.model.SessionType

@Composable
fun SessionScreen(
    sessionRegistry: SessionRegistry
) {
    val sessions by sessionRegistry.sessions.collectAsState()
    var selectedType by remember { mutableStateOf<SessionType?>(null) }

    val filteredSessions = if (selectedType != null) {
        sessions.filter { it.type == selectedType }
    } else sessions

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Type filter tabs
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            FilterChip(
                selected = selectedType == null,
                onClick = { selectedType = null },
                label = { Text("全部") }
            )
            FilterChip(
                selected = selectedType == SessionType.CHAT,
                onClick = { selectedType = if (selectedType == SessionType.CHAT) null else SessionType.CHAT },
                label = { Text("聊天") }
            )
            FilterChip(
                selected = selectedType == SessionType.SSH_ASSIST,
                onClick = { selectedType = if (selectedType == SessionType.SSH_ASSIST) null else SessionType.SSH_ASSIST },
                label = { Text("SSH") }
            )
            FilterChip(
                selected = selectedType == SessionType.WORKBENCH,
                onClick = { selectedType = if (selectedType == SessionType.WORKBENCH) null else SessionType.WORKBENCH },
                label = { Text("工作台") }
            )
        }

        if (filteredSessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无会话记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onPause = { sessionRegistry.pauseSession(session.id) },
                        onResume = { sessionRegistry.resumeSession(session.id) },
                        onDelete = { sessionRegistry.deleteSession(session.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: ManagedSession,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Row 1: Status dot + label
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (session.status) {
                                SessionStatus.ACTIVE -> Color(0xFF4CAF50)
                                SessionStatus.PAUSED -> Color(0xFFFFC107)
                                SessionStatus.ENDED -> Color(0xFF9E9E9E)
                            }
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = session.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Row 2: Type icon + model
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (session.type) {
                        SessionType.CHAT -> Icons.AutoMirrored.Outlined.Chat
                        SessionType.SSH_ASSIST -> Icons.Outlined.Terminal
                        SessionType.WORKBENCH -> Icons.Outlined.Handyman
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = when (session.type) {
                        SessionType.CHAT -> "聊天"
                        SessionType.SSH_ASSIST -> "SSH"
                        SessionType.WORKBENCH -> "工作台"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = session.modelId.ifBlank { "未知模型" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = " · ${session.messageCount} 条消息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Row 3: Relative time + actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatRelativeTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.weight(1f))

                if (session.status == SessionStatus.ACTIVE) {
                    IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Pause,
                            contentDescription = "暂停",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (session.status == SessionStatus.PAUSED) {
                    IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = "恢复",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86400_000 -> "${diff / 3600_000} 小时前"
        diff < 604800_000 -> "${diff / 86400_000} 天前"
        else -> {
            val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}
