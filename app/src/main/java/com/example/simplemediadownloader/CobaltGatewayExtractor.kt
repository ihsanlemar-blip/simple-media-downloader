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

object CobaltGatewayExtractor {
    fun extract(
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

                gatewayClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyString = response.body?.readBoundedString().orEmpty()
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

                    if (directUrl.isNotBlank() && NetworkSecurityPolicy.isAllowedMediaUrl(directUrl, allowCleartextHttp = false)) {
                        val title = if (filename.isNotBlank()) {
                            cleanTitle(filename.substringBeforeLast('.'))
                        } else {
                            "$platform Video"
                        }

                        val streamSize = probeStreamSize(client, directUrl, emptyMap())
                        val estimatedAudioBytes = streamSize?.let { (it * 0.08).toLong().coerceAtLeast(64 * 1024L) }

                        val videoFormats = listOf(
                            AvailableFormat(
                                key = "$platform-gateway-hd",
                                mode = DownloadMode.VIDEO,
                                formatId = directUrl,
                                extension = "mp4",
                                height = 1080,
                                formatNote = "HD Quality",
                                estimatedSizeBytes = streamSize,
                                sizeIsApproximate = streamSize == null,
                                isQuickPreset = false,
                            )
                        )
                        val audioFormats = listOf(
                            AvailableFormat(
                                key = "$platform-gateway-audio",
                                mode = DownloadMode.AUDIO_ORIGINAL,
                                formatId = directUrl,
                                extension = "m4a",
                                formatNote = "Video source (audio extracted)",
                                estimatedSizeBytes = estimatedAudioBytes,
                                sizeIsApproximate = true,
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

}
