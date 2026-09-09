package com.example.simplemediadownloader

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.regex.Pattern

object SocialMediaExtractor {

    private class InMemoryCookieJar : CookieJar {
        private val cookieStore = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                cookieStore.removeAll { existing -> cookies.any { it.name == existing.name } }
                cookieStore.addAll(cookies)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return synchronized(cookieStore) { cookieStore.toList() }
        }

        fun getFormattedCookieHeader(): String {
            return synchronized(cookieStore) {
                cookieStore.distinctBy { it.name }.joinToString("; ") { "${it.name}=${it.value}" }
            }
        }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val CRAWLER_USER_AGENT =
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    private const val WHATSAPP_USER_AGENT =
        "WhatsApp/2.21.12.21 A"
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"

    suspend fun extract(
        client: OkHttpClient,
        url: String,
        platform: String,
    ): FormatDiscoveryResult {
        return try {
            val resolvedUrl = followRedirects(client, url)
            val primaryResult = when (platform.lowercase()) {
                "facebook" -> extractFacebook(client, resolvedUrl)
                "tiktok" -> extractTikTok(client, resolvedUrl)
                "instagram" -> extractInstagram(client, resolvedUrl)
                "x", "twitter" -> extractTwitter(client, resolvedUrl)
                "reddit" -> extractReddit(client, resolvedUrl)
                else -> FormatDiscoveryResult.Failure("Unsupported platform: $platform")
            }

            if (primaryResult is FormatDiscoveryResult.Success) {
                primaryResult
            } else {
                // Tier 4: Fallback to universal public media gateway
                val gatewayCatalog = tryPublicGatewayExtraction(client, resolvedUrl, platform)
                if (gatewayCatalog != null && gatewayCatalog.videoFormats.isNotEmpty()) {
                    FormatDiscoveryResult.Success(gatewayCatalog)
                } else {
                    primaryResult
                }
            }
        } catch (e: Exception) {
            FormatDiscoveryResult.Failure(
                e.localizedMessage ?: "Could not extract media from this $platform link.",
            )
        }
    }

    private fun followRedirects(client: OkHttpClient, initialUrl: String): String {
        var currentUrl = initialUrl
        val redirectClient = client.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val ua = when {
            initialUrl.contains("facebook.com") || initialUrl.contains("fb.watch") -> CRAWLER_USER_AGENT
            initialUrl.contains("tiktok.com") -> MOBILE_USER_AGENT
            else -> USER_AGENT
        }

        val request = Request.Builder()
            .url(currentUrl)
            .addHeader("User-Agent", ua)
            .build()

        try {
            redirectClient.newCall(request).execute().use { response ->
                currentUrl = response.request.url.toString()
            }
        } catch (_: Exception) {}
        return currentUrl
    }

