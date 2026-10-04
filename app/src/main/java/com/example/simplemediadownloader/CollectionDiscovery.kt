package com.example.simplemediadownloader

import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Base64

/** Uses the existing NewPipe downloader, SafeDns, redirect checks and scoped cookies. */
class YouTubeCollectionExtractor(private val dispatchers: AppDispatchers = AppDispatchers()) : CollectionExtractor {
    override suspend fun canHandle(url: String) = SourceUrlClassifier.classify(url) == SourceUrlType.YOUTUBE_PLAYLIST
    override suspend fun getInfo(url: String): CollectionInfo = withContext(dispatchers.io) {
        check(canHandle(url)) { "Unsupported collection URL" }
        val info = PlaylistInfo.getInfo(url)
        CollectionInfo(url, "YouTube", CollectionType.YOUTUBE_PLAYLIST, info.name, info.streamCount.takeIf { it in 1..Int.MAX_VALUE }?.toInt())
    }
    override suspend fun getItems(url: String, limit: Int?, continuation: String?): CollectionPage = withContext(dispatchers.io) {
        check(canHandle(url)) { "Unsupported collection URL" }
        val count = (limit ?: 100).also { require(it in 1..100) }
        require(continuation == null || continuation.length <= 262_144) { "Collection continuation is too large" }
        val token = continuation?.let(::JSONObject)
        val offset = token?.optInt("offset", 0) ?: 0
        val position = token?.optInt("position", 0) ?: 0
        val encodedPage = token?.optJSONObject("page")
        val items: List<StreamInfoItem>
        val next: Page?
        if (encodedPage == null) {
            val info = PlaylistInfo.getInfo(url)
            items = info.relatedItems
            next = info.nextPage
        } else {
            val page = decodePage(encodedPage)
            // Continuations cannot bypass the network policy; NewPipe's downloader also validates redirects.
            page.url?.takeIf { it.isNotBlank() }?.let { require(NetworkSecurityPolicy.isAllowedShareUrl(it)) }
            val result = PlaylistInfo.getMoreItems(NewPipe.getServiceByUrl(url), url, page)
            items = result.items
            next = result.nextPage
        }
        require(offset in 0..items.size && position >= 0) { "Invalid collection continuation" }
        val chosen = items.drop(offset).take(count)
        val following = when {
            offset + chosen.size < items.size -> JSONObject().apply {
                put("offset", offset + chosen.size); put("position", position + chosen.size)
                encodedPage?.let { put("page", it) }
            }
            Page.isValid(next) -> JSONObject().apply {
                put("offset", 0); put("position", position + chosen.size); put("page", encodePage(next!!))
            }
            else -> null
        }
        val nextToken = following?.toString()?.also { require(it.length <= 262_144) }
        CollectionPage(chosen.mapIndexed { index, item ->
            CollectionItem(NormalizedMediaUrl.from(item.url) + ":" + (position + index), item.url, item.name,
                item.uploaderName, item.thumbnails.firstOrNull()?.url, item.duration.takeIf { it > 0 }, position + index)
        }, nextToken, nextToken != null)
    }
    private fun encodePage(page: Page) = JSONObject().apply {
        check(page.cookies.isNullOrEmpty()) { "Authenticated collection continuations are unsupported" }
        page.url?.let { put("url", it) }; page.id?.let { put("id", it) }
        page.ids?.let { put("ids", JSONArray(it)) }
        page.body?.let { put("body", Base64.getEncoder().encodeToString(it)) }
    }
    private fun decodePage(json: JSONObject) = Page(
        json.optString("url").takeIf(String::isNotBlank), json.optString("id").takeIf(String::isNotBlank),
        json.optJSONArray("ids")?.let { ids -> List(ids.length()) { ids.getString(it) } }, emptyMap(),
        json.optString("body").takeIf(String::isNotBlank)?.let { Base64.getDecoder().decode(it) },
    )
}

class CollectionExtractorRegistry(private val extractors: List<CollectionExtractor>) {
    suspend fun extractor(url: String): CollectionExtractor = extractors.firstOrNull { it.canHandle(url) }
        ?: error("Collection discovery is not available for this platform yet. Open an individual media link instead.")
}
