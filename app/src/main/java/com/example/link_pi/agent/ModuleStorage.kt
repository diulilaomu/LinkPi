package com.example.link_pi.agent

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        val description: String = ""
    )

    data class Module(
        val id: String,
        val name: String,
        val description: String,
        val baseUrl: String,
        val defaultHeaders: Map<String, String> = emptyMap(),
        val endpoints: List<Endpoint> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    )

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
        defaultHeaders: Map<String, String> = emptyMap(),
        endpoints: List<Endpoint> = emptyList()
    ): Module {
        val module = Module(
            id = UUID.randomUUID().toString().take(12),
            name = name.trim(),
            description = description.trim(),
            baseUrl = baseUrl.trimEnd('/'),
            defaultHeaders = defaultHeaders,
            endpoints = endpoints
        )
        return save(module)
    }

    fun loadById(id: String): Module? {
        val safeId = id.replace(Regex("[^a-zA-Z0-9\\-]"), "")
        val file = File(moduleDir, "$safeId.json")
        return if (file.exists()) loadFromFile(file) else null
    }

    fun findByName(name: String): Module? {
        return loadAll().find { it.name.equals(name, ignoreCase = true) }
            ?: loadAll().find { it.name.contains(name, ignoreCase = true) }
    }

    fun loadAll(): List<Module> {
        return moduleDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { loadFromFile(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    @Synchronized
    fun delete(id: String): Boolean {
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
        defaultHeaders: Map<String, String>? = null
    ): Module? {
        val module = loadById(id) ?: return null
        val updated = module.copy(
            name = name?.trim() ?: module.name,
            description = description?.trim() ?: module.description,
            baseUrl = baseUrl?.trimEnd('/') ?: module.baseUrl,
            defaultHeaders = defaultHeaders ?: module.defaultHeaders
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

        return executeHttp(module, endpoint, params)
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
                    })
                }
            })
            put("createdAt", m.createdAt)
            put("updatedAt", m.updatedAt)
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
                        description = epJson.optString("description", "")
                    ))
                }
            }
            Module(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.optString("description", ""),
                baseUrl = json.optString("baseUrl", ""),
                defaultHeaders = headers,
                endpoints = endpoints,
                createdAt = json.optLong("createdAt", 0),
                updatedAt = json.optLong("updatedAt", json.optLong("createdAt", 0))
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val ioExecutor = Executors.newCachedThreadPool()
    }
}
