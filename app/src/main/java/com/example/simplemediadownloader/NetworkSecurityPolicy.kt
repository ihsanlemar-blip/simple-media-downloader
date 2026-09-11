package com.example.simplemediadownloader

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/**
 * Centralized network security policy enforcing:
 * 1. Blocking of loopback, private RFC 1918, IPv6 ULA, link-local, and cloud metadata destinations.
 * 2. Mandatory HTTPS for media stream requests and redirects.
 * 3. Rejection of user-info credentials embedded in URLs.
 * 4. Interception and validation of HTTP redirect targets.
 */
object NetworkSecurityPolicy {

    private val BLOCKED_HOST_SUFFIXES = listOf(
        "localhost",
        ".localhost",
        ".local",
        ".internal",
        ".lan",
        ".home",
        ".corp",
    )

    private val BLOCKED_EXACT_HOSTS = setOf(
        "localhost",
        "metadata.google.internal",
        "metadata",
        "instance-data",
        "metadata.packet.net",
    )

    /**
     * Inspects whether an IP address belongs to loopback, private, link-local,
     * carrier-grade NAT, or cloud metadata ranges.
     */
    fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        val raw = address.address
        return when (address) {
            is Inet4Address -> isBlockedIpv4(raw)
            is Inet6Address -> isBlockedIpv6(raw)
            else -> raw.size == 4 && isBlockedIpv4(raw) || raw.size == 16 && isBlockedIpv6(raw)
        }
    }

    private fun isBlockedIpv4(raw: ByteArray): Boolean {
        if (raw.size != 4) return false
        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF
        val b2 = raw[2].toInt() and 0xFF
        val b3 = raw[3].toInt() and 0xFF

        return when {
            // 0.0.0.0/8 (Current network)
            b0 == 0 -> true
            // 10.0.0.0/8 (Private-Use)
            b0 == 10 -> true
            // 100.64.0.0/10 (Shared Address / CGNAT)
            b0 == 100 && (b1 and 0xC0) == 64 -> true
            // 127.0.0.0/8 (Loopback)
            b0 == 127 -> true
            // 169.254.0.0/16 (Link Local, including 169.254.169.254 cloud metadata)
            b0 == 169 && b1 == 254 -> true
            // 172.16.0.0/12 (Private-Use)
            b0 == 172 && (b1 and 0xF0) == 16 -> true
            // 192.0.2.0/24 (TEST-NET-1)
            b0 == 192 && b1 == 0 && b2 == 2 -> true
            // 192.168.0.0/16 (Private-Use)
            b0 == 192 && b1 == 168 -> true
            // 198.51.100.0/24 (TEST-NET-2)
            b0 == 198 && b1 == 51 && b2 == 100 -> true
            // 203.0.113.0/24 (TEST-NET-3)
            b0 == 203 && b1 == 0 && b2 == 113 -> true
            // 224.0.0.0/4 (Multicast) and 240.0.0.0/4 (Reserved)
            b0 >= 224 -> true
            // Broadcast
            b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255 -> true
            else -> false
        }
    }

    private fun isBlockedIpv6(raw: ByteArray): Boolean {
        if (raw.size != 16) return false

        // Check for IPv4-mapped IPv6 (::ffff:x.x.x.x)
        val isIpv4Mapped = (0..9).all { raw[it] == 0.toByte() } &&
            raw[10] == 0xFF.toByte() && raw[11] == 0xFF.toByte()
        if (isIpv4Mapped) {
            return isBlockedIpv4(raw.sliceArray(12..15))
        }

        // Check for IPv4-compatible IPv6 (::x.x.x.x)
        val isIpv4Compatible = (0..11).all { raw[it] == 0.toByte() } &&
            !(raw[12] == 0.toByte() && raw[13] == 0.toByte() && raw[14] == 0.toByte() && (raw[15] == 0.toByte() || raw[15] == 1.toByte()))
        if (isIpv4Compatible) {
            return isBlockedIpv4(raw.sliceArray(12..15))
        }

        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF

        // ::1 (Loopback)
        val isLoopback = (0..14).all { raw[it] == 0.toByte() } && raw[15] == 1.toByte()
        if (isLoopback) return true

        // :: (Unspecified)
        val isUnspecified = (0..15).all { raw[it] == 0.toByte() }
        if (isUnspecified) return true

        // fc00::/7 (Unique Local Address - ULA)
        if ((b0 and 0xFE) == 0xFC) return true

        // fe80::/10 (Link-Local)
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true

        // fec0::/10 (Site-Local deprecated)
        if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return true

        // ff00::/8 (Multicast)
        if (b0 == 0xFF) return true

        return false
    }

    /**
     * Checks whether the host string is a reserved, local, or prohibited name.
     */
    fun isBlockedHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.US).trimEnd('.')
        if (normalized.isBlank()) return true
        if (BLOCKED_EXACT_HOSTS.contains(normalized)) return true
        if (BLOCKED_HOST_SUFFIXES.any { suffix ->
            normalized == suffix.trimStart('.') || normalized.endsWith(suffix)
        }) return true

        // If host is an IP literal, evaluate it directly
        val ipLiteral = runCatching { InetAddress.getByName(normalized) }.getOrNull()
        if (ipLiteral != null && isBlockedAddress(ipLiteral)) {
            return true
        }

        return false
    }

    /**
     * Validates an HttpUrl for compliance.
     * @param allowHttp whether plain HTTP is permitted (e.g. for initial web pages). Media streams must require HTTPS.
     * @throws SecurityException if the URL violates security policies.
     */
    fun validateUrl(url: HttpUrl, allowHttp: Boolean = false) {
        if (!allowHttp && !url.isHttps) {
            throw SecurityException("Insecure cleartext HTTP scheme is prohibited for media streams: ${url.redacted()}")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw SecurityException("URLs with embedded credentials are not allowed: ${url.redacted()}")
        }
        val host = url.host
        if (isBlockedHost(host)) {
            throw SecurityException("Access to host '$host' is prohibited by network security policy.")
        }
    }

    /**
     * Validates a URL string for media stream fetching. Rejects cleartext HTTP and blocked hosts.
     */
    fun validateMediaStreamUrl(urlString: String) {
        val httpUrl = urlString.toHttpUrlOrNull()
            ?: throw SecurityException("Malformed media stream URL.")
        validateUrl(httpUrl, allowHttp = false)
    }

    /**
     * Checks whether a shared URL is acceptable for extraction.
     */
    fun isAllowedShareUrl(urlString: String): Boolean {
        val httpUrl = urlString.toHttpUrlOrNull() ?: return false
        if (httpUrl.scheme != "http" && httpUrl.scheme != "https") return false
        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return false
        if (isBlockedHost(httpUrl.host)) return false
        return true
    }

    fun isAllowedHost(host: String): Boolean = !isBlockedHost(host)

    fun isAllowedIp(address: InetAddress): Boolean = !isBlockedAddress(address)

    fun isAllowedMediaUrl(urlString: String, allowCleartextHttp: Boolean = false): Boolean {
        val httpUrl = urlString.toHttpUrlOrNull() ?: return false
        return try {
            validateUrl(httpUrl, allowHttp = allowCleartextHttp)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun HttpUrl.redacted(): String =
        newBuilder().username("").password("").build().toString()
}

/**
 * An OkHttp Dns implementation that resolves hostnames and verifies every
 * returned IP address against [NetworkSecurityPolicy]. Prohibits SSRF to local/private networks.
 */
class SafeDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        if (NetworkSecurityPolicy.isBlockedHost(hostname)) {
            throw UnknownHostException("Host '$hostname' is blocked by security policy.")
        }

        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty()) {
            throw UnknownHostException("No IP addresses found for host '$hostname'.")
        }

        for (addr in addresses) {
            if (NetworkSecurityPolicy.isBlockedAddress(addr)) {
                throw UnknownHostException(
                    "Address ${addr.hostAddress} for host '$hostname' is blocked by network security policy.",
                )
            }
        }

        return addresses
    }
}

/**
 * An OkHttp Interceptor that validates outgoing request URLs and inspects
 * redirect targets to enforce destination security before following them.
 */
class SecurityInterceptor(
    private val allowCleartextHttp: Boolean = false,
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            NetworkSecurityPolicy.validateUrl(request.url, allowHttp = allowCleartextHttp)
        } catch (e: SecurityException) {
            throw IOException("Security policy violation: ${e.message}", e)
        }

        val response = chain.proceed(request)

        if (response.isRedirect) {
            val location = response.header("Location")
            if (!location.isNullOrBlank()) {
                val targetUrl = request.url.resolve(location)
                if (targetUrl != null) {
                    try {
                        NetworkSecurityPolicy.validateUrl(targetUrl, allowHttp = allowCleartextHttp)
                    } catch (e: SecurityException) {
                        response.close()
                        throw IOException("Redirect security policy violation: ${e.message}", e)
                    }
                }
            }
        }

        return response
    }
}
