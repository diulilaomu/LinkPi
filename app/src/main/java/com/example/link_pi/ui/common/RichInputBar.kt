package com.example.link_pi.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.link_pi.data.model.Attachment
import com.example.link_pi.network.ModelConfig
import com.example.link_pi.ui.theme.*

/**
 * Shared rich input bar used across Chat, SSH, and Workbench screens.
 *
 * Two visual styles: [RichInputBarStyle.Material] (default M3 theme) and
 * [RichInputBarStyle.Terminal] (dark monospace terminal style).
 */
@Composable
fun RichInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true,
    placeholder: String = "输入内容...",
    disabledPlaceholder: String = placeholder,
    maxLines: Int = 4,
    // Model selector
    models: List<ModelConfig> = emptyList(),
    activeModelId: String = "",
    onSwitchModel: (String) -> Unit = {},
    showModelMenu: Boolean = false,
    onToggleModelMenu: () -> Unit = {},
    // Deep thinking
    deepThinking: Boolean = false,
    onToggleThinking: () -> Unit = {},
    // Attachments
    pendingAttachments: List<Attachment> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    onPickFile: () -> Unit = {},
    showAttachButton: Boolean = true,
    // Style
    style: RichInputBarStyle = RichInputBarStyle.Material,
    // Optional leading content before the text field (e.g. ">_" prompt)
    leadingContent: @Composable (() -> Unit)? = null,
    // Send button customisation: if null, uses default send button
    sendButton: @Composable ((canSend: Boolean) -> Unit)? = null,
    // Extra modifier on the outer Surface
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 0.dp,
) {
    val s = style.resolve()
    val activeModel = models.find { it.id == activeModelId }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(s.outerPadding),
        shape = s.shape,
        color = s.containerColor,
        border = s.border,
        shadowElevation = shadowElevation
    ) {
        Column {
            // ── Model selector list (expandable) ──
            AnimatedVisibility(visible = showModelMenu, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                    models.forEach { model ->
                        val isActive = model.id == activeModelId
                        Surface(
                            onClick = {
                                onSwitchModel(model.id)
                                onToggleModelMenu()
                            },
                            shape = s.modelItemShape,
                            color = if (isActive) s.activeItemBg else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(s.modelItemPadding),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = model.name.ifBlank { model.model },
                                    style = s.modelItemTextStyle,
                                    color = if (isActive) s.accentColor else s.secondaryTextColor,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isActive) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(s.modelCheckSize),
                                        tint = s.accentColor
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = s.dividerColor
                    )
                }
            }

            // ── Attachment chips ──
            if (pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pendingAttachments.forEachIndexed { index, att ->
                        val label = "附件${index + 1}:${if (att.base64Data != null) "图片" else "文本"}"
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = s.chipBg,
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = s.chipTextStyle,
                                    color = s.chipTextColor,
                                    maxLines = 1
                                )
                                Surface(
                                    onClick = { onRemoveAttachment(index) },
                                    shape = RoundedCornerShape(50),
                                    color = Color.Transparent
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "移除",
                                        modifier = Modifier.size(14.dp).padding(1.dp),
                                        tint = s.chipCloseColor
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Text input ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                leadingContent?.invoke()
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    placeholder = {
                        Text(
                            if (enabled) placeholder else disabledPlaceholder,
                            style = s.placeholderStyle,
                            color = s.placeholderColor
                        )
                    },
                    maxLines = maxLines,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        cursorColor = s.accentColor,
                        disabledBorderColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        disabledTextColor = s.disabledTextColor
                    ),
                    textStyle = s.inputTextStyle
                )
            }

            // ── Bottom toolbar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(s.toolbarPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left tools
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(s.toolSpacing)
                ) {
                    // Model selector toggle
                    Surface(
                        onClick = onToggleModelMenu,
                        shape = s.pillShape,
                        color = if (showModelMenu) s.activePillBg else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(s.pillPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(s.pillInnerSpacing)
                        ) {
                            Text(
                                text = activeModel?.name?.ifBlank { activeModel?.model ?: "模型" } ?: "模型",
                                style = s.pillTextStyle,
                                color = if (showModelMenu) s.activePillTextColor else s.inactivePillTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = s.modelNameMaxWidth)
                            )
                            val arrowRotation by animateFloatAsState(
                                targetValue = if (showModelMenu) 180f else 0f,
                                label = "arrow"
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(s.arrowSize)
                                    .then(s.arrowRotationModifier(arrowRotation)),
                                tint = if (showModelMenu) s.activePillTextColor else s.inactivePillTextColor
                            )
                        }
                    }

                    // Deep thinking toggle
                    Surface(
                        onClick = onToggleThinking,
                        shape = s.pillShape,
                        color = if (deepThinking) s.thinkingActiveBg else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(s.pillPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(s.pillInnerSpacing)
                        ) {
                            Icon(
                                Icons.Outlined.Lightbulb,
                                contentDescription = null,
                                modifier = Modifier.size(s.toolIconSize),
                                tint = if (deepThinking) s.thinkingActiveColor else s.inactivePillTextColor
                            )
                            Text(
                                "深度思考",
                                style = s.pillTextStyle,
                                color = if (deepThinking) s.thinkingActiveColor else s.inactivePillTextColor
                            )
                        }
                    }

                    // Attachment button
                    if (showAttachButton) {
                        Surface(
                            onClick = onPickFile,
                            shape = s.pillShape,
                            color = Color.Transparent
                        ) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                contentDescription = "附件",
                                modifier = Modifier.padding(s.attachIconPadding).size(s.toolIconSize),
                                tint = s.inactivePillTextColor
                            )
                        }
                    }
                }

                // Send button
                val canSend = (text.isNotBlank() || pendingAttachments.isNotEmpty()) && enabled
                if (sendButton != null) {
                    sendButton(canSend)
                } else {
                    DefaultSendButton(canSend = canSend, onClick = { if (canSend) onSend() }, style = s)
                }
            }
        }
    }
}

