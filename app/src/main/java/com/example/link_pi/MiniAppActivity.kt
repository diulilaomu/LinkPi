package com.example.link_pi

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.Toast
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.link_pi.agent.ModuleService
import com.example.link_pi.agent.ModuleStorage
import com.example.link_pi.bridge.NativeBridge
import com.example.link_pi.bridge.RuntimeErrorCollector
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.miniapp.RunningMiniApps
import com.example.link_pi.miniapp.SdkManager
import com.example.link_pi.miniapp.SdkModule
import com.example.link_pi.ui.miniapp.CdnProxy
import com.example.link_pi.ui.theme.LinkpiTheme
import com.example.link_pi.workspace.WorkspaceManager
import java.io.File

/**
 * Standalone Activity for running a MiniApp in its own system task.
 * Each instance appears as an independent card in the Android recent-apps screen.
 *
 * Launch via [MiniAppActivity.launch].
 */
open class MiniAppActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MiniAppActivity"
        private const val EXTRA_APP_ID = "app_id"
        private const val EXTRA_APP_NAME = "app_name"
        private const val EXTRA_IS_WORKSPACE = "is_workspace"
        private const val EXTRA_ENTRY_FILE = "entry_file"
        private const val EXTRA_HTML_CONTENT = "html_content"

        private val SLOT_CLASSES: Array<Class<out MiniAppActivity>> = arrayOf(
            MiniAppSlot0::class.java,
            MiniAppSlot1::class.java,
            MiniAppSlot2::class.java,
            MiniAppSlot3::class.java,
            MiniAppSlot4::class.java,
        )

        fun launch(context: Context, appId: String, appName: String,
                   isWorkspace: Boolean, entryFile: String = "index.html",
                   htmlContent: String = "") {
            val slot = (appId.hashCode() and 0x7FFFFFFF) % SLOT_CLASSES.size
            val slotClass = SLOT_CLASSES[slot]
            Log.d(TAG, "Launching MiniApp '$appName' (id=$appId) → slot=$slot class=${slotClass.simpleName}")

            val intent = Intent(context, slotClass).apply {
                putExtra(EXTRA_APP_ID, appId)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_IS_WORKSPACE, isWorkspace)
                putExtra(EXTRA_ENTRY_FILE, entryFile)
                putExtra(EXTRA_HTML_CONTENT, htmlContent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            context.startActivity(intent)
        }
    }

    private var webView: WebView? = null
    private var taskLabel: String = ""
    private var currentAppId: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.d(TAG, "onCreate: taskId=$taskId class=${this::class.simpleName} " +
                "flags=${intent.flags.toString(16)}")

        // Support two launch modes:
        // 1. Full extras from MiniAppActivity.launch() → EXTRA_APP_ID + EXTRA_APP_NAME etc.
        // 2. Shortcut with only miniapp_id → load from storage
        var appId = intent.getStringExtra(EXTRA_APP_ID)
        var appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "MiniApp"
        var isWorkspace = intent.getBooleanExtra(EXTRA_IS_WORKSPACE, false)
        var entryFile = intent.getStringExtra(EXTRA_ENTRY_FILE) ?: "index.html"
        var htmlContent = intent.getStringExtra(EXTRA_HTML_CONTENT) ?: ""

        // Shortcut launch: only miniapp_id is provided
        if (appId == null) {
            val shortcutId = intent.getStringExtra("miniapp_id")
            if (shortcutId != null) {
                val app = MiniAppStorage(this).loadById(shortcutId)
                if (app != null) {
                    appId = app.id
                    appName = app.name
                    isWorkspace = app.isWorkspaceApp
                    entryFile = app.entryFile
                    htmlContent = app.htmlContent
                }
            }
        }

        if (appId == null) { finish(); return }

        // Set task description so this shows as a named card in recents
        taskLabel = appName
        currentAppId = appId
        applyTaskDescription(appName)

        // Register in running apps tracker
        RunningMiniApps.add(appId, appName, this::class.java)

        val wv = if (isWorkspace) {
            createWorkspaceWebView(appId, entryFile)
        } else {
            createSingleHtmlWebView(appId, htmlContent)
        }
        webView = wv

        setContent {
            LinkpiTheme {
                WebViewContent(wv)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (taskLabel.isNotEmpty()) {
            applyTaskDescription(taskLabel)
        }
    }

    override fun onDestroy() {
        currentAppId?.let { RunningMiniApps.remove(it) }
        webView?.let { wv ->
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWorkspaceWebView(appId: String, entryFile: String): WebView {
        val workspaceManager = WorkspaceManager(this)
        val workspaceDir = workspaceManager.getWorkspaceDir(appId)
        val cdnProxy = CdnProxy(this)
        val sdkManager = SdkManager(this)
        val enabled = sdkManager.getEnabledModules(appId).let { saved ->
            if (saved.size <= 1) {
                val all = SdkModule.entries.toSet()
                sdkManager.saveEnabledModules(appId, all)
                all
            } else saved
        }
        val sdkScripts = sdkManager.buildSdkScripts(enabled)

        val workspaceClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
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
                            "png" -> "image/png"
                            "jpg", "jpeg" -> "image/jpeg"
                            "gif" -> "image/gif"
                            "svg" -> "image/svg+xml"
                            "woff" -> "font/woff"
                            "woff2" -> "font/woff2"
                            "ttf" -> "font/ttf"
                            else -> "application/octet-stream"
                        }
                        if (path == entryFile && mimeType == "text/html") {
                            val rawHtml = file.readText()
                            val injectedHtml = injectScripts(rawHtml, sdkScripts)
                            return WebResourceResponse(mimeType, "UTF-8", injectedHtml.byteInputStream())
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

        return buildWebView(appId, workspaceClient).also { wv ->
            wv.loadUrl("https://miniapp.local/$entryFile")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createSingleHtmlWebView(appId: String, htmlContent: String): WebView {
        val cdnProxy = CdnProxy(this)
        val cdnClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
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

        val sdkManager = SdkManager(this)
        val scripts = sdkManager.buildSdkScripts(SdkModule.entries.toSet())
        sdkManager.saveEnabledModules(appId, SdkModule.entries.toSet())
        val injectedHtml = injectScripts(htmlContent, scripts)

        return buildWebView(appId, cdnClient).also { wv ->
            wv.loadDataWithBaseURL("https://miniapp.local", injectedHtml, "text/html", "UTF-8", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(appId: String, client: WebViewClient): WebView {
        val moduleStorage = ModuleStorage(this)
        val moduleService = ModuleService(moduleStorage)

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.blockNetworkLoads = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            }

            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    Log.e(TAG, "Render process gone for $appId (crashed=${detail?.didCrash()})")
                    RuntimeErrorCollector.report(appId, "WebView render process gone")
                    return true
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    return client.shouldInterceptRequest(view, request)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d("MiniAppJS", "[${it.messageLevel()}] ${it.message()} (line ${it.lineNumber()})")
                        if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            RuntimeErrorCollector.report(appId, "${it.message()} (line ${it.lineNumber()})")
                        }
                    }
                    return true
                }
            }
        }

        val bridge = NativeBridge(this, appId, { /* no send-to-app in standalone */ },
            jsEvaluator = { js -> wv.evaluateJavascript(js, null) },
            moduleStorage = moduleStorage,
            moduleService = moduleService
        )
        wv.addJavascriptInterface(bridge, "NativeBridge")
        return wv
    }

    private fun injectScripts(html: String, scripts: String): String {
        val injected = html.replace(
            Regex("<head([^>]*)>", RegexOption.IGNORE_CASE),
            "<head$1>" + scripts
        )
        return if (injected == html && !html.contains("<head", ignoreCase = true)) {
            scripts + html
        } else injected
    }

    private fun applyTaskDescription(label: String) {
        try {
            val icon = createTaskIcon(label)
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(label, icon))
        } catch (_: Exception) { /* some ROMs may throw */ }
    }

    /** Render the first character of appName (or emoji) as a square Bitmap for the recents icon. */
    private fun createTaskIcon(label: String): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Background: rounded rect with a theme color
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6750A4.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 24f, 24f, bgPaint)
        // Text: first grapheme cluster (supports emoji)
        val displayChar = label.firstGrapheme()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 72f
            textAlign = Paint.Align.CENTER
        }
        val fontMetrics = textPaint.fontMetrics
        val textY = (size - fontMetrics.top - fontMetrics.bottom) / 2f
        canvas.drawText(displayChar, size / 2f, textY, textPaint)
        return bitmap
    }

    private fun String.firstGrapheme(): String {
        if (isEmpty()) return "A"
        val breaker = java.text.BreakIterator.getCharacterInstance()
        breaker.setText(this)
        val end = breaker.next()
        return if (end != java.text.BreakIterator.DONE) substring(0, end) else substring(0, 1)
    }
}

@Composable
private fun WebViewContent(webView: WebView) {
    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize()
    )
}
