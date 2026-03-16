package com.example.link_pi.bridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.example.link_pi.agent.ModuleService
import com.example.link_pi.agent.ModuleStorage
import com.example.link_pi.util.SecurityUtils
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.link_pi.miniapp.SdkManager
import com.example.link_pi.miniapp.SdkModule
import org.json.JSONObject

class NativeBridge(
    private val context: Context,
    private val appId: String = "default",
    private val onSendToApp: (String) -> Unit = {},
    private val jsEvaluator: ((String) -> Unit)? = null,
    private val moduleStorage: ModuleStorage? = null,
    private val moduleService: ModuleService? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    var webSocketBridge: WebSocketBridge? = null

    /** Cached SDK module config — read once at creation, immutable at runtime. */
    private val enabledModules: Set<SdkModule> by lazy {
        SdkManager(context).getEnabledModules(appId)
    }

    private fun requireModule(module: SdkModule): Boolean = module in enabledModules

    private fun getPrefs() =
        context.getSharedPreferences("miniapp_data_$appId", Context.MODE_PRIVATE)

    @JavascriptInterface
    fun showToast(message: String) {
        if (!requireModule(SdkModule.DEVICE)) return
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun vibrate(milliseconds: Long) {
        if (!requireModule(SdkModule.DEVICE)) return
        val ms = milliseconds.coerceIn(0, 5000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        if (!requireModule(SdkModule.DEVICE)) return "{}"
        return JSONObject().apply {
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("manufacturer", Build.MANUFACTURER)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE)
        }.toString()
    }

    @JavascriptInterface
    fun saveData(key: String, value: String) {
        if (!requireModule(SdkModule.STORAGE)) return
        if (key.length > 256 || value.length > 1024 * 1024) return // key max 256 chars, value max 1MB
        getPrefs().edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun loadData(key: String): String {
        if (!requireModule(SdkModule.STORAGE)) return ""
        return getPrefs().getString(key, "") ?: ""
    }

    @JavascriptInterface
    fun removeData(key: String) {
        if (!requireModule(SdkModule.STORAGE)) return
        getPrefs().edit().remove(key).apply()
    }

    @JavascriptInterface
    fun clearData() {
        if (!requireModule(SdkModule.STORAGE)) return
        getPrefs().edit().clear().apply()
    }

    @JavascriptInterface
    fun listKeys(): String {
        if (!requireModule(SdkModule.STORAGE)) return ""
        return getPrefs().all.keys.joinToString(",")
    }

    @JavascriptInterface
    fun getAppId(): String {
        return appId
    }

    @JavascriptInterface
    fun writeClipboard(text: String) {
        if (!requireModule(SdkModule.DEVICE)) return
        mainHandler.post {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
        }
    }

    @JavascriptInterface
    fun getBatteryLevel(): Int {
        if (!requireModule(SdkModule.DEVICE)) return -1
        val batteryManager =
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    @JavascriptInterface
    fun getLocation(): String {
        if (!requireModule(SdkModule.DEVICE)) return "{\"error\":\"not_enabled\"}"
        return try {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val location =
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (location != null) {
                    JSONObject().apply {
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy.toDouble())
                    }.toString()
                } else JSONObject().apply { put("error", "no_location") }.toString()
            } else JSONObject().apply { put("error", "no_permission") }.toString()
        } catch (_: Exception) {
            JSONObject().apply { put("error", "exception") }.toString()
        }
    }

    @JavascriptInterface
    fun sendToApp(data: String) {
        onSendToApp(data)
    }

    @JavascriptInterface
    fun listModules(): String {
        if (!requireModule(SdkModule.MODULES)) return "[]"
        val ms = moduleService ?: return "[]"
        return ms.statusJson()
    }

    @JavascriptInterface
    fun callModule(callbackId: String, moduleName: String, path: String, method: String, body: String) {
        if (!callbackId.matches(Regex("^[a-zA-Z0-9_]+$"))) return
        if (!requireModule(SdkModule.MODULES)) {
            callbackToJs(callbackId, """{"error":"Module access not enabled for this app"}""")
            return
        }
        ioExecutor.execute {
            try {
                val ms = moduleStorage
                val svc = moduleService
                if (ms == null || svc == null) {
                    callbackToJs(callbackId, """{"error":"Module system not available"}""")
                    return@execute
                }
                val module = ms.loadById(moduleName) ?: ms.findByName(moduleName)
                if (module == null) {
                    callbackToJs(callbackId, """{"error":"Module '$moduleName' not found"}""")
                    return@execute
                }
                val result = svc.callHttp(module.id, path, method, body.ifBlank { null })
                callbackToJs(callbackId, result)
            } catch (e: Exception) {
                callbackToJs(callbackId, org.json.JSONObject().apply { put("error", e.message ?: "Unknown error") }.toString())
            }
        }
    }

    @JavascriptInterface
    fun httpRequest(callbackId: String, url: String, method: String, headers: String, body: String) {
        if (!callbackId.matches(Regex("^[a-zA-Z0-9_]+$"))) return
        if (!requireModule(SdkModule.NETWORK)) {
            callbackToJs(callbackId, """{"error":"Network module not enabled"}""")
            return
        }
        val uri = Uri.parse(url)
        if (uri.scheme != "https") {
            callbackToJs(callbackId, JSONObject().put("error", "Only https allowed").toString())
            return
        }
        // Block requests to private/loopback IPs to prevent SSRF
        val host = uri.host?.lowercase() ?: ""
        if (isPrivateHost(host)) {
            callbackToJs(callbackId, JSONObject().put("error", "Requests to private networks are blocked").toString())
            return
        }
        ioExecutor.execute {
            try {
                val headerMap = try { JSONObject(headers) } catch (_: Exception) { JSONObject() }
                val reqBuilder = Request.Builder().url(url)
                headerMap.keys().forEach { key ->
                    reqBuilder.addHeader(key, headerMap.getString(key))
                }
                val reqBody = when (method.uppercase()) {
                    "GET", "HEAD" -> null
                    else -> {
                        val ct = if (headerMap.has("Content-Type")) headerMap.getString("Content-Type")
                                 else "application/json"
                        body.toRequestBody(ct.toMediaTypeOrNull())
                    }
                }
                reqBuilder.method(method.uppercase(), reqBody)
                val response = httpClient.newCall(reqBuilder.build()).execute()
                val respBody = response.body?.use { rb ->
                    val src = rb.source()
                    src.request(5L * 1024 * 1024) // 5MB limit
                    src.buffer.snapshot().utf8()
                } ?: ""
                val respHeaders = JSONObject()
                for (i in 0 until response.headers.size) {
                    respHeaders.put(response.headers.name(i), response.headers.value(i))
                }
                val result = JSONObject().apply {
                    put("status", response.code)
                    put("statusText", response.message)
                    put("headers", respHeaders)
                    put("body", respBody)
                }
                callbackToJs(callbackId, result.toString())
            } catch (e: Exception) {
                callbackToJs(callbackId, JSONObject().put("error", e.message ?: "Request failed").toString())
            }
        }
    }

    // ── WebSocket Server ──────────────────────────────

    @JavascriptInterface
    fun startWebSocketServer(callbackId: String, port: Int) {
        if (!callbackId.matches(Regex("^[a-zA-Z0-9_]+$"))) return
        if (!requireModule(SdkModule.REALTIME)) {
            callbackToJs(callbackId, """{"ok":false,"error":"Realtime module not enabled"}""")
            return
        }
        if (port !in 1024..65535) {
            callbackToJs(callbackId, """{"ok":false,"error":"Port must be 1024-65535"}""")
            return
        }
        ioExecutor.execute {
            val ws = WebSocketBridge(jsEvaluator)
            val result = ws.start(port)
            if (result.isSuccess) {
                webSocketBridge = ws
                val ip = WebSocketBridge.getLocalIpAddress()
                callbackToJs(callbackId, JSONObject().apply {
                    put("ok", true)
                    put("port", result.getOrDefault(port))
                    put("ip", ip)
                }.toString())
            } else {
                callbackToJs(callbackId, JSONObject().apply {
                    put("ok", false)
                    put("error", result.exceptionOrNull()?.message ?: "Failed to start server")
                }.toString())
            }
        }
    }

    @JavascriptInterface
    fun stopWebSocketServer() {
        if (!requireModule(SdkModule.REALTIME)) return
        webSocketBridge?.stop()
        webSocketBridge = null
    }

    @JavascriptInterface
    fun serverSend(clientId: String, message: String) {
        if (!requireModule(SdkModule.REALTIME)) return
        webSocketBridge?.send(clientId, message)
    }

    @JavascriptInterface
    fun serverBroadcast(message: String) {
        if (!requireModule(SdkModule.REALTIME)) return
        webSocketBridge?.broadcast(message)
    }

    @JavascriptInterface
    fun getLocalIpAddress(): String {
        if (!requireModule(SdkModule.REALTIME)) return ""
        return WebSocketBridge.getLocalIpAddress()
    }

    @JavascriptInterface
    fun reportError(error: String) {
        RuntimeErrorCollector.report(appId, error)
    }

    private fun callbackToJs(callbackId: String, json: String) {
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        mainHandler.post {
            try {
                jsEvaluator?.invoke("window.__nfCb('$callbackId','$b64')")
            } catch (_: Exception) { }
        }
    }

    companion object {
        private val ioExecutor = java.util.concurrent.Executors.newCachedThreadPool()
        private val httpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        private fun isPrivateHost(host: String): Boolean = SecurityUtils.isPrivateHost(host)
    }
}
