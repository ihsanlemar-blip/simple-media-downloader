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

object TikTokExtractor {
    fun extract(
        client: OkHttpClient,
        url: String,
        allowThirdPartyGateways: Boolean = false,
    ): FormatDiscoveryResult {
        // Tier 1: Universal SSR rehydration and mobile web scraper with session cookie preservation
        val webCatalog = tryTikTokWebScrape(client, url)
        if (webCatalog != null && webCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(webCatalog)
        }

        if (allowThirdPartyGateways) {
            // Tier 2: TikWM Public High-Speed API (Watermark-free HD, MP3 audio, full metadata)
            val tikWmCatalog = tryTikWmApi(client, url)
            if (tikWmCatalog != null && tikWmCatalog.videoFormats.isNotEmpty()) {
                return FormatDiscoveryResult.Success(tikWmCatalog)
            }
        }

        return FormatDiscoveryResult.Failure(
            if (!allowThirdPartyGateways) {
                "Could not extract video stream directly from TikTok. Third-party fallback is disabled in settings."
            } else {
                "Could not extract video stream from this TikTok link. The post may be private or removed."
            },
        )
    }

    private fun tryTikWmApi(client: OkHttpClient, url: String): MediaFormatCatalog? {
        return try {
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val apiUrl = "https://www.tikwm.com/api/?url=$encodedUrl&hd=1"

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/json")
                .build()

            gatewayClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.readBoundedString().orEmpty()
                if (bodyStr.isBlank()) return null

                val json = JSONObject(bodyStr)
                if (json.optInt("code", -1) != 0) return null

                val data = json.optJSONObject("data") ?: return null
                val rawTitle = data.optString("title").ifBlank { "TikTok Video" }
                val title = cleanTitle(rawTitle)
                val authorObj = data.optJSONObject("author")
                val author = authorObj?.optString("nickname")
                    ?: authorObj?.optString("unique_id")
                    ?: "TikTok Creator"
                val cover = data.optString("cover").takeIf {
                    it.isNotBlank() && NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false)
                }

                val playUrl = data.optString("play").takeIf {
                    it.isNotBlank() && NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false)
                }
                val hdUrl = data.optString("hdplay").takeIf {
                    it.isNotBlank() && NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false)
                }
                val musicUrl = data.optString("music").takeIf {
                    it.isNotBlank() && NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false)
                }
                val size = data.optLong("size", 0L).takeIf { it > 0 }
                val hdSize = data.optLong("hd_size", 0L).takeIf { it > 0 }

                if (playUrl.isNullOrBlank() && hdUrl.isNullOrBlank()) return null

                val standardStream = playUrl ?: hdUrl!!
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://www.tiktok.com/",
                )

                val resolvedHdSize = hdSize ?: hdUrl?.let { probeStreamSize(client, it, headers) }
                val resolvedSize = size ?: probeStreamSize(client, standardStream, headers)
                val musicSize = musicUrl?.let { probeStreamSize(client, it, headers) }

                val videoFormats = mutableListOf<AvailableFormat>()
                if (!hdUrl.isNullOrBlank()) {
                    videoFormats.add(
                        AvailableFormat(
                            key = "tiktok-hd",
                            mode = DownloadMode.VIDEO,
                            formatId = hdUrl,
                            extension = "mp4",
                            height = 1080,
                            formatNote = "HD No Watermark",
                            estimatedSizeBytes = resolvedHdSize ?: (resolvedSize?.times(2)),
                            sizeIsApproximate = resolvedHdSize == null,
                            httpHeaders = headers,
                        )
                    )
                }

                videoFormats.add(
                    AvailableFormat(
                        key = "tiktok-watermark-free",
                        mode = DownloadMode.VIDEO,
                        formatId = standardStream,
                        extension = "mp4",
                        height = 720,
                        formatNote = "Watermark-Free",
                        estimatedSizeBytes = resolvedSize,
                        sizeIsApproximate = resolvedSize == null,
                        httpHeaders = headers,
                    )
                )

                val audioFormats = mutableListOf<AvailableFormat>()
                if (!musicUrl.isNullOrBlank()) {
                    val isMp3 = musicUrl.contains(".mp3", ignoreCase = true)
                    audioFormats.add(
                        AvailableFormat(
                            key = "tiktok-music",
                            mode = if (isMp3) DownloadMode.AUDIO_MP3 else DownloadMode.AUDIO_ORIGINAL,
                            formatId = musicUrl,
                            extension = if (isMp3) "mp3" else "m4a",
                            bitrateKbps = 128,
                            formatNote = if (isMp3) "Original Soundtrack (MP3)" else "Original Soundtrack",
                            estimatedSizeBytes = musicSize,
                            sizeIsApproximate = musicSize == null,
                            httpHeaders = headers,
                        )
                    )
                } else if (standardStream.isNotBlank()) {
                    val estimatedAudioBytes = resolvedSize?.let { (it * 0.08).toLong().coerceAtLeast(64 * 1024L) }
                    audioFormats.add(
                        AvailableFormat(
                            key = "tiktok-audio-extract",
                            mode = DownloadMode.AUDIO_ORIGINAL,
                            formatId = standardStream,
                            extension = "m4a",
                            formatNote = "Video source (audio extracted)",
                            estimatedSizeBytes = estimatedAudioBytes,
                            sizeIsApproximate = true,
                            httpHeaders = headers,
                        )
                    )
                }

                MediaFormatCatalog(
                    sourceUrl = url,
                    title = title,
                    videoFormats = videoFormats,
                    audioFormats = audioFormats,
                    author = author,
                    thumbnailUrl = cover,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryTikTokWebScrape(client: OkHttpClient, url: String): MediaFormatCatalog? {
        return try {
            val cookieJar = ScopedCookieJar()
            val cookieClient = client.newBuilder()
                .cookieJar(cookieJar)
                .dns(SafeDns())
                .addInterceptor(SecurityInterceptor(allowCleartextHttp = true))
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val html: String
            var finalUrl = url
            cookieClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                finalUrl = response.request.url.toString()
                html = response.body?.readBoundedString().orEmpty()
            }
            if (html.isBlank()) return null

            fun buildStreamHeaders(streamUrl: String): Map<String, String> {
                val streamHeaders = mutableMapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://www.tiktok.com/",
                )
                val cookies = cookieJar.getCookiesForUrl(streamUrl)
                if (cookies.isNotBlank()) {
                    streamHeaders["Cookie"] = cookies
                }
                return streamHeaders
            }

            var directVideoUrl: String? = null
            var directAudioUrl: String? = null
            var itemTitle: String? = null
            var itemAuthor: String? = null
            var itemCover: String? = null
            var videoHeight: Int? = null
            var matchedVideoObj: JSONObject? = null

            val rehydrationJson = extractJsonFromHtmlTag(html, "id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"")
                ?: extractJsonFromHtmlTag(html, "id=\"SIGI_STATE\"")
                ?: extractJsonFromHtmlTag(html, "id=\"sigi-persisted-data\"")

            if (!rehydrationJson.isNullOrBlank()) {
                try {
                    val rootJson = JSONObject(rehydrationJson)
                    val scope = rootJson.optJSONObject("__DEFAULT_SCOPE__")
                    if (scope != null) {
                        val keys = scope.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val section = scope.optJSONObject(key) ?: continue
                            val itemStruct = section.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
                                ?: section.optJSONObject("itemStruct")
                            if (itemStruct != null) {
                                val videoObj = itemStruct.optJSONObject("video")
                                matchedVideoObj = videoObj
                                directVideoUrl = videoObj?.optString("playAddr")?.takeIf { it.isNotBlank() }
                                    ?: videoObj?.optString("downloadAddr")?.takeIf { it.isNotBlank() }
                                itemCover = videoObj?.optString("cover")?.takeIf { it.isNotBlank() }
                                    ?: videoObj?.optString("originCover")?.takeIf { it.isNotBlank() }
                                itemTitle = itemStruct.optString("desc").takeIf { it.isNotBlank() }
                                val authorObj = itemStruct.optJSONObject("author")
                                itemAuthor = authorObj?.optString("nickname")?.takeIf { it.isNotBlank() }
                                    ?: authorObj?.optString("uniqueId")?.takeIf { it.isNotBlank() }
                                val musicObj = itemStruct.optJSONObject("music")
                                directAudioUrl = musicObj?.optString("playUrl")?.takeIf { it.isNotBlank() }
                                val h = videoObj?.optInt("height", 0) ?: 0
                                if (h > 0) videoHeight = h
                                break
                            }
                        }
                    }
                    if (directVideoUrl.isNullOrBlank()) {
                        val itemModule = rootJson.optJSONObject("ItemModule")
                        if (itemModule != null) {
                            val keys = itemModule.keys()
                            while (keys.hasNext()) {
                                val item = itemModule.optJSONObject(keys.next()) ?: continue
                                val videoObj = item.optJSONObject("video")
                                matchedVideoObj = videoObj
                                directVideoUrl = videoObj?.optString("playAddr")?.takeIf { it.isNotBlank() }
                                    ?: videoObj?.optString("downloadAddr")?.takeIf { it.isNotBlank() }
                                itemCover = videoObj?.optString("cover")?.takeIf { it.isNotBlank() }
                                itemTitle = item.optString("desc").takeIf { it.isNotBlank() }
                                itemAuthor = item.optString("author").takeIf { it.isNotBlank() }
                                val musicObj = item.optJSONObject("music")
                                directAudioUrl = musicObj?.optString("playUrl")?.takeIf { it.isNotBlank() }
                                val h = videoObj?.optInt("height", 0) ?: 0
                                if (h > 0) videoHeight = h
                                if (directVideoUrl != null) break
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (directVideoUrl.isNullOrBlank()) {
                directVideoUrl = findMetaProperty(html, "og:video")
                    ?: findMetaProperty(html, "og:video:secure_url")
                    ?: extractPattern(html, """"playAddr"\s*:\s*"([^"]+)"""")
                    ?: extractPattern(html, """"downloadAddr"\s*:\s*"([^"]+)"""")
            }

            if (directVideoUrl.isNullOrBlank()) return null

            val cleanVideoUrl = unescapeJsonUrl(directVideoUrl)

            if (itemTitle.isNullOrBlank()) {
                itemTitle = findMetaProperty(html, "og:title")
                    ?: extractPattern(html, """<title>([^<]+)</title>""")
            }

            // If title is missing or generic, fetch official TikTok oEmbed metadata
            if (itemTitle.isNullOrBlank() || (itemTitle.contains("TikTok") && itemTitle.length < 15)) {
                val oembed = fetchTikTokOEmbed(client, finalUrl)
                if (!oembed.first.isNullOrBlank()) itemTitle = oembed.first
                if (!oembed.second.isNullOrBlank() && itemAuthor.isNullOrBlank()) itemAuthor = oembed.second
            }

            val title = cleanTitle(itemTitle ?: "TikTok Video")

            val videoFormats = mutableListOf<AvailableFormat>()
            val seenUrls = mutableSetOf<String>()

            val dlAddr = matchedVideoObj?.optString("downloadAddr")?.takeIf { it.isNotBlank() }
            if (!dlAddr.isNullOrBlank()) {
                val cleanDl = unescapeJsonUrl(dlAddr)
                if (NetworkSecurityPolicy.isAllowedMediaUrl(cleanDl, allowCleartextHttp = false)) {
                    val streamHeaders = buildStreamHeaders(cleanDl)
                    val dlSize = probeStreamSize(client, cleanDl, streamHeaders)
                    seenUrls.add(cleanDl)
                    videoFormats.add(
                        AvailableFormat(
                            key = "tiktok-web-hd",
                            mode = DownloadMode.VIDEO,
                            formatId = cleanDl,
                            extension = "mp4",
                            height = 1080,
                            formatNote = "Full HD",
                            estimatedSizeBytes = dlSize,
                            sizeIsApproximate = dlSize == null,
                            isQuickPreset = false,
                            httpHeaders = streamHeaders,
                        )
                    )
                }
            }

            val bitrateInfo = matchedVideoObj?.optJSONArray("bitrateInfo")
            if (bitrateInfo != null) {
                for (i in 0 until bitrateInfo.length()) {
                    val bi = bitrateInfo.optJSONObject(i) ?: continue
                    val gear = bi.optString("GearName")
                    val pa = bi.optJSONObject("PlayAddr") ?: continue
                    val urls = pa.optJSONArray("UrlList")
                    val rawStreamUrl = urls?.optString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val cleanStreamUrl = unescapeJsonUrl(rawStreamUrl)
                    if (!NetworkSecurityPolicy.isAllowedMediaUrl(cleanStreamUrl, allowCleartextHttp = false)) continue
                    if (!seenUrls.add(cleanStreamUrl)) continue

                    val streamHeaders = buildStreamHeaders(cleanStreamUrl)
                    val streamSize = pa.optLong("DataSize", 0L).takeIf { it > 0 }
                        ?: probeStreamSize(client, cleanStreamUrl, streamHeaders)
                    val h = when {
                        gear.contains("1080") -> 1080
                        gear.contains("720") -> 720
                        gear.contains("540") -> 540
                        gear.contains("480") -> 480
                        gear.contains("360") -> 360
                        else -> videoHeight ?: 540
                    }
                    val isSaver = gear.contains("adapt_540") || (h <= 540 && gear.contains("adapt")) || h <= 480
                    val note = when {
                        isSaver -> "Data Saver (${h}p)"
                        gear.contains("720") -> "Standard HD (720p)"
                        gear.contains("540") -> "Standard (540p)"
                        else -> "${h}p"
                    }

                    videoFormats.add(
                        AvailableFormat(
                            key = "tiktok-web-$gear",
                            mode = DownloadMode.VIDEO,
                            formatId = cleanStreamUrl,
                            extension = "mp4",
                            height = h,
                            formatNote = note,
                            estimatedSizeBytes = streamSize,
                            sizeIsApproximate = streamSize == null,
                            isQuickPreset = false,
                            httpHeaders = streamHeaders,
                        )
                    )
                }
            }

            if (videoFormats.isEmpty() && !cleanVideoUrl.isNullOrBlank()) {
                if (NetworkSecurityPolicy.isAllowedMediaUrl(cleanVideoUrl, allowCleartextHttp = false)) {
                    val streamHeaders = buildStreamHeaders(cleanVideoUrl)
                    val videoSize = probeStreamSize(client, cleanVideoUrl, streamHeaders)
                    videoFormats.add(
                        AvailableFormat(
                            key = "tiktok-web-hd",
                            mode = DownloadMode.VIDEO,
                            formatId = cleanVideoUrl,
                            extension = "mp4",
                            height = videoHeight ?: 1080,
                            formatNote = "HD Video",
                            estimatedSizeBytes = videoSize,
                            sizeIsApproximate = videoSize == null,
                            isQuickPreset = false,
                            httpHeaders = streamHeaders,
                        )
                    )
                }
            }

            videoFormats.sortWith(
                compareByDescending<AvailableFormat> { it.height }
                    .thenByDescending { it.estimatedSizeBytes ?: 0L }
            )

            val audioFormats = mutableListOf<AvailableFormat>()
            if (!directAudioUrl.isNullOrBlank()) {
                val cleanAudioUrl = unescapeJsonUrl(directAudioUrl)
                if (NetworkSecurityPolicy.isAllowedMediaUrl(cleanAudioUrl, allowCleartextHttp = false)) {
                    val streamHeaders = buildStreamHeaders(cleanAudioUrl)
                    val audioSize = probeStreamSize(client, cleanAudioUrl, streamHeaders)
                    val isMp3 = cleanAudioUrl.contains(".mp3", ignoreCase = true)
                    audioFormats.add(
                        AvailableFormat(
                            key = "tiktok-web-music",
                            mode = if (isMp3) DownloadMode.AUDIO_MP3 else DownloadMode.AUDIO_ORIGINAL,
                            formatId = cleanAudioUrl,
                            extension = if (isMp3) "mp3" else "m4a",
                            bitrateKbps = 128,
                            formatNote = if (isMp3) "Original Soundtrack (MP3)" else "Original Soundtrack",
                            estimatedSizeBytes = audioSize,
                            sizeIsApproximate = audioSize == null,
                            isQuickPreset = false,
                            httpHeaders = streamHeaders,
                        )
                    )
                }
            }
            if (audioFormats.isEmpty() && !cleanVideoUrl.isNullOrBlank()) {
                if (NetworkSecurityPolicy.isAllowedMediaUrl(cleanVideoUrl, allowCleartextHttp = false)) {
                    val streamHeaders = buildStreamHeaders(cleanVideoUrl)
                    val videoFallbackSize = videoFormats.firstOrNull()?.estimatedSizeBytes
                    val estimatedAudioBytes = videoFallbackSize?.let { (it * 0.08).toLong().coerceAtLeast(64 * 1024L) }
                    audioFormats.add(
                        AvailableFormat(
                            key = "tiktok-web-audio",
                            mode = DownloadMode.AUDIO_ORIGINAL,
                            formatId = cleanVideoUrl,
                            extension = "m4a",
                            formatNote = "Video source (audio extracted)",
                            estimatedSizeBytes = estimatedAudioBytes,
                            sizeIsApproximate = true,
                            isQuickPreset = false,
                            httpHeaders = streamHeaders,
                        )
                    )
                }
            }

            val safeCover = itemCover?.takeIf {
                NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false)
            }

            MediaFormatCatalog(
                sourceUrl = url,
                title = title,
                videoFormats = videoFormats,
                audioFormats = audioFormats,
                author = itemAuthor,
                thumbnailUrl = safeCover,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchTikTokOEmbed(client: OkHttpClient, url: String): Pair<String?, String?> {
        return try {
            val oembedUrl = "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8")
            val req = Request.Builder()
                .url(oembedUrl)
                .addHeader("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Pair(null, null)
                val json = JSONObject(resp.body?.readBoundedString().orEmpty())
                Pair(
                    json.optString("title").takeIf { it.isNotBlank() },
                    json.optString("author_name").takeIf { it.isNotBlank() }
                )
            }
        } catch (_: Exception) {
            Pair(null, null)
        }
    }
}
