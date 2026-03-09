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
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.link_pi.agent.ModuleStorage
import com.example.link_pi.bridge.NativeBridge
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.workspace.WorkspaceManager
import java.io.File

private const val NATIVE_FETCH_SCRIPT = """
<script>
window.__nfCbs={};
window.__nfCb=function(id,b){
    var c=window.__nfCbs[id];
    if(c){delete window.__nfCbs[id];try{c(JSON.parse(atob(b)))}catch(e){c({error:e.message})}}
};
window.nativeFetch=function(url,o){
    o=o||{};
    return new Promise(function(resolve,reject){
        if(!window.NativeBridge||!window.NativeBridge.httpRequest){
            reject(new Error('NativeBridge unavailable'));return;
        }
        var id='_'+Math.random().toString(36).substr(2,9);
        window.__nfCbs[id]=function(r){
            if(r.error)reject(new Error(r.error));
            else resolve({
                status:r.status,statusText:r.statusText,headers:r.headers,
                body:r.body,ok:r.status>=200&&r.status<300,
                json:function(){return Promise.resolve(JSON.parse(r.body))},
                text:function(){return Promise.resolve(r.body)}
            });
        };
        NativeBridge.httpRequest(id,url,o.method||'GET',
            JSON.stringify(o.headers||{}),o.body||'');
    });
};
window.callModule=function(moduleName,endpointName,params){
    return new Promise(function(resolve,reject){
        if(!window.NativeBridge||!window.NativeBridge.callModule){
            reject(new Error('NativeBridge unavailable'));return;
        }
        var id='_m'+Math.random().toString(36).substr(2,9);
        window.__nfCbs[id]=function(r){
            if(r.error)reject(new Error(r.error));
            else resolve(r);
        };
        NativeBridge.callModule(id,moduleName,endpointName,
            JSON.stringify(params||{}));
    });
};
window.listModules=function(){
    if(!window.NativeBridge||!window.NativeBridge.listModules) return [];
    try{return JSON.parse(NativeBridge.listModules())}catch(e){return []}
};
</script>
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniAppScreen(
    miniApp: MiniApp,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(miniApp.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (miniApp.isWorkspaceApp) {
                WorkspaceMiniAppWebView(appId = miniApp.id, entryFile = miniApp.entryFile)
            } else {
                MiniAppWebView(appId = miniApp.id, htmlContent = miniApp.htmlContent)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MiniAppWebView(
    appId: String = "default",
    htmlContent: String,
    onSendToApp: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val moduleStorage = remember { ModuleStorage(context) }
    val webView = remember {
        WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowContentAccess = false
                settings.allowFileAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true

                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            Log.d("MiniAppJS", "[${it.messageLevel()}] ${it.message()} (line ${it.lineNumber()})")
                        }
                        return true
                    }
                }

                val wv = this
                addJavascriptInterface(
                    NativeBridge(context, appId, onSendToApp,
                        jsEvaluator = { js -> wv.evaluateJavascript(js, null) },
                        moduleStorage = moduleStorage
                    ),
                    "NativeBridge"
                )

                // Inject error overlay + nativeFetch polyfill before the HTML content
                val errorOverlay = """
                    <script>
                    (function(){
                        var errBox = null;
                        function showErr(msg) {
                            if(window.NativeBridge&&window.NativeBridge.reportError){try{NativeBridge.reportError(msg);}catch(e){}}
                            if (!errBox) {
                                errBox = document.createElement('div');
                                errBox.id = '_err_overlay';
                                errBox.style.cssText = 'position:fixed;bottom:0;left:0;right:0;max-height:40vh;overflow:auto;background:rgba(0,0,0,0.85);color:#ff6b6b;font:12px monospace;padding:8px;z-index:999999;white-space:pre-wrap;';
                                var closeBtn = document.createElement('span');
                                closeBtn.textContent = ' [X] ';
                                closeBtn.style.cssText = 'color:#fff;cursor:pointer;float:right;font-weight:bold;';
                                closeBtn.onclick = function(){ errBox.style.display='none'; };
                                errBox.appendChild(closeBtn);
                                (document.body || document.documentElement).appendChild(errBox);
                            }
                            errBox.style.display = 'block';
                            var line = document.createElement('div');
                            line.textContent = msg;
                            errBox.appendChild(line);
                        }
                        window.onerror = function(msg, src, line, col, err) {
                            showErr('ERROR: ' + msg + ' (line ' + line + ')');
                        };
                        window.addEventListener('unhandledrejection', function(e) {
                            showErr('PROMISE: ' + (e.reason || e));
                        });
                        // Detect CDN load failures
                        document.addEventListener('error', function(e) {
                            var t = e.target;
                            if (t && (t.tagName === 'SCRIPT' || t.tagName === 'LINK')) {
                                showErr('LOAD FAILED: ' + (t.src || t.href));
                            }
                        }, true);
                    })();
                    </script>
                """.trimIndent()

                val scripts = errorOverlay + NATIVE_FETCH_SCRIPT
                val injectedHtml = htmlContent.replace(
                    Regex("<head([^>]*)>", RegexOption.IGNORE_CASE),
                    "<head$1>" + scripts
                ).let { result ->
                    if (result == htmlContent && !htmlContent.contains("<head", ignoreCase = true)) {
                        scripts + htmlContent
                    } else result
                }

                loadDataWithBaseURL(
                    "https://miniapp.local",
                    injectedHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.removeJavascriptInterface("NativeBridge")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * WebView for workspace-based multi-file apps.
 * Serves files from the workspace directory via a custom WebViewClient
 * that intercepts requests and loads from the local file system.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WorkspaceMiniAppWebView(
    appId: String,
    entryFile: String = "index.html",
    onSendToApp: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val workspaceManager = remember { WorkspaceManager(context) }
    val moduleStorage = remember { ModuleStorage(context) }
    val workspaceDir = remember { workspaceManager.getWorkspaceDir(appId) }
    val webView = remember {
        WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowContentAccess = false
                settings.allowFileAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url ?: return null
                        // Intercept requests to miniapp.local and serve from workspace
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
                                return WebResourceResponse(
                                    mimeType,
                                    "UTF-8",
                                    file.inputStream()
                                )
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            Log.d("MiniAppJS", "[${it.messageLevel()}] ${it.message()} (line ${it.lineNumber()})")
                        }
                        return true
                    }
                }

                val wv = this
                addJavascriptInterface(
                    NativeBridge(context, appId, onSendToApp,
                        jsEvaluator = { js -> wv.evaluateJavascript(js, null) },
                        moduleStorage = moduleStorage
                    ),
                    "NativeBridge"
                )

                // Read entry file and inject error overlay + nativeFetch
                val entryContent = workspaceManager.readEntryFile(appId, entryFile) ?: "<html><body><h1>Entry file not found</h1></body></html>"

                val errorOverlay = """
                    <script>
                    (function(){
                        var errBox = null;
                        function showErr(msg) {
                            if(window.NativeBridge&&window.NativeBridge.reportError){try{NativeBridge.reportError(msg);}catch(e){}}
                            if (!errBox) {
                                errBox = document.createElement('div');
                                errBox.id = '_err_overlay';
                                errBox.style.cssText = 'position:fixed;bottom:0;left:0;right:0;max-height:40vh;overflow:auto;background:rgba(0,0,0,0.85);color:#ff6b6b;font:12px monospace;padding:8px;z-index:999999;white-space:pre-wrap;';
                                var closeBtn = document.createElement('span');
                                closeBtn.textContent = ' [X] ';
                                closeBtn.style.cssText = 'color:#fff;cursor:pointer;float:right;font-weight:bold;';
                                closeBtn.onclick = function(){ errBox.style.display='none'; };
                                errBox.appendChild(closeBtn);
                                (document.body || document.documentElement).appendChild(errBox);
                            }
                            errBox.style.display = 'block';
                            var line = document.createElement('div');
                            line.textContent = msg;
                            errBox.appendChild(line);
                        }
                        window.onerror = function(msg, src, line, col, err) {
                            showErr('ERROR: ' + msg + ' (line ' + line + ')');
                        };
                        window.addEventListener('unhandledrejection', function(e) {
                            showErr('PROMISE: ' + (e.reason || e));
                        });
                        document.addEventListener('error', function(e) {
                            var t = e.target;
                            if (t && (t.tagName === 'SCRIPT' || t.tagName === 'LINK')) {
                                showErr('LOAD FAILED: ' + (t.src || t.href));
                            }
                        }, true);
                    })();
                    </script>
                """.trimIndent()

                val scripts = errorOverlay + NATIVE_FETCH_SCRIPT
                val injectedHtml = entryContent.replace(
                    Regex("<head([^>]*)>", RegexOption.IGNORE_CASE),
                    "<head$1>" + scripts
                ).let { result ->
                    if (result == entryContent && !entryContent.contains("<head", ignoreCase = true)) {
                        scripts + entryContent
                    } else result
                }

                // Load via https://miniapp.local so relative paths are intercepted
                loadDataWithBaseURL(
                    "https://miniapp.local/$entryFile",
                    injectedHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.removeJavascriptInterface("NativeBridge")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize()
    )
}
