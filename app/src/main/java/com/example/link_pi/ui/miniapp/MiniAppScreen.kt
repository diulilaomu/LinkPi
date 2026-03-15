package com.example.link_pi.ui.miniapp

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.example.link_pi.agent.ModuleService
import com.example.link_pi.agent.ModuleStorage
import com.example.link_pi.bridge.NativeBridge
import com.example.link_pi.bridge.RuntimeErrorCollector

import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.miniapp.SdkManager
import com.example.link_pi.miniapp.SdkModule
import com.example.link_pi.miniapp.WebViewEntry
import com.example.link_pi.miniapp.WebViewPool
import com.example.link_pi.workspace.WorkspaceManager
import java.io.File

/**
 * Always-mounted overlay host. Renders ALL alive MiniApp WebViews as stacked layers.
 * Foreground app: alpha=1, zIndex=1 (visible + interactive).
 * Background apps: alpha=0, zIndex=0 (invisible but DOM/JS alive).
 *
 * Call this once from NavGraph inside a Box that fills the screen.
 * The [visible] flag controls whether ANY overlay is shown (false = all hidden).
 */
@Composable
fun MiniAppOverlayHost(
    pool: WebViewPool,
    visible: Boolean,
    currentApp: MiniApp?,
) {
    val aliveEntries = pool.entries
    // Use both pool.foregroundAppId (Compose state) and currentApp?.id for
    // immediate foreground determination — whichever matches first wins.
    val fgId = if (visible) (currentApp?.id ?: pool.foregroundAppId) else null

    for ((appId, entry) in aliveEntries) {
        val isForeground = visible && appId == fgId
        key(appId) {
            // Cold restore only when foreground
            if (isForeground && currentApp != null && currentApp.id == appId) {
                ColdRestoreEffect(entry)
            }
            Box(
                modifier = if (isForeground) {
                    Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                } else {
                    // Background: zero size to avoid intercepting touch events
                    Modifier.size(0.dp)
                }
            ) {
                // Single AndroidView per entry — avoids dispose/recreate cycle
                // that causes WebView rendering surface to ghost on transitions.
                // View.GONE ensures the platform view stops drawing entirely.
                AndroidView(
                    factory = { entry.webView },
                    update = { view ->
                        view.visibility = if (isForeground)
                            android.view.View.VISIBLE else android.view.View.GONE
                    },
                    modifier = if (isForeground) Modifier.fillMaxSize()
                        else Modifier.size(0.dp)
                )
            }
        }
    }
}

/** Inject script tags into HTML, before closing </head> or at the start if no <head>. */
private fun injectScriptsIntoHtml(html: String, scripts: String): String {
    val injected = html.replace(
        Regex("<head([^>]*)>", RegexOption.IGNORE_CASE),
        "<head$1>" + scripts
    )
    return if (injected == html && !html.contains("<head", ignoreCase = true)) {
        scripts + html
    } else injected
}

/** Create a WebViewEntry for a single-HTML MiniApp. Called from pool.show() factory. */
@SuppressLint("SetJavaScriptEnabled")
fun createMiniAppEntry(
    context: android.content.Context,
    appId: String,
    htmlContent: String,
    onSendToApp: (String) -> Unit = {}
): WebViewEntry {
    val moduleStorage = ModuleStorage(context)
    val moduleService = ModuleService(moduleStorage)
    val cdnProxy = CdnProxy(context)
    val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowContentAccess = false
        settings.allowFileAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.blockNetworkLoads = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val scheme = url.scheme?.lowercase()
                if (scheme == "https" || scheme == "http") {
                    return cdnProxy.fetch(url.toString())
                        ?: super.shouldInterceptRequest(view, request)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("MiniAppJS", "[${it.messageLevel()}] ${it.message()} (line ${it.lineNumber()})")
                    if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        RuntimeErrorCollector.report(appId, "${it.message()} (line ${it.lineNumber()})")
                    } else {
                        RuntimeErrorCollector.log(appId, it.messageLevel().name, it.message(), it.lineNumber())
                    }
                }
                return true
            }
        }
    }
    val bridge = NativeBridge(context, appId, onSendToApp,
        jsEvaluator = { js -> webView.evaluateJavascript(js, null) },
        moduleStorage = moduleStorage,
        moduleService = moduleService
    )
    webView.addJavascriptInterface(bridge, "NativeBridge")

    // Inject all SDK scripts — real capability gating is at NativeBridge level
    val sdkManager = SdkManager(context)
    val scripts = sdkManager.buildSdkScripts(SdkModule.entries.toSet())
    sdkManager.saveEnabledModules(appId, SdkModule.entries.toSet())
    val injectedHtml = injectScriptsIntoHtml(htmlContent, scripts)

    webView.loadDataWithBaseURL(
        "https://miniapp.local",
        injectedHtml,
        "text/html",
        "UTF-8",
        null
    )

    return WebViewEntry(appId, webView, bridge)
}

