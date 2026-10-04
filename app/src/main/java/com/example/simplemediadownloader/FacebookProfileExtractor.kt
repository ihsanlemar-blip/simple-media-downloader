package com.example.simplemediadownloader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Reads public timeline payloads only; never guesses GraphQL IDs or visits unrelated posts. */
class FacebookProfileExtractor internal constructor(private val http: ProfileHttpClient) : PagedProfileExtractor("Facebook") {
    constructor(client: OkHttpClient, dispatchers: AppDispatchers = AppDispatchers()) : this(
        ProfileHttpClient(client, setOf("www.facebook.com", "facebook.com", "m.facebook.com"), dispatchers))

    override suspend fun load(address: ProfileAddress, cursor: String?): ProfileSourcePage {
        val target = cursor?.let { JSONObject(it).getString("url") } ?: address.url
        val targetUrl = target.toHttpUrl()
        val sourceUrl = address.url.toHttpUrl()
        if (targetUrl.encodedPath != sourceUrl.encodedPath || (sourceUrl.queryParameter("id") != null && targetUrl.queryParameter("id") != sourceUrl.queryParameter("id"))) {
            throw ProfileDiscoveryException("Unsupported profile continuation.")
        }
        val body = http.get(target)
        val roots = mutableListOf<Any>()
        if (body.trimStart().startsWith("{")) roots += JSONObject(body)
        else Regex("<script\\b[^>]*\\bdata-sjs[^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(body).take(200).forEach { match -> runCatching { JSONObject(match.groupValues[1]) }.getOrNull()?.let { roots += it } }
        val feeds = mutableListOf<JSONObject>()
        var title: String? = null
        var thumbnail: String? = null
        for (root in roots) walk(root) { obj ->
            for (key in listOf("timeline_list_feed_units", "timeline_feed_units")) obj.optJSONObject(key)?.let { feeds += it }
            obj.optJSONObject("profile")?.let { profile ->
                if (profile.optBoolean("is_private")) throw ProfileDiscoveryException("Profile is private.")
                title = profile.text("name") ?: title
                thumbnail = profile.optJSONObject("profile_picture")?.text("uri") ?: thumbnail
            }
        }
        if (feeds.isEmpty()) throw ProfileDiscoveryException("Could not load public posts. Facebook may require sign-in or an app update.")
        val posts = mutableListOf<CollectionItem>()
        var next: String? = null
        var moreWithoutUrl = false
        for (feed in feeds) {
            val edges = feed.optJSONArray("edges") ?: continue
            for (index in 0 until minOf(edges.length(), 100)) {
                currentCoroutineContext().ensureActive()
                val node = edges.optJSONObject(index)?.optJSONObject("node") ?: continue
                // Only media inside an owned timeline story is eligible, never recommendations elsewhere in the page.
                val story = node.optJSONObject("comet_sections")?.optJSONObject("content")?.optJSONObject("story") ?: node
                val attachments = story.optJSONArray("attachments") ?: continue
                var video: JSONObject? = null
                walk(attachments) { obj -> if (video == null && obj.text("__typename") == "Video") video = obj }
                val media = video ?: continue
                val id = media.text("id")?.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: continue
                posts += CollectionItem(id, "https://www.facebook.com/watch/?v=$id", story.optJSONObject("message")?.text("text") ?: media.text("name"),
                    "@${address.handle}", media.optJSONObject("preferred_thumbnail")?.optJSONObject("image")?.text("uri"),
                    media.optDouble("playable_duration_in_ms", 0.0).takeIf { it.isFinite() && it > 0 }?.div(1000)?.toLong()?.takeIf { it > 0 }, posts.size,
                    publishedAtSeconds = story.optLong("creation_time").takeIf { it > 0 })
            }
            val page = feed.optJSONObject("page_info")
            if (page?.optBoolean("has_next_page") == true) {
                val url = page.text("next_url") ?: feed.text("next_url")
                if (url != null) {
                    val resolved = targetUrl.resolve(url) ?: throw ProfileDiscoveryException("Could not load more posts.")
                    // ProfileHttpClient also enforces first-party host, DNS and redirect policy on the next request.
                    if (resolved.encodedPath != sourceUrl.encodedPath) throw ProfileDiscoveryException("Unsupported profile continuation.")
                    next = JSONObject().put("url", resolved.toString()).toString()
                } else moreWithoutUrl = true
            }
        }
        return ProfileSourcePage(profileInfo(address, title, thumbnail), posts.distinctBy { it.id }.take(100), next,
            if (moreWithoutUrl && next == null) "Facebook did not expose a public next page. Only the available public feed can be shown." else null)
    }

    private suspend fun walk(root: Any, visit: (JSONObject) -> Unit) {
        val pending = java.util.ArrayDeque<Pair<Any, Int>>()
        pending.add(root to 0)
        var seen = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (++seen > 20_000) throw ProfileDiscoveryException("Profile format changed and requires an app update.")
            val (value, depth) = pending.removeLast()
            if (depth > 40) continue
            when (value) {
                is JSONObject -> {
                    visit(value)
                    value.keys().forEach { key -> value.opt(key)?.takeIf { it is JSONObject || it is JSONArray }?.let { pending.add(it to depth + 1) } }
                }
                is JSONArray -> for (i in 0 until value.length()) value.opt(i)?.takeIf { it is JSONObject || it is JSONArray }?.let { pending.add(it to depth + 1) }
            }
        }
    }
}
