package com.example.simplemediadownloader

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Lists newest public submissions supported by the existing Reddit single-post extractor. */
class RedditProfileExtractor internal constructor(private val http: ProfileHttpClient) : PagedProfileExtractor("Reddit") {
    constructor(client: OkHttpClient, dispatchers: AppDispatchers = AppDispatchers()) : this(
        ProfileHttpClient(client, setOf("www.reddit.com", "reddit.com"), dispatchers))

    override suspend fun load(address: ProfileAddress, cursor: String?): ProfileSourcePage {
        val url = "https://www.reddit.com/user/${address.handle}/submitted.json".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "new").addQueryParameter("limit", "25")
        cursor?.let { after ->
            if (!after.matches(Regex("t3_[a-z0-9]{1,30}"))) throw ProfileDiscoveryException("Could not load more posts.")
            url.addQueryParameter("after", after)
        }
        val root = JSONObject(http.get(url.build().toString()))
        if (root.optBoolean("is_suspended")) throw ProfileDiscoveryException("Profile does not exist or is unavailable.")
        if (root.text("kind") != "Listing") throw ProfileDiscoveryException("Profile format changed and requires an app update.")
        val data = root.optJSONObject("data") ?: throw ProfileDiscoveryException("Could not load more posts.")
        val children = data.optJSONArray("children") ?: throw ProfileDiscoveryException("Could not load more posts.")
        val posts = (0 until minOf(children.length(), 100)).mapNotNull { index ->
            val child = children.optJSONObject(index) ?: return@mapNotNull null
            if (child.text("kind") != "t3") return@mapNotNull null
            val post = child.optJSONObject("data") ?: return@mapNotNull null
            val owner = post.text("author") ?: return@mapNotNull null
            if (!owner.equals(address.handle, true) || post.optBoolean("removed_by_category") || post.text("removed_by_category") != null) return@mapNotNull null
            val video = (post.optJSONObject("secure_media") ?: post.optJSONObject("media"))?.optJSONObject("reddit_video")
                ?: return@mapNotNull null // External links/photos are unsupported by the existing child extractor.
            if (video.text("fallback_url") == null) return@mapNotNull null
            val id = post.text("id")?.takeIf { it.matches(Regex("[a-z0-9]{1,30}")) } ?: return@mapNotNull null
            val permalink = post.text("permalink") ?: return@mapNotNull null
            val canonical = "https://www.reddit.com".toHttpUrl().resolve(permalink) ?: return@mapNotNull null
            val segments = canonical.pathSegments
            if (canonical.host != "www.reddit.com" || segments.size < 4 || segments[0] !in setOf("r", "user") || segments[2] != "comments" || segments[3] != id) return@mapNotNull null
            CollectionItem(id, canonical.newBuilder().query(null).fragment(null).build().toString(), post.text("title"), "@${address.handle}",
                post.text("thumbnail")?.takeIf { NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false) },
                video.optLong("duration").takeIf { it > 0 }, index,
                publishedAtSeconds = post.optDouble("created_utc", 0.0).takeIf { it.isFinite() && it > 0 }?.toLong())
        }
        val next = data.text("after")?.also { if (!it.matches(Regex("t3_[a-z0-9]{1,30}"))) throw ProfileDiscoveryException("Could not load more posts.") }
        return ProfileSourcePage(profileInfo(address), posts, next)
    }
}
