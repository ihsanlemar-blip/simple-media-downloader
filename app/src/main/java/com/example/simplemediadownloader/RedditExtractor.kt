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

object RedditExtractor {
    fun extract(client: OkHttpClient, url: String): FormatDiscoveryResult {
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
                response.body?.readBoundedString().orEmpty()
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
            if (fallbackUrl.isNullOrBlank() || !NetworkSecurityPolicy.isAllowedMediaUrl(fallbackUrl, allowCleartextHttp = false)) {
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
            }?.takeIf { NetworkSecurityPolicy.isAllowedMediaUrl(it, allowCleartextHttp = false) }

            val headers = mapOf("User-Agent" to USER_AGENT)
            val audioSize = companionAudio?.let { probeStreamSize(client, it, headers) }

            val videoFormats = mutableListOf<AvailableFormat>()
            if (fallbackUrl.contains("DASH_")) {
                val basePrefix = fallbackUrl.substringBefore("DASH_")
                val queryParams = if (fallbackUrl.contains("?")) "?" + fallbackUrl.substringAfter("?") else ""
                val resolutionCandidates = listOf(1080, 720, 480, 360, 240)

                for (res in resolutionCandidates) {
                    val candidateUrl = "${basePrefix}DASH_${res}.mp4$queryParams"
                    val isPrimary = (res == height || candidateUrl == fallbackUrl)
                    if (NetworkSecurityPolicy.isAllowedMediaUrl(candidateUrl, allowCleartextHttp = false) &&
                        (isPrimary || checkUrlExists(client, candidateUrl))
                    ) {
                        val streamUrl = if (isPrimary) fallbackUrl else candidateUrl
                        val vSize = probeStreamSize(client, streamUrl, headers)
                        val totalSize = if (vSize != null && audioSize != null) vSize + audioSize else (vSize ?: audioSize)
                        videoFormats.add(
                            AvailableFormat(
                                key = "reddit-video-$res",
                                mode = DownloadMode.VIDEO,
                                formatId = streamUrl,
                                companionAudioFormatId = companionAudio,
                                extension = "mp4",
                                height = res,
                                formatNote = "${res}p" + (if (companionAudio != null) " (Audio muxed)" else " (Muted)"),
                                estimatedSizeBytes = totalSize,
                                sizeIsApproximate = totalSize == null,
                                isQuickPreset = false,
                                httpHeaders = headers,
                            )
                        )
                    }
                }
            }

            if (videoFormats.isEmpty()) {
                val videoSize = probeStreamSize(client, fallbackUrl, headers)
                val totalSize = if (videoSize != null && audioSize != null) videoSize + audioSize else (videoSize ?: audioSize)
                videoFormats.add(
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
            }

            val audioFormats = if (companionAudio != null) {
                listOf(
                    AvailableFormat(
                        key = "reddit-audio",
                        mode = DownloadMode.AUDIO_ORIGINAL,
                        formatId = companionAudio,
                        extension = "m4a",
                        formatNote = "Original Audio",
                        estimatedSizeBytes = audioSize,
                        sizeIsApproximate = audioSize == null,
                        isQuickPreset = false,
                        httpHeaders = headers,
                    ),
                )
            } else {
                val primaryVideo = videoFormats.firstOrNull()
                val primarySize = primaryVideo?.estimatedSizeBytes
                val estimatedAudioBytes = primarySize?.let { (it * 0.08).toLong().coerceAtLeast(64 * 1024L) }
                listOf(
                    AvailableFormat(
                        key = "reddit-audio-extract",
                        mode = DownloadMode.AUDIO_ORIGINAL,
                        formatId = fallbackUrl,
                        extension = "m4a",
                        formatNote = "Video source (audio extracted)",
                        estimatedSizeBytes = estimatedAudioBytes,
                        sizeIsApproximate = true,
                        isQuickPreset = false,
                        httpHeaders = headers,
                    ),
                )
            }

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
}
