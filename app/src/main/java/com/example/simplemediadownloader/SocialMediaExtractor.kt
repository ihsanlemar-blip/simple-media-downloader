package com.example.simplemediadownloader

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Public facade for platform-specific media extractors.
 * Delegates to dedicated platform adapters ([TikTokExtractor], [InstagramExtractor],
 * [FacebookExtractor], [TwitterExtractor], [RedditExtractor], and [CobaltGatewayExtractor]).
 */
object SocialMediaExtractor {

    var gatewayClient: OkHttpClient
        get() = ExtractorSharedUtils.gatewayClient
        set(value) {
            ExtractorSharedUtils.gatewayClient = value
        }

    suspend fun extract(
        client: OkHttpClient,
        url: String,
        platform: String,
        allowThirdPartyGateways: Boolean = false,
    ): FormatDiscoveryResult {
        return try {
            val httpUrl = url.toHttpUrlOrNull()
                ?: return FormatDiscoveryResult.Failure("Invalid URL format.")
            NetworkSecurityPolicy.validateUrl(httpUrl, allowHttp = true)
            val resolvedUrl = ExtractorSharedUtils.followRedirects(client, url)
            val primaryResult = when (platform.lowercase()) {
                "facebook" -> FacebookExtractor.extract(client, resolvedUrl)
                "tiktok" -> TikTokExtractor.extract(client, resolvedUrl, allowThirdPartyGateways)
                "instagram" -> InstagramExtractor.extract(client, resolvedUrl)
                "x", "twitter" -> TwitterExtractor.extract(client, resolvedUrl, allowThirdPartyGateways)
                "reddit" -> RedditExtractor.extract(client, resolvedUrl)
                else -> FormatDiscoveryResult.Failure("Unsupported platform: $platform")
            }

            if (primaryResult is FormatDiscoveryResult.Success) {
                primaryResult
            } else if (allowThirdPartyGateways) {
                // Tier 4: Fallback to universal public media gateway
                val gatewayCatalog = CobaltGatewayExtractor.extract(client, resolvedUrl, platform)
                if (gatewayCatalog != null && gatewayCatalog.videoFormats.isNotEmpty()) {
                    FormatDiscoveryResult.Success(gatewayCatalog)
                } else {
                    primaryResult
                }
            } else {
                primaryResult
            }
        } catch (e: Exception) {
            FormatDiscoveryResult.Failure(
                e.localizedMessage ?: "Could not extract media from this $platform link.",
            )
        }
    }

    fun cleanTitle(raw: String): String = ExtractorSharedUtils.cleanTitle(raw)

    fun extractFacebookId(url: String): String? = FacebookExtractor.extractFacebookId(url)

    fun parseFacebookHtml(client: OkHttpClient, html: String, url: String): MediaFormatCatalog? =
        FacebookExtractor.parseFacebookHtml(client, html, url)

    fun unescapeDashManifest(manifest: String): String =
        FacebookExtractor.unescapeDashManifest(manifest)

    fun parseDashManifest(
        client: OkHttpClient,
        mpdXml: String,
        headers: Map<String, String>,
    ): Pair<List<AvailableFormat>, AvailableFormat?> =
        FacebookExtractor.parseDashManifest(client, mpdXml, headers)
}
