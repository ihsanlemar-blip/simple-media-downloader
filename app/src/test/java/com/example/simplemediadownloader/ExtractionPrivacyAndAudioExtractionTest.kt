package com.example.simplemediadownloader

import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ExtractionPrivacyAndAudioExtractionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `gatewayClient uses NO_COOKIES to prevent cookie leakage`() {
        assertEquals(CookieJar.NO_COOKIES, SocialMediaExtractor.gatewayClient.cookieJar)
    }

    @Test
    fun `extract with allowThirdPartyGateways false makes 0 calls to third-party gateways for Twitter`() = runBlocking {
        val gatewayCallCount = AtomicInteger(0)
        val interceptor = Interceptor { chain ->
            val host = chain.request().url.host.lowercase()
            if (host.contains("fxtwitter") || host.contains("fixupx") || host.contains("cobalt") || host.contains("tikwm")) {
                gatewayCallCount.incrementAndGet()
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val testClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        val previousGatewayClient = SocialMediaExtractor.gatewayClient
        SocialMediaExtractor.gatewayClient = testClient
        try {
            val result = SocialMediaExtractor.extract(
                client = testClient,
                url = "https://twitter.com/jack/status/20",
                platform = "x",
                allowThirdPartyGateways = false,
            )

            assertTrue(result is FormatDiscoveryResult.Failure)
            val failure = result as FormatDiscoveryResult.Failure
            assertTrue(failure.message.contains("Enable third-party extractors in Settings", ignoreCase = true))
            assertEquals("Must not contact any third-party gateway when disabled", 0, gatewayCallCount.get())
        } finally {
            SocialMediaExtractor.gatewayClient = previousGatewayClient
        }
    }

    @Test
    fun `extract with allowThirdPartyGateways false makes 0 calls to TikWM or Cobalt for TikTok when direct scrape fails`() = runBlocking {
        val gatewayCallCount = AtomicInteger(0)
        val interceptor = Interceptor { chain ->
            val host = chain.request().url.host.lowercase()
            if (host.contains("tikwm") || host.contains("cobalt")) {
                gatewayCallCount.incrementAndGet()
            }
            // Direct TikTok web scrape returns 404
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("<html><body>Not found</body></html>".toResponseBody("text/html".toMediaType()))
                .build()
        }

        val testClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        val previousGatewayClient = SocialMediaExtractor.gatewayClient
        SocialMediaExtractor.gatewayClient = testClient
        try {
            val result = SocialMediaExtractor.extract(
                client = testClient,
                url = "https://www.tiktok.com/@creator/video/7123456789012345678",
                platform = "tiktok",
                allowThirdPartyGateways = false,
            )

            assertTrue(result is FormatDiscoveryResult.Failure)
            val failure = result as FormatDiscoveryResult.Failure
            assertTrue(
                "Failure should explain fallback is disabled: ${failure.message}",
                failure.message.contains("Third-party fallback is disabled in settings", ignoreCase = true),
            )
            assertEquals("Must not contact TikWM or Cobalt when allowThirdPartyGateways is false", 0, gatewayCallCount.get())
        } finally {
            SocialMediaExtractor.gatewayClient = previousGatewayClient
        }
    }

    @Test
    fun `extractAudioTrack aborts immediately and cleans output when cancelled`() {
        val sourceFile = tempFolder.newFile("sample_video.mp4")
        val outputFile = tempFolder.newFile("sample_audio.m4a")

        sourceFile.writeBytes(ByteArray(2048))

        val success = MediaStreamMuxer.extractAudioTrack(
            sourceFile = sourceFile,
            outputFile = outputFile,
            isCancelled = { true },
        )

        assertFalse(success)
        assertFalse("Output file must be deleted when cancelled", outputFile.exists())
    }

    @Test
    fun `extractAudioTrack cleans output file on invalid or corrupted source file`() {
        val sourceFile = tempFolder.newFile("corrupted_video.mp4")
        val outputFile = tempFolder.newFile("corrupted_audio.m4a")

        // Corrupted garbage content with no audio track
        sourceFile.writeBytes(ByteArray(512))

        val success = MediaStreamMuxer.extractAudioTrack(
            sourceFile = sourceFile,
            outputFile = outputFile,
            isCancelled = { false },
        )

        assertFalse(success)
        assertFalse("Output file must be deleted when extraction fails", outputFile.exists())
    }

    @Test
    fun `audio options are honestly labeled and do not duplicate synthetic mp3`() {
        val engine = NewPipeFormatDiscoveryEngine()
        val quickCatalog = engine.quickFormatCatalog("https://www.youtube.com/watch?v=test12345")

        // None of the audio formats should have duplicate synthetic mp3 extension
        for (audio in quickCatalog.audioFormats) {
            if (audio.mode == DownloadMode.AUDIO_ORIGINAL) {
                assertEquals("m4a", audio.extension)
            }
        }
        // No duplicate entries with identical bitrates
        val bitrates = quickCatalog.audioFormats.map { it.bitrateKbps }
        assertEquals(bitrates.distinct().size, bitrates.size)
    }

    @Test
    fun `ensureFfmpeg is not present on SimpleMediaDownloaderApp`() {
        val appClass = SimpleMediaDownloaderApp::class.java
        val ffmpegMethod = appClass.methods.firstOrNull { it.name == "ensureFfmpeg" }
        assertTrue("ensureFfmpeg stub must be completely removed from SimpleMediaDownloaderApp", ffmpegMethod == null)
    }
}
