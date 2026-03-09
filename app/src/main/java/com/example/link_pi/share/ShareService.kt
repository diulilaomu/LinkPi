package com.example.link_pi.share

import android.content.Context
import com.example.link_pi.agent.ModuleStorage
import com.example.link_pi.data.model.MiniApp
import com.example.link_pi.data.model.Skill
import com.example.link_pi.data.model.SkillMode
import com.example.link_pi.miniapp.MiniAppStorage
import com.example.link_pi.skill.BridgeGroup
import com.example.link_pi.skill.CdnGroup
import com.example.link_pi.skill.SkillStorage
import com.example.link_pi.skill.ToolGroup
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.util.UUID

// ── State models ──

sealed class ConnectionState {
    data object Scanning : ConnectionState()
    data class Connecting(val ip: String) : ConnectionState()
    data class IncomingRequest(val ip: String) : ConnectionState()
    data class Connected(val peerIp: String) : ConnectionState()
    data class Finished(
        val sentNames: List<String>,
        val receivedItems: List<ReceivedItem>
    ) : ConnectionState()
}

data class ReceivedItem(
    val category: String, // "skill" | "module" | "app"
    val name: String,
    val success: Boolean
)

class ShareService(private val context: Context) {

    companion object {
        private const val DISCOVERY_PORT = 19876
        private const val TRANSFER_PORT = 19877
        private const val BROADCAST_INTERVAL = 1500L
        private const val BROADCAST_PREFIX = "LINKPI_SHARE:"
        private const val MAX_MESSAGE_SIZE = 5_000_000 // 5MB
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _discoveredDevices = MutableStateFlow<List<String>>(emptyList())
    val discoveredDevices: StateFlow<List<String>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Scanning)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 4-digit PIN shown to user; peer must match to connect. */
    val pin: String = "%04d".format((1000..9999).random())

    private val _receivedItems = MutableStateFlow<List<ReceivedItem>>(emptyList())
    val receivedItems: StateFlow<List<ReceivedItem>> = _receivedItems.asStateFlow()

    private val _sentItems = MutableStateFlow<List<String>>(emptyList())
    val sentItems: StateFlow<List<String>> = _sentItems.asStateFlow()

    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var serverJob: Job? = null
    private var receiveJob: Job? = null

    private var activeSocket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    val localIp: String by lazy { getLocalIpAddress() }

    // ══════════════════════════════════════
    //  Discovery
    // ══════════════════════════════════════

    fun startDiscovery() {
        _connectionState.value = ConnectionState.Scanning
        _discoveredDevices.value = emptyList()
        _receivedItems.value = emptyList()
        _sentItems.value = emptyList()
        startServer()
        startBroadcasting()
        startListening()
    }