// ═══════════════════════════════════════
//  Default send buttons
// ═══════════════════════════════════════

@Composable
private fun DefaultSendButton(canSend: Boolean, onClick: () -> Unit, style: ResolvedStyle) {
    if (style.useSendIcon) {
        Surface(
            onClick = onClick,
            enabled = canSend,
            shape = RoundedCornerShape(16.dp),
            color = if (canSend) style.sendActiveColor else style.sendInactiveColor
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = if (canSend) style.sendIconTint else style.sendIconTintDisabled,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).size(16.dp)
            )
        }
    } else {
        Surface(
            onClick = onClick,
            enabled = canSend,
            shape = RoundedCornerShape(24.dp),
            color = if (canSend) style.sendActiveColor else style.sendInactiveColor
        ) {
            Text(
                text = "发送",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = style.sendTextStyle,
                color = if (canSend) style.sendTextActiveColor else style.sendTextInactiveColor
            )
        }
    }
}

// ═══════════════════════════════════════
//  Style definitions
// ═══════════════════════════════════════

sealed class RichInputBarStyle {
    object Material : RichInputBarStyle()
    object Terminal : RichInputBarStyle()

    @Composable
    internal fun resolve(): ResolvedStyle = when (this) {
        Material -> resolveMaterial()
        Terminal -> resolveTerminal()
    }
}

internal data class ResolvedStyle(
    // Container
    val outerPadding: androidx.compose.foundation.layout.PaddingValues,
    val shape: RoundedCornerShape,
    val containerColor: Color,
    val border: BorderStroke?,
    // Model list
    val modelItemShape: RoundedCornerShape,
    val modelItemPadding: androidx.compose.foundation.layout.PaddingValues,
    val modelItemTextStyle: TextStyle,
    val modelCheckSize: Dp,
    val activeItemBg: Color,
    val dividerColor: Color,
    // Accent
    val accentColor: Color,
    val secondaryTextColor: Color,
    // Chips
    val chipBg: Color,
    val chipTextStyle: TextStyle,
    val chipTextColor: Color,
    val chipCloseColor: Color,
    // Text input
    val placeholderStyle: TextStyle,
    val placeholderColor: Color,
    val inputTextStyle: TextStyle,
    val disabledTextColor: Color,
    // Toolbar
    val toolbarPadding: androidx.compose.foundation.layout.PaddingValues,
    val toolSpacing: Dp,
    val pillShape: RoundedCornerShape,
    val pillPadding: androidx.compose.foundation.layout.PaddingValues,
    val pillInnerSpacing: Dp,
    val pillTextStyle: TextStyle,
    val activePillBg: Color,
    val activePillTextColor: Color,
    val inactivePillTextColor: Color,
    val modelNameMaxWidth: Dp,
    val arrowSize: Dp,
    val arrowRotationModifier: (Float) -> Modifier,
    val toolIconSize: Dp,
    val attachIconPadding: androidx.compose.foundation.layout.PaddingValues,
    // Deep thinking
    val thinkingActiveBg: Color,
    val thinkingActiveColor: Color,
    // Send button
    val useSendIcon: Boolean,
    val sendActiveColor: Color,
    val sendInactiveColor: Color,
    val sendIconTint: Color,
    val sendIconTintDisabled: Color,
    val sendTextStyle: TextStyle,
    val sendTextActiveColor: Color,
    val sendTextInactiveColor: Color,
)

