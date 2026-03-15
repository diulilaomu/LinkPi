package com.example.link_pi.data.model

data class MiniApp(
    val id: String,
    val name: String,
    val description: String,
    val htmlContent: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** If true, this app uses a workspace directory with multiple files instead of single htmlContent. */
    val isWorkspaceApp: Boolean = false,
    /** Entry file relative path within the workspace (e.g. "index.html"). */
    val entryFile: String = "index.html",
    /** Emoji icon for home screen shortcut. */
    val icon: String = ""
)
