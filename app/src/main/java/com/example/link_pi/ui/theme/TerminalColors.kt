package com.example.link_pi.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/** Shared terminal / editor color palette used across SSH, SFTP, and File Editor screens. */
val TermBg = Color(0xFF0D1117)
val TermSurface = Color(0xFF161B22)
val TermCard = Color(0xFF1C2128)
val TermBorder = Color(0xFF30363D)
val TermGreen = Color(0xFF3FB950)
val TermYellow = Color(0xFFD29922)
val TermRed = Color(0xFFF85149)
val TermCyan = Color(0xFF58A6FF)
val TermText = Color(0xFFE6EDF3)
val TermDim = Color(0xFF8B949E)
val MonoFont: FontFamily = FontFamily.Monospace

/** Shared border stroke for terminal-themed components. */
val TermBorderStroke = BorderStroke(1.dp, TermBorder)

/** Shared OutlinedTextField colors for terminal theme. */
@Composable
fun termFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TermText,
    unfocusedTextColor = TermText,
    focusedBorderColor = TermGreen,
    unfocusedBorderColor = TermBorder,
    focusedLabelColor = TermGreen,
    unfocusedLabelColor = TermDim,
    cursorColor = TermGreen,
    focusedPlaceholderColor = TermDim,
    unfocusedPlaceholderColor = TermDim.copy(alpha = 0.5f),
    disabledTextColor = TermDim,
    disabledBorderColor = TermBorder,
    disabledLabelColor = TermDim
)

/** Shared TextStyle for terminal text fields (e.g. server edit forms). */
val TermFieldStyle = TextStyle(fontFamily = MonoFont, fontSize = 14.sp)

/**
 * Terminal-themed confirmation dialog.
 * Used for delete confirmations, error alerts, etc. across SSH/SFTP screens.
 */
@Composable
fun TermConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    dismissText: String? = "取消",
    confirmColor: Color = TermRed
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TermCard,
        titleContentColor = TermText,
        textContentColor = TermDim,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = confirmColor)
            }
        },
        dismissButton = dismissText?.let {
            { TextButton(onClick = onDismiss) { Text(it, color = TermDim) } }
        }
    )
}
