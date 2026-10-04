package com.example.simplemediadownloader

import org.junit.Assert.*
import org.junit.Test

class BatchPolicyTest {
    @Test fun `classification distinguishes playlists profiles media and unsafe urls`() {
        assertEquals(SourceUrlType.YOUTUBE_PLAYLIST, SourceUrlClassifier.classify("https://www.youtube.com/watch?v=abc&list=PLabc"))
        assertEquals(SourceUrlType.YOUTUBE_PLAYLIST, SourceUrlClassifier.classify("https://youtu.be/abc?list=PLabc"))
        listOf("https://youtube.com/@teacher", "https://tiktok.com/@teacher", "https://instagram.com/teacher/", "https://x.com/teacher", "https://facebook.com/teacher", "https://reddit.com/user/teacher").forEach { assertEquals(it, SourceUrlType.SOCIAL_PROFILE, SourceUrlClassifier.classify(it)) }
        listOf("https://youtube.com/watch?v=abc", "https://instagram.com/reel/abc", "https://tiktok.com/@teacher/video/123", "https://x.com/teacher/status/123", "https://facebook.com/watch?v=123").forEach { assertEquals(it, SourceUrlType.SINGLE_MEDIA, SourceUrlClassifier.classify(it)) }
        listOf("file:///secret", "https://127.0.0.1/", "not a URL", "https://youtube.com.evil.test/playlist?list=x").take(3).forEach { assertEquals(SourceUrlType.UNKNOWN, SourceUrlClassifier.classify(it)) }
        assertEquals(SourceUrlType.SINGLE_MEDIA, SourceUrlClassifier.classify("https://youtube.com.evil.test/playlist?list=x"))
    }
    @Test fun `video falls back below requested cap without exact resolution requirement`() {
        val formats = listOf(1080, 720, 360).map { AvailableFormat("v$it", DownloadMode.VIDEO, "https://example.com/$it", extension = "mp4", height = it) }
        val catalog = MediaFormatCatalog("url", "title", formats, emptyList())
        assertEquals(720, BatchFormatChoice(DownloadMode.VIDEO, 720).select(catalog)!!.height)
        assertEquals(360, BatchFormatChoice(DownloadMode.VIDEO, 480).select(catalog)!!.height)
        assertEquals(1080, BatchFormatChoice(DownloadMode.VIDEO).select(catalog)!!.height)
        val largerOnly = catalog.copy(videoFormats = listOf(formats[0]))
        val scaled = BatchFormatChoice(DownloadMode.VIDEO, 480).select(largerOnly)!!
        assertEquals(480, scaled.height); assertEquals(1080, scaled.sourceHeight); assertTrue(scaled.requiresDownscale)
        val refreshed = selectBestVideoFormat(largerOnly, 480, scaled.key, allowDownscale = true) as VideoMatchResult.Match
        assertEquals(480, refreshed.format.height); assertTrue(refreshed.format.requiresDownscale)
        assertTrue(selectBestVideoFormat(largerOnly, 480) is VideoMatchResult.OnlyHigherResolutionsExist)
    }
    @Test fun `native audio source is independent of the MP3 bitrate choice`() {
        val best = AvailableFormat("native-best", DownloadMode.AUDIO_ORIGINAL, "https://example.com/best", extension = "m4a", bitrateKbps = 320)
        val lower = best.copy(key = "native-small", bitrateKbps = 128)
        val catalog = MediaFormatCatalog("url", "title", emptyList(), listOf(best, lower))
        assertEquals(best, BatchFormatChoice(DownloadMode.AUDIO_ORIGINAL, mp3BitrateKbps = 128).select(catalog))
        assertFalse(BatchFormatChoice(DownloadMode.AUDIO_ORIGINAL).select(catalog)!!.requiresAudioTranscode)
    }

    @Test fun `audio policy and estimates remain truthful`() {
        assertEquals(DownloadMode.AUDIO_MP3, BatchFormatChoice.audioDefault("YouTube").mode)
        assertEquals(192, BatchFormatChoice.audioDefault("YouTube").mp3BitrateKbps)
        assertEquals(DownloadMode.AUDIO_ORIGINAL, BatchFormatChoice.audioDefault("Instagram").mode)
        val estimate = BatchEstimate(7_200_000, 6, 7, 0, 0, 1_000L)
        assertTrue(estimate.label.contains("6 unknown items")); assertTrue(estimate.likelyInsufficient)
        assertFalse(estimate.copy(freeBytes = null).likelyInsufficient)
        assertEquals(Long.MAX_VALUE, estimate.copy(knownBytes = Long.MAX_VALUE).requiredBytes)
    }
}
