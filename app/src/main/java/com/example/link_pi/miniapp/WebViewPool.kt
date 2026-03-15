package com.example.link_pi.miniapp

import android.content.Context
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.link_pi.data.session.AppSessionManager
import com.example.link_pi.data.session.AppType
import com.example.link_pi.ui.ssh.KeepAliveService

/**
 * LRU pool of live WebView instances. Keeps up to [maxAlive] WebViews
 * in memory for instant switching. WebViews stay attached to the View tree
 * at all times (visibility controlled by Compose layer) — no removeView/addView
 * cycle, so zero white-flash on hot switch.
 *
 * When the pool is full, the least-recently-used entry is evicted:
 * its state is serialized to AppSessionManager before destruction.
 */
class WebViewPool(
    private val maxAlive: Int = 3,
    private val sessionManager: AppSessionManager,
    private val context: Context
) {
    // accessOrder = true → LinkedHashMap acts as LRU (most recent access moves to end)
    private val pool = LinkedHashMap<String, WebViewEntry>(maxAlive + 1, 0.75f, true)

    /** Observable snapshot of alive appIds → entries, drives Compose overlay rendering. */
    val entries = mutableStateMapOf<String, WebViewEntry>()

    /** The currently foregrounded appId (visible), or null if none. */
    var foregroundAppId: String? by mutableStateOf(null)
        private set

    /**
     * Bring an app to foreground. If not yet in pool, runs [factory] to create it.
     * Returns the entry. The Compose layer observes [entries] and [foregroundAppId]
     * to show/hide overlays — no View detach/attach needed.
     */
    fun show(appId: String, factory: () -> WebViewEntry): WebViewEntry {
        val existing = pool[appId]
        if (existing != null) {
            existing.lastAccess = System.currentTimeMillis()
            if (foregroundAppId != appId) {
                pauseForeground()
                foregroundAppId = appId
                existing.notifyResume()
            }
            return existing
        }

        // Pool full → evict least recently used
        if (pool.size >= maxAlive) {
            evictLeastRecent()
        }

        pauseForeground()

        val entry = factory()
        // Check if there's a cold-restore session available
        val session = sessionManager.resume(appId)
        if (session != null) {
            entry.pendingRestore = session
        }
        pool[appId] = entry
        entries[appId] = entry
        foregroundAppId = appId
        KeepAliveService.addClient(context, "miniapp:$appId", entry.webView.url ?: appId)
        return entry
    }

    /** Hide the current foreground app (user navigated away from MiniApp route). */
    fun hideAll() {
        pauseForeground()
        foregroundAppId = null
    }

    /** Check if an appId is currently alive in the pool. */
    fun isAlive(appId: String): Boolean = pool.containsKey(appId)

    /** Pause the current foreground entry (collect state + dispatch pause event). */
    private fun pauseForeground() {
        val fgId = foregroundAppId ?: return
        pool[fgId]?.let { entry ->
            // Fire async JS evals for scroll_y + __onSuspend; callbacks populate _collectedState.
            // On next eviction these accumulated values will be picked up by getSuspendedState().
            entry.prepareForSuspend()
            entry.notifyPause()
        }
    }

    /**
     * Evict the least recently used entry. Collects state before destruction.
     *
     * Note on state collection: The evicted entry was previously paused via [pauseForeground],
     * which fired async JS evaluations (scroll_y, __onSuspend). If sufficient time elapsed
     * between pause and eviction (e.g., user opened/closed several apps), those callbacks
     * will have completed, and [getSuspendedState] captures them. If eviction happens
     * immediately after pause (same Looper pass — pool full during show()), the async
     * values may be missed, but synchronous data (url_hash, NativeBridge SharedPreferences)
     * is always available. This is an acceptable trade-off to avoid blocking the main thread.
     */
    private fun evictLeastRecent() {
        val eldest = pool.entries.firstOrNull() ?: return
        val entry = eldest.value
        val state = entry.getSuspendedState()
        sessionManager.suspend(eldest.key, AppType.MINI_APP, state)
        (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
        entry.destroy()
        KeepAliveService.removeClient(context, "miniapp:${eldest.key}")
        pool.remove(eldest.key)
        entries.remove(eldest.key)
    }

    /** Destroy a specific app's WebView and session (e.g., when app is deleted). */
    fun destroy(appId: String) {
        if (foregroundAppId == appId) foregroundAppId = null
        pool.remove(appId)?.let { entry ->
            (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
            entry.destroy()
        }
        entries.remove(appId)
        sessionManager.destroy(appId)
        KeepAliveService.removeClient(context, "miniapp:$appId")
        // Clean up NativeBridge SharedPreferences for this app
        context.deleteSharedPreferences("miniapp_data_$appId")
    }

    /** Destroy all WebViews. Call from Activity.onDestroy. */
    fun destroyAll() {
        foregroundAppId = null
        pool.forEach { (appId, entry) ->
            KeepAliveService.removeClient(context, "miniapp:$appId")
            (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
            entry.destroy()
        }
        pool.clear()
        entries.clear()
    }

    /**
     * Reload an app by destroying its existing WebView entry from the pool.
     * The next [show] call will create a fresh WebView and re-load the app content.
     */
    fun evict(appId: String) {
        if (foregroundAppId == appId) foregroundAppId = null
        pool.remove(appId)?.let { entry ->
            (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
            entry.destroy()
        }
        entries.remove(appId)
        KeepAliveService.removeClient(context, "miniapp:$appId")
    }
}
