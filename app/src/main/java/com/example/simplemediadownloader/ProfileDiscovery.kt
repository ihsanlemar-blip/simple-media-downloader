package com.example.simplemediadownloader

import java.net.URI
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import org.json.JSONObject
import kotlin.random.Random

object ProfileDiscoveryPolicy {
    const val DEFAULT_COUNT = 20
    const val MAX_COUNT = 100
    val PRESETS = listOf(10, 20, 50, 100)
    fun validate(count: Int) = require(count in 1..MAX_COUNT) { "Choose between 1 and 100 posts" }
}

data class ProfileAddress(val platform: String, val handle: String, val url: String) {
    companion object {
        fun parse(url: String): ProfileAddress? {
            if (!NetworkSecurityPolicy.isAllowedShareUrl(url)) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.") ?: return null
            val parts = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
            val reserved = setOf("watch", "reel", "reels", "p", "tv", "share", "stories", "explore", "accounts", "about", "help", "legal", "login", "logout", "settings", "direct", "home", "search", "i", "intent", "messages", "notifications", "compose", "hashtag", "photo.php", "story.php", "permalink.php", "groups", "marketplace", "gaming", "events", "business", "pages", "people", "home.php", "index.php", "login.php", "notifications.php", "photo", "photos", "video", "videos", "albums", "saved", "friends", "bookmarks.php")
            val name = parts.singleOrNull()
            return when {
                host == "tiktok.com" && name?.matches(Regex("@[A-Za-z0-9._]{1,24}")) == true -> ProfileAddress("TikTok", name.drop(1), "https://www.tiktok.com/$name")
                host == "instagram.com" && name?.matches(Regex("[A-Za-z0-9._]{1,30}")) == true && name.lowercase(Locale.ROOT) !in reserved && name.any(Char::isLetterOrDigit) -> ProfileAddress("Instagram", name, "https://www.instagram.com/$name/")
                host in setOf("twitter.com", "x.com") && name?.matches(Regex("[A-Za-z0-9_]{1,15}")) == true && name.lowercase(Locale.ROOT) !in reserved -> ProfileAddress("X/Twitter", name, "https://x.com/$name")
                host == "reddit.com" && parts.size in 2..3 && parts[0] in setOf("user", "u") && parts[1].matches(Regex("[A-Za-z0-9_-]{3,20}")) && (parts.size == 2 || parts[2] in setOf("submitted", "overview")) -> ProfileAddress("Reddit", parts[1], "https://www.reddit.com/user/${parts[1]}/")
                host == "facebook.com" && uri.rawQuery.orEmpty().split('&').none { it.startsWith("story_fbid=") || it.startsWith("v=") } -> {
                    val id = when {
                        name == "profile.php" -> uri.rawQuery.orEmpty().split('&').singleOrNull { it.startsWith("id=") }?.substringAfter('=')?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
                        parts.size == 3 && parts[0] in setOf("people", "pages") && parts[2].matches(Regex("[0-9]{1,30}")) -> parts[2]
                        name?.matches(Regex("[A-Za-z0-9.\u002d]{1,100}")) == true && name.lowercase(Locale.ROOT) !in reserved -> name
                        else -> null
                    }
                    id?.let { ProfileAddress("Facebook", it, if (it.all(Char::isDigit)) "https://www.facebook.com/profile.php?id=$it" else "https://www.facebook.com/$it") }
                }
                else -> null
            }
        }
    }
}

class ProfileDiscoveryException(val userMessage: String) : IOException(userMessage)
internal fun profileError(error: Exception): String = (error as? ProfileDiscoveryException)?.userMessage
    ?: "Profile format changed and requires an app update."
internal data class ProfileResponse(val status: Int, val body: String, val retryAfterSeconds: Long? = null)
internal fun interface ProfileTransport { suspend fun get(url: String, headers: Map<String, String>): ProfileResponse }

