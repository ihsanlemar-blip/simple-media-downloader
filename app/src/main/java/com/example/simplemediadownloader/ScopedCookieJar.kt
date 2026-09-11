package com.example.simplemediadownloader

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory, thread-safe, RFC 6265 compliant cookie store.
 * Cookies are strictly keyed by (domain, path, name) and only served to
 * requests that match domain, path, secure, and expiration criteria.
 */
class ScopedCookieJar(
    private val clock: () -> Long = System::currentTimeMillis,
) : CookieJar {

    private data class CookieKey(
        val domain: String,
        val path: String,
        val name: String,
    )

    private val storage = ConcurrentHashMap<CookieKey, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = clock()
        cookies.forEach { cookie ->
            val key = CookieKey(
                domain = cookie.domain.lowercase(),
                path = cookie.path,
                name = cookie.name,
            )
            if (cookie.expiresAt <= now) {
                storage.remove(key)
            } else {
                storage[key] = cookie
            }
        }
        pruneExpired(now)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = clock()
        pruneExpired(now)
        return storage.values.filter { cookie ->
            cookie.expiresAt > now && cookie.matches(url)
        }
    }

    /**
     * Formats cookies matching [url] into a standard HTTP "Cookie: name=value; name2=value2" header string.
     */
    fun getCookieHeader(url: HttpUrl): String {
        return loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * Formats cookies matching [urlString] into a header string, or empty string if invalid or no matches.
     */
    fun getCookiesForUrl(urlString: String): String {
        val httpUrl = urlString.toHttpUrlOrNull() ?: return ""
        return getCookieHeader(httpUrl)
    }

    /**
     * Manually records a cookie for a specific target URL, parsing standard Set-Cookie syntax.
     */
    fun setCookie(url: String, setCookieHeader: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val cookie = Cookie.parse(httpUrl, setCookieHeader) ?: return
        saveFromResponse(httpUrl, listOf(cookie))
    }

    /**
     * Clears all stored cookies.
     */
    fun clear() {
        storage.clear()
    }

    /**
     * Formats all currently valid, unexpired cookies into a standard HTTP Cookie header string.
     */
    fun getAllCookieHeader(): String {
        val now = clock()
        pruneExpired(now)
        return storage.values.filter { it.expiresAt > now }.joinToString("; ") { "${it.name}=${it.value}" }
    }

    val size: Int
        get() {
            pruneExpired(clock())
            return storage.size
        }

    private fun pruneExpired(now: Long) {
        val expiredKeys = storage.entries
            .filter { it.value.expiresAt <= now }
            .map { it.key }
        expiredKeys.forEach { storage.remove(it) }
    }
}