/** Create a WebViewEntry for a workspace-based multi-file MiniApp. */
@SuppressLint("SetJavaScriptEnabled")
fun createWorkspaceMiniAppEntry(
    context: android.content.Context,
    appId: String,
    entryFile: String = "index.html",
    onSendToApp: (String) -> Unit = {}
): WebViewEntry {
    val workspaceManager = WorkspaceManager(context)
    val moduleStorage = ModuleStorage(context)
    val moduleService = ModuleService(moduleStorage)
    val workspaceDir = workspaceManager.getWorkspaceDir(appId)
    val cdnProxy = CdnProxy(context)
    val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowContentAccess = false
        settings.allowFileAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.blockNetworkLoads = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host == "miniapp.local") {
                    val path = url.path?.trimStart('/') ?: return null
                    val file = File(workspaceDir, path)
                    if (file.exists() && file.isFile && file.canonicalPath.startsWith(workspaceDir.canonicalPath)) {
                        val mimeType = when (file.extension.lowercase()) {
                            "html", "htm" -> "text/html"
                            "css" -> "text/css"
                            "js" -> "application/javascript"
                            "json" -> "application/json"
                            "png" -> "image/png"
                            "jpg", "jpeg" -> "image/jpeg"
                            "gif" -> "image/gif"
                            "svg" -> "image/svg+xml"
                            "woff" -> "font/woff"
                            "woff2" -> "font/woff2"
                            "ttf" -> "font/ttf"
                            else -> "application/octet-stream"
                        }
                        return WebResourceResponse(mimeType, "UTF-8", file.inputStream())
                    }
                }
                val scheme = url.scheme?.lowercase()
                if (scheme == "https" || scheme == "http") {
                    return cdnProxy.fetch(url.toString())
                        ?: super.shouldInterceptRequest(view, request)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("MiniAppJS", "[${it.messageLevel()}] ${it.message()} (line ${it.lineNumber()})")
                    if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        RuntimeErrorCollector.report(appId, "${it.message()} (line ${it.lineNumber()})")
                    } else {
                        RuntimeErrorCollector.log(appId, it.messageLevel().name, it.message(), it.lineNumber())
                    }
                }
                return true
            }
        }
    }
    val bridge = NativeBridge(context, appId, onSendToApp,
        jsEvaluator = { js -> webView.evaluateJavascript(js, null) },
        moduleStorage = moduleStorage,
        moduleService = moduleService
    )
    webView.addJavascriptInterface(bridge, "NativeBridge")

    val entryContent = workspaceManager.readEntryFile(appId, entryFile)
        ?: "<html><body><h1>Entry file not found</h1></body></html>"

    // Inject all SDK scripts — real capability gating is at NativeBridge level
    val sdkManager = SdkManager(context)
    val enabled = sdkManager.getEnabledModules(appId).let { saved ->
        if (saved.size <= 1) {
            // First run: enable all modules by default
            val all = SdkModule.entries.toSet()
            sdkManager.saveEnabledModules(appId, all)
            all
        } else saved
    }
    val scripts = sdkManager.buildSdkScripts(enabled)
    val injectedHtml = injectScriptsIntoHtml(entryContent, scripts)

    webView.loadDataWithBaseURL(
        "https://miniapp.local/$entryFile",
        injectedHtml,
        "text/html",
        "UTF-8",
        null
    )

    return WebViewEntry(appId, webView, bridge)
}

