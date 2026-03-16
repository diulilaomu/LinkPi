package com.example.link_pi.ui.miniapp

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.File

/**
 * Proxies external (CDN) requests through OkHttp with disk caching.
 * WebView sometimes fails to load CDN scripts (DNS/TLS issues on emulators).
 * This fetches via OkHttp and caches to `cdn_cache/` for offline reuse.
 */
internal class CdnProxy(context: android.content.Context) {

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