/** Public first-party metadata only. No gateway endpoints, user authentication, or stream extraction. */
internal class ProfileHttpClient(
    client: OkHttpClient,
    private val hosts: Set<String>,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val transport: ProfileTransport? = null,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Long = { Random.nextLong(0, 251) },
) {
    private val publicClient = client.newBuilder().cookieJar(ScopedCookieJar()).dns(SafeDns())
        .addNetworkInterceptor(SecurityInterceptor()).followRedirects(false).followSslRedirects(false).build()
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        var target = url
        var redirects = 0
        fun validate(value: String) {
            val uri = URI(value)
            if (uri.scheme != "https" || uri.host !in hosts || !NetworkSecurityPolicy.isAllowedShareUrl(value)) throw ProfileDiscoveryException("Unsupported profile link.")
        }
        repeat(3) { attempt ->
            validate(target)
            val response = try {
                if (transport != null) transport.get(target, headers) else runInterruptible(dispatchers.io) {
                    val request = Request.Builder().url(target).header("User-Agent", USER_AGENT).header("Accept", "application/json,text/html").apply { headers.forEach { (name, value) -> header(name, value) } }.build()
                    publicClient.newCall(request).execute().use { r ->
                        if (r.isRedirect) {
                            val next = r.header("Location")?.let { r.request.url.resolve(it)?.toString() } ?: throw ProfileDiscoveryException("Could not load more posts.")
                            validate(next)
                            if (++redirects > 2) throw ProfileDiscoveryException("Could not load more posts.")
                            target = next
                            ProfileResponse(503, "") // A bounded, delayed retry follows only a first-party redirect.
                        } else ProfileResponse(r.code, r.body?.readBoundedString().orEmpty(), r.header("Retry-After")?.toLongOrNull())
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: ProfileDiscoveryException) { throw error }
            catch (_: IOException) {
                if (attempt == 2) throw ProfileDiscoveryException("Could not load more posts. Check your connection and try again.")
                wait((1_000L shl attempt) + jitter()); return@repeat
            }
            val body = response.body.lowercase(Locale.ROOT)
            if (response.status == 404) throw ProfileDiscoveryException("Profile does not exist.")
            if (response.status == 401 || (response.status == 403 && (body.contains("private") || body.contains("login") || body.contains("authentication")))) throw ProfileDiscoveryException("Profile is private or requires sign-in.")
            if (response.status == 429 || response.status == 403 || response.status in 500..599) {
                if (attempt == 2) throw ProfileDiscoveryException(if (response.status == 429) "Rate limited. Try again later." else "Could not load more posts. The platform is temporarily blocking public access.")
                wait(maxOf((1_000L shl attempt) + jitter(), (response.retryAfterSeconds ?: 0).coerceIn(0, 30) * 1000)); return@repeat
            }
            if (response.status !in 200..299) throw ProfileDiscoveryException("Could not load more posts.")
            if (response.body.isBlank()) throw ProfileDiscoveryException("Could not load more posts. The platform is limiting public access.")
            return response.body
        }
        throw ProfileDiscoveryException("Could not load more posts.")
    }
}

internal data class ProfileSourcePage(val info: CollectionInfo, val items: List<CollectionItem>, val next: String?, val notice: String? = null)
/** Shared pagination mechanics; each platform owns its requests and parser. */
abstract class PagedProfileExtractor internal constructor(private val platform: String) : CollectionExtractor {
    internal abstract suspend fun load(address: ProfileAddress, cursor: String?): ProfileSourcePage
    override suspend fun canHandle(url: String) = ProfileAddress.parse(url)?.platform == platform
    private fun address(url: String) = ProfileAddress.parse(url)?.takeIf { it.platform == platform } ?: throw ProfileDiscoveryException("Unsupported profile link.")
    private var cachedPage: Pair<Pair<String, String?>, ProfileSourcePage>? = null
    override suspend fun getInfo(url: String): CollectionInfo {
        val address = address(url)
        val page = load(address, null)
        cachedPage = (address.url to null) to page
        return page.info
    }
    override suspend fun getItems(url: String, limit: Int?, continuation: String?): CollectionPage {
        val count = limit ?: ProfileDiscoveryPolicy.DEFAULT_COUNT
        ProfileDiscoveryPolicy.validate(count)
        if (continuation != null && continuation.length > 65_536) throw ProfileDiscoveryException("Could not load more posts.")
        val token = continuation?.let(::JSONObject)
        val cursor = token?.optString("cursor")?.takeIf { it.isNotEmpty() }
        val offset = token?.optInt("offset", 0) ?: 0
        val position = token?.optInt("position", 0) ?: 0
        val address = address(url)
        val key = address.url to cursor
        val page = cachedPage?.takeIf { it.first == key }?.second ?: load(address, cursor).also { cachedPage = key to it }
        val sorted = page.items.distinctBy { it.id }.let { if (it.all { item -> item.publishedAtSeconds != null }) it.sortedByDescending { item -> item.publishedAtSeconds } else it }
        if (sorted.size > 100 || offset !in 0..sorted.size || position < 0 || position > 10_000) throw ProfileDiscoveryException("Profile format changed and requires an app update.")
        val items = sorted.drop(offset).take(count)
        val next = when {
            offset + items.size < sorted.size -> JSONObject().put("cursor", cursor.orEmpty()).put("offset", offset + items.size).put("position", position + items.size)
            page.next != null -> JSONObject().put("cursor", page.next).put("offset", 0).put("position", position + items.size)
            else -> null
        }?.toString()?.also { if (it.length > 65_536) throw ProfileDiscoveryException("Could not load more posts.") }
        if (offset + items.size >= sorted.size) cachedPage = null
        return CollectionPage(items.mapIndexed { index, item -> item.copy(position = position + index) }, next, next != null, page.notice)
    }
}
internal fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
internal fun profileInfo(address: ProfileAddress, title: String? = null, thumbnail: String? = null) = CollectionInfo(address.url, address.platform, CollectionType.SOCIAL_PROFILE, title ?: "@${address.handle}", author = "@${address.handle}", thumbnailUrl = thumbnail)