/**
 * Applies cold-restore session state to a newly created WebViewEntry.
 * Uses WebViewClient.onPageFinished instead of hardcoded delay.
 * On hot path (pendingRestore == null), this is a no-op.
 */
@Composable
private fun ColdRestoreEffect(entry: WebViewEntry) {
    val restore = entry.pendingRestore ?: return
    LaunchedEffect(entry.appId) {
        // Use a CompletableDeferred-like approach: register onPageFinished callback
        val latch = kotlinx.coroutines.CompletableDeferred<Unit>()
        val originalClient = entry.webView.webViewClient
        entry.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Restore original client first
                entry.webView.webViewClient = originalClient
                latch.complete(Unit)
            }
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                return originalClient.shouldInterceptRequest(view, request)
            }
        }

        // Wait for page to finish loading (with 3s safety timeout)
        kotlinx.coroutines.withTimeoutOrNull(3000) { latch.await() }

        val hash = restore.state["url_hash"]
        if (!hash.isNullOrEmpty()) {
            val safeHash = hash.replace("\\", "\\\\").replace("'", "\\'")
            entry.webView.evaluateJavascript("window.location.hash='$safeHash';", null)
        }
        val scrollY = restore.state["scroll_y"]
        if (!scrollY.isNullOrEmpty()) {
            entry.webView.evaluateJavascript("window.scrollTo(0,$scrollY);", null)
        }
        val jsState = restore.state["js_state"]
        if (!jsState.isNullOrEmpty()) {
            val escaped = jsState.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            entry.webView.evaluateJavascript(
                "if(typeof window.__onResume==='function'){window.__onResume('$escaped');}",
                null
            )
        }
        entry.pendingRestore = null
    }
}

/**
 * Proxies external (CDN) requests through OkHttp with disk caching.
 * WebView sometimes fails to load CDN scripts (DNS/TLS issues on emulators).
 * This fetches via OkHttp and caches to `cdn_cache/` for offline reuse.
 */
private class CdnProxy(context: android.content.Context) {

    private val cacheDir = File(context.cacheDir, "cdn_cache")
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        private const val MAX_CACHE_BYTES = 100L * 1024 * 1024  // 100 MB
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }

    fun fetch(url: String): WebResourceResponse? {
        val safeKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val cached = File(cacheDir, safeKey)
        val metaFile = File(cacheDir, "$safeKey.meta")

        // Serve from cache (if not expired)
        if (cached.exists() && metaFile.exists()) {
            if (System.currentTimeMillis() - cached.lastModified() > MAX_AGE_MS) {
                cached.delete(); metaFile.delete()
            } else {
                val mime = metaFile.readText().trim()
                return WebResourceResponse(mime, "UTF-8", cached.inputStream())
            }
        }

        // Download via OkHttp (bypasses WebView network stack issues)
        return try {
            val request = okhttp3.Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val contentType = response.header("Content-Type") ?: guessMime(url)
            val mime = contentType.substringBefore(";").trim()
            val bytes = response.body?.bytes() ?: return null

            cacheDir.mkdirs()
            cached.writeBytes(bytes)
            metaFile.writeText(mime)
            trimCache()

            WebResourceResponse(mime, "UTF-8", java.io.ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            Log.w("CdnProxy", "Failed to fetch $url: ${e.message}")
            null
        }
    }

    /** Evict oldest files when cache exceeds size limit. */
    private fun trimCache() {
        try {
            val files = cacheDir.listFiles() ?: return
            val totalSize = files.sumOf { it.length() }
            if (totalSize <= MAX_CACHE_BYTES) return
            val sorted = files.sortedBy { it.lastModified() }
            var freed = 0L
            val target = totalSize - MAX_CACHE_BYTES
            for (f in sorted) {
                if (freed >= target) break
                freed += f.length()
                f.delete()
            }
        } catch (_: Exception) { /* best-effort */ }
    }

    private fun guessMime(url: String): String {
        val path = Uri.parse(url).path?.lowercase() ?: ""
        return when {
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }
}
