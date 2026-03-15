package com.example.link_pi.data.session

import android.content.Context

/**
 * Manages app session lifecycle: suspend (save state before leaving),
 * resume (restore state when re-entering), and destroy (cleanup on delete).
 *
 * Works for both MiniApp (WebView-based) and built-in apps (SSH).
 */
class AppSessionManager(context: Context) {

    private val store = AppSessionStore(context)

    companion object {
        private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    /** Save app state when leaving / being evicted from pool. */
    fun suspend(appId: String, appType: AppType, state: Map<String, String>) {
        store.save(
            AppSession(
                appId = appId,
                appType = appType,
                lastActiveAt = System.currentTimeMillis(),
                state = state
            )
        )
    }

    /** Retrieve last saved state when re-entering an app. Returns null if no session. */
    fun resume(appId: String): AppSession? {
        return store.load(appId)
    }

    /** Remove session data when an app is explicitly deleted. */
    fun destroy(appId: String) {
        store.delete(appId)
    }

    /** List recently used apps sorted by lastActiveAt descending. */
    fun getRecentApps(limit: Int = 10): List<AppSession> {
        return store.loadAll()
            .sortedByDescending { it.lastActiveAt }
            .take(limit)
    }

    /** Remove sessions older than 30 days. Call on app startup. */
    fun cleanupExpired() {
        store.deleteExpired(MAX_AGE_MS)
    }
}
