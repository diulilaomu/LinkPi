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
import com.example.link_pi.agent.ModuleStorage
import com.example.link_pi.util.SecurityUtils
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class NativeBridge(
    private val context: Context,
    private val appId: String = "default",
    private val onSendToApp: (String) -> Unit = {},
    private val jsEvaluator: ((String) -> Unit)? = null,
    private val moduleStorage: ModuleStorage? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    var webSocketBridge: WebSocketBridge? = null

    private fun getPrefs() =
        context.getSharedPreferences("miniapp_data_$appId", Context.MODE_PRIVATE)

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun vibrate(milliseconds: Long) {
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
        getPrefs().edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun loadData(key: String): String {
        return getPrefs().getString(key, "") ?: ""
    }

    @JavascriptInterface
    fun removeData(key: String) {
        getPrefs().edit().remove(key).apply()
    }

    @JavascriptInterface
    fun clearData() {
        getPrefs().edit().clear().apply()
    }

    @JavascriptInterface
    fun listKeys(): String {
        return getPrefs().all.keys.joinToString(",")
    }

    @JavascriptInterface
    fun getAppId(): String {
        return appId
    }

    @JavascriptInterface
    fun writeClipboard(text: String) {
        mainHandler.post {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
        }
    }

    @JavascriptInterface
    fun getBatteryLevel(): Int {
        val batteryManager =
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    @JavascriptInterface
    fun getLocation(): String {
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
                } else ""
            } else ""
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun sendToApp(data: String) {
        onSendToApp(data)
    }

    @JavascriptInterface
    fun listModules(): String {
        val modules = moduleStorage?.loadAll() ?: return "[]"
        val arr = org.json.JSONArray()
        for (m in modules) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("description", m.description)
                put("protocol", m.protocol)
                put("endpoints", org.json.JSONArray().apply {
                    m.endpoints.forEach { ep ->
                        put(JSONObject().apply {
                            put("name", ep.name)
                            put("method", ep.method)
                            put("path", ep.path)
                            put("description", ep.description)
                        })
                    }
                })
            })
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun callModule(callbackId: String, moduleName: String, endpointName: String, paramsJson: String) {
        if (!callbackId.matches(Regex("^[a-zA-Z0-9_]+$"))) return
        ModuleStorage.ioExecutor.execute {
            try {
                val ms = moduleStorage
                if (ms == null) {
                    callbackToJs(callbackId, """{"error":"Module system not available"}""")
                    return@execute
                }
                val module = ms.loadById(moduleName) ?: ms.findByName(moduleName)
                if (module == null) {
                    callbackToJs(callbackId, """{"error":"Module '$moduleName' not found"}""")
                    return@execute
                }
                val params = mutableMapOf<String, String>()
                try {
                    val pj = JSONObject(paramsJson)
                    pj.keys().forEach { k -> params[k] = pj.optString(k, "") }
                } catch (_: Exception) {}

                val result = ms.callEndpoint(module, endpointName, params)
                callbackToJs(callbackId, result)
            } catch (e: Exception) {
                callbackToJs(callbackId, """{"error":"${e.message?.replace("\"", "'")}"}""")
            }
        }
    }

    @JavascriptInterface
    fun httpRequest(callbackId: String, url: String, method: String, headers: String, body: String) {
        if (!callbackId.matches(Regex("^[a-zA-Z0-9_]+$"))) return
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
        ModuleStorage.ioExecutor.execute {
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
                response.headers.forEach { (name, value) -> respHeaders.put(name, value) }
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
        ModuleStorage.ioExecutor.execute {
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
        webSocketBridge?.stop()
        webSocketBridge = null
    }

    @JavascriptInterface
    fun serverSend(clientId: String, message: String) {
        webSocketBridge?.send(clientId, message)
    }

    @JavascriptInterface
    fun serverBroadcast(message: String) {
        webSocketBridge?.broadcast(message)
    }

    @JavascriptInterface
    fun getLocalIpAddress(): String {
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
        private val httpClient get() = ModuleStorage.httpClient

        private fun isPrivateHost(host: String): Boolean = SecurityUtils.isPrivateHost(host)
    }
}
