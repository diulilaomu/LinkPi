package com.example.link_pi.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.link_pi.agent.AgentStep
import com.example.link_pi.agent.StepType

/**
 * Collapsible panel showing live agent steps, placed above the input bar.
 * Reusable across ChatScreen, SshScreen, or any other AI interaction surface.
 */
@Composable
fun AgentStepsPanel(
    steps: List<AgentStep>,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty() && !isLoading) return

    var expanded by remember { mutableStateOf(true) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "arrow"
    )
    val scrollState = rememberScrollState()

    LaunchedEffect(steps.size) {
        if (expanded) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isLoading) "思考过程" else "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${steps.size} 步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )
            }

            // Collapsible content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(scrollState)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        steps.forEachIndexed { idx, step ->
                            StepRow(step, isLast = idx == steps.size - 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: AgentStep, isLast: Boolean) {
    val accentColor = when (step.type) {
        StepType.THINKING -> MaterialTheme.colorScheme.tertiary
        StepType.TOOL_CALL -> MaterialTheme.colorScheme.primary
        StepType.TOOL_RESULT -> MaterialTheme.colorScheme.secondary
        StepType.FINAL_RESPONSE -> MaterialTheme.colorScheme.primary
    }
    val isLongDetail = step.detail.length > 100
    var detailExpanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        // Timeline dot + line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.7f))
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(if (detailExpanded) 200.dp else 24.dp)
                        .background(accentColor.copy(alpha = 0.2f))
                )
            }
        }

        // Content
        Column(modifier = Modifier.padding(start = 4.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isLongDetail) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        onClick = { detailExpanded = !detailExpanded },
                        shape = RoundedCornerShape(4.dp),
                        color = accentColor.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = if (detailExpanded) "收起 ▲" else "${step.detail.length}字 ▼",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = accentColor.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            if (step.detail.isNotBlank()) {
                if (!isLongDetail) {
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (!detailExpanded) {
                    Text(
                        text = step.detail.take(80) + "...",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        onClick = { clipboardManager.setText(AnnotatedString(step.detail)) },
                        shape = RoundedCornerShape(4.dp),
                        color = accentColor.copy(alpha = 0.1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "复制",
                                modifier = Modifier.size(10.dp),
                                tint = accentColor
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "复制全文",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = accentColor
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
