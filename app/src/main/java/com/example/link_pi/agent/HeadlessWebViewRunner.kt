package com.example.link_pi.agent

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.link_pi.bridge.RuntimeErrorCollector
import com.example.link_pi.miniapp.SdkManager
import com.example.link_pi.miniapp.SdkModule
import com.example.link_pi.workspace.WorkspaceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Runs a workspace app in a headless (invisible) WebView to collect JS runtime errors.
 * Used by REFINEMENT to detect errors that static analysis cannot find.
 *
 * The WebView is created on the main thread, loaded, given a few seconds to execute JS,
 * then destroyed. All console errors are piped to [RuntimeErrorCollector].
 */
object HeadlessWebViewRunner {

    private const val LOAD_TIMEOUT_MS = 6000L

    /**
     * Load the app in a headless WebView for [LOAD_TIMEOUT_MS] and collect runtime errors.
     * Must be called from a coroutine. Returns the list of errors collected.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun collectRuntimeErrors(context: Context, appId: String): List<String> {
        val appCtx = context.applicationContext
        val wm = WorkspaceManager(appCtx)
        if (!wm.hasFiles(appId)) return emptyList()

        // Resolve actual entry file (may not be index.html)
        val allFiles = wm.getAllFiles(appId)
        val entryFile = if ("index.html" in allFiles) "index.html"
            else allFiles.firstOrNull { it.endsWith(".html", ignoreCase = true) }
            ?: return emptyList()

        // Build SDK scripts upfront for injection during request interception
        val sdkManager = SdkManager(appCtx)
        val sdkScripts = sdkManager.buildSdkScripts(SdkModule.entries.toSet())

        // Clear any stale errors first
        RuntimeErrorCollector.clear(appId)

        val pageLoaded = CompletableDeferred<Unit>()

        withContext(Dispatchers.Main) {
            val workspaceDir = File(wm.getWorkspacePath(appId))
            var webView: WebView? = null
            try {
                webView = WebView(appCtx).apply {
                // Minimal settings — no user interaction, just JS execution
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowContentAccess = false
                settings.allowFileAccess = false
                settings.blockNetworkLoads = true // No network in headless mode
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoaded.complete(Unit)
                    }
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        // Serve local workspace files
                        val url = request?.url ?: return null
                        if (url.host == "miniapp.local") {
                            val path = url.path?.trimStart('/') ?: return null
                            val file = File(workspaceDir, path)
                            if (file.exists() && file.isFile && file.canonicalPath.startsWith(workspaceDir.canonicalPath)) {
                                val mimeType = when (file.extension.lowercase()) {
                                    "html", "htm" -> "text/html"
                                    "css" -> "text/css"
                                    "js", "mjs" -> "application/javascript"
                                    "json" -> "application/json"
                                    else -> "application/octet-stream"
                                }
                                // Inject SDK scripts into entry HTML
                                if (path == entryFile && mimeType == "text/html") {
                                    val rawHtml = file.readText()
                                    val injectedHtml = injectSdkScripts(rawHtml, sdkScripts)
                                    return WebResourceResponse(mimeType, "UTF-8", injectedHtml.byteInputStream())
                                }
                                return WebResourceResponse(mimeType, "UTF-8", file.inputStream())
                            }
                        }
                        // Block all external requests in headless mode
                        return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                RuntimeErrorCollector.report(appId, "${it.message()} (line ${it.lineNumber()})")
                            }
                        }
                        return true
                    }
                }
            }

            // Navigate to entry file — shouldInterceptRequest handles serving + SDK injection
            webView.loadUrl("https://miniapp.local/$entryFile")

            // Wait for page load (with timeout), then allow JS to execute
            withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                pageLoaded.await()
                // Give JS time to run initialization code
                delay(2000)
            }

            } finally {
                // Always clean up WebView to prevent resource leaks
                webView?.let { wv ->
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
        }

        return RuntimeErrorCollector.getErrors(appId)
    }

    /** Inject SDK scripts right after <head> or at the beginning of HTML. */
    private fun injectSdkScripts(html: String, scripts: String): String {
        val headIdx = html.indexOf("<head", ignoreCase = true)
        if (headIdx >= 0) {
            val closeIdx = html.indexOf('>', headIdx)
            if (closeIdx >= 0) {
                return html.substring(0, closeIdx + 1) + "\n" + scripts + "\n" + html.substring(closeIdx + 1)
            }
        }
        return scripts + "\n" + html
    }
}
