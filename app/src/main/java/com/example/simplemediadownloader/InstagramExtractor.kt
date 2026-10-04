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

object InstagramExtractor {
    fun extract(client: OkHttpClient, url: String): FormatDiscoveryResult {
        val shortcode = extractInstagramShortcode(url)

        // Tier 1: Modern SSR RelayPrefetchedStreamCache web extractor (Fastest, full HD, no auth)
        if (shortcode != null) {
            val relayCatalog = tryInstagramRelayCache(client, url, shortcode)
            if (relayCatalog != null && relayCatalog.videoFormats.isNotEmpty()) {
                return FormatDiscoveryResult.Success(relayCatalog)
            }

            // Tier 2: Public captioned embed endpoint
            val embedCatalog = tryInstagramEmbed(client, url, shortcode)
            if (embedCatalog != null && embedCatalog.videoFormats.isNotEmpty()) {
                return FormatDiscoveryResult.Success(embedCatalog)
            }
        }

        // Tier 3: Public crawler emulation (WhatsApp / Facebook bot impersonation)
        val crawlerCatalog = tryInstagramCrawler(client, url)
        if (crawlerCatalog != null && crawlerCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(crawlerCatalog)
        }

        return FormatDiscoveryResult.Failure(
            "Could not extract video from this Instagram link. The post may be private, expired, or restricted.",
        )
    }

    private fun tryInstagramRelayCache(client: OkHttpClient, url: String, shortcode: String): MediaFormatCatalog? {
        return try {
            val pageUrl = "https://www.instagram.com/p/$shortcode/"
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Mode", "navigate")
                .build()

            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.readBoundedString().orEmpty()
            }
            if (html.isBlank()) return null

            val marker = "RelayPrefetchedStreamCache"
            var markerIdx = html.indexOf(marker)
            var targetProduct: JSONObject? = null

            while (markerIdx != -1) {
                val scriptStart = html.lastIndexOf("<script", markerIdx)
                val contentStart = if (scriptStart != -1) html.indexOf('>', scriptStart) + 1 else -1
                val scriptEnd = html.indexOf("</script>", markerIdx)
                if (contentStart != -1 && scriptEnd != -1 && contentStart < scriptEnd) {
                    val jsonStr = html.substring(contentStart, scriptEnd).trim()
                    try {
                        val root = JSONObject(jsonStr)
                        val requireArr = root.optJSONArray("require")
                        if (requireArr != null) {
                            for (i in 0 until requireArr.length()) {
                                val subArr = requireArr.optJSONArray(i) ?: continue
                                for (j in 0 until subArr.length()) {
                                    val itemArr = subArr.optJSONArray(j) ?: continue
                                    for (k in 0 until itemArr.length()) {
                                        val itemObj = itemArr.optJSONObject(k) ?: continue
                                        val bbox = itemObj.optJSONObject("__bbox") ?: continue
                                        val innerRequire = bbox.optJSONArray("require") ?: continue
                                        for (r in 0 until innerRequire.length()) {
                                            val rCall = innerRequire.optJSONArray(r) ?: continue
                                            if (rCall.optString(0) == "RelayPrefetchedStreamCache") {
                                                val args = rCall.optJSONArray(3) ?: continue
                                                for (a in 0 until args.length()) {
                                                    val argObj = args.optJSONObject(a) ?: continue
                                                    val argBbox = argObj.optJSONObject("__bbox") ?: continue
                                                    val result = argBbox.optJSONObject("result") ?: continue
                                                    val data = result.optJSONObject("data") ?: continue
                                                    val polarisMedia = data.optJSONObject("xig_polaris_media") ?: continue
                                                    val product = polarisMedia.optJSONObject("if_not_gated_logged_out")
                                                    if (product != null && product.optJSONArray("video_versions") != null) {
                                                        targetProduct = product
                                                        break
                                                    }
                                                }
                                            }
                                            if (targetProduct != null) break
                                        }
                                        if (targetProduct != null) break
                                    }
                                    if (targetProduct != null) break
                                }
                                if (targetProduct != null) break
                            }
                        }
                    } catch (_: Exception) {}
                }
                if (targetProduct != null) break
                markerIdx = html.indexOf(marker, if (scriptEnd != -1) scriptEnd else markerIdx + 1)
            }

            if (targetProduct == null) return null

            val videoVersions = targetProduct.optJSONArray("video_versions") ?: return null
            if (videoVersions.length() == 0) return null

            val userObj = targetProduct.optJSONObject("user")
            val username = userObj?.optString("username").takeIf { !it.isNullOrBlank() }
            val fullName = userObj?.optString("full_name").takeIf { !it.isNullOrBlank() }
            val author = fullName ?: username ?: "Instagram Creator"

            val captionObj = targetProduct.optJSONObject("caption")
            val captionText = captionObj?.optString("text").takeIf { !it.isNullOrBlank() }
            val rawTitle = captionText
                ?: findMetaProperty(html, "og:title")
                ?: findMetaProperty(html, "twitter:title")
                ?: findMetaProperty(html, "og:description")
                ?: "Instagram Reel by $author"
            val title = cleanTitle(rawTitle)

            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://www.instagram.com/",
            )

