package com.example.link_pi.agent

import android.content.Context
import android.net.Uri
import com.example.link_pi.util.SecurityUtils
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Dynamic module system — AI-created reusable API service wrappers.
 *
 * A Module is a named collection of HTTP endpoints (like a mini SDK).
 * Modules are persistent, can be called by the AI agent or by mini-apps via NativeBridge.
 */
class ModuleStorage(context: Context) {

    private val moduleDir: File =
        File(context.filesDir, "modules").also { it.mkdirs() }

    data class Endpoint(
        val name: String,
        val path: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val bodyTemplate: String = "",
        val description: String = "",
        val port: Int = 0,
        val encoding: String = "utf8"  // "utf8" or "hex"
    )

    data class Module(
        val id: String,
        val name: String,
        val description: String,
        val baseUrl: String,
        val protocol: String = "HTTP",
        val defaultHeaders: Map<String, String> = emptyMap(),
        val endpoints: List<Endpoint> = emptyList(),
        val instructions: String = "",  // Usage instructions for AI to read
        val allowPrivateNetwork: Boolean = false,  // Allow LAN/private IP access (for DTU gateways etc.)
        val isBuiltIn: Boolean = false,  // Built-in modules cannot be edited/deleted/shared
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    )

    companion object BuiltIns {
        /** Built-in SSH module — visible in module list but not editable/deletable. */
        val SSH_MODULE = Module(
            id = "builtin_ssh",
            name = "SSH 远程服务器",
            description = "通过 SSH 连接远程服务器，执行命令、传输文件、端口转发。支持密码/密钥/凭据管理器认证",
            baseUrl = "ssh://",
            protocol = "SSH",
            isBuiltIn = true,
            endpoints = listOf(
                Endpoint(name = "ssh_connect", path = "", description = "连接SSH服务器", method = "CONNECT"),
                Endpoint(name = "ssh_exec", path = "", description = "执行远程命令", method = "EXEC"),
                Endpoint(name = "ssh_upload", path = "", description = "SFTP上传文件", method = "PUT"),
                Endpoint(name = "ssh_download", path = "", description = "SFTP下载文件", method = "GET"),
                Endpoint(name = "ssh_list_remote", path = "", description = "列出远程目录", method = "LIST"),
                Endpoint(name = "ssh_port_forward", path = "", description = "端口转发/SSH隧道", method = "FORWARD"),
                Endpoint(name = "ssh_disconnect", path = "", description = "断开连接", method = "CLOSE"),
                Endpoint(name = "ssh_list_sessions", path = "", description = "列出活跃会话", method = "LIST")
            ),
            instructions = "SSH 内置模块，通过 ssh_connect/ssh_exec/ssh_disconnect 等工具调用，不通过 call_module 调用。"
        )

        val builtInModules: List<Module> = listOf(SSH_MODULE)

        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val ioExecutor = Executors.newFixedThreadPool(8)
    }

    // ── CRUD ──

    @Synchronized
    fun save(module: Module): Module {
        val m = module.copy(updatedAt = System.currentTimeMillis())
        val json = moduleToJson(m)
        File(moduleDir, "${m.id}.json").writeText(json.toString(2))
        return m
    }

    fun create(
        name: String,
        description: String,
        baseUrl: String,
        protocol: String = "HTTP",
        defaultHeaders: Map<String, String> = emptyMap(),
        endpoints: List<Endpoint> = emptyList(),
        allowPrivateNetwork: Boolean = false,
        instructions: String = ""
    ): Module {
        val module = Module(
            id = UUID.randomUUID().toString().take(12),
            name = name.trim(),
            description = description.trim(),
            baseUrl = baseUrl.trimEnd('/'),
            protocol = protocol.uppercase(),
            defaultHeaders = defaultHeaders,
            endpoints = endpoints,
            instructions = instructions.trim(),
            allowPrivateNetwork = allowPrivateNetwork
        )
        return save(module)
    }

    fun loadById(id: String): Module? {
        val safeId = id.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        val file = File(moduleDir, "$safeId.json")
        return if (file.exists()) loadFromFile(file) else null
    }

    fun findByName(name: String): Module? {
        val all = loadAll()
        return all.find { it.name.equals(name, ignoreCase = true) }
            ?: all.find { it.name.contains(name, ignoreCase = true) }
    }

