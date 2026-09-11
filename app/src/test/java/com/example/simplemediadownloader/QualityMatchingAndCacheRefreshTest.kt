package com.example.simplemediadownloader

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QualityMatchingAndCacheRefreshTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `invalidate bypasses cache and calls delegate again`() = runBlocking {
        val delegate = CountingDiscoveryEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = CachingFormatDiscoveryEngine(
            delegate = delegate,
            scope = scope,
            ttlMillis = 60_000L,
        )
        try {
            val url = "https://example.test/video1"

            // 1. First discovery calls delegate
            val res1 = engine.discoverFormats(url)
            assertTrue(res1 is FormatDiscoveryResult.Success)
            assertEquals(1, delegate.discoverCalls.get())

            // 2. Second discovery uses cache without calling delegate
            val res2 = engine.discoverFormats(url)
            assertTrue(res2 is FormatDiscoveryResult.Success)
            assertEquals(1, delegate.discoverCalls.get())

            // 3. Invalidate cache
            engine.invalidate(url)
            assertEquals(1, delegate.invalidateCalls.get())

            // 4. Third discovery bypasses cache and calls delegate again
            val res3 = engine.discoverFormats(url)
            assertTrue(res3 is FormatDiscoveryResult.Success)
            assertEquals(2, delegate.discoverCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `refreshFormats invalidates cache and calls delegate`() = runBlocking {
        val delegate = CountingDiscoveryEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = CachingFormatDiscoveryEngine(
            delegate = delegate,
            scope = scope,
            ttlMillis = 60_000L,
        )
        try {
            val url = "https://example.test/video2"

            engine.discoverFormats(url)
            assertEquals(1, delegate.discoverCalls.get())

            val refreshed = engine.refreshFormats(url)
            assertTrue(refreshed is FormatDiscoveryResult.Success)
            assertEquals(2, delegate.discoverCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `requested 480p with only 360p and 1080p available chooses 360p and never 1080p`() {
        val catalog = MediaFormatCatalog(
            sourceUrl = "https://example.test/watch",
            title = "Test Video",
            videoFormats = listOf(
                videoFormat(height = 1080, key = "video-1080"),
                videoFormat(height = 360, key = "video-360"),
            ),
            audioFormats = emptyList(),
        )

        val result = selectBestVideoFormat(catalog, requestedHeight = 480)
        assertTrue(result is VideoMatchResult.Match)
        val matched = (result as VideoMatchResult.Match).format
        assertEquals(360, matched.height)
        assertEquals("video-360", matched.key)
    }

    @Test
    fun `requested 480p with exact match chooses 480p`() {
        val catalog = MediaFormatCatalog(
            sourceUrl = "https://example.test/watch",
            title = "Test Video",
            videoFormats = listOf(
                videoFormat(height = 1080, key = "video-1080"),
                videoFormat(height = 480, key = "video-480"),
                videoFormat(height = 360, key = "video-360"),
            ),
            audioFormats = emptyList(),
        )

        val result = selectBestVideoFormat(catalog, requestedHeight = 480)
        assertTrue(result is VideoMatchResult.Match)
        val matched = (result as VideoMatchResult.Match).format
        assertEquals(480, matched.height)
        assertEquals("video-480", matched.key)
    }

    @Test
    fun `requested 480p when only higher resolutions exist reports OnlyHigherResolutionsExist`() {
        val catalog = MediaFormatCatalog(
            sourceUrl = "https://example.test/watch",
            title = "Test Video",
            videoFormats = listOf(
                videoFormat(height = 720, key = "video-720"),
                videoFormat(height = 1080, key = "video-1080"),
            ),
            audioFormats = emptyList(),
        )

        val result = selectBestVideoFormat(catalog, requestedHeight = 480)
        assertTrue(result is VideoMatchResult.OnlyHigherResolutionsExist)
        val higher = result as VideoMatchResult.OnlyHigherResolutionsExist
        assertEquals(480, higher.requestedHeight)
        assertEquals(listOf(720, 1080), higher.availableHeights)
    }

    @Test
    fun `audio preset selects closest bitrate`() {
        val catalog = MediaFormatCatalog(
            sourceUrl = "https://example.test/audio",
            title = "Test Audio",
            videoFormats = emptyList(),
            audioFormats = listOf(
                audioFormat(bitrate = 128, key = "audio-128"),
                audioFormat(bitrate = 320, key = "audio-320"),
            ),
        )

        val matched = selectBestAudioFormat(
            catalog = catalog,
            requestedMode = DownloadMode.AUDIO_MP3,
            requestedBitrateKbps = 192,
        )
        assertNotNull(matched)
        assertEquals(128, matched!!.bitrateKbps)
        assertEquals("audio-128", matched.key)
    }

    @Test
    fun `HTTP 403 during download triggers discovery cache invalidation and retry with fresh URL`() = runBlocking {
        val expiredUrl = "https://example.test/stream/expired"
        val freshUrl = "https://example.test/stream/fresh"

        val bodyData = ByteArray(2048).apply {
            "....ftypisom".toByteArray().copyInto(this, 0)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val urlStr = req.url.toString()
                if (urlStr.contains("expired")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body("Forbidden".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Length", bodyData.size.toString())
                        .header("Content-Type", "video/mp4")
                        .body(bodyData.toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            }
            .build()

        var discoveryInvalidated = false
        val discoveryEngine = object : FormatDiscoveryEngine {
            override fun quickFormatCatalog(url: String) = catalog(url, expiredUrl)
            override fun fastVideoPreset() = videoFormat(720, "video-720", expiredUrl)
            override fun invalidate(url: String) {
                discoveryInvalidated = true
            }
            override suspend fun discoverFormats(url: String): FormatDiscoveryResult {
                return FormatDiscoveryResult.Success(catalog(url, freshUrl))
            }
        }

        val engine = OkHttpDownloadEngine(
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
            client = client,
            discoveryEngine = discoveryEngine,
        )

        val outputDir = temporaryFolder.newFolder("out")
        val request = DownloadRequest(
            id = "test-task-403",
            url = "https://example.test/source",
            title = "Test 403 Video",
            format = videoFormat(720, "video-720", expiredUrl),
        )

        val result = engine.download(request, outputDir) {}

        assertTrue(discoveryInvalidated)
        assertTrue(result is DownloadExecutionResult.Success)
    }

    private fun videoFormat(height: Int, key: String, url: String = "https://example.test/v/$height") = AvailableFormat(
        key = key,
        mode = DownloadMode.VIDEO,
        formatId = url,
        extension = "mp4",
        height = height,
    )

    private fun audioFormat(bitrate: Int, key: String, url: String = "https://example.test/a/$bitrate") = AvailableFormat(
        key = key,
        mode = DownloadMode.AUDIO_MP3,
        formatId = url,
        extension = "mp3",
        bitrateKbps = bitrate,
    )

    private fun catalog(sourceUrl: String, videoUrl: String) = MediaFormatCatalog(
        sourceUrl = sourceUrl,
        title = "Catalog Video",
        videoFormats = listOf(videoFormat(720, "video-720", videoUrl)),
        audioFormats = emptyList(),
    )

    private class CountingDiscoveryEngine : FormatDiscoveryEngine {
        val discoverCalls = AtomicInteger()
        val invalidateCalls = AtomicInteger()

        override fun quickFormatCatalog(url: String) = catalog(url)
        override fun fastVideoPreset() = catalog("fast").videoFormats.single()

        override fun invalidate(url: String) {
            invalidateCalls.incrementAndGet()
        }

        override suspend fun discoverFormats(url: String): FormatDiscoveryResult {
            discoverCalls.incrementAndGet()
            return FormatDiscoveryResult.Success(catalog(url))
        }

        private fun catalog(url: String) = MediaFormatCatalog(
            sourceUrl = url,
            title = "Example",
            videoFormats = listOf(
                AvailableFormat(
                    key = "video",
                    mode = DownloadMode.VIDEO,
                    formatId = "18",
                    extension = "mp4",
                    height = 360,
                ),
            ),
            audioFormats = emptyList(),
        )
    }
}