    private fun probeStreamSize(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>? = null,
    ): Long? {
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return null
        return try {
            val probeClient = client.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(4))
                .readTimeout(java.time.Duration.ofSeconds(4))
                .build()
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
            headers?.forEach { (k, v) -> reqBuilder.header(k, v) }
            probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 206) {
                    resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                } else if (resp.isSuccessful) {
                    val cl = resp.body?.contentLength()?.takeIf { it > 0 }
                    cl ?: resp.header("Content-Length")?.toLongOrNull()
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==========================================
    // TIKTOK MULTI-TIER EXTRACTOR
    // ==========================================

    private fun extractTikTok(client: OkHttpClient, url: String): FormatDiscoveryResult {
        // Tier 1: Universal SSR rehydration and mobile web scraper with session cookie preservation
        val webCatalog = tryTikTokWebScrape(client, url)
        if (webCatalog != null && webCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(webCatalog)
        }

        // Tier 2: TikWM Public High-Speed API (Watermark-free HD, MP3 audio, full metadata)
        val tikWmCatalog = tryTikWmApi(client, url)
        if (tikWmCatalog != null && tikWmCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(tikWmCatalog)
        }

        return FormatDiscoveryResult.Failure(
            "Could not extract video stream from this TikTok link. The post may be private or removed.",
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

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string().orEmpty()
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
                val cover = data.optString("cover")

                val playUrl = data.optString("play")
                val hdUrl = data.optString("hdplay").takeIf { it.isNotBlank() }
                val musicUrl = data.optString("music").takeIf { it.isNotBlank() }
                val size = data.optLong("size", 0L).takeIf { it > 0 }
                val hdSize = data.optLong("hd_size", 0L).takeIf { it > 0 }

                if (playUrl.isBlank() && hdUrl.isNullOrBlank()) return null

                val standardStream = if (playUrl.isNotBlank()) playUrl else hdUrl!!
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
                    audioFormats.add(
                        AvailableFormat(
                            key = "tiktok-music",
                            mode = DownloadMode.AUDIO_MP3,
                            formatId = musicUrl,
                            extension = "mp3",
                            bitrateKbps = 192,
                            formatNote = "Original Soundtrack",
                            estimatedSizeBytes = musicSize,
                            sizeIsApproximate = musicSize == null,
                            httpHeaders = headers,
                        )
                    )
                }
                audioFormats.add(
                    AvailableFormat(
                        key = "tiktok-audio-extract",
                        mode = DownloadMode.AUDIO_ORIGINAL,
                        formatId = standardStream,
                        extension = "mp4",
                        formatNote = "Original Audio",
                        estimatedSizeBytes = resolvedSize,
                        sizeIsApproximate = resolvedSize == null,
                        httpHeaders = headers,
                    )
                )

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
            val cookieJar = InMemoryCookieJar()
            val cookieClient = client.newBuilder()
                .cookieJar(cookieJar)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val html: String
            var finalUrl = url
            cookieClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                finalUrl = response.request.url.toString()
                html = response.body?.string().orEmpty()
            }
            if (html.isBlank()) return null

            val cookieHeader = cookieJar.getFormattedCookieHeader()
            val headers = mutableMapOf(
                "User-Agent" to MOBILE_USER_AGENT,
                "Referer" to "https://www.tiktok.com/",
            )
            if (cookieHeader.isNotBlank()) {
                headers["Cookie"] = cookieHeader
            }

            var directVideoUrl: String? = null
            var directAudioUrl: String? = null
            var itemTitle: String? = null
            var itemAuthor: String? = null
            var itemCover: String? = null
            var videoHeight: Int? = null

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

            val videoSize = probeStreamSize(client, cleanVideoUrl, headers)
            val audioSize = if (!directAudioUrl.isNullOrBlank()) {
                probeStreamSize(client, unescapeJsonUrl(directAudioUrl), headers)
            } else null

            val videoFormats = mutableListOf<AvailableFormat>()
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
                    httpHeaders = headers,
                )
            )

            val audioFormats = mutableListOf<AvailableFormat>()
            if (!directAudioUrl.isNullOrBlank()) {
                val cleanAudioUrl = unescapeJsonUrl(directAudioUrl)
                audioFormats.add(
                    AvailableFormat(
                        key = "tiktok-web-music",
                        mode = DownloadMode.AUDIO_MP3,
                        formatId = cleanAudioUrl,
                        extension = "mp3",
                        bitrateKbps = 192,
                        formatNote = "Original Soundtrack",
                        estimatedSizeBytes = audioSize,
                        sizeIsApproximate = audioSize == null,
                        isQuickPreset = false,
                        httpHeaders = headers,
                    )
                )
            }
            audioFormats.add(
                AvailableFormat(
                    key = "tiktok-web-audio",
                    mode = DownloadMode.AUDIO_ORIGINAL,
                    formatId = cleanVideoUrl,
                    extension = "mp4",
                    formatNote = "Original Audio",
                    estimatedSizeBytes = videoSize,
                    sizeIsApproximate = videoSize == null,
                    isQuickPreset = false,
                    httpHeaders = headers,
                )
            )

            MediaFormatCatalog(
                sourceUrl = url,
                title = title,
                videoFormats = videoFormats,
                audioFormats = audioFormats,
                author = itemAuthor,
                thumbnailUrl = itemCover,
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
                val json = JSONObject(resp.body?.string().orEmpty())
                Pair(
                    json.optString("title").takeIf { it.isNotBlank() },
                    json.optString("author_name").takeIf { it.isNotBlank() }
                )
            }
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    // ==========================================
    // INSTAGRAM MULTI-TIER EXTRACTOR
    // ==========================================

    private fun extractInstagram(client: OkHttpClient, url: String): FormatDiscoveryResult {
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
                response.body?.string().orEmpty()
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

            if (videoFormats.isEmpty() || bestUrl == null) return null

            val primarySize = videoFormats.firstOrNull()?.estimatedSizeBytes
            val audioFormats = listOf(
                AvailableFormat(
                    key = "ig-relay-audio",
                    mode = DownloadMode.AUDIO_ORIGINAL,
                    formatId = bestUrl,
                    extension = "mp4",
                    formatNote = "Original Audio",
                    estimatedSizeBytes = primarySize,
                    sizeIsApproximate = primarySize == null,
                    httpHeaders = headers,
                )
            )

            val images = targetProduct.optJSONObject("image_versions2")?.optJSONArray("candidates")
            val thumbUrl = images?.optJSONObject(0)?.optString("url")
                ?: findMetaProperty(html, "og:image")
                ?: findMetaProperty(html, "twitter:image")

            MediaFormatCatalog(
                sourceUrl = url,
                title = title,
                videoFormats = videoFormats,
                audioFormats = audioFormats,
                author = author,
                thumbnailUrl = thumbUrl?.let { unescapeJsonUrl(it) },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractInstagramShortcode(url: String): String? {
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
                val html = response.body?.string().orEmpty()

                // Extract direct video URL from embed HTML
                val videoMatcher = Pattern.compile("""<video[^>]+src="([^"]+)"""").matcher(html)
                val jsonVideoMatcher = Pattern.compile(""""video_url"\s*:\s*"([^"]+)"""").matcher(html)

                val rawVideoUrl = when {
                    videoMatcher.find() -> videoMatcher.group(1)
                    jsonVideoMatcher.find() -> jsonVideoMatcher.group(1)
                    else -> null
                } ?: return null

                val cleanVideoUrl = unescapeJsonUrl(rawVideoUrl)
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
                val audioFormats = listOf(
                    AvailableFormat(
                        key = "ig-embed-audio",
                        mode = DownloadMode.AUDIO_ORIGINAL,
                        formatId = cleanVideoUrl,
                        extension = "mp4",
                        formatNote = "Original Audio",
                        estimatedSizeBytes = streamSize,
                        sizeIsApproximate = streamSize == null,
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
                    val html = response.body?.string().orEmpty()

                    val videoUrl = findMetaProperty(html, "og:video")
                        ?: findMetaProperty(html, "og:video:secure_url")
                        ?: extractPattern(html, """"video_url"\s*:\s*"([^"]+)"""")
                        ?: return@use

                    val cleanVideoUrl = unescapeJsonUrl(videoUrl)
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
                    val audioFormats = listOf(
                        AvailableFormat(
                            key = "ig-crawler-audio",
                            mode = DownloadMode.AUDIO_ORIGINAL,
                            formatId = cleanVideoUrl,
                            extension = "mp4",
                            formatNote = "Original Audio",
                            estimatedSizeBytes = streamSize,
                            sizeIsApproximate = streamSize == null,
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

    // ==========================================
    // FACEBOOK MULTI-TIER EXTRACTOR
    // ==========================================

    private fun extractFacebook(client: OkHttpClient, url: String): FormatDiscoveryResult {
        val resolvedUrl = if (url.contains("/share/") || url.contains("fb.watch")) {
            followRedirects(client, url)
        } else {
            url
        }
        val videoId = extractFacebookId(resolvedUrl) ?: extractFacebookId(url)

        // Tier 1: Facebook Video Plugin Embed
        val embedCatalog = tryFacebookPluginEmbed(client, resolvedUrl, videoId)
        if (embedCatalog != null && embedCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(embedCatalog)
        }

        // Tier 2: Mobile Watch endpoint
        if (videoId != null) {
            val watchCatalog = tryFacebookWatch(client, videoId, resolvedUrl)
            if (watchCatalog != null && watchCatalog.videoFormats.isNotEmpty()) {
                return FormatDiscoveryResult.Success(watchCatalog)
            }
        }

        // Tier 3: Crawler Impersonation
        val crawlerCatalog = tryFacebookCrawler(client, resolvedUrl)
        if (crawlerCatalog != null && crawlerCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(crawlerCatalog)
        }

        // Tier 4: Direct web scraping
        val webCatalog = tryFacebookWeb(client, resolvedUrl)
        if (webCatalog != null && webCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(webCatalog)
        }

        return FormatDiscoveryResult.Failure(
            "Could not find a public video stream in this Facebook link. It may be private or restricted.",
        )
    }

    private fun extractFacebookId(url: String): String? {
        val patterns = listOf(
            Pattern.compile("""/(?:reel|videos|posts)/([0-9]+)"""),
            Pattern.compile("""[?&]v=([0-9]+)"""),
            Pattern.compile("""facebook\.com/share/[vrp]/([a-zA-Z0-9_-]+)"""),
            Pattern.compile("""facebook\.com/watch/?\?v=([0-9]+)"""),
            Pattern.compile("""fb\.watch/([a-zA-Z0-9_-]+)"""),
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun tryFacebookPluginEmbed(client: OkHttpClient, url: String, videoId: String?): MediaFormatCatalog? {
        val canonicalUrl = when {
            videoId != null && videoId.all { it.isDigit() } -> "https://www.facebook.com/reel/$videoId/"
            url.contains("/share/") || url.contains("fb.watch") -> {
                val redirected = followRedirects(client, url)
                val id = extractFacebookId(redirected)
                if (id != null && id.all { it.isDigit() }) "https://www.facebook.com/reel/$id/" else redirected
            }
            else -> url
        }
        return try {
            val encodedUrl = URLEncoder.encode(canonicalUrl, "UTF-8")
            val embedUrl = "https://www.facebook.com/plugins/video.php?href=$encodedUrl"

            val request = Request.Builder()
                .url(embedUrl)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            val catalog = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string().orEmpty()
                parseFacebookHtml(client, html, canonicalUrl)
            } ?: return null

            if (catalog.title == "Facebook" || catalog.title == "Facebook Video") {
                val realTitle = fetchFacebookPageTitle(client, canonicalUrl)
                if (!realTitle.isNullOrBlank()) {
                    return catalog.copy(title = cleanTitle(realTitle))
                }
            }
            catalog
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFacebookPageTitle(client: OkHttpClient, url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", CRAWLER_USER_AGENT)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string().orEmpty()
                findMetaProperty(html, "og:title") ?: extractPattern(html, """<title>([^<]+)</title>""")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryFacebookCrawler(client: OkHttpClient, url: String): MediaFormatCatalog? {
        val crawlerUas = listOf(CRAWLER_USER_AGENT, WHATSAPP_USER_AGENT)
        for (ua in crawlerUas) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", ua)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val html = response.body?.string().orEmpty()
                    val catalog = parseFacebookHtml(client, html, url)
                    if (catalog != null && catalog.videoFormats.isNotEmpty()) {
                        return catalog
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun tryFacebookWatch(client: OkHttpClient, videoId: String, originalUrl: String): MediaFormatCatalog? {
        val watchEndpoints = listOf(
            "https://m.facebook.com/watch/?v=$videoId",
            "https://www.facebook.com/watch/?v=$videoId",
        )
        for (endpoint in watchEndpoints) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("User-Agent", MOBILE_USER_AGENT)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val html = response.body?.string().orEmpty()
                    val catalog = parseFacebookHtml(client, html, originalUrl)
                    if (catalog != null && catalog.videoFormats.isNotEmpty()) {
                        return catalog
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun tryFacebookWeb(client: OkHttpClient, url: String): MediaFormatCatalog? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string().orEmpty()
                parseFacebookHtml(client, html, url)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFacebookHtml(client: OkHttpClient, html: String, sourceUrl: String): MediaFormatCatalog? {
        val hdUrl = findFacebookStream(html, "hd_src")
            ?: findFacebookStream(html, "hd_src_no_ratelimit")
            ?: findFacebookStream(html, "browser_native_hd_url")
            ?: findFacebookStream(html, "playable_url_quality_hd")

        val sdUrl = findFacebookStream(html, "sd_src")
            ?: findFacebookStream(html, "sd_src_no_ratelimit")
            ?: findFacebookStream(html, "browser_native_sd_url")
            ?: findFacebookStream(html, "playable_url")
            ?: findMetaProperty(html, "og:video")
            ?: findMetaProperty(html, "og:video:url")
            ?: findMetaProperty(html, "og:video:secure_url")
            ?: findMetaProperty(html, "twitter:player:stream")
            ?: findDirectFbcdnMp4(html)

        val validHd = hdUrl?.takeIf { isValidFbStream(it) }
        val validSd = sdUrl?.takeIf { isValidFbStream(it) }

        if (validHd.isNullOrBlank() && validSd.isNullOrBlank()) {
            return null
        }

        val rawTitle = findMetaProperty(html, "og:title")
            ?: extractPattern(html, """<title>([^<]+)</title>""")
            ?: "Facebook Video"
        val title = cleanTitle(rawTitle)

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://www.facebook.com/",
        )

        val hdSize = validHd?.let { probeStreamSize(client, it, headers) }
        val sdSize = validSd?.let { probeStreamSize(client, it, headers) }

        val videoFormats = mutableListOf<AvailableFormat>()
        if (!validHd.isNullOrBlank()) {
            videoFormats.add(
                AvailableFormat(
                    key = "fb-hd",
                    mode = DownloadMode.VIDEO,
                    formatId = validHd,
                    extension = "mp4",
                    height = 1080,
                    formatNote = "HD Quality (1080p)",
                    estimatedSizeBytes = hdSize,
                    sizeIsApproximate = hdSize == null,
                    isQuickPreset = false,
                    httpHeaders = headers,
                )
            )
        }
        if (!validSd.isNullOrBlank() && validSd != validHd) {
            videoFormats.add(
                AvailableFormat(
                    key = "fb-sd",
                    mode = DownloadMode.VIDEO,
                    formatId = validSd,
                    extension = "mp4",
                    height = 720,
                    formatNote = "SD Quality",
                    estimatedSizeBytes = sdSize,
                    sizeIsApproximate = sdSize == null,
                    isQuickPreset = false,
                    httpHeaders = headers,
                )
            )
        }

        if (videoFormats.isEmpty()) return null

        val primaryStreamUrl = (validHd ?: validSd)!!
        val primarySize = hdSize ?: sdSize
        val audioFormats = listOf(
            AvailableFormat(
                key = "fb-audio",
                mode = DownloadMode.AUDIO_ORIGINAL,
                formatId = primaryStreamUrl,
                extension = "mp4",
                formatNote = "Audio from video",
                estimatedSizeBytes = primarySize,
                sizeIsApproximate = primarySize == null,
                isQuickPreset = false,
                httpHeaders = headers,
            ),
            AvailableFormat(
                key = "fb-mp3",
                mode = DownloadMode.AUDIO_MP3,
                formatId = primaryStreamUrl,
                extension = "mp4",
                formatNote = "MP3 audio",
                estimatedSizeBytes = primarySize,
                sizeIsApproximate = primarySize == null,
                isQuickPreset = false,
                httpHeaders = headers,
            ),
        )

        return MediaFormatCatalog(
            sourceUrl = sourceUrl,
            title = title,
            videoFormats = videoFormats,
            audioFormats = audioFormats,
        )
    }

    private fun isValidFbStream(url: String): Boolean {
        if (url.contains("from_lookaside=1") || url.contains("plugins/video.php")) return false
        if (url.contains("facebook.com/") && !url.contains("fbcdn.net")) return false
        return url.contains("fbcdn.net") || url.contains(".mp4")
    }

    private fun findDirectFbcdnMp4(html: String): String? {
        val pattern = Pattern.compile("""https?:\\/\\/[^"'\s]+?fbcdn\.net[^"'\s]+?\.mp4[^"'\s]*""")
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            val matched = matcher.group(0)
            if (!matched.isNullOrBlank()) return unescapeJsonUrl(matched)
        }
        val unescapedPattern = Pattern.compile("""https?://[^"'\s]+?fbcdn\.net[^"'\s]+?\.mp4[^"'\s]*""")
        val unescapedMatcher = unescapedPattern.matcher(html)
        if (unescapedMatcher.find()) {
            return unescapedMatcher.group(0)
        }
        return null
    }

    private fun findFacebookStream(html: String, key: String): String? {
        val pattern = Pattern.compile(""""$key"\s*:\s*"([^"]+)"""")
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            val raw = matcher.group(1).orEmpty()
            return unescapeJsonUrl(raw)
        }
        return null
    }

    // ==========================================
    // TWITTER / X EXTRACTOR
    // ==========================================

    private fun extractTwitter(client: OkHttpClient, url: String): FormatDiscoveryResult {
        val tweetId = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (tweetId.isBlank()) {
            return FormatDiscoveryResult.Failure("Invalid Twitter/X URL format.")
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

                val jsonString = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string().orEmpty()
                } ?: continue

                val rootJson = JSONObject(jsonString)
                val tweetObj = rootJson.optJSONObject("tweet") ?: rootJson

                val text = tweetObj.optString("text").ifBlank {
                    tweetObj.optString("raw_text", "X Post")
                }

                val authorObj = tweetObj.optJSONObject("author")
                val author = authorObj?.optString("name")
                    ?: tweetObj.optString("user_name").ifBlank { tweetObj.optString("user_screen_name") }

                var videoUrl: String? = null
                var videoHeight = 1080

                val mediaObj = tweetObj.optJSONObject("media")
                val videosArray = mediaObj?.optJSONArray("videos")
                    ?: tweetObj.optJSONArray("media_extended")

                if (videosArray != null && videosArray.length() > 0) {
                    val firstVideo = videosArray.optJSONObject(0)
                    videoUrl = firstVideo?.optString("url")
                    val h = firstVideo?.optInt("height", 0) ?: 0
                    if (h > 0) videoHeight = h
                }

                if (videoUrl.isNullOrBlank()) {
                    val allArray = mediaObj?.optJSONArray("all")
                    if (allArray != null) {
                        for (i in 0 until allArray.length()) {
                            val item = allArray.optJSONObject(i)
                            if (item?.optString("type") == "video") {
                                videoUrl = item.optString("url")
                                val h = item.optInt("height", 0) ?: 0
                                if (h > 0) videoHeight = h
                                break
                            }
                        }
                    }
                }

                if (videoUrl.isNullOrBlank()) {
                    val directMedia = tweetObj.optString("media_url")
                    if (tweetObj.optString("mediaType") == "video" && directMedia.isNotBlank()) {
                        videoUrl = directMedia
                    }
                }

                if (!videoUrl.isNullOrBlank()) {
                    val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://twitter.com/")
                    val streamSize = probeStreamSize(client, videoUrl, headers)
                    val videoFormats = listOf(
                        AvailableFormat(
                            key = "tw-video",
                            mode = DownloadMode.VIDEO,
                            formatId = videoUrl,
                            extension = "mp4",
                            height = videoHeight,
                            formatNote = "MP4 Video",
                            estimatedSizeBytes = streamSize,
                            sizeIsApproximate = streamSize == null,
                            isQuickPreset = false,
                            httpHeaders = headers,
                        )
                    )
                    val audioFormats = listOf(
                        AvailableFormat(
                            key = "tw-audio",
                            mode = DownloadMode.AUDIO_ORIGINAL,
                            formatId = videoUrl,
                            extension = "mp4",
                            formatNote = "Audio",
                            estimatedSizeBytes = streamSize,
                            sizeIsApproximate = streamSize == null,
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

                val catalog = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val html = response.body?.string().orEmpty()

                    val videoUrl = findMetaProperty(html, "og:video")
                        ?: findMetaProperty(html, "og:video:url")
                        ?: findMetaProperty(html, "og:video:secure_url")
                        ?: findMetaProperty(html, "twitter:player:stream")
                        ?: return@use null

                    val title = findMetaProperty(html, "og:title")
                        ?: findMetaProperty(html, "og:description")
                        ?: "X Post"

                    val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://twitter.com/")
                    val streamSize = probeStreamSize(client, videoUrl, headers)
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
                            extension = "mp4",
                            formatNote = "Audio",
                            estimatedSizeBytes = streamSize,
                            sizeIsApproximate = streamSize == null,
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

    // ==========================================
    // REDDIT EXTRACTOR (WITH AUDIO STREAM MUXING)
    // ==========================================

    private fun extractReddit(client: OkHttpClient, url: String): FormatDiscoveryResult {
        val cleanUrl = url.substringBefore('?').trimEnd('/')
        val jsonUrl = "$cleanUrl.json"

        val request = Request.Builder()
            .url(jsonUrl)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        return try {
            val jsonString = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return FormatDiscoveryResult.Failure("Reddit returned HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }

            val jsonArray = JSONArray(jsonString)
            val post = jsonArray.getJSONObject(0)
                .getJSONObject("data")
                .getJSONArray("children")
                .getJSONObject(0)
                .getJSONObject("data")

            val title = cleanTitle(post.optString("title", "Reddit Video"))
            val author = post.optString("author")
            val secureMedia = post.optJSONObject("secure_media")
                ?: post.optJSONObject("media")
            val redditVideo = secureMedia?.optJSONObject("reddit_video")

            val fallbackUrl = redditVideo?.optString("fallback_url")
            if (fallbackUrl.isNullOrBlank()) {
                return FormatDiscoveryResult.Failure("No Reddit hosted video found in this post.")
            }

            val height = redditVideo.optInt("height", 720)

            // Construct companion audio URL for Reddit's DASH separated audio track
            val audioUrl = fallbackUrl.substringBefore("DASH_") + "DASH_AUDIO_128.mp4"
            val companionAudio = if (checkUrlExists(client, audioUrl)) {
                audioUrl
            } else {
                val legacyAudio = fallbackUrl.substringBefore("DASH_") + "DASH_audio.mp4"
                if (checkUrlExists(client, legacyAudio)) legacyAudio else null
            }

            val headers = mapOf("User-Agent" to USER_AGENT)
            val videoSize = probeStreamSize(client, fallbackUrl, headers)
            val audioSize = companionAudio?.let { probeStreamSize(client, it, headers) }
            val totalSize = if (videoSize != null && audioSize != null) videoSize + audioSize else (videoSize ?: audioSize)

            val videoFormats = listOf(
                AvailableFormat(
                    key = "reddit-video",
                    mode = DownloadMode.VIDEO,
                    formatId = fallbackUrl,
                    companionAudioFormatId = companionAudio,
                    extension = "mp4",
                    height = height,
                    formatNote = if (companionAudio != null) "Full Video & Audio" else "Video (Muted)",
                    estimatedSizeBytes = totalSize,
                    sizeIsApproximate = totalSize == null,
                    isQuickPreset = false,
                    httpHeaders = headers,
                )
            )

            val audioFormats = if (companionAudio != null) {
                listOf(
                    AvailableFormat(
                        key = "reddit-audio",
                        mode = DownloadMode.AUDIO_ORIGINAL,
                        formatId = companionAudio,
                        extension = "mp4",
                        formatNote = "Original Audio",
                        estimatedSizeBytes = audioSize,
                        sizeIsApproximate = audioSize == null,
                        isQuickPreset = false,
                        httpHeaders = headers,
                    ),
                    AvailableFormat(
                        key = "reddit-mp3",
                        mode = DownloadMode.AUDIO_MP3,
                        formatId = companionAudio,
                        extension = "mp4",
                        formatNote = "MP3 Audio",
                        estimatedSizeBytes = audioSize,
                        sizeIsApproximate = audioSize == null,
                        isQuickPreset = false,
                        httpHeaders = headers,
                    )
                )
            } else emptyList()

            FormatDiscoveryResult.Success(
                MediaFormatCatalog(
                    sourceUrl = url,
                    title = title,
                    videoFormats = videoFormats,
                    audioFormats = audioFormats,
                    author = author.takeIf { it.isNotBlank() },
                )
            )
        } catch (e: Exception) {
            FormatDiscoveryResult.Failure(e.localizedMessage ?: "Could not inspect Reddit post.")
        }
    }

    private fun checkUrlExists(client: OkHttpClient, url: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .addHeader("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    // ==========================================
    // UNIVERSAL COBALT V10 GATEWAY FALLBACK
    // ==========================================

    private fun tryPublicGatewayExtraction(
        client: OkHttpClient,
        url: String,
        platform: String,
    ): MediaFormatCatalog? {
        val gateways = listOf(
            "https://cobalt.api.scav.run",
            "https://api.cobalt.tools",
            "https://co.wuk.sh/api/json",
        )

        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        for (endpoint in gateways) {
            try {
                val jsonBody = JSONObject().apply {
                    put("url", url)
                    put("videoQuality", "max")
                }.toString()

                val requestBody = jsonBody.toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", USER_AGENT)
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyString = response.body?.string().orEmpty()
                    if (bodyString.isBlank()) return@use

                    val json = JSONObject(bodyString)
                    val status = json.optString("status")
                    var directUrl = json.optString("url")
                    val filename = json.optString("filename")

                    if (directUrl.isBlank() && status == "picker") {
                        val picker = json.optJSONArray("picker")
                        if (picker != null && picker.length() > 0) {
                            for (i in 0 until picker.length()) {
                                val item = picker.optJSONObject(i)
                                val itemUrl = item?.optString("url").orEmpty()
                                if (itemUrl.isNotBlank()) {
                                    directUrl = itemUrl
                                    break
                                }
                            }
                        }
                    }

                    if (directUrl.isNotBlank()) {
                        val title = if (filename.isNotBlank()) {
                            cleanTitle(filename.substringBeforeLast('.'))
                        } else {
                            "$platform Video"
                        }

                        val videoFormats = listOf(
                            AvailableFormat(
                                key = "$platform-gateway-hd",
                                mode = DownloadMode.VIDEO,
                                formatId = directUrl,
                                extension = "mp4",
                                height = 1080,
                                formatNote = "HD Quality",
                                isQuickPreset = false,
                            )
                        )
                        val audioFormats = listOf(
                            AvailableFormat(
                                key = "$platform-gateway-audio",
                                mode = DownloadMode.AUDIO_ORIGINAL,
                                formatId = directUrl,
                                extension = "mp4",
                                formatNote = "Audio stream",
                                isQuickPreset = false,
                            )
                        )

                        return MediaFormatCatalog(
                            sourceUrl = url,
                            title = title,
                            videoFormats = videoFormats,
                            audioFormats = audioFormats,
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // ==========================================
    // UTILITIES & TITLE SANITIZATION
    // ==========================================

    fun cleanTitle(raw: String): String {
        var decoded = raw
        try {
            val hexPattern = Pattern.compile("&#x([0-9a-fA-F]+);")
            val hexMatcher = hexPattern.matcher(decoded)
            val sb = StringBuffer()
            while (hexMatcher.find()) {
                val hexStr = hexMatcher.group(1)
                val cp = hexStr?.toIntOrNull(16)
                if (cp != null) {
                    hexMatcher.appendReplacement(sb, String(Character.toChars(cp)))
                }
            }
            hexMatcher.appendTail(sb)
            decoded = sb.toString()
        } catch (_: Exception) {}

        try {
            val decPattern = Pattern.compile("&#([0-9]+);")
            val decMatcher = decPattern.matcher(decoded)
            val sb2 = StringBuffer()
            while (decMatcher.find()) {
                val decStr = decMatcher.group(1)
                val cp = decStr?.toIntOrNull(10)
                if (cp != null) {
                    decMatcher.appendReplacement(sb2, String(Character.toChars(cp)))
                }
            }
            decMatcher.appendTail(sb2)
            decoded = sb2.toString()
        } catch (_: Exception) {}

        decoded = decoded
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&middot;", "·")
            .replace("&#xb7;", "·")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (decoded.contains(" on Instagram: \"") && decoded.endsWith("\"")) {
            decoded = decoded.substringAfter(" on Instagram: \"").removeSuffix("\"")
        }
        if (decoded.endsWith(" | Facebook")) {
            decoded = decoded.removeSuffix(" | Facebook")
        }
        if (decoded.endsWith(" - Facebook")) {
            decoded = decoded.removeSuffix(" - Facebook")
        }

        val strippedPrefix = decoded.replace(
            Regex("""^\d+[\d.,]*[KkMmBb]?\s+(?:views|plays|reactions)\s*(?:[·|•]\s*\d+[\d.,]*[KkMmBb]?\s+(?:views|plays|reactions))*\s*[|•·]\s*""", RegexOption.IGNORE_CASE),
            ""
        )
        val withoutHashtags = strippedPrefix.replace(Regex("""\s*#\S+.*$"""), "").trim()
        val candidate = if (withoutHashtags.length >= 4) withoutHashtags else strippedPrefix
        return candidate.take(120).trim().ifBlank { "Media Video" }
    }

    private fun findMetaProperty(html: String, property: String): String? {
        val pattern1 = Pattern.compile("""<meta\s+property=["']$property["']\s+content=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        val matcher1 = pattern1.matcher(html)
        if (matcher1.find()) return unescapeJsonUrl(matcher1.group(1).orEmpty())

        val pattern2 = Pattern.compile("""<meta\s+content=["']([^"']+)["']\s+property=["']$property["']""", Pattern.CASE_INSENSITIVE)
        val matcher2 = pattern2.matcher(html)
        if (matcher2.find()) return unescapeJsonUrl(matcher2.group(1).orEmpty())

        return null
    }

    private fun extractPattern(html: String, regex: String): String? {
        val pattern = Pattern.compile(regex)
        val matcher = pattern.matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractJsonFromHtmlTag(html: String, tagIdentifier: String): String? {
        val idx = html.indexOf(tagIdentifier)
        if (idx == -1) return null
        val tagEnd = html.indexOf('>', idx)
        if (tagEnd == -1) return null
        val scriptEnd = html.indexOf("</script>", tagEnd)
        if (scriptEnd == -1) return null
        return html.substring(tagEnd + 1, scriptEnd).trim()
    }

    private fun unescapeJsonUrl(url: String): String {
        return url.replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u00252F", "/")
            .replace("\\u0025", "%")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003F", "?")
            .replace("&amp;", "&")
    }
}
