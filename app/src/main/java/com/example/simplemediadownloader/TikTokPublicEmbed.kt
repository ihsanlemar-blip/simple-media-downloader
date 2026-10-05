package com.example.simplemediadownloader

import org.json.JSONObject

/** TikTok-owned public embed payload, shared by metadata discovery and the single-post adapter. */
internal object TikTokPublicEmbed {
    fun page(html: String, path: String): JSONObject? {
        val raw = extractJsonFromHtmlTag(html, "id=\"__FRONTITY_CONNECT_STATE__\"") ?: return null
        return JSONObject(raw).optJSONObject("source")?.optJSONObject("data")?.optJSONObject(path)
    }
    fun videoId(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.host?.lowercase() !in setOf("www.tiktok.com", "tiktok.com", "m.tiktok.com")) return null
        return Regex("/video/([0-9]{1,30})(?:/|$)").find(uri.path.orEmpty())?.groupValues?.get(1)
    }
    fun catalog(html: String, sourceUrl: String, id: String): MediaFormatCatalog? {
        val page = page(html, "/embed/v2/$id") ?: return null
        if (page.optInt("code") != 200 || page.optBoolean("isError")) return null
        val data = page.optJSONObject("videoData") ?: return null
        val item = data.optJSONObject("itemInfos") ?: return null
        val author = data.optJSONObject("authorInfos")
        if (item.text("id") != id || item.optBoolean("secret") || item.optBoolean("forFriend") || author?.optBoolean("isSecret") == true) return null
        val video = item.optJSONObject("video") ?: return null
        val urls = video.optJSONArray("urls") ?: return null
        val meta = video.optJSONObject("videoMeta")
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://www.tiktok.com/")
        val duration = meta?.optLong("duration")?.takeIf { it > 0 }
        val formats = (0 until minOf(urls.length(), 4)).mapNotNull { index ->
            val stream = urls.optString(index).takeIf { NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false) } ?: return@mapNotNull null
            AvailableFormat("tiktok-embed-$index", DownloadMode.VIDEO, stream, extension = "mp4", height = meta?.optInt("height") ?: 0,
                width = meta?.optInt("width") ?: 0, formatNote = "Public TikTok source", httpHeaders = headers, durationSeconds = duration)
        }.distinctBy { it.formatId }
        if (formats.isEmpty()) return null
        // Preserve the post's audio track; music metadata can represent a different, longer soundtrack.
        val audio = formats.first().copy(key = "tiktok-embed-audio", mode = DownloadMode.AUDIO_ORIGINAL, extension = "m4a", sourceExtension = "mp4",
            formatNote = "Video source (audio extracted)")
        return MediaFormatCatalog(sourceUrl, cleanTitle(item.text("text") ?: "TikTok Video"), formats, listOf(audio),
            author = author?.text("nickName") ?: author?.text("uniqueId"), durationSeconds = duration,
            thumbnailUrl = item.optJSONArray("covers")?.optString(0)?.takeIf { NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false) })
    }
}
