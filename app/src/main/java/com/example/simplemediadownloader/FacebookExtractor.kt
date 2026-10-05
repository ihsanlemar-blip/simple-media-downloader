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

object FacebookExtractor {
    fun extract(client: OkHttpClient, url: String): FormatDiscoveryResult {
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

        // Tier 2: Watch / Reel endpoint with desktop navigation headers
        if (videoId != null && videoId.all { it.isDigit() }) {
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
            if (url.contains("/share/") && resolvedUrl.contains("/share/")) {
                "Facebook could not resolve this shared link. Open it in a browser and copy the direct reel or video URL."
            } else "Could not find a public video stream in this Facebook link. It may be private or restricted.",
        )
    }

    internal fun extractFacebookId(url: String): String? {
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
            videoId != null && videoId.all { it.isDigit() } -> "https://www.facebook.com/watch/?v=$videoId"
            url.contains("/share/") || url.contains("fb.watch") -> {
                val redirected = followRedirects(client, url)
                val id = extractFacebookId(redirected)
                if (id != null && id.all { it.isDigit() }) "https://www.facebook.com/watch/?v=$id" else redirected
            }
            else -> url
        }
        return try {
            val encodedUrl = URLEncoder.encode(canonicalUrl, "UTF-8")
            val embedUrl = "https://www.facebook.com/plugins/video.php?href=$encodedUrl"

            val reqBuilder = Request.Builder().url(embedUrl)
            FACEBOOK_NAV_HEADERS.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

            val catalog = client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.readBoundedString().orEmpty()
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
                val html = response.body?.readBoundedString().orEmpty()
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
                    val html = response.body?.readBoundedString().orEmpty()
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
            "https://www.facebook.com/watch/?v=$videoId",
            "https://www.facebook.com/reel/$videoId/",
            "https://m.facebook.com/watch/?v=$videoId",
        )
        for (endpoint in watchEndpoints) {
            try {
                val reqBuilder = Request.Builder().url(endpoint)
                FACEBOOK_NAV_HEADERS.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val html = response.body?.readBoundedString().orEmpty()
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
            val reqBuilder = Request.Builder().url(url)
            FACEBOOK_NAV_HEADERS.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.readBoundedString().orEmpty()
                parseFacebookHtml(client, html, url)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun unescapeDashManifest(raw: String): String {
        return raw.replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\u003C", "<", ignoreCase = true)
            .replace("\\u003E", ">", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    internal fun parseDashManifest(
        client: OkHttpClient,
        manifestXml: String,
        headers: Map<String, String>,
    ): Pair<List<AvailableFormat>, AvailableFormat?> {
        val videoFormats = mutableListOf<AvailableFormat>()
        var primaryAudioFormat: AvailableFormat? = null

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(manifestXml.toByteArray(Charsets.UTF_8)))
            val repNodes = doc.getElementsByTagName("Representation")

            data class DashVideoRep(
                val width: Int,
                val height: Int,
                val resolutionHeight: Int,
                val bandwidth: Long,
                val url: String,
            )

            val videoReps = mutableListOf<DashVideoRep>()
            var bestAudioUrl: String? = null
            var bestAudioBandwidth = 0L

            for (i in 0 until repNodes.length) {
                val node = repNodes.item(i) as? Element ?: continue
                val mime = node.getAttribute("mimeType")
                val baseUrlNode = node.getElementsByTagName("BaseURL").item(0) as? Element
                val rawUrl = baseUrlNode?.textContent?.trim().orEmpty()
                if (rawUrl.isBlank() || !NetworkSecurityPolicy.isAllowedMediaUrl(rawUrl, allowCleartextHttp = false)) continue

                val bandwidth = node.getAttribute("bandwidth").toLongOrNull() ?: 0L

                if (mime.startsWith("video/")) {
                    val w = node.getAttribute("width").toIntOrNull() ?: 0
                    val h = node.getAttribute("height").toIntOrNull() ?: 0
                    if (w > 0 && h > 0) {
                        val resHeight = if (h > w) w else h
                        videoReps.add(DashVideoRep(w, h, resHeight, bandwidth, rawUrl))
                    }
                } else if (mime.startsWith("audio/")) {
                    if (bandwidth > bestAudioBandwidth || bestAudioUrl == null) {
                        bestAudioBandwidth = bandwidth
                        bestAudioUrl = rawUrl
                    }
                }
            }

            val audioSize = bestAudioUrl?.let { probeStreamSize(client, it, headers) }
            val audioBitrateKbps = if (bestAudioBandwidth > 0) (bestAudioBandwidth / 1000).toInt().coerceIn(32, 320) else 128

            if (bestAudioUrl != null) {
                primaryAudioFormat = AvailableFormat(
                    key = "fb-dash-audio",
                    mode = DownloadMode.AUDIO_ORIGINAL,
                    formatId = bestAudioUrl,
                    extension = "m4a",
                    bitrateKbps = audioBitrateKbps,
                    formatNote = "Original Audio (${audioBitrateKbps} kbps)",
                    estimatedSizeBytes = audioSize,
                    sizeIsApproximate = audioSize == null,
                    isQuickPreset = false,
                    httpHeaders = headers,
                )
            }

            val byResolution = videoReps.groupBy { it.resolutionHeight }
            for ((resHeight, reps) in byResolution.toSortedMap(reverseOrder())) {
                val bestRep = reps.maxByOrNull { it.bandwidth } ?: continue
                val videoSize = probeStreamSize(client, bestRep.url, headers)
                val totalSize = if (videoSize != null && audioSize != null) {
                    videoSize + audioSize
                } else {
                    videoSize ?: audioSize
                }

                val note = when {
                    resHeight >= 1080 -> "1080p HD"
                    resHeight >= 720 -> "720p HD"
                    resHeight in 480..540 -> "${resHeight}p SD"
                    else -> "${resHeight}p"
                }

                videoFormats.add(
                    AvailableFormat(
                        key = "fb-dash-$resHeight",
                        mode = DownloadMode.VIDEO,
                        formatId = bestRep.url,
                        companionAudioFormatId = bestAudioUrl,
                        extension = "mp4",
                        width = bestRep.width,
                        height = resHeight,
                        bitrateKbps = (bestRep.bandwidth / 1000).toInt(),
                        formatNote = if (bestAudioUrl != null) "$note · video + audio" else note,
                        estimatedSizeBytes = totalSize,
                        sizeIsApproximate = totalSize == null,
                        isQuickPreset = false,
                        httpHeaders = headers,
                    )
                )

                // If this resolution has a much lower bandwidth alternative (e.g. 720p Data Saver), expose it
                val saverRep = reps.minByOrNull { it.bandwidth }
                if (saverRep != null && saverRep.bandwidth > 0 && bestRep.bandwidth > 0 && saverRep.bandwidth < (bestRep.bandwidth * 0.75).toLong()) {
                    val saverSize = probeStreamSize(client, saverRep.url, headers)
                    val totalSaverSize = if (saverSize != null && audioSize != null) saverSize + audioSize else (saverSize ?: audioSize)
                    videoFormats.add(
                        AvailableFormat(
                            key = "fb-dash-$resHeight-saver",
                            mode = DownloadMode.VIDEO,
                            formatId = saverRep.url,
                            companionAudioFormatId = bestAudioUrl,
                            extension = "mp4",
                            width = saverRep.width,
                            height = resHeight,
                            bitrateKbps = (saverRep.bandwidth / 1000).toInt(),
                            formatNote = "$note (Data Saver)",
                            estimatedSizeBytes = totalSaverSize,
                            sizeIsApproximate = totalSaverSize == null,
                            isQuickPreset = false,
                            httpHeaders = headers,
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        return Pair(videoFormats, primaryAudioFormat)
    }

    internal fun parseFacebookHtml(client: OkHttpClient, html: String, sourceUrl: String): MediaFormatCatalog? {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://www.facebook.com/",
        )

        val rawTitle = findMetaProperty(html, "og:title")
            ?: extractPattern(html, """<title>([^<]+)</title>""")
            ?: "Facebook Video"
        val title = cleanTitle(rawTitle)

        // 1. Try extracting DASH manifest (provides full resolution ladder and authentic audio track)
        val dashManifestRaw = extractPattern(html, """dash_manifest["']:\s*"((?:[^"\\]|\\.)*)"""")
        val (dashVideoFormats, dashAudioFormat) = if (!dashManifestRaw.isNullOrBlank()) {
            parseDashManifest(client, unescapeDashManifest(dashManifestRaw), headers)
        } else {
            Pair(emptyList(), null)
        }

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

        val validHd = hdUrl?.takeIf { isValidFbStream(it) && NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false) }
        val validSd = sdUrl?.takeIf { isValidFbStream(it) && NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false) }

        if (dashVideoFormats.isEmpty() && validHd.isNullOrBlank() && validSd.isNullOrBlank()) {
            return null
        }

        val hdSize = validHd?.let { probeStreamSize(client, it, headers) }
        val sdSize = validSd?.let { probeStreamSize(client, it, headers) }

        val videoFormats = mutableListOf<AvailableFormat>()
        videoFormats.addAll(dashVideoFormats)

        if (!validHd.isNullOrBlank() && videoFormats.none { it.formatId == validHd }) {
            videoFormats.add(
                AvailableFormat(
                    key = "fb-hd",
                    mode = DownloadMode.VIDEO,
                    formatId = validHd,
                    extension = "mp4",
                    height = 1080,
                    formatNote = if (dashVideoFormats.isNotEmpty()) "1080p HD (Progressive)" else "HD Quality (1080p)",
                    estimatedSizeBytes = hdSize,
                    sizeIsApproximate = hdSize == null,
                    isQuickPreset = false,
                    httpHeaders = headers,
                )
            )
        }
        if (!validSd.isNullOrBlank() && validSd != validHd && videoFormats.none { it.formatId == validSd }) {
            videoFormats.add(
                AvailableFormat(
                    key = "fb-sd",
                    mode = DownloadMode.VIDEO,
                    formatId = validSd,
                    extension = "mp4",
                    height = 720,
                    formatNote = if (dashVideoFormats.isNotEmpty()) "720p SD (Progressive)" else "SD Quality",
                    estimatedSizeBytes = sdSize,
                    sizeIsApproximate = sdSize == null,
                    isQuickPreset = false,
                    httpHeaders = headers,
                )
            )
        }

        if (videoFormats.isEmpty()) return null

        val audioFormats = mutableListOf<AvailableFormat>()
        if (dashAudioFormat != null) {
            audioFormats.add(dashAudioFormat)
        } else {
            val primaryStreamUrl = validHd ?: validSd
            if (primaryStreamUrl != null) {
                val primarySize = hdSize ?: sdSize
                val estimatedAudioBytes = primarySize?.let { (it * 0.08).toLong().coerceAtLeast(64 * 1024L) }
                audioFormats.add(
                    AvailableFormat(
                        key = "fb-audio-extract",
                        mode = DownloadMode.AUDIO_ORIGINAL,
                        formatId = primaryStreamUrl,
                        extension = "m4a",
                        formatNote = "Video source (audio extracted)",
                        estimatedSizeBytes = estimatedAudioBytes,
                        sizeIsApproximate = true,
                        isQuickPreset = false,
                        httpHeaders = headers,
                    ),
                )
            }
        }

        val thumbUrl = findMetaProperty(html, "og:image")
            ?: findMetaProperty(html, "twitter:image")
        val safeThumbUrl = thumbUrl?.let { unescapeJsonUrl(it) }?.takeIf {
            NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false)
        }
        val author = extractPattern(html, """"owner"\s*:\s*\{[^}]*"name"\s*:\s*"([^"]+)"""")
            ?: extractPattern(html, """"author"\s*:\s*\{[^}]*"name"\s*:\s*"([^"]+)"""")

        return MediaFormatCatalog(
            sourceUrl = sourceUrl,
            title = title,
            videoFormats = videoFormats,
            audioFormats = audioFormats,
            author = author?.let { cleanTitle(it) },
            thumbnailUrl = safeThumbUrl,
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
}
