package com.example.link_pi.bridge

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages a lightweight WebSocket server for LAN real-time communication.
 * Mini-apps can host a game/session on one device and other devices connect via
 * standard WebSocket client (`new WebSocket('ws://ip:port')`).
 */
class WebSocketBridge(
    private val jsEvaluator: ((String) -> Unit)?
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: WsServer? = null
    private val clients = ConcurrentHashMap<String, WebSocket>()
    private var clientIdCounter = 0

    val isRunning: Boolean get() = server != null

    /**
     * Start a WebSocket server on the given port.
     * Events are delivered to JS via the persistent callback.
     */
    fun start(port: Int): Result<Int> {
        if (server != null) {
            stop()
        }
        return try {
            val validPort = port.coerceIn(1024, 65535)
            val ws = WsServer(validPort)
            ws.isReuseAddr = true
            ws.connectionLostTimeout = 30
            ws.start()
            server = ws
            Result.success(validPort)
        } catch (e: Exception) {
            Log.e("WebSocketBridge", "Failed to start server", e)
            Result.failure(e)
        }
    }

    fun stop() {
        try {
            server?.stop(500)
        } catch (_: Exception) {}
        server = null
        clients.clear()
        clientIdCounter = 0
    }

    fun send(clientId: String, message: String): Boolean {
        val conn = clients[clientId] ?: return false
        return try {
            if (conn.isOpen) { conn.send(message); true } else false
        } catch (_: Exception) { false }
    }

    fun broadcast(message: String) {
        clients.values.forEach { conn ->
            try { if (conn.isOpen) conn.send(message) } catch (_: Exception) {}
        }
    }

    fun getClientCount(): Int = clients.size

    private fun emitEvent(json: String) {
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        mainHandler.post {
            try {
                jsEvaluator?.invoke("window.__wsServerEvent&&window.__wsServerEvent('$b64')")
            } catch (_: Exception) {}
        }
    }

    private inner class WsServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
            val id = "c${++clientIdCounter}"
            clients[id] = conn
            conn.setAttachment(id)
            val addr = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
            emitEvent(JSONObject().apply {
                put("type", "connection")
                put("clientId", id)
                put("address", addr)
            }.toString())
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            val id = conn.getAttachment<String>() ?: return
            clients.remove(id)
            emitEvent(JSONObject().apply {
                put("type", "close")
                put("clientId", id)
                put("code", code)
            }.toString())
        }

        override fun onMessage(conn: WebSocket, message: String?) {
            val id = conn.getAttachment<String>() ?: return
            emitEvent(JSONObject().apply {
                put("type", "message")
                put("clientId", id)
                put("data", message ?: "")
            }.toString())
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            val id = conn?.getAttachment<String>()
            emitEvent(JSONObject().apply {
                put("type", "error")
                put("clientId", id ?: "")
                put("message", ex?.message ?: "Unknown error")
            }.toString())
        }

        override fun onStart() {
            Log.d("WebSocketBridge", "Server started on port $port")
        }
    }

    companion object {
        /**
         * Returns the device's local WiFi/LAN IPv4 address.
         * Returns empty string if unavailable.
         */
        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
                for (intf in interfaces) {
                    // Skip loopback and down interfaces
                    if (intf.isLoopback || !intf.isUp) continue
                    // Prefer wlan/wifi interfaces
                    val name = intf.name.lowercase()
                    if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("ap")) continue
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
                // Fallback: any non-loopback IPv4
                val allInterfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
                for (intf in allInterfaces) {
                    if (intf.isLoopback || !intf.isUp) continue
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            } catch (_: Exception) {}
            return ""
        }
    }
}