            val videoFormats = mutableListOf<AvailableFormat>()
            var bestUrl: String? = null

            // Check for DASH manifest in Instagram reel
            val igDashRaw = targetProduct.optString("dash_manifest").takeIf { it.isNotBlank() }
                ?: targetProduct.optString("video_dash_manifest").takeIf { it.isNotBlank() }
                ?: extractPattern(html, """(?:video_)?dash_manifest["']:\s*"((?:[^"\\]|\\.)*)"""")

            val dashResult: Pair<List<AvailableFormat>, AvailableFormat?> = if (!igDashRaw.isNullOrBlank()) {
                FacebookExtractor.parseDashManifest(client, FacebookExtractor.unescapeDashManifest(igDashRaw), headers)
            } else {
                Pair(emptyList<AvailableFormat>(), null)
            }
            val igDashVideos = dashResult.first
            val igDashAudio = dashResult.second
            videoFormats.addAll(igDashVideos)

            for (i in 0 until videoVersions.length()) {
                val vObj = videoVersions.optJSONObject(i) ?: continue
                val rawUrl = vObj.optString("url")
                if (rawUrl.isBlank()) continue
                val cleanUrl = unescapeJsonUrl(rawUrl)
                if (bestUrl == null) bestUrl = cleanUrl

                val width = vObj.optInt("width", 0).takeIf { it > 0 }
                val height = vObj.optInt("height", 0).takeIf { it > 0 }
                val note = when {
                    height != null && height >= 1080 -> "1080p HD"
                    height != null && height >= 720 -> "720p HD"
                    height != null -> "${height}p"
                    i == 0 -> "HD Quality"
                    else -> "Standard Quality"
                }

                if (videoFormats.none { it.formatId == cleanUrl || (height != null && it.height == height && it.companionAudioFormatId == null) }) {
                    val streamSize = probeStreamSize(client, cleanUrl, headers)
                    videoFormats.add(
                        AvailableFormat(
                            key = "ig-relay-video-$i",
                            mode = DownloadMode.VIDEO,
                            formatId = cleanUrl,
                            extension = "mp4",
                            width = width ?: 0,
                            height = height ?: 1080,
                            formatNote = note,
                            estimatedSizeBytes = streamSize,
                            sizeIsApproximate = streamSize == null,
                            httpHeaders = headers,
                        )
                    )
                }
            }

            if (videoFormats.isEmpty() || (bestUrl == null && igDashVideos.isEmpty())) return null

            val primarySize = videoFormats.firstOrNull()?.estimatedSizeBytes
            val audioFormats = mutableListOf<AvailableFormat>()
            if (igDashAudio != null) {
                audioFormats.add(
                    igDashAudio.copy(
                        key = "ig-dash-audio",
                        mode = DownloadMode.AUDIO_ORIGINAL,
                        extension = igDashAudio.extension.ifBlank { "m4a" },
                        formatNote = "Original Audio (${igDashAudio.bitrateKbps} kbps)",
                    )
                )
            } else {
                val estimatedAudioBytes = primarySize?.let { (it * 0.08).toLong().coerceAtLeast(64 * 1024L) }
                val audioSourceUrl = bestUrl ?: videoFormats.first().formatId
                audioFormats.add(
                    AvailableFormat(
                        key = "ig-relay-audio",
                        mode = DownloadMode.AUDIO_ORIGINAL,
                        formatId = audioSourceUrl,
                        extension = "m4a",
                        formatNote = "Video source (audio extracted)",
                        estimatedSizeBytes = estimatedAudioBytes,
                        sizeIsApproximate = true,
                        httpHeaders = headers,
                    )
                )
            }

            val images = targetProduct.optJSONObject("image_versions2")?.optJSONArray("candidates")
            val thumbUrl = images?.optJSONObject(0)?.optString("url")
                ?: findMetaProperty(html, "og:image")
                ?: findMetaProperty(html, "twitter:image")

            val safeThumbUrl = thumbUrl?.let { unescapeJsonUrl(it) }?.takeIf {
                NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false)
            }

