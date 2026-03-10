package com.example.link_pi.ui.sftp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════
//  Theme
// ═══════════════════════════════════════

private val EditorBg = Color(0xFF0D1117)
private val EditorSurface = Color(0xFF161B22)
private val EditorCard = Color(0xFF1C2128)
private val EditorBorder = Color(0xFF30363D)
private val EditorGreen = Color(0xFF3FB950)
private val EditorYellow = Color(0xFFD29922)
private val EditorRed = Color(0xFFF85149)
private val EditorCyan = Color(0xFF58A6FF)
private val EditorText = Color(0xFFE6EDF3)
private val EditorDim = Color(0xFF8B949E)
private val MonoFont = FontFamily.Monospace

// ═══════════════════════════════════════
//  File Editor Screen
// ═══════════════════════════════════════

@Composable
fun FileEditorScreen(
    viewModel: SftpViewModel,
    onBack: () -> Unit
) {
    val editingFile by viewModel.editingFile.collectAsState()
    val editingContent by viewModel.editingContent.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()

    // System back: close editor and return to SFTP
    BackHandler {
        viewModel.closeEditor()
        onBack()
    }

    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var showFindReplace by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var matchCount by remember { mutableIntStateOf(0) }
    var currentMatch by remember { mutableIntStateOf(-1) }

    // Sync ViewModel content to TextFieldValue when it changes externally (file loaded)
    LaunchedEffect(editingContent) {
        if (textFieldValue.text != editingContent) {
            textFieldValue = TextFieldValue(editingContent)
        }
    }

    // Compute matches whenever search query or text changes
    LaunchedEffect(searchQuery, textFieldValue.text) {
        if (searchQuery.isBlank()) {
            matchCount = 0
            currentMatch = -1
        } else {
            val text = textFieldValue.text
            var count = 0
            var idx = text.indexOf(searchQuery, ignoreCase = true)
            while (idx >= 0) {
                count++
                idx = text.indexOf(searchQuery, idx + 1, ignoreCase = true)
            }
            matchCount = count
            if (currentMatch >= count) currentMatch = count - 1
            if (count > 0 && currentMatch < 0) currentMatch = 0
        }
    }

    val fileName = editingFile?.substringAfterLast('/') ?: "?"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorBg)
            .statusBarsPadding()
    ) {
        // ── Top Bar ──
        Surface(color = EditorSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    viewModel.closeEditor()
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = EditorText, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        fileName,
                        style = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = EditorText),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        editingFile ?: "",
                        style = TextStyle(fontFamily = MonoFont, fontSize = 9.sp, color = EditorDim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Find/Replace toggle
                IconButton(onClick = { showFindReplace = !showFindReplace }) {
                    Icon(
                        if (showFindReplace) Icons.Filled.Close else Icons.Filled.Search,
                        "查找",
                        tint = if (showFindReplace) EditorCyan else EditorDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
                // Save
                IconButton(
                    onClick = {
                        viewModel.updateEditingContent(textFieldValue.text)
                        viewModel.saveEditingFile()
                    },
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = EditorCyan)
                    } else {
                        Icon(Icons.Filled.Save, "保存", tint = EditorGreen, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // ── Find/Replace Bar ──
        if (showFindReplace) {
            FindReplaceBar(
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                replaceQuery = replaceQuery,
                onReplaceChange = { replaceQuery = it },
                matchCount = matchCount,
                currentMatch = currentMatch,
                onPrev = {
                    if (matchCount > 0) {
                        currentMatch = if (currentMatch > 0) currentMatch - 1 else matchCount - 1
                        textFieldValue = selectNthMatch(textFieldValue.text, searchQuery, currentMatch)
                    }
                },
                onNext = {
                    if (matchCount > 0) {
                        currentMatch = (currentMatch + 1) % matchCount
                        textFieldValue = selectNthMatch(textFieldValue.text, searchQuery, currentMatch)
                    }
                },
                onReplace = {
                    if (matchCount > 0 && searchQuery.isNotBlank()) {
                        val pos = findNthOccurrence(textFieldValue.text, searchQuery, currentMatch)
                        if (pos >= 0) {
                            val newText = textFieldValue.text.substring(0, pos) +
                                    replaceQuery +
                                    textFieldValue.text.substring(pos + searchQuery.length)
                            textFieldValue = TextFieldValue(newText, TextRange(pos + replaceQuery.length))
                            viewModel.updateEditingContent(newText)
                        }
                    }
                },
                onReplaceAll = {
                    if (searchQuery.isNotBlank()) {
                        val newText = textFieldValue.text.replace(searchQuery, replaceQuery, ignoreCase = true)
                        textFieldValue = TextFieldValue(newText)
                        viewModel.updateEditingContent(newText)
                    }
                }
            )
        }

        HorizontalDivider(color = EditorBorder)

        // ── Editor ──
        if (editingFile != null && editingContent.isEmpty() && !isSaving) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = EditorCyan, modifier = Modifier.size(24.dp))
            }
        } else {
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(8.dp)
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = {
                        textFieldValue = it
                        viewModel.updateEditingContent(it.text)
                    },
                    textStyle = TextStyle(
                        fontFamily = MonoFont,
                        fontSize = 12.sp,
                        color = EditorText,
                        lineHeight = 18.sp
                    ),
                    cursorBrush = SolidColor(EditorGreen),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ═══════════════════════════════════════
//  Find / Replace Bar
// ═══════════════════════════════════════

@Composable
private fun FindReplaceBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    replaceQuery: String,
    onReplaceChange: (String) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit
) {
    Surface(color = EditorCard) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Search row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = EditorDim, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                SearchField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = "查找...",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(4.dp))
                if (searchQuery.isNotBlank()) {
                    Text(
                        "${if (matchCount > 0) currentMatch + 1 else 0}/$matchCount",
                        style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = EditorDim)
                    )
                }
                IconButton(onClick = onPrev, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, "上一个", tint = EditorDim, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onNext, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, "下一个", tint = EditorDim, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(4.dp))

            // Replace row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FindReplace, null, tint = EditorDim, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                SearchField(
                    value = replaceQuery,
                    onValueChange = onReplaceChange,
                    placeholder = "替换...",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(4.dp))
                Surface(
                    onClick = onReplace,
                    shape = RoundedCornerShape(4.dp),
                    color = EditorCyan.copy(alpha = 0.15f)
                ) {
                    Text(
                        "替换",
                        style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = EditorCyan),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Surface(
                    onClick = onReplaceAll,
                    shape = RoundedCornerShape(4.dp),
                    color = EditorYellow.copy(alpha = 0.15f)
                ) {
                    Text(
                        "全部",
                        style = TextStyle(fontFamily = MonoFont, fontSize = 10.sp, color = EditorYellow),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = EditorBg,
        modifier = modifier.height(28.dp)
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            if (value.isBlank()) {
                Text(
                    placeholder,
                    style = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = EditorDim)
                )
            }
            // Use TextFieldValue for IME safety
            var tfv by remember(value) { mutableStateOf(TextFieldValue(value)) }
            BasicTextField(
                value = tfv,
                onValueChange = { tfv = it; onValueChange(it.text) },
                textStyle = TextStyle(fontFamily = MonoFont, fontSize = 11.sp, color = EditorText),
                cursorBrush = SolidColor(EditorGreen),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ═══════════════════════════════════════
//  Helpers
// ═══════════════════════════════════════

private fun findNthOccurrence(text: String, query: String, n: Int): Int {
    var count = 0
    var idx = text.indexOf(query, ignoreCase = true)
    while (idx >= 0) {
        if (count == n) return idx
        count++
        idx = text.indexOf(query, idx + 1, ignoreCase = true)
    }
    return -1
}

private fun selectNthMatch(text: String, query: String, n: Int): TextFieldValue {
    val pos = findNthOccurrence(text, query, n)
    return if (pos >= 0) {
        TextFieldValue(text, TextRange(pos, pos + query.length))
    } else {
        TextFieldValue(text)
    }
}
