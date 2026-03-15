package com.example.link_pi.miniapp

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.example.link_pi.bridge.NativeBridge
import com.example.link_pi.data.session.AppSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An entry in the WebViewPool. Holds a live WebView + its NativeBridge,
 * plus metadata for LRU tracking and cold-restore.
 */
class WebViewEntry(
    val appId: String,
    val webView: WebView,
    val nativeBridge: NativeBridge,
    var lastAccess: Long = System.currentTimeMillis(),
    /** Non-null if this entry was just created and has a cold-restore session to apply. */
    var pendingRestore: AppSession? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    // State collected asynchronously during release()
    private val _collectedState = mutableMapOf<String, String>()

    /**
     * Collect state for suspension. Fires JS evaluations and waits for callbacks
     * to complete (with a timeout). Must be called from the main thread.
     *
     * Returns the collected state map including NativeBridge session state.
     */
    suspend fun prepareAndCollectState(timeoutMs: Long = 2000): Map<String, String> {
        _collectedState.clear()

        // URL hash — synchronous, no JS eval needed
        val url = webView.url ?: ""
        val hashIndex = url.indexOf('#')
        if (hashIndex >= 0) {
            val hash = url.substring(hashIndex)
            if (hash.isNotEmpty()) _collectedState["url_hash"] = hash
        }

        // Scroll position — async JS eval, await completion
        val scrollDeferred = CompletableDeferred<String>()
        webView.evaluateJavascript("(function(){ return String(window.scrollY || 0); })()") { scrollY ->
            scrollDeferred.complete(scrollY?.trim('"') ?: "0")
        }

        // Trigger JS-side suspend callback
        val suspendDeferred = CompletableDeferred<Unit>()
        webView.evaluateJavascript(
            "(function(){ if(typeof window.__onSuspend==='function'){window.__onSuspend();} })()"
        ) { suspendDeferred.complete(Unit) }

        // Wait for both callbacks with timeout
        withTimeoutOrNull(timeoutMs) {
            val scrollVal = scrollDeferred.await()
            if (scrollVal != "0") _collectedState["scroll_y"] = scrollVal
            suspendDeferred.await()
        }

        // Add NativeBridge session state
        val jsState = nativeBridge.loadData("__session_state")
        if (jsState.isNotEmpty()) _collectedState["js_state"] = jsState

        return _collectedState.toMap()
    }

    /**
     * Begin async state collection (fire-and-forget).
     * Used for pause scenarios where we don't need immediate results.
     */
    fun prepareForSuspend() {
        // URL hash — extracted from webView.url synchronously (no JS eval needed)
        val url = webView.url ?: ""
        val hashIndex = url.indexOf('#')
        if (hashIndex >= 0) {
            val hash = url.substring(hashIndex)
            if (hash.isNotEmpty()) _collectedState["url_hash"] = hash
        }

        // Scroll position — async JS eval, callback updates _collectedState
        webView.evaluateJavascript("(function(){ return String(window.scrollY || 0); })()") { scrollY ->
            val cleaned = scrollY?.trim('"') ?: "0"
            if (cleaned != "0") _collectedState["scroll_y"] = cleaned
        }

        // Trigger JS-side suspend callback so the app can save its own state
        webView.evaluateJavascript(
            "(function(){ if(typeof window.__onSuspend==='function'){window.__onSuspend();} })()",
            null
        )
    }

    /**
     * Return collected state for serialization. Called synchronously during eviction.
     * Includes any data gathered by prepareForSuspend() plus fresh NativeBridge prefs.
     */
    fun getSuspendedState(): Map<String, String> {
        val jsState = nativeBridge.loadData("__session_state")
        if (jsState.isNotEmpty()) _collectedState["js_state"] = jsState
        return _collectedState.toMap()
    }

    /** Dispatch pause event to JS when WebView is released to background in pool. */
    fun notifyPause() {
        mainHandler.post {
            webView.evaluateJavascript(
                "document.dispatchEvent(new Event('pause'));",
                null
            )
        }
    }

    /** Dispatch resume event to JS when WebView is re-acquired from pool. */
    fun notifyResume() {
        mainHandler.post {
            webView.evaluateJavascript(
                "document.dispatchEvent(new Event('resume'));",
                null
            )
        }
    }

    /** Destroy WebView and cleanup resources. */
    fun destroy() {
        nativeBridge.webSocketBridge?.stop()
        webView.removeJavascriptInterface("NativeBridge")
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.destroy()
    }
}
