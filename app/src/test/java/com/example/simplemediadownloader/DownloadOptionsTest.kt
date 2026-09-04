package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadOptionsTest {
    @Test
    fun `video-only format selects its matching companion audio`() {
        val format = AvailableFormat(
            key = "video:137:140",
            mode = DownloadMode.VIDEO,
            formatId = "137",
            companionAudioFormatId = "140",
            extension = "mp4",
            height = 1080,
        )

        assertEquals("137+140/137", DownloadOptions.formatSelector(format))
    }

    @Test
    fun `muxed video selects the exact chosen format`() {
        val format = AvailableFormat(
            key = "video:22:",
            mode = DownloadMode.VIDEO,
            formatId = "22",
            extension = "mp4",
            height = 720,
        )

        assertEquals("22", DownloadOptions.formatSelector(format))
    }

    @Test
    fun `audio format maps its discovered bitrate to yt-dlp`() {
        val format = AvailableFormat(
            key = "audio:251",
            mode = DownloadMode.AUDIO_MP3,
            formatId = "251",
            extension = "webm",
            bitrateKbps = 128,
        )

        assertEquals("251", DownloadOptions.formatSelector(format))
        assertEquals("128K", DownloadOptions.audioBitrate(format))
    }

    @Test
    fun `format size labels distinguish estimates and exact sizes`() {
        val exact = AvailableFormat(
            key = "video:a:",
            mode = DownloadMode.VIDEO,
            formatId = "a",
            extension = "mp4",
            estimatedSizeBytes = 25L * 1024 * 1024,
            sizeIsApproximate = false,
        )
        val estimated = exact.copy(estimatedSizeBytes = 3L * 1024 * 1024 / 2, sizeIsApproximate = true)

        assertEquals("25.0 MB", formatSizeLabel(exact))
        assertEquals("≈ 1.5 MB", formatSizeLabel(estimated))
    }

    @Test
    fun `resolution ladder preserves native qualities without transcoding`() {
        val downloader = YtDlpFormatDiscoveryEngine()
        val native1080 = AvailableFormat(
            key = "video:1080",
            mode = DownloadMode.VIDEO,
            formatId = "1080",
            extension = "mp4",
            width = 1920,
            height = 1080,
            bitrateKbps = 2_500,
            estimatedSizeBytes = 100L * 1024 * 1024,
        )
        val native360 = native1080.copy(
            key = "video:360",
            formatId = "360",
            width = 640,
            height = 360,
            bitrateKbps = 600,
        )

        val ladder = downloader.buildResolutionLadder(listOf(native1080, native360))

        assertEquals(listOf(1080, 360), ladder.map { it.height })
        assertEquals(false, ladder.any(AvailableFormat::requiresDownscale))

        val tinySource = native360.copy(height = 120, sourceHeight = 120)
        assertEquals(listOf(120), downloader.buildResolutionLadder(listOf(tinySource)).map { it.height })
    }
}
