package com.example.simplemediadownloader

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Public web profile metadata only; post streams stay in InstagramExtractor. */
class InstagramProfileExtractor internal constructor(private val http: ProfileHttpClient) : PagedProfileExtractor("Instagram") {
    constructor(client: OkHttpClient, dispatchers: AppDispatchers = AppDispatchers()) : this(
        ProfileHttpClient(client, setOf("www.instagram.com", "instagram.com"), dispatchers))

    override suspend fun load(address: ProfileAddress, cursor: String?): ProfileSourcePage {
        val url = if (cursor == null) {
            "https://www.instagram.com/api/v1/users/web_profile_info/".toHttpUrl().newBuilder()
                .addQueryParameter("username", address.handle).build()
        } else {
            val state = JSONObject(cursor)
            val id = state.getString("id").also { require(it.matches(Regex("[0-9]{1,30}"))) }
            val after = state.getString("after").also { require(it.length in 1..4096) }
            // Public web timeline query. A changed contract fails safely instead of scraping sign-in pages.
            "https://www.instagram.com/graphql/query/".toHttpUrl().newBuilder()
                .addQueryParameter("query_hash", "003056d32c2554def87228bc3fd9668a")
                .addQueryParameter("variables", JSONObject().put("id", id).put("first", 30).put("after", after).toString()).build()
        }
        val json = try { JSONObject(http.get(url.toString(), mapOf("X-IG-App-ID" to "936619743392459"))) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: ProfileDiscoveryException) { throw error }
        catch (_: Exception) { throw ProfileDiscoveryException("Could not load public posts. Instagram may require sign-in or an app update.") }
        val user = json.optJSONObject("data")?.optJSONObject("user")
            ?: throw ProfileDiscoveryException("Profile does not exist or requires sign-in.")
        if (user.optBoolean("is_private")) throw ProfileDiscoveryException("Profile is private.")
        val owner = user.text("username")
        if (owner != null && !owner.equals(address.handle, true)) throw ProfileDiscoveryException("Unsupported profile response.")
        val id = user.text("id") ?: cursor?.let { JSONObject(it).text("id") }
        val timeline = user.optJSONObject("edge_owner_to_timeline_media")
            ?: throw ProfileDiscoveryException("Profile format changed and requires an app update.")
        val edges = timeline.optJSONArray("edges") ?: throw ProfileDiscoveryException("Could not load more posts.")
        val items = (0 until minOf(edges.length(), 100)).mapNotNull { index ->
            val node = edges.optJSONObject(index)?.optJSONObject("node") ?: return@mapNotNull null
            val children = node.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
            val playable = node.optBoolean("is_video") || (children != null && (0 until children.length()).any {
                children.optJSONObject(it)?.optJSONObject("node")?.optBoolean("is_video") == true
            })
            if (!playable) return@mapNotNull null // Photos are outside the existing media downloader.
            val shortcode = node.text("shortcode")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: return@mapNotNull null
            val caption = node.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.text("text")
            CollectionItem(node.text("id") ?: shortcode, "https://www.instagram.com/p/$shortcode/", caption,
                "@${address.handle}", node.text("thumbnail_src") ?: node.text("display_url"),
                node.optDouble("video_duration", 0.0).takeIf { it.isFinite() && it > 0 }?.toLong()?.takeIf { it > 0 }, index,
                publishedAtSeconds = node.optLong("taken_at_timestamp").takeIf { it > 0 })
        }
        val page = timeline.optJSONObject("page_info")
        val next = if (page?.optBoolean("has_next_page") == true) {
            val after = page.text("end_cursor") ?: throw ProfileDiscoveryException("Could not load more posts.")
            if (id == null || !id.matches(Regex("[0-9]{1,30}"))) throw ProfileDiscoveryException("Could not load more posts.")
            JSONObject().put("id", id).put("after", after).toString()
        } else null
        return ProfileSourcePage(profileInfo(address, user.text("full_name"), user.text("profile_pic_url_hd")), items, next)
    }
}
