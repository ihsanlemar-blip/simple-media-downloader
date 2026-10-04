package com.example.simplemediadownloader

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Uses Twitter's own public embedded timeline, not third-party profile crawling. */
class TwitterProfileExtractor internal constructor(private val http: ProfileHttpClient) : PagedProfileExtractor("X/Twitter") {
    constructor(client: OkHttpClient, dispatchers: AppDispatchers = AppDispatchers()) : this(
        ProfileHttpClient(client, setOf("syndication.twitter.com"), dispatchers))

    override suspend fun load(address: ProfileAddress, cursor: String?): ProfileSourcePage {
        val endpoint = "https://syndication.twitter.com/srv/timeline-profile/screen-name/${address.handle}".toHttpUrl()
        val target = cursor?.let { JSONObject(it).getString("url").toHttpUrl() } ?: endpoint
        if (target.host != endpoint.host || target.encodedPath != endpoint.encodedPath) throw ProfileDiscoveryException("Unsupported profile continuation.")
        val body = http.get(target.toString())
        val embedded = extractJsonFromHtmlTag(body, "id=\"__NEXT_DATA__\"")
            ?: throw ProfileDiscoveryException("Profile format changed and requires an app update.")
        val props = JSONObject(embedded).optJSONObject("props")?.optJSONObject("pageProps")
            ?: throw ProfileDiscoveryException("Could not load public posts.")
        if (props.optBoolean("protected") || props.optJSONObject("headerProps")?.optBoolean("protected") == true) throw ProfileDiscoveryException("Profile is private.")
        val timeline = props.optJSONObject("timeline") ?: throw ProfileDiscoveryException("Profile does not exist or requires sign-in.")
        val entries = timeline.optJSONArray("entries") ?: throw ProfileDiscoveryException("Could not load more posts.")
        val posts = (0 until minOf(entries.length(), 100)).mapNotNull { index ->
            val content = entries.optJSONObject(index)?.optJSONObject("content") ?: return@mapNotNull null
            if (content.optBoolean("isPromoted")) return@mapNotNull null
            val tweet = content.optJSONObject("tweet") ?: return@mapNotNull null
            val owner = tweet.optJSONObject("user")?.text("screen_name")
            if (owner != null && !owner.equals(address.handle, true)) return@mapNotNull null
            val id = (tweet.text("id_str") ?: tweet.text("id"))?.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: return@mapNotNull null
            val media = tweet.optJSONObject("extended_entities")?.optJSONArray("media") ?: tweet.optJSONObject("entities")?.optJSONArray("media")
            val video = media?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) }.firstOrNull { it.text("type") in setOf("video", "animated_gif") } }
                ?: return@mapNotNull null
            val duration = video.optJSONObject("video_info")?.optLong("duration_millis")?.takeIf { it > 0 }?.div(1000)?.takeIf { it > 0 }
            val timestamp = tweet.text("created_at")?.let { value ->
                runCatching { Instant.parse(value).epochSecond }.getOrNull()
                    ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)).toEpochSecond() }.getOrNull()
            }
            CollectionItem(id, "https://x.com/${address.handle}/status/$id", tweet.text("full_text") ?: tweet.text("text"),
                "@${address.handle}", video.text("media_url_https"), duration, index, publishedAtSeconds = timestamp)
        }
        val next = timeline.text("next_url")?.let { url ->
            val resolved = target.resolve(url) ?: throw ProfileDiscoveryException("Could not load more posts.")
            if (resolved.host != endpoint.host || resolved.encodedPath != endpoint.encodedPath) throw ProfileDiscoveryException("Unsupported profile continuation.")
            JSONObject().put("url", resolved.toString()).toString()
        }
        val header = props.optJSONObject("headerProps")
        return ProfileSourcePage(profileInfo(address, header?.text("name"), header?.text("profileImageUrl")), posts, next,
            if (next == null) "X may limit its public embedded feed. Individual post downloads still require the existing third-party extractor opt-in." else null)
    }
}
