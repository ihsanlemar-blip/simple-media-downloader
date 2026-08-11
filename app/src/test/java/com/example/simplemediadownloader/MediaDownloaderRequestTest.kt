package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaDownloaderRequestTest {
    @Test
    fun `request enables official EJS components and separates output variants`() {
        val downloader = MediaDownloader()
        val output = File("build/test-downloads")
        val videoFormat = AvailableFormat(
            key = "video:136:140",
            mode = DownloadMode.VIDEO,
            formatId = "136",
            companionAudioFormatId = "140",
            extension = "mp4",
            height = 720,
        )
        val audioFormat = AvailableFormat(
            key = "audio:251",
            mode = DownloadMode.AUDIO_MP3,
            formatId = "251",
            extension = "webm",
            bitrateKbps = 128,
        )

        val videoCommand = downloader.buildRequest(
            url = "https://www.youtube.com/watch?v=test",
            format = videoFormat,
            outputDirectory = output,
        ).buildCommand()
        val audioCommand = downloader.buildRequest(
            url = "https://www.youtube.com/watch?v=test",
            format = audioFormat,
            outputDirectory = output,
        ).buildCommand()

        assertTrue(videoCommand.windowed(2).contains(listOf("--remote-components", "ejs:github")))
        assertTrue(videoCommand.windowed(2).contains(listOf("--format", "136+140/136")))
        assertTrue(videoCommand.contains("--progress"))
        assertTrue(videoCommand.contains("--no-quiet"))
        assertTrue(videoCommand.contains("--no-color"))
        assertTrue(videoCommand.indexOf("--progress") > videoCommand.indexOf("--print"))
        assertTrue(videoCommand.indexOf("--no-quiet") > videoCommand.indexOf("--print"))
        assertTrue(videoCommand.any { it.contains("video-720p-136") })
        assertTrue(audioCommand.windowed(2).contains(listOf("--format", "251")))
        assertTrue(audioCommand.any { it.contains("audio-128k-251") })
    }

    @Test
    fun `progress parser accepts yt-dlp lines even without library ETA matching`() {
        val downloader = MediaDownloader()

        assertEquals(
            42.7f,
            downloader.progressPercentage(
                "[download]  42.7% of 10.00MiB at 2.00MiB/s ETA 00:03",
                -1f,
            ),
        )
        assertEquals(3L, downloader.progressEtaSeconds("[download] 42.7% ETA 00:03"))
        assertEquals(3723L, downloader.progressEtaSeconds("[download] 42.7% ETA 01:02:03"))
        assertEquals(0f, downloader.progressPercentage("Preparing", -1f))
    }

    @Test
    fun `generated lower resolution uses local FFmpeg downscaling`() {
        val format = AvailableFormat(
            key = "video:18:downscale-144",
            mode = DownloadMode.VIDEO,
            formatId = "18",
            extension = "mkv",
            width = 256,
            height = 144,
            sourceHeight = 360,
            requiresDownscale = true,
        )

        val command = MediaDownloader().buildRequest(
            url = "https://example.test/video",
            format = format,
            outputDirectory = File("build/test-downloads"),
        ).buildCommand()

        assertTrue(command.windowed(2).contains(listOf("--recode-video", "mkv")))
        assertTrue(command.any { it.contains("scale=-2:144") })
    }

    @Test
    fun `quick presets are immediately available and prevent upscaling`() {
        val downloader = MediaDownloader()
        val catalog = downloader.quickFormatCatalog("https://example.test/video")

        assertTrue(catalog.detailsLoading)
        assertTrue(catalog.videoFormats.map { it.height }.containsAll(listOf(2160, 1080, 480, 144)))
        val quick480 = catalog.videoFormats.single { it.height == 480 }
        assertTrue(quick480.isQuickPreset)

        val command = downloader.buildRequest(
            url = catalog.sourceUrl,
            format = quick480,
            outputDirectory = File("build/test-downloads"),
        ).buildCommand()
        assertTrue(command.any { it.contains("min(480\\,ih)") })
    }
}
