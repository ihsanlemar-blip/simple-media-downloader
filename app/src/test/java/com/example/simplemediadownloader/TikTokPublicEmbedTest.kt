package com.example.simplemediadownloader

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TikTokPublicEmbedTest {
    private fun fixture(name: String) = javaClass.getResource("/profiles/$name")!!.readText()
    @Test fun `public embed exposes real post source audio and duration without mistaking source for output`() {
        val catalog = TikTokPublicEmbed.catalog(fixture("tiktok-embed-video.html"), "https://www.tiktok.com/@teacher/video/1", "1")!!
        assertEquals(33L, catalog.durationSeconds); assertEquals(768, catalog.videoFormats.single().height)
        assertEquals("mp4", catalog.audioFormats.single().sourceExtension); assertEquals("m4a", catalog.audioFormats.single().outputExtension)
        val mp3 = AudioFormatOptions.augment(catalog).audioFormats.filter { it.mode == DownloadMode.AUDIO_MP3 }
        assertEquals(listOf(128, 192, 256, 320), mp3.map { it.targetAudioBitrateKbps }.sorted())
        assertTrue(mp3.all { it.formatId == catalog.videoFormats.single().formatId && it.requiresAudioTranscode })
        assertNull(TikTokPublicEmbed.catalog(fixture("tiktok-embed-video.html"), "https://www.tiktok.com/@teacher/video/2", "2"))
        assertNull(TikTokPublicEmbed.catalog(fixture("tiktok-embed-video.html").replace("\"id\": \"1\"", "\"id\": \"2\""), "https://www.tiktok.com/@teacher/video/1", "1"))
    }
    @Test fun `empty or blocked profile API falls back to official embed without any gateway`() = runBlocking {
        for (api in listOf("", "{\"statusCode\":10221}")) {
            val urls = mutableListOf<String>()
            val http = ProfileHttpClient(OkHttpClient(), setOf("www.tiktok.com"), transport = ProfileTransport { url, _ ->
                urls += url
                ProfileResponse(200, when { url.contains("/api/") -> api; url.contains("/embed/") -> fixture("tiktok-embed-profile.html")
                    else -> "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\">{\"__DEFAULT_SCOPE__\":{\"webapp.user-detail\":{\"userInfo\":{\"user\":{\"secUid\":\"public\",\"privateAccount\":false}}}}}</script>" })
            }, wait = {})
            val source = TikTokProfileExtractor(http)
            val page = source.getItems("https://www.tiktok.com/@teacher", 20)
            assertEquals(listOf("1", "2"), page.items.map { it.id }); assertFalse(page.hasMore)
            assertNull(page.items.first().durationSeconds); assertNull(page.items.first().publishedAtSeconds)
            assertTrue(urls.all { it.startsWith("https://www.tiktok.com/") }); assertTrue(page.notice!!.contains("limited"))
        }
    }
    @Test fun `private public embed never reaches enabled gateway`() = runBlocking {
        var gatewayCalls = 0
        val direct = ProfileHttpClient(OkHttpClient(), setOf("www.tiktok.com"), transport = ProfileTransport { url, _ ->
            ProfileResponse(200, if (url.contains("/embed/")) fixture("tiktok-embed-profile.html").replace("\"privateAccount\": false", "\"privateAccount\": true")
                else if (url.contains("/api/")) "" else "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\">{\"__DEFAULT_SCOPE__\":{\"webapp.user-detail\":{\"userInfo\":{\"user\":{\"secUid\":\"public\",\"privateAccount\":false}}}}}</script>")
        }, wait = {})
        val gateway = ProfileHttpClient(OkHttpClient(), setOf("www.tikwm.com"), transport = ProfileTransport { _, _ -> gatewayCalls++; ProfileResponse(200, "{}") })
        try { TikTokProfileExtractor(direct, gateway) { true }.getInfo("https://www.tiktok.com/@teacher"); fail() } catch (error: ProfileDiscoveryException) { assertTrue(error.message!!.contains("private")) }
        assertEquals(0, gatewayCalls)
    }
    @Test fun `single post uses official embed when web media is denied and Facebook shared failure asks for canonical link`() {
        var deniedProbes = 0
        val web = """<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__">{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"1","desc":"Lesson","video":{"playAddr":"https://cdn.example.test/denied.mp4","height":768}}}}}}</script>"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/embed/v2/1" -> fixture("tiktok-embed-video.html")
                "/@teacher/video/1" -> web
                else -> ""
            }
            val denied = request.url.host == "cdn.example.test"
            if (denied) deniedProbes++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (denied) 403 else if (body.isNotEmpty()) 200 else 400).message("Fixture")
                .body(body.toResponseBody("text/html".toMediaType())).build()
        }.build()
        val result = TikTokExtractor.extract(client, "https://www.tiktok.com/@teacher/video/1", false) as FormatDiscoveryResult.Success
        assertEquals("tiktok-embed-0", result.catalog.videoFormats.single().key)
        assertTrue("Denied web source must be checked before embed fallback", deniedProbes > 0)
        val facebook = FacebookExtractor.extract(client, "https://www.facebook.com/share/r/1btoauPFZC/") as FormatDiscoveryResult.Failure
        assertTrue(facebook.message.contains("direct reel"))
        assertEquals(SourceUrlType.SINGLE_MEDIA, SourceUrlClassifier.classify("https://www.facebook.com/share/r/1btoauPFZC/"))
    }
}
