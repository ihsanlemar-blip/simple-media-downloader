package com.example.simplemediadownloader

import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import org.json.JSONArray
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import java.util.Base64
import java.util.Locale

/** A fixture seam at the NewPipe boundary, not a media downloader. */
internal data class PlaylistSourcePage(val items: List<StreamInfoItem>, val next: Page?, val info: CollectionInfo? = null)
internal interface PlaylistSource {
    fun initial(url: String): PlaylistSourcePage
    fun more(url: String, page: Page): PlaylistSourcePage
}
private object NewPipePlaylistSource : PlaylistSource {
    override fun initial(url: String): PlaylistSourcePage {
        val info = PlaylistInfo.getInfo(url)
        return PlaylistSourcePage(info.relatedItems, info.nextPage,
            CollectionInfo(url, "YouTube", CollectionType.YOUTUBE_PLAYLIST, info.name.takeIf { it.isNotBlank() },
                info.streamCount.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
                info.uploaderName?.takeIf { it.isNotBlank() }, info.thumbnails.firstOrNull()?.url))
    }
    override fun more(url: String, page: Page): PlaylistSourcePage {
        val result = PlaylistInfo.getMoreItems(NewPipe.getServiceByUrl(url), url, page)
        return PlaylistSourcePage(result.items, result.nextPage)
    }
}

/** Uses NewPipe playlist support and the existing secured downloader; never downloads media. */
class YouTubePlaylistExtractorAdapter internal constructor(
    private val dispatchers: AppDispatchers,
    private val source: PlaylistSource,
) : CollectionExtractor {
    constructor(dispatchers: AppDispatchers = AppDispatchers()) : this(dispatchers, NewPipePlaylistSource)
    override suspend fun canHandle(url: String) = SourceUrlClassifier.classify(url) == SourceUrlType.YOUTUBE_PLAYLIST
    override suspend fun getInfo(url: String): CollectionInfo = runInterruptible(dispatchers.io) {
        val canonical = SourceUrlClassifier.playlistUrl(url)
        source.initial(canonical).info ?: CollectionInfo(canonical, "YouTube", CollectionType.YOUTUBE_PLAYLIST, null)
    }
    override suspend fun getItems(url: String, limit: Int?, continuation: String?): CollectionPage = runInterruptible(dispatchers.io) {
        val canonical = SourceUrlClassifier.playlistUrl(url)
        val count = (limit ?: 100).also { require(it in 1..100) }
        require(continuation == null || continuation.length <= 262_144) { "Collection continuation is too large" }
        val token = continuation?.let(::JSONObject)
        val offset = token?.optInt("offset", 0) ?: 0
        val position = token?.optInt("position", 0) ?: 0
        val encodedPage = token?.optJSONObject("page")
        val result = if (encodedPage == null) source.initial(canonical) else {
            val page = decodePage(encodedPage)
            // Continuations still use network policy and NewPipe redirect validation.
            page.url?.takeIf { it.isNotBlank() }?.let { require(NetworkSecurityPolicy.isAllowedShareUrl(it)) }
            source.more(canonical, page)
        }
        val items = result.items
        val next = result.next
        require(offset in 0..items.size && position >= 0 && position <= Int.MAX_VALUE - items.size) { "Invalid collection continuation" }
        val chosen = items.subList(offset, minOf(items.size, offset + count))
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
            val itemPosition = position + index
            val reason = unavailableReason(item)
            CollectionItem("${NormalizedMediaUrl.from(item.url.orEmpty())}:$itemPosition", item.url.orEmpty(), item.name,
                item.uploaderName, item.thumbnails.firstOrNull()?.url, item.duration.takeIf { it > 0 }, itemPosition, reason)
        }, nextToken, nextToken != null)
    }
    private fun unavailableReason(item: StreamInfoItem): String? = when {
        !NetworkSecurityPolicy.isAllowedShareUrl(item.url.orEmpty()) -> "Unsupported media URL"
        item.name?.trim()?.lowercase(Locale.ROOT) in setOf("[private video]", "private video") -> "Private video"
        item.name?.trim()?.lowercase(Locale.ROOT) in setOf("[deleted video]", "deleted video", "[removed video]", "removed video") -> "Removed video"
        item.name?.trim()?.lowercase(Locale.ROOT) in setOf("[unavailable video]", "unavailable video") -> "Unavailable video"
        item.contentAvailability == ContentAvailability.MEMBERSHIP -> "Members-only video"
        item.contentAvailability == ContentAvailability.PAID -> "Paid video"
        item.contentAvailability == ContentAvailability.UPCOMING -> "Upcoming video"
        item.streamType == StreamType.NONE -> "Unavailable video"
        else -> null // Unknown availability is resolved by the normal per-child extractor.
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
        ?: throw ProfileDiscoveryException("Collection discovery is not available for this platform yet. Open an individual media link instead.")
}
