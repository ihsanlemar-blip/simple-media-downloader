package com.example.simplemediadownloader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.regex.Pattern

object SocialMediaExtractor {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val CRAWLER_USER_AGENT =
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    private const val WHATSAPP_USER_AGENT =
        "WhatsApp/2.21.12.21 A"
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

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
                // Tier 4: Seamless fallback to universal public media gateway
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

        redirectClient.newCall(request).execute().use { response ->
            currentUrl = response.request.url.toString()
        }
        return currentUrl
    }

    private fun extractFacebook(client: OkHttpClient, url: String): FormatDiscoveryResult {
        val videoId = extractFacebookId(url)

        // Tier 1: Facebook Video Plugin Embed (Official public player returning direct fbcdn.net MP4s)
        val embedCatalog = tryFacebookPluginEmbed(client, url, videoId)
        if (embedCatalog != null && embedCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(embedCatalog)
        }

        // Tier 2: Mobile Watch & Desktop Watch endpoints
        if (videoId != null) {
            val watchCatalog = tryFacebookWatch(client, videoId, url)
            if (watchCatalog != null && watchCatalog.videoFormats.isNotEmpty()) {
                return FormatDiscoveryResult.Success(watchCatalog)
            }
        }

        // Tier 3: Social Crawler Impersonation (facebookexternalhit & WhatsApp)
        val crawlerCatalog = tryFacebookCrawler(client, url)
        if (crawlerCatalog != null && crawlerCatalog.videoFormats.isNotEmpty()) {
            return FormatDiscoveryResult.Success(crawlerCatalog)
        }

        // Tier 4: Direct desktop web scraping with script regexes
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
            val encodedUrl = java.net.URLEncoder.encode(canonicalUrl, "UTF-8")
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
            } catch (_: Exception) {
                // Ignore and try next
            }
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
            } catch (_: Exception) {
                // Ignore and try next
            }
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

        val videoFormats = mutableListOf<AvailableFormat>()
        if (!validHd.isNullOrBlank()) {
            videoFormats.add(
                AvailableFormat(
                    key = "fb-hd",
                    mode = DownloadMode.VIDEO,
                    formatId = validHd,
                    extension = "mp4",
                    height = 1080,
                    formatNote = "HD Quality (720p/1080p)",
                    isQuickPreset = false,
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
            ),
            AvailableFormat(
                key = "fb-mp3",
                mode = DownloadMode.AUDIO_MP3,
                formatId = primaryStreamUrl,
                extension = "mp4",
                formatNote = "MP3 audio",
                isQuickPreset = false,
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
        return candidate.take(80).trim().ifBlank { "Facebook Video" }
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

    private fun tryPublicGatewayExtraction(
        client: OkHttpClient,
        url: String,
        platform: String,
    ): MediaFormatCatalog? {
        val gateways = listOf(
            "https://api.cobalt.tools/",
            "https://api.cobalt.tools/api/json",
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
                            filename.substringBeforeLast('.')
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
                                formatNote = "HD Quality (Gateway)",
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
            } catch (_: Exception) {
                // Continue to next gateway
            }
        }
        return null
    }

    private fun extractTikTok(client: OkHttpClient, url: String): FormatDiscoveryResult {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return FormatDiscoveryResult.Failure("TikTok request returned HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }

        val videoUrl = findMetaProperty(html, "og:video")
            ?: findMetaProperty(html, "og:video:url")
            ?: findMetaProperty(html, "og:video:secure_url")
            ?: extractPattern(html, """"playAddr"\s*:\s*"([^"]+)"""")
            ?: extractPattern(html, """"downloadAddr"\s*:\s*"([^"]+)"""")

        if (videoUrl.isNullOrBlank()) {
            return FormatDiscoveryResult.Failure(
                "Could not extract video stream from this TikTok link.",
            )
        }

        val cleanVideoUrl = unescapeJsonUrl(videoUrl)
        val title = findMetaProperty(html, "og:title") ?: "TikTok Video"

        val videoFormats = listOf(
            AvailableFormat(
                key = "tiktok-video",
                mode = DownloadMode.VIDEO,
                formatId = cleanVideoUrl,
                extension = "mp4",
                height = 1080,
                formatNote = "HD Video",
                isQuickPreset = false,
            )
        )
        val audioFormats = listOf(
            AvailableFormat(
                key = "tiktok-audio",
                mode = DownloadMode.AUDIO_ORIGINAL,
                formatId = cleanVideoUrl,
                extension = "mp4",
                formatNote = "Original sound",
                isQuickPreset = false,
            )
        )

        return FormatDiscoveryResult.Success(
            MediaFormatCatalog(
                sourceUrl = url,
                title = title,
                videoFormats = videoFormats,
                audioFormats = audioFormats,
            )
        )
    }

    private fun extractInstagram(client: OkHttpClient, url: String): FormatDiscoveryResult {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return FormatDiscoveryResult.Failure("Instagram request returned HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }

        val videoUrl = findMetaProperty(html, "og:video")
            ?: findMetaProperty(html, "og:video:secure_url")
            ?: extractPattern(html, """"video_url"\s*:\s*"([^"]+)"""")

        if (videoUrl.isNullOrBlank()) {
            return FormatDiscoveryResult.Failure(
                "Could not extract video from this Instagram post. Login may be required.",
            )
        }

        val cleanVideoUrl = unescapeJsonUrl(videoUrl)
        val title = findMetaProperty(html, "og:title") ?: "Instagram Video"

        val videoFormats = listOf(
            AvailableFormat(
                key = "ig-video",
                mode = DownloadMode.VIDEO,
                formatId = cleanVideoUrl,
                extension = "mp4",
                height = 1080,
                formatNote = "Best quality",
                isQuickPreset = false,
            )
        )
        val audioFormats = listOf(
            AvailableFormat(
                key = "ig-audio",
                mode = DownloadMode.AUDIO_ORIGINAL,
                formatId = cleanVideoUrl,
                extension = "mp4",
                formatNote = "Original audio",
                isQuickPreset = false,
            )
        )

        return FormatDiscoveryResult.Success(
            MediaFormatCatalog(
                sourceUrl = url,
                title = title,
                videoFormats = videoFormats,
                audioFormats = audioFormats,
            )
        )
    }

    private fun extractTwitter(client: OkHttpClient, url: String): FormatDiscoveryResult {
        val tweetId = url.substringBefore('?').substringAfterLast('/')
        val apiEndpoint = "https://api.vxtwitter.com/Twitter/status/$tweetId"

        val request = Request.Builder()
            .url(apiEndpoint)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        val jsonString = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return FormatDiscoveryResult.Failure("Twitter/X API returned HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }

        val json = JSONObject(jsonString)
        val mediaUrl = json.optString("media_url", "")
        val mediaType = json.optString("mediaType", "")
        val text = json.optString("text", "Twitter Video")

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

        if (videoUrl.isBlank()) {
            return FormatDiscoveryResult.Failure("No downloadable video found in this Tweet.")
        }

        val videoFormats = listOf(
            AvailableFormat(
                key = "tw-video",
                mode = DownloadMode.VIDEO,
                formatId = videoUrl,
                extension = "mp4",
                height = 1080,
                formatNote = "MP4 Video",
                isQuickPreset = false,
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
            )
        )

        return FormatDiscoveryResult.Success(
            MediaFormatCatalog(
                sourceUrl = url,
                title = text.take(60),
                videoFormats = videoFormats,
                audioFormats = audioFormats,
            )
        )
    }

    private fun extractReddit(client: OkHttpClient, url: String): FormatDiscoveryResult {
        val cleanUrl = url.substringBefore('?').trimEnd('/')
        val jsonUrl = "$cleanUrl.json"

        val request = Request.Builder()
            .url(jsonUrl)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        val jsonString = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return FormatDiscoveryResult.Failure("Reddit returned HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }

        val jsonArray = org.json.JSONArray(jsonString)
        val post = jsonArray.getJSONObject(0)
            .getJSONObject("data")
            .getJSONArray("children")
            .getJSONObject(0)
            .getJSONObject("data")

        val title = post.optString("title", "Reddit Video")
        val secureMedia = post.optJSONObject("secure_media")
            ?: post.optJSONObject("media")
        val redditVideo = secureMedia?.optJSONObject("reddit_video")

        val fallbackUrl = redditVideo?.optString("fallback_url")
        if (fallbackUrl.isNullOrBlank()) {
            return FormatDiscoveryResult.Failure("No Reddit hosted video found in this post.")
        }

        val height = redditVideo.optInt("height", 720)
        val videoFormats = listOf(
            AvailableFormat(
                key = "reddit-video",
                mode = DownloadMode.VIDEO,
                formatId = fallbackUrl,
                extension = "mp4",
                height = height,
                formatNote = "Reddit Video",
                isQuickPreset = false,
            )
        )
        val audioFormats = listOf(
            AvailableFormat(
                key = "reddit-audio",
                mode = DownloadMode.AUDIO_ORIGINAL,
                formatId = fallbackUrl,
                extension = "mp4",
                formatNote = "Audio",
                isQuickPreset = false,
            )
        )

        return FormatDiscoveryResult.Success(
            MediaFormatCatalog(
                sourceUrl = url,
                title = title,
                videoFormats = videoFormats,
                audioFormats = audioFormats,
            )
        )
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