    private fun startBroadcasting() {
        broadcastJob = scope.launch {
            try {
                val socket = DatagramSocket().apply { broadcast = true }
                val message = "$BROADCAST_PREFIX$localIp".toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                while (isActive) {
                    try {
                        socket.send(DatagramPacket(message, message.size, broadcastAddr, DISCOVERY_PORT))
                    } catch (_: Exception) {}
                    delay(BROADCAST_INTERVAL)
                }
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun startListening() {
        listenJob = scope.launch {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                udpSocket = socket
                val buffer = ByteArray(256)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (msg.startsWith(BROADCAST_PREFIX)) {
                            val ip = msg.removePrefix(BROADCAST_PREFIX).trim()
                            if (ip != localIp && ip !in _discoveredDevices.value) {
                                _discoveredDevices.value = _discoveredDevices.value + ip
                            }
                        }
                    } catch (_: SocketException) {
                        break
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun startServer() {
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(TRANSFER_PORT).apply { reuseAddress = true }
                while (isActive) {
                    val client = try { serverSocket?.accept() } catch (_: Exception) { null } ?: break
                    handleIncomingConnection(client)
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        val peerIp = socket.inetAddress.hostAddress ?: run { socket.close(); return }
        try {
            val input = DataInputStream(socket.getInputStream())
            val msg = readMessage(input)
            val json = JSONObject(msg)
            if (json.optString("type") == "CONNECT_REQUEST") {
                val incomingPin = json.optString("pin", "")
                if (incomingPin != pin) {
                    // PIN mismatch — reject silently
                    val output = DataOutputStream(socket.getOutputStream())
                    writeMessage(output, JSONObject().apply {
                        put("type", "CONNECT_REJECT")
                        put("reason", "PIN mismatch")
                    }.toString())
                    socket.close()
                    return
                }
                activeSocket = socket
                _connectionState.value = ConnectionState.IncomingRequest(peerIp)
            } else {
                socket.close()
            }
        } catch (_: Exception) {
            socket.close()
        }
    }

    // ══════════════════════════════════════
    //  Connection
    // ══════════════════════════════════════

    fun connectTo(ip: String, peerPin: String) {
        _connectionState.value = ConnectionState.Connecting(ip)
        scope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, TRANSFER_PORT), 5000)
                activeSocket = socket
                val output = DataOutputStream(socket.getOutputStream())
                writeMessage(output, JSONObject().apply {
                    put("type", "CONNECT_REQUEST")
                    put("ip", localIp)
                    put("pin", peerPin)
                }.toString())

                // Wait for response
                val input = DataInputStream(socket.getInputStream())
                val response = readMessage(input)
                val json = JSONObject(response)
                when (json.optString("type")) {
                    "CONNECT_ACCEPT" -> {
                        stopDiscovery()
                        _connectionState.value = ConnectionState.Connected(ip)
                        startReceiving()
                    }
                    else -> {
                        socket.close()
                        activeSocket = null
                        _connectionState.value = ConnectionState.Scanning
                    }
                }
            } catch (_: Exception) {
                activeSocket?.close()
                activeSocket = null
                _connectionState.value = ConnectionState.Scanning
            }
        }
    }

    fun acceptConnection() {
        val state = _connectionState.value
        if (state !is ConnectionState.IncomingRequest) return
        val peerIp = state.ip
        scope.launch {
            try {
                val socket = activeSocket ?: return@launch
                val output = DataOutputStream(socket.getOutputStream())
                writeMessage(output, JSONObject().apply {
                    put("type", "CONNECT_ACCEPT")
                }.toString())
                stopDiscovery()
                _connectionState.value = ConnectionState.Connected(peerIp)
                startReceiving()
            } catch (_: Exception) {
                _connectionState.value = ConnectionState.Scanning
            }
        }
    }

    fun rejectConnection() {
        scope.launch {
            try {
                val socket = activeSocket ?: return@launch
                val output = DataOutputStream(socket.getOutputStream())
                writeMessage(output, JSONObject().apply {
                    put("type", "CONNECT_REJECT")
                }.toString())
                socket.close()
            } catch (_: Exception) {}
            activeSocket = null
            _connectionState.value = ConnectionState.Scanning
        }
    }

    // ══════════════════════════════════════
    //  Transfer
    // ══════════════════════════════════════

    fun shareItems(items: List<Pair<String, String>>) {
        scope.launch {
            val socket = activeSocket ?: return@launch
            try {
                val output = DataOutputStream(socket.getOutputStream())
                for ((category, data) in items) {
                    val json = JSONObject(data)
                    val name = json.optString("name", "未知")
                    writeMessage(output, JSONObject().apply {
                        put("type", "SHARE_ITEM")
                        put("category", category)
                        put("data", json)
                    }.toString())
                    _sentItems.value = _sentItems.value + name
                }
            } catch (_: Exception) {}
        }
    }

    private fun startReceiving() {
        receiveJob = scope.launch {
            val socket = activeSocket ?: return@launch
            try {
                val input = DataInputStream(socket.getInputStream())
                while (isActive && !socket.isClosed) {
                    val msg = try { readMessage(input) } catch (_: Exception) { break }
                    val json = JSONObject(msg)
                    when (json.optString("type")) {
                        "SHARE_ITEM" -> {
                            val category = json.getString("category")
                            val data = json.getJSONObject("data")
                            val result = importItem(category, data)
                            _receivedItems.value = _receivedItems.value + result
                        }
                        "DISCONNECT" -> {
                            _connectionState.value = ConnectionState.Finished(
                                _sentItems.value, _receivedItems.value
                            )
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                if (_connectionState.value is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Finished(
                        _sentItems.value, _receivedItems.value
                    )
                }
            }
        }
    }

    fun finishSharing() {
        scope.launch {
            try {
                val socket = activeSocket ?: return@launch
                val output = DataOutputStream(socket.getOutputStream())
                writeMessage(output, JSONObject().apply {
                    put("type", "DISCONNECT")
                }.toString())
            } catch (_: Exception) {}
            _connectionState.value = ConnectionState.Finished(
                _sentItems.value, _receivedItems.value
            )
            try { activeSocket?.close() } catch (_: Exception) {}
            activeSocket = null
        }
    }

    // ══════════════════════════════════════
    //  Import helpers
    // ══════════════════════════════════════

    private fun importItem(category: String, data: JSONObject): ReceivedItem {
        val name = data.optString("name", "未知")
        return try {
            when (category) {
                "skill" -> {
                    importSkill(data)
                    ReceivedItem(category, name, true)
                }
                "module" -> {
                    ModuleStorage(context).importFromJson(data.toString())
                        ?: throw Exception("import failed")
                    ReceivedItem(category, name, true)
                }
                "app" -> {
                    importApp(data)
                    ReceivedItem(category, name, true)
                }
                else -> ReceivedItem(category, name, false)
            }
        } catch (_: Exception) {
            ReceivedItem(category, name, false)
        }
    }

    private fun importSkill(json: JSONObject) {
        val skill = Skill(
            id = UUID.randomUUID().toString().take(12),
            name = json.getString("name"),
            icon = json.optString("icon", "🔧"),
            description = json.optString("description", ""),
            systemPrompt = json.getString("systemPrompt"),
            mode = SkillMode.fromString(json.optString("mode", "CODING")),
            isBuiltIn = false,
            createdAt = System.currentTimeMillis(),
            bridgeGroups = json.optJSONArray("bridgeGroups")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    try { BridgeGroup.valueOf(arr.getString(i)) } catch (_: Exception) { null }
                }.toSet()
            } ?: setOf(BridgeGroup.STORAGE, BridgeGroup.UI_FEEDBACK),
            cdnGroups = json.optJSONArray("cdnGroups")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    try { CdnGroup.valueOf(arr.getString(i)) } catch (_: Exception) { null }
                }.toSet()
            } ?: emptySet(),
            extraToolGroups = json.optJSONArray("extraToolGroups")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    try { ToolGroup.valueOf(arr.getString(i)) } catch (_: Exception) { null }
                }.toSet()
            } ?: emptySet()
        )
        SkillStorage(context).save(skill)
    }

    private fun importApp(json: JSONObject) {
        val app = MiniApp(
            id = UUID.randomUUID().toString().take(12),
            name = json.getString("name"),
            description = json.optString("description", ""),
            htmlContent = json.optString("htmlContent", ""),
            createdAt = System.currentTimeMillis(),
            isWorkspaceApp = json.optBoolean("isWorkspaceApp", false),
            entryFile = json.optString("entryFile", "index.html")
        )
        MiniAppStorage(context).save(app)
    }

    // ══════════════════════════════════════
    //  Export helpers
    // ══════════════════════════════════════

    fun skillToJson(skill: Skill): String = JSONObject().apply {
        put("name", skill.name)
        put("icon", skill.icon)
        put("description", skill.description)
        put("systemPrompt", skill.systemPrompt)
        put("mode", skill.mode.name)
        put("isBuiltIn", skill.isBuiltIn)
        put("bridgeGroups", JSONArray(skill.bridgeGroups.map { it.name }))
        put("cdnGroups", JSONArray(skill.cdnGroups.map { it.name }))
        put("extraToolGroups", JSONArray(skill.extraToolGroups.map { it.name }))
    }.toString()

    fun appToJson(app: MiniApp): String = JSONObject().apply {
        put("name", app.name)
        put("description", app.description)
        put("htmlContent", app.htmlContent)
        put("isWorkspaceApp", app.isWorkspaceApp)
        put("entryFile", app.entryFile)
    }.toString()

    // ══════════════════════════════════════
    //  Protocol: length-prefixed JSON
    // ══════════════════════════════════════

    private fun writeMessage(output: DataOutputStream, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readMessage(input: DataInputStream): String {
        val length = input.readInt()
        if (length < 0 || length > MAX_MESSAGE_SIZE) {
            throw IllegalStateException("Invalid message size: $length")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    // ══════════════════════════════════════
    //  Cleanup
    // ══════════════════════════════════════

    private fun stopDiscovery() {
        broadcastJob?.cancel()
        listenJob?.cancel()
        serverJob?.cancel()
        try { udpSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    fun shutdown() {
        stopDiscovery()
        receiveJob?.cancel()
        try { activeSocket?.close() } catch (_: Exception) {}
        scope.cancel()
    }

    // ══════════════════════════════════════
    //  Utility
    // ══════════════════════════════════════

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }
}
