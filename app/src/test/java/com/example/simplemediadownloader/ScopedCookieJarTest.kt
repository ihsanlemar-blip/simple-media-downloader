package com.example.simplemediadownloader

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScopedCookieJarTest {

    private lateinit var cookieJar: ScopedCookieJar

    @Before
    fun setUp() {
        cookieJar = ScopedCookieJar()
    }

    @Test
    fun `cookies are scoped to domain and not shared across foreign hosts`() {
        val tiktokUrl = "https://www.tiktok.com/video/123".toHttpUrl()
        val twitterUrl = "https://twitter.com/i/status/456".toHttpUrl()

        val cookie = Cookie.Builder()
            .domain("tiktok.com")
            .path("/")
            .name("session_id")
            .value("secret123")
            .build()

        cookieJar.saveFromResponse(tiktokUrl, listOf(cookie))

        val tiktokCookies = cookieJar.loadForRequest(tiktokUrl)
        assertEquals(1, tiktokCookies.size)
        assertEquals("session_id", tiktokCookies[0].name)
        assertEquals("secret123", tiktokCookies[0].value)

        val twitterCookies = cookieJar.loadForRequest(twitterUrl)
        assertTrue(twitterCookies.isEmpty())
    }

    @Test
    fun `cookies respect path matching`() {
        val apiPathUrl = "https://example.com/api/v1/user".toHttpUrl()
        val otherPathUrl = "https://example.com/public/images".toHttpUrl()

        val apiCookie = Cookie.Builder()
            .domain("example.com")
            .path("/api")
            .name("api_token")
            .value("token_abc")
            .build()

        cookieJar.saveFromResponse(apiPathUrl, listOf(apiCookie))

        val matchedCookies = cookieJar.loadForRequest(apiPathUrl)
        assertEquals(1, matchedCookies.size)

        val unmatchedCookies = cookieJar.loadForRequest(otherPathUrl)
        assertTrue(unmatchedCookies.isEmpty())
    }

    @Test
    fun `secure cookies are not returned for plain http requests`() {
        val httpsUrl = "https://secure.example.com/stream".toHttpUrl()
        val httpUrl = "http://secure.example.com/stream".toHttpUrl()

        val secureCookie = Cookie.Builder()
            .domain("secure.example.com")
            .path("/")
            .name("secure_token")
            .value("secure_val")
            .secure()
            .build()

        cookieJar.saveFromResponse(httpsUrl, listOf(secureCookie))

        val httpsCookies = cookieJar.loadForRequest(httpsUrl)
        assertEquals(1, httpsCookies.size)

        val httpCookies = cookieJar.loadForRequest(httpUrl)
        assertTrue(httpCookies.isEmpty())
    }

    @Test
    fun `expired cookies are pruned and not returned`() {
        val url = "https://example.com/data".toHttpUrl()

        // Expired cookie in the past
        val expiredCookie = Cookie.Builder()
            .domain("example.com")
            .path("/")
            .name("expired_token")
            .value("dead")
            .expiresAt(System.currentTimeMillis() - 10_000)
            .build()

        cookieJar.saveFromResponse(url, listOf(expiredCookie))

        val cookies = cookieJar.loadForRequest(url)
        assertTrue(cookies.isEmpty())
    }

    @Test
    fun `clear removes all stored cookies`() {
        val url = "https://example.com/media".toHttpUrl()
        val cookie = Cookie.Builder()
            .domain("example.com")
            .path("/")
            .name("auth")
            .value("val")
            .build()

        cookieJar.saveFromResponse(url, listOf(cookie))
        assertEquals(1, cookieJar.loadForRequest(url).size)

        cookieJar.clear()
        assertTrue(cookieJar.loadForRequest(url).isEmpty())
    }
}
