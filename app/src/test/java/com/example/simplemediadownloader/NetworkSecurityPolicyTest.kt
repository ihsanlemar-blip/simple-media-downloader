package com.example.simplemediadownloader

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class NetworkSecurityPolicyTest {

    @Test
    fun `isAllowedHost rejects loopback and cloud metadata hostnames`() {
        assertFalse(NetworkSecurityPolicy.isAllowedHost("localhost"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("127.0.0.1"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("127.0.1.1"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("::1"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("169.254.169.254"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("metadata.google.internal"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("sub.metadata.google.internal"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("instance-data"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("metadata.packet.net"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("local"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("mydevice.local"))
        assertFalse(NetworkSecurityPolicy.isAllowedHost("server.internal"))
    }

    @Test
    fun `isAllowedHost accepts legitimate public domains`() {
        assertTrue(NetworkSecurityPolicy.isAllowedHost("www.youtube.com"))
        assertTrue(NetworkSecurityPolicy.isAllowedHost("tiktok.com"))
        assertTrue(NetworkSecurityPolicy.isAllowedHost("v16-webapp-prime.tiktok.com"))
        assertTrue(NetworkSecurityPolicy.isAllowedHost("instagram.com"))
        assertTrue(NetworkSecurityPolicy.isAllowedHost("x.com"))
        assertTrue(NetworkSecurityPolicy.isAllowedHost("twitter.com"))
        assertTrue(NetworkSecurityPolicy.isAllowedHost("reddit.com"))
        assertTrue(NetworkSecurityPolicy.isAllowedHost("8.8.8.8"))
        assertTrue(NetworkSecurityPolicy.isAllowedHost("1.1.1.1"))
    }

    @Test
    fun `isAllowedIp rejects loopback addresses`() {
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("127.0.0.1")))
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("127.0.0.254")))
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("::1")))
    }

    @Test
    fun `isAllowedIp rejects RFC 1918 private IPv4 addresses`() {
        // 10.0.0.0/8
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("10.0.0.1")))
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("10.255.255.254")))
        // 172.16.0.0/12
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("172.16.0.1")))
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("172.31.255.254")))
        // 192.168.0.0/16
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("192.168.1.1")))
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("192.168.254.254")))
    }

    @Test
    fun `isAllowedIp rejects IPv6 Unique Local Addresses and link-local`() {
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("fc00::1")))
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("fd00::1234")))
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("fe80::1")))
    }

    @Test
    fun `isAllowedIp rejects link-local and cloud metadata 169-254-x-x`() {
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("169.254.169.254")))
        assertFalse(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("169.254.1.1")))
    }

    @Test
    fun `isAllowedIp accepts public routable IP addresses`() {
        assertTrue(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("8.8.8.8")))
        assertTrue(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("1.1.1.1")))
        assertTrue(NetworkSecurityPolicy.isAllowedIp(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun `isAllowedMediaUrl enforces HTTPS unless allowCleartextHttp is explicitly set`() {
        assertTrue(NetworkSecurityPolicy.isAllowedMediaUrl("https://example.com/stream.mp4"))
        assertFalse(NetworkSecurityPolicy.isAllowedMediaUrl("http://example.com/stream.mp4", allowCleartextHttp = false))
        assertTrue(NetworkSecurityPolicy.isAllowedMediaUrl("http://example.com/stream.mp4", allowCleartextHttp = true))
    }

    @Test
    fun `SafeDns blocks resolution to private or loopback addresses`() {
        val mockDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
                "safe.example.com" -> listOf(InetAddress.getByName("93.184.216.34"))
                "evil-internal.example.com" -> listOf(InetAddress.getByName("192.168.1.100"))
                "evil-loopback.example.com" -> listOf(InetAddress.getByName("127.0.0.1"))
                "evil-metadata.example.com" -> listOf(InetAddress.getByName("169.254.169.254"))
                else -> emptyList()
            }
        }

        val safeDns = SafeDns(mockDns)

        // Safe domain should resolve
        val safeIps = safeDns.lookup("safe.example.com")
        assertEquals(1, safeIps.size)

        // Blocked domain resolving to private IP
        try {
            safeDns.lookup("evil-internal.example.com")
            org.junit.Assert.fail("Expected UnknownHostException for private IP")
        } catch (e: UnknownHostException) {
            assertTrue(e.message?.contains("blocked by network security policy") == true)
        }

        // Blocked domain resolving to loopback IP
        try {
            safeDns.lookup("evil-loopback.example.com")
            org.junit.Assert.fail("Expected UnknownHostException for loopback IP")
        } catch (e: UnknownHostException) {
            assertTrue(e.message?.contains("blocked by network security policy") == true)
        }

        // Blocked domain resolving to metadata IP
        try {
            safeDns.lookup("evil-metadata.example.com")
            org.junit.Assert.fail("Expected UnknownHostException for metadata IP")
        } catch (e: UnknownHostException) {
            assertTrue(e.message?.contains("blocked by network security policy") == true)
        }
    }
}
