package com.example.link_pi.util

import org.junit.Assert.*
import org.junit.Test

class SecurityUtilsTest {

    // ── Obvious private/loopback addresses ──

    @Test
    fun `localhost is private`() {
        assertTrue(SecurityUtils.isPrivateHost("localhost"))
    }

    @Test
    fun `0_0_0_0 is private`() {
        assertTrue(SecurityUtils.isPrivateHost("0.0.0.0"))
    }

    @Test
    fun `IPv6 loopback is private`() {
        assertTrue(SecurityUtils.isPrivateHost("::1"))
    }

    @Test
    fun `blank host is private`() {
        assertTrue(SecurityUtils.isPrivateHost(""))
        assertTrue(SecurityUtils.isPrivateHost("  "))
    }

    // ── Special TLD suffixes ──

    @Test
    fun `dot local suffix is private`() {
        assertTrue(SecurityUtils.isPrivateHost("myhost.local"))
    }

    @Test
    fun `dot internal suffix is private`() {
        assertTrue(SecurityUtils.isPrivateHost("service.internal"))
    }

    @Test
    fun `dot localhost suffix is private`() {
        assertTrue(SecurityUtils.isPrivateHost("app.localhost"))
    }

    // ── RFC 1918 private ranges (resolved via DNS) ──

    @Test
    fun `127_0_0_1 is private`() {
        assertTrue(SecurityUtils.isPrivateHost("127.0.0.1"))
    }

    @Test
    fun `10_x range is private`() {
        assertTrue(SecurityUtils.isPrivateHost("10.0.0.1"))
    }

    @Test
    fun `172_16_x range is private`() {
        assertTrue(SecurityUtils.isPrivateHost("172.16.0.1"))
    }

    @Test
    fun `192_168_x range is private`() {
        assertTrue(SecurityUtils.isPrivateHost("192.168.1.1"))
    }

    // ── CGNAT range 100.64-127.x.x ──

    @Test
    fun `CGNAT address 100_64_0_1 is private`() {
        assertTrue(SecurityUtils.isPrivateHost("100.64.0.1"))
    }

    @Test
    fun `CGNAT address 100_127_255_255 is private`() {
        assertTrue(SecurityUtils.isPrivateHost("100.127.255.255"))
    }

    // ── Documentation ranges ──

    @Test
    fun `doc range 192_0_2_x is private`() {
        assertTrue(SecurityUtils.isPrivateHost("192.0.2.1"))
    }

    @Test
    fun `doc range 198_51_100_x is private`() {
        assertTrue(SecurityUtils.isPrivateHost("198.51.100.1"))
    }

    @Test
    fun `doc range 203_0_113_x is private`() {
        assertTrue(SecurityUtils.isPrivateHost("203.0.113.1"))
    }

    // ── Benchmarking range ──

    @Test
    fun `benchmark range 198_18_x is private`() {
        assertTrue(SecurityUtils.isPrivateHost("198.18.0.1"))
    }

    @Test
    fun `benchmark range 198_19_x is private`() {
        assertTrue(SecurityUtils.isPrivateHost("198.19.255.255"))
    }

    // ── Public addresses should not be private ──

    @Test
    fun `public IP 8_8_8_8 is not private`() {
        assertFalse(SecurityUtils.isPrivateHost("8.8.8.8"))
    }

    @Test
    fun `public IP 1_1_1_1 is not private`() {
        assertFalse(SecurityUtils.isPrivateHost("1.1.1.1"))
    }

    // ── Case insensitivity ──

    @Test
    fun `LOCALHOST uppercase is private`() {
        assertTrue(SecurityUtils.isPrivateHost("LOCALHOST"))
    }

    @Test
    fun `trailing dot is handled`() {
        assertTrue(SecurityUtils.isPrivateHost("localhost."))
    }

    // ── DNS resolution failure defaults to private (safe) ──

    @Test
    fun `unresolvable hostname is treated as private`() {
        assertTrue(SecurityUtils.isPrivateHost("this-host-definitely-does-not-exist-xyz123.invalid"))
    }

    // ── IPv6 special ──

    @Test
    fun `IPv6 all-zeros is private`() {
        assertTrue(SecurityUtils.isPrivateHost("[::]"))
    }
}
