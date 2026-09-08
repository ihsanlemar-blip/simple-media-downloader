package com.example.simplemediadownloader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.regex.Pattern

object SocialMediaExtractor {

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

        val request = Request.Builder()
            .url(currentUrl)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        try {
            redirectClient.newCall(request).execute().use { response ->
                currentUrl = response.request.url.toString()
            }
        } catch (_: Exception) {}
        return currentUrl
    }

    // ==========================================
    // TIKTOK MULTI-TIER EXTRACTOR
    // ==========================================

    private fun extractTikTok(client: OkHttpClient, url: String): FormatDiscoveryResult {
        // Tier 1: TikWM Public High-Speed API (Watermark-free HD, MP3 audio, full metadata)
        val tikWmCatalog = tryTikWmApi(client, url)
        if (tikWmCatalog != null && tikWmCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(tikWmCatalog)
        }

        // Tier 2: Universal SSR rehydration and mobile web scraper
        val webCatalog = tryTikTokWebScrape(client, url)
        if (webCatalog != null && webCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(webCatalog)
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

                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://www.tiktok.com/",
                )

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
                            estimatedSizeBytes = hdSize ?: (size?.times(2)),
                            sizeIsApproximate = hdSize == null,
                            httpHeaders = headers,
                        )
                    )
                }

                val standardStream = if (playUrl.isNotBlank()) playUrl else hdUrl!!
                videoFormats.add(
                    AvailableFormat(
                        key = "tiktok-watermark-free",
                        mode = DownloadMode.VIDEO,
                        formatId = standardStream,
                        extension = "mp4",
                        height = 720,
                        formatNote = "Watermark-Free",
                        estimatedSizeBytes = size,
                        sizeIsApproximate = false,
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
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", MOBILE_USER_AGENT)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string().orEmpty()

                val videoUrl = findMetaProperty(html, "og:video")
                    ?: findMetaProperty(html, "og:video:secure_url")
                    ?: extractPattern(html, """"playAddr"\s*:\s*"([^"]+)"""")
                    ?: extractPattern(html, """"downloadAddr"\s*:\s*"([^"]+)"""")
                    ?: return null

                val cleanVideoUrl = unescapeJsonUrl(videoUrl)
                val rawTitle = findMetaProperty(html, "og:title")
                    ?: extractPattern(html, """<title>([^<]+)</title>""")
                    ?: "TikTok Video"
                val title = cleanTitle(rawTitle)

                val headers = mapOf(
                    "User-Agent" to MOBILE_USER_AGENT,
                    "Referer" to "https://www.tiktok.com/",
                )

                val videoFormats = listOf(
                    AvailableFormat(
                        key = "tiktok-web-hd",
                        mode = DownloadMode.VIDEO,
                        formatId = cleanVideoUrl,
                        extension = "mp4",
                        height = 1080,
                        formatNote = "Standard Quality",
                        httpHeaders = headers,
                    )
                )
                val audioFormats = listOf(
                    AvailableFormat(
                        key = "tiktok-web-audio",
                        mode = DownloadMode.AUDIO_ORIGINAL,
                        formatId = cleanVideoUrl,
                        extension = "mp4",
                        formatNote = "Original Sound",
                        httpHeaders = headers,
                    )
                )

                MediaFormatCatalog(
                    sourceUrl = url,
                    title = title,
                    videoFormats = videoFormats,
                    audioFormats = audioFormats,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==========================================
    // INSTAGRAM MULTI-TIER EXTRACTOR
    // ==========================================

    private fun extractInstagram(client: OkHttpClient, url: String): FormatDiscoveryResult {
        val shortcode = extractInstagramShortcode(url)

        // Tier 1: Public captioned embed endpoint (Bypasses Instagram login blocks completely)
        if (shortcode != null) {
            val embedCatalog = tryInstagramEmbed(client, url, shortcode)
            if (embedCatalog != null && embedCatalog.videoFormats.isNotEmpty()) {
                return FormatDiscoveryResult.Success(embedCatalog)
            }
        }

        // Tier 2: Public crawler emulation (WhatsApp / Facebook bot impersonation)
        val crawlerCatalog = tryInstagramCrawler(client, url)
        if (crawlerCatalog != null && crawlerCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(crawlerCatalog)
        }

        return FormatDiscoveryResult.Failure(
            "Could not extract video from this Instagram link. The post may be private or restricted.",
        )
    }

    private fun extractInstagramShortcode(url: String): String? {
        val pattern = Pattern.compile("""/(?:p|reel|tv)/([A-Za-z0-9_-]+)""")
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

                val videoFormats = listOf(
                    AvailableFormat(
                        key = "ig-embed-video",
                        mode = DownloadMode.VIDEO,
                        formatId = cleanVideoUrl,
                        extension = "mp4",
                        height = 1080,
                        formatNote = "Best Quality",
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

                    val videoFormats = listOf(
                        AvailableFormat(
                            key = "ig-crawler-video",
                            mode = DownloadMode.VIDEO,
                            formatId = cleanVideoUrl,
                            extension = "mp4",
                            height = 1080,
                            formatNote = "HD Quality",
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
        val videoId = extractFacebookId(url)

        // Tier 1: Facebook Video Plugin Embed
        val embedCatalog = tryFacebookPluginEmbed(client, url, videoId)
        if (embedCatalog != null && embedCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(embedCatalog)
        }

        // Tier 2: Mobile Watch endpoint
        if (videoId != null) {
            val watchCatalog = tryFacebookWatch(client, videoId, url)
            if (watchCatalog != null && watchCatalog.videoFormats.isNotEmpty()) {
                return FormatDiscoveryResult.Success(watchCatalog)
            }
        }

        // Tier 3: Crawler Impersonation
        val crawlerCatalog = tryFacebookCrawler(client, url)
        if (crawlerCatalog != null && crawlerCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(crawlerCatalog)
        }

        // Tier 4: Direct web scraping
        val webCatalog = tryFacebookWeb(client, url)
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
            Pattern.compile("""facebook\.com/share/[vr]/([a-zA-Z0-9_-]+)"""),
            Pattern.compile("""facebook\.com/watch/\?v=([0-9]+)"""),
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
            url.contains("/share/") -> {
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
                parseFacebookHtml(html, canonicalUrl)
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
                    val catalog = parseFacebookHtml(html, url)
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
                    val catalog = parseFacebookHtml(html, originalUrl)
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
                parseFacebookHtml(html, url)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFacebookHtml(html: String, sourceUrl: String): MediaFormatCatalog? {
        val hdUrl = findFacebookStream(html, "hd_src")
            ?: findFacebookStream(html, "hd_src_no_ratelimit")
            ?: findFacebookStream(html, "browser_native_hd_url")
            ?: findFacebookStream(html, "playable_url_quality_hd")

        val sdUrl = findFacebookStream(html, "sd_src")
            ?: findFacebookStream(html, "sd_src_no_ratelimit")
            ?: findFacebookStream(html, "browser_native_sd_url")
            ?: findFacebookStream(html, "playable_url")
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
                    isQuickPreset = false,
                    httpHeaders = headers,
                )
            )
        }

        if (videoFormats.isEmpty()) return null

        val primaryStreamUrl = (validHd ?: validSd)!!
        val audioFormats = listOf(
            AvailableFormat(
                key = "fb-audio",
                mode = DownloadMode.AUDIO_ORIGINAL,
                formatId = primaryStreamUrl,
                extension = "mp4",
                formatNote = "Audio from video",
                isQuickPreset = false,
                httpHeaders = headers,
            ),
            AvailableFormat(
                key = "fb-mp3",
                mode = DownloadMode.AUDIO_MP3,
                formatId = primaryStreamUrl,
                extension = "mp4",
                formatNote = "MP3 audio",
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
        val tweetId = url.substringBefore('?').substringAfterLast('/')
        val endpoints = listOf(
            "https://api.vxtwitter.com/Twitter/status/$tweetId",
            "https://api.fxtwitter.com/status/$tweetId",
            "https://api.fixupx.com/status/$tweetId",
        )

        for (apiEndpoint in endpoints) {
            try {
                val request = Request.Builder()
                    .url(apiEndpoint)
                    .addHeader("User-Agent", USER_AGENT)
                    .build()

                val jsonString = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string().orEmpty()
                } ?: continue

                val json = JSONObject(jsonString)
                val mediaUrl = json.optString("media_url", "")
                val mediaType = json.optString("mediaType", "")
                val text = json.optString("text", "X Post").ifBlank { "X Post" }
                val author = json.optString("user_name").ifBlank { json.optString("user_screen_name") }

                val videoUrl = if (mediaType == "video" && mediaUrl.isNotBlank()) {
                    mediaUrl
                } else {
                    val videoArray = json.optJSONArray("media_extended")
                    var foundUrl = ""
                    if (videoArray != null) {
                        for (i in 0 until videoArray.length()) {
                            val item = videoArray.getJSONObject(i)
                            if (item.optString("type") == "video") {
                                foundUrl = item.optString("url")
                                break
                            }
                        }
                    }
                    foundUrl
                }

                if (videoUrl.isNotBlank()) {
                    val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://twitter.com/")
                    val videoFormats = listOf(
                        AvailableFormat(
                            key = "tw-video",
                            mode = DownloadMode.VIDEO,
                            formatId = videoUrl,
                            extension = "mp4",
                            height = 1080,
                            formatNote = "MP4 Video",
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

            val videoFormats = listOf(
                AvailableFormat(
                    key = "reddit-video",
                    mode = DownloadMode.VIDEO,
                    formatId = fallbackUrl,
                    companionAudioFormatId = companionAudio,
                    extension = "mp4",
                    height = height,
                    formatNote = if (companionAudio != null) "Full Video & Audio" else "Video (Muted)",
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
                        isQuickPreset = false,
                        httpHeaders = headers,
                    ),
                    AvailableFormat(
                        key = "reddit-mp3",
                        mode = DownloadMode.AUDIO_MP3,
                        formatId = companionAudio,
                        extension = "mp4",
                        formatNote = "MP3 Audio",
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

    private fun unescapeJsonUrl(url: String): String {
        return url.replace("\\/", "/")
            .replace("\\u0025", "%")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }
}