            MediaFormatCatalog(
                sourceUrl = url,
                title = title,
                durationSeconds = targetProduct.optDouble("video_duration", 0.0).toLong().takeIf { it > 0 },
                videoFormats = videoFormats,
                audioFormats = audioFormats,
                author = author,
                thumbnailUrl = safeThumbUrl,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun extractInstagramShortcode(url: String): String? {
        val pattern = Pattern.compile("""/(?:p|reels?|tv|share/(?:reel|p))/([A-Za-z0-9_-]+)""")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun tryInstagramEmbed(client: OkHttpClient, originalUrl: String, shortcode: String): MediaFormatCatalog? {
        return try {
            val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
            val request = Request.Builder()
                .url(embedUrl)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.readBoundedString().orEmpty()

                // Extract direct video URL from embed HTML
                val videoMatcher = Pattern.compile("""<video[^>]+src="([^"]+)"""").matcher(html)
                val jsonVideoMatcher = Pattern.compile(""""video_url"\s*:\s*"([^"]+)"""").matcher(html)

                val rawVideoUrl = when {
                    videoMatcher.find() -> videoMatcher.group(1)
                    jsonVideoMatcher.find() -> jsonVideoMatcher.group(1)
                    else -> null
                } ?: return null

                val cleanVideoUrl = unescapeJsonUrl(rawVideoUrl)
                if (!NetworkSecurityPolicy.isAllowedMediaUrl(cleanVideoUrl, allowCleartextHttp = false)) return null
                val rawCaption = extractPattern(html, """<div class="Caption"[\s\S]*?>([\s\S]*?)</div>""")
                    ?: extractPattern(html, """"caption"\s*:\s*"([^"]+)"""")
                    ?: "Instagram Video"
                val title = cleanTitle(rawCaption)

                val author = extractPattern(html, """class="UsernameText">([^<]+)<""")
                    ?: extractPattern(html, """"username"\s*:\s*"([^"]+)"""")

                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://www.instagram.com/",
                )

                val streamSize = probeStreamSize(client, cleanVideoUrl, headers)
                val videoFormats = listOf(
                    AvailableFormat(
                        key = "ig-embed-video",
                        mode = DownloadMode.VIDEO,
                        formatId = cleanVideoUrl,
                        extension = "mp4",
                        height = 1080,
                        formatNote = "Best Quality",
                        estimatedSizeBytes = streamSize,
                        sizeIsApproximate = streamSize == null,
                        httpHeaders = headers,
                    )
                )
                val estimatedAudioBytes = streamSize?.let { (it * 0.08).toLong().coerceAtLeast(64 * 1024L) }
                val audioFormats = listOf(
                    AvailableFormat(
                        key = "ig-embed-audio",
                        mode = DownloadMode.AUDIO_ORIGINAL,
                        formatId = cleanVideoUrl,
                        extension = "m4a",
                        formatNote = "Video source (audio extracted)",
                        estimatedSizeBytes = estimatedAudioBytes,
                        sizeIsApproximate = true,
                        httpHeaders = headers,
                    )
                )

                MediaFormatCatalog(
                    sourceUrl = originalUrl,
                    title = title,
                    videoFormats = videoFormats,
                    audioFormats = audioFormats,
                    author = author,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryInstagramCrawler(client: OkHttpClient, url: String): MediaFormatCatalog? {
        val crawlers = listOf(WHATSAPP_USER_AGENT, CRAWLER_USER_AGENT)
        for (ua in crawlers) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", ua)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val html = response.body?.readBoundedString().orEmpty()

                    val videoUrl = findMetaProperty(html, "og:video")
                        ?: findMetaProperty(html, "og:video:secure_url")
                        ?: extractPattern(html, """"video_url"\s*:\s*"([^"]+)"""")
                        ?: return@use

                    val cleanVideoUrl = unescapeJsonUrl(videoUrl)
                    if (!NetworkSecurityPolicy.isAllowedMediaUrl(cleanVideoUrl, allowCleartextHttp = false)) return@use
                    val rawTitle = findMetaProperty(html, "og:title")
                        ?: findMetaProperty(html, "og:description")
                        ?: "Instagram Video"
                    val title = cleanTitle(rawTitle)

                    val headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to "https://www.instagram.com/",
                    )

                    val streamSize = probeStreamSize(client, cleanVideoUrl, headers)
                    val videoFormats = listOf(
                        AvailableFormat(
                            key = "ig-crawler-video",
                            mode = DownloadMode.VIDEO,
                            formatId = cleanVideoUrl,
                            extension = "mp4",
                            height = 1080,
                            formatNote = "HD Quality",
                            estimatedSizeBytes = streamSize,
                            sizeIsApproximate = streamSize == null,
                            httpHeaders = headers,
                        )
                    )
                    val estimatedAudioBytes = streamSize?.let { (it * 0.08).toLong().coerceAtLeast(64 * 1024L) }
                    val audioFormats = listOf(
                        AvailableFormat(
                            key = "ig-crawler-audio",
                            mode = DownloadMode.AUDIO_ORIGINAL,
                            formatId = cleanVideoUrl,
                            extension = "m4a",
                            formatNote = "Video source (audio extracted)",
                            estimatedSizeBytes = estimatedAudioBytes,
                            sizeIsApproximate = true,
                            httpHeaders = headers,
                        )
                    )

                    return MediaFormatCatalog(
                        sourceUrl = url,
                        title = title,
                        videoFormats = videoFormats,
                        audioFormats = audioFormats,
                    )
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
