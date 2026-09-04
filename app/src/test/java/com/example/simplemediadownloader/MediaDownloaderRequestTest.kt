package com.example.simplemediadownloader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaDownloaderRequestTest {
    @Test
    fun `quick presets are immediately available and have correct resolutions`() {
        val discoveryEngine = NewPipeFormatDiscoveryEngine()
        val catalog = discoveryEngine.quickFormatCatalog("https://example.test/video")

        assertTrue(catalog.detailsLoading)
        assertTrue(catalog.videoFormats.map { it.height }.containsAll(listOf(2160, 1080, 480, 144)))
        val quick480 = catalog.videoFormats.single { it.height == 480 }
        assertTrue(quick480.isQuickPreset)
        assertEquals(480, quick480.height)
    }

    @Test
    fun `fast video preset is prepared correctly`() {
        val discoveryEngine = NewPipeFormatDiscoveryEngine()
        val fastPreset = discoveryEngine.fastVideoPreset()

        assertTrue(fastPreset.isQuickPreset)
        assertEquals(DownloadMode.VIDEO, fastPreset.mode)
        assertEquals("mp4", fastPreset.extension)
    }

    @Test
    fun `resolution ladder groups and orders formats by height descending`() {
        val discoveryEngine = NewPipeFormatDiscoveryEngine()
        val format1080 = AvailableFormat(
            key = "video-1080",
            mode = DownloadMode.VIDEO,
            formatId = "https://example.test/video1080.mp4",
            extension = "mp4",
            height = 1080,
            bitrateKbps = 5000,
        )
        val format720 = AvailableFormat(
            key = "video-720",
            mode = DownloadMode.VIDEO,
            formatId = "https://example.test/video720.mp4",
            extension = "mp4",
            height = 720,
            bitrateKbps = 2500,
        )

        val ladder = discoveryEngine.buildResolutionLadder(listOf(format720, format1080))
        assertEquals(2, ladder.size)
        assertEquals(1080, ladder[0].height)
        assertEquals(720, ladder[1].height)
    }

    @Test
    fun `okhttp download engine cancellation reports success immediately`() = runBlocking {
        val engine = OkHttpDownloadEngine()
        val cancelled = engine.cancel("test-task-cancel-id")
        assertTrue(cancelled)
    }
}
