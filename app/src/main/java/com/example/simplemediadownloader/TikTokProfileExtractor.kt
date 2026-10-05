package com.example.simplemediadownloader

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONArray

class TikTokProfileExtractor internal constructor(private val http: ProfileHttpClient,
    private val gateway: ProfileHttpClient? = null, private val allowGateway: () -> Boolean = { false }) : PagedProfileExtractor("TikTok") {
    constructor(client: OkHttpClient, dispatchers: AppDispatchers = AppDispatchers(), allowGateway: () -> Boolean = { false }) : this(
        ProfileHttpClient(client, setOf("www.tiktok.com", "tiktok.com"), dispatchers),
        ProfileHttpClient(client, setOf("www.tikwm.com"), dispatchers), allowGateway)
    override suspend fun load(address: ProfileAddress, cursor: String?): ProfileSourcePage {
        cursor?.let { if (JSONObject(it).optBoolean("gateway")) return loadGateway(address, JSONObject(it).getString("cursor")) }
        val json: JSONObject
        val secUid: String
        val user: JSONObject?
        val feed: JSONObject
        val items: JSONArray
        if (cursor == null) {
            val html = http.get(address.url)
            val embedded = extractJsonFromHtmlTag(html, "id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"")
                ?: extractJsonFromHtmlTag(html, "id=\"SIGI_STATE\"") ?: throw ProfileDiscoveryException("Profile format changed and requires an app update.")
            json = JSONObject(embedded)
            val scope = json.optJSONObject("__DEFAULT_SCOPE__")
            user = scope?.optJSONObject("webapp.user-detail")?.optJSONObject("userInfo")?.optJSONObject("user")
                ?: json.optJSONObject("UserModule")?.optJSONObject("users")?.optJSONObject(address.handle)
            if (user?.optBoolean("privateAccount") == true || user?.optBoolean("secret") == true) throw ProfileDiscoveryException("Profile is private.")
            secUid = user?.text("secUid") ?: throw ProfileDiscoveryException("Profile format changed and requires an app update.")
            feed = scope?.optJSONObject("webapp.user-post") ?: json.optJSONObject("ItemList")?.optJSONObject("user-post")
                ?: try { JSONObject(http.get("https://www.tiktok.com/api/post/item_list/".toHttpUrl().newBuilder()
                    .addQueryParameter("aid", "1988").addQueryParameter("secUid", secUid).addQueryParameter("cursor", "0").addQueryParameter("count", "30").build().toString())) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    return publicFallback(address, user)
                }
            if (feed.optInt("statusCode", 0) != 0) return publicFallback(address, user)
            items = feed.optJSONArray("itemList") ?: feed.optJSONArray("list")?.let { ids -> JSONArray().apply {
                val module = json.optJSONObject("ItemModule")
                for (i in 0 until ids.length()) module?.optJSONObject(ids.optString(i))?.let { put(it) }
            } } ?: return publicFallback(address, user)
        } else {
            val state = JSONObject(cursor)
            secUid = state.getString("secUid").also { require(it.length in 1..256) }
            val offset = state.getString("cursor").also { require(it.matches(Regex("[0-9]{1,30}"))) }
            val endpoint = "https://www.tiktok.com/api/post/item_list/".toHttpUrl().newBuilder()
                .addQueryParameter("aid", "1988").addQueryParameter("secUid", secUid).addQueryParameter("cursor", offset).addQueryParameter("count", "30").build()
            json = JSONObject(http.get(endpoint.toString()))
            if (json.optInt("statusCode", 0) != 0) throw ProfileDiscoveryException("Could not load more posts. The platform may require sign-in.")
            user = null; feed = json
            items = json.optJSONArray("itemList") ?: throw ProfileDiscoveryException("Profile format changed and requires an app update.")
        }
        val posts = mutableListOf<CollectionItem>()
        for (i in 0 until minOf(items.length(), 100)) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.text("id")?.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: continue
            val video = item.optJSONObject("video") ?: continue
            posts += CollectionItem(id, "https://www.tiktok.com/@${address.handle}/video/$id", item.text("desc"), "@${address.handle}", video.text("cover"), video.optLong("duration").takeIf { it > 0 }, i,
                publishedAtSeconds = item.optLong("createTime").takeIf { it > 0 })
        }
        val hasMore = feed.optBoolean("hasMore", feed.optBoolean("has_more", false))
        val next = if (hasMore) feed.text("cursor")?.let { JSONObject().put("secUid", secUid).put("cursor", it).toString() }
            ?: throw ProfileDiscoveryException("Could not load more posts.") else null
        return ProfileSourcePage(profileInfo(address, user?.text("nickname"), user?.text("avatarLarger")), posts, next)
    }
    private suspend fun publicFallback(address: ProfileAddress, user: JSONObject?): ProfileSourcePage {
        try { return loadPublicEmbed(address) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: ProfileDiscoveryException) { if (error.userMessage.contains("private", true)) throw error }
        catch (_: Exception) { /* A changed embed contract can still use the existing explicit gateway opt-in. */ }
        val publicKnown = (user?.has("privateAccount") == true && !user.optBoolean("privateAccount")) || (user?.has("secret") == true && !user.optBoolean("secret"))
        if (publicKnown && allowGateway() && gateway != null) return loadGateway(address, "0")
        throw ProfileDiscoveryException("Could not load recent public posts. Direct public access is limited; third-party profile fallback is disabled or unavailable.")
    }
    private suspend fun loadPublicEmbed(address: ProfileAddress): ProfileSourcePage {
        val page = TikTokPublicEmbed.page(http.get("https://www.tiktok.com/embed/@${address.handle}"), "/embed/@${address.handle}")
            ?: throw ProfileDiscoveryException("Profile format changed and requires an app update.")
        val user = page.optJSONObject("userInfo") ?: throw ProfileDiscoveryException("Could not load public posts.")
        if (user.optBoolean("privateAccount")) throw ProfileDiscoveryException("Profile is private.")
        if (!user.has("privateAccount") || !user.text("uniqueId").equals(address.handle, true)) throw ProfileDiscoveryException("Could not verify this public profile.")
        val videos = page.optJSONArray("videoList") ?: throw ProfileDiscoveryException("Could not load public posts.")
        val posts = (0 until minOf(videos.length(), 100)).mapNotNull { index ->
            val item = videos.optJSONObject(index) ?: return@mapNotNull null
            if (item.optBoolean("privateItem")) return@mapNotNull null
            val id = item.text("id")?.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: return@mapNotNull null
            val owner = item.text("authorUniqueId")
            if (owner != null && !owner.equals(address.handle, true)) return@mapNotNull null
            CollectionItem(id, "https://www.tiktok.com/@${address.handle}/video/$id", item.text("desc"), "@${address.handle}", item.text("coverUrl"),
                item.optLong("duration").takeIf { it > 0 }, index, publishedAtSeconds = item.optLong("createTime").takeIf { it > 0 })
        }
        val next = if (posts.isNotEmpty() && allowGateway() && gateway != null) JSONObject().put("gateway", true).put("cursor", "0").toString() else null
        return ProfileSourcePage(profileInfo(address, user.text("nickname")), posts, next,
            "TikTok's public embed exposes a limited recent feed; no further public continuation is available.")
    }
    private suspend fun loadGateway(address: ProfileAddress, cursor: String): ProfileSourcePage {
        if (!allowGateway() || gateway == null) throw ProfileDiscoveryException("Third-party profile discovery is disabled in Settings.")
        require(cursor.length <= 256)
        val url = "https://www.tikwm.com/api/user/posts".toHttpUrl().newBuilder().addQueryParameter("unique_id", address.handle)
            .addQueryParameter("count", "30").addQueryParameter("cursor", cursor).build().toString()
        val json = JSONObject(gateway.get(url))
        if (json.optInt("code", -1) != 0) throw ProfileDiscoveryException("Could not load more public posts.")
        val data = json.optJSONObject("data") ?: throw ProfileDiscoveryException("Profile format changed and requires an app update.")
        val videos = data.optJSONArray("videos") ?: throw ProfileDiscoveryException("Could not load more public posts.")
        val posts = (0 until minOf(videos.length(), 100)).mapNotNull { index ->
            val video = videos.optJSONObject(index) ?: return@mapNotNull null
            val id = (video.text("video_id") ?: video.text("id"))?.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: return@mapNotNull null
            val author = video.optJSONObject("author")?.text("unique_id")
            if (author != null && !author.equals(address.handle, true)) return@mapNotNull null
            CollectionItem(id, "https://www.tiktok.com/@${address.handle}/video/$id", video.text("title"), "@${address.handle}", video.text("cover"), video.optLong("duration").takeIf { it > 0 }, index,
                publishedAtSeconds = video.optLong("create_time").takeIf { it > 0 })
        }
        val next = if (data.optBoolean("hasMore")) data.text("cursor")?.let { JSONObject().put("gateway", true).put("cursor", it).toString() } else null
        return ProfileSourcePage(profileInfo(address), posts, next, "TikWM profile fallback was used with your Settings opt-in. No origin cookies were sent.")
    }

}
