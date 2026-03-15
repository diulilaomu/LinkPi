package com.example.link_pi.data.session

/**
 * Represents a saved session state for any app (MiniApp or built-in).
 * Used by AppSessionStore for cold-restore after WebView eviction or process death.
 */
data class AppSession(
    val appId: String,
    val appType: AppType,
    val lastActiveAt: Long = System.currentTimeMillis(),
    val state: Map<String, String> = emptyMap()
)

enum class AppType {
    MINI_APP,
    BUILTIN_SSH
}