@Composable
private fun resolveMaterial(): ResolvedStyle {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    return ResolvedStyle(
        outerPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        containerColor = cs.surfaceContainerHigh,
        border = null,
        modelItemShape = RoundedCornerShape(12.dp),
        modelItemPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modelItemTextStyle = typ.bodyMedium,
        modelCheckSize = 16.dp,
        activeItemBg = cs.primaryContainer.copy(alpha = 0.5f),
        dividerColor = cs.outlineVariant.copy(alpha = 0.3f),
        accentColor = cs.primary,
        secondaryTextColor = cs.onSurfaceVariant,
        chipBg = cs.secondaryContainer,
        chipTextStyle = typ.labelSmall,
        chipTextColor = cs.onSecondaryContainer,
        chipCloseColor = cs.onSecondaryContainer.copy(alpha = 0.6f),
        placeholderStyle = typ.bodyMedium,
        placeholderColor = cs.onSurfaceVariant.copy(alpha = 0.5f),
        inputTextStyle = typ.bodyMedium,
        disabledTextColor = cs.onSurfaceVariant.copy(alpha = 0.4f),
        toolbarPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
        toolSpacing = 0.dp,
        pillShape = RoundedCornerShape(16.dp),
        pillPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        pillInnerSpacing = 4.dp,
        pillTextStyle = typ.labelSmall,
        activePillBg = cs.primaryContainer,
        activePillTextColor = cs.onPrimaryContainer,
        inactivePillTextColor = cs.onSurfaceVariant.copy(alpha = 0.6f),
        modelNameMaxWidth = 80.dp,
        arrowSize = 14.dp,
        arrowRotationModifier = { rotation -> Modifier.rotate(rotation) },
        toolIconSize = 14.dp,
        attachIconPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        thinkingActiveBg = cs.primaryContainer,
        thinkingActiveColor = cs.onPrimaryContainer,
        useSendIcon = false,
        sendActiveColor = cs.primary,
        sendInactiveColor = cs.onSurfaceVariant.copy(alpha = 0.12f),
        sendIconTint = Color.Unspecified,
        sendIconTintDisabled = Color.Unspecified,
        sendTextStyle = typ.labelMedium,
        sendTextActiveColor = cs.onPrimary,
        sendTextInactiveColor = cs.onSurfaceVariant.copy(alpha = 0.4f),
    )
}

@Composable
private fun resolveTerminal(): ResolvedStyle {
    return ResolvedStyle(
        outerPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        containerColor = TermSurface,
        border = BorderStroke(1.dp, TermBorder),
        modelItemShape = RoundedCornerShape(8.dp),
        modelItemPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modelItemTextStyle = TextStyle(fontFamily = MonoFont, fontSize = 11.sp),
        modelCheckSize = 14.dp,
        activeItemBg = TermGreen.copy(alpha = 0.12f),
        dividerColor = TermBorder.copy(alpha = 0.5f),
        accentColor = TermGreen,
        secondaryTextColor = TermText,
        chipBg = TermGreen.copy(alpha = 0.12f),
        chipTextStyle = TextStyle(fontFamily = MonoFont, fontSize = 9.sp),
        chipTextColor = TermGreen,
        chipCloseColor = TermGreen.copy(alpha = 0.6f),
        placeholderStyle = TextStyle(fontFamily = MonoFont, fontSize = 13.sp),
        placeholderColor = TermDim,
        inputTextStyle = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, color = TermText),
        disabledTextColor = TermDim,
        toolbarPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, bottom = 6.dp),
        toolSpacing = 2.dp,
        pillShape = RoundedCornerShape(12.dp),
        pillPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        pillInnerSpacing = 2.dp,
        pillTextStyle = TextStyle(fontFamily = MonoFont, fontSize = 9.sp),
        activePillBg = TermGreen.copy(alpha = 0.12f),
        activePillTextColor = TermGreen,
        inactivePillTextColor = TermDim,
        modelNameMaxWidth = 72.dp,
        arrowSize = 12.dp,
        arrowRotationModifier = { rotation -> Modifier.graphicsLayer { rotationZ = rotation } },
        toolIconSize = 12.dp,
        attachIconPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        thinkingActiveBg = TermCyan.copy(alpha = 0.12f),
        thinkingActiveColor = TermCyan,
        useSendIcon = true,
        sendActiveColor = TermGreen,
        sendInactiveColor = TermDim.copy(alpha = 0.2f),
        sendIconTint = TermBg,
        sendIconTintDisabled = TermDim,
        sendTextStyle = TextStyle(),
        sendTextActiveColor = Color.Unspecified,
        sendTextInactiveColor = Color.Unspecified,
    )
}
