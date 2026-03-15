package com.example.link_pi.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Manages user-trusted SSL certificates for module connections.
 *
 * When a module endpoint uses a self-signed or untrusted certificate,
 * the user can explicitly trust it via the Security Audit screen.
 * Trusted certs are persisted by SHA-256 fingerprint.
 */
class TrustedCertStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("trusted_certs", Context.MODE_PRIVATE)

    data class TrustedCert(
        val sha256: String,
        val host: String,
        val port: Int,
        val subjectDN: String,
        val issuerDN: String,
        val notBefore: Long,
        val notAfter: Long,
        val trustedAt: Long = System.currentTimeMillis()
    )

    /** Load all trusted certificate entries. */
    fun loadAll(): List<TrustedCert> {
        val json = prefs.getString("certs", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TrustedCert(
                    sha256 = o.getString("sha256"),
                    host = o.getString("host"),
                    port = o.getInt("port"),
                    subjectDN = o.optString("subjectDN", ""),
                    issuerDN = o.optString("issuerDN", ""),
                    notBefore = o.optLong("notBefore", 0),
                    notAfter = o.optLong("notAfter", 0),
                    trustedAt = o.optLong("trustedAt", 0)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Trust a certificate by its fingerprint. */
    fun trust(cert: TrustedCert) {
        val list = loadAll().toMutableList()
        list.removeAll { it.sha256 == cert.sha256 }
        list.add(cert)
        save(list)
    }

    /** Revoke trust for a certificate. */
    fun revoke(sha256: String) {
        val list = loadAll().toMutableList()
        list.removeAll { it.sha256 == sha256 }
        save(list)
    }

    /** Check if a certificate fingerprint is trusted. */
    fun isTrusted(sha256: String): Boolean = loadAll().any { it.sha256 == sha256 }

    private fun save(list: List<TrustedCert>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("sha256", c.sha256)
                put("host", c.host)
                put("port", c.port)
                put("subjectDN", c.subjectDN)
                put("issuerDN", c.issuerDN)
                put("notBefore", c.notBefore)
                put("notAfter", c.notAfter)
                put("trustedAt", c.trustedAt)
            })
        }
        prefs.edit().putString("certs", arr.toString()).apply()
    }

    companion object {
        /** Calculate SHA-256 fingerprint of an X509 certificate. */
        fun fingerprint(cert: X509Certificate): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(cert.encoded)
            return digest.joinToString(":") { "%02X".format(it) }
        }

        /**
         * Build a TrustManager that trusts system CAs + user-trusted certs.
         * Returns null if no custom trust is needed (no user-trusted certs).
         */
        fun buildTrustManager(store: TrustedCertStore): X509TrustManager? {
            val trusted = store.loadAll()
            if (trusted.isEmpty()) return null

            val trustedFingerprints = trusted.map { it.sha256 }.toSet()

            val defaultFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            defaultFactory.init(null as java.security.KeyStore?)
            val defaultTm = defaultFactory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()

            return object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTm.acceptedIssuers

                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                    defaultTm.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    try {
                        defaultTm.checkServerTrusted(chain, authType)
                    } catch (e: Exception) {
                        // Check if the leaf cert is user-trusted
                        val leaf = chain.firstOrNull()
                            ?: throw e
                        val fp = fingerprint(leaf)
                        if (fp !in trustedFingerprints) throw e
                        // User-trusted — allow
                    }
                }
            }
        }

        /**
         * Build an SSLContext using the custom trust manager.
         * Returns null if no custom trust is needed.
         */
        fun buildSSLContext(store: TrustedCertStore): SSLContext? {
            val tm = buildTrustManager(store) ?: return null
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            return ctx
        }

        /**
         * Build a HostnameVerifier that accepts user-trusted hosts.
         * Falls back to the default verifier for non-trusted hosts.
         */
        fun buildHostnameVerifier(store: TrustedCertStore): HostnameVerifier {
            val trustedHosts = store.loadAll().map { "${it.host}:${it.port}" }.toSet()
            val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            return HostnameVerifier { hostname, session ->
                // Try default verification first
                if (defaultVerifier.verify(hostname, session)) return@HostnameVerifier true
                // Check if this host:port is in user trust list
                val port = session.peerPort
                val hostPort = "$hostname:$port"
                hostPort in trustedHosts
            }
        }

        /**
         * Probe a host:port and return the server's certificate chain.
         * Runs synchronously — call from a background thread.
         */
        fun probeCertificates(host: String, port: Int, timeoutMs: Int = 10_000): List<X509Certificate> {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as java.security.KeyStore?)
            val defaultTm = factory.trustManagers.filterIsInstance<X509TrustManager>().first()

            val certs = mutableListOf<X509Certificate>()
            val captureTm = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTm.acceptedIssuers
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    certs.addAll(chain)
                    // Don't throw — we want to capture regardless of trust
                }
            }

            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(captureTm), null)
            val sf = ctx.socketFactory

            val socket = sf.createSocket() as javax.net.ssl.SSLSocket
            try {
                socket.soTimeout = timeoutMs
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                socket.startHandshake()
            } catch (_: Exception) {
                // Even if handshake fails (untrusted), we captured the certs
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
            return certs
        }
    }
}
