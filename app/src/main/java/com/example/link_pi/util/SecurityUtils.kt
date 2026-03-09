package com.example.link_pi.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Shared SSRF protection — blocks requests to private/loopback/link-local addresses. */
object SecurityUtils {

    /**
     * Check if a hostname or IP string points to a private/reserved network.
     * Performs DNS resolution to defend against DNS rebinding.
     */
    fun isPrivateHost(host: String): Boolean {
        if (host.isBlank()) return true

        // Quick string-level checks
        val h = host.lowercase().trim().removeSuffix(".")
        if (h == "localhost" || h == "0.0.0.0" || h == "::1" || h == "[::]") return true
        if (h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".localhost")) return true

        // Resolve DNS and check all returned addresses
        return try {
            val addresses = InetAddress.getAllByName(h)
            addresses.any { isPrivateAddress(it) }
        } catch (_: Exception) {
            // Unresolvable hosts are treated as blocked
            true
        }
    }

    /** Check resolved InetAddress against all private/reserved ranges. */
    private fun isPrivateAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true      // 127.0.0.0/8, ::1
        if (addr.isLinkLocalAddress) return true      // 169.254.0.0/16, fe80::/10
        if (addr.isSiteLocalAddress) return true      // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fec0::/10
        if (addr.isAnyLocalAddress) return true       // 0.0.0.0, ::

        when (addr) {
            is Inet4Address -> {
                val bytes = addr.address
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                // CGNAT: 100.64.0.0/10
                if (b0 == 100 && b1 in 64..127) return true
                // Benchmarking: 198.18.0.0/15
                if (b0 == 198 && b1 in 18..19) return true
                // Documentation: 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24
                if (b0 == 192 && b1 == 0 && (bytes[2].toInt() and 0xFF) == 2) return true
                if (b0 == 198 && b1 == 51 && (bytes[2].toInt() and 0xFF) == 100) return true
                if (b0 == 203 && b1 == 0 && (bytes[2].toInt() and 0xFF) == 113) return true
            }
            is Inet6Address -> {
                val bytes = addr.address
                val b0 = bytes[0].toInt() and 0xFF
                // ULA: fc00::/7
                if (b0 and 0xFE == 0xFC) return true
                // IPv4-mapped ::ffff:x.x.x.x — check embedded IPv4
                if (bytes.take(10).all { it.toInt() == 0 } &&
                    (bytes[10].toInt() and 0xFF) == 0xFF &&
                    (bytes[11].toInt() and 0xFF) == 0xFF) {
                    val mapped = InetAddress.getByAddress(bytes.sliceArray(12..15))
                    return isPrivateAddress(mapped)
                }
            }
        }
        return false
    }
}
