package com.example.simplemediadownloader

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import com.example.simplemediadownloader.ExtractorSharedUtils.*

object TwitterExtractor {
    fun extract(
        client: OkHttpClient,
        url: String,
        allowThirdPartyGateways: Boolean = false,
    ): FormatDiscoveryResult {
        val tweetId = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (tweetId.isBlank()) {
            return FormatDiscoveryResult.Failure("Invalid Twitter/X URL format.")
        }

        if (!allowThirdPartyGateways) {
            return FormatDiscoveryResult.Failure(
                "Direct extraction from Twitter/X requires third-party gateway fallback (FxTwitter/Fixupx). Enable third-party extractors in Settings.",
            )
        }

        // Tier 1: FxTwitter & Fixupx REST APIs
        val jsonEndpoints = listOf(
            "https://api.fxtwitter.com/i/status/$tweetId",
            "https://api.fixupx.com/i/status/$tweetId",
        )

        for (apiEndpoint in jsonEndpoints) {
            try {
                val request = Request.Builder()
                    .url(apiEndpoint)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Accept", "application/json")
                    .build()

                val jsonString = gatewayClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.readBoundedString().orEmpty()
                } ?: continue

                val rootJson = JSONObject(jsonString)
                val tweetObj = rootJson.optJSONObject("tweet") ?: rootJson

                val text = tweetObj.optString("text").ifBlank {
                    tweetObj.optString("raw_text", "X Post")
                }

                val authorObj = tweetObj.optJSONObject("author")
                val author = authorObj?.optString("name")
                    ?: tweetObj.optString("user_name").ifBlank { tweetObj.optString("user_screen_name") }

                data class VideoVariantCandidate(
                    val url: String,
                    val height: Int,
                    val bitrate: Long? = null,
                )

                val candidates = mutableListOf<VideoVariantCandidate>()

                fun extractHeightFromUrl(vUrl: String): Int? {
                    val resMatch = Pattern.compile("""/(\d+)x(\d+)/""").matcher(vUrl)
                    if (resMatch.find()) {
                        val w = resMatch.group(1)?.toIntOrNull() ?: 0
                        val h = resMatch.group(2)?.toIntOrNull() ?: 0
                        if (w > 0 && h > 0) return if (h > w) w else h
                    }
                    return null
                }

                fun addVideoCandidate(vUrl: String, rawHeight: Int, rawWidth: Int, bitrate: Long?) {
                    if (vUrl.isBlank() || !vUrl.contains(".mp4") || !NetworkSecurityPolicy.isAllowedMediaUrl(vUrl, allowCleartextHttp = false)) return
                    if (candidates.any { it.url == vUrl }) return
                    var h = if (rawHeight > 0 && rawWidth > 0 && rawHeight > rawWidth) rawWidth else rawHeight
                    if (h <= 0) {
                        h = extractHeightFromUrl(vUrl) ?: 0
                    }
                    if (h <= 0 && bitrate != null && bitrate > 0) {
                        h = when {
                            bitrate >= 2_000_000 -> 1080
                            bitrate >= 800_000 -> 720
                            bitrate >= 400_000 -> 480
                            else -> 360
                        }
                    }
                    if (h <= 0) h = 720
                    candidates.add(VideoVariantCandidate(vUrl, h, bitrate))
                }

                val mediaObj = tweetObj.optJSONObject("media")
                val videosArray = mediaObj?.optJSONArray("videos")
                    ?: tweetObj.optJSONArray("media_extended")
                    ?: mediaObj?.optJSONArray("all")

                if (videosArray != null) {
                    for (i in 0 until videosArray.length()) {
                        val item = videosArray.optJSONObject(i) ?: continue
                        val itemVariants = item.optJSONArray("variants")
                            ?: item.optJSONObject("video_info")?.optJSONArray("variants")
                        if (itemVariants != null) {
                            for (v in 0 until itemVariants.length()) {
                                val variant = itemVariants.optJSONObject(v) ?: continue
                                val vUrl = variant.optString("url")
                                val bitrate = variant.optLong("bitrate", 0).takeIf { it > 0 }
                                addVideoCandidate(vUrl, 0, 0, bitrate)
                            }
                        }
                        val directUrl = item.optString("url")
                        val h = item.optInt("height", 0)
                        val w = item.optInt("width", 0)
                        if (directUrl.isNotBlank()) {
                            addVideoCandidate(directUrl, h, w, null)
                        }
                    }
                }

                val directMedia = tweetObj.optString("media_url")
                if (tweetObj.optString("mediaType") == "video" && directMedia.isNotBlank()) {
                    addVideoCandidate(directMedia, 0, 0, null)
                }

                if (candidates.isNotEmpty()) {
                    val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://twitter.com/")
                    val grouped = candidates.groupBy { it.height }
                    val sortedCandidates = grouped.values.map { list ->
                        list.maxByOrNull { it.bitrate ?: 0L } ?: list.first()
                    }.sortedByDescending { it.height }

                    val videoFormats = mutableListOf<AvailableFormat>()
                    for (cand in sortedCandidates) {
                        val streamSize = probeStreamSize(client, cand.url, headers)
                        videoFormats.add(
                            AvailableFormat(
                                key = "tw-video-${cand.height}",
                                mode = DownloadMode.VIDEO,
                                formatId = cand.url,
                                extension = "mp4",
                                height = cand.height,
                                formatNote = "${cand.height}p MP4 Video",
                                estimatedSizeBytes = streamSize,
                                sizeIsApproximate = streamSize == null,
                                isQuickPreset = false,
                                httpHeaders = headers,
                            )
                        )
                    }

                    val primaryVideo = videoFormats.firstOrNull()
                    val primarySize = primaryVideo?.estimatedSizeBytes
                    val estimatedAudioBytes = primarySize?.let { (it * 0.08).toLong().coerceAtLeast(64 * 1024L) }
                    val audioFormats = listOf(
                        AvailableFormat(
                            key = "tw-audio",
                            mode = DownloadMode.AUDIO_ORIGINAL,
                            formatId = primaryVideo?.formatId ?: candidates.first().url,
                            extension = "m4a",
                            formatNote = "Video source (audio extracted)",
                            estimatedSizeBytes = estimatedAudioBytes,
                            sizeIsApproximate = true,
                            isQuickPreset = false,
                            httpHeaders = headers,
                        )
                    )

                    return FormatDiscoveryResult.Success(
                        MediaFormatCatalog(
                            sourceUrl = url,
                            title = cleanTitle(text).take(100),
                            videoFormats = videoFormats,
                            audioFormats = audioFormats,
                            author = author.takeIf { it.isNotBlank() },
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        // Tier 2: OpenGraph crawler emulation on fxtwitter / fixupx
        val crawlerEndpoints = listOf(
            "https://fxtwitter.com/i/status/$tweetId",
            "https://fixupx.com/status/$tweetId",
        )
        for (crawlerUrl in crawlerEndpoints) {
            try {
                val request = Request.Builder()
                    .url(crawlerUrl)
                    .addHeader("User-Agent", CRAWLER_USER_AGENT)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                val catalog = gatewayClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val html = response.body?.readBoundedString().orEmpty()

                    val rawVideoUrl = findMetaProperty(html, "og:video")
                        ?: findMetaProperty(html, "og:video:url")
                        ?: findMetaProperty(html, "og:video:secure_url")
                        ?: findMetaProperty(html, "twitter:player:stream")
                        ?: return@use null

                    val videoUrl = rawVideoUrl.takeIf {
                        NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false)
                    } ?: return@use null

                    val title = findMetaProperty(html, "og:title")
                        ?: findMetaProperty(html, "og:description")
                        ?: "X Post"

                    val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://twitter.com/")
                    val streamSize = probeStreamSize(client, videoUrl, headers)
                    val estimatedAudioBytes = streamSize?.let { (it * 0.08).toLong().coerceAtLeast(64 * 1024L) }
                    val videoFormats = listOf(
                        AvailableFormat(
                            key = "tw-og-video",
                            mode = DownloadMode.VIDEO,
                            formatId = videoUrl,
                            extension = "mp4",
                            height = 1080,
                            formatNote = "MP4 Video",
                            estimatedSizeBytes = streamSize,
                            sizeIsApproximate = streamSize == null,
                            isQuickPreset = false,
                            httpHeaders = headers,
                        )
                    )
                    val audioFormats = listOf(
                        AvailableFormat(
                            key = "tw-og-audio",
                            mode = DownloadMode.AUDIO_ORIGINAL,
                            formatId = videoUrl,
                            extension = "m4a",
                            formatNote = "Video source (audio extracted)",
                            estimatedSizeBytes = estimatedAudioBytes,
                            sizeIsApproximate = true,
                            isQuickPreset = false,
                            httpHeaders = headers,
                        )
                    )
                    MediaFormatCatalog(
                        sourceUrl = url,
                        title = cleanTitle(title).take(100),
                        videoFormats = videoFormats,
                        audioFormats = audioFormats,
                    )
                }
                if (catalog != null) {
                    return FormatDiscoveryResult.Success(catalog)
                }
            } catch (_: Exception) {}
        }

        return FormatDiscoveryResult.Failure("No downloadable video found in this Tweet/Post.")
    }
}