    fun loadAll(): List<Module> {
        val userModules = moduleDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { loadFromFile(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
        return builtInModules + userModules
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (builtInModules.any { it.id == id }) return false
        val safeId = id.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        return File(moduleDir, "$safeId.json").delete()
    }

    fun addEndpoint(moduleId: String, endpoint: Endpoint): Module? {
        val module = loadById(moduleId) ?: return null
        val updated = module.copy(
            endpoints = module.endpoints + endpoint
        )
        return save(updated)
    }

    fun removeEndpoint(moduleId: String, endpointName: String): Module? {
        val module = loadById(moduleId) ?: return null
        val updated = module.copy(
            endpoints = module.endpoints.filter { it.name != endpointName }
        )
        return save(updated)
    }

    fun updateModule(
        id: String,
        name: String? = null,
        description: String? = null,
        baseUrl: String? = null,
        defaultHeaders: Map<String, String>? = null,
        instructions: String? = null
    ): Module? {
        if (builtInModules.any { it.id == id }) return null
        val module = loadById(id) ?: return null
        val updated = module.copy(
            name = name?.trim() ?: module.name,
            description = description?.trim() ?: module.description,
            baseUrl = baseUrl?.trimEnd('/') ?: module.baseUrl,
            defaultHeaders = defaultHeaders ?: module.defaultHeaders,
            instructions = instructions?.trim() ?: module.instructions
        )
        return save(updated)
    }

    // ── Execute ──

    fun callEndpoint(
        module: Module,
        endpointName: String,
        params: Map<String, String> = emptyMap()
    ): String {
        val endpoint = module.endpoints.find { it.name.equals(endpointName, ignoreCase = true) }
            ?: return """{"error":"Endpoint '$endpointName' not found in module '${module.name}'. Available: ${module.endpoints.joinToString { it.name }}"}"""

        return when (module.protocol.uppercase()) {
            "TCP" -> executeTcp(module, endpoint, params)
            "UDP" -> executeUdp(module, endpoint, params)
            else -> executeHttp(module, endpoint, params)
        }
    }

    // ── Hex Utilities ──

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(Regex("[\\s,]"), "")
        require(clean.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray, length: Int = bytes.size): String {
        return bytes.take(length).joinToString(" ") { "%02X".format(it) }
    }

    private fun executeHttp(
        module: Module,
        endpoint: Endpoint,
        params: Map<String, String>
    ): String {
        try {
            // Build URL: baseUrl + path, replacing {{param}} placeholders in path
            var path = endpoint.path
            for ((k, v) in params) {
                path = path.replace("{{$k}}", Uri.encode(v))
            }
            val url = module.baseUrl + path

            // SSRF protection: block requests to private/loopback IPs (unless module allows it)
            val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
            if (!module.allowPrivateNetwork && isPrivateHost(host)) {
                return """{"error":"Requests to private/internal hosts are not allowed. Set allow_private_network=true to allow LAN access."}""" 
            }

            // Build query params: any remaining {{param}} after path substitution
            val urlBuilder = Uri.parse(url).buildUpon()
            // Add query params from endpoint path if any ?key={{val}} patterns
            // (already handled by path replacement above)

            val reqBuilder = Request.Builder().url(urlBuilder.build().toString())

            // Merge headers: module defaults + endpoint headers
            val allHeaders = module.defaultHeaders + endpoint.headers
            for ((k, v) in allHeaders) {
                // Replace {{param}} in header values too
                var hv = v
                for ((pk, pv) in params) {
                    hv = hv.replace("{{$pk}}", pv)
                }
                reqBuilder.addHeader(k, hv)
            }

            // Build body
            val body = when (endpoint.method.uppercase()) {
                "GET", "HEAD", "DELETE" -> null
                else -> {
                    var bodyStr = endpoint.bodyTemplate
                    for ((k, v) in params) {
                        bodyStr = bodyStr.replace("{{$k}}", v)
                    }
                    if (bodyStr.isBlank()) bodyStr = "{}"
                    val ct = allHeaders.entries.find {
                        it.key.equals("Content-Type", ignoreCase = true)
                    }?.value ?: "application/json"
                    bodyStr.toRequestBody(ct.toMediaTypeOrNull())
                }
            }

            reqBuilder.method(endpoint.method.uppercase(), body)

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val respBody = response.body?.string() ?: ""
            val truncated = if (respBody.length > 8000) respBody.take(8000) + "...(truncated)" else respBody

            return JSONObject().apply {
                put("status", response.code)
                put("ok", response.isSuccessful)
                put("body", truncated)
            }.toString()
        } catch (e: Exception) {
            return """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    // ── Serialization ──

    private fun moduleToJson(m: Module): JSONObject {
        return JSONObject().apply {
            put("id", m.id)
            put("name", m.name)
            put("description", m.description)
            put("baseUrl", m.baseUrl)
            put("protocol", m.protocol)
            put("defaultHeaders", JSONObject(m.defaultHeaders))
            put("endpoints", JSONArray().apply {
                m.endpoints.forEach { ep ->
                    put(JSONObject().apply {
                        put("name", ep.name)
                        put("path", ep.path)
                        put("method", ep.method)
                        put("headers", JSONObject(ep.headers))
                        put("bodyTemplate", ep.bodyTemplate)
                        put("description", ep.description)
                        put("port", ep.port)
                        put("encoding", ep.encoding)
                    })
                }
            })
            put("allowPrivateNetwork", m.allowPrivateNetwork)
            put("instructions", m.instructions)
            put("createdAt", m.createdAt)
            put("updatedAt", m.updatedAt)
        }
    }

    /** Export module as formatted JSON string. */
    fun exportToJson(module: Module): String = moduleToJson(module).toString(2)

    /** Import module from JSON string. Returns the saved module or null on error. */
    fun importFromJson(jsonStr: String): Module? {
        return try {
            val json = JSONObject(jsonStr)
            val headers = mutableMapOf<String, String>()
            json.optJSONObject("defaultHeaders")?.let { hj ->
                hj.keys().forEach { headers[it] = hj.getString(it) }
            }
            val endpoints = mutableListOf<Endpoint>()
            json.optJSONArray("endpoints")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val ep = arr.getJSONObject(i)
                    val epHeaders = mutableMapOf<String, String>()
                    ep.optJSONObject("headers")?.let { h ->
                        h.keys().forEach { k -> epHeaders[k] = h.getString(k) }
                    }
                    endpoints.add(Endpoint(
                        name = ep.getString("name"),
                        path = ep.optString("path", ""),
                        method = ep.optString("method", "GET"),
                        headers = epHeaders,
                        bodyTemplate = ep.optString("bodyTemplate", ep.optString("body_template", "")),
                        description = ep.optString("description", ""),
                        port = ep.optInt("port", 0),
                        encoding = ep.optString("encoding", "utf8")
                    ))
                }
            }
            val module = Module(
                id = UUID.randomUUID().toString().take(12),
                name = json.getString("name"),
                description = json.optString("description", ""),
                baseUrl = json.optString("baseUrl", ""),
                protocol = json.optString("protocol", "HTTP"),
                defaultHeaders = headers,
                endpoints = endpoints,
                instructions = json.optString("instructions", ""),
                allowPrivateNetwork = json.optBoolean("allowPrivateNetwork", false)
            )
            save(module)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadFromFile(file: File): Module? {
        return try {
            val json = JSONObject(file.readText())
            val headers = mutableMapOf<String, String>()
            val hdrJson = json.optJSONObject("defaultHeaders")
            if (hdrJson != null) {
                hdrJson.keys().forEach { headers[it] = hdrJson.getString(it) }
            }
            val endpoints = mutableListOf<Endpoint>()
            val epArr = json.optJSONArray("endpoints")
            if (epArr != null) {
                for (i in 0 until epArr.length()) {
                    val epJson = epArr.getJSONObject(i)
                    val epHeaders = mutableMapOf<String, String>()
                    val epHdrJson = epJson.optJSONObject("headers")
                    if (epHdrJson != null) {
                        epHdrJson.keys().forEach { epHeaders[it] = epHdrJson.getString(it) }
                    }
                    endpoints.add(Endpoint(
                        name = epJson.getString("name"),
                        path = epJson.optString("path", ""),
                        method = epJson.optString("method", "GET"),
                        headers = epHeaders,
                        bodyTemplate = epJson.optString("bodyTemplate", epJson.optString("body_template", "")),
                        description = epJson.optString("description", ""),
                        port = epJson.optInt("port", 0),
                        encoding = epJson.optString("encoding", "utf8")
                    ))
                }
            }
            Module(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.optString("description", ""),
                baseUrl = json.optString("baseUrl", ""),
                protocol = json.optString("protocol", "HTTP"),
                defaultHeaders = headers,
                endpoints = endpoints,
                instructions = json.optString("instructions", ""),
                allowPrivateNetwork = json.optBoolean("allowPrivateNetwork", false),
                createdAt = json.optLong("createdAt", 0),
                updatedAt = json.optLong("updatedAt", json.optLong("createdAt", 0))
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isPrivateHost(host: String): Boolean = SecurityUtils.isPrivateHost(host)

    // ── TCP ──

    private fun executeTcp(
        module: Module,
        endpoint: Endpoint,
        params: Map<String, String>
    ): String {
        try {
            val host = try { java.net.URI(module.baseUrl).host ?: module.baseUrl } catch (_: Exception) { module.baseUrl }
            if (!module.allowPrivateNetwork && isPrivateHost(host)) {
                return """{"error":"Requests to private/internal hosts are not allowed. Set allow_private_network=true to allow LAN access."}""" 
            }
            val port = if (endpoint.port > 0) endpoint.port
                       else try { java.net.URI(module.baseUrl).port.takeIf { it > 0 } } catch (_: Exception) { null }
                       ?: return """{"error":"No port specified for TCP endpoint '${endpoint.name}'"}"""
            if (port !in 1..65535) return """{"error":"Invalid port: $port"}"""

            val isHex = endpoint.encoding.equals("hex", ignoreCase = true)

            var payload = endpoint.bodyTemplate
            for ((k, v) in params) {
                payload = payload.replace("{{$k}}", v)
            }

            val sendBytes = if (isHex) hexToBytes(payload) else payload.toByteArray(Charsets.UTF_8)

            val socket = Socket()
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(host, port), 10_000)
            socket.use { s ->
                if (sendBytes.isNotEmpty()) {
                    s.getOutputStream().write(sendBytes)
                    s.getOutputStream().flush()
                }
                val buffer = ByteArray(8192)
                val read = try { s.getInputStream().read(buffer) } catch (_: java.net.SocketTimeoutException) { 0 }
                val response = if (read > 0) {
                    if (isHex) bytesToHex(buffer, read) else String(buffer, 0, read, Charsets.UTF_8)
                } else ""
                val truncated = if (response.length > 8000) response.take(8000) + "...(truncated)" else response
                return JSONObject().apply {
                    put("ok", true)
                    put("encoding", if (isHex) "hex" else "utf8")
                    put("bytes_sent", sendBytes.size)
                    put("bytes_received", maxOf(read, 0))
                    put("body", truncated)
                }.toString()
            }
        } catch (e: Exception) {
            return """{"error":"${e.message?.replace("\"", "'")}"}""" 
        }
    }

    // ── UDP ──

    private fun executeUdp(
        module: Module,
        endpoint: Endpoint,
        params: Map<String, String>
    ): String {
        try {
            val host = try { java.net.URI(module.baseUrl).host ?: module.baseUrl } catch (_: Exception) { module.baseUrl }
            if (!module.allowPrivateNetwork && isPrivateHost(host)) {
                return """{"error":"Requests to private/internal hosts are not allowed. Set allow_private_network=true to allow LAN access."}""" 
            }
            val port = if (endpoint.port > 0) endpoint.port
                       else try { java.net.URI(module.baseUrl).port.takeIf { it > 0 } } catch (_: Exception) { null }
                       ?: return """{"error":"No port specified for UDP endpoint '${endpoint.name}'"}"""
            if (port !in 1..65535) return """{"error":"Invalid port: $port"}"""

            val isHex = endpoint.encoding.equals("hex", ignoreCase = true)

            var payload = endpoint.bodyTemplate
            for ((k, v) in params) {
                payload = payload.replace("{{$k}}", v)
            }

            val sendBytes = if (isHex) hexToBytes(payload) else payload.toByteArray(Charsets.UTF_8)

            val addr = java.net.InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                socket.soTimeout = 5_000
                if (sendBytes.isNotEmpty()) {
                    socket.send(DatagramPacket(sendBytes, sendBytes.size, addr, port))
                }
                val recvBuf = ByteArray(4096)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                val (response, recvLen) = try {
                    socket.receive(recvPacket)
                    val len = recvPacket.length
                    val body = if (isHex) bytesToHex(recvPacket.data, len)
                               else String(recvPacket.data, 0, len, Charsets.UTF_8)
                    body to len
                } catch (_: java.net.SocketTimeoutException) { "" to 0 }
                return JSONObject().apply {
                    put("ok", true)
                    put("encoding", if (isHex) "hex" else "utf8")
                    put("bytes_sent", sendBytes.size)
                    put("bytes_received", recvLen)
                    put("body", response)
                }.toString()
            }
        } catch (e: Exception) {
            return """{"error":"${e.message?.replace("\"", "'")}"}""" 
        }
    }
}
